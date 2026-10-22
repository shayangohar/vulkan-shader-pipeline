package net.chimera.render;

import net.chimera.mixin.ChimeraVulkanImageAccessor;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.PackConfig;
import net.chimera.shaderpack.ShadowTargetDirectives;
import net.chimera.shaderpack.TargetSpec;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the shadow map: framebuffer, light-space matrices, and rendering.
 *
 * The shadow map keeps an RGBA8 color buffer for the fixed host path, the pack's shadowcolor
 * targets in their directive formats (Iris ShadowRenderTargets), and a
 * stored depth attachment for pack shadowtex0 sampling. Resolution and light
 * distance are selected once when the pack session is created.
 *
 * Light matrices: ortho projection looking from the sun toward the player.
 * Updated each frame from the celestial angle.
 */
public class ChimeraShadowMap {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    private Framebuffer shadowFramebuffer;
    private RenderPass shadowRenderPass;
    /** The pack's shadowcolor directives; Iris's defaults until a pack session sets them. */
    private ShadowTargetDirectives targets = ShadowTargetDirectives.defaults();
    private List<VulkanImage> shadowColors = List.of();
    /** The formats pass computes write each shadowcolor target in, none for one they only sample. */
    private Map<Integer, Set<Integer>> computeTargets = Map.of();
    /**
     * The shadowcolor targets only computes name, past those the shadow pass draws. Iris creates a
     * target a compute names on first use (ShadowRenderTargets.getOrCreate) and clears it with the
     * rest before the shadow pass (ClearPassCreator).
     */
    private Map<Integer, VulkanImage> computeColors = Map.of();
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

    // The pack's settings: its depth range follows the render distance when a plane is -1.
    private PackConfig.ShadowSettings settings = new PackConfig.ShadowSettings(
            PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION, PackConfig.DEFAULT_SHADOW_DISTANCE, Map.of(), List.of());
    private int shadowMapSize = PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION;
    private float shadowDistance = PackConfig.DEFAULT_SHADOW_DISTANCE;
    private float intervalSize = PackConfig.DEFAULT_SHADOW_INTERVAL_SIZE;

    private boolean initialized;
    /** True only until the newly created images receive their first read layout. */
    private boolean needsInitialSamplingLayout;

    public void init() {
        init(PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION, PackConfig.DEFAULT_SHADOW_DISTANCE);
    }

    public void init(int resolution, float distance) {
        init(new PackConfig.ShadowSettings(resolution, distance, Map.of(), List.of()));
    }

    public void init(PackConfig.ShadowSettings settings) {
        init(settings, Map.of());
    }

    /** As {@link #init(PackConfig.ShadowSettings)}, with the shadowcolor targets pass computes bind. */
    public void init(PackConfig.ShadowSettings settings, Map<Integer, Set<Integer>> computeTargets) {
        if (this.initialized) return;
        this.computeTargets = Map.copyOf(computeTargets);

        this.settings = settings;
        this.shadowMapSize = deviceResolution(settings.resolution());
        this.shadowDistance = settings.distance();
        this.intervalSize = settings.intervalSize();
        // The celestial frame derives the light direction from the pack's sun path, so the shadow
        // matrix follows the same numbers the pack reads instead of repeating the trigonometry.
        PackUniformProvider.installSunPath(settings.sunPathRotation(), settings.sunPathOffset());

        try {
            createResources(settings);
        } catch (RuntimeException failure) {
            // Frees whatever was made before the failure; the map stays uninitialized.
            cleanUp();
            throw failure;
        }

        // Register the light MVP supplier so the terrain pipeline's LightMVP
        // UBO field is filled with our matrix during upload.
        this.lightMVPBuffer = new MappedBuffer(64);
        Uniforms.mat4f_uniformMap.put("LightMVP", () -> this.lightMVPBuffer);

        this.needsInitialSamplingLayout = true;
        this.mapSnapshotValid = false;
        this.mapGeneration = 0L;
        this.initialized = true;
    }

