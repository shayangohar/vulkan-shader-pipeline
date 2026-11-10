package net.chimera.render;

import net.chimera.mixin.ChimeraVulkanImageAccessor;
import net.chimera.shaderpack.ComputeValueBlock;
import net.chimera.shaderpack.IrisColorFormats;
import net.chimera.shaderpack.PackConfig;
import net.chimera.shaderpack.PackResourceBinding;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PassComputePlan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntPredicate;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The session's pass computes (Iris CompositeRenderer): each dispatches at its pass's place, before
 * the pass's program draws or alone, with {@code colorimgN} and {@code colortexN} bound to the
 * image the pass reads. A written
 * target is in GENERAL for the dispatch and returns to the sampled layout afterwards, so the pass
 * and every later reader see what the compute stored. Pack images and their samplers bind the
 * session's images, which stay in GENERAL. Pack textures bind the session's, loaded on first use
 * when no program reads them. Storage blocks bind the session's buffers whole, as programs do.
 */
final class PackPassComputes implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    /** One compute that loaded: its plan and its pipeline. */
    private record Loaded(PassComputePlan.PassCompute plan, ComputeKernel kernel) {}

    /**
     * The images a pass reads now, as its program reads them. Image bindings come before sampler
     * bindings, so a sampler of a target a compute writes reads where {@link #storage} moved it.
     */
    interface ImageSources {
        /** The colour targets the pass reads. */
        VulkanImage[] colors();

        /** The image a compute writes as colorimgN, which may first move colortex0 out of the HDR image. */
        VulkanImage storage(int target);

        /** depthtexN's snapshot. */
        VulkanImage depth(String name);

        /** shadowtex0, shadowtex1 or shadowcolorN. */
        VulkanImage shadow(String resource);

        /** The sampler a shadow image reads through: a comparison one for a sampler2DShadow. */
        long shadowSampler(String resource, VulkanImage image, boolean compare);
    }

    private final List<Loaded> loaded;
    private final PackResourceOwner resources;
    private final PackAdvancedImageOwner packImages;
    private final PackStorageBufferOwner buffers;
    private final Set<String> failed = new HashSet<>();
    /** Computes that have skipped a frame, each logged once. */
    private final Set<String> skipped = new HashSet<>();
    /** Computes whose work groups cannot be dispatched (dispatchGap), each logged once. */
    private final Set<String> refused = new HashSet<>();
    /** The setup computes' round; a session's first round starts at load. */
    private final SetupRound setupRound = new SetupRound();

    private PackPassComputes(List<Loaded> loaded, PackResourceOwner resources, PackAdvancedImageOwner packImages,
                             PackStorageBufferOwner buffers) {
        this.loaded = loaded;
        this.resources = resources;
        this.packImages = packImages;
        this.buffers = buffers;
        requestSetup();
    }

    /** Builds a pipeline for every supported compute whose resources this session has; logs each outcome. */
    static PackPassComputes load(PassComputePlan plan, PackResourceOwner resources,
                                 PackAdvancedImageOwner packImages, PackStorageBufferOwner buffers) {
        List<Loaded> loaded = new ArrayList<>();
        for (PassComputePlan.PassCompute compute : plan.computes()) {
            String refusal = compute.supported() ? refusal(compute, resources, packImages, buffers)
                    : String.valueOf(compute.deviations());
            ComputeKernel kernel = refusal != null ? null : ComputeKernel.create("chimera_" + compute.name() + ".csh",
                    compute.source(), descriptorTypes(compute), ComputeValueBlock.bufferSize(compute.values()));
            if (kernel != null) {
                loaded.add(new Loaded(compute, kernel));
                LOGGER.info("[chimera] pack compute {}: ok (before {}, bindings={}, values={})", compute.name(),
                        compute.pass(), compute.bindings().stream().map(PassComputePlan.Binding::symbol).toList(),
                        compute.values().size());
            } else {
                LOGGER.warn("[chimera] pack compute {}: not run ({})", compute.name(),
                        refusal != null ? refusal : "pipeline build failed");
            }
        }
        return new PackPassComputes(List.copyOf(loaded), resources, packImages, buffers);
    }

    /** The computes that loaded, in run order. */
    List<String> installedNames() {
        return loaded.stream().map(compute -> compute.plan().name()).toList();
    }

    /**
     * Whether a loaded compute runs in the post chain: a frame pass's or a setup compute. The
     * shadowcomp computes run with the shadow pass (PackShadowComposites).
     */
    boolean runInPostChain() {
        return loaded.stream().anyMatch(compute -> !PassComputePlan.isShadowcomp(compute.plan().pass()));
    }

    /**
     * Each pass of the frame some loaded compute runs before, with the depth snapshots its computes
     * read; setup and shadowcomp computes run apart (dispatchSetup, PackShadowComposites).
     */
    Map<String, Set<String>> depthReadsByPass() {
        Map<String, Set<String>> passes = new TreeMap<>();
        for (Loaded compute : loaded) {
            if (!framePass(compute.plan().pass())) continue;
            Set<String> reads = passes.computeIfAbsent(compute.plan().pass(), ignored -> new TreeSet<>());
            compute.plan().bindings().stream().filter(binding -> binding.kind() == PassComputePlan.Kind.DEPTH)
                    .forEach(binding -> reads.add(binding.resource()));
        }
        return passes;
    }

    /**
     * The shadow colour targets the loaded computes bind, each with the Vulkan formats they write
     * it in (none for one they only sample): the shadow map creates any the shadow pass does not
     * draw, as Iris creates one a compute names (ShadowRenderTargets.getOrCreate).
     */
    Map<Integer, Set<Integer>> shadowTargets() {
        Map<Integer, Set<Integer>> targets = new TreeMap<>();
        for (Loaded compute : loaded) {
            for (PassComputePlan.Binding binding : compute.plan().bindings()) {
                int index = PackResourcePlan.shadowColorIndex(binding.resource());
                if (index < 0 || binding.kind() != PassComputePlan.Kind.SHADOW_COLOR
                        && binding.kind() != PassComputePlan.Kind.SHADOW_COLOR_IMAGE) continue;
                Set<Integer> formats = targets.computeIfAbsent(index, ignored -> new TreeSet<>());
                if (binding.storage()) formats.add(IrisColorFormats.of(binding.format()).vkFormat());
            }
        }
        return targets;
    }

    /** The colour targets and depth snapshots the loaded computes bind, which the target graph must hold. */
    PackTargetGraphPlan.ComputeUse use() {
        Set<Integer> targets = new TreeSet<>();
        Set<String> depthReads = new TreeSet<>();
        for (Loaded compute : loaded) {
            for (PassComputePlan.Binding binding : compute.plan().bindings()) {
                switch (binding.kind()) {
                    case TARGET_IMAGE, TARGET_SAMPLER -> targets.add(binding.target());
                    case DEPTH -> depthReads.add(binding.resource());
                    default -> { }
                }
            }
        }
        return new PackTargetGraphPlan.ComputeUse(targets, depthReads);
    }

    /**
     * The targets a loaded compute writes, which their images must allow as storage, each with the
     * Vulkan formats it is bound in: a view in each one other than the target's own (TargetFormatViews).
     */
    Map<Integer, Set<Integer>> storageTargets() {
        Map<Integer, Set<Integer>> targets = new TreeMap<>();
        for (Loaded compute : loaded) {
            compute.plan().bindings().stream().filter(binding -> binding.kind() == PassComputePlan.Kind.TARGET_IMAGE)
                    .forEach(binding -> targets.computeIfAbsent(binding.target(), ignored -> new TreeSet<>())
                            .add(IrisColorFormats.of(binding.format()).vkFormat()));
        }
        return targets;
    }

    /**
     * Runs the computes of this pass, in order, over the images the pass is about to read. One that
     * cannot dispatch this frame (frameGap) skips the frame before it binds anything; looking up its
     * images may already have moved colortex0 out of the HDR image (storageSource), which leaves the
     * chain's contents as they were. One that fails to record runs no more.
     */
    void dispatchBefore(String pass, VkCommandBuffer commandBuffer, ImageSources sources, int width, int height) {
        for (Loaded compute : loaded) {
            if (compute.plan().pass().equals(pass)) run(compute, commandBuffer, sources, width, height);
        }
    }

    /**
     * Runs the setup computes as their round allows (SetupRound), each with {@code dispatch(1, 1)}
     * as Iris does, so a relative work-group count is of a one-pixel screen (IrisRenderingPipeline).
     */
    void dispatchSetup(VkCommandBuffer commandBuffer, ImageSources sources) {
        Map<String, Loaded> setup = new LinkedHashMap<>();
        loaded.stream().filter(compute -> PassComputePlan.isSetup(compute.plan().pass()))
                .forEach(compute -> setup.put(compute.plan().name(), compute));
        for (String name : setupRound.advance(List.copyOf(setup.keySet()),
                name -> run(setup.get(name), commandBuffer, sources, 1, 1))) {
            LOGGER.warn("[chimera] pack setup compute {} skipped {} frames; this setup round goes on without it",
                    name, SetupRound.TRIES);
        }
    }

    /** Starts a setup round: the targets were made again, after a resize. */
    void requestSetup() {
        setupRound.start(loaded.stream().anyMatch(compute -> PassComputePlan.isSetup(compute.plan().pass())));
    }

    /** Whether a setup compute of this round has yet to run. */
    boolean setupDue() {
        return setupRound.due();
    }

    /**
     * The shadowcomp passes with computes. PackShadowComposites runs each pass's computes before its
     * fragment program (dispatchBefore), at the screen's size, as ShadowCompositeRenderer.renderAll
     * dispatches at the main render target's.
     */
    Set<String> shadowcompPasses() {
        Set<String> passes = new TreeSet<>();
        for (Loaded compute : loaded) {
            if (PassComputePlan.isShadowcomp(compute.plan().pass())) passes.add(compute.plan().pass());
        }
        return passes;
    }

    /** Whether the computes of {@code pass} run in the frame's colour chain: setup and shadowcomp run apart. */
    private static boolean framePass(String pass) {
        return !PassComputePlan.isSetup(pass) && !PassComputePlan.isShadowcomp(pass);
    }

    /** What one compute does this frame, and whether it counts as run, as a setup round reads it. */
    enum Step {
        DISPATCH(true),
        /**
         * Work groups that cannot be dispatched (dispatchGap): nothing is dispatched and the compute
         * counts as run, as glDispatchCompute leaves work groups it refuses, so a setup round goes on
         * to its next compute in the same frame. The reason holds at this size, so waiting would not help.
         */
        NOTHING(true),
        /** An image or indirect count missing this frame (frameGap): it skips, holding a setup round. */
        SKIP_FRAME(false);

        final boolean ran;

        Step(boolean ran) {
            this.ran = ran;
        }
    }

    /** The step for a compute with this frame gap and dispatch gap, either null when it has none. */
    static Step step(String frameGap, String dispatchGap) {
        return frameGap != null ? Step.SKIP_FRAME : dispatchGap != null ? Step.NOTHING : Step.DISPATCH;
    }

    /** Dispatches one compute unless it failed before; whether it counts as run ({@link Step}). */
    private boolean run(Loaded compute, VkCommandBuffer commandBuffer, ImageSources sources, int width, int height) {
        String name = compute.plan().name();
        if (failed.contains(name)) return true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            List<VulkanImage> images = images(compute.plan(), sources);
            PassComputePlan.Indirect indirect = compute.plan().indirect();
            String gap = frameGap(compute.plan(), binding -> images.get(binding) != null,
                    indirect == null ? 0L : buffers.buffer(indirect.buffer()).getBufferSize());
            String refusal = gap != null ? null
                    : dispatchGap(compute.plan(), workGroupSize(compute), width, height, dispatchLimits());
            Step step = step(gap, refusal);
            switch (step) {
                case DISPATCH -> dispatch(stack, commandBuffer, compute, images, sources, width, height);
                case NOTHING -> {
                    if (refused.add(name)) {
                        LOGGER.warn("[chimera] pack compute {} dispatches nothing: {}", name, refusal);
                    }
                }
                case SKIP_FRAME -> {
                    if (skipped.add(name)) LOGGER.warn("[chimera] pack compute {} skips a frame: {}", name, gap);
                }
            }
            return step.ran;
        } catch (RuntimeException failure) {
            failed.add(name);
            LOGGER.warn("[chimera] pack compute {} dispatch failed; it no longer runs: {}",
                    name, failure.getMessage(), failure);
            return true;
        }
    }

    /**
     * Why {@code compute} cannot dispatch this frame, or null when it can: an image it binds is
     * missing ({@code bound} false for its binding), as when the pass that writes it did not run, or
     * its indirect counts lie past the buffer, as a relative buffer's size can make them at some
     * screen sizes. glDispatchComputeIndirect dispatches nothing then (INVALID_OPERATION).
     */
    static String frameGap(PassComputePlan.PassCompute compute, IntPredicate bound, long indirectBufferSize) {
        for (PassComputePlan.Binding binding : compute.bindings()) {
            if (binding.kind() != PassComputePlan.Kind.STORAGE_BUFFER && !bound.test(binding.binding())) {
                return binding.symbol() + " has no image this frame";
            }
        }
        PassComputePlan.Indirect indirect = compute.indirect();
        if (indirect != null && indirect.offset() + PassComputePlan.INDIRECT_COUNTS_BYTES > indirectBufferSize) {
            return "indirect counts at " + indirect.offset() + " lie past bufferObject." + indirect.buffer();
        }
        return null;
    }

    /**
     * Why {@code compute}'s work groups for a {@code width} by {@code height} screen cannot be
     * dispatched over {@code workGroupSize}, or null when they can. A count past {@code limits}, the
     * device's maxComputeWorkGroupCount, or a negative one, as a negative workGroupsRender share
     * makes, fails glDispatchCompute with INVALID_VALUE, which dispatches nothing; Vulkan leaves it
     * undefined. A screen-sized count needs a positive work group size on x and y: one the module
     * does not give is Chimera's gap, where GL knows the size and Iris dispatches.
     */
    static String dispatchGap(PassComputePlan.PassCompute compute, int[] workGroupSize, int width, int height,
                              int[] limits) {
        if (compute.indirect() != null) return null;
        if (compute.workGroups() == null && (workGroupSize[0] <= 0 || workGroupSize[1] <= 0)) {
            return "its work group size is unknown";
        }
        int[] groups = compute.groups(width, height, workGroupSize);
        for (int axis = 0; axis < 3; axis++) {
            if (Integer.compareUnsigned(groups[axis], limits[axis]) > 0) {
                return "work groups " + groups[0] + "x" + groups[1] + "x" + groups[2]
                        + " lie outside the device's maxComputeWorkGroupCount " + Integer.toUnsignedString(limits[0])
                        + "x" + Integer.toUnsignedString(limits[1]) + "x" + Integer.toUnsignedString(limits[2]);
            }
        }
        return null;
    }

    /** The work group size a compute dispatches over: its module's, as Iris reads it from GL, else its layout's. */
    private static int[] workGroupSize(Loaded compute) {
        int[] module = compute.kernel().localSize();
        return module != null ? module : compute.plan().localSize();
    }

    /** The device's maxComputeWorkGroupCount; unbounded before a device. */
    private static int[] dispatchLimits() {
        if (DeviceManager.deviceProperties == null) return new int[] {-1, -1, -1};
        var counts = DeviceManager.deviceProperties.limits().maxComputeWorkGroupCount();
        return new int[] {counts.get(0), counts.get(1), counts.get(2)};
    }

    private void dispatch(MemoryStack stack, VkCommandBuffer commandBuffer, Loaded compute, List<VulkanImage> images,
                          ImageSources sources, int width, int height) {
        Map<VulkanImage, Boolean> written = new IdentityHashMap<>();
        for (PassComputePlan.Binding binding : compute.plan().bindings()) {
            if (writesTarget(binding)) written.put(images.get(binding.binding()), Boolean.TRUE);
        }
        ComputeKernel.FrameSlot frame = compute.kernel().currentFrame();
        for (PassComputePlan.Binding binding : compute.plan().bindings()) {
            VulkanImage image = images.get(binding.binding());
            if (binding.kind() == PassComputePlan.Kind.STORAGE_BUFFER) {
                // Written each dispatch: a relative buffer is a new one after a resize.
                PackStorageBuffer buffer = buffers.buffer(binding.target());
                compute.kernel().writeBuffer(frame, binding.binding(), buffer.getId(), buffer.getBufferSize());
            } else if (image instanceof PackStorageImage pack) {
                // Pack images stay in GENERAL: graphics programs and computes read and write them alike.
                // A storage binding reads the image through the view in its declared format.
                pack.transitionToGeneral(stack, commandBuffer);
                long view = binding.storage()
                        ? pack.view(IrisColorFormats.of(binding.format()).vkFormat()) : pack.getImageView();
                compute.kernel().writeImage(frame, binding.binding(), view,
                        binding.storage() ? 0L : pack.getSampler(), VK_IMAGE_LAYOUT_GENERAL);
            } else if (binding.storage()) {
                compute.kernel().writeImage(frame, binding.binding(),
                        TargetFormatViews.storageView(image, IrisColorFormats.of(binding.format()).vkFormat()), 0L,
                        VK_IMAGE_LAYOUT_GENERAL);
            } else {
                boolean general = written.containsKey(image);
                if (!general) {
                    image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
                compute.kernel().writeImage(frame, binding.binding(), sampledView(image),
                        sampler(binding, image, sources),
                        general ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        }
        for (VulkanImage image : written.keySet()) {
            VulkanImage.transitionLayout(stack, commandBuffer, image, image.getCurrentLayout(), VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        }
        PackVulkanBarriers.beforeCompute(commandBuffer, stack);
        compute.kernel().bind(commandBuffer, stack, frame, compute.plan().values());
        dispatchGroups(commandBuffer, stack, compute, width, height);
        PackVulkanBarriers.afterCompute(commandBuffer, stack);
        for (VulkanImage image : written.keySet()) {
            VulkanImage.transitionLayout(stack, commandBuffer, image, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
    }

    /** Whether a binding writes a colour or shadow colour target, which goes GENERAL for the dispatch. */
    private static boolean writesTarget(PassComputePlan.Binding binding) {
        return binding.kind() == PassComputePlan.Kind.TARGET_IMAGE
                || binding.kind() == PassComputePlan.Kind.SHADOW_COLOR_IMAGE;
    }

    /**
     * The view a sampled binding reads: the depth-only one a depth-stencil image registers (the
     * shadow map's depth and opaque depth are D24S8, and a sampled descriptor takes one aspect,
     * as programs read them through ChimeraImageDescriptorMixin), else the image's own.
     */
    private static long sampledView(VulkanImage image) {
        long depth = ChimeraDepthViewOverride.viewFor(image);
        return depth != 0L ? depth : image.getImageView();
    }

    /** The sampler a sampled binding reads through: a shadow image's as a program reads it, else the image's own. */
    private static long sampler(PassComputePlan.Binding binding, VulkanImage image, ImageSources sources) {
        return switch (binding.kind()) {
            case SHADOW_DEPTH, SHADOW_COLOR -> sources.shadowSampler(binding.resource(), image, false);
            case SHADOW_COMPARE -> sources.shadowSampler(binding.resource(), image, true);
            default -> image.getSampler();
        };
    }

    /**
     * Dispatches the work groups the pack's indirect buffer holds, once earlier writes to it are
     * visible, or Iris's work groups for this screen (ComputeProgram.dispatch), over the work group
     * size the compiled module declares.
     */
    private void dispatchGroups(VkCommandBuffer commandBuffer, MemoryStack stack, Loaded compute,
                                int width, int height) {
        PassComputePlan.PassCompute plan = compute.plan();
        PassComputePlan.Indirect indirect = plan.indirect();
        if (indirect == null) {
            int[] groups = plan.groups(width, height, workGroupSize(compute));
            vkCmdDispatch(commandBuffer, groups[0], groups[1], groups[2]);
            return;
        }
        PackVulkanBarriers.beforeIndirectDispatch(commandBuffer, stack);
        vkCmdDispatchIndirect(commandBuffer, buffers.buffer(indirect.buffer()).getId(), indirect.offset());
    }

    /** The image one binding reads or writes this frame, or null when it has none. */
    private VulkanImage image(PassComputePlan.Binding binding, ImageSources sources) {
        VulkanImage[] colors = sources.colors();
        return switch (binding.kind()) {
            case PACK_IMAGE, PACK_IMAGE_SAMPLER -> packImages == null ? null : packImages.image(binding.resource());
            case TEXTURE -> resources == null ? null : resources.image(binding.resource());
            case TARGET_IMAGE -> sources.storage(binding.target());
            case SHADOW_COLOR_IMAGE -> sources.shadow(binding.resource());
            case TARGET_SAMPLER -> binding.target() < colors.length ? colors[binding.target()] : null;
            case DEPTH -> sources.depth(binding.resource());
            case SHADOW_DEPTH, SHADOW_COMPARE, SHADOW_COLOR -> sources.shadow(binding.resource());
            case STORAGE_BUFFER -> null;
        };
    }

    /**
     * The image each binding reads or writes this frame, null for a buffer or an image missing this
     * frame; throws when a target image is not storable.
     */
    private List<VulkanImage> images(PassComputePlan.PassCompute compute, ImageSources sources) {
        List<VulkanImage> images = new ArrayList<>();
        for (PassComputePlan.Binding binding : compute.bindings()) {
            if (binding.kind() == PassComputePlan.Kind.STORAGE_BUFFER) {
                images.add(null);
                continue;
            }
            VulkanImage image = image(binding, sources);
            if (image != null && writesTarget(binding)
                    && (((ChimeraVulkanImageAccessor) image).chimera$usage() & VK_IMAGE_USAGE_STORAGE_BIT) == 0) {
                throw new IllegalStateException(binding.symbol() + " image was not created for storage");
            }
            images.add(image);
        }
        return images;
    }

    private static int[] descriptorTypes(PassComputePlan.PassCompute compute) {
        return compute.bindings().stream().mapToInt(binding -> binding.kind() == PassComputePlan.Kind.STORAGE_BUFFER
                ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : binding.storage()
                ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).toArray();
    }

    /**
     * Why this compute cannot run in this session: a pack texture it reads could not be loaded, a
     * binding's image or buffer is missing (bindingRefusal), or the buffer it dispatches from is.
     * Null when it can. Loads the compute's textures.
     */
    private static String refusal(PassComputePlan.PassCompute compute, PackResourceOwner resources,
                                  PackAdvancedImageOwner packImages, PackStorageBufferOwner buffers) {
        for (PackResourceBinding texture : compute.textures()) {
            if (resources == null || !resources.available(texture)) {
                return "pack texture " + texture.resourceKey() + " could not be loaded"
                        + (resources == null ? "" : ": " + resources.failure(texture.resourceKey()));
            }
        }
        for (PassComputePlan.Binding binding : compute.bindings()) {
            String refusal = bindingRefusal(binding, packImages, buffers);
            if (refusal != null) return refusal;
        }
        PassComputePlan.Indirect indirect = compute.indirect();
        if (indirect != null && (buffers == null || buffers.buffer(indirect.buffer()) == null)) {
            return "bufferObject." + indirect.buffer() + " for indirect dispatch was not allocated";
        }
        return null;
    }

    /**
     * Why a binding cannot be bound: its pack image was not created, its buffer was not allocated,
     * or the target it writes cannot be a storage image on this device. Null when it can.
     */
    private static String bindingRefusal(PassComputePlan.Binding binding, PackAdvancedImageOwner packImages,
                                         PackStorageBufferOwner buffers) {
        return switch (binding.kind()) {
            case PACK_IMAGE, PACK_IMAGE_SAMPLER -> packImages == null || packImages.image(binding.resource()) == null
                    ? "pack image " + binding.resource() + " was not created" : null;
            case STORAGE_BUFFER -> buffers == null || buffers.buffer(binding.target()) == null
                    ? "bufferObject." + binding.target() + " was not allocated"
                    + (buffers == null ? "" : ": " + buffers.failure(binding.target())) : null;
            case TARGET_IMAGE, SHADOW_COLOR_IMAGE -> storageSupported(PackConfig.FMT_TO_VK.getOrDefault(
                    binding.format().toUpperCase(Locale.ROOT), 0))
                    ? null : binding.symbol() + " format " + binding.format() + " cannot be a storage image";
            case TARGET_SAMPLER, TEXTURE, DEPTH, SHADOW_DEPTH, SHADOW_COMPARE, SHADOW_COLOR -> null;
        };
    }

    static boolean storageSupported(int format) {
        if (format == 0 || DeviceManager.physicalDevice == null) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, properties);
            return (properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0;
        }
    }

    @Override
    public void close() {
        for (Loaded compute : loaded) compute.kernel().close();
    }
}
