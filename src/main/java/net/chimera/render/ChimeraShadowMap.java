package net.chimera.render;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.PackConfig;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.SamplerInfo;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import net.vulkanmod.vulkan.shader.Uniforms;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the shadow map: framebuffer, light-space matrices, and rendering.
 *
 * The shadow map keeps an RGBA8 color buffer for the fixed host path and a
 * stored depth attachment for pack shadowtex0 sampling. Resolution and light
 * distance are selected once when the pack session is created.
 *
 * Light matrices: ortho projection looking from the sun toward the player.
 * Updated each frame from the celestial angle.
 */
public class ChimeraShadowMap {
    private static final float SHADOW_NEAR = 0.1F;
    private static final float SHADOW_FAR = 512.0F;

    private Framebuffer shadowFramebuffer;
    private RenderPass shadowRenderPass;
    private VulkanImage shadowColor1;
    private java.util.List<VulkanImage> shadowColors = java.util.List.of();
    private GraphicsPipeline shadowPipeline;
    private final DepthSampleView depthSampleView = new DepthSampleView();
    /**
     * Iris shadowtex1: the caster depth before translucent terrain draws.
     * The main depth attachment is shadowtex0 and holds every caster.
     */
    private VulkanImage opaqueDepth;
    private final DepthSampleView opaqueDepthSampleView = new DepthSampleView();
    /** True when opaqueDepth holds the current map's opaque casters. */
    private boolean opaqueDepthCurrent;
    private long shadowSampler;

    // CPU-side buffer for the light MVP, read by the Uniforms system during upload
    private MappedBuffer lightMVPBuffer;

    // Current caster state. Host and pack projections intentionally use
    // different Vulkan clip-depth conventions.
    private final Matrix4f hostLightProjection = new Matrix4f();
    private final Matrix4f packLightProjection = new Matrix4f();
    private final Matrix4f lightView = new Matrix4f();
    private final Matrix4f lightMVP = new Matrix4f();
    private final Vector3f lightDir = new Vector3f();
    private final float[] matrixScratch = new float[16];
    private final Matrix4f receiverScratch = new Matrix4f();
    // Clip X that the fixed receiver maps to u = 1.5, outside the map.
    private static final float OUTSIDE_MAP_X = 2.0F;
    // False while the bound image holds pack-caster depth.
    private boolean hostReadableMap = true;

    // State that describes the depth image currently bound to receivers.
    private final Matrix4f mapLightView = new Matrix4f();
    private final Matrix4f mapPackProjection = new Matrix4f();
    private final Matrix4f samplingView = new Matrix4f();
    private double mapCameraX;
    private double mapCameraY;
    private double mapCameraZ;
    private boolean mapSnapshotValid;
    private long mapGeneration;

    private int shadowMapSize = PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION;
    private float shadowDistance = PackConfig.DEFAULT_SHADOW_DISTANCE;
    private float shadowDistanceRenderMultiplier = 1.0F;

    private boolean initialized;
    /** True only until the newly created images receive their first read layout. */
    private boolean needsInitialSamplingLayout;

    public void init() {
        init(PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION, PackConfig.DEFAULT_SHADOW_DISTANCE);
    }

    public void init(int resolution, float distance) {
        init(new PackConfig.ShadowSettings(resolution, distance, java.util.Map.of(), java.util.List.of()));
    }

