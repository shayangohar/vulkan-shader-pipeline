package net.chimera.shaderpack;

import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import java.util.ArrayList;
import java.util.List;

/** Finalized config and its descriptor inventory; no Vulkan objects are allocated here. */
final class OrdinaryDescriptorContract {
    private final PipelineConfig config;
    private final boolean semanticTransformBlocks;
    private final List<PackAdvancedResourcePlan.DescriptorBinding> descriptors;
    private final int nextBinding;
    private final List<PackResourceBinding> resources;

    OrdinaryDescriptorContract(PipelineConfig input) {
        this(input, List.of(), false);
    }

    OrdinaryDescriptorContract(PipelineConfig input, List<PackResourceBinding> resources) {
        this(input, resources, false);
    }

    /**
     * @param semanticTransformBlocks true for the guarded family contract. Its
     *        bindings 0 and 1 carry the host per-draw transforms, and the bridge
     *        addresses them with {@code Pipeline.getUBO(String)}.
     */
    OrdinaryDescriptorContract(PipelineConfig input, List<PackResourceBinding> resources,
                               boolean semanticTransformBlocks) {
        this.resources = List.copyOf(resources);
        this.semanticTransformBlocks = semanticTransformBlocks;
        var ubs = input.ubs.stream().map(ub -> new PipelineConfig.UB(
                ub.binding, ub.stage, ub.size, List.copyOf(ub.uniforms))).toList();
        var push = input.pushConstantsInfo;
        config = new PipelineConfig(new java.util.EnumMap<>(input.shaderPaths), ubs,
                List.copyOf(input.imageDescriptors), push == null ? null : new PipelineConfig.UB(
                        push.binding, push.stage, push.size, List.copyOf(push.uniforms)));
        List<PackAdvancedResourcePlan.DescriptorBinding> inventory = new ArrayList<>();
        for (var ub : config.ubs) inventory.add(new PackAdvancedResourcePlan.DescriptorBinding(
                "UBO" + ub.binding, 0, ub.binding, 8, ub.stage, "ubo:" + ub.binding));
        for (var image : config.imageDescriptors) {
            int type = switch (image.type()) {
                case "sampler2D" -> 1;
                case "image2D" -> 3;
                default -> throw new IllegalArgumentException("ORDINARY_DESCRIPTOR_TYPE_UNSUPPORTED:" + image.type());
            };
            inventory.add(new PackAdvancedResourcePlan.DescriptorBinding(image.name(), 0, image.binding(),
                    type, 63, "resource:" + image.imageIdx() + ":" + type));
        }
        inventory.sort(java.util.Comparator.comparingInt(PackAdvancedResourcePlan.DescriptorBinding::binding));
        descriptors = List.copyOf(inventory);
        nextBinding = inventory.stream().mapToInt(value -> value.binding() + 1).max().orElse(0);
    }

    List<PackAdvancedResourcePlan.DescriptorBinding> descriptors() { return descriptors; }
    List<PackResourceBinding> resources() { return resources; }

    /** Finalized uniform blocks with their bindings, stages, sizes, and fields. */
    List<PipelineConfig.UB> uboBlocks() { return config.ubs; }

    OrdinaryDescriptorContract withResources(List<PackResourceBinding> resolved) {
        List<PackResourceBinding> bindings = new ArrayList<>(resolved);
        for (var resource : resources) {
            if (PackResourcePlan.isHostTexture(resource.resourceKey())
                    && resolved.stream().noneMatch(value -> value.slot() == resource.slot())) bindings.add(resource);
        }
        return new OrdinaryDescriptorContract(config, bindings, semanticTransformBlocks);
    }
    int nextBinding() { return nextBinding; }

    void apply(Pipeline.Builder builder) {
        builder.applyConfig(config);
        if (semanticTransformBlocks) {
            builder.setUniforms(namedTransformBlocks(builder.getUBOs()), builder.getImageDescriptors());
        }
    }

    /**
     * Rebuilds the host transform blocks under the names the bridge looks up.
     *
     * <p>VulkanMod names config-declared blocks {@code "UBO: <binding>"}, while
     * its reflected host pipelines carry the semantic block names of their GLSL
     * and {@code Pipeline.getUBO(String)} compares names exactly. The converted
     * family preambles declare {@code DynamicTransforms} at binding 0 and
     * {@code Projection} at binding 1, so a guarded family pipeline must carry
     * those names too or the host per-draw slices can never be bound.</p>
     */
    private static List<UBO> namedTransformBlocks(List<UBO> blocks) {
        List<UBO> named = new ArrayList<>(blocks.size());
        for (UBO block : blocks) {
            String name = switch (block.binding) {
                case UniformRegistry.DYNAMIC_TRANSFORMS_BINDING -> UniformRegistry.DYNAMIC_TRANSFORMS_UBO;
                case UniformRegistry.PROJECTION_BINDING -> UniformRegistry.PROJECTION_UBO;
                default -> null;
            };
            named.add(name == null ? block : namedCopy(block, name));
        }
        return named;
    }

    /** Rebuilds one block under a new name, keeping its fields, suppliers, and buffer state. */
    static UBO namedCopy(UBO block, String name) {
        var uniforms = AlignedStruct.builder();
        for (var uniform : block.getUniforms()) {
            uniforms.addUniform(uniform.getInfo());
        }
        UBO copy = uniforms.buildUBO(name, block.binding, block.stages);
        copy.setUseGlobalBuffer(block.useGlobalBuffer());
        return copy;
    }
}
