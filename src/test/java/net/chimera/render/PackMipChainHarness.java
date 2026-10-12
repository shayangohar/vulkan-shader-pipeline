package net.chimera.render;

import net.chimera.shaderpack.TargetSpec;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Mip chain sizing, plus an optional headless Vulkan run of the blit chain
 * PackPostTargets records before a program that declares colortexNMipmapEnabled.
 */
public final class PackMipChainHarness {
    private static final int FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;

    public static void main(String[] args) {
        require(spec(1920, 1080, true).mipLevels() == 11, "1080p chain");
        require(spec(2560, 1440, true).mipLevels() == 12, "1440p chain");
        require(spec(1, 1, true).mipLevels() == 1, "1x1 chain");
        require(spec(37, 5, true).mipLevels() == 6, "odd chain");
        require(spec(1920, 1080, false).mipLevels() == 1, "unmipmapped target");
        if (Boolean.getBoolean("chimera.mipChain.gpu")) {
            try (Gpu gpu = new Gpu()) {
                // Odd extents exercise the floor-halving the chain shares with glGenerateMipmap.
                gpu.verifyConstantChain(37, 5);
                gpu.verifyAverage();
            }
            System.out.println("[chimera] headless mip chain: every level written, linear average: PASS");
        }
        System.out.println("[chimera] mip chain sizing: PASS");
    }

    private static TargetSpec spec(int width, int height, boolean mipmapped) {
        return new TargetSpec(0, FORMAT, width, height, true, null, false, false, mipmapped, List.of());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("mip chain: " + message);
    }

    private static void ok(int result) {
        require(result == VK_SUCCESS, "Vulkan result " + result);
    }

    /** Does not start Minecraft or touch VulkanMod's global device. */
    private static final class Gpu implements AutoCloseable {
        private VkInstance instance;
        private VkDevice device;
        private VkQueue queue;
        private VkCommandBuffer command;
        private VkPhysicalDevice physical;
        private long pool;
        private long fence;

