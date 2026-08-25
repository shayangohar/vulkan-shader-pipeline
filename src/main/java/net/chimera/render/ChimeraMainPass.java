package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Chimera's main render pass — two-buffer segmented frame:
 *
 *   - Nothing opens at frame start.
 *   - renderLevel HEAD opens the HDR segment (world -> RGBA16F).
 *   - renderLevel TAIL closes the composite... actually keeps the pass OPEN:
 *     hand and GUI draw into the same HDR buffer via rebindMainTarget.
 *   - MainPass.end: close HDR -> present HDR directly to swapchain via a
 *     fullscreen passthrough.
 *
 * No intermediate final buffer, no phase flip, no composite pass. One target,
 * one post pass at frame end. GUI values are LDR and stored correctly in
 * RGBA16F. When a post-processing stack is needed later, a composite pass
 * slots in between the HDR close and the present inside end().
 */
public class ChimeraMainPass implements MainPass {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("chimera");

    private Framebuffer hdrFramebuffer;
    private RenderPass hdrRenderPass;
    private RenderPass hdrAuxRenderPass;
    private RenderPass hdrAuxClearDepthRenderPass;
    private RenderPass presentRenderPass;

    private GraphicsPipeline presentPipeline;

    private ChimeraShadowMap shadowMap = new ChimeraShadowMap();

    /** True while inside the level segment (HDR pass recording). */
    private boolean levelPhase;
    /** Set when vanilla requests a depth clear while no pass is recording. */
    private boolean pendingDepthClear;

    private GpuTexture hdrColorTexture;
    private GpuTextureView hdrColorTextureView;
    private GpuTexture hdrDepthTexture;
    private GpuTextureView hdrDepthTextureView;

    public ChimeraMainPass() {
        this.levelPhase = true;
        createResources();
        this.shadowMap.init();
        Renderer.getInstance().addOnResizeCallback(this::onResize);
    }

    /**
     * Renders the shadow map: re-renders terrain from the light's perspective
     * into the shadow framebuffer, then binds the shadow texture for sampling.
     * Called at renderLevel HEAD, before the HDR segment opens.
     */
    public void renderShadowMap() {
        if (!this.shadowMap.isInitialized()) return;

        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        VkCommandBuffer cmd = Renderer.getCommandBuffer();

        // Compute light matrices
        Vec3 playerPos = mc.player.position();
        // TODO: compute from level time — fixed noon angle for M3 shadow testing
        float celestialAngle = 0.25F;
        this.shadowMap.updateLight(celestialAngle, playerPos);

        // Switch to the shadow pipeline
        PipelineManager.setShaderGetter(rt -> this.shadowMap.getShadowPipeline());

        // Set VRenderSystem to light-space matrices (fills the MVP UBO)
        VRenderSystem.applyProjectionMatrix(this.shadowMap.getLightProjection());
        VRenderSystem.applyModelViewMatrix(this.shadowMap.getLightView());
        VRenderSystem.calculateMVP();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Open shadow render pass on the shadow framebuffer
            VulkanImage shadowColor = this.shadowMap.getShadowFramebuffer().getColorAttachment();
            shadowColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            Renderer.getInstance().beginRenderPass(this.shadowMap.getShadowRenderPass(), this.shadowMap.getShadowFramebuffer());
            Renderer.setViewport(0, 0, ChimeraShadowMap.getSize(), ChimeraShadowMap.getSize(), stack);
            VK10.vkCmdSetScissor(cmd, 0, this.shadowMap.getShadowFramebuffer().scissor(stack));
            Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            // Render terrain from the light's perspective
            // The shader getter returns the shadow pipeline, so terrain draws
            // use the light-space MVP. The vertex data and section offsets
            // are shared with the main pass.
            WorldRenderer worldRenderer = WorldRenderer.getInstance();
            worldRenderer.renderSectionLayer(
                    TerrainRenderType.SOLID,
                    playerPos.x, playerPos.y, playerPos.z,
                    this.shadowMap.getLightView(),
                    this.shadowMap.getLightProjection()
            );

            // Close shadow render pass
            Renderer.getInstance().endRenderPass(cmd);

            // Transition shadow map for sampling
            shadowColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        // Restore terrain pipeline getter
        PipelineManager.setShaderGetter(rt -> ChimeraTerrainPipelines.getTerrainPipeline());

        // Bind shadow texture for terrain fragment shader sampling
        this.shadowMap.bindShadowTexture();
    }

    // ------------------------------------------------------------------
    // Segment control
    // ------------------------------------------------------------------

