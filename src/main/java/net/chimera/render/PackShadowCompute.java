package net.chimera.render;

import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.PackEngineDefines;
import net.chimera.shaderpack.GlslTokenRewriter;
import net.chimera.shaderpack.SpirvLocalInitializer;
import net.chimera.shaderpack.ShaderSourcePreprocessor;
import net.chimera.shaderpack.UniformRegistry;
import net.chimera.render.shader.PackUniformProvider;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.vulkan.VkBufferCreateInfo;
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

/** Native compute bridge for the bounded shadowcomp image contract. */
public final class PackShadowCompute implements AutoCloseable {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("chimera");
    private static final Pattern IMAGE_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?"
                    + "(?:(?:uniform|writeonly|readonly|coherent|volatile|restrict)\\s+)*"
                    + "(u?i?image3D)\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern SAMPLER_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+"
                    + "(u?sampler3D|sampler2D|isampler2D|usampler2D|sampler2DShadow)\\s+"
                    + "([A-Za-z_]\\w*)\\s*;");
    private static final Pattern SAMPLER_3D_PARAMETER = Pattern.compile(
            "\\b(?:u|i)?sampler3D\\s+([A-Za-z_]\\w*)");
    private static final Pattern VALUE_UNIFORM_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)[ \\t]*)?uniform[ \\t]+"
                    + "(bool|int|uint|float|vec[234]|ivec[234]|uvec[234]|bvec[234]|mat[234])"
                    + "[ \\t]+([^;\\r\\n]+);");
    private static final Pattern ANY_UNIFORM_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)[ \\t]*)?"
                    + "(?:(?:uniform|writeonly|readonly|coherent|volatile|restrict)[ \\t]+)+"
                    + "([A-Za-z_]\\w*)[ \\t]+([^;\\r\\n]+);");

    private final PackAdvancedResourcePlan.ComputeSpec spec;
    private final PackAdvancedImageOwner owner;
    private final PackResourceOwner sampledResourceOwner;
    private final List<String> imageNames;
    private final List<String> samplerNames;
    private final List<String> sampled2dNames;
    private final List<ComputeUniform> computeUniforms;
    private final int uniformBufferSize;
    private long shaderModule;
    private long setLayout;
    private long descriptorPool;
    private long descriptorSet;
    private long pipelineLayout;
    private long pipeline;
    private long uniformBuffer;
    private long uniformAllocation;
    private long uniformMappedAddress;
    private ByteBuffer uniformData;
    private boolean uniformMappedByChimera;
    private boolean failed;
    private boolean dispatchObserved;

    private PackShadowCompute(
            PackAdvancedResourcePlan.ComputeSpec spec,
            PackAdvancedImageOwner owner,
            PackResourceOwner sampledResourceOwner,
            List<String> imageNames,
            List<String> samplerNames,
            List<String> sampled2dNames,
            List<ComputeUniform> computeUniforms
    ) {
        this.spec = spec;
        this.owner = owner;
        this.sampledResourceOwner = sampledResourceOwner;
        this.imageNames = imageNames;
        this.samplerNames = samplerNames;
        this.sampled2dNames = sampled2dNames;
        this.computeUniforms = computeUniforms == null ? List.of() : List.copyOf(computeUniforms);
        this.uniformBufferSize = uniformBufferSize(this.computeUniforms);
    }

    public static PackShadowCompute load(
            PackAdvancedResourcePlan plan,
            PackAdvancedImageOwner owner,
            Path shadersDir,
            Map<String, String> initialMacros,
            Set<String> lockedMacros
    ) {
        return load(plan, owner, null, shadersDir, initialMacros, lockedMacros);
    }

    public static PackShadowCompute load(
            PackAdvancedResourcePlan plan,
            PackAdvancedImageOwner owner,
            PackResourceOwner sampledResourceOwner,
            Path shadersDir,
            Map<String, String> initialMacros,
            Set<String> lockedMacros
    ) {
        if (plan == null || owner == null || !plan.capabilityPossible()) return null;
        PackAdvancedResourcePlan.ComputeSpec spec = plan.computeStages().get("shadowcomp");
        if (spec == null || !spec.supported()) return null;
        try {
            Path sourcePath = shadersDir.resolve(spec.relativeSource()).normalize();
            String source = Files.readString(sourcePath);
            Map<String, String> macros = PackEngineDefines.forCompute(
                    initialMacros, plan.capabilityPossible());
            ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepareForRuntime(
                    shadersDir, sourcePath, source,
                    macros,
                    PackEngineDefines.lockedNames(
                            lockedMacros, plan.capabilityPossible(), true));
            if (prepared.deviations().stream().anyMatch(value ->
                    value == null || !value.startsWith("PREPROCESSOR_MACRO_REDEFINED:"))) {
                return null;
            }
            List<String> names = spec.imageNames().stream()
                    .filter(name -> owner.imageForSymbol(name) != null)
                    .toList();
            if (names.size() != spec.imageNames().size()) return null;
            List<String> samplers = spec.samplerNames().stream()
                    .filter(name -> owner.imageForSymbol(name) != null)
                    .toList();
            if (samplers.size() != spec.samplerNames().size()) return null;
            List<String> sampled2d = standardSampledResources(prepared.source(), sampledResourceOwner);
            List<ComputeUniform> uniforms = computeUniforms(prepared.source());
            int uniformBinding = names.size() + samplers.size() + sampled2d.size();
            String translated = translate(prepared.source(), names, samplers, sampled2d,
                    owner, sampledResourceOwner, uniforms, uniformBinding);
            if (translated == null) return null;
            PackShadowCompute compute = new PackShadowCompute(
                    spec, owner, sampledResourceOwner, names, samplers, sampled2d,
                    uniforms);
            compute.create(translated);
            if (compute.pipeline == 0L) {
                compute.close();
                return null;
            }
            return compute;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[chimera] shadowcomp load failed: {}", failure.getMessage(), failure);
            return null;
        }
    }

    public boolean isInstalled() { return pipeline != 0L && !failed; }

    public boolean dispatch(VkCommandBuffer commandBuffer) {
        if (!isInstalled() || commandBuffer == null) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!this.dispatchObserved) {
                this.dispatchObserved = true;
                LOGGER.info("[chimera] shadowcomp dispatch recording");
            }
            PackVulkanBarriers.beforeCompute(commandBuffer, stack);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            Set<String> transitioned = new HashSet<>();
            for (String name : imageNames) {
                if (!transitioned.add(name)) continue;
                PackStorageImage image = owner.imageForSymbol(name);
                if (image != null) image.transitionToGeneral(stack, commandBuffer);
            }
            for (String name : samplerNames) {
                if (!transitioned.add(name)) continue;
                PackStorageImage image = owner.imageForSymbol(name);
                if (image != null) image.transitionToGeneral(stack, commandBuffer);
            }
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSet), null);
            if (!computeUniforms.isEmpty() && uniformData != null) {
                MemoryUtil.memSet(uniformData, 0);
                List<UniformRegistry.UniformDeclaration> declarations = computeUniforms.stream()
                        .map(value -> new UniformRegistry.UniformDeclaration(value.name(), value.type()))
                        .toList();
                int[] offsets = computeUniforms.stream().mapToInt(ComputeUniform::offset).toArray();
                int[] sizes = computeUniforms.stream().mapToInt(ComputeUniform::size).toArray();
                PackUniformProvider.writeComputeUniforms(declarations, offsets, sizes, uniformData);
            }
            int x = groups(imageNames.get(0), spec.localSizeX(), true);
            int y = groups(imageNames.get(0), spec.localSizeY(), false);
            int z = Math.max(1, (owner.imageForSymbol(imageNames.get(0)).depth()
                    + spec.localSizeZ() - 1) / spec.localSizeZ());
            vkCmdDispatch(commandBuffer, x, y, z);
            // Advanced images stay in GENERAL. Their sampler aliases and
            // storage-image descriptors can name the same physical image,
            // so moving them to read-only layout here would make the next
            // graphics producer or storage descriptor disagree with Vulkan's
            // tracked layout. The memory barrier below orders this dispatch
            // without changing that shared layout contract.
            PackVulkanBarriers.afterCompute(commandBuffer, stack);
            return true;
        } catch (RuntimeException failure) {
            failed = true;
            LOGGER.warn("[chimera] shadowcomp dispatch error: {}", failure.getMessage(), failure);
            return false;
        }
    }

    private int groups(String name, int local, boolean width) {
        PackStorageImage image = owner.imageForSymbol(name);
        int extent = width ? image.width : image.height;
        return Math.max(1, (extent + local - 1) / local);
    }

    private void create(String source) {
        ByteBuffer code = compileSpirv(source);
        if (code == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!createUniformBuffer(stack)) return;
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(code);
            LongBuffer module = stack.mallocLong(1);
            if (vkCreateShaderModule(Vulkan.getVkDevice(), moduleInfo, null, module) != VK_SUCCESS) return;
            shaderModule = module.get(0);

            int resourceCount = imageNames.size() + samplerNames.size() + sampled2dNames.size();
            int uniformBinding = resourceCount;
            int bindingCount = resourceCount + (uniformBufferSize > 0 ? 1 : 0);
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
            for (int index = 0; index < imageNames.size(); index++) {
                bindings.get(index).binding(index).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            for (int index = 0; index < samplerNames.size(); index++) {
                bindings.get(imageNames.size() + index)
                        .binding(imageNames.size() + index)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            for (int index = 0; index < sampled2dNames.size(); index++) {
                int binding = imageNames.size() + samplerNames.size() + index;
                bindings.get(binding).binding(binding)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            if (uniformBufferSize > 0) {
                bindings.get(uniformBinding).binding(uniformBinding)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);
            LongBuffer layout = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(Vulkan.getVkDevice(), layoutInfo, null, layout) != VK_SUCCESS) return;
            setLayout = layout.get(0);

            boolean hasCombinedSamplers = !samplerNames.isEmpty() || !sampled2dNames.isEmpty();
            boolean hasUniformBuffer = uniformBufferSize > 0;
            int poolSizeCount = 1 + (hasCombinedSamplers ? 1 : 0) + (hasUniformBuffer ? 1 : 0);
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(poolSizeCount, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(imageNames.size());
            int poolIndex = 1;
            if (!samplerNames.isEmpty()) {
                poolSizes.get(poolIndex++).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(samplerNames.size() + sampled2dNames.size());
            } else if (!sampled2dNames.isEmpty()) {
                poolSizes.get(poolIndex++).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(sampled2dNames.size());
            }
            if (hasUniformBuffer) {
                poolSizes.get(poolIndex).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(1);
            }
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(1).pPoolSizes(poolSizes);
            LongBuffer pool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(Vulkan.getVkDevice(), poolInfo, null, pool) != VK_SUCCESS) return;
            descriptorPool = pool.get(0);

            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool).pSetLayouts(stack.longs(setLayout));
            LongBuffer descriptor = stack.mallocLong(1);
            if (vkAllocateDescriptorSets(Vulkan.getVkDevice(), allocateInfo, descriptor) != VK_SUCCESS) return;
            descriptorSet = descriptor.get(0);

            VkDescriptorImageInfo.Buffer images = VkDescriptorImageInfo.calloc(resourceCount, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindingCount, stack);
            for (int index = 0; index < imageNames.size(); index++) {
                PackStorageImage image = owner.imageForSymbol(imageNames.get(index));
                images.get(index).imageView(image.getImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                writes.get(index).sType$Default().dstSet(descriptorSet).dstBinding(index)
                        .dstArrayElement(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(images.slice(index, 1));
            }
            for (int index = 0; index < samplerNames.size(); index++) {
                int binding = imageNames.size() + index;
                PackStorageImage image = owner.imageForSymbol(samplerNames.get(index));
                images.get(binding).sampler(image.getSampler()).imageView(image.getImageView())
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                writes.get(binding).sType$Default().dstSet(descriptorSet).dstBinding(binding)
                        .dstArrayElement(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(images.slice(binding, 1));
            }
            for (int index = 0; index < sampled2dNames.size(); index++) {
                int binding = imageNames.size() + samplerNames.size() + index;
                VulkanImage image = sampledResourceOwner == null
                        ? null : sampledResourceOwner.image(sampled2dNames.get(index));
                if (image == null) return;
                images.get(binding).sampler(image.getSampler()).imageView(image.getImageView())
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                writes.get(binding).sType$Default().dstSet(descriptorSet).dstBinding(binding)
                        .dstArrayElement(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(images.slice(binding, 1));
            }
            if (uniformBufferSize > 0) {
                VkDescriptorBufferInfo.Buffer buffer = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(uniformBuffer).offset(0L).range(uniformBufferSize);
                writes.get(uniformBinding).sType$Default().dstSet(descriptorSet)
                        .dstBinding(uniformBinding).dstArrayElement(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .pBufferInfo(buffer);
            }
            vkUpdateDescriptorSets(Vulkan.getVkDevice(), writes, null);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(setLayout));
            LongBuffer pipelineLayout = stack.mallocLong(1);
            if (vkCreatePipelineLayout(Vulkan.getVkDevice(), pipelineLayoutInfo, null, pipelineLayout) != VK_SUCCESS) return;
            this.pipelineLayout = pipelineLayout.get(0);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(this.pipelineLayout);
            LongBuffer pipeline = stack.mallocLong(1);
            if (vkCreateComputePipelines(Vulkan.getVkDevice(), 0L, pipelineInfo, null, pipeline) == VK_SUCCESS) {
                this.pipeline = pipeline.get(0);
            }
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    /** Allocates one host-visible uniform block for all live compute values. */
    private boolean createUniformBuffer(MemoryStack stack) {
        if (uniformBufferSize <= 0) return true;
        VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(uniformBufferSize)
                .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                .sharingMode(org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE);
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
        uniformBuffer = buffer.get(0);
        uniformAllocation = allocation.get(0);
        uniformMappedAddress = info.pMappedData();
        if (uniformMappedAddress == 0L) {
            PointerBuffer mapped = stack.mallocPointer(1);
            if (Vma.vmaMapMemory(Vulkan.getAllocator(), uniformAllocation, mapped) != VK_SUCCESS) {
                return false;
            }
            uniformMappedAddress = mapped.get(0);
            uniformMappedByChimera = true;
        }
        uniformData = MemoryUtil.memByteBuffer(uniformMappedAddress, uniformBufferSize);
        return true;
    }

    private static ByteBuffer compileSpirv(String source) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                    Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
            result = Shaderc.shaderc_compile_into_spv(compiler, source,
                    Shaderc.shaderc_glsl_compute_shader, "chimera_shadowcomp.csh", "main", options);
            if (result == 0L || Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                if (result != 0L) {
                    LOGGER.warn("[chimera] shadowcomp shaderc compile failed: {}",
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

    private static String translate(
            String source,
            List<String> imageNames,
            List<String> samplerNames,
            List<String> sampled2dNames,
            PackAdvancedImageOwner owner,
            PackResourceOwner sampledResourceOwner,
            List<ComputeUniform> computeUniforms,
            int uniformBinding
    ) {
        if (source == null) return null;
        try {
            String body = normalizeVersion(source);
            body = removeLegacyExtensions(body);
            body = GlslTokenRewriter.rewriteTextureCalls(body);
            Set<String> boundedSamplers = new TreeSet<>(samplerNames);
            Matcher samplerParameter = SAMPLER_3D_PARAMETER.matcher(body);
            while (samplerParameter.find()) {
                boundedSamplers.add(samplerParameter.group(1));
            }
            body = GlslTokenRewriter.rewriteTexelFetchBounds(body, boundedSamplers);

            Set<String> declaredSamplers = new TreeSet<>();
            Set<String> declaredSampled2d = new TreeSet<>();
            Matcher sampler = SAMPLER_DECLARATION.matcher(body);
            StringBuffer samplerResult = new StringBuffer();
            while (sampler.find()) {
                String type = sampler.group(1);
                String name = sampler.group(2);
                if (type.endsWith("2D") || type.equals("sampler2DShadow")) {
                    if (!sampled2dNames.contains(name)) {
                        if (GlslTokenRewriter.identifierCount(body, name) > 1) {
                            throw new IllegalArgumentException("unsupported compute sampler: " + name);
                        }
                        sampler.appendReplacement(samplerResult, "");
                        continue;
                    }
                    if (sampledResourceOwner == null
                            || sampledResourceOwner.image(name) == null) {
                        throw new IllegalArgumentException("compute sampler unavailable: " + name);
                    }
                    int binding = imageNames.size() + samplerNames.size()
                            + sampled2dNames.indexOf(name);
                    String replacement = "layout(binding = " + binding
                            + ") uniform sampler2D " + name + ";";
                    declaredSampled2d.add(name);
                    sampler.appendReplacement(samplerResult, Matcher.quoteReplacement(replacement));
                    continue;
                }
                if (!samplerNames.contains(name)) {
                    if (GlslTokenRewriter.identifierCount(body, name) > 1) {
                        throw new IllegalArgumentException("unsupported compute sampler: " + name);
                    }
                    sampler.appendReplacement(samplerResult, "");
                    continue;
                }
                int binding = imageNames.size() + samplerNames.indexOf(name);
                PackAdvancedResourcePlan.ImageSpec image = owner.specForSymbol(name);
                String replacement = "layout(binding = " + binding + ") uniform "
                        + samplerType(image) + " " + name + ";";
                declaredSamplers.add(name);
                sampler.appendReplacement(samplerResult, Matcher.quoteReplacement(replacement));
            }
            sampler.appendTail(samplerResult);
            body = samplerResult.toString();

            Matcher image = IMAGE_DECLARATION.matcher(body);
            StringBuffer imageResult = new StringBuffer();
            while (image.find()) {
                String type = image.group(1);
                String name = image.group(2);
                int binding = imageNames.indexOf(name);
                if (binding < 0) binding = indexBySampler(imageNames, name, owner);
                if (binding < 0) {
                    throw new IllegalArgumentException("unsupported compute image: " + name);
                }
                PackAdvancedResourcePlan.ImageSpec spec = owner.specForSymbol(name);
                String replacement = "layout(" + spec.internalFormat() + ", binding = " + binding
                        + ") uniform " + imageType(spec, type) + " " + name + ";";
                image.appendReplacement(imageResult, Matcher.quoteReplacement(replacement));
            }
            image.appendTail(imageResult);
            body = imageResult.toString();

            Matcher valueUniform = VALUE_UNIFORM_DECLARATION.matcher(body);
            StringBuffer uniformResult = new StringBuffer();
            while (valueUniform.find()) {
                String type = valueUniform.group(1);
                String declarators = valueUniform.group(2).trim();
                StringBuilder replacement = new StringBuilder();
                for (String declarator : declarators.split(",")) {
                    String name = declarator.trim();
                    int initializer = name.indexOf('=');
                    if (initializer >= 0) name = name.substring(0, initializer).trim();
                    String uniformName = name;
                    ComputeUniform uniform = computeUniforms.stream()
                            .filter(value -> value.name().equals(uniformName))
                            .findFirst().orElse(null);
                    if (uniform == null) {
                        if (GlslTokenRewriter.identifierCount(body, name) > 1) {
                            throw new IllegalArgumentException("unsupported compute uniform: " + name);
                        }
                        continue;
                    }
                }
                valueUniform.appendReplacement(uniformResult,
                        Matcher.quoteReplacement(replacement.toString()));
            }
            valueUniform.appendTail(uniformResult);
            body = uniformResult.toString();

            Matcher unsupportedUniform = ANY_UNIFORM_DECLARATION.matcher(body);
            StringBuffer unsupportedResult = new StringBuffer();
            while (unsupportedUniform.find()) {
                String type = unsupportedUniform.group(1);
                String declarators = unsupportedUniform.group(2).trim();
                if (isTranslatedResourceDeclaration(type, declarators, imageNames, samplerNames,
                        sampled2dNames)) {
                    unsupportedUniform.appendReplacement(unsupportedResult,
                            Matcher.quoteReplacement(unsupportedUniform.group()));
                    continue;
                }
                for (String declarator : declarators.split(",")) {
                    String name = declarator.trim();
                    int initializer = name.indexOf('=');
                    if (initializer >= 0) name = name.substring(0, initializer).trim();
                    name = name.replaceFirst("\\[.*$", "").trim();
                    if (!name.matches("[A-Za-z_]\\w*")) {
                        throw new IllegalArgumentException("unsupported compute uniform declaration: "
                                + type + " " + declarators);
                    }
                    if (GlslTokenRewriter.identifierCount(body, name) > 1) {
                        throw new IllegalArgumentException("unsupported compute uniform: " + name);
                    }
                }
                unsupportedUniform.appendReplacement(unsupportedResult, "");
            }
            unsupportedUniform.appendTail(unsupportedResult);
            body = unsupportedResult.toString();
            body = addDispatchBoundsGuard(body, imageNames);

            StringBuilder generated = new StringBuilder();
            for (String name : samplerNames) {
                if (declaredSamplers.contains(name)) continue;
                if (!GlslTokenRewriter.containsIdentifier(body, name)) continue;
                int binding = imageNames.size() + samplerNames.indexOf(name);
                PackAdvancedResourcePlan.ImageSpec imageSpec = owner.specForSymbol(name);
                generated.append("layout(binding = ").append(binding).append(") uniform ")
                        .append(samplerType(imageSpec)).append(' ').append(name).append(";\n");
            }
            for (String name : sampled2dNames) {
                if (declaredSampled2d.contains(name)) continue;
                if (!GlslTokenRewriter.containsIdentifier(body, name)) continue;
                int binding = imageNames.size() + samplerNames.size() + sampled2dNames.indexOf(name);
                generated.append("layout(binding = ").append(binding)
                        .append(") uniform sampler2D ").append(name).append(";\n");
            }
            StringBuilder values = new StringBuilder();
            if (!computeUniforms.isEmpty()) {
                values.append("layout(std140, binding = ").append(uniformBinding)
                        .append(") uniform ChimeraShadowComputeValues {\n");
                for (ComputeUniform uniform : computeUniforms) {
                    values.append("    ").append(uniform.type()).append(' ')
                            .append(uniform.name()).append(';').append('\n');
                }
                values.append("};\n");
            }
            return "#version 450\n" + values + generated + body;
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] shadowcomp translation failed: {}", failure.getMessage());
            return null;
        }
    }

    private static String normalizeVersion(String source) {
        String body = source.replaceAll("(?m)^\\s*#version\\b[^\\r\\n]*(?:\\r?\\n|$)", "");
        return body;
    }

    private static String addDispatchBoundsGuard(String source, List<String> imageNames) {
        if (source == null || imageNames == null || imageNames.isEmpty()) return source;
        int main = source.indexOf("void main");
        if (main < 0) throw new IllegalArgumentException("compute main function is missing");
        int open = source.indexOf('{', main);
        if (open < 0) throw new IllegalArgumentException("compute main body is missing");
        String image = imageNames.get(0);
        String guard = "\n    ivec3 chimeraDispatchSize = imageSize(" + image + ");\n"
                + "    ivec3 chimeraGlobalId = ivec3(gl_GlobalInvocationID);\n"
                + "    if (any(greaterThanEqual(chimeraGlobalId, chimeraDispatchSize))) return;\n";
        return source.substring(0, open + 1) + guard + source.substring(open + 1);
    }

    private static String removeLegacyExtensions(String source) {
        return source.replaceAll("(?m)^\\s*#extension\\s+GL_ARB_shader_texture_lod\\s*:\\s*"
                + "(?:enable|require|warn)\\s*(?:\\r?\\n|$)", "");
    }

    private static List<ComputeUniform> computeUniforms(String source) {
        if (source == null) return List.of();
        Map<String, String> declarations = new java.util.TreeMap<>();
        Matcher matcher = VALUE_UNIFORM_DECLARATION.matcher(source);
        while (matcher.find()) {
            String type = matcher.group(1);
            for (String raw : matcher.group(2).split(",")) {
                String name = raw.trim();
                int initializer = name.indexOf('=');
                if (initializer >= 0) name = name.substring(0, initializer).trim();
                if (!name.matches("[A-Za-z_]\\w*")) {
                    throw new IllegalArgumentException("unsupported compute uniform declaration: " + raw);
                }
                if (PackUniformProvider.resolveUniform(name, type) != null
                        && GlslTokenRewriter.identifierCount(source, name) > 1) {
                    declarations.putIfAbsent(name, type);
                } else if (GlslTokenRewriter.identifierCount(source, name) > 1) {
                    throw new IllegalArgumentException("unsupported compute uniform: " + name);
                }
            }
        }
        int offset = 0;
        List<ComputeUniform> result = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : declarations.entrySet()) {
            int alignment = computeAlignment(entry.getValue());
            int size = computeSize(entry.getValue());
            offset = align(offset, alignment);
            result.add(new ComputeUniform(entry.getKey(), entry.getValue(), offset, size));
            offset += size;
        }
        return List.copyOf(result);
    }

    private static int uniformBufferSize(List<ComputeUniform> uniforms) {
        if (uniforms == null || uniforms.isEmpty()) return 0;
        ComputeUniform last = uniforms.get(uniforms.size() - 1);
        return align(last.offset() + last.size(), 16);
    }

    private static int computeAlignment(String type) {
        if (type.startsWith("vec3") || type.startsWith("vec4")
                || type.startsWith("ivec3") || type.startsWith("ivec4")
                || type.startsWith("uvec3") || type.startsWith("uvec4")
                || type.startsWith("bvec3") || type.startsWith("bvec4")
                || type.startsWith("mat")) return 16;
        if (type.endsWith("vec2") || type.equals("vec2") || type.equals("ivec2")
                || type.equals("uvec2") || type.equals("bvec2")) return 8;
        return 4;
    }

    private static int computeSize(String type) {
        if (type.equals("mat2")) return 32;
        if (type.equals("mat3")) return 48;
        if (type.equals("mat4")) return 64;
        if (type.startsWith("vec3") || type.startsWith("vec4")
                || type.startsWith("ivec3") || type.startsWith("ivec4")
                || type.startsWith("uvec3") || type.startsWith("uvec4")
                || type.startsWith("bvec3") || type.startsWith("bvec4")) return 16;
        if (type.equals("vec2") || type.equals("ivec2") || type.equals("uvec2")
                || type.equals("bvec2")) return 8;
        return 4;
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private record ComputeUniform(String name, String type, int offset, int size) {}

    private static boolean isTranslatedResourceDeclaration(
            String type,
            String declarators,
            List<String> imageNames,
            List<String> samplerNames,
            List<String> sampled2dNames
    ) {
        if (type == null || declarators == null) return false;
        if (type.equals("sampler2D")) {
            return sampled2dNames.contains(declarators.trim());
        }
        if (!(type.equals("image3D") || type.equals("uimage3D")
                || type.equals("iimage3D") || type.equals("sampler3D")
                || type.equals("usampler3D"))) return false;
        String name = declarators.trim();
        int initializer = name.indexOf('=');
        if (initializer >= 0) name = name.substring(0, initializer).trim();
        if (name.contains(",") || !name.matches("[A-Za-z_]\\w*")) return false;
        return imageNames.contains(name) || samplerNames.contains(name);
    }

    private static String samplerType(PackAdvancedResourcePlan.ImageSpec spec) {
        return isInteger(spec) ? "usampler3D" : "sampler3D";
    }

    private static String imageType(PackAdvancedResourcePlan.ImageSpec spec, String sourceType) {
        return isInteger(spec) ? "uimage3D" : sourceType;
    }

    private static boolean isInteger(PackAdvancedResourcePlan.ImageSpec spec) {
        return spec != null && (spec.internalFormat().equalsIgnoreCase("r8ui")
                || spec.internalFormat().equalsIgnoreCase("r16ui"));
    }

    private static int indexBySampler(List<String> names, String name, PackAdvancedImageOwner owner) {
        for (int index = 0; index < names.size(); index++) {
            PackAdvancedResourcePlan.ImageSpec spec = owner.specForSymbol(names.get(index));
            if (spec != null && spec.sampler().equals(name)) return index;
        }
        return -1;
    }

    /** Resolves only standard sampled resources that already have a pack owner. */
    private static List<String> standardSampledResources(String source, PackResourceOwner owner) {
        if (source == null || owner == null) return List.of();
        if (GlslTokenRewriter.containsIdentifier(source, "noisetex")
                && owner.image("noisetex") != null) {
            return List.of("noisetex");
        }
        return List.of();
    }

    @Override
    public void close() {
        if (pipeline != 0L) vkDestroyPipeline(Vulkan.getVkDevice(), pipeline, null);
        if (pipelineLayout != 0L) vkDestroyPipelineLayout(Vulkan.getVkDevice(), pipelineLayout, null);
        if (descriptorPool != 0L) vkDestroyDescriptorPool(Vulkan.getVkDevice(), descriptorPool, null);
        if (setLayout != 0L) vkDestroyDescriptorSetLayout(Vulkan.getVkDevice(), setLayout, null);
        if (shaderModule != 0L) vkDestroyShaderModule(Vulkan.getVkDevice(), shaderModule, null);
        if (uniformMappedByChimera && uniformAllocation != 0L) {
            Vma.vmaUnmapMemory(Vulkan.getAllocator(), uniformAllocation);
        }
        if (uniformBuffer != 0L) {
            Vma.vmaDestroyBuffer(Vulkan.getAllocator(), uniformBuffer, uniformAllocation);
        }
        pipeline = 0L;
        pipelineLayout = 0L;
        descriptorPool = 0L;
        descriptorSet = 0L;
        setLayout = 0L;
        shaderModule = 0L;
        uniformBuffer = 0L;
        uniformAllocation = 0L;
        uniformMappedAddress = 0L;
        uniformData = null;
        uniformMappedByChimera = false;
    }
}
