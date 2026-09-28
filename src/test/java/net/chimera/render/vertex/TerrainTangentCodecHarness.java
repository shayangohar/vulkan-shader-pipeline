package net.chimera.render.vertex;

/**
 * Terrain at_tangent must be the quad's texture U direction with Iris's
 * handedness, not a tangent synthesized from the normal: the synthesized one
 * is a quarter turn off on up/down faces and wrong on rotated textures, which
 * sends pack parallax marching the wrong way.
 */
public final class TerrainTangentCodecHarness {
    private TerrainTangentCodecHarness() {}

    public static void main(String[] args) {
        // Minecraft top face: corners (0,1,0) (0,1,1) (1,1,1) (1,1,0) with U along +X.
        checkQuad("up", 0, 1, 0,
                new float[] {0, 1, 0, 0, 0, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0},
                1, 0, 0);
        // North face (-Z): U runs along -X, V down.
        checkQuad("north", 0, 0, -1,
                new float[] {1, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 0, 1, 0, 1, 0},
                -1, 0, 0);
        // East face (+X): U runs along -Z.
        checkQuad("east", 1, 0, 0,
                new float[] {1, 1, 1, 0, 0, 1, 0, 1, 0, 1, 1, 0, 0, 1, 1, 1, 1, 0, 1, 0},
                0, 0, -1);
        // Mirrored UVs keep the direction and flip the handedness.
        int normal = ChimeraEntityVertexData.pack(0, 0, -1, 0);
        int mirrored = ChimeraEntityVertexData.tangent(0, 0, -1, false,
                1, 1, 0, 1, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1);
        float[] decodedMirror = TerrainTangentCodec.decode(TerrainTangentCodec.encode(normal, mirrored));
        require(decodedMirror[3] == Math.signum(ChimeraEntityVertexData.unpack(mirrored, 3)),
                "mirrored UV handedness lost: " + decodedMirror[3]);
        requireNear(decodedMirror, mirrored, "mirrored UV direction");

        // A texture rotated 45 degrees on a north face stays within one step.
        float r = (float) Math.sqrt(0.5);
        int rotated = ChimeraEntityVertexData.pack(-r, r, 0, 1);
        float[] decodedRotated = TerrainTangentCodec.decode(TerrainTangentCodec.encode(normal, rotated));
        requireNear(decodedRotated, rotated, "rotated texture direction");

        // Byte 0 and an unset word reproduce the reference tangent.
        float[] reference = TerrainTangentCodec.decode(normal);
        require(Math.abs(reference[0] + 1.0f) < 1.0e-4f && reference[3] == 1.0f,
                "zero code does not reproduce the reference tangent");
        require((TerrainTangentCodec.encode(normal, rotated) & 0x00FFFFFF) == (normal & 0x00FFFFFF),
                "encoding changed the normal bytes");
        checkFluidNormal();
        System.out.println("[chimera] terrain tangent codec: PASS");
    }

    /**
     * VulkanMod's fluid renderer passes packed normal 0. The builder must
     * store the quad's face normal, as Iris does, or the pack normalizes a
     * zero vector and water lighting and reflections turn NaN.
     */
    private static void checkFluidNormal() {
        net.chimera.shaderpack.TerrainMaterialPlan plan = new net.chimera.shaderpack.TerrainMaterialPlan(
                true, true, true, true, false, java.util.List.of());
        ChimeraExtVertexBuilder builder = new ChimeraExtVertexBuilder(plan);
        int stride = plan.stride();
        long base = org.lwjgl.system.MemoryUtil.nmemCalloc(4, stride);
        try {
            // Water top face, Minecraft corner order (counter-clockwise from above).
            float[][] corners = {{0, 0.875f, 0}, {0, 0.875f, 1}, {1, 0.875f, 1}, {1, 0.875f, 0}};
            for (int index = 0; index < 4; index++) {
                float[] c = corners[index];
                builder.vertex(base + (long) index * stride, c[0], c[1], c[2], 0xFFFFFFFF,
                        index >= 2 ? 1 : 0, index == 1 || index == 2 ? 1 : 0, 0x00F000F0, 0);
            }
            for (int index = 0; index < 4; index++) {
                int word = org.lwjgl.system.MemoryUtil.memGetInt(base + (long) index * stride + 32);
                require(Math.abs(ChimeraEntityVertexData.unpack(word, 1) - 1.0f) < 0.01f
                                && Math.abs(ChimeraEntityVertexData.unpack(word, 0)) < 0.01f
                                && Math.abs(ChimeraEntityVertexData.unpack(word, 2)) < 0.01f,
                        "fluid vertex " + index + " kept a missing normal: " + Integer.toHexString(word));
            }
        } finally {
            org.lwjgl.system.MemoryUtil.nmemFree(base);
        }
    }

    private static void checkQuad(String face, float nx, float ny, float nz, float[] c,
                                  float tx, float ty, float tz) {
        int tangent = ChimeraEntityVertexData.tangent(nx, ny, nz, false,
                c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9],
                c[10], c[11], c[12], c[13], c[14]);
        float[] expected = {ChimeraEntityVertexData.unpack(tangent, 0),
                ChimeraEntityVertexData.unpack(tangent, 1), ChimeraEntityVertexData.unpack(tangent, 2)};
        require(Math.abs(expected[0] - tx) < 0.01f && Math.abs(expected[1] - ty) < 0.01f
                        && Math.abs(expected[2] - tz) < 0.01f,
                face + " fixture tangent is not the Minecraft U direction");
        int word = TerrainTangentCodec.encode(ChimeraEntityVertexData.pack(nx, ny, nz, 0), tangent);
        float[] decoded = TerrainTangentCodec.decode(word);
        requireNear(decoded, tangent, face + " round trip");
        require(decoded[3] == (ChimeraEntityVertexData.unpack(tangent, 3) < 0 ? -1.0f : 1.0f),
                face + " handedness changed");
    }

    private static void requireNear(float[] decoded, int tangent, String message) {
        float dot = decoded[0] * ChimeraEntityVertexData.unpack(tangent, 0)
                + decoded[1] * ChimeraEntityVertexData.unpack(tangent, 1)
                + decoded[2] * ChimeraEntityVertexData.unpack(tangent, 2);
        // One codec step is 5.6 degrees; round-to-nearest stays within half of it.
        require(dot > Math.cos(Math.toRadians(3.0)) * 0.99f, message + ": dot=" + dot);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
