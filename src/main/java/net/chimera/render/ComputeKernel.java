package net.chimera.render;

import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.ComputeValueBlock;
import net.chimera.shaderpack.SpirvLocalInitializer;
import net.chimera.shaderpack.UniformRegistry;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
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
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

/**
 * One compute pipeline and what it binds each frame in flight: a descriptor set and a
 * host-visible uniform block. Values are written while a frame records, so a block shared across
 * frames in flight could change under an earlier frame's dispatch. Binding i has
 * {@code descriptorTypes[i]}; the uniform block, when there is one, takes the binding after them.
 */
final class ComputeKernel implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    private final String label;
    private final int[] descriptorTypes;
    private final int uniformBufferSize;
    private long shaderModule;
    private long setLayout;
    private long descriptorPool;
    private long pipelineLayout;
    private long pipeline;
    private FrameSlot[] frames = new FrameSlot[0];

    /** One frame in flight's descriptor set and uniform block. */
    static final class FrameSlot {
        long descriptorSet;
        long uniformBuffer;
        long uniformAllocation;
        ByteBuffer uniformData;
        boolean uniformMappedByChimera;
    }

    private ComputeKernel(String label, int[] descriptorTypes, int uniformBufferSize) {
        this.label = label;
        this.descriptorTypes = descriptorTypes.clone();
        this.uniformBufferSize = uniformBufferSize;
    }

    /** Compiles the source and builds the pipeline; null, with the reason logged, when a step fails. */
    static ComputeKernel create(String label, String source, int[] descriptorTypes, int uniformBufferSize) {
        ByteBuffer code = compileSpirv(label, source);
        if (code == null) return null;
        ComputeKernel kernel = new ComputeKernel(label, descriptorTypes, uniformBufferSize);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (kernel.build(stack, code)) return kernel;
            LOGGER.warn("[chimera] {} pipeline build failed", label);
            kernel.close();
            return null;
        } catch (RuntimeException failure) {
            kernel.close();
            throw failure;
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private boolean build(MemoryStack stack, ByteBuffer code) {
        int frameCount = Math.max(1, Renderer.getFramesNum());
        frames = new FrameSlot[frameCount];
        for (int index = 0; index < frameCount; index++) {
            frames[index] = new FrameSlot();
            if (!createUniformBuffer(stack, frames[index])) return false;
        }
        LongBuffer module = stack.mallocLong(1);
        VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
        if (vkCreateShaderModule(Vulkan.getVkDevice(), moduleInfo, null, module) != VK_SUCCESS) return false;
        shaderModule = module.get(0);
        return createSetLayout(stack) && createDescriptorPool(stack) && allocateDescriptorSets(stack)
                && createPipeline(stack);
    }

    int frameCount() { return frames.length; }

    FrameSlot frame(int index) { return frames[index]; }

    /** This frame slot's fence has signalled, so its set and block are free to rewrite. */
    FrameSlot currentFrame() { return frames[Renderer.getCurrentFrame() % frames.length]; }

    /** Points one image binding of a frame slot's set at an image view (and sampler, for a sampled one). */
    void writeImage(FrameSlot frame, int binding, long view, long sampler, int layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(sampler).imageView(view).imageLayout(layout);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(frame.descriptorSet).dstBinding(binding)
                    .dstArrayElement(0).descriptorCount(1).descriptorType(descriptorTypes[binding])
                    .pImageInfo(image);
            vkUpdateDescriptorSets(Vulkan.getVkDevice(), write, null);
        }
    }

    /** Binds the pipeline and the frame slot's set, and writes the frame's live values into its block. */
    void bind(VkCommandBuffer commandBuffer, MemoryStack stack, FrameSlot frame, List<ComputeValueBlock.Value> values) {
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                pipelineLayout, 0, stack.longs(frame.descriptorSet), null);
        if (values.isEmpty() || frame.uniformData == null) return;
        MemoryUtil.memSet(frame.uniformData, 0);
        List<UniformRegistry.UniformDeclaration> declarations = values.stream()
                .map(value -> new UniformRegistry.UniformDeclaration(value.name(), value.type()))
                .toList();
        int[] offsets = values.stream().mapToInt(ComputeValueBlock.Value::offset).toArray();
        int[] sizes = values.stream().mapToInt(ComputeValueBlock.Value::size).toArray();
        PackUniformProvider.writeComputeUniforms(declarations, offsets, sizes, frame.uniformData);
    }

    private boolean createSetLayout(MemoryStack stack) {
        int count = descriptorTypes.length + (uniformBufferSize > 0 ? 1 : 0);
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(count, stack);
        for (int binding = 0; binding < count; binding++) {
            int type = binding < descriptorTypes.length ? descriptorTypes[binding] : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            bindings.get(binding).binding(binding).descriptorType(type)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        }
        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(bindings);
        LongBuffer layout = stack.mallocLong(1);
        if (vkCreateDescriptorSetLayout(Vulkan.getVkDevice(), layoutInfo, null, layout) != VK_SUCCESS) return false;
        setLayout = layout.get(0);
        return true;
    }

    /** A pool holding one set per frame slot, sized by descriptor type. */
    private boolean createDescriptorPool(MemoryStack stack) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int type : descriptorTypes) counts.merge(type, frames.length, Integer::sum);
        if (uniformBufferSize > 0) counts.merge(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, frames.length, Integer::sum);
        if (counts.isEmpty()) counts.put(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1);
        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(counts.size(), stack);
        int index = 0;
        for (Map.Entry<Integer, Integer> count : counts.entrySet()) {
            poolSizes.get(index++).type(count.getKey()).descriptorCount(count.getValue());
        }
        VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default().maxSets(frames.length).pPoolSizes(poolSizes);
        LongBuffer pool = stack.mallocLong(1);
        if (vkCreateDescriptorPool(Vulkan.getVkDevice(), poolInfo, null, pool) != VK_SUCCESS) return false;
        descriptorPool = pool.get(0);
        return true;
    }

    /** Allocates each frame slot's set and points its uniform binding at the slot's block. */
    private boolean allocateDescriptorSets(MemoryStack stack) {
        LongBuffer layouts = stack.mallocLong(frames.length);
        for (int index = 0; index < frames.length; index++) layouts.put(index, setLayout);
        VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default().descriptorPool(descriptorPool).pSetLayouts(layouts);
        LongBuffer sets = stack.mallocLong(frames.length);
        if (vkAllocateDescriptorSets(Vulkan.getVkDevice(), allocateInfo, sets) != VK_SUCCESS) return false;
        for (int index = 0; index < frames.length; index++) {
            FrameSlot frame = frames[index];
            frame.descriptorSet = sets.get(index);
            if (uniformBufferSize <= 0) continue;
            VkDescriptorBufferInfo.Buffer buffer = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(frame.uniformBuffer).offset(0L).range(uniformBufferSize);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(frame.descriptorSet).dstBinding(descriptorTypes.length)
                    .dstArrayElement(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(buffer);
            vkUpdateDescriptorSets(Vulkan.getVkDevice(), write, null);
        }
        return true;
    }

    private boolean createPipeline(MemoryStack stack) {
        VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default().pSetLayouts(stack.longs(setLayout));
        LongBuffer layout = stack.mallocLong(1);
        if (vkCreatePipelineLayout(Vulkan.getVkDevice(), layoutInfo, null, layout) != VK_SUCCESS) return false;
        pipelineLayout = layout.get(0);
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
        pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
        LongBuffer created = stack.mallocLong(1);
        if (vkCreateComputePipelines(Vulkan.getVkDevice(), 0L, pipelineInfo, null, created) != VK_SUCCESS) {
            return false;
        }
        pipeline = created.get(0);
        return true;
    }

    /** Allocates one frame slot's host-visible uniform block for all live values. */
    private boolean createUniformBuffer(MemoryStack stack, FrameSlot frame) {
        if (uniformBufferSize <= 0) return true;
        VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(uniformBufferSize)
                .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
        VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT
                        | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT)
                .requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        LongBuffer buffer = stack.mallocLong(1);
        PointerBuffer allocation = stack.mallocPointer(1);
        VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
        if (Vma.vmaCreateBuffer(Vulkan.getAllocator(), bufferInfo, allocationInfo,
                buffer, allocation, info) != VK_SUCCESS) {
            return false;
        }
        frame.uniformBuffer = buffer.get(0);
        frame.uniformAllocation = allocation.get(0);
        long mappedAddress = info.pMappedData();
        if (mappedAddress == 0L) {
            PointerBuffer mapped = stack.mallocPointer(1);
            if (Vma.vmaMapMemory(Vulkan.getAllocator(), frame.uniformAllocation, mapped) != VK_SUCCESS) {
                return false;
            }
            mappedAddress = mapped.get(0);
            frame.uniformMappedByChimera = true;
        }
        frame.uniformData = MemoryUtil.memByteBuffer(mappedAddress, uniformBufferSize);
        return true;
    }

    /** VulkanMod's shaderc target (Vulkan 1.2); null, with the error logged, when compilation fails. */
    private static ByteBuffer compileSpirv(String label, String source) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                    Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
            result = Shaderc.shaderc_compile_into_spv(compiler, source,
                    Shaderc.shaderc_glsl_compute_shader, label, "main", options);
            if (result == 0L || Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                if (result != 0L) {
                    LOGGER.warn("[chimera] {} shaderc compile failed: {}", label,
                            Shaderc.shaderc_result_get_error_message(result));
                }
                return null;
            }
            ByteBuffer bytes = SpirvLocalInitializer.apply(Shaderc.shaderc_result_get_bytes(result));
            ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
            MemoryUtil.memCopy(bytes, copy);
            return copy;
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            if (options != 0L) Shaderc.shaderc_compile_options_release(options);
            if (compiler != 0L) Shaderc.shaderc_compiler_release(compiler);
        }
    }

    @Override
    public void close() {
        if (pipeline != 0L) vkDestroyPipeline(Vulkan.getVkDevice(), pipeline, null);
        if (pipelineLayout != 0L) vkDestroyPipelineLayout(Vulkan.getVkDevice(), pipelineLayout, null);
        if (descriptorPool != 0L) vkDestroyDescriptorPool(Vulkan.getVkDevice(), descriptorPool, null);
        if (setLayout != 0L) vkDestroyDescriptorSetLayout(Vulkan.getVkDevice(), setLayout, null);
        if (shaderModule != 0L) vkDestroyShaderModule(Vulkan.getVkDevice(), shaderModule, null);
        for (FrameSlot frame : frames) {
            if (frame == null) continue;
            if (frame.uniformMappedByChimera && frame.uniformAllocation != 0L) {
                Vma.vmaUnmapMemory(Vulkan.getAllocator(), frame.uniformAllocation);
            }
            if (frame.uniformBuffer != 0L) {
                Vma.vmaDestroyBuffer(Vulkan.getAllocator(), frame.uniformBuffer, frame.uniformAllocation);
            }
        }
        pipeline = 0L;
        pipelineLayout = 0L;
        descriptorPool = 0L;
        setLayout = 0L;
        shaderModule = 0L;
        frames = new FrameSlot[0];
        LOGGER.debug("[chimera] {} compute kernel closed", label);
    }
}
