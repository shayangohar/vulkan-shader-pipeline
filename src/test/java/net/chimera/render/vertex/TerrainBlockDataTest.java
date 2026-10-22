package net.chimera.render.vertex;

import net.chimera.shaderpack.TerrainMaterialPlan;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The block data each terrain vertex carries, as Iris's Sodium terrain writes it: mc_Entity is
 * the block's id and render type, 0 for a block model and 1 for a fluid
 * (MixinChunkMeshBuildTask.beginBlock, decoded as {@code mc_Entity & 1u}).
 */
final class TerrainBlockDataTest {
    private static final TerrainMaterialPlan PLAN = new TerrainMaterialPlan(true, true, true, true, List.of());

    @Test
    void blocksAreRenderTypeZeroAndFluidsOne() {
        ChimeraExtVertexBuilder builder = new ChimeraExtVertexBuilder(PLAN);
        assertEquals(ChimeraExtVertexBuilder.BLOCK_RENDER_TYPE, writeEntity(builder)[1],
                "a vertex before any block data reads as a block model");
        builder.setBlockData(12, ChimeraExtVertexBuilder.FLUID_RENDER_TYPE, 0);
        assertEquals(12, writeEntity(builder)[0]);
        assertEquals(1, writeEntity(builder)[1]);
        builder.setBlockData(3, ChimeraExtVertexBuilder.BLOCK_RENDER_TYPE, 0);
        assertEquals(0, writeEntity(builder)[1]);
    }

    /** Writes one vertex and returns its (material id, render type) words. */
    private static int[] writeEntity(ChimeraExtVertexBuilder builder) {
        long base = MemoryUtil.nmemCalloc(1, PLAN.stride());
        try {
            builder.vertex(base, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0x00F000F0, ChimeraEntityVertexData.pack(0, 1, 0, 0));
            return new int[] {MemoryUtil.memGetInt(base + 16), MemoryUtil.memGetInt(base + 20)};
        } finally {
            MemoryUtil.nmemFree(base);
        }
    }
}
