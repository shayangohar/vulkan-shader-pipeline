package net.chimera.render;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/** CPU/compiler checks, plus an optional headless Vulkan test of the actual scan shader. */
public final class PackNanTripwireHarness {
    public static void main(String[] args) {
        ByteBuffer results = ByteBuffer.allocate(PackNanTripwire.RESULT_BYTES).order(ByteOrder.nativeOrder());
        PackNanTripwire.initializeResults(results);
        for (int word = 0; word < results.capacity() / 4; word++) {
            require(results.getInt(word * 4) == (word % 4 == 2 ? -1 : 0), "result reset " + word);
        }
        for (int extent : new int[] {1, 4, 63, 64, 65, 127, 128, 129, 1919, 1920, 3840}) {
            int groups = PackNanTripwire.dispatchGroups(extent);
            require(groups * PackNanTripwire.SCAN_TILE >= extent, "scan misses edge " + extent);
            require((groups - 1) * PackNanTripwire.SCAN_TILE < extent, "redundant scan tile " + extent);
        }
        require(PackNanTripwire.isFloatFormat(VK_FORMAT_R32_SFLOAT), "float depth copies must be scanned");
        require(PackNanTripwire.isFloatFormat(VK_FORMAT_R16G16B16A16_SFLOAT), "history must be scanned");
        require(!PackNanTripwire.isFloatFormat(VK_FORMAT_R8G8B8A8_UNORM), "UNORM cannot store NaN");
        ByteBuffer code = PackNanTripwire.compile();
        try {
            require(code.remaining() > 20, "empty compute module");
            require(code.order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 0x07230203, "invalid SPIR-V");
            boolean nan = false, inf = false;
            for (int at = 20; at < code.limit();) {
                int word = code.getInt(at);
                nan |= (word & 0xffff) == 156; // OpIsNan
                inf |= (word & 0xffff) == 157; // OpIsInf
                at += (word >>> 16) * 4;
            }
            require(nan && inf, "optimizer discarded non-finite detection");
            if (Boolean.getBoolean("chimera.nanTripwire.gpu")) {
                try (GpuScan scan = new GpuScan(code)) {
                    scan.runChecks();
                }
            }
        } finally {
            MemoryUtil.memFree(code);
        }
        System.out.println("[chimera] NaN tripwire reset, coverage, format and shader checks: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void ok(int result) {
        require(result == VK_SUCCESS, "Vulkan result " + result);
    }

    /** Does not start Minecraft or touch VulkanMod's global device. */
    private static final class GpuScan implements AutoCloseable {
        private VkInstance instance;
        private VkPhysicalDevice physical;
        private VkDevice device;
        private VkQueue queue;
        private VkCommandBuffer command;
        private int family;
        private long pool, fence, image, imageMemory, view, sampler, shader, setLayout, layout, pipeline, descriptors, set;
        private long resultBuffer, resultMemory, hostBuffer, hostMemory;
        private ByteBuffer mapped;
        private int imageLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        // Nonmultiples of the tile size exercise edge lanes at full-screen scale.
        private static final int WIDTH = 1921, HEIGHT = 1081;

        GpuScan(ByteBuffer code) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.mallocPointer(1);
                VkInstanceCreateInfo create = VkInstanceCreateInfo.calloc(stack).sType$Default()
                        .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default()
                                .pApplicationName(stack.UTF8("Chimera NaN scan test")).apiVersion(VK_MAKE_VERSION(1, 2, 0)));
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
                family = -1;
                for (int i = 0; i < queues.remaining(); i++) {
                    if ((queues.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) { family = i; break; }
                }
                require(family >= 0, "no compute queue");
                VkDeviceQueueCreateInfo.Buffer queueCreate = VkDeviceQueueCreateInfo.calloc(1, stack)
                        .sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
                VkDeviceCreateInfo deviceCreate = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queueCreate);
                ok(vkCreateDevice(physical, deviceCreate, null, pointer));
                device = new VkDevice(pointer.get(0), physical, deviceCreate);
                vkGetDeviceQueue(device, family, 0, pointer);
                queue = new VkQueue(pointer.get(0), device);
                LongBuffer handle = stack.mallocLong(1);
                ok(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                        .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle));
                pool = handle.get(0);
                ok(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer));
                command = new VkCommandBuffer(pointer.get(0), device);
                ok(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle));
                fence = handle.get(0);
                createResources(stack, code);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private void createResources(MemoryStack stack, ByteBuffer code) {
            LongBuffer handle = stack.mallocLong(1);
            ok(vkCreateImage(device, VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R32G32B32A32_SFLOAT)
                    .extent(e -> e.set(WIDTH, HEIGHT, 1)).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED), null, handle));
            image = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            imageMemory = allocate(stack, requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            ok(vkBindImageMemory(device, image, imageMemory, 0));
            VkImageViewCreateInfo viewCreate = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R32G32B32A32_SFLOAT);
            viewCreate.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            ok(vkCreateImageView(device, viewCreate, null, handle));
            view = handle.get(0);
            ok(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, handle));
            sampler = handle.get(0);
            long[] result = buffer(stack, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                    | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            resultBuffer = result[0]; resultMemory = result[1];
            long[] host = buffer(stack, VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            hostBuffer = host[0]; hostMemory = host[1];
            PointerBuffer pointer = stack.mallocPointer(1);
            ok(vkMapMemory(device, hostMemory, 0, PackNanTripwire.RESULT_BYTES, 0, pointer));
            mapped = MemoryUtil.memByteBuffer(pointer.get(0), PackNanTripwire.RESULT_BYTES);

            ok(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle));
            shader = handle.get(0);
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            ok(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle));
            setLayout = handle.get(0);
            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(4);
            ok(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, handle));
            layout = handle.get(0);
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(layout);
            info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
            ok(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, handle));
            pipeline = handle.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            ok(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, handle));
            descriptors = handle.get(0);
            ok(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptors).pSetLayouts(stack.longs(setLayout)), handle));
            set = handle.get(0);
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(resultBuffer).range(PackNanTripwire.RESULT_BYTES);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfo);
            writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
            vkUpdateDescriptorSets(device, writes, null);
        }

        private long[] buffer(MemoryStack stack, int usage, int flags) {
            LongBuffer handle = stack.mallocLong(1);
            ok(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(PackNanTripwire.RESULT_BYTES).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle));
            long buffer = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, requirements);
            long memory = allocate(stack, requirements, flags);
            ok(vkBindBufferMemory(device, buffer, memory, 0));
            return new long[] {buffer, memory};
        }

        private long allocate(MemoryStack stack, VkMemoryRequirements requirements, int flags) {
            VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physical, properties);
            int type = -1;
            for (int i = 0; i < properties.memoryTypeCount(); i++) {
                if ((requirements.memoryTypeBits() & (1 << i)) != 0
                        && (properties.memoryTypes(i).propertyFlags() & flags) == flags) { type = i; break; }
            }
            require(type >= 0, "no suitable Vulkan memory type");
            LongBuffer handle = stack.mallocLong(1);
            ok(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(type), null, handle));
            return handle.get(0);
        }

        void runChecks() {
            scan(1.0f, false, 0, 0, -1, 0);
            scan(Float.NaN, false, WIDTH * HEIGHT, 0, 0, 1);
            scan(Float.POSITIVE_INFINITY, false, 0, WIDTH * HEIGHT, 0, 16);
            // Only the bottom-right texel is dirty, beyond both 64-pixel tile boundaries.
            scan(0.0f, true, 1, 1, ((HEIGHT - 1) << 16) | (WIDTH - 1), 33);
            scan(1.0f, false, 0, 0, -1, 0); // No stale results after a dirty frame.
            System.out.println("[chimera] headless Vulkan scan: finite, full NaN/Inf, mixed edge pixel, re-arm: PASS");
            scan(1.0f, false, 0, 0, -1, 0, 96);
            scan(Float.NaN, false, WIDTH * HEIGHT, 0, 0, 1, 96);
        }

        private void scan(float red, boolean edge, int nan, int inf, int first, int bits) {
            scan(red, edge, nan, inf, first, bits, 1);
        }

        private void scan(float red, boolean edge, int nan, int inf, int first, int bits, int checks) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ok(vkResetCommandBuffer(command, 0));
                ok(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()));
                ByteBuffer initial = stack.malloc(PackNanTripwire.RESULT_BYTES);
                PackNanTripwire.initializeResults(initial);
                vkCmdUpdateBuffer(command, resultBuffer, 0, initial);
                transition(stack, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                VkClearColorValue clear = VkClearColorValue.calloc(stack);
                clear.float32(0, red).float32(3, 1);
                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdClearColorImage(command, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
                if (edge) {
                    mapped.putFloat(0, Float.NaN).putFloat(4, Float.NEGATIVE_INFINITY).putFloat(8, 0).putFloat(12, 1);
                    memoryBarrier(stack, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                    VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                    copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    copy.imageOffset().set(WIDTH - 1, HEIGHT - 1, 0);
                    copy.imageExtent().set(1, 1, 1);
                    vkCmdCopyBufferToImage(command, hostBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
                }
                transition(stack, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
                memoryBarrier(stack, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, stack.longs(set), null);
                for (int check = 0; check < checks; check++) {
                    // Single-check tests also verify that a nonzero slot leaves its neighbor untouched.
                    vkCmdPushConstants(command, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(checks == 1 ? 1 : check));
                    vkCmdDispatch(command, PackNanTripwire.dispatchGroups(WIDTH), PackNanTripwire.dispatchGroups(HEIGHT), 1);
                }
                memoryBarrier(stack, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
                vkCmdCopyBuffer(command, resultBuffer, hostBuffer, VkBufferCopy.calloc(1, stack)
                        .size(checks == 1 ? 32 : PackNanTripwire.RESULT_BYTES));
                memoryBarrier(stack, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
                ok(vkEndCommandBuffer(command));
                ok(vkResetFences(device, fence));
                long start = System.nanoTime();
                ok(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(command.address())), fence));
                ok(vkWaitForFences(device, fence, true, 10_000_000_000L));
                long elapsed = System.nanoTime() - start;
                if (checks == 1) require(mapped.getInt(0) == 0 && mapped.getInt(8) == -1, "neighbor slot overwritten");
                for (int check = 0; check < checks; check++) {
                    int at = (checks == 1 ? 1 : check) * 16;
                    require(mapped.getInt(at) == nan && mapped.getInt(at + 4) == inf
                                    && mapped.getInt(at + 8) == first && mapped.getInt(at + 12) == bits,
                            "GPU counts/coordinate/channel mismatch at slot " + check);
                }
                if (checks > 1) System.out.printf("[chimera] %d %dx%d RGBA32F %s scans + clear/copy/submit/fence: %.2f ms%n",
                        checks, WIDTH, HEIGHT, nan == 0 ? "clean" : "fully NaN", elapsed / 1_000_000.0);
            }
        }

        private void transition(MemoryStack stack, int next, int sourceStage, int sourceAccess, int destinationStage, int destinationAccess) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .image(image).oldLayout(imageLayout).newLayout(next)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, null, null, barrier);
            imageLayout = next;
        }

        private void memoryBarrier(MemoryStack stack, int sourceStage, int sourceAccess, int destinationStage, int destinationAccess) {
            vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0,
                    PackVulkanBarriers.createMemoryBarrier(stack, sourceAccess, destinationAccess), null, null);
        }

        @Override public void close() {
            if (device != null) {
                vkDeviceWaitIdle(device);
                if (mapped != null) vkUnmapMemory(device, hostMemory);
                if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
                if (descriptors != 0) vkDestroyDescriptorPool(device, descriptors, null);
                if (layout != 0) vkDestroyPipelineLayout(device, layout, null);
                if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
                if (shader != 0) vkDestroyShaderModule(device, shader, null);
                if (sampler != 0) vkDestroySampler(device, sampler, null);
                if (view != 0) vkDestroyImageView(device, view, null);
                if (image != 0) vkDestroyImage(device, image, null);
                if (imageMemory != 0) vkFreeMemory(device, imageMemory, null);
                if (resultBuffer != 0) vkDestroyBuffer(device, resultBuffer, null);
                if (hostBuffer != 0) vkDestroyBuffer(device, hostBuffer, null);
                if (resultMemory != 0) vkFreeMemory(device, resultMemory, null);
                if (hostMemory != 0) vkFreeMemory(device, hostMemory, null);
                if (fence != 0) vkDestroyFence(device, fence, null);
                if (pool != 0) vkDestroyCommandPool(device, pool, null);
                vkDestroyDevice(device, null);
            }
            if (instance != null) vkDestroyInstance(instance, null);
        }
    }
}
