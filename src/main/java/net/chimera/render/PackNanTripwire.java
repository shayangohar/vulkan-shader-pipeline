package net.chimera.render;

import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.WinBase;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Debug tripwire for non-finite pack output. After every pack post pass, and before each post
 * window, a compute pass scans every float target the frame has written and records how many
 * texels hold NaN or infinity and the first one found. The counts come back once the frame's
 * fence has passed. The first frame that has any is logged in pass order, together with every
 * bound float uniform that is not finite, and a RenderDoc capture of the next frame is triggered
 * when the game runs under RenderDoc. It then stays quiet until a frame is clean again.
 *
 * <p>Off unless {@code -Dchimera.debug.nan=true} or {@code /chimera debug nan on}. Costs one
 * full-screen read per checked target while on.
 */
public final class PackNanTripwire {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("chimera");
    private static final int MAX_CHECKS = 96;
    /** Per check: NaN texels, infinite texels, first bad texel (y << 16 | x), NaN/Inf channel bits. */
    private static final int WORDS_PER_CHECK = 4;
    private static final int LOCAL_SIZE = 16;
    /** RenderDoc in-app API 1.1.2, whose table holds TriggerCapture at entry 15. */
    private static final int RENDERDOC_API_1_1_2 = 10102;
    private static final int RENDERDOC_TRIGGER_CAPTURE = 15;

    private static final String SHADER = """
            #version 450
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(binding = 0) uniform sampler2D target;
            layout(std430, binding = 1) buffer Results { uint data[]; };
            layout(push_constant) uniform Push { uint slot; } push;
            void main() {
                ivec2 size = textureSize(target, 0);
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= size.x || p.y >= size.y) return;
                vec4 v = texelFetch(target, p, 0);
                bvec4 n = isnan(v);
                bvec4 i = isinf(v);
                if (!any(n) && !any(i)) return;
                uint base = push.slot * 4u;
                if (any(n)) atomicAdd(data[base], 1u);
                if (any(i)) atomicAdd(data[base + 1u], 1u);
                atomicMin(data[base + 2u], (uint(p.y) << 16) | uint(p.x));
                uint bits = (n.x ? 1u : 0u) | (n.y ? 2u : 0u) | (n.z ? 4u : 0u) | (n.w ? 8u : 0u)
                        | (i.x ? 16u : 0u) | (i.y ? 32u : 0u) | (i.z ? 64u : 0u) | (i.w ? 128u : 0u);
                atomicOr(data[base + 3u], bits);
            }
            """;

    private static volatile boolean enabled = Boolean.getBoolean("chimera.debug.nan");

    private boolean created;
    private boolean failed;
    private long shaderModule;
    private long setLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private long sampler;
    private long[] descriptorSets;
    private Frame[] frames;
    private Frame current;
    private boolean armed = true;
    private long frameNumber;

    /** One frame-in-flight slot: its result buffer and what was checked into it. */
    private static final class Frame {
        long buffer;
        long allocation;
        ByteBuffer data;
        final List<String> labels = new ArrayList<>();
        final List<int[]> sizes = new ArrayList<>();
        List<String> badUniforms = List.of();
        boolean uniformsChecked;
        long number;
        String pack;
        boolean pending;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Turns the tripwire on and scans a small NaN-filled image next frame, end to end. */
    public static void requestSelfTest() {
        enabled = true;
        selfTestPending = true;
    }

    private static volatile boolean selfTestPending;
    private VulkanImage selfTestImage;

    /** Clears the self-test image to NaN and scans it, so the report path runs on demand. */
    private void runSelfTest(VkCommandBuffer commandBuffer) {
        selfTestPending = false;
        this.armed = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.selfTestImage == null) {
                this.selfTestImage = VulkanImage.builder(4, 4)
                        .setName("chimeraNanTripwireSelfTest")
                        .setFormat(VK_FORMAT_R16G16B16A16_SFLOAT)
                        .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                        .setLinearFiltering(false).setClamp(true).createVulkanImage();
            }
            this.selfTestImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            org.lwjgl.vulkan.VkClearColorValue nan = org.lwjgl.vulkan.VkClearColorValue.calloc(stack);
            nan.float32(0, Float.NaN).float32(1, 0.0f).float32(2, 0.0f).float32(3, 1.0f);
            org.lwjgl.vulkan.VkImageSubresourceRange.Buffer range =
                    org.lwjgl.vulkan.VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdClearColorImage(commandBuffer, this.selfTestImage.getId(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, nan, range);
        }
        check(commandBuffer, "self-test (4x4 image cleared to NaN in red)", this.selfTestImage);
    }

