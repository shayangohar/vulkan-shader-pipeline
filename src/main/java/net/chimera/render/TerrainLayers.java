package net.chimera.render;

import net.chimera.shaderpack.PackRenderingPhase;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.vertex.TerrainRenderType;

/**
 * The section layers VulkanMod draws. Its chunk builds put solid blocks into cutout_mipped,
 * cutout blocks too under uniqueOpaqueLayer, and tripwire into translucent
 * (BuildTask.compactRenderTypes); a layer call holding none draws nothing.
 */
public final class TerrainLayers {
    /** The opaque layer every frame draws first, before any other terrain. */
    public static final TerrainRenderType OPAQUE = TerrainRenderType.CUTOUT_MIPPED;

    private TerrainLayers() {}

    public static boolean drawn(TerrainRenderType type) {
        return (Initializer.CONFIG.uniqueOpaqueLayer
                ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES).contains(type);
    }

    /** Iris's rendering phase for the layer LevelRenderer is drawing. */
    public static PackRenderingPhase phase(TerrainRenderType type) {
        return switch (type) {
            case SOLID -> PackRenderingPhase.TERRAIN_SOLID;
            case CUTOUT_MIPPED -> PackRenderingPhase.TERRAIN_CUTOUT_MIPPED;
            case CUTOUT -> PackRenderingPhase.TERRAIN_CUTOUT;
            case TRANSLUCENT -> PackRenderingPhase.TERRAIN_TRANSLUCENT;
            case TRIPWIRE -> PackRenderingPhase.TRIPWIRE;
        };
    }
}
