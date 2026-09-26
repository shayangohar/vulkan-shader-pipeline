package net.chimera.render;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Resource-reload generation boundary for material companions.
 *
 * <p>Registered after vanilla textures, so atlas uploads observed during a
 * reload already belong to the incoming pack set when {@code apply} runs.
 * The listener only signals: {@code apply} advances the owner generation,
 * which retires built companions and identity caches at the next pump.
 * No file or GPU work happens here, and a missing owner (chimera idle) is
 * a silent no-op.</p>
 */
public final class ChimeraMaterialReloadListener extends SimplePreparableReloadListener<Integer> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChimeraMaterialReloadListener.class);
    public static final Identifier ID =
            Identifier.fromNamespaceAndPath("chimera", "material_maps");

    private final Supplier<MaterialMapOwner> owners;

    public ChimeraMaterialReloadListener(Supplier<MaterialMapOwner> owners) {
        this.owners = owners;
    }

    /** Production listener over the live renderer-owned material maps. */
    public static ChimeraMaterialReloadListener live() {
        return new ChimeraMaterialReloadListener(() -> {
            var pass = ChimeraRenderer.getMainPass();
            return pass == null ? null : pass.materialMaps();
        });
    }

    @Override
    protected Integer prepare(ResourceManager manager, ProfilerFiller profiler) {
        return 0;
    }

    @Override
    protected void apply(Integer prepared, ResourceManager manager, ProfilerFiller profiler) {
        try {
            MaterialMapOwner owner = owners.get();
            if (owner != null) owner.bumpGeneration();
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] material maps: generation bump failed", failure);
        }
    }
}