        Gpu() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.mallocPointer(1);
                VkInstanceCreateInfo create = VkInstanceCreateInfo.calloc(stack).sType$Default()
                        .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default()
                                .pApplicationName(stack.UTF8("Chimera mip chain test"))
                                .apiVersion(VK_MAKE_VERSION(1, 2, 0)));
                ok(vkCreateInstance(create, null, pointer));
                instance = new VkInstance(pointer.get(0), create);
                var count = stack.mallocInt(1);
                ok(vkEnumeratePhysicalDevices(instance, count, null));
                require(count.get(0) > 0, "no Vulkan device");
                PointerBuffer devices = stack.mallocPointer(count.get(0));
                ok(vkEnumeratePhysicalDevices(instance, count, devices));
                physical = new VkPhysicalDevice(devices.get(0), instance);
                vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
                VkQueueFamilyProperties.Buffer queues = VkQueueFamilyProperties.calloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(physical, count, queues);
                int family = -1;
                for (int i = 0; i < queues.remaining(); i++) {
                    // vkCmdBlitImage requires a graphics queue.
                    if ((queues.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) { family = i; break; }
                }
                require(family >= 0, "no graphics queue");
                VkDeviceQueueCreateInfo.Buffer queueCreate = VkDeviceQueueCreateInfo.calloc(1, stack)
                        .sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
                VkDeviceCreateInfo deviceCreate = VkDeviceCreateInfo.calloc(stack).sType$Default()
                        .pQueueCreateInfos(queueCreate);
                ok(vkCreateDevice(physical, deviceCreate, null, pointer));
                device = new VkDevice(pointer.get(0), physical, deviceCreate);
                vkGetDeviceQueue(device, family, 0, pointer);
                queue = new VkQueue(pointer.get(0), device);
                LongBuffer handle = stack.mallocLong(1);
                ok(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                        .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),
                        null, handle));
                pool = handle.get(0);
                ok(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer));
                command = new VkCommandBuffer(pointer.get(0), device);
                ok(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle));
                fence = handle.get(0);
                VkFormatProperties properties = VkFormatProperties.calloc(stack);
                vkGetPhysicalDeviceFormatProperties(physical, FORMAT, properties);
                int required = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT
                        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
                require((properties.optimalTilingFeatures() & required) == required, "RGBA16F blit support");
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        /** A constant level 0 must reach every level; untouched levels keep the poison value. */
        void verifyConstantChain(int width, int height) {
            int levels = spec(width, height, true).mipLevels();
            float[][] read = run(width, height, levels, null, new float[] {2.0f, 0.5f, -1.0f, 1.0f});
            for (int level = 0; level < levels; level++) {
                float[] texels = read[level];
                for (int i = 0; i < texels.length; i += 4) {
                    require(texels[i] == 2.0f && texels[i + 1] == 0.5f && texels[i + 2] == -1.0f
                                    && texels[i + 3] == 1.0f,
                            "level " + level + " texel " + i / 4 + " was not generated");
                }
            }
        }

        /** Two texels average into the 1x1 level, as a linear downsample does. */
        void verifyAverage() {
            float[][] read = run(2, 1, 2, new float[] {0, 0, 0, 1, 4, 8, 16, 1}, null);
            float[] top = read[1];
            require(top[0] == 2.0f && top[1] == 4.0f && top[2] == 8.0f && top[3] == 1.0f,
                    "1x1 level is not the linear average: " + java.util.Arrays.toString(top));
        }

        /** Seeds level 0 (texels or a clear), poisons the rest, records the chain, reads every level. */
        private float[][] run(int width, int height, int levels, float[] texels, float[] clear) {
            long image = 0, memory = 0, buffer = 0, bufferMemory = 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer handle = stack.mallocLong(1);
                ok(vkCreateImage(device, VkImageCreateInfo.calloc(stack).sType$Default()
                        .imageType(VK_IMAGE_TYPE_2D).format(FORMAT).extent(e -> e.set(width, height, 1))
                        .mipLevels(levels).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                                | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED),
                        null, handle));
                image = handle.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                vkGetImageMemoryRequirements(device, image, requirements);
                memory = allocate(stack, requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                ok(vkBindImageMemory(device, image, memory, 0));

                long bytes = 0;
                for (int level = 0; level < levels; level++) {
                    bytes += (long) Math.max(1, width >> level) * Math.max(1, height >> level) * 8;
                }
                ok(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                        .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle));
                buffer = handle.get(0);
                VkMemoryRequirements bufferRequirements = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device, buffer, bufferRequirements);
                bufferMemory = allocate(stack, bufferRequirements,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                ok(vkBindBufferMemory(device, buffer, bufferMemory, 0));
                PointerBuffer pointer = stack.mallocPointer(1);
                ok(vkMapMemory(device, bufferMemory, 0, bytes, 0, pointer));
                ByteBuffer mapped = MemoryUtil.memByteBuffer(pointer.get(0), (int) bytes);
                if (texels != null) {
                    for (int i = 0; i < texels.length; i++) mapped.putShort(i * 2, Float.floatToFloat16(texels[i]));
                }

                ok(vkResetCommandBuffer(command, 0));
                ok(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()));
                barrier(stack, image, 0, levels, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                VkImageSubresourceRange.Buffer all = VkImageSubresourceRange.calloc(1, stack);
                all.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(levels).layerCount(1);
                VkClearColorValue poison = VkClearColorValue.calloc(stack).float32(stack.floats(-7, -7, -7, -7));
                vkCmdClearColorImage(command, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, poison, all);
                barrier(stack, image, 0, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                if (texels != null) {
                    VkBufferImageCopy.Buffer upload = VkBufferImageCopy.calloc(1, stack);
                    upload.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    upload.get(0).imageExtent().set(width, height, 1);
                    vkCmdCopyBufferToImage(command, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, upload);
                } else {
                    VkImageSubresourceRange.Buffer base = VkImageSubresourceRange.calloc(1, stack);
                    base.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    VkClearColorValue value = VkClearColorValue.calloc(stack).float32(stack.floats(clear));
                    vkCmdClearColorImage(command, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, value, base);
                }
                barrier(stack, image, 0, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

                PackPostTargets.recordMipChain(stack, command, image, width, height, levels);

                barrier(stack, image, 0, levels, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_READ_BIT,
                        VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                VkBufferImageCopy.Buffer readback = VkBufferImageCopy.calloc(levels, stack);
                long offset = 0;
                for (int level = 0; level < levels; level++) {
                    int levelWidth = Math.max(1, width >> level);
                    int levelHeight = Math.max(1, height >> level);
                    readback.get(level).bufferOffset(offset);
                    readback.get(level).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(level).layerCount(1);
                    readback.get(level).imageExtent().set(levelWidth, levelHeight, 1);
                    offset += (long) levelWidth * levelHeight * 8;
                }
                vkCmdCopyImageToBuffer(command, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, readback);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                        VkMemoryBarrier.calloc(1, stack).sType$Default()
                                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT),
                        null, null);
                ok(vkEndCommandBuffer(command));
                ok(vkResetFences(device, fence));
                ok(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(command.address())), fence));
                ok(vkWaitForFences(device, fence, true, 10_000_000_000L));

                float[][] result = new float[levels][];
                int at = 0;
                for (int level = 0; level < levels; level++) {
                    int count = Math.max(1, width >> level) * Math.max(1, height >> level) * 4;
                    result[level] = new float[count];
                    for (int i = 0; i < count; i++, at += 2) {
                        result[level][i] = Float.float16ToFloat(mapped.getShort(at));
                    }
                }
                vkUnmapMemory(device, bufferMemory);
                return result;
            } finally {
                if (buffer != 0) vkDestroyBuffer(device, buffer, null);
                if (bufferMemory != 0) vkFreeMemory(device, bufferMemory, null);
                if (image != 0) vkDestroyImage(device, image, null);
                if (memory != 0) vkFreeMemory(device, memory, null);
            }
        }

        private void barrier(MemoryStack stack, long image, int baseLevel, int levels, int oldLayout,
                             int newLayout, int sourceAccess, int destinationAccess, int sourceStage) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .oldLayout(oldLayout).newLayout(newLayout).image(image)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(baseLevel).levelCount(levels).layerCount(1);
            vkCmdPipelineBarrier(command, sourceStage, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
        }

        private long allocate(MemoryStack stack, VkMemoryRequirements requirements, int properties) {
            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physical, memory);
            for (int i = 0; i < memory.memoryTypeCount(); i++) {
                if ((requirements.memoryTypeBits() & (1 << i)) != 0
                        && (memory.memoryTypes(i).propertyFlags() & properties) == properties) {
                    LongBuffer handle = stack.mallocLong(1);
                    ok(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                            .allocationSize(requirements.size()).memoryTypeIndex(i), null, handle));
                    return handle.get(0);
                }
            }
            throw new AssertionError("mip chain: no memory type " + properties);
        }

        @Override
        public void close() {
            if (device != null) {
                vkDeviceWaitIdle(device);
                if (fence != 0) vkDestroyFence(device, fence, null);
                if (pool != 0) vkDestroyCommandPool(device, pool, null);
                vkDestroyDevice(device, null);
                device = null;
            }
            if (instance != null) {
                vkDestroyInstance(instance, null);
                instance = null;
            }
        }
    }
}