    public void openLevelSegment() {
        if (this.hdrFramebuffer == null) {
            return;
        }

        // Render shadow map before opening HDR
        renderShadowMap();

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            Renderer.getInstance().beginRenderPass(this.hdrRenderPass, this.hdrFramebuffer);

            Renderer.setViewport(0, 0, this.hdrFramebuffer.getWidth(), this.hdrFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.hdrFramebuffer.scissor(stack));

            Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            this.levelPhase = true;
            this.pendingDepthClear = false;
        }
    }

    // ------------------------------------------------------------------
    // MainPass contract
    // ------------------------------------------------------------------

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.levelPhase = false;
        this.pendingDepthClear = false;
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        // Close the HDR pass (or whatever aux pass is open from hand/GUI).
        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VTextureSelector.bindTexture(hdrColor);

            VRenderSystem.disableDepthTest();
            VRenderSystem.disableCull();
            VRenderSystem.disableBlend();

            SwapChain swapChain = Renderer.getInstance().getSwapChain();
            if (swapChain.hasImages()) {
                swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                Renderer.getInstance().beginRenderPass(this.presentRenderPass, swapChain);
                drawFullscreen(commandBuffer, this.presentPipeline);
                Renderer.getInstance().endRenderPass(commandBuffer);

                swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            }
        }

        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer: " + result);
        }
    }

    private void drawFullscreen(VkCommandBuffer commandBuffer, GraphicsPipeline pipeline) {
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
        VK10.vkCmdDraw(commandBuffer, 3, 1, 0, 0);
    }

    // ------------------------------------------------------------------
    // Main-target interop
    // ------------------------------------------------------------------

    @Override
    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Framebuffer target = this.hdrFramebuffer;

        if (Renderer.getInstance().getBoundFramebuffer() == target) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            target.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }

        RenderPass pass = this.pendingDepthClear ? this.hdrAuxClearDepthRenderPass : this.hdrAuxRenderPass;
        this.pendingDepthClear = false;
        Renderer.getInstance().beginRenderPass(pass, target);
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        VulkanImage color = this.hdrFramebuffer.getColorAttachment();

        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().endRenderPass(commandBuffer);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(color);
    }

    @Override
    public Framebuffer getMainFramebuffer() {
        return this.hdrFramebuffer;
    }

    @Override
    public GpuTexture getColorAttachment() {
        return this.hdrColorTexture;
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.hdrColorTextureView;
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.hdrDepthTexture;
    }

    /** Records a depth clear to apply at the next level-phase reopen. */
    public void requestPendingDepthClear() {
        this.pendingDepthClear = true;
    }

    /** True when the view belongs to chimera's HDR buffer. */
    public boolean isFamilyView(GpuTextureView view) {
        return view == this.hdrColorTextureView;
    }

    /** The live main-target color texture for the current phase. */
    public GpuTexture currentMainColorTexture() {
        return this.hdrColorTexture;
    }

    /**
     * Re-enters the HDR buffer applying any pending depth clear.
     */
    public void reopenWithPendingClear() {
        if (!this.levelPhase) {
            return;
        }
        this.requestPendingDepthClear();
        this.rebindMainTarget();
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    @Override
    public void cleanUp() {
        this.shadowMap.cleanUp();
        cleanUpFramebuffersAndPasses();
        cleanUpPipelines();
    }

    @Override
    public void onResize() {
        createResources();
    }

    private void createResources() {
        cleanUpFramebuffersAndPasses();
        cleanUpPipelines();

        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        int width = Math.max(swapChain.getWidth(), 1);
        int height = Math.max(swapChain.getHeight(), 1);

        // 97 = VK_FORMAT_R16G16B16A16_SFLOAT
        this.hdrFramebuffer = new Framebuffer.Builder("chimeraHdr", width, height, 1, true)
                .setFormat(97)
                .build();

        createRenderPasses();
        createPipelines();
        createInteropTextures();
    }

    private void createRenderPasses() {
        // HDR segment: cleared at open, color stored for the present read.
        RenderPass.Builder b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.hdrRenderPass = b.build();

        // Re-entry (vanilla passes alias here): preserve everything so far.
        b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.hdrAuxRenderPass = b.build();

        // Re-entry with depth clear (hand rendering after vanilla's request).
        b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.hdrAuxClearDepthRenderPass = b.build();

        // Present onto the swapchain.
        b = RenderPass.builder(Renderer.getInstance().getSwapChain());
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.presentRenderPass = b.build();
    }

    private void createPipelines() {
        this.presentPipeline = ChimeraPostPipelines.create("chimera_present");
    }

    private void createInteropTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();

        VkGpuTexture hdrTex = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getColorAttachment());
        this.hdrColorTexture = hdrTex;
        this.hdrColorTextureView = device.createTextureView(hdrTex);
        this.hdrDepthTexture = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getDepthAttachment());
        this.hdrDepthTextureView = device.createTextureView(this.hdrDepthTexture);
    }

    private void cleanUpFramebuffersAndPasses() {
        if (this.hdrFramebuffer != null) this.hdrFramebuffer.cleanUp(true);
        if (this.hdrRenderPass != null) this.hdrRenderPass.cleanUp();
        if (this.hdrAuxRenderPass != null) this.hdrAuxRenderPass.cleanUp();
        if (this.hdrAuxClearDepthRenderPass != null) this.hdrAuxClearDepthRenderPass.cleanUp();
        if (this.presentRenderPass != null) this.presentRenderPass.cleanUp();
        this.hdrFramebuffer = null;
        this.hdrRenderPass = null;
        this.hdrAuxRenderPass = null;
        this.hdrAuxClearDepthRenderPass = null;
        this.presentRenderPass = null;
    }

    private void cleanUpPipelines() {
        if (this.presentPipeline != null) this.presentPipeline.cleanUp();
        this.presentPipeline = null;
    }
}
