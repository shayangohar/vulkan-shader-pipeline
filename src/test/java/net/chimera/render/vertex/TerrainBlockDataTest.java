package net.chimera.render.vertex;

import net.chimera.shaderpack.TerrainMaterialPlan;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block data each terrain vertex carries, as Iris's Sodium terrain writes it: mc_Entity is
 * the block's id and render type, 0 for a block model and 1 for a fluid
 * (MixinChunkMeshBuildTask.beginBlock, decoded as {@code mc_Entity & 1u}), and at_midBlock points
 * at the block's centre.
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

    /**
     * Terrain at_midBlock must be Iris's: for every vertex, the offset to the centre of the block
     * being rendered in 1/64 blocks, then the block's light emission. A centre taken from the
     * vertex names the neighbouring block for every vertex on a +X, +Y or +Z face, and Solas's
     * voxelization, which places each block at vertex + at_midBlock / 64, raced neighbouring
     * blocks into one voxel: its light sources flickered.
     */
    @Test
    void midBlockOffsets() {
        ChimeraExtVertexBuilder builder = new ChimeraExtVertexBuilder(PLAN);
        // A glowstone-like block at section position (4, 7, 9), emission 15.
        builder.setBlockData(7, ChimeraExtVertexBuilder.BLOCK_RENDER_TYPE, 15);
        builder.setBlockPosition(4, 7, 9);
        // Its +X face lies on x = 5, the boundary with block (5, 7, 9).
        float[][] eastFace = {{5, 8, 10}, {5, 7, 10}, {5, 7, 9}, {5, 8, 9}};
        int[][] words = write(builder, PLAN.stride(), eastFace);
        for (int index = 0; index < 4; index++) {
            float[] vertex = eastFace[index];
            for (int axis = 0; axis < 3; axis++) {
                float centre = vertex[axis] + words[index][axis] / 64.0F;
                assertTrue(centre == new float[] {4.5F, 7.5F, 9.5F}[axis],
                        "vertex " + index + " axis " + axis + " points at " + centre + ", not its block's centre");
            }
            assertTrue(words[index][3] == 15, "vertex " + index + " lost the block's emission: " + words[index][3]);
        }
        // A torch-like model inside the block keeps the exact offset Iris computes.
        builder.setBlockData(9, ChimeraExtVertexBuilder.BLOCK_RENDER_TYPE, 7);
        float[][] post = {{4.4375F, 7.625F, 9.4375F}, {4.4375F, 7, 9.4375F},
                {4.5625F, 7, 9.5625F}, {4.5625F, 7.625F, 9.5625F}};
        int[][] postWords = write(builder, PLAN.stride(), post);
        assertTrue(postWords[0][0] == 4 && postWords[0][1] == -8 && postWords[0][2] == 4 && postWords[0][3] == 7,
                "inner model offset differs from Iris: " + Arrays.toString(postWords[0]));
    }

    /** Writes one quad and returns each vertex's at_midBlock bytes, signed as the shader reads them. */
    private static int[][] write(ChimeraExtVertexBuilder builder, int stride, float[][] corners) {
        long base = MemoryUtil.nmemCalloc(4, stride);
        try {
            for (int index = 0; index < 4; index++) {
                float[] c = corners[index];
                builder.vertex(base + (long) index * stride, c[0], c[1], c[2], 0xFFFFFFFF,
                        index >= 2 ? 1 : 0, index == 1 || index == 2 ? 1 : 0, 0x00F000F0,
                        ChimeraEntityVertexData.pack(1, 0, 0, 0));
            }
            int[][] result = new int[4][4];
            for (int index = 0; index < 4; index++) {
                int word = MemoryUtil.memGetInt(base + (long) index * stride + 28);
                for (int lane = 0; lane < 4; lane++) result[index][lane] = (byte) (word >>> (lane * 8));
            }
            return result;
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
