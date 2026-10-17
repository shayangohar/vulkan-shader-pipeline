package net.chimera.render;

import net.chimera.mixin.ChimeraDeviceAccessor;
import net.chimera.mixin.ChimeraVulkanImageAccessor;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.shaderpack.PackConfig;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackProgram;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.TargetSpec;
import net.chimera.shaderpack.TargetStep;
import net.chimera.shaderpack.UniformRegistry;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_BLIT_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_BLIT_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.vkCmdBlitImage;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImage;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;

/**
 * Render-thread executor for the immutable target graph. Logical
 * validity and physical image ownership are kept separate so a skipped pass
 * cannot make a cleared or stale image visible to a later pass.
 */
public final class PackPostTargets {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private static final int TARGET_COUNT = PackTargetGraphPlan.MAX_TARGET + 1;

    private static final int SIDE_COUNT = 3;
    private final VulkanImage[][] images = new VulkanImage[SIDE_COUNT][TARGET_COUNT];
    private final boolean[] used = new boolean[TARGET_COUNT];
    private final boolean[] valid = new boolean[TARGET_COUNT];
    private final boolean[] doubled = new boolean[TARGET_COUNT];
    private final int[] sideCounts = new int[TARGET_COUNT];
    private final int[] activeSide = new int[TARGET_COUNT];
    private final int[] previousSide = new int[TARGET_COUNT];
    private final int[] pendingWriteSide = new int[TARGET_COUNT];
    private final VulkanImage[] sourceImages = new VulkanImage[TARGET_COUNT];
    private final VulkanImage[] pendingImages = new VulkanImage[TARGET_COUNT];
    /** Copies a geometry window samples in place of the targets it draws into. */
    private final VulkanImage[] snapshotImages = new VulkanImage[TARGET_COUNT];

    private VulkanImage hdrIdentitySource;
    private PackTargetGraphPlan graph;
    private Framebuffer pipelineFramebuffer;
    private boolean configured;
    private boolean rendering;
    private final PackTemporalState temporal = new PackTemporalState();
    /** Images whose sampler reads their generated mip chain until the window ends. */
    private final Set<VulkanImage> mipSampling = Collections.newSetFromMap(new IdentityHashMap<>());
    private TargetStep currentStep;
    private PostTargetPlan currentPlan;

    /** Installs the validated graph and allocates only required physical sides. */
    public boolean configure(PackTargetGraphPlan graph) {
        return configure(graph, Set.of());
    }

    /** As {@link #configure(PackTargetGraphPlan)}, with the targets a pass compute writes allowing storage. */
    public boolean configure(PackTargetGraphPlan graph, Set<Integer> storageTargets) {
        trace("configure begin oldTarget0=" + imageId(this.images[0][0])
                + " oldTarget1=" + imageId(this.images[1][0]));
        cleanUp();
        if (graph == null || (graph.steps().isEmpty() && graph.targets().isEmpty())) {
            return false;
        }
        this.graph = graph;
        // RGBA16's sampled/attachment features are not mandatory on every device.
        // Reject unsupported storage rather than silently substituting floating point.
        if (graph.targets().stream().anyMatch(target -> target.format() == VK_FORMAT_R16G16B16A16_UNORM)) {
            verifyNormalizedRgba16Support();
        }
        graph.targets().stream().filter(TargetSpec::mipmapped).map(TargetSpec::format)
                .distinct().forEach(PackPostTargets::verifyMipmapSupport);
        Arrays.fill(this.used, false);
        Arrays.fill(this.valid, false);
        Arrays.fill(this.doubled, false);
        Arrays.fill(this.sideCounts, 1);
        Arrays.fill(this.activeSide, 0);
        Arrays.fill(this.previousSide, 0);
        Arrays.fill(this.pendingWriteSide, 0);
        for (TargetSpec target : graph.targets()) {
            int index = target.index();
            if (index < 0 || index >= TARGET_COUNT) {
                continue;
            }
            this.used[index] = true;
            this.doubled[index] = target.doubled();
            this.sideCounts[index] = target.doubled() ? (target.requiresHistory() ? 3 : 2) : 1;
            boolean storage = storageTargets.contains(index);
            this.images[0][index] = createTargetImage(target, "Side0", storage);
            if (this.doubled[index]) {
                this.images[1][index] = createTargetImage(target, "Side1", storage);
            }
            if (this.sideCounts[index] == 3) {
                this.images[2][index] = createTargetImage(target, "History", storage);
            }
        }
        if (this.images[0][0] == null) {
            throw new IllegalStateException("target graph does not contain colortex0");
        }
        this.pipelineFramebuffer = Framebuffer.builder(this.images[0][0], null).build();
        this.configured = true;
        trace("configure done target0=" + imageId(this.images[0][0])
                + " target1=" + imageId(this.images[1][0])
                + " sides=" + this.sideCounts[0] + " doubled=" + this.doubled[0]);
        return true;
    }