    /**
     * Reads the results this frame slot holds from its last use, whose fence VulkanMod has
     * waited on, then clears the slot for this frame.
     */
    public void beginFrame(VkCommandBuffer commandBuffer, String packName) {
        if (!enabled || !ensureCreated()) {
            if (this.frames != null) {
                for (Frame frame : this.frames) frame.pending = false;
            }
            this.current = null;
            this.armed = true;
            return;
        }
        Frame frame = this.frames[Renderer.getCurrentFrame() % this.frames.length];
        if (frame.pending) {
            evaluate(frame);
        }
        for (int word = 0; word < MAX_CHECKS * WORDS_PER_CHECK; word++) {
            frame.data.putInt(word * 4, word % WORDS_PER_CHECK == 2 ? -1 : 0);
        }
        frame.labels.clear();
        frame.sizes.clear();
        frame.badUniforms = List.of();
        frame.uniformsChecked = false;
        frame.number = ++this.frameNumber;
        frame.pack = packName;
        frame.pending = true;
        this.current = frame;
        if (selfTestPending) {
            runSelfTest(commandBuffer);
        }
    }

    /** Records the uniforms once per frame, after the shadow state for the frame is published. */
    public void checkUniforms() {
        Frame frame = this.current;
        if (frame == null || frame.uniformsChecked) return;
        frame.uniformsChecked = true;
        frame.badUniforms = PackUniformProvider.nonFiniteUniforms();
    }

    /**
     * Scans one image. No rendering may be active. A float image is moved to shader-read
     * layout first (a no-op after a post pass); other formats cannot hold NaN and are skipped.
     */
    public void check(VkCommandBuffer commandBuffer, String label, VulkanImage image) {
        Frame frame = this.current;
        if (frame == null || image == null || !isFloatFormat(image.format)) return;
        int slot = frame.labels.size();
        if (slot >= MAX_CHECKS) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            long set = this.descriptorSets[(Renderer.getCurrentFrame() % this.frames.length) * MAX_CHECKS + slot];
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.get(0).sampler(this.sampler).imageView(image.getImageView())
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.get(0).buffer(frame.buffer).offset(0).range(VK_WHOLE_SIZE);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfo);
            writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
            vkUpdateDescriptorSets(Vulkan.getVkDevice(), writes, null);

