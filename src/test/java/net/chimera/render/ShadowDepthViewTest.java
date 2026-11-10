package net.chimera.render;

import net.chimera.render.shader.GlslCompiler;
import net.chimera.testing.HeadlessVulkan;
import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;

import static net.chimera.testing.HeadlessVulkan.ok;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;

/**
 * Iris swizzles its shadow depth textures to R, R, R, 1 (ShadowRenderer.configureDepthSampler), so a
 * pack that reads shadowtex0 or shadowtex1 without a depth compare finds the depth in every colour
 * channel, where a plain depth read gives (d, 0, 0, 1) (audit T-S3). A headless Vulkan run samples
 * one depth texel through Chimera's shadow depth view, through an identity view, and with a compare
 * sampler through the shadow view.
 */
final class ShadowDepthViewTest {
    private static final float DEPTH = 0.25f;
    private static final float EPSILON = 1.0e-6f;
    private static final int RESULT_FLOATS = 12;
    private static final int[] IDENTITY = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
    private static final String SHADER = """
            #version 450
            layout(local_size_x = 1) in;
            layout(binding = 0) uniform sampler2D shadowView;
            layout(binding = 1) uniform sampler2D identityView;
            layout(binding = 2) uniform sampler2DShadow compared;
            layout(std430, binding = 3) buffer Results { vec4 values[3]; };
            void main() {
                values[0] = texelFetch(shadowView, ivec2(0), 0);
                values[1] = texelFetch(identityView, ivec2(0), 0);
                values[2] = vec4(texture(compared, vec3(0.5, 0.5, 0.1)), texture(compared, vec3(0.5, 0.5, 0.5)),
                        0.0, 0.0);
            }
            """;

