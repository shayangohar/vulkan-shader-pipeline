package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Finalized image descriptors and their explicit, program-scoped execution resolvers. */
public record ProgramImageBindingManifest(List<Entry> entries) {
    public enum Kind { COLOR_TARGET, DEPTH_TARGET, SHADOW_DEPTH, SHADOW_COLOR,
        PACK_TEXTURE, HOST_TEXTURE, ADVANCED_IMAGE, MATERIAL_MAP }

    public record Entry(String symbol, int slot, int descriptorType, int stageMask,
            Kind kind, String resourceKey, int set, int binding, String sourceSymbol) {
        public Entry {
            Objects.requireNonNull(symbol);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(resourceKey);
            Objects.requireNonNull(sourceSymbol);
            if (resourceKey.isBlank()) throw failure("IMAGE_RESOLVER_MISSING:" + symbol);
        }
    }

    public ProgramImageBindingManifest { entries = List.copyOf(entries); }

    static ProgramImageBindingManifest from(PackAdvancedResourcePlan.ProgramBindingLayout layout,
            OrdinaryDescriptorContract ordinary) {
        List<Entry> entries = new ArrayList<>();
        for (var descriptor : ordinary.descriptors()) {
            if (descriptor.type() != 1 && descriptor.type() != 3) continue;
            int slot = selector(descriptor);
            PackResourceBinding resource = ordinary.resources().stream()
                    .filter(value -> value.slot() == slot).findFirst().orElse(null);
            if (resource == null) throw failure("IMAGE_RESOLVER_MISSING:" + descriptor.symbol());
            Kind kind = switch (resource.kind()) {
                case TARGET -> PackResourcePlan.isHostTexture(resource.resourceKey())
                        ? Kind.HOST_TEXTURE : Kind.COLOR_TARGET;
                case DEPTH -> Kind.DEPTH_TARGET;
                case SHADOW_DEPTH -> Kind.SHADOW_DEPTH;
                case SHADOW_COLOR -> Kind.SHADOW_COLOR;
                case PACK_TEXTURE, NOISE, GAME_RESOURCE -> Kind.PACK_TEXTURE;
                case MATERIAL_MAP -> Kind.MATERIAL_MAP;
                default -> throw failure("IMAGE_RESOLVER_MISSING:" + descriptor.symbol());
            };
            entries.add(new Entry(descriptor.symbol(), slot, descriptor.type(), descriptor.stages(),
                    kind, resource.resourceKey(), descriptor.set(), descriptor.binding(), resource.sampler()));
        }
        if (layout != null) {
            for (int i = 0; i < layout.storageImages().size(); i++) {
                var image = layout.storageImages().get(i);
                entries.add(new Entry("Sampler" + image.selectorSlot(), image.selectorSlot(), 3, 63,
                        Kind.ADVANCED_IMAGE, image.imageName(), 0, layout.imageBase() + i, image.symbol()));
            }
            for (int i = 0; i < layout.advancedSamplers().size(); i++) {
                var image = layout.advancedSamplers().get(i);
                entries.add(new Entry("Sampler" + image.selectorSlot(), image.selectorSlot(), 1, 63,
                        Kind.ADVANCED_IMAGE, image.imageName(), 0, layout.samplerBase() + i, image.sampler()));
            }
        }
        ProgramImageBindingManifest manifest = new ProgramImageBindingManifest(entries);
        if (layout != null) {
            for (var descriptor : layout.descriptors()) {
                if (descriptor.type() != 1 && descriptor.type() != 3) continue;
                Entry entry = entries.stream().filter(value -> value.set() == descriptor.set()
                        && value.binding() == descriptor.binding()).findFirst().orElse(null);
                if (entry == null || entry.slot() != selector(descriptor) || entry.descriptorType() != descriptor.type()
                        || (entry.stageMask() & descriptor.stages()) != descriptor.stages()
                        || !(entry.symbol().equals(descriptor.symbol()) || entry.sourceSymbol().equals(descriptor.symbol())))
                    throw failure("IMAGE_MANIFEST_MISMATCH:" + descriptor.symbol());
            }
        }
        return manifest;
    }

    /** Exact bijection against the actual builder inventory, before native allocation. */
    public void verify(List<PackAdvancedResourcePlan.DescriptorBinding> actual) {
        Map<String, Entry> remaining = new HashMap<>();
        Map<Integer, Entry> slots = new HashMap<>();
        for (Entry entry : entries) {
            if (!SelectorNamespace.isAddressable(entry.slot())) throw failure("SELECTOR_SLOT_UNSUPPORTED:" + entry.slot());
            Entry previous = slots.putIfAbsent(entry.slot(), entry);
            if (previous != null && (previous.kind() != entry.kind()
                    || !previous.resourceKey().equals(entry.resourceKey())))
                throw failure("IMAGE_RESOLVER_CONFLICT:" + entry.symbol());
            if (remaining.put(entry.set() + ":" + entry.binding(), entry) != null)
                throw failure("IMAGE_MANIFEST_DUPLICATE:" + entry.symbol());
        }
        for (var descriptor : actual) {
            if (descriptor.type() != 1 && descriptor.type() != 3) continue;
            Entry entry = remaining.remove(descriptor.set() + ":" + descriptor.binding());
            if (entry == null) throw failure("IMAGE_RESOLVER_MISSING:" + descriptor.symbol());
            if (!entry.symbol().equals(descriptor.symbol()) || entry.slot() != selector(descriptor)
                    || entry.descriptorType() != descriptor.type() || entry.stageMask() != descriptor.stages())
                throw failure("IMAGE_MANIFEST_MISMATCH:" + descriptor.symbol());
        }
        if (!remaining.isEmpty()) throw failure("IMAGE_DESCRIPTOR_MISSING:" + remaining.values().iterator().next().symbol());
    }

    private static int selector(PackAdvancedResourcePlan.DescriptorBinding descriptor) {
        String prefix = "resource:";
        String identity = descriptor.identity();
        int end = identity.indexOf(':', prefix.length());
        if (!identity.startsWith(prefix) || end < 0) throw failure("IMAGE_SELECTOR_MISSING:" + descriptor.symbol());
        try { return Integer.parseInt(identity.substring(prefix.length(), end)); }
        catch (NumberFormatException failure) { throw failure("IMAGE_SELECTOR_MISSING:" + descriptor.symbol()); }
    }

    private static PackPipelines.PreparationFailure failure(String reason) {
        return new PackPipelines.PreparationFailure("descriptor-contract", reason);
    }
}
