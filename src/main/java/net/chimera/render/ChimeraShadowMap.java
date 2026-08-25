package net.chimera.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.SamplerInfo;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the shadow map: framebuffer, light-space matrices, and rendering.
 *
 * The shadow map is a 2048x2048 RGBA8 color buffer where the shadow pipeline
 * writes gl_FragCoord.z. The terrain fragment shader samples this texture to
 * determine shadow occlusion.
 *
 * Light matrices: ortho projection looking from the sun toward the player.
 * Updated each frame from the celestial angle.
 */
public class ChimeraShadowMap {
    private static final int SHADOW_MAP_SIZE = 2048;
    private static final float SHADOW_DISTANCE = 128.0F;
    private static final float SHADOW_NEAR = 0.1F;
    private static final float SHADOW_FAR = 512.0F;

    private Framebuffer shadowFramebuffer;
    private RenderPass shadowRenderPass;
    private GraphicsPipeline shadowPipeline;
    private long shadowSampler;

    // Light-space matrices (updated each frame)
    private final Matrix4f lightProjection = new Matrix4f();
    private final Matrix4f lightView = new Matrix4f();
    private final Matrix4f lightMVP = new Matrix4f();
    private final Vector3f lightDir = new Vector3f();

    private boolean initialized;

    public void init() {
        if (this.initialized) return;

        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        // Shadow map size is fixed, not tied to window size
        this.shadowFramebuffer = new Framebuffer.Builder("chimeraShadow", SHADOW_MAP_SIZE, SHADOW_MAP_SIZE, 1, true)
                .setFormat(37) // VK_FORMAT_R8G8B8A8_UNORM
                .build();

        createRenderPass();
        this.shadowPipeline = ChimeraPostPipelines.createTerrainPipeline("chimera_shadow", ChimeraTerrainPipelines.getTerrainVertexFormat());
        createSampler();

        this.initialized = true;
    }

    private void createRenderPass() {
        RenderPass.Builder b = RenderPass.builder(this.shadowFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.shadowRenderPass = b.build();
    }

    private void createSampler() {
        this.shadowSampler = SamplerManager.createTextureSampler(
                SamplerInfo.builder()
                        .setAddressMode(2) // CLAMP_TO_BORDER
                        .setFiltering(1, 1, 1)
                        .createSamplerInfo()
        );
    }

    /**
     * Computes the light-space matrices from the celestial angle.
     * Called each frame before the shadow pass.
     */
    public void updateLight(float celestialAngle, Vec3 cameraPos) {
        // Sun direction: rotates around the X axis based on celestial angle
        // At celestialAngle=0 (noon), sun is overhead
        float sunAngleRad = celestialAngle * (float) Math.PI * 2.0F;
        float sunX = (float) Math.sin(sunAngleRad);
        float sunY = (float) Math.cos(sunAngleRad);
        float sunZ = 0.0F;

        // At night, use moon direction (opposite the sun)
        if (celestialAngle > 0.5F) {
            sunX = -sunX;
            sunY = -sunY;
        }

        this.lightDir.set(sunX, sunY, sunZ).normalize();

        // Light view: look from sun toward the player position
        Vec3 eyePos = new Vec3(
                cameraPos.x + this.lightDir.x * SHADOW_DISTANCE,
                cameraPos.y + this.lightDir.y * SHADOW_DISTANCE,
                cameraPos.z + this.lightDir.z * SHADOW_DISTANCE
        );

        this.lightView.identity();
        this.lightView.lookAt(
                (float) eyePos.x, (float) eyePos.y, (float) eyePos.z,
                (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z,
                0.0F, 1.0F, 0.0F
        );

        // Ortho projection covering the shadow distance
        float halfExtent = SHADOW_DISTANCE * 0.5F;
        this.lightProjection.identity();
        this.lightProjection.ortho(
                -halfExtent, halfExtent,
                -halfExtent, halfExtent,
                SHADOW_NEAR, SHADOW_FAR
        );

        // Combined MVP
        this.lightMVP.set(this.lightProjection).mul(this.lightView);
    }

    /**
     * Renders the shadow map: opens the shadow render pass, sets the light MVP,
     * and calls the shadow render callback (which issues terrain draws).
     */
    public void renderShadowMap(VkCommandBuffer commandBuffer, MemoryStack stack, Runnable terrainDrawCallback) {
        if (!this.initialized || this.shadowFramebuffer == null) return;

        // Transition shadow color to attachment layout
        VulkanImage shadowColor = this.shadowFramebuffer.getColorAttachment();
        shadowColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        // Open shadow render pass
        Renderer.getInstance().beginRenderPass(this.shadowRenderPass, this.shadowFramebuffer);
        Renderer.setViewport(0, 0, SHADOW_MAP_SIZE, SHADOW_MAP_SIZE, stack);
        VK10.vkCmdSetScissor(commandBuffer, 0, this.shadowFramebuffer.scissor(stack));
        Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        // Set the light-space MVP via RenderSystem (fills the shared MVP UBO)
        VRenderSystem.applyProjectionMatrix(this.lightProjection);
        VRenderSystem.applyModelViewMatrix(this.lightView);
        VRenderSystem.calculateMVP();

        // Issue terrain draws (callback into the shadow render logic)
        terrainDrawCallback.run();

        // Close shadow render pass
        Renderer.getInstance().endRenderPass(commandBuffer);
    }

    /** Binds the shadow map texture for sampling by the terrain shader. */
    public void bindShadowTexture() {
        if (this.shadowFramebuffer == null) return;
        VulkanImage shadowColor = this.shadowFramebuffer.getColorAttachment();
        shadowColor.setSampler(this.shadowSampler);
        VTextureSelector.bindTexture(3, shadowColor); // slot 3 for shadow map
    }

    public Matrix4f getLightMVP() {
        return this.lightMVP;
    }

    public Vector3f getLightDir() {
        return this.lightDir;
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    public static int getSize() {
        return SHADOW_MAP_SIZE;
    }

    public Framebuffer getShadowFramebuffer() {
        return this.shadowFramebuffer;
    }

    public RenderPass getShadowRenderPass() {
        return this.shadowRenderPass;
    }

    public void onResize() {
        // Shadow map size is fixed; no resize needed
    }

    public void cleanUp() {
        if (this.shadowFramebuffer != null) this.shadowFramebuffer.cleanUp(true);
        if (this.shadowRenderPass != null) this.shadowRenderPass.cleanUp();
        if (this.shadowPipeline != null) this.shadowPipeline.cleanUp();
        this.shadowFramebuffer = null;
        this.shadowRenderPass = null;
        this.shadowPipeline = null;
        this.initialized = false;
    }
}
