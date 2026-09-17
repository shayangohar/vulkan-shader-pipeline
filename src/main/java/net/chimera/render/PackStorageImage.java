package net.chimera.render;

import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_3D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_3D;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

/** A pack-owned true 3D image with storage and sampled descriptor views. */
final class PackStorageImage extends VulkanImage {
    private final long allocation;
    private final long view;
    private final int depth;
    private boolean freed;
    private boolean initialized;

    private PackStorageImage(
            String name,
            long image,
            long allocation,
            int format,
            int width,
            int height,
            int depth,
            int usage,
            long view,
            int formatSize,
            long sampler
    ) {
        super(name, image, format, 1, width, height, formatSize, usage, view);
        this.allocation = allocation;
        this.view = view;
        this.depth = depth;
        setSampler(sampler);
        setCurrentLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        this.initialized = false;
    }

    static PackStorageImage create(PackAdvancedResourcePlan.ImageSpec spec) {
        int format = format(spec.internalFormat());
        verifyFormatSupport(format, spec.name());
        int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_3D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().width(spec.width()).height(spec.height()).depth(spec.depth());
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            LongBuffer image = stack.mallocLong(1);
            PointerBuffer allocation = stack.mallocPointer(1);
            int result = vmaCreateImage(Vulkan.getAllocator(), info, allocationInfo,
                    image, allocation, null);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("advanced image allocation failed: " + result);
            }
            long imageId = image.get(0);
            long imageView = VulkanImage.createImageView(imageId, VK_IMAGE_VIEW_TYPE_3D,
                    format, VK_IMAGE_ASPECT_COLOR_BIT, 1, 0, 1);
            LongBuffer sampler = stack.mallocLong(1);
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(isInteger(spec.internalFormat()) ? VK_FILTER_NEAREST : VK_FILTER_LINEAR)
                    .minFilter(isInteger(spec.internalFormat()) ? VK_FILTER_NEAREST : VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK)
                    .unnormalizedCoordinates(false)
                    .minLod(0.0f)
                    .maxLod(0.0f);
            if (vkCreateSampler(Vulkan.getVkDevice(), samplerInfo, null, sampler) != VK_SUCCESS) {
                vkDestroyImageView(Vulkan.getVkDevice(), imageView, null);
                vmaDestroyImage(Vulkan.getAllocator(), imageId, allocation.get(0));
                throw new IllegalStateException("advanced image sampler allocation failed");
            }
            return new PackStorageImage("chimeraAdvanced_" + spec.name(), imageId,
                    allocation.get(0), format, spec.width(), spec.height(), spec.depth(),
                    usage, imageView, bytesPerVoxel(spec.internalFormat()), sampler.get(0));
        }
    }

    private static void verifyFormatSupport(int format, String name) {
        if (DeviceManager.physicalDevice == null) {
            throw new IllegalStateException("Vulkan physical device is unavailable for advanced image " + name);
        }
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, properties);
            int required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                    | VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
            if ((properties.optimalTilingFeatures() & required) != required) {
                throw new IllegalArgumentException("advanced image format lacks sampled/storage/transfer support: "
                        + name + " format=" + format);
            }
        }
    }

    int depth() { return depth; }

    /**
     * A persistent pack image still has undefined contents after allocation.
     * The first frame must seed it before a compute or graphics stage samples
     * it. This is separate from the pack's per-frame clear policy.
     */
    boolean initialized() { return initialized; }

    void markInitialized() { this.initialized = true; }

    void clearToZero(MemoryStack stack, VkCommandBuffer commandBuffer) {
        if (commandBuffer == null) return;
        VkClearColorValue clear = VkClearColorValue.calloc(stack);
        if (isIntegerFormat()) {
            IntBuffer values = stack.callocInt(4);
            clear.int32(values);
        } else {
            clear.float32(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));
        }
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        // Storage images remain in GENERAL for their complete lifetime. This
        // is the layout used by both imageStore and sampled aliases in the
        // Iris/Vitrail contract, and avoids VulkanMod's read-only transition
        // helper being applied to a storage-backed image.
        transitionToGeneral(stack, commandBuffer);
        vkCmdClearColorImage(commandBuffer, getId(), VK_IMAGE_LAYOUT_GENERAL, clear, range);
    }

    void transitionToGeneral(MemoryStack stack, VkCommandBuffer commandBuffer) {
        transition(stack, commandBuffer, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    }

    /**
     * VulkanMod's helper does not accept GENERAL as a source layout. Advanced
     * storage images use GENERAL between graphics and compute work, so their
     * complete state machine stays in this owner.
     */
    private void transition(
            MemoryStack stack,
            VkCommandBuffer commandBuffer,
            int newLayout,
            int destinationStage,
            int destinationAccess
    ) {
        if (commandBuffer == null || getCurrentLayout() == newLayout) return;
        int oldLayout = getCurrentLayout();
        int sourceStage;
        int sourceAccess;
        switch (oldLayout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> {
                sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                sourceAccess = 0;
            }
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                sourceAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
            }
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                sourceAccess = VK_ACCESS_TRANSFER_READ_BIT;
            }
            case VK_IMAGE_LAYOUT_GENERAL -> {
                sourceStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
                sourceAccess = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            }
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                sourceStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
                sourceAccess = VK_ACCESS_SHADER_READ_BIT;
            }
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> {
                sourceStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                sourceAccess = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                        | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            }
            default -> throw new IllegalStateException("unsupported advanced image source layout: " + oldLayout);
        }
        if (newLayout != VK_IMAGE_LAYOUT_GENERAL
                && newLayout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                && newLayout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            throw new IllegalArgumentException("unsupported advanced image destination layout: " + newLayout);
        }
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(getId());
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer, sourceStage, destinationStage,
                0, null, null, barrier);
        setCurrentLayout(newLayout);
    }

    @Override
    public long getImageView() { return view; }

    @Override
    public long getImageView(int ignoredFormat) { return view; }

    @Override
    public void free() {
        if (freed) return;
        freed = true;
        if (getSampler() != 0L) {
            vkDestroySampler(Vulkan.getVkDevice(), getSampler(), null);
            setSampler(0L);
        }
        vkDestroyImageView(Vulkan.getVkDevice(), view, null);
        vmaDestroyImage(Vulkan.getAllocator(), getId(), allocation);
    }

    private static int format(String value) {
        return switch (value.toLowerCase()) {
            case "r8ui" -> org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UINT;
            case "r16ui" -> org.lwjgl.vulkan.VK10.VK_FORMAT_R16_UINT;
            case "rgba16f" -> org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            default -> throw new IllegalArgumentException("unsupported advanced image format: " + value);
        };
    }

    private static int bytesPerVoxel(String value) {
        return switch (value.toLowerCase()) {
            case "r8ui" -> 1;
            case "r16ui" -> 2;
            case "rgba16f" -> 8;
            default -> 0;
        };
    }

    private static boolean isInteger(String value) {
        return "r8ui".equalsIgnoreCase(value) || "r16ui".equalsIgnoreCase(value);
    }

    private boolean isIntegerFormat() {
        return format == org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UINT
                || format == org.lwjgl.vulkan.VK10.VK_FORMAT_R16_UINT;
    }
}