    /** The shadow framebuffer, colour targets, opaque depth copy, render pass, pipeline and sampler. */
    private void createResources(PackConfig.ShadowSettings settings) {
        this.shadowFramebuffer = Framebuffer.builder(this.shadowMapSize, this.shadowMapSize, 1, true)
                .setFormat(37) // VK_FORMAT_R8G8B8A8_UNORM
                .build();
        this.targets = settings.targets();
        this.shadowColors = createColorTargets(settings.colorTargetCount());
        this.computeColors = createComputeColors(settings.colorTargetCount());
        // Receivers sample this D24S8 image; VulkanMod's view carries both aspects.
        VulkanImage depth = this.shadowFramebuffer.getDepthAttachment();
        ChimeraDepthViewOverride.registerOwned(depth, this.depthSampleView.ensure(depth));
        this.opaqueDepth = VulkanImage.builder(this.shadowMapSize, this.shadowMapSize)
                .setFormat(depth.format)
                .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(false).setClamp(true).createVulkanImage();
        ChimeraDepthViewOverride.registerOwned(this.opaqueDepth,
                this.opaqueDepthSampleView.ensure(this.opaqueDepth));
        this.opaqueDepthCurrent = false;

        createRenderPass();
        this.shadowPipeline = ChimeraPostPipelines.createTerrainPipeline("chimera_shadow",
                ChimeraTerrainPipelines.getTerrainVertexFormat());
        createSampler();
    }

    /**
     * The pack's shadowcolor targets, each in its directive's format, filter and mip chain (Iris
     * ShadowRenderTargets and ShadowRenderer.configureSampler). A target's mips build after each
     * shadow pass; until the first, it samples its base level.
     */
    private List<VulkanImage> createColorTargets(int count) {
        List<TargetSpec> specs = new ArrayList<>();
        for (int index = 0; index < count; index++) specs.add(colorSpec(index));
        PackTargetImages.verifyFormats(specs);
        List<VulkanImage> images = new ArrayList<>();
        try {
            specs.forEach(spec -> images.add(createColorTarget(spec)));
        } catch (RuntimeException failure) {
            images.forEach(TargetFormatViews::free);
            throw failure;
        }
        return List.copyOf(images);
    }

    /** The compute-named shadowcolor targets from index {@code drawn} on, which the shadow pass does not draw. */
    private Map<Integer, VulkanImage> createComputeColors(int drawn) {
        List<TargetSpec> specs = this.computeTargets.keySet().stream().filter(index -> index >= drawn)
                .map(this::colorSpec).toList();
        PackTargetImages.verifyFormats(specs);
        Map<Integer, VulkanImage> images = new TreeMap<>();
        try {
            specs.forEach(spec -> images.put(spec.index(), createColorTarget(spec)));
        } catch (RuntimeException failure) {
            images.values().forEach(TargetFormatViews::free);
            throw failure;
        }
        return images;
    }

    private TargetSpec colorSpec(int index) {
        ShadowTargetDirectives.ColorTarget target = this.targets.color(index);
        return new TargetSpec(index, target.format().vkFormat(), this.shadowMapSize, this.shadowMapSize,
                target.clear(), target.clearColor(), false, false, target.mipmap(),
                target.format().paddedAlpha(), List.of());
    }

    /** A shadowcolor target, storable in the formats computes write it in (PackTargetImages). */
    private VulkanImage createColorTarget(TargetSpec spec) {
        VulkanImage image = PackTargetImages.createTargetImage(spec,
                this.computeTargets.getOrDefault(spec.index(), Set.of()));
        if (this.targets.color(spec.index()).nearestFiltering()) {
            PackTargetImages.sampleWith(image, ChimeraSamplers.flat(false, true));
        }
        return image;
    }

    /**
     * Clears the compute-named shadowcolor targets as the frame opens (PackShadowPass, before setup
     * and begin, as Iris clears its shadow targets in beginLevelRendering), each that clears every
     * frame or all of them on the full clear after creation (colorClears), and leaves them sampleable.
     * Recorded outside a render pass.
     */
    void clearComputeColors(MemoryStack stack, VkCommandBuffer commandBuffer, boolean fullClear) {
        this.computeColors.forEach((index, image) -> {
            ShadowTargetDirectives.ColorTarget target = this.targets.color(index);
            if (fullClear || target.clear()) {
                PackTargetImages.clearImage(stack, commandBuffer, image, target.clearColor());
            }
        });
    }

