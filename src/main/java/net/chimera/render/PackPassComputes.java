package net.chimera.render;

import net.chimera.mixin.ChimeraVulkanImageAccessor;
import net.chimera.shaderpack.ComputeValueBlock;
import net.chimera.shaderpack.PackConfig;
import net.chimera.shaderpack.PackResourceBinding;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The session's pass computes (Iris CompositeRenderer): each dispatches right before its pass
 * draws, with {@code colorimgN} and {@code colortexN} bound to the image the pass reads. A written
 * target is in GENERAL for the dispatch and returns to the sampled layout afterwards, so the pass
 * and every later reader see what the compute stored. Pack images and their samplers bind the
 * session's images, which stay in GENERAL. Pack textures bind the session's, loaded on first use
 * when no program reads them. Storage blocks bind the session's buffers whole, as programs do.
 */
final class PackPassComputes implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    /** One compute that loaded: its plan and its pipeline. */
    private record Loaded(PassComputePlan.PassCompute plan, ComputeKernel kernel) {}

    /** The images a pass reads now: its colour targets, and each depthtexN as its program samples it. */
    record ImageSources(VulkanImage[] colors, Function<String, VulkanImage> depth) {}

    private final List<Loaded> loaded;
    private final PackResourceOwner resources;
    private final PackAdvancedImageOwner packImages;
    private final PackStorageBufferOwner buffers;
    private final Set<String> failed = new HashSet<>();

    private PackPassComputes(List<Loaded> loaded, PackResourceOwner resources, PackAdvancedImageOwner packImages,
                             PackStorageBufferOwner buffers) {
        this.loaded = loaded;
        this.resources = resources;
        this.packImages = packImages;
        this.buffers = buffers;
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

    /** Each pass some loaded compute runs before, with the depth snapshots its computes read. */
    Map<String, Set<String>> depthReadsByPass() {
        Map<String, Set<String>> passes = new TreeMap<>();
        for (Loaded compute : loaded) {
            Set<String> reads = passes.computeIfAbsent(compute.plan().pass(), ignored -> new TreeSet<>());
            compute.plan().bindings().stream().filter(binding -> binding.kind() == PassComputePlan.Kind.DEPTH)
                    .forEach(binding -> reads.add(binding.resource()));
        }
        return passes;
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

    /** The targets a loaded compute writes, which their images must allow as storage. */
    Set<Integer> storageTargets() {
        Set<Integer> targets = new TreeSet<>();
        for (Loaded compute : loaded) {
            compute.plan().bindings().stream().filter(binding -> binding.kind() == PassComputePlan.Kind.TARGET_IMAGE)
                    .forEach(binding -> targets.add(binding.target()));
        }
        return targets;
    }

    /** Runs the computes of this pass, in order, over the images the pass is about to read. */
    void dispatchBefore(String pass, VkCommandBuffer commandBuffer, ImageSources sources, int width, int height) {
        for (Loaded compute : loaded) {
            if (!compute.plan().pass().equals(pass) || failed.contains(compute.plan().name())) continue;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                dispatch(stack, commandBuffer, compute, sources, width, height);
            } catch (RuntimeException failure) {
                failed.add(compute.plan().name());
                LOGGER.warn("[chimera] pack compute {} dispatch failed; it no longer runs: {}",
                        compute.plan().name(), failure.getMessage(), failure);
            }
        }
    }

    private void dispatch(MemoryStack stack, VkCommandBuffer commandBuffer, Loaded compute, ImageSources sources,
                          int width, int height) {
        List<VulkanImage> images = images(compute.plan(), sources);
        Map<VulkanImage, Boolean> written = new IdentityHashMap<>();
        for (PassComputePlan.Binding binding : compute.plan().bindings()) {
            if (binding.kind() == PassComputePlan.Kind.TARGET_IMAGE) {
                written.put(images.get(binding.binding()), Boolean.TRUE);
            }
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
                pack.transitionToGeneral(stack, commandBuffer);
                compute.kernel().writeImage(frame, binding.binding(), pack.getImageView(),
                        binding.storage() ? 0L : pack.getSampler(), VK_IMAGE_LAYOUT_GENERAL);
            } else if (binding.storage()) {
                compute.kernel().writeImage(frame, binding.binding(), PackPostTargets.attachmentView(image), 0L,
                        VK_IMAGE_LAYOUT_GENERAL);
            } else {
                boolean general = written.containsKey(image);
                if (!general) {
                    image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
                compute.kernel().writeImage(frame, binding.binding(), image.getImageView(), image.getSampler(),
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
        int[] groups = compute.plan().groups(width, height);
        vkCmdDispatch(commandBuffer, groups[0], groups[1], groups[2]);
        PackVulkanBarriers.afterCompute(commandBuffer, stack);
        for (VulkanImage image : written.keySet()) {
            VulkanImage.transitionLayout(stack, commandBuffer, image, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
    }

    /**
     * The image each binding reads or writes this frame, null for a buffer; throws when one is
     * missing or not storable.
     */
    private List<VulkanImage> images(PassComputePlan.PassCompute compute, ImageSources sources) {
        List<VulkanImage> images = new ArrayList<>();
        for (PassComputePlan.Binding binding : compute.bindings()) {
            if (binding.kind() == PassComputePlan.Kind.STORAGE_BUFFER) {
                images.add(null);
                continue;
            }
            VulkanImage[] colors = sources.colors();
            VulkanImage image = switch (binding.kind()) {
                case PACK_IMAGE, PACK_IMAGE_SAMPLER -> packImages == null ? null : packImages.image(binding.resource());
                case TEXTURE -> resources == null ? null : resources.image(binding.resource());
                case TARGET_IMAGE, TARGET_SAMPLER -> binding.target() < colors.length ? colors[binding.target()] : null;
                case DEPTH -> sources.depth().apply(binding.resource());
                case STORAGE_BUFFER -> null;
            };
            if (image == null) throw new IllegalStateException(binding.symbol() + " has no image this frame");
            if (binding.kind() == PassComputePlan.Kind.TARGET_IMAGE
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
     * Why this compute cannot run in this session: a pack texture it reads could not be loaded, or
     * a binding's image or buffer is missing (bindingRefusal). Null when it can. Loads the
     * compute's textures.
     */
    private static String refusal(PassComputePlan.PassCompute compute, PackResourceOwner resources,
                                  PackAdvancedImageOwner packImages, PackStorageBufferOwner buffers) {
        for (PackResourceBinding texture : compute.textures()) {
            if (resources == null || resources.image(texture) == null) {
                return "pack texture " + texture.resourceKey() + " could not be loaded"
                        + (resources == null ? "" : ": " + resources.failure(texture.resourceKey()));
            }
        }
        for (PassComputePlan.Binding binding : compute.bindings()) {
            String refusal = bindingRefusal(binding, packImages, buffers);
            if (refusal != null) return refusal;
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
            case TARGET_IMAGE -> storageSupported(PackConfig.FMT_TO_VK.getOrDefault(
                    binding.format().toUpperCase(Locale.ROOT), 0))
                    ? null : "colortex" + binding.target() + " format cannot be a storage image";
            case TARGET_SAMPLER, TEXTURE, DEPTH -> null;
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