    public void init(PackConfig.ShadowSettings settings) {
        if (this.initialized) return;

        this.shadowMapSize = settings.resolution();
        this.shadowDistance = settings.distance();
        this.shadowDistanceRenderMultiplier = settings.distanceRenderMultiplier();
        // The celestial frame derives the light direction from the pack's sun path, so the shadow
        // matrix follows the same numbers the pack reads instead of repeating the trigonometry.
        PackUniformProvider.installSunPath(settings.sunPathRotation(), settings.sunPathOffset());

        this.shadowFramebuffer = new Framebuffer.Builder("chimeraShadow", this.shadowMapSize, this.shadowMapSize, 1, true)
                .setFormat(37) // VK_FORMAT_R8G8B8A8_UNORM
                .build();
        this.shadowColor1 = VulkanImage.builder(this.shadowMapSize, this.shadowMapSize)
                .setName("chimeraShadowColor1")
                .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(true).setClamp(true).createVulkanImage();
        this.shadowColors = java.util.List.of(this.shadowFramebuffer.getColorAttachment(), this.shadowColor1);
        // Receivers sample this D24S8 image; VulkanMod's view carries both aspects.
        VulkanImage depth = this.shadowFramebuffer.getDepthAttachment();
        ChimeraDepthViewOverride.registerOwned(depth, this.depthSampleView.ensure(depth));
        this.opaqueDepth = VulkanImage.builder(this.shadowMapSize, this.shadowMapSize)
                .setName("chimeraShadowOpaqueDepth")
                .setFormat(depth.format)
                .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(false).setClamp(true).createVulkanImage();
        ChimeraDepthViewOverride.registerOwned(this.opaqueDepth,
                this.opaqueDepthSampleView.ensure(this.opaqueDepth));
        this.opaqueDepthCurrent = false;

        createRenderPass();
        this.shadowPipeline = ChimeraPostPipelines.createTerrainPipeline("chimera_shadow", ChimeraTerrainPipelines.getTerrainVertexFormat());
        createSampler();

        // Register the light MVP supplier so the terrain pipeline's LightMVP
        // UBO field is filled with our matrix during upload.
        this.lightMVPBuffer = new MappedBuffer(64);
        Uniforms.mat4f_uniformMap.put("LightMVP", () -> this.lightMVPBuffer);

        this.needsInitialSamplingLayout = true;
        this.mapSnapshotValid = false;
        this.mapGeneration = 0L;
        this.initialized = true;
    }

