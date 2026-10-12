package net.chimera.render.vertex;

/**
 * Terrain at_midBlock must be Iris's: for every vertex, the offset to the centre of the block
 * being rendered in 1/64 blocks, then the block's light emission. A centre taken from the
 * vertex names the neighbouring block for every vertex on a +X, +Y or +Z face, and Solas's
 * voxelization, which places each block at vertex + at_midBlock / 64, raced neighbouring
 * blocks into one voxel: its light sources flickered.
 */
public final class TerrainMidBlockHarness {
    private TerrainMidBlockHarness() {}

    public static void main(String[] args) {
        net.chimera.shaderpack.TerrainMaterialPlan plan = new net.chimera.shaderpack.TerrainMaterialPlan(
                true, true, true, true, false, java.util.List.of());
        ChimeraExtVertexBuilder builder = new ChimeraExtVertexBuilder(plan);
        // A glowstone-like block at section position (4, 7, 9), emission 15.
        builder.setBlockData(7, -1, 15, 4, 7, 9);
        // Its +X face lies on x = 5, the boundary with block (5, 7, 9).
        float[][] eastFace = {{5, 8, 10}, {5, 7, 10}, {5, 7, 9}, {5, 8, 9}};
        int[][] words = write(builder, plan.stride(), eastFace);
        for (int index = 0; index < 4; index++) {
            float[] vertex = eastFace[index];
            for (int axis = 0; axis < 3; axis++) {
                float centre = vertex[axis] + words[index][axis] / 64.0F;
                require(centre == new float[] {4.5F, 7.5F, 9.5F}[axis],
                        "vertex " + index + " axis " + axis + " points at " + centre + ", not its block's centre");
            }
            require(words[index][3] == 15, "vertex " + index + " lost the block's emission: " + words[index][3]);
        }
        // A torch-like model inside the block keeps the exact offset Iris computes.
        builder.setBlockData(9, -1, 7, 4, 7, 9);
        float[][] post = {{4.4375F, 7.625F, 9.4375F}, {4.4375F, 7, 9.4375F},
                {4.5625F, 7, 9.5625F}, {4.5625F, 7.625F, 9.5625F}};
        int[][] postWords = write(builder, plan.stride(), post);
        require(postWords[0][0] == 4 && postWords[0][1] == -8 && postWords[0][2] == 4 && postWords[0][3] == 7,
                "inner model offset differs from Iris: " + java.util.Arrays.toString(postWords[0]));
        System.out.println("[chimera] terrain at_midBlock: PASS");
    }

    /** Writes one quad and returns each vertex's at_midBlock bytes, signed as the shader reads them. */
    private static int[][] write(ChimeraExtVertexBuilder builder, int stride, float[][] corners) {
        long base = org.lwjgl.system.MemoryUtil.nmemCalloc(4, stride);
        try {
            for (int index = 0; index < 4; index++) {
                float[] c = corners[index];
                builder.vertex(base + (long) index * stride, c[0], c[1], c[2], 0xFFFFFFFF,
                        index >= 2 ? 1 : 0, index == 1 || index == 2 ? 1 : 0, 0x00F000F0,
                        ChimeraEntityVertexData.pack(1, 0, 0, 0));
            }
            int[][] result = new int[4][4];
            for (int index = 0; index < 4; index++) {
                int word = org.lwjgl.system.MemoryUtil.memGetInt(base + (long) index * stride + 28);
                for (int lane = 0; lane < 4; lane++) result[index][lane] = (byte) (word >>> (lane * 8));
            }
            return result;
        } finally {
            org.lwjgl.system.MemoryUtil.nmemFree(base);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