    /**
     * The clear colour of each shadowcolor target as the shadow pass opens: its own for a target
     * that clears every frame, or for every target on the full clear after creation (Iris
     * ClearPassCreator.createShadowClearPasses); null keeps the last frame's contents.
     */
    List<float[]> colorClears(boolean fullClear) {
        List<float[]> clears = new ArrayList<>();
        for (int index = 0; index < this.shadowColors.size(); index++) {
            ShadowTargetDirectives.ColorTarget target = this.targets.color(index);
            clears.add(fullClear || target.clear() ? target.clearColor() : null);
        }
        return clears;
    }

    /**
     * Makes the pack's shadowcolor targets sampleable after the shadow pass, building the mip
     * chain of each target that asks for one (Iris ShadowRenderer.generateMipmaps):
     * linear-mipmap-linear, or nearest-mipmap-nearest for a nearest target.
     */
    void publishColorTargets(MemoryStack stack, VkCommandBuffer commandBuffer) {
        for (int index = 0; index < this.shadowColors.size(); index++) {
            VulkanImage image = this.shadowColors.get(index);
            ShadowTargetDirectives.ColorTarget target = this.targets.color(index);
            if (image.mipLevels > 1) {
                PackTargetImages.buildMipChain(stack, commandBuffer, image);
                PackTargetImages.sampleWith(image,
                        ChimeraSamplers.mipmapped(image.mipLevels - 1, !target.nearestFiltering()));
            } else {
                image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        }
    }

    /** Whether a shadow depth, 0 for shadowtex0 and 1 for shadowtex1, samples nearest (shadowtexNNearest). */
    boolean depthNearest(int index) {
        return this.targets.depth(index).nearest();
    }

    private void createRenderPass() {
        RenderPass.Builder b = RenderPass.builder(this.shadowFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        this.shadowRenderPass = b.build();
    }

    private void createSampler() {
        this.shadowSampler = ChimeraSamplers.flat(true, true);
    }

    /**
     * Builds camera-relative light-space matrices from the frame's celestial shadow rotation.
     * Called each frame before the shadow pass.
     */
    public void updateLight(Matrix4f lightRotation, double cameraX, double cameraY, double cameraZ,
                            int renderDistanceChunks) {
        // DrawBuffers already subtracts the render camera from terrain
        // positions, so the light view is Iris's rotation about that origin,
        // snapped to its grid; the ortho depth range reaches toward the sun.
        this.lightView.set(lightRotation);
        snapToGrid(this.lightView, this.intervalSize, cameraX, cameraY, cameraZ);

        // Iris's halfPlaneLength is the pack's shadowDistance itself.
        float near = this.settings.nearPlane(renderDistanceChunks);
        float far = this.settings.farPlane(renderDistanceChunks);
        createLightProjection(this.hostLightProjection, this.shadowDistance, near, far, true);
        createLightProjection(this.packLightProjection, this.shadowDistance, near, far, false);

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

    /**
     * Iris's {@code ShadowMatrices.snapModelViewToGrid}: shifts the camera-relative view to the
     * centre of the grid cell the camera is in, so the shadow texels stay fixed in the world while
     * the player moves. Without it every caster edge, and above all a thin one like a glass pane's
     * frame, slides and changes width with the camera. Iris keeps Java's truncating remainder
     * (negative for negative coordinates) and so does this; the remainder is taken in double
     * because Iris's float cast loses the sub-block phase far from the origin.
     */
    static void snapToGrid(Matrix4f view, float intervalSize,
            double cameraX, double cameraY, double cameraZ) {
        if (Math.abs(intervalSize) == 0.0F) return;
        float half = intervalSize / 2.0F;
        view.translate(
                (float) (cameraX % intervalSize) - half,
                (float) (cameraY % intervalSize) - half,
                (float) (cameraZ % intervalSize) - half);
    }

    /**
     * Iris ShadowMatrices.createOrthoMatrix: a square of half the pack's shadowDistance on each
     * side, between the pack's depth planes. Packs tune their shadow depth bias to this range.
     */
    static void createLightProjection(
            Matrix4f destination, float halfExtent, float near, float far, boolean zZeroToOne) {
        destination.identity().ortho(
                -halfExtent, halfExtent,
                -halfExtent, halfExtent,
                near, far,
                zZeroToOne);
    }

    /** The pack's resolution within the device's image limit; Iris passes it to GL unclamped. */
    private static int deviceResolution(int resolution) {
        int limit = ChimeraMainPass.deviceMaxImageDimension();
        if (resolution > limit) {
            LOGGER.warn("[chimera] shadow map resolution {} exceeds the device limit; using {}", resolution, limit);
        }
        return Math.min(resolution, limit);
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

    /** Clears a sampled depth image to the far plane and leaves it shader-readable. */
    private static void clearToFarDepth(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image) {
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkClearDepthStencilValue far = VkClearDepthStencilValue.calloc(stack).depth(1.0F).stencil(0);
        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack).aspectMask(image.aspect)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdClearDepthStencilImage(commandBuffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, far, range);
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
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

    /**
     * shadowtex1 for a shadow program: always the copy, never the depth attachment the pass draws
     * into. As Iris copies it before the translucent layer (ShadowRenderer copyPreTranslucentDepth),
     * casters read last frame's copy and the translucent layer this frame's.
     */
    public VulkanImage opaqueDepthCopy() {
        return this.shadowFramebuffer == null ? null : this.opaqueDepth;
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
        ((ChimeraVulkanImageAccessor) shadowColor).chimera$setSampler(this.shadowSampler);
        // Iris filters each shadow depth linearly unless shadowtexNNearest (ShadowRenderer.configureSampler).
        ((ChimeraVulkanImageAccessor) shadowDepth).chimera$setSampler(ChimeraSamplers.flat(!depthNearest(0), true));
        ((ChimeraVulkanImageAccessor) this.opaqueDepth).chimera$setSampler(
                ChimeraSamplers.flat(!depthNearest(1), true));
        VTextureSelector.bindTexture(3, shadowColor); // slot 3 for shadow map
        VTextureSelector.bindTexture(5, shadowDepth); // slot 5 for pack shadowtex0
    }

    public VulkanImage shadowColor(int index) {
        return index >= 0 && index < this.shadowColors.size() ? this.shadowColors.get(index)
                : this.computeColors.get(index);
    }

    public List<VulkanImage> shadowColors() { return this.shadowColors; }

    /** Initial contents must be defined before geometry samples the first shadow frame. */
    public void initializeSampling(VkCommandBuffer commandBuffer) {
        VulkanImage color = this.shadowFramebuffer.getColorAttachment();
        VulkanImage depth = this.shadowFramebuffer.getDepthAttachment();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            depth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }
        PackGeometryContext.beginShadow(this.shadowColors, this.shadowFramebuffer.getDepthAttachment(),
                colorClears(true));
        try {
            Renderer.getInstance().beginRendering(this.shadowRenderPass, this.shadowFramebuffer);
            Renderer.getInstance().endRenderPass(commandBuffer);
        } finally {
            PackGeometryContext.close();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            depth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            for (VulkanImage target : this.shadowColors) {
                target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            clearComputeColors(stack, commandBuffer, true);
            // Before its first copy the opaque copy reads as a cleared map: Chimera's defined start.
            clearToFarDepth(stack, commandBuffer, this.opaqueDepth);
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
        this.shadowColors.forEach(TargetFormatViews::free);
        this.shadowColors = List.of();
        this.computeColors.values().forEach(TargetFormatViews::free);
        this.computeColors = Map.of();
        this.computeTargets = Map.of();
        this.targets = ShadowTargetDirectives.defaults();
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
