package net.chimera.render;

import net.chimera.shaderpack.RawTextureSpec;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.StagingBuffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A pack raw texture (Iris GlTexture over CustomTextureData.RawData*): a 1D, 2D or 3D image
 * holding the file's pixels unchanged, three channels widened to four with alpha 1. It uploads
 * through VulkanMod's image upload commands, as pack PNG textures do, and stays read-only.
 */
final class PackRawTexture extends VulkanImage implements PackResourceOwner.LoadedTexture {
    /** Iris reads the whole file; this only refuses a declaration far past any real lookup table. */
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    /** Vulkan formats by kind and bits, for one, two and four channels. */
    private static final Map<String, int[]> FORMATS = Map.ofEntries(
            Map.entry("UNORM8", new int[] {VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8B8A8_UNORM}),
            Map.entry("SNORM8", new int[] {VK_FORMAT_R8_SNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8B8A8_SNORM}),
            Map.entry("UINT8", new int[] {VK_FORMAT_R8_UINT, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8B8A8_UINT}),
            Map.entry("SINT8", new int[] {VK_FORMAT_R8_SINT, VK_FORMAT_R8G8_SINT, VK_FORMAT_R8G8B8A8_SINT}),
            Map.entry("UNORM16", new int[] {VK_FORMAT_R16_UNORM, VK_FORMAT_R16G16_UNORM,
                    VK_FORMAT_R16G16B16A16_UNORM}),
            Map.entry("SNORM16", new int[] {VK_FORMAT_R16_SNORM, VK_FORMAT_R16G16_SNORM,
                    VK_FORMAT_R16G16B16A16_SNORM}),
            Map.entry("FLOAT16", new int[] {VK_FORMAT_R16_SFLOAT, VK_FORMAT_R16G16_SFLOAT,
                    VK_FORMAT_R16G16B16A16_SFLOAT}),
            Map.entry("UINT16", new int[] {VK_FORMAT_R16_UINT, VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16B16A16_UINT}),
            Map.entry("SINT16", new int[] {VK_FORMAT_R16_SINT, VK_FORMAT_R16G16_SINT, VK_FORMAT_R16G16B16A16_SINT}),
            Map.entry("FLOAT32", new int[] {VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT,
                    VK_FORMAT_R32G32B32A32_SFLOAT}),
            Map.entry("UINT32", new int[] {VK_FORMAT_R32_UINT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32A32_UINT}),
            Map.entry("SINT32", new int[] {VK_FORMAT_R32_SINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32B32A32_SINT}));

    private final long allocation;
    private final long view;
    private long sampler;
    private boolean freed;

    private PackRawTexture(long image, long allocation, int format, int width, int height,
                           int texelBytes, long view, long sampler) {
        super(image, format, 1, width, height, texelBytes, VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_DST_BIT, view);
        this.allocation = allocation;
        this.view = view;
        this.sampler = sampler;
        setCurrentLayout(VK_IMAGE_LAYOUT_UNDEFINED);
    }

