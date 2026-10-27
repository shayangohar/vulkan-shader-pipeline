package net.chimera.render;

import net.chimera.mixin.ChimeraDeviceAccessor;
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
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;

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
        return configure(graph, Map.of());
    }

    /**
     * As {@link #configure(PackTargetGraphPlan)}, with the targets a pass compute writes allowing
     * storage in the formats it binds them in (PackPassComputes.storageTargets).
     */
    public boolean configure(PackTargetGraphPlan graph, Map<Integer, Set<Integer>> storageTargets) {
        trace("configure begin oldTarget0=" + imageId(this.images[0][0])
                + " oldTarget1=" + imageId(this.images[1][0]));
        cleanUp();
        if (graph == null || (graph.steps().isEmpty() && graph.targets().isEmpty())) {
            return false;
        }
        this.graph = graph;
        PackTargetImages.verifyFormats(graph.targets());
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
            Set<Integer> storage = storageTargets.getOrDefault(index, Set.of());
            this.images[0][index] = PackTargetImages.createTargetImage(target, storage);
            if (this.doubled[index]) {
                this.images[1][index] = PackTargetImages.createTargetImage(target, storage);
            }
            if (this.sideCounts[index] == 3) {
                this.images[2][index] = PackTargetImages.createTargetImage(target, storage);
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
                List.of());
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
        Set<Integer> unwrittenFlips = this.graph.unwrittenFlips();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (TargetSpec target : this.graph.targets()) {
                int index = target.index();
                if (index == 0 || !this.used[index]) {
                    continue;
                }
                if (target.clear() && !target.persistent()) {
                    VulkanImage image = imageFor(index, this.activeSide[index]);
                    PackTargetImages.clearImage(stack, commandBuffer, image, target.clearColorCopy());
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
                    PackTargetImages.clearImage(stack, commandBuffer, image, target.clearColorCopy());
                    this.valid[index] = true;
                    this.sourceImages[index] = image;
                    this.temporal.seedCurrent(index);
                    trace("seedPersistent target=" + index + " image=" + imageId(image));
                }
                if (this.valid[index] && unwrittenFlips.contains(index)) matchOtherSide(stack, commandBuffer, index);
            }
        }
    }

    /**
     * Copies a target's current side over the side a step that leaves it unwritten will make current.
     * Iris's FinalPassRenderer copies each flipped buffer back at frame end, and clears both sides of
     * a cleared one, so that flip hands back what the frame started with, never an older frame's.
     */
    private void matchOtherSide(MemoryStack stack, VkCommandBuffer commandBuffer, int target) {
        if (this.sideCounts[target] < 2) return;
        int other = nextWriteSide(target);
        PackTargetImages.copyImage(stack, commandBuffer, imageFor(target, this.activeSide[target]),
                imageFor(target, other));
        this.previousSide[target] = other;
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
                    this.snapshotImages[target] = PackTargetImages.createTargetImage(targetSpec(target), false);
                }
                PackTargetImages.copyImage(stack, commandBuffer, this.sourceImages[target],
                        this.snapshotImages[target]);
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
            PackTargetImages.copyImage(stack, commandBuffer, this.sourceImages[0], hdrColor);
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
                PackTargetImages.buildMipChain(stack, commandBuffer, image);
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
            PackTargetImages.sampleWith(image, PackTargetImages.baseLevelSampler(image));
        }
        this.mipSampling.clear();
    }

    /** A pack target's clear colour in its format's kind (PackTargetImages.clearValue). */
    public static void clearColorValue(MemoryStack stack, VkClearColorValue clear, int vkFormat, float[] color) {
        PackTargetImages.clearValue(stack, clear, vkFormat, color);
    }

    /** Pack targets render to level 0 only, through an identity view of exactly one level. */
    public static long attachmentView(VulkanImage image) {
        return image.getLevelImageViews() != null ? image.getLevelImageView(0) : image.getImageView();
    }

    /**
     * The image a pass compute writes as {@code colorimgN}: the one the chain reads now. colortex0
     * still in the live HDR image moves into its graph image first, as for a mip chain: the HDR
     * image is a render attachment without storage usage, while a graph image a compute writes has it.
     */
    public VulkanImage storageSource(VkCommandBuffer commandBuffer, int target) {
        VulkanImage image = target >= 0 && target < TARGET_COUNT ? this.sourceImages[target] : null;
        if (image == null || image != this.hdrIdentitySource) return image;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return adoptIdentitySource(stack, commandBuffer, target, image);
        }
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
        PackTargetImages.copyImage(stack, commandBuffer, source, graphImage);
        this.sourceImages[target] = graphImage;
        this.valid[target] = true;
        return graphImage;
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

            // The pass draws unblended unless its blend.<program> says otherwise (CompositeRenderer).
            MrtPipelineContext.begin(plan.outputFormatsArray(), deviceLimit,
                    post.blendPlan().attachments(plan.targetSlots()));
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

    /**
     * Picks each output's write side and, when the step also reads it, copies its source in first.
     * A target the step leaves unwritten takes its other side as that side stands, as Iris's flip does.
     */
    private void claimOutputSides(MemoryStack stack, VkCommandBuffer commandBuffer, TargetStep step) {
        for (int target : step.outputTargets()) {
            if (target < 0 || target >= TARGET_COUNT || !this.used[target]) {
                throw new IllegalStateException("missing output target " + target);
            }
            boolean unwritten = step.unwrittenTargets().contains(target);
            int side = writeSide(unwritten, this.doubled[target], this.activeSide[target],
                    this.previousSide[target], this.sideCounts[target]);
            this.pendingWriteSide[target] = side;
            VulkanImage destination = imageFor(target, side);
            this.pendingImages[target] = destination;
            if (!unwritten && step.reads(target) && this.sourceImages[target] != null
                    && this.sourceImages[target] != destination) {
                PackTargetImages.copyImage(stack, commandBuffer, this.sourceImages[target], destination);
            }
        }
    }

    /** The side a step writes a target to: the other side of one it leaves unwritten, else the next or current. */
    static int writeSide(boolean unwritten, boolean doubled, int activeSide, int previousSide, int sideCount) {
        if (unwritten) return previousSide;
        return doubled ? nextSide(activeSide, sideCount) : activeSide;
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
                    TargetFormatViews.free(this.images[side][target]);
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
}
