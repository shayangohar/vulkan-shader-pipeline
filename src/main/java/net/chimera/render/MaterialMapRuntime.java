package net.chimera.render;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.Optional;

import static org.lwjgl.vulkan.VK10.*;

/** Owns the pass's material-map companions: creates the owner, and pumps its builds at a safe seam. */
final class MaterialMapRuntime {
    private final ChimeraMainPass pass;

    /** Resource-pack material images and flat fallbacks, retained for the renderer lifetime. */
    MaterialMapOwner materialMapOwner;

    MaterialMapRuntime(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /** Renderer-lifetime material owner for the atlas mixin; null before first use. */
    public MaterialMapOwner materialMaps() {
        return this.materialMapOwner;
    }

    /** Creates the material owner once on the render thread; later installs only ensure it. */
    void ensureMaterialMapOwner() {
        if (this.materialMapOwner == null) {
            this.materialMapOwner = MaterialMapOwner.create();
        }
    }

    /**
     * Builds queued atlas companions at the safe frame-boundary seam.
     * Material maps are independent of shader-pack state, so this runs even
     * when no pack is installed. Never throws out of the frame boundary.
     */
    public void pumpMaterialMapBuilds() {
        try {
            ensureMaterialMapOwner();
            var manager = Minecraft.getInstance().getResourceManager();
            MaterialMapOwner.ResourceLookup lookup = (namespace, path) -> {
                try {
                    return manager.getResource(ResourceLocation.fromNamespaceAndPath(namespace, path))
                            .map(resource -> {
                                try {
                                    return resource.open();
                                } catch (IOException openFailure) {
                                    return null;
                                }
                            });
                } catch (RuntimeException lookupFailure) {
                    return Optional.empty();
                }
            };
            this.materialMapOwner.pumpPendingBuilds(lookup, MaterialCompanionPixels::decodeNative,
                    () -> SimpleTextureIndex.snapshot(Minecraft.getInstance().getTextureManager()));
        } catch (RuntimeException failure) {
            ChimeraMainPass.LOGGER.warn("[chimera] material maps: pump failed", failure);
        }
    }
}