    /**
     * D32, which every device samples, and D24S8, the depth VulkanMod gives the shadow map here,
     * whose view takes the depth aspect of a combined format, where the device samples it.
     */
    @Test
    void shadowDepthSamplesAsRedRedRedOne() {
        HeadlessVulkan.assumeEnabled();
        ByteBuffer code = GlslCompiler.compile("shadow-depth-view.csh", SHADER, Shaderc.shaderc_glsl_compute_shader,
                options -> {});
        try {
            for (int format : new int[] {VK_FORMAT_D32_SFLOAT, VK_FORMAT_D24_UNORM_S8_UINT}) {
                try (Gpu gpu = new Gpu(code, format)) {
                    assertTrue(gpu.supported() || format != VK_FORMAT_D32_SFLOAT, "D32 must sample");
                    if (gpu.supported()) check(gpu.run(), "format " + format);
                    System.out.printf("[chimera] shadow depth view, format %d: %s%n", format,
                            gpu.supported() ? "checked" : "not sampled by this device");
                }
            }
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static void check(float[] values, String format) {
        assertArrayEquals(new float[] {DEPTH, DEPTH, DEPTH, 1}, slice(values, 0), EPSILON,
                format + ": the shadow depth view reads R, R, R, 1, as Iris's swizzle does");
        assertArrayEquals(new float[] {DEPTH, 0, 0, 1}, slice(values, 1), EPSILON,
                format + ": an identity depth view reads (d, 0, 0, 1)");
        assertArrayEquals(new float[] {1, 0}, new float[] {values[8], values[9]}, EPSILON,
                format + ": a compare through the shadow view still compares the depth");
    }

    private static float[] slice(float[] values, int vector) {
        float[] result = new float[4];
        System.arraycopy(values, vector * 4, result, 0, 4);
        return result;
    }

    /** A 1x1 depth image, its two views, the samplers, a result buffer and a compute pipeline. */
    private static final class Gpu implements AutoCloseable {
        private final HeadlessVulkan.Device gpu;
        private final VkDevice device;
        private final int format;
        /** Every aspect of the format, which a layout transition of a combined depth-stencil image names. */
        private final int aspects;
        private boolean supported;
        private long image, imageMemory, shadowView, identityView, sampler, compareSampler;
        private long buffer, bufferMemory, shader, setLayout, layout, pipeline, descriptors, set;
        private FloatBuffer mapped;

        Gpu(ByteBuffer code, int format) {
            // The depth clear needs a graphics queue, the sampling a compute one.
            gpu = new HeadlessVulkan.Device("Chimera shadow depth view test",
                    VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT);
            device = gpu.device();
            this.format = format;
            aspects = format == VK_FORMAT_D24_UNORM_S8_UINT ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT
                    : VK_IMAGE_ASPECT_DEPTH_BIT;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkFormatProperties properties = VkFormatProperties.calloc(stack);
                vkGetPhysicalDeviceFormatProperties(gpu.physical(), format, properties);
                int needed = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
                supported = (properties.optimalTilingFeatures() & needed) == needed;
                if (!supported) return;
                createImage(stack);
                createSamplersAndBuffer(stack);
                createPipeline(stack, code);
                writeDescriptors(stack);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        boolean supported() {
            return supported;
        }

        /** The depth image, with Chimera's shadow depth view of it beside an identity view. */
        private void createImage(MemoryStack stack) {
            LongBuffer handle = stack.mallocLong(1);
            ok(vkCreateImage(device, VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(format).extent(e -> e.set(1, 1, 1))
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED), null, handle));
            image = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            imageMemory = gpu.allocate(stack, requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            ok(vkBindImageMemory(device, image, imageMemory, 0));
            shadowView = ImageViews.create(device, image, VK_IMAGE_VIEW_TYPE_2D, format,
                    VK_IMAGE_ASPECT_DEPTH_BIT, 1, ImageViews.shadowDepthComponents(), 0);
            identityView = ImageViews.create(device, image, VK_IMAGE_VIEW_TYPE_2D, format,
                    VK_IMAGE_ASPECT_DEPTH_BIT, 1, IDENTITY, 0);
        }

        private void createSamplersAndBuffer(MemoryStack stack) {
            LongBuffer handle = stack.mallocLong(1);
            ok(vkCreateSampler(device, samplerInfo(stack), null, handle));
            sampler = handle.get(0);
            ok(vkCreateSampler(device, samplerInfo(stack).compareEnable(true).compareOp(VK_COMPARE_OP_LESS_OR_EQUAL),
                    null, handle));
            compareSampler = handle.get(0);
            ok(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(RESULT_FLOATS * 4L)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle));
            buffer = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, requirements);
            bufferMemory = gpu.allocate(stack, requirements,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            ok(vkBindBufferMemory(device, buffer, bufferMemory, 0));
            PointerBuffer pointer = stack.mallocPointer(1);
            ok(vkMapMemory(device, bufferMemory, 0, RESULT_FLOATS * 4L, 0, pointer));
            mapped = MemoryUtil.memFloatBuffer(pointer.get(0), RESULT_FLOATS);
        }

        private static VkSamplerCreateInfo samplerInfo(MemoryStack stack) {
            return VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        }

        /** Three combined image samplers, then the result buffer, for one compute stage. */
        private void createPipeline(MemoryStack stack, ByteBuffer code) {
            LongBuffer handle = stack.mallocLong(1);
            ok(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null,
                    handle));
            shader = handle.get(0);
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            for (int binding = 0; binding < 4; binding++) {
                bindings.get(binding).binding(binding).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .descriptorType(binding < 3 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            }
            ok(vkCreateDescriptorSetLayout(device,
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle));
            setLayout = handle.get(0);
            ok(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)), null, handle));
            layout = handle.get(0);
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                    .layout(layout);
            info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
            ok(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, handle));
            pipeline = handle.get(0);
        }

        private void writeDescriptors(MemoryStack stack) {
            LongBuffer handle = stack.mallocLong(1);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            ok(vkCreateDescriptorPool(device,
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null,
                    handle));
            descriptors = handle.get(0);
            ok(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptors).pSetLayouts(stack.longs(setLayout)), handle));
            set = handle.get(0);
            long[][] images = {{shadowView, sampler}, {identityView, sampler}, {shadowView, compareSampler}};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            for (int binding = 0; binding < 3; binding++) {
                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageView(images[binding][0]).sampler(images[binding][1])
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                writes.get(binding).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfo);
            }
            writes.get(3).sType$Default().dstSet(set).dstBinding(3).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer).range(RESULT_FLOATS * 4L));
            vkUpdateDescriptorSets(device, writes, null);
        }

        /** Clears the depth to DEPTH, samples it once, and returns the three result vectors. */
        float[] run() {
            VkCommandBuffer command = gpu.command();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ok(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()));
                transition(stack, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT);
                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                        .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1);
                vkCmdClearDepthStencilImage(command, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VkClearDepthStencilValue.calloc(stack).depth(DEPTH), range);
                transition(stack, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, stack.longs(set), null);
                vkCmdDispatch(command, 1, 1, 1);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                        PackVulkanBarriers.createMemoryBarrier(stack, VK_ACCESS_SHADER_WRITE_BIT,
                                VK_ACCESS_HOST_READ_BIT), null, null);
                gpu.submitAndWait(stack);
            }
            float[] values = new float[RESULT_FLOATS];
            mapped.get(0, values);
            return values;
        }

        private void transition(MemoryStack stack, int from, int to, int sourceStage, int sourceAccess,
                                int destinationStage, int destinationAccess) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .image(image).oldLayout(from).newLayout(to)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
            barrier.subresourceRange().aspectMask(aspects).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(gpu.command(), sourceStage, destinationStage, 0, null, null, barrier);
        }

        @Override
        public void close() {
            vkDeviceWaitIdle(device);
            if (mapped != null) vkUnmapMemory(device, bufferMemory);
            if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            if (descriptors != 0) vkDestroyDescriptorPool(device, descriptors, null);
            if (layout != 0) vkDestroyPipelineLayout(device, layout, null);
            if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (shader != 0) vkDestroyShaderModule(device, shader, null);
            if (sampler != 0) vkDestroySampler(device, sampler, null);
            if (compareSampler != 0) vkDestroySampler(device, compareSampler, null);
            if (shadowView != 0) vkDestroyImageView(device, shadowView, null);
            if (identityView != 0) vkDestroyImageView(device, identityView, null);
            if (image != 0) vkDestroyImage(device, image, null);
            if (imageMemory != 0) vkFreeMemory(device, imageMemory, null);
            if (buffer != 0) vkDestroyBuffer(device, buffer, null);
            if (bufferMemory != 0) vkFreeMemory(device, bufferMemory, null);
            gpu.close();
        }
    }
}
