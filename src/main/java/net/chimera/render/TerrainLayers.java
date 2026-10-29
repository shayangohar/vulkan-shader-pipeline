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

    /**
     * The alpha-test reference Iris gives the layer's pack terrain (SodiumPrograms.getAlphaTest):
     * 0.1 for its cutout pass, which holds cutout and cutout_mipped blocks, and none (0) for solid
     * and translucent. This layer split holds solid blocks in cutout_mipped too, which Iris draws
     * untested; their opaque texels pass 0.1 alike.
     */
    public static float irisAlphaReference(TerrainRenderType type) {
        return type == TerrainRenderType.CUTOUT || type == TerrainRenderType.CUTOUT_MIPPED ? 0.1F : 0.0F;
    }

    /**
     * Iris's rendering phase (renderStage) for the blocks a layer draws. Iris sets the phase from the
     * layer LevelRenderer asks for, and Sodium draws its solid and cutout passes in the solid call
     * (SodiumWorldRenderer.drawChunkLayer), so every opaque block reads TERRAIN_SOLID; the cutout
     * calls draw nothing. Packs voxelize and light only that stage (Glimmer's flood-fill light).
     */
    public static PackRenderingPhase phase(TerrainRenderType type) {
        return switch (type) {
            case SOLID, CUTOUT_MIPPED, CUTOUT -> PackRenderingPhase.TERRAIN_SOLID;
            case TRANSLUCENT -> PackRenderingPhase.TERRAIN_TRANSLUCENT;
            case TRIPWIRE -> PackRenderingPhase.TRIPWIRE;
        };
    }
}