    private void createRenderPass() {
        RenderPass.Builder b = RenderPass.builder(this.shadowFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
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
     * Builds camera-relative light-space matrices from the frame's celestial light vector.
     * Called each frame before the shadow pass.
     */
    public void updateLight(Vector3f light) {
        this.lightDir.set(light).normalize();

        // DrawBuffers already subtracts the render camera from terrain
        // positions, so the light view must use the same relative origin.
        this.lightView.identity();
        this.lightView.lookAt(
                this.lightDir.x * this.shadowDistance,
                this.lightDir.y * this.shadowDistance,
                this.lightDir.z * this.shadowDistance,
                0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F
        );

        // Ortho projection covering the shadow distance
        float effectiveDistance = this.shadowDistance * this.shadowDistanceRenderMultiplier;
        float halfExtent = effectiveDistance * 0.5F;
        createLightProjection(this.hostLightProjection, halfExtent, effectiveDistance, true);
        createLightProjection(this.packLightProjection, halfExtent, effectiveDistance, false);

        // Combined MVP
        this.lightMVP.set(this.hostLightProjection).mul(this.lightView);
        writeHostReceiverMatrix();
    }

    /**
     * Fills the fixed receiver's LightMVP for the map currently bound. A map
     * written by the pack caster holds authored distortion and clip depth the
     * fixed shader cannot decode, so its receivers get a constant coordinate
     * outside the map, which chimera_terrain.fsh defines as lit. Host fallback
     * programs then match Iris's unshadowed vanilla fallback.
     */
    private void writeHostReceiverMatrix() {
        hostReceiverMatrix().get(this.matrixScratch);
        if (this.lightMVPBuffer != null) {
            this.lightMVPBuffer.buffer.asFloatBuffer().put(this.matrixScratch);
        }
    }

    private void setHostReadableMap(boolean readable) {
        if (this.hostReadableMap == readable) return;
        this.hostReadableMap = readable;
        writeHostReceiverMatrix();
    }

    Matrix4f hostReceiverMatrix() {
        return this.hostReadableMap
                ? this.lightMVP
                : this.receiverScratch.zero().m30(OUTSIDE_MAP_X).m33(1.0F);
    }

    static void createLightProjection(
            Matrix4f destination, float halfExtent, float effectiveDistance, boolean zZeroToOne) {
        destination.identity().ortho(
                -halfExtent, halfExtent,
                -halfExtent, halfExtent,
                SHADOW_NEAR, Math.max(SHADOW_FAR, effectiveDistance * 4.0F),
                zZeroToOne);
    }

    /** Seeds a newly cleared all-lit depth image with a coherent receiver matrix pair. */
    void seedClearedMap(double cameraX, double cameraY, double cameraZ) {
        storeMapState(cameraX, cameraY, cameraZ);
    }

    /** Commits the map state only after its producer and attachment transitions succeed. */
    void commitRenderedMap(double cameraX, double cameraY, double cameraZ) {
        storeMapState(cameraX, cameraY, cameraZ);
        setHostReadableMap(false);
    }

    private void storeMapState(double cameraX, double cameraY, double cameraZ) {
        this.mapLightView.set(this.lightView);
        this.mapPackProjection.set(this.packLightProjection);
        this.mapCameraX = cameraX;
        this.mapCameraY = cameraY;
        this.mapCameraZ = cameraZ;
        this.mapSnapshotValid = true;
        this.needsInitialSamplingLayout = false;
        this.mapGeneration++;
    }

    /**
     * Invalidates the matrix/image association after a partial write. The next
     * frame must clear and reinitialize the image before any pack samples it.
     */
    void invalidateMapSnapshot() {
        this.mapSnapshotValid = false;
        this.needsInitialSamplingLayout = true;
    }

    /** Invalidates pack-facing matrices when the fixed host pipeline wrote the image. */
    void invalidatePackMapSnapshot() {
        this.mapSnapshotValid = false;
        this.opaqueDepthCurrent = false;
        setHostReadableMap(true);
    }

    /**
     * Copies the caster depth drawn so far into shadowtex1. The pack shadow
     * pass calls this after the opaque and cutout layers and before the
     * translucent layer, which Iris keeps out of shadowtex1. No render pass
     * may be open; the depth attachment returns to its attachment layout.
     */
    void captureOpaqueDepth(VkCommandBuffer commandBuffer) {
        VulkanImage depth = this.shadowFramebuffer.getDepthAttachment();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            copyDepth(stack, commandBuffer, depth, this.opaqueDepth);
            depth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }
        this.opaqueDepthCurrent = true;
    }

    private static void copyDepth(MemoryStack stack, VkCommandBuffer commandBuffer,
            VulkanImage source, VulkanImage destination) {
        source.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        destination.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
        copy.srcSubresource().aspectMask(source.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
        copy.dstSubresource().aspectMask(destination.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
        copy.extent().set(source.width, source.height, 1);
        vkCmdCopyImage(commandBuffer, source.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                destination.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
        destination.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    /**
     * The image packs sample as shadowtex1. Without a pack caster pass the
     * map holds no translucent casters, so shadowtex0 already is shadowtex1.
     */
    public VulkanImage opaqueDepth() {
        if (this.shadowFramebuffer == null) return null;
        return this.opaqueDepthCurrent ? this.opaqueDepth : this.shadowFramebuffer.getDepthAttachment();
    }

    /** Publishes the previous map's matrices expressed relative to this frame's camera. */
    void publishMapState(double cameraX, double cameraY, double cameraZ) {
        if (!this.mapSnapshotValid) {
            return;
        }
        Matrix4f view = mapViewAt(cameraX, cameraY, cameraZ);
        PackUniformProvider.updateShadowState(view, this.mapPackProjection);
    }

    Matrix4f mapViewAt(double cameraX, double cameraY, double cameraZ) {
        if (!this.mapSnapshotValid) {
            return null;
        }
        return compensatedView(this.samplingView, this.mapLightView,
                this.mapCameraX, this.mapCameraY, this.mapCameraZ,
                cameraX, cameraY, cameraZ);
    }

    /** Publishes the current caster pair only while its shadow draw is active. */
    void publishCurrentDrawState() {
        PackUniformProvider.updateShadowState(this.lightView, this.packLightProjection);
    }

    static Matrix4f compensatedView(
            Matrix4f destination,
            Matrix4f mapView,
            double mapX, double mapY, double mapZ,
            double currentX, double currentY, double currentZ
    ) {
        // Subtract in world-space double precision before storing the local delta.
        return destination.set(mapView).translate(
                (float) (currentX - mapX),
                (float) (currentY - mapY),
                (float) (currentZ - mapZ));
    }

    boolean hasMapSnapshot() {
        return this.mapSnapshotValid;
    }

    long mapGeneration() {
        return this.mapGeneration;
    }

    /** Binds the shadow map texture for sampling by the terrain shader. */
    public void bindShadowTexture() {
        if (this.shadowFramebuffer == null) return;
        VulkanImage shadowColor = this.shadowFramebuffer.getColorAttachment();
        VulkanImage shadowDepth = this.shadowFramebuffer.getDepthAttachment();
        shadowColor.setSampler(this.shadowSampler);
        shadowDepth.setSampler(this.shadowSampler);
        this.opaqueDepth.setSampler(this.shadowSampler);
        VTextureSelector.bindTexture(3, shadowColor); // slot 3 for shadow map
        VTextureSelector.bindTexture(5, shadowDepth); // slot 5 for pack shadowtex0
    }

    public VulkanImage shadowColor(int index) {
        return index >= 0 && index < this.shadowColors.size() ? this.shadowColors.get(index) : null;
    }

    public java.util.List<VulkanImage> shadowColors() { return this.shadowColors; }

    /** Initial contents must be defined before geometry samples the first shadow frame. */
    public void initializeSampling(VkCommandBuffer commandBuffer) {
        VulkanImage color = this.shadowFramebuffer.getColorAttachment();
        VulkanImage depth = this.shadowFramebuffer.getDepthAttachment();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            depth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }
        PackGeometryContext.beginShadow(this.shadowColors, this.shadowFramebuffer.getDepthAttachment());
        try {
            Renderer.getInstance().beginRenderPass(this.shadowRenderPass, this.shadowFramebuffer);
            Renderer.getInstance().endRenderPass(commandBuffer);
        } finally {
            PackGeometryContext.close();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            depth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        // The cleared map has no translucent casters: shadowtex1 is shadowtex0.
        this.opaqueDepthCurrent = false;
        this.needsInitialSamplingLayout = false;
    }

    /** Forward [0,1] projection for the fixed VulkanMod fallback pipeline. */
    public Matrix4f getHostLightProjection() {
        return this.hostLightProjection;
    }

    /** Legacy [-1,1] projection for converted pack shadow programs. */
    public Matrix4f getPackLightProjection() {
        return this.packLightProjection;
    }

    public Matrix4f getLightView() {
        return this.lightView;
    }

    public GraphicsPipeline getShadowPipeline() {
        return this.shadowPipeline;
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    public boolean needsInitialSamplingLayout() {
        return this.needsInitialSamplingLayout;
    }

    public void markInitialSamplingLayoutReady() {
        this.needsInitialSamplingLayout = false;
    }

    public Framebuffer getShadowFramebuffer() {
        return this.shadowFramebuffer;
    }

    public int getShadowMapSize() {
        return this.shadowMapSize;
    }

    public float getShadowDistance() {
        return this.shadowDistance;
    }

    public RenderPass getShadowRenderPass() {
        return this.shadowRenderPass;
    }

    public void cleanUp() {
        if (this.shadowFramebuffer != null) {
            ChimeraDepthViewOverride.unregisterOwned(this.shadowFramebuffer.getDepthAttachment());
        }
        if (this.opaqueDepth != null) {
            ChimeraDepthViewOverride.unregisterOwned(this.opaqueDepth);
        }
        this.depthSampleView.destroy();
        this.opaqueDepthSampleView.destroy();
        if (this.opaqueDepth != null) this.opaqueDepth.free();
        this.opaqueDepth = null;
        this.opaqueDepthCurrent = false;
        if (this.shadowFramebuffer != null) this.shadowFramebuffer.cleanUp(true);
        if (this.shadowRenderPass != null) this.shadowRenderPass.cleanUp();
        if (this.shadowPipeline != null) this.shadowPipeline.cleanUp();
        if (this.shadowColor1 != null) this.shadowColor1.free();
        this.shadowColor1 = null;
        this.shadowColors = java.util.List.of();
        this.shadowFramebuffer = null;
        this.shadowRenderPass = null;
        this.shadowPipeline = null;
        this.needsInitialSamplingLayout = false;
        this.mapSnapshotValid = false;
        this.hostReadableMap = true;
        this.mapGeneration = 0L;
        this.initialized = false;
    }
}
