package net.chimera.render;

import net.chimera.shaderpack.IrisColorFormats;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_1D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_3D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_1D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_3D;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;

/** A pack-owned 1D, 2D or 3D image (Iris's custom image) with storage and sampled descriptor views. */
final class PackStorageImage extends VulkanImage {
    private final long allocation;
    private final long view;
    private final int ownFormat;
    private final int viewType;
    private final Map<Integer, Long> views = new HashMap<>();
    private final int depth;
    private long sampler;
    private boolean freed;
    private boolean initialized;

    private PackStorageImage(
            long image,
            long allocation,
            int format,
            int width,
            int height,
            int depth,
            int usage,
            long view,
            int viewType,
            int formatSize,
            long sampler
    ) {
        super(image, format, 1, width, height, formatSize, usage, view);
        this.allocation = allocation;
        this.view = view;
        this.ownFormat = format;
        this.viewType = viewType;
        this.depth = depth;
        this.sampler = sampler;
        setCurrentLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        this.initialized = false;
    }

    /**
     * The image {@code spec} declares at {@code extent} (width, height, depth), of its own dimensions.
     * A program reading it through another format of its size needs it mutable, with a storage view
     * in each of {@code viewFormats}; a format the device cannot store refuses the image.
     */
    static PackStorageImage create(PackAdvancedResourcePlan.ImageSpec spec, int[] extent, Set<Integer> viewFormats) {
        int format = spec.format().vkFormat();
        boolean integer = IrisColorFormats.integer(format);
        boolean mutableFormat = !viewFormats.isEmpty();
        verifyFormatSupport(format, spec.name());
        viewFormats.forEach(view -> verifyStorageSupport(view, spec.name()));
        String limit = extentLimitFailure(spec, extent);
        if (limit != null) throw new IllegalStateException(limit);
        int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .flags(mutableFormat ? VK10.VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT : 0)
                    .imageType(imageType(spec.dimensions()))
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().width(extent[0]).height(extent[1]).depth(extent[2]);
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
            long imageView = ImageViews.create(imageId, viewType(spec.dimensions()), format, VK_IMAGE_ASPECT_COLOR_BIT);
            LongBuffer sampler = stack.mallocLong(1);
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(integer ? VK_FILTER_NEAREST : VK_FILTER_LINEAR)
                    .minFilter(integer ? VK_FILTER_NEAREST : VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK)
                    .unnormalizedCoordinates(false)
                    .minLod(0.0f)
                    .maxLod(0.0f);
            if (vkCreateSampler(Vulkan.getVkDevice(), samplerInfo, null, sampler) != VK_SUCCESS) {
                vkDestroyImageView(Vulkan.getVkDevice(), imageView, null);
                vmaDestroyImage(Vulkan.getAllocator(), imageId, allocation.get(0));
                throw new IllegalStateException("advanced image sampler allocation failed");
            }
            PackStorageImage created = new PackStorageImage(imageId,
                    allocation.get(0), format, extent[0], extent[1], extent[2],
                    usage, imageView, viewType(spec.dimensions()), spec.format().bytes(), sampler.get(0));
            try {
                viewFormats.forEach(view -> created.views.put(view,
                        ImageViews.createStorage(imageId, viewType(spec.dimensions()), view)));
            } catch (RuntimeException failure) {
                created.free();
                throw failure;
            }
            return created;
        }
    }

    private static int imageType(int dimensions) {
        return dimensions == 1 ? VK_IMAGE_TYPE_1D : dimensions == 2 ? VK_IMAGE_TYPE_2D : VK_IMAGE_TYPE_3D;
    }

    private static int viewType(int dimensions) {
        return dimensions == 1 ? VK_IMAGE_VIEW_TYPE_1D
                : dimensions == 2 ? VK_IMAGE_VIEW_TYPE_2D : VK_IMAGE_VIEW_TYPE_3D;
    }

    /** Why the device cannot create {@code spec} at {@code extent} (PackImageLimits), or null when it can. */
    static String extentLimitFailure(PackAdvancedResourcePlan.ImageSpec spec, int[] extent) {
        return PackImageLimits.deviceFailure("advanced image " + spec.name(), spec.dimensions(), extent);
    }

    /** A view format must store: a program writes the image through it. */
    private static void verifyStorageSupport(int format, String name) {
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, properties);
            if ((properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
                throw new IllegalArgumentException("advanced image view format lacks storage support: "
                        + name + " format=" + format);
            }
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
        if (IrisColorFormats.integer(format)) {
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
        // The clear is a transfer write: it waits for the image's last access,
        // and the shaders that read or store the image next wait for the clear.
        int oldLayout = getCurrentLayout();
        Access last = lastAccess(oldLayout);
        barrier(stack, commandBuffer, oldLayout, VK_IMAGE_LAYOUT_GENERAL, last,
                new Access(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT));
        setCurrentLayout(VK_IMAGE_LAYOUT_GENERAL);
        vkCmdClearColorImage(commandBuffer, getId(), VK_IMAGE_LAYOUT_GENERAL, clear, range);
        barrier(stack, commandBuffer, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                new Access(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT),
                lastAccess(VK_IMAGE_LAYOUT_GENERAL));
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
        if (newLayout != VK_IMAGE_LAYOUT_GENERAL
                && newLayout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                && newLayout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            throw new IllegalArgumentException("unsupported advanced image destination layout: " + newLayout);
        }
        int oldLayout = getCurrentLayout();
        barrier(stack, commandBuffer, oldLayout, newLayout, lastAccess(oldLayout),
                new Access(destinationStage, destinationAccess));
        setCurrentLayout(newLayout);
    }

    /** A pipeline stage and the accesses it makes to the image there. */
    private record Access(int stage, int mask) {}

    /**
     * What a barrier out of {@code layout} waits for: the image's last use in it. A GENERAL image
     * is last used by shaders; a clear in GENERAL hands it back to them with a barrier of its own.
     */
    private static Access lastAccess(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> new Access(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0);
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL ->
                    new Access(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL ->
                    new Access(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            case VK_IMAGE_LAYOUT_GENERAL -> new Access(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ->
                    new Access(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_SHADER_READ_BIT);
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> new Access(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            default -> throw new IllegalStateException("unsupported advanced image source layout: " + layout);
        };
    }

    private void barrier(MemoryStack stack, VkCommandBuffer commandBuffer, int oldLayout, int newLayout,
                         Access source, Access destination) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(source.mask())
                .dstAccessMask(destination.mask())
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(getId());
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer, source.stage(), destination.stage(),
                0, null, null, barrier);
    }

    @Override
    public long getImageView() { return view; }

    /** The image's storage view in {@code format}, made with the image; its own view for its own format. */
    long view(int format) {
        if (format == ownFormat || format == 0) return view;
        return views.getOrDefault(format, view);
    }

    /** The image's own sampler: nearest for integer formats, linear otherwise, repeating, on one level. */
    @Override
    public long getSampler() { return sampler; }

    /** Retires the image with its views and sampler once no frame can use them (HandleRetirement). */
    @Override
    public void free() {
        if (freed) return;
        freed = true;
        long ownSampler = sampler;
        List<Long> aliases = List.copyOf(views.values());
        long image = getId();
        sampler = 0L;
        views.clear();
        HandleRetirement.retire(() -> {
            if (ownSampler != 0L) vkDestroySampler(Vulkan.getVkDevice(), ownSampler, null);
            vkDestroyImageView(Vulkan.getVkDevice(), view, null);
            aliases.forEach(alias -> vkDestroyImageView(Vulkan.getVkDevice(), alias, null));
            vmaDestroyImage(Vulkan.getAllocator(), image, allocation);
        });
    }
}
