package net.chimera.render;

import net.chimera.mixin.ChimeraVulkanImageAccessor;
import net.chimera.shaderpack.ComputeValueBlock;
import net.chimera.shaderpack.PackConfig;
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
import java.util.TreeSet;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The session's pass computes (Iris CompositeRenderer): each dispatches right before its pass
 * draws, with {@code colorimgN} and {@code colortexN} bound to the image the pass reads. A written
 * image is in GENERAL for the dispatch and returns to the sampled layout afterwards, so the pass
 * and every later reader see what the compute stored.
 */
final class PackPassComputes implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    /** One compute that loaded: its plan and its pipeline. */
    private record Loaded(PassComputePlan.PassCompute plan, ComputeKernel kernel) {}

    private final List<Loaded> loaded;
    private final PackResourceOwner resources;
    private final Set<String> failed = new HashSet<>();

    private PackPassComputes(List<Loaded> loaded, PackResourceOwner resources) {
        this.loaded = loaded;
        this.resources = resources;
    }

    /** Builds a pipeline for every supported compute whose targets the device can store to; logs each outcome. */
    static PackPassComputes load(PassComputePlan plan, PackResourceOwner resources) {
        List<Loaded> loaded = new ArrayList<>();
        for (PassComputePlan.PassCompute compute : plan.computes()) {
            String refusal = compute.supported() ? storageRefusal(compute) : String.valueOf(compute.deviations());
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
        return new PackPassComputes(List.copyOf(loaded), resources);
    }

    /** The computes that loaded, in run order. */
    List<String> installedNames() {
        return loaded.stream().map(compute -> compute.plan().name()).toList();
    }

    /** The targets a loaded compute writes, which their images must allow as storage. */
    Set<Integer> storageTargets() {
        Set<Integer> targets = new TreeSet<>();
        for (Loaded compute : loaded) {
            compute.plan().bindings().stream().filter(PassComputePlan.Binding::storage)
                    .forEach(binding -> targets.add(binding.target()));
        }
        return targets;
    }

    /** Runs the computes of this pass, in order, over the images the pass is about to read. */
    void dispatchBefore(String pass, VkCommandBuffer commandBuffer, VulkanImage[] sources, int width, int height) {
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

    private void dispatch(MemoryStack stack, VkCommandBuffer commandBuffer, Loaded compute, VulkanImage[] sources,
                          int width, int height) {
        List<VulkanImage> images = images(compute.plan(), sources);
        Map<VulkanImage, Boolean> written = new IdentityHashMap<>();
        for (PassComputePlan.Binding binding : compute.plan().bindings()) {
            if (binding.storage()) written.put(images.get(binding.binding()), Boolean.TRUE);
        }
        ComputeKernel.FrameSlot frame = compute.kernel().currentFrame();
        for (PassComputePlan.Binding binding : compute.plan().bindings()) {
            VulkanImage image = images.get(binding.binding());
            if (binding.storage()) {
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

    /** The image each binding reads or writes this frame; throws when one is missing or not storable. */
    private List<VulkanImage> images(PassComputePlan.PassCompute compute, VulkanImage[] sources) {
        List<VulkanImage> images = new ArrayList<>();
        for (PassComputePlan.Binding binding : compute.bindings()) {
            VulkanImage image = binding.target() < 0 ? (resources == null ? null : resources.image("noisetex"))
                    : binding.target() < sources.length ? sources[binding.target()] : null;
            if (image == null) throw new IllegalStateException(binding.symbol() + " has no image this frame");
            if (binding.storage()
                    && (((ChimeraVulkanImageAccessor) image).chimera$usage() & VK_IMAGE_USAGE_STORAGE_BIT) == 0) {
                throw new IllegalStateException(binding.symbol() + " image was not created for storage");
            }
            images.add(image);
        }
        return images;
    }

    private static int[] descriptorTypes(PassComputePlan.PassCompute compute) {
        return compute.bindings().stream().mapToInt(binding -> binding.storage()
                ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).toArray();
    }

    /** Why a target this compute writes cannot be a storage image on this device, or null. */
    private static String storageRefusal(PassComputePlan.PassCompute compute) {
        for (PassComputePlan.Binding binding : compute.bindings()) {
            if (!binding.storage()) continue;
            if (!storageSupported(PackConfig.FMT_TO_VK.getOrDefault(binding.format().toUpperCase(Locale.ROOT), 0))) {
                return "colortex" + binding.target() + " format cannot be a storage image";
            }
        }
        return null;
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
