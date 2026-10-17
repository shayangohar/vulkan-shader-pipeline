package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finalized layout and its descriptor inventory; no Vulkan objects are allocated here. */
final class OrdinaryDescriptorContract {
    private final PackDescriptorLayout layout;
    private final boolean semanticTransformBlocks;
    private final List<PackAdvancedResourcePlan.DescriptorBinding> descriptors;
    private final int nextBinding;
    private final List<PackResourceBinding> resources;

    OrdinaryDescriptorContract(PackDescriptorLayout layout) {
        this(layout, List.of(), false);
    }

    OrdinaryDescriptorContract(PackDescriptorLayout layout, List<PackResourceBinding> resources) {
        this(layout, resources, false);
    }

    /**
     * @param semanticTransformBlocks true for the guarded family contract. Its
     *        bindings 0 and 1 carry the host per-draw transforms, and the bridge
     *        addresses them by name.
     */
    OrdinaryDescriptorContract(PackDescriptorLayout layout, List<PackResourceBinding> resources,
                               boolean semanticTransformBlocks) {
        this.layout = layout;
        this.resources = List.copyOf(resources);
        this.semanticTransformBlocks = semanticTransformBlocks;
        List<PackAdvancedResourcePlan.DescriptorBinding> inventory = new ArrayList<>();
        for (var block : layout.uniformBlocks()) inventory.add(new PackAdvancedResourcePlan.DescriptorBinding(
                "UBO" + block.binding(), 0, block.binding(), 8, block.stages(), "ubo:" + block.binding()));
        for (var image : layout.images()) {
            int type = switch (image.type()) {
                case "sampler2D" -> 1;
                case "image2D" -> 3;
                default -> throw new IllegalArgumentException("ORDINARY_DESCRIPTOR_TYPE_UNSUPPORTED:" + image.type());
            };
            inventory.add(new PackAdvancedResourcePlan.DescriptorBinding(image.name(), 0, image.binding(),
                    type, 63, "resource:" + image.textureSlot() + ":" + type));
        }
        inventory.sort(Comparator.comparingInt(PackAdvancedResourcePlan.DescriptorBinding::binding));
        descriptors = List.copyOf(inventory);
        nextBinding = inventory.stream().mapToInt(value -> value.binding() + 1).max().orElse(0);
    }

    List<PackAdvancedResourcePlan.DescriptorBinding> descriptors() { return descriptors; }
    List<PackResourceBinding> resources() { return resources; }

    /** The layout the host pipeline is built from. */
    PackDescriptorLayout layout() { return layout; }

    /**
     * True for a guarded family contract, whose blocks at bindings 0 and 1 carry the host
     * transform names ({@code UniformRegistry.DYNAMIC_TRANSFORMS_UBO} and {@code PROJECTION_UBO})
     * because the converted family preambles declare them and the bridge looks them up by name.
     */
    boolean semanticTransformBlocks() { return semanticTransformBlocks; }

    OrdinaryDescriptorContract withResources(List<PackResourceBinding> resolved) {
        List<PackResourceBinding> bindings = new ArrayList<>(resolved);
        for (var resource : resources) {
            if (PackResourcePlan.isHostTexture(resource.resourceKey())
                    && resolved.stream().noneMatch(value -> value.slot() == resource.slot())) bindings.add(resource);
        }
        return new OrdinaryDescriptorContract(layout, bindings, semanticTransformBlocks);
    }
    int nextBinding() { return nextBinding; }
}
