package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finalized layout and its descriptor inventory; no Vulkan objects are allocated here. */
final class OrdinaryDescriptorContract {
    private final PackDescriptorLayout layout;
    private final List<PackAdvancedResourcePlan.DescriptorBinding> descriptors;
    private final int nextBinding;
    private final List<PackResourceBinding> resources;

    OrdinaryDescriptorContract(PackDescriptorLayout layout) {
        this(layout, List.of());
    }

    OrdinaryDescriptorContract(PackDescriptorLayout layout, List<PackResourceBinding> resources) {
        this.layout = layout;
        this.resources = List.copyOf(resources);
        List<PackAdvancedResourcePlan.DescriptorBinding> inventory = new ArrayList<>(layout.descriptorBindings());
        inventory.sort(Comparator.comparingInt(PackAdvancedResourcePlan.DescriptorBinding::binding));
        descriptors = List.copyOf(inventory);
        nextBinding = inventory.stream().mapToInt(value -> value.binding() + 1).max().orElse(0);
    }

    List<PackAdvancedResourcePlan.DescriptorBinding> descriptors() { return descriptors; }
    List<PackResourceBinding> resources() { return resources; }

    /** The layout the host pipeline is built from. */
    PackDescriptorLayout layout() { return layout; }

    OrdinaryDescriptorContract withResources(List<PackResourceBinding> resolved) {
        List<PackResourceBinding> bindings = new ArrayList<>(resolved);
        for (var resource : resources) {
            if (PackResourcePlan.isHostTexture(resource.resourceKey())
                    && resolved.stream().noneMatch(value -> value.slot() == resource.slot())) bindings.add(resource);
        }
        return new OrdinaryDescriptorContract(layout, bindings);
    }
    int nextBinding() { return nextBinding; }
}