            // Whatever wrote the image (attachment, shader or copy) finishes before the scan.
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                    PackVulkanBarriers.createMemoryBarrier(stack,
                            VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT
                                    | VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT),
                    null, null);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout,
                    0, stack.longs(set), null);
            vkCmdPushConstants(commandBuffer, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(slot));
            vkCmdDispatch(commandBuffer, (image.width + LOCAL_SIZE - 1) / LOCAL_SIZE,
                    (image.height + LOCAL_SIZE - 1) / LOCAL_SIZE, 1);
            // Later writes to the image wait for the scan, and the host sees the counts.
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK_PIPELINE_STAGE_HOST_BIT, 0,
                    PackVulkanBarriers.createMemoryBarrier(stack, VK_ACCESS_SHADER_WRITE_BIT,
                            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_HOST_READ_BIT),
                    null, null);
            frame.labels.add(label);
            frame.sizes.add(new int[] {image.width, image.height});
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] NaN tripwire: cannot check {}: {}", label, failure.toString());
        }
    }

    private void evaluate(Frame frame) {
        frame.pending = false;
        List<String> dirty = new ArrayList<>();
        int firstDirty = -1;
        for (int slot = 0; slot < frame.labels.size(); slot++) {
            int base = slot * WORDS_PER_CHECK * 4;
            int nan = frame.data.getInt(base);
            int inf = frame.data.getInt(base + 4);
            if (nan == 0 && inf == 0) continue;
            int first = frame.data.getInt(base + 8);
            int bits = frame.data.getInt(base + 12);
            int x = first & 0xFFFF;
            int y = first >>> 16;
            int[] size = frame.sizes.get(slot);
            if (firstDirty < 0) firstDirty = slot;
            dirty.add(String.format(
                    "#%d %s %dx%d: nan=%d inf=%d channels=%s first=(%d,%d) image, (%d,%d) screen",
                    slot, frame.labels.get(slot), size[0], size[1], nan, inf, channels(bits),
                    x, y, x, size[1] - 1 - y));
        }
        if (dirty.isEmpty() && frame.badUniforms.isEmpty()) {
            if (!this.armed) {
                LOGGER.info("[chimera] NaN tripwire: frame {} is clean again; re-armed", frame.number);
            }
            this.armed = true;
            return;
        }
        if (!this.armed) return;
        this.armed = false;
        StringBuilder report = new StringBuilder();
        report.append("[chimera] NaN tripwire fired on frame ").append(frame.number)
                .append(" (pack ").append(frame.pack).append(", ").append(frame.labels.size())
                .append(" checks in recording order; image y is GL row order)");
        report.append("\n  checks: ").append(String.join(", ", frame.labels));
        if (dirty.isEmpty()) {
            report.append("\n  no target holds a non-finite texel");
        } else {
            report.append("\n  first dirty check: ").append(dirty.get(0));
            for (int i = 1; i < dirty.size(); i++) {
                report.append("\n  also dirty: ").append(dirty.get(i));
            }
        }
        report.append("\n  non-finite uniforms: ")
                .append(frame.badUniforms.isEmpty() ? "none" : String.join(", ", frame.badUniforms));
        boolean capture = triggerRenderDocCapture();
        report.append("\n  RenderDoc capture: ").append(capture ? "triggered for the next frame"
                : "not available (not running under RenderDoc)");
        LOGGER.warn(report.toString());
        String first = firstDirty < 0 ? "uniforms " + String.join(", ", frame.badUniforms)
                : frame.labels.get(firstDirty);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.literal("[Chimera] NaN tripwire fired, first at "
                    + first + (capture ? "; RenderDoc capture triggered" : "") + ". Details in latest.log."));
        }
    }

    private static String channels(int bits) {
        StringBuilder text = new StringBuilder();
        String names = "rgba";
        for (int c = 0; c < 4; c++) {
            if ((bits & (1 << c)) != 0) text.append(names.charAt(c)).append(":nan ");
            if ((bits & (16 << c)) != 0) text.append(names.charAt(c)).append(":inf ");
        }
        return text.toString().trim().replace(' ', ',');
    }

    private static boolean renderDocPresent() {
        try {
            return Platform.get() == Platform.WINDOWS && WinBase.GetModuleHandle("renderdoc.dll") != 0L;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** RenderDoc injects renderdoc.dll; when present its in-app API captures the next frame. */
    private static boolean triggerRenderDocCapture() {
        if (Platform.get() != Platform.WINDOWS) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long module = WinBase.GetModuleHandle("renderdoc.dll");
            if (module == 0L) return false;
            long getApi = WinBase.GetProcAddress(module, "RENDERDOC_GetAPI");
            if (getApi == 0L) return false;
            PointerBuffer api = stack.mallocPointer(1);
            if (JNI.invokePI(RENDERDOC_API_1_1_2, MemoryUtil.memAddress(api), getApi) != 1) return false;
            long table = api.get(0);
            if (table == 0L) return false;
            long trigger = MemoryUtil.memGetAddress(table + (long) RENDERDOC_TRIGGER_CAPTURE * Long.BYTES);
            if (trigger == 0L) return false;
            JNI.invokeV(trigger);
            return true;
        } catch (Throwable failure) {
            LOGGER.warn("[chimera] NaN tripwire: RenderDoc trigger failed: {}", failure.toString());
            return false;
        }
    }

    static boolean isFloatFormat(int format) {
        return switch (format) {
            case VK_FORMAT_R16_SFLOAT, VK_FORMAT_R16G16_SFLOAT, VK_FORMAT_R16G16B16_SFLOAT,
                 VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT,
                 VK_FORMAT_R32G32B32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT,
                 VK_FORMAT_B10G11R11_UFLOAT_PACK32 -> true;
            default -> false;
        };
    }

    private boolean ensureCreated() {
        if (this.created) return true;
        if (this.failed) return false;
        try {
            create();
            this.created = true;
            LOGGER.info("[chimera] NaN tripwire: armed ({} checks per frame, {} frames in flight, RenderDoc {})",
                    MAX_CHECKS, this.frames.length, renderDocPresent() ? "detected" : "not detected");
            return true;
        } catch (RuntimeException failure) {
            this.failed = true;
            LOGGER.warn("[chimera] NaN tripwire: cannot create its compute pass: {}", failure.toString());
            close();
            return false;
        }
    }

    private void create() {
        int frameCount = Math.max(1, Renderer.getFramesNum());
        ByteBuffer code = compile();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateShaderModule(Vulkan.getVkDevice(), VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(code), null, handle), "shader module");
            this.shaderModule = handle.get(0);
            MemoryUtil.memFree(code);

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(Vulkan.getVkDevice(), VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings), null, handle), "descriptor set layout");
            this.setLayout = handle.get(0);

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(4);
            check(vkCreatePipelineLayout(Vulkan.getVkDevice(), VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(this.setLayout)).pPushConstantRanges(push),
                    null, handle), "pipeline layout");
            this.pipelineLayout = handle.get(0);

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().layout(this.pipelineLayout).stage()
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(this.shaderModule)
                    .pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(Vulkan.getVkDevice(), VK_NULL_HANDLE, pipelineInfo, null, handle),
                    "compute pipeline");
            this.pipeline = handle.get(0);

            check(vkCreateSampler(Vulkan.getVkDevice(), VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0.0f), null, handle), "sampler");
            this.sampler = handle.get(0);

            int setCount = frameCount * MAX_CHECKS;
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(setCount);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(setCount);
            check(vkCreateDescriptorPool(Vulkan.getVkDevice(), VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(setCount).pPoolSizes(sizes), null, handle), "descriptor pool");
            this.descriptorPool = handle.get(0);
            LongBuffer layouts = stack.mallocLong(setCount);
            for (int i = 0; i < setCount; i++) layouts.put(i, this.setLayout);
            LongBuffer sets = stack.mallocLong(setCount);
            check(vkAllocateDescriptorSets(Vulkan.getVkDevice(), VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(this.descriptorPool).pSetLayouts(layouts), sets),
                    "descriptor sets");
            this.descriptorSets = new long[setCount];
            sets.get(this.descriptorSets);

            this.frames = new Frame[frameCount];
            for (int i = 0; i < frameCount; i++) {
                this.frames[i] = createFrame(stack);
            }
        }
    }

    private static Frame createFrame(MemoryStack stack) {
        int bytes = MAX_CHECKS * WORDS_PER_CHECK * 4;
        VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                .size(bytes).usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT
                        | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT)
                .requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        LongBuffer buffer = stack.mallocLong(1);
        PointerBuffer allocation = stack.mallocPointer(1);
        VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
        check(Vma.vmaCreateBuffer(Vulkan.getAllocator(), bufferInfo, allocationInfo, buffer, allocation, info),
                "result buffer");
        Frame frame = new Frame();
        frame.buffer = buffer.get(0);
        frame.allocation = allocation.get(0);
        if (info.pMappedData() == 0L) {
            throw new IllegalStateException("result buffer is not mapped");
        }
        frame.data = MemoryUtil.memByteBuffer(info.pMappedData(), bytes);
        return frame;
    }

    private static ByteBuffer compile() {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                    Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.shaderc_compile_into_spv(compiler, SHADER,
                    Shaderc.shaderc_glsl_compute_shader, "chimera_nan_tripwire.csh", "main", options);
            if (result == 0L || Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                throw new IllegalStateException("shaderc: " + (result == 0L ? "no result"
                        : Shaderc.shaderc_result_get_error_message(result)));
            }
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
            MemoryUtil.memCopy(bytes, copy);
            return copy;
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("cannot create " + what + ": VkResult " + result);
        }
    }

    /** Releases everything; the device must be idle. */
    public void close() {
        if (this.selfTestImage != null) {
            this.selfTestImage.free();
            this.selfTestImage = null;
        }
        if (this.frames != null) {
            for (Frame frame : this.frames) {
                if (frame != null && frame.buffer != 0L) {
                    Vma.vmaDestroyBuffer(Vulkan.getAllocator(), frame.buffer, frame.allocation);
                }
            }
            this.frames = null;
        }
        if (this.descriptorPool != 0L) vkDestroyDescriptorPool(Vulkan.getVkDevice(), this.descriptorPool, null);
        if (this.sampler != 0L) vkDestroySampler(Vulkan.getVkDevice(), this.sampler, null);
        if (this.pipeline != 0L) vkDestroyPipeline(Vulkan.getVkDevice(), this.pipeline, null);
        if (this.pipelineLayout != 0L) vkDestroyPipelineLayout(Vulkan.getVkDevice(), this.pipelineLayout, null);
        if (this.setLayout != 0L) vkDestroyDescriptorSetLayout(Vulkan.getVkDevice(), this.setLayout, null);
        if (this.shaderModule != 0L) vkDestroyShaderModule(Vulkan.getVkDevice(), this.shaderModule, null);
        this.descriptorPool = this.sampler = this.pipeline = this.pipelineLayout = 0L;
        this.setLayout = this.shaderModule = 0L;
        this.descriptorSets = null;
        this.current = null;
        this.created = false;
    }
}