    /** Reads, creates and queues the upload of one raw texture; filter and wrap are the plan's. */
    static PackRawTexture load(Path file, RawTextureSpec spec, String name, String filter, String wrap)
            throws IOException {
        if (spec.unsupportedReason() != null) throw new IOException("raw texture " + spec.unsupportedReason());
        if (spec.requiredBytes() > MAX_BYTES) throw new IOException("raw texture exceeds size limit");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < spec.requiredBytes()) {
            throw new IOException("raw texture holds " + bytes.length + " bytes, needs " + spec.requiredBytes());
        }
        RawTextureSpec.Storage storage = spec.storage();
        int format = vulkanFormat(storage);
        boolean linear = !"nearest".equals(filter) && filters(format);
        ByteBuffer pixels = texels(bytes, spec, storage);
        try {
            PackRawTexture texture = create(spec, storage, format, name, linear, "clamp".equals(wrap));
            texture.upload(pixels, spec, storage.uploadedChannels() * storage.bits() / 8);
            return texture;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /** The file's rows without GL's 4-byte row padding, three channels widened to four. */
    static ByteBuffer texels(byte[] bytes, RawTextureSpec spec, RawTextureSpec.Storage storage) {
        int channelBytes = storage.bits() / 8;
        int texelBytes = storage.uploadedChannels() * channelBytes;
        int sourceTexel = storage.channels() * channelBytes;
        byte[] one = storage.one();
        int rows = spec.extentHeight() * spec.extentDepth();
        ByteBuffer out = MemoryUtil.memAlloc(rows * spec.width() * texelBytes);
        for (int row = 0; row < rows; row++) {
            int source = row * spec.rowStride();
            for (int x = 0; x < spec.width(); x++) {
                out.put(bytes, source + x * sourceTexel, sourceTexel);
                if (storage.channels() == 3) out.put(one);
            }
        }
        return out.flip();
    }

    private static PackRawTexture create(RawTextureSpec spec, RawTextureSpec.Storage storage, int format,
                                         String name, boolean linear, boolean clamp) {
        verifySampled(format, name);
        int dimensions = spec.dimensions();
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(dimensions == 1 ? VK_IMAGE_TYPE_1D : dimensions == 2 ? VK_IMAGE_TYPE_2D
                            : VK_IMAGE_TYPE_3D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().width(spec.width()).height(spec.extentHeight()).depth(spec.extentDepth());
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            LongBuffer image = stack.mallocLong(1);
            PointerBuffer allocation = stack.mallocPointer(1);
            if (vmaCreateImage(Vulkan.getAllocator(), info, allocationInfo, image, allocation, null) != VK_SUCCESS) {
                throw new IllegalStateException("raw texture allocation failed: " + name);
            }
            long view = ImageViews.create(image.get(0), dimensions == 1 ? VK_IMAGE_VIEW_TYPE_1D
                    : dimensions == 2 ? VK_IMAGE_VIEW_TYPE_2D : VK_IMAGE_VIEW_TYPE_3D,
                    format, VK_IMAGE_ASPECT_COLOR_BIT);
            int address = clamp ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT;
            int vkFilter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            // Iris GlTexture: no mipmaps, LOD pinned to 0, no bias.
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(vkFilter)
                    .minFilter(vkFilter)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(address)
                    .addressModeV(address)
                    .addressModeW(address)
                    .borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK)
                    .minLod(0.0F)
                    .maxLod(0.0F);
            LongBuffer sampler = stack.mallocLong(1);
            if (vkCreateSampler(Vulkan.getVkDevice(), samplerInfo, null, sampler) != VK_SUCCESS) {
                vkDestroyImageView(Vulkan.getVkDevice(), view, null);
                vmaDestroyImage(Vulkan.getAllocator(), image.get(0), allocation.get(0));
                throw new IllegalStateException("raw texture sampler allocation failed: " + name);
            }
            return new PackRawTexture(image.get(0), allocation.get(0), format, spec.width(), spec.extentHeight(),
                    storage.uploadedChannels() * storage.bits() / 8, view, sampler.get(0));
        }
    }

    /**
     * Records the copy as VulkanMod records its texture uploads: into the graphics queue's upload
     * commands, which run before the next frame draws. The staging buffer grows to fit.
     */
    private void upload(ByteBuffer pixels, RawTextureSpec spec, int texelBytes) {
        StagingBuffer staging = Vulkan.getStagingBuffer();
        staging.align(texelBytes);
        staging.copyBuffer(pixels.remaining(), pixels);
        CommandPool.CommandBuffer commands = DeviceManager.getGraphicsQueue().getCommandBuffer();
        VkCommandBuffer commandBuffer = commands.getHandle();
        try (MemoryStack stack = stackPush()) {
            transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(staging.getOffset());
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                    .baseArrayLayer(0).layerCount(1);
            region.imageExtent().set(spec.width(), spec.extentHeight(), spec.extentDepth());
            vkCmdCopyBufferToImage(commandBuffer, staging.getId(), getId(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        if (DeviceManager.getGraphicsQueue().endIfNeeded(commands) != VK_NULL_HANDLE) {
            Synchronization.INSTANCE.addCommandBuffer(commands);
        }
    }

    private static void verifySampled(int format, String name) {
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, properties);
            if ((properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) == 0) {
                throw new IllegalArgumentException("raw texture format cannot be sampled: " + name);
            }
        }
    }

    /** Whether the device filters this format linearly; Iris's blur falls back to nearest otherwise. */
    private static boolean filters(int format) {
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, properties);
            return (properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
        }
    }

    /** The Vulkan format of an uploaded raw texture (three channels already widened to four). */
    static int vulkanFormat(RawTextureSpec.Storage storage) {
        int[] formats = FORMATS.get(storage.kind().name() + storage.bits());
        if (formats == null) throw new IllegalArgumentException("no Vulkan format for " + storage);
        int channels = storage.uploadedChannels();
        return formats[channels == 4 ? 2 : channels - 1];
    }

    @Override
    public VulkanImage image() {
        return this;
    }

    @Override
    public long getImageView() { return view; }

    /** The texture's own sampler: its pack's filter and wrap, on one level. */
    @Override
    public long getSampler() { return sampler; }

    @Override
    public void close() {
        free();
    }

    @Override
    public void free() {
        if (freed) return;
        freed = true;
        if (sampler != 0L) {
            vkDestroySampler(Vulkan.getVkDevice(), sampler, null);
            sampler = 0L;
        }
        vkDestroyImageView(Vulkan.getVkDevice(), view, null);
        vmaDestroyImage(Vulkan.getAllocator(), getId(), allocation);
    }
}
