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

    /**
     * Iris's voxelizeLightBlocks quad (MixinChunkMeshBuildTask.iris$setLightBlock): four vertices at
     * the light block's corner + 0.25, colour 0, UV 0 as XHFPTerrainVertex encodes it (wrapped to
     * the top word) with a mid-coordinate of 0, the emission as both lightmap coordinates, the
     * block's id with render type 0, and an at_midBlock of the emission alone. The next block's
     * quads take their mid-block offsets again.
     */
    @Test
    void lightBlockQuadIsIrisVoxelQuad() {
        ChimeraExtVertexBuilder builder = new ChimeraExtVertexBuilder(PLAN);
        int stride = PLAN.stride();
        long base = MemoryUtil.nmemCalloc(8, stride);
        try {
            builder.lightBlockQuad(base, 42, 15, 3, 4, 5);
            short corner = (short) (3.25F * 2048.0F - 4.0F * 2048.0F);
            for (int index = 0; index < 4; index++) {
                long ptr = base + (long) index * stride;
                assertEquals(corner, MemoryUtil.memGetShort(ptr), "x of vertex " + index);
                assertEquals((short) (4.25F * 2048.0F - 4.0F * 2048.0F), MemoryUtil.memGetShort(ptr + 2));
                assertEquals((short) (5.25F * 2048.0F - 4.0F * 2048.0F), MemoryUtil.memGetShort(ptr + 4));
                assertEquals((short) (240 << 8 | 240), MemoryUtil.memGetShort(ptr + 6), "block and sky light 15");
                assertEquals(0, MemoryUtil.memGetInt(ptr + 8), "colour");
                assertEquals(32767, MemoryUtil.memGetShort(ptr + 12), "u as Iris's wrapped word reads");
                assertEquals(32767, MemoryUtil.memGetShort(ptr + 14), "v as Iris's wrapped word reads");
                assertEquals(0, MemoryUtil.memGetInt(ptr + 24), "mc_midTexCoord is the raw centroid, 0");
                assertEquals(42, MemoryUtil.memGetInt(ptr + 16), "the block's id");
                assertEquals(ChimeraExtVertexBuilder.BLOCK_RENDER_TYPE, MemoryUtil.memGetInt(ptr + 20));
                assertEquals(15 << 24, MemoryUtil.memGetInt(ptr + 28), "at_midBlock is the emission alone");
                assertEquals(ChimeraExtVertexBuilder.LIGHT_BLOCK_NORMAL & 0x00FFFFFF,
                        MemoryUtil.memGetInt(ptr + 32) & 0x00FFFFFF, "the zero-area quad keeps Iris's +Z normal");
            }
            builder.setBlockData(7, ChimeraExtVertexBuilder.BLOCK_RENDER_TYPE, 0);
            builder.setBlockPosition(3, 4, 5);
            for (int index = 4; index < 8; index++) {
                builder.vertex(base + (long) index * stride, 3, 4, 5, 0xFFFFFFFF, 0, 0, 0x00F000F0,
                        ChimeraEntityVertexData.pack(0, 1, 0, 0));
            }
            assertEquals(32, (byte) MemoryUtil.memGetInt(base + 4L * stride + 28),
                    "a later block's vertex points at its centre again");
        } finally {
            MemoryUtil.nmemFree(base);
        }
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
