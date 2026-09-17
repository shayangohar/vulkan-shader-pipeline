package net.chimera.shaderpack;

import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import java.util.ArrayList;
import java.util.List;

/** Finalized config and its descriptor inventory; no Vulkan objects are allocated here. */
final class OrdinaryDescriptorContract {
    private final PipelineConfig config;
    private final List<PackAdvancedResourcePlan.DescriptorBinding> descriptors;
    private final int nextBinding;
    private final List<PackResourceBinding> resources;

    OrdinaryDescriptorContract(PipelineConfig input) {
        this(input, List.of());
    }

    OrdinaryDescriptorContract(PipelineConfig input, List<PackResourceBinding> resources) {
        this.resources = List.copyOf(resources);
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
    OrdinaryDescriptorContract withResources(List<PackResourceBinding> resolved) {
        List<PackResourceBinding> bindings = new ArrayList<>(resolved);
        for (var resource : resources) {
            if ((resource.resourceKey().equals("texture") || resource.resourceKey().equals("lightmap"))
                    && resolved.stream().noneMatch(value -> value.slot() == resource.slot())) bindings.add(resource);
        }
        return new OrdinaryDescriptorContract(config, bindings);
    }
    int nextBinding() { return nextBinding; }
    void apply(Pipeline.Builder builder) { builder.applyConfig(config); }
}