    /** A target image; one a pass compute writes through {@code colorimgN} also allows storage. */
    private static VulkanImage createTargetImage(TargetSpec target, String side, boolean storage) {
        VulkanImage image = VulkanImage.builder(target.width(), target.height())
                .setFormat(target.format())
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                        | (storage && PackPassComputes.storageSupported(target.format())
                        ? VK_IMAGE_USAGE_STORAGE_BIT : 0))
                .setMipLevels(target.mipLevels())
                // Reforged makes per-level views only on request; a mipmapped target renders to level 0.
                .setLevelViews(target.mipLevels() > 1)
                .setLinearFiltering(true)
                .setClamp(true)
                .createVulkanImage();
        // Iris samples the base level until a program's pass generates the chain.
        if (image.mipLevels > 1) sampleWith(image, baseLevelSampler());
        return image;
    }

    /** The mip chain is built by linear blits; reject a format that cannot be blitted that way. */
    private static void verifyMipmapSupport(int format) {
        if (DeviceManager.physicalDevice == null) {
            throw new IllegalStateException("Vulkan physical device is unavailable for mipmapped targets");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, properties);
            int required = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT
                    | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if ((properties.optimalTilingFeatures() & required) != required) {
                throw new IllegalArgumentException("mipmapped target format " + format
                        + " lacks linear blit support (required=0x" + Integer.toHexString(required)
                        + ", supported=0x" + Integer.toHexString(properties.optimalTilingFeatures()) + ")");
            }
        }
    }

    /** Check the optional exact normalized format before allocating any target images. */
    private static void verifyNormalizedRgba16Support() {
        if (DeviceManager.physicalDevice == null) {
            throw new IllegalStateException("Vulkan physical device is unavailable for RGBA16 targets");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice,
                    VK_FORMAT_R16G16B16A16_UNORM, properties);
            int required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                    | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                    | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                    | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT
                    | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
            if ((properties.optimalTilingFeatures() & required) != required) {
                throw new IllegalArgumentException("RGBA16 target format lacks "
                        + "sampled/linear/attachment/blend/transfer support"
                        + " (required=0x" + Integer.toHexString(required)
                        + ", supported=0x" + Integer.toHexString(properties.optimalTilingFeatures()) + ")");
            }
        }
    }

    /** Compatibility entry point retained for earlier callers. */
    public boolean configure(
            List<PackPipelines.PackPost> posts,
            Map<Integer, Integer> declaredFormats,
            int width,
            int height
    ) {
        if (posts == null || posts.isEmpty()) return false;
        List<PackProgramPlan> plans = posts.stream().map(post -> {
            List<UniformRegistry.SamplerBinding> samplers = new ArrayList<>();
            for (int index = 0; index < post.samplerNames().size(); index++) {
                samplers.add(new UniformRegistry.SamplerBinding(
                        post.samplerNames().get(index), post.samplerSlots()[index]));
            }
            UniformRegistry.ProgramInterface stage = new UniformRegistry.ProgramInterface(
                    UniformRegistry.Stage.POST, List.of(), samplers, List.of());
            UniformRegistry.ProgramInterfacePlan interfacePlan =
                    new UniformRegistry.ProgramInterfacePlan(
                            Map.of("fragment", stage), List.of(), samplers, List.of());
            PackProgram program = new PackProgram(post.name(), "", null);
            return new PackProgramPlan(program, Map.of(), interfacePlan, Map.of(), Map.of(),
                    post.targetPlan(), post.convertedFragment(), null, null, List.of(), true);
        }).toList();
        PackConfig.PackConfigData config = new PackConfig.PackConfigData(
                declaredFormats == null ? Map.of() : declaredFormats, 1,
                new PackConfig.ShadowSettings(PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION,
                        PackConfig.DEFAULT_SHADOW_DISTANCE, Map.of(), List.of()),
                Map.of(), List.of());
        int maxAttachments = deviceMaxColorAttachments();
        PackTargetGraphPlan legacy = PackTargetGraphPlan.build(
                plans, config, width, height, maxAttachments, Integer.MAX_VALUE);
        return configure(legacy);
    }

    /** Starts a frame without clearing or copying unrelated logical targets. */
    public void beginFrame(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        if (!this.configured || this.graph == null) {
            throw new IllegalStateException("pack target graph is not configured");
        }
        this.hdrIdentitySource = hdrColor;
        this.temporal.beginFrame(hdrColor != null);
        resetMipmapSampling();
        Arrays.fill(this.pendingImages, null);
        Arrays.fill(this.sourceImages, null);
        this.valid[0] = false;
        this.sourceImages[0] = hdrColor;
        trace("beginFrame target0=" + imageId(this.images[0][0])
                + " target1=" + imageId(this.images[1][0])
                + " activeSide=" + this.activeSide[0]
                + " sides=" + this.sideCounts[0]);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (TargetSpec target : this.graph.targets()) {
                int index = target.index();
                if (index == 0 || !this.used[index]) {
                    continue;
                }
                if (target.clear() && !target.persistent()) {
                    VulkanImage image = imageFor(index, this.activeSide[index]);
                    clearImage(stack, commandBuffer, image, target.clearColorCopy());
                    this.valid[index] = true;
                    this.sourceImages[index] = image;
                    this.temporal.seedCurrent(index);
                } else if (this.valid[index]) {
                    this.sourceImages[index] = imageFor(index, this.activeSide[index]);
                    this.temporal.seedCurrent(index);
                } else if (this.graph.requiresInitialSeed(index)) {
                    // A persistent feedback target has no defined Vulkan
                    // contents on its first allocation. Seed it once so the
                    // first read/write pass cannot sample undefined memory.
                    VulkanImage image = imageFor(index, this.activeSide[index]);
                    clearImage(stack, commandBuffer, image, target.clearColorCopy());
                    this.valid[index] = true;
                    this.sourceImages[index] = image;
                    this.temporal.seedCurrent(index);
                    trace("seedPersistent target=" + index + " image=" + imageId(image));
                }
            }
        }
    }

    public void requireFrameStarted() {
        this.temporal.requireFrameStarted();
    }

    /** Invalidates target 0 before pack geometry initializes it at render-pass load. */
    public void prepareGeometryTarget(VkCommandBuffer commandBuffer) {
        if (!this.configured || !this.used[0]) {
            throw new IllegalStateException("pack geometry target 0 is unavailable");
        }
        this.valid[0] = false;
        this.sourceImages[0] = null;
    }

    public VulkanImage geometryTarget0() {
        VulkanImage image = this.configured && this.used[0] ? imageFor(0, this.activeSide[0]) : null;
        trace("geometryTarget0 configured=" + this.configured + " used=" + this.used[0]
                + " activeSide=" + this.activeSide[0] + " sides=" + this.sideCounts[0]
                + " image=" + imageId(image));
        return image;
    }

    /** Starts a geometry window for the declared targets, not necessarily including HDR. */
    public void beginGeometry(VkCommandBuffer commandBuffer, VulkanImage hdrColor,
                              List<Integer> outputTargets) {
        beginGeometry(commandBuffer, hdrColor, outputTargets, List.of());
    }

    /**
     * As above, for a program that also samples some of its outputs: Iris lets a gbuffer program
     * read the target it draws into, which Vulkan forbids inside a render pass, so each such
     * target's current contents are copied and the copy is what the window's draws sample.
     */
    public void beginGeometry(VkCommandBuffer commandBuffer, VulkanImage hdrColor,
                              List<Integer> outputTargets, List<Integer> snapshotTargets) {
        requireFrameStarted();
        if (!this.configured || hdrColor == null || outputTargets == null || outputTargets.isEmpty()) {
            throw new IllegalStateException("pack geometry target state is not ready");
        }
        this.hdrIdentitySource = hdrColor;
        this.sourceImages[0] = hdrColor;
        this.valid[0] = false;
        this.rendering = false;
        for (int target : outputTargets) {
            if (target < 0 || target >= TARGET_COUNT || !this.used[target]) {
                throw new IllegalStateException("missing geometry output target " + target);
            }
        }
        if (snapshotTargets != null && !snapshotTargets.isEmpty()) {
            snapshotOutputs(commandBuffer, outputTargets, snapshotTargets);
        }
    }

    /** Copies each written target the window also samples, and makes the copy what it samples. */
    private void snapshotOutputs(VkCommandBuffer commandBuffer, List<Integer> outputTargets,
                                 List<Integer> snapshotTargets) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int target : snapshotTargets) {
                if (target <= 0 || target >= TARGET_COUNT || !outputTargets.contains(target)
                        || !this.valid[target] || this.sourceImages[target] == null) continue;
                if (this.snapshotImages[target] == null) {
                    this.snapshotImages[target] = createTargetImage(targetSpec(target), "Snapshot", false);
                }
                copyImage(stack, commandBuffer, this.sourceImages[target], this.snapshotImages[target]);
                this.sourceImages[target] = this.snapshotImages[target];
                trace("snapshot target=" + target + " image=" + imageId(this.snapshotImages[target]));
            }
        }
    }

    private TargetSpec targetSpec(int target) {
        return this.graph.targets().stream().filter(spec -> spec.index() == target).findFirst()
                .orElseThrow(() -> new IllegalStateException("missing geometry snapshot target " + target));
    }

    /** Returns attachments in the same order as fragment output locations. */
    public List<VulkanImage> geometryAttachments(List<Integer> outputTargets) {
        if (outputTargets == null || outputTargets.isEmpty()) return List.of();
        ArrayList<VulkanImage> result = new ArrayList<>();
        for (int target : outputTargets) {
            VulkanImage image = target == 0 ? this.hdrIdentitySource
                    : imageFor(target, this.activeSide[target]);
            if (image == null) {
                throw new IllegalStateException("geometry output image is unavailable: " + target);
            }
            result.add(image);
        }
        return List.copyOf(result);
    }

    /** Commits only auxiliary geometry outputs; target 0 remains the HDR identity source. */
    public void commitGeometry(List<Integer> outputTargets, VulkanImage hdrColor) {
        if (outputTargets == null || hdrColor == null) return;
        this.sourceImages[0] = hdrColor;
        this.valid[0] = false;
        for (int target : outputTargets) {
            if (target <= 0 || target >= TARGET_COUNT) continue;
            VulkanImage image = imageFor(target, this.activeSide[target]);
            if (image != null) {
                this.valid[target] = true;
                this.sourceImages[target] = image;
                this.temporal.seedCurrent(target);
            }
        }
    }

    /**
     * Makes the live HDR image colortex0 again after an early (deferred)
     * window. A deferred pass writes colortex0 to a graph image, but the
     * world keeps drawing into the HDR image, so the deferred result is
     * copied there and the HDR image becomes the identity source. Iris has
     * one colortex0 for the whole frame; this restores that contract.
     */
    public void adoptTarget0(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        if (!this.configured || hdrColor == null || !this.valid[0]
                || this.sourceImages[0] == null || this.sourceImages[0] == hdrColor) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            copyImage(stack, commandBuffer, this.sourceImages[0], hdrColor);
        }
        trace("adoptTarget0 source=" + imageId(this.sourceImages[0]) + " hdr=" + imageId(hdrColor));
        this.hdrIdentitySource = hdrColor;
        this.sourceImages[0] = hdrColor;
        this.valid[0] = false;
    }

    public void abortGeometry(VulkanImage hdrColor) {
        this.sourceImages[0] = hdrColor == null ? this.hdrIdentitySource : hdrColor;
        this.valid[0] = false;
        for (int target = 1; target < TARGET_COUNT; target++) {
            if (this.sourceImages[target] != null && this.sourceImages[target] == this.snapshotImages[target]) {
                this.sourceImages[target] = imageFor(target, this.activeSide[target]);
            }
        }
    }

    /** Commits target 0 after the scene seed successfully merged host pixels. */
    public void commitSceneSeed(VulkanImage target) {
        if (target == null || target != geometryTarget0()) {
            throw new IllegalStateException("scene seed target does not match active target 0");
        }
        this.valid[0] = true;
        this.sourceImages[0] = target;
    }

    /** Invalidates logical outputs while retaining physical images. */
    public void invalidateOutputs(List<Integer> targets) {
        if (targets == null) return;
        for (int target : targets) {
            if (target < 0 || target >= TARGET_COUNT) continue;
            this.valid[target] = false;
            this.sourceImages[target] = target == 0 ? this.hdrIdentitySource : null;
            this.temporal.invalidate(target);
        }
    }

    /**
     * Iris CompositeRenderer.setupMipmapping: before a program that declares
     * colortexNMipmapEnabled, rebuild the chain of the image the program
     * reads and sample that image through its mips until
     * {@link #resetMipmapSampling()}. Call before the program's images are bound.
     */
    public void generateMipmaps(VkCommandBuffer commandBuffer, String programName) {
        TargetStep step = this.graph == null ? null : this.graph.step(programName);
        if (!this.configured || step == null || step.mipmapTargets().isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int target : step.mipmapTargets()) {
                VulkanImage image = this.sourceImages[target];
                VulkanImage graphImage = imageFor(target, this.activeSide[target]);
                // A minimized window sizes targets 1x1: their whole chain is level 0.
                if (!needsMipChain(image == null || graphImage == null ? 0 : graphImage.mipLevels)) continue;
                if (image == this.hdrIdentitySource) image = adoptIdentitySource(stack, commandBuffer, target, image);
                buildMipChain(stack, commandBuffer, image);
                sampleWith(image, ChimeraSamplers.linearMipmapped(image.mipLevels - 1));
                this.mipSampling.add(image);
            }
        }
        trace("mipmaps program=" + programName + " targets=" + step.mipmapTargets());
    }

    /** A chain of one level has nothing to generate; nothing written has nothing to mip. */
    static boolean needsMipChain(int levels) {
        return levels > 1;
    }

    /** Iris resets every target's filter after each deferred, composite and final renderer. */
    public void resetMipmapSampling() {
        for (VulkanImage image : this.mipSampling) {
            sampleWith(image, baseLevelSampler());
        }
        this.mipSampling.clear();
    }

    /** Pack targets render to level 0 only: an attachment view has exactly one level. */
    public static long attachmentView(VulkanImage image) {
        return image.mipLevels > 1 ? image.getLevelImageView(0) : image.getImageView();
    }

    /**
     * The live HDR image stands in for colortex0 but has no mip chain. Move
     * the logical colortex0 into its graph image, as {@link #adoptTarget0}
     * moves it back after an early window.
     */
    private VulkanImage adoptIdentitySource(MemoryStack stack, VkCommandBuffer commandBuffer,
                                            int target, VulkanImage source) {
        VulkanImage graphImage = imageFor(target, this.activeSide[target]);
        if (target != 0 || graphImage == null) {
            throw new IllegalStateException("identity source stands in for colortex" + target);
        }
        copyImage(stack, commandBuffer, source, graphImage);
        this.sourceImages[target] = graphImage;
        this.valid[target] = true;
        return graphImage;
    }

    private static void buildMipChain(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image) {
        // One tracked layout covers the chain; TRANSFER_DST keeps level 0's contents.
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        recordMipChain(stack, commandBuffer, image.getId(), image.width, image.height, image.mipLevels);
        image.setCurrentLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    /**
     * glGenerateMipmap: each level is a linear downsample of the one above.
     * Every level enters in TRANSFER_DST and leaves in SHADER_READ_ONLY.
     */
    static void recordMipChain(MemoryStack stack, VkCommandBuffer commandBuffer,
                               long image, int width, int height, int levels) {
        for (int level = 1; level < levels; level++) {
            levelBarrier(stack, commandBuffer, image, level - 1, 1,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            int nextWidth = Math.max(1, width / 2);
            int nextHeight = Math.max(1, height / 2);
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level - 1).layerCount(1);
            blit.srcOffsets(1).set(width, height, 1);
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).layerCount(1);
            blit.dstOffsets(1).set(nextWidth, nextHeight, 1);
            vkCmdBlitImage(commandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);
            width = nextWidth;
            height = nextHeight;
        }
        int last = levels - 1;
        if (last > 0) {
            levelBarrier(stack, commandBuffer, image, 0, last,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        }
        levelBarrier(stack, commandBuffer, image, last, 1,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    }

    private static void levelBarrier(MemoryStack stack, VkCommandBuffer commandBuffer, long image,
                                     int baseLevel, int levelCount, int oldLayout, int newLayout,
                                     int sourceAccess, int destinationAccess, int destinationStage) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default().oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(baseLevel).levelCount(levelCount).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, destinationStage,
                0, null, null, barrier);
    }

    /** Iris samples a target's base level, linearly and clamped to the edge. */
    private static long baseLevelSampler() {
        return ChimeraSamplers.flat(true, true);
    }

    private static void sampleWith(VulkanImage image, long sampler) {
        ((ChimeraVulkanImageAccessor) image).chimera$setSampler(sampler);
    }

    /** Begins dynamic rendering for the graph step represented by this post. */
    public void prepare(PackPipelines.PackPost post, VkCommandBuffer commandBuffer) {
        if (!this.configured || this.graph == null || this.rendering) {
            throw new IllegalStateException("pack post target state is not ready");
        }
        PostTargetPlan plan = post.targetPlan();
        TargetStep step = this.graph.step(post.name());
        if (plan == null || plan.isFinal() || step == null || !step.executable()) {
            throw new IllegalStateException("post step is not executable: " + post.name());
        }
        pendingWriteSideReset();
        flip(step.flipsBefore());
        this.currentStep = step;
        this.currentPlan = plan;
        int attachmentCount = plan.targetSlots().size();
        int deviceLimit = deviceMaxColorAttachments();
        if (!deviceSupportsMrt(attachmentCount, deviceLimit)) {
            throw new IllegalStateException("post pass requires " + attachmentCount
                    + " color attachments, device supports " + deviceLimit);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            claimOutputSides(stack, commandBuffer, step);
            PostAttachments attachments = colorAttachments(stack, commandBuffer, plan, step);
            int renderWidth = attachments.width();
            int renderHeight = attachments.height();

            VkRect2D renderArea = VkRect2D.calloc(stack);
            renderArea.offset().set(0, 0);
            renderArea.extent().set(renderWidth, renderHeight);
            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
            renderingInfo.sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR);
            renderingInfo.renderArea(renderArea);
            renderingInfo.layerCount(1);
            renderingInfo.pColorAttachments(attachments.buffer());

            MrtPipelineContext.begin(plan.outputFormatsArray(), deviceLimit);
            Renderer.getInstance().setBoundFramebuffer(this.pipelineFramebuffer);
            Renderer.getInstance().setBoundRenderPass(ChimeraRenderingPass.of(plan.outputFormatsArray(), 0, false));
            Renderer.setViewport(0, 0, renderWidth, renderHeight, stack);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(0, 0);
            scissor.get(0).extent().set(renderWidth, renderHeight);
            vkCmdSetScissor(commandBuffer, 0, scissor);
            try {
                vkCmdBeginRenderingKHR(commandBuffer, renderingInfo);
                this.rendering = true;
            } catch (RuntimeException failure) {
                MrtPipelineContext.end();
                Renderer.getInstance().setBoundRenderPass(null);
                Renderer.getInstance().setBoundFramebuffer(null);
                throw failure;
            }
        }
    }

    /** Picks each output's write side and, when the step also reads it, copies its source in first. */
    private void claimOutputSides(MemoryStack stack, VkCommandBuffer commandBuffer, TargetStep step) {
        for (int target : step.outputTargets()) {
            if (target < 0 || target >= TARGET_COUNT || !this.used[target]) {
                throw new IllegalStateException("missing output target " + target);
            }
            int side = this.doubled[target]
                    ? nextWriteSide(target) : this.activeSide[target];
            this.pendingWriteSide[target] = side;
            VulkanImage destination = imageFor(target, side);
            this.pendingImages[target] = destination;
            if (step.reads(target) && this.sourceImages[target] != null
                    && this.sourceImages[target] != destination) {
                copyImage(stack, commandBuffer, this.sourceImages[target], destination);
            }
        }
    }

    /** One post draw's color attachments and its render extent, the first attachment's size. */
    private record PostAttachments(VkRenderingAttachmentInfo.Buffer buffer, int width, int height) {}

    /** Moves the plan's attachment targets to attachment layout and describes them in slot order. */
    private PostAttachments colorAttachments(MemoryStack stack, VkCommandBuffer commandBuffer,
                                             PostTargetPlan plan, TargetStep step) {
        List<Integer> attachmentTargets = plan.targetSlots();
        VkRenderingAttachmentInfo.Buffer attachments =
                VkRenderingAttachmentInfo.calloc(attachmentTargets.size(), stack);
        int renderWidth = step.width();
        int renderHeight = step.height();
        for (int i = 0; i < attachmentTargets.size(); i++) {
            int target = attachmentTargets.get(i);
            VulkanImage destination = this.pendingImages[target] != null
                    ? this.pendingImages[target] : imageFor(target, this.activeSide[target]);
            if (destination == null) {
                throw new IllegalStateException("missing attachment target " + target);
            }
            destination.transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkRenderingAttachmentInfo attachment = attachments.get(i);
            attachment.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            attachment.imageView(attachmentView(destination));
            attachment.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            attachment.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            attachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (i == 0) {
                renderWidth = destination.width;
                renderHeight = destination.height;
            }
        }
        return new PostAttachments(attachments, renderWidth, renderHeight);
    }

    /** Commits only successful output writes. */
    public void finish(VkCommandBuffer commandBuffer) {
        if (!this.rendering) throw new IllegalStateException("pack post rendering is not active");
        boolean committed = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdEndRenderingKHR(commandBuffer);
            transitionWrittenTargetsForSampling(stack, commandBuffer);
            committed = true;
        } finally {
            if (committed) {
                for (int target = 0; target < TARGET_COUNT; target++) {
                    if (this.pendingImages[target] != null) {
                        this.previousSide[target] = this.activeSide[target];
                        this.activeSide[target] = this.pendingWriteSide[target];
                        this.valid[target] = true;
                        this.sourceImages[target] = this.pendingImages[target];
                        this.temporal.stageWrite(target);
                    }
                }
                if (this.currentStep != null) flip(this.currentStep.flipsAfter());
            }
            clearPendingState();
            finishRendererState();
        }
    }

    /** Returns the pending images, and every target the current plan wrote, to the sampled layout. */
    private void transitionWrittenTargetsForSampling(MemoryStack stack, VkCommandBuffer commandBuffer) {
        for (VulkanImage image : this.pendingImages) {
            if (image != null) {
                image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        }
        if (this.currentPlan == null) return;
        for (int target : this.currentPlan.targetSlots()) {
            VulkanImage image = this.pendingImages[target] != null
                    ? this.pendingImages[target] : imageFor(target, this.activeSide[target]);
            if (image != null && !contains(this.pendingImages, image)) {
                image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        }
    }

    /** Commits the current frame's logical state after all scheduled windows finish. */
    public boolean commitFrame() {
        return this.temporal.commit();
    }

    /** Aborts pending writes without changing committed logical state. */
    public void abort(VkCommandBuffer commandBuffer) {
        if (this.rendering) vkCmdEndRenderingKHR(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionWrittenTargetsForSampling(stack, commandBuffer);
        } finally {
            this.temporal.abort();
            clearPendingState();
            finishRendererState();
        }
    }

    public VulkanImage[] sourceImages() { return this.sourceImages; }
    public PackTargetGraphPlan graph() { return this.graph; }

    public boolean areInputsAvailable(List<Integer> requiredTargets) {
        if (requiredTargets == null) return true;
        for (int target : requiredTargets) if (!isTargetAvailable(target)) return false;
        return true;
    }

    public VulkanImage activeTarget(int target) {
        if (target < 0 || target >= TARGET_COUNT) return null;
        if (target == 0 && !this.valid[0]) return this.sourceImages[0];
        return this.valid[target] ? imageFor(target, this.activeSide[target]) : null;
    }

    /** Returns the last committed frame image when temporal history exists. */
    public VulkanImage previousTarget(int target) {
        if (target < 0 || target >= TARGET_COUNT || !this.valid[target]
                || !this.temporal.previousAvailable(target)) return null;
        return imageFor(target, this.previousSide[target]);
    }

    public boolean currentTargetAvailable(int target) {
        return isTargetAvailable(target);
    }

    public void resetTemporalState() {
        this.temporal.reset();
        Arrays.fill(this.previousSide, 0);
    }

    static boolean isTargetAvailable(int target, boolean hdrIdentity, boolean[] written) {
        return written != null && target >= 0 && target < TARGET_COUNT && target < written.length
                && (target == 0 ? hdrIdentity || written[target] : written[target]);
    }

    static boolean areTargetsAvailable(List<Integer> targets, boolean hdrIdentity, boolean[] written) {
        if (targets == null) return true;
        for (int target : targets) if (!isTargetAvailable(target, hdrIdentity, written)) return false;
        return true;
    }

    static void invalidateWrittenTargets(boolean[] written, List<Integer> targets) {
        if (written == null || targets == null) return;
        for (int target : targets) if (target >= 0 && target < TARGET_COUNT
                && target < written.length) written[target] = false;
    }

    static int bankAfterFinish(int activeBank, int destinationBank, boolean committed) {
        return committed ? destinationBank : activeBank;
    }

    static boolean deviceSupportsMrt(int attachments, int maxColorAttachments) {
        return attachments > 0 && maxColorAttachments > 0
                && attachments <= Math.min(PackTargetGraphPlan.LOGICAL_ATTACHMENT_LIMIT, maxColorAttachments);
    }

    public boolean isRendering() { return this.rendering; }
    public boolean isConfigured() { return this.configured; }

    public void cleanUp() {
        trace("cleanup target0=" + imageId(this.images[0][0])
                + " target1=" + imageId(this.images[1][0])
                + " configured=" + this.configured);
        if (this.rendering) throw new IllegalStateException("cannot clean up active pack post rendering");
        if (this.pipelineFramebuffer != null) this.pipelineFramebuffer.cleanUp(false);
        this.pipelineFramebuffer = null;
        for (int side = 0; side < images.length; side++) {
            for (int target = 0; target < TARGET_COUNT; target++) {
                if (this.images[side][target] != null) {
                    this.images[side][target].free();
                    this.images[side][target] = null;
                }
            }
        }
        for (int target = 0; target < TARGET_COUNT; target++) {
            if (this.snapshotImages[target] != null) {
                this.snapshotImages[target].free();
                this.snapshotImages[target] = null;
            }
        }
        this.mipSampling.clear();
        Arrays.fill(this.sourceImages, null);
        Arrays.fill(this.pendingImages, null);
        Arrays.fill(this.used, false);
        Arrays.fill(this.valid, false);
        Arrays.fill(this.doubled, false);
        Arrays.fill(this.sideCounts, 1);
        Arrays.fill(this.previousSide, 0);
        this.hdrIdentitySource = null;
        this.graph = null;
        this.currentStep = null;
        this.currentPlan = null;
        this.configured = false;
        this.temporal.reset();
        MrtPipelineContext.end();
    }

    private boolean isTargetAvailable(int target) {
        if (target < 0 || target >= TARGET_COUNT) return false;
        return target == 0 ? this.sourceImages[0] != null
                : this.valid[target] && this.sourceImages[target] != null;
    }

    private VulkanImage imageFor(int target, int side) {
        if (target < 0 || target >= TARGET_COUNT) return null;
        int count = this.sideCounts[target];
        int safeSide = count <= 1 ? 0 : Math.floorMod(side, count);
        return this.images[safeSide][target];
    }

    /**
     * Iris BufferFlipper.flip for explicit flip directives: each target's other image, the one
     * written before its current one, becomes current for later passes and the next frame. After
     * a pass that wrote the target this restores the pre-pass image, as Iris's write flip and the
     * explicit flip cancel. Photon's flip.composite1.colortex6 hands deferred3 back its AO
     * history after composite wrote fog transmittance there. Target 0 is the HDR identity source.
     */
    private void flip(List<Integer> targets) {
        for (int target : targets) {
            if (target <= 0 || target >= TARGET_COUNT || !this.doubled[target]) continue;
            swapSides(this.activeSide, this.previousSide, target);
            if (this.valid[target]) this.sourceImages[target] = imageFor(target, this.activeSide[target]);
        }
    }

    /** Makes a target's previous side current, and its current side previous. */
    static void swapSides(int[] activeSide, int[] previousSide, int target) {
        int side = activeSide[target];
        activeSide[target] = previousSide[target];
        previousSide[target] = side;
    }

    /** The side a write to a target with {@code sideCount} sides goes to after {@code activeSide}. */
    static int nextSide(int activeSide, int sideCount) {
        return sideCount <= 1 ? 0 : Math.floorMod(activeSide + 1, sideCount);
    }

    private int nextWriteSide(int target) {
        return nextSide(this.activeSide[target], this.sideCounts[target]);
    }

    private void pendingWriteSideReset() {
        Arrays.fill(this.pendingImages, null);
        Arrays.fill(this.pendingWriteSide, 0);
        this.currentStep = null;
        this.currentPlan = null;
    }

    private void clearPendingState() {
        Arrays.fill(this.pendingImages, null);
        this.rendering = false;
        this.currentStep = null;
        this.currentPlan = null;
    }

    private void finishRendererState() {
        MrtPipelineContext.end();
        Renderer.getInstance().setBoundRenderPass(null);
        Renderer.getInstance().setBoundFramebuffer(null);
    }

    private static int deviceMaxColorAttachments() {
        if (DeviceManager.device == null) return PackTargetGraphPlan.LOGICAL_ATTACHMENT_LIMIT;
        return ((ChimeraDeviceAccessor) DeviceManager.device)
                .chimera$properties().limits().maxColorAttachments();
    }

    private static void clearImage(
            MemoryStack stack,
            VkCommandBuffer commandBuffer,
            VulkanImage image,
            float[] color
    ) {
        if (image == null) throw new IllegalStateException("cannot clear missing target image");
        VkClearColorValue clear = VkClearColorValue.calloc(stack);
        clear.float32(stack.floats(color[0], color[1], color[2], color[3]));
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        range.get(0).baseMipLevel(0);
        range.get(0).levelCount(1);
        range.get(0).baseArrayLayer(0);
        range.get(0).layerCount(1);
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        vkCmdClearColorImage(commandBuffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    private static boolean contains(VulkanImage[] images, VulkanImage needle) {
        for (VulkanImage image : images) if (image == needle) return true;
        return false;
    }

    private static long imageId(VulkanImage image) {
        return image == null ? 0L : image.getId();
    }

    private static void trace(String message) {
        if (Boolean.getBoolean("chimera.traceTransitions")) {
            LOGGER.info("[chimera] pack target {}", message);
        }
    }

    private static void copyImage(
            MemoryStack stack,
            VkCommandBuffer commandBuffer,
            VulkanImage source,
            VulkanImage destination
    ) {
        if (source.format != destination.format || source.width != destination.width
                || source.height != destination.height) {
            throw new IllegalStateException("POST_TARGET_SEED_CONVERSION");
        }
        source.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        destination.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
        VkImageSubresourceLayers sourceSubresource = copy.srcSubresource();
        sourceSubresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        sourceSubresource.mipLevel(0);
        sourceSubresource.baseArrayLayer(0);
        sourceSubresource.layerCount(1);
        VkImageSubresourceLayers destinationSubresource = copy.dstSubresource();
        destinationSubresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        destinationSubresource.mipLevel(0);
        destinationSubresource.baseArrayLayer(0);
        destinationSubresource.layerCount(1);
        copy.extent().set(source.width, source.height, 1);
        vkCmdCopyImage(commandBuffer, source.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                destination.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
        source.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        destination.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }
}
