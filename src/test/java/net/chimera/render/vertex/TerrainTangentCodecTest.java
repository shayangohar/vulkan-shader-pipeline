package net.chimera.render.vertex;

import net.chimera.shaderpack.TerrainMaterialPlan;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terrain at_tangent must be the quad's texture U direction with Iris's
 * handedness, not a tangent synthesized from the normal: the synthesized one
 * is a quarter turn off on up/down faces and wrong on rotated textures, which
 * sends pack parallax marching the wrong way.
 */
final class TerrainTangentCodecTest {
    @Test
    void quadTangents() {
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
        assertTrue(decodedMirror[3] == Math.signum(ChimeraEntityVertexData.unpack(mirrored, 3)),
                "mirrored UV handedness lost: " + decodedMirror[3]);
        requireNear(decodedMirror, mirrored, "mirrored UV direction");

        // A texture rotated 45 degrees on a north face stays within one step.
        float r = (float) Math.sqrt(0.5);
        int rotated = ChimeraEntityVertexData.pack(-r, r, 0, 1);
        float[] decodedRotated = TerrainTangentCodec.decode(TerrainTangentCodec.encode(normal, rotated));
        requireNear(decodedRotated, rotated, "rotated texture direction");

        // Byte 0 and an unset word reproduce the reference tangent.
        float[] reference = TerrainTangentCodec.decode(normal);
        assertTrue(Math.abs(reference[0] + 1.0f) < 1.0e-4f && reference[3] == 1.0f,
                "zero code does not reproduce the reference tangent");
        assertTrue((TerrainTangentCodec.encode(normal, rotated) & 0x00FFFFFF) == (normal & 0x00FFFFFF),
                "encoding changed the normal bytes");
    }

    /**
     * The builder stores each quad's face normal from its diagonals, as Iris does. VulkanMod's
     * fluid renderer passes packed normal 0, which a pack would normalize into NaN water
     * lighting; its block renderer passes the facing axis as unit bytes (1/127 once unpacked).
     */
    @Test
    void storesFaceNormals() {
        // Water top face, Minecraft corner order (counter-clockwise from above), no host normal.
        checkStoredNormal(new float[][] {{0, 0.875f, 0}, {0, 0.875f, 1}, {1, 0.875f, 1}, {1, 0.875f, 0}},
                0, new float[] {0, 1, 0});
        // Block east face with VulkanMod's VertexUtil.packNormal(1, 0, 0).
        checkStoredNormal(new float[][] {{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}},
                0x000001, new float[] {1, 0, 0});
    }

    private static void checkStoredNormal(float[][] corners, int hostNormal, float[] expected) {
        TerrainMaterialPlan plan = new TerrainMaterialPlan(
                true, true, true, true, List.of());
        ChimeraExtVertexBuilder builder = new ChimeraExtVertexBuilder(plan);
        int stride = plan.stride();
        long base = MemoryUtil.nmemCalloc(4, stride);
        try {
            for (int index = 0; index < 4; index++) {
                float[] c = corners[index];
                builder.vertex(base + (long) index * stride, c[0], c[1], c[2], 0xFFFFFFFF,
                        index >= 2 ? 1 : 0, index == 1 || index == 2 ? 1 : 0, 0x00F000F0, hostNormal);
            }
            for (int index = 0; index < 4; index++) {
                int word = MemoryUtil.memGetInt(base + (long) index * stride + 32);
                for (int axis = 0; axis < 3; axis++) {
                    assertTrue(Math.abs(ChimeraEntityVertexData.unpack(word, axis) - expected[axis]) < 0.01f,
                            "vertex " + index + " stored normal " + Integer.toHexString(word));
                }
            }
        } finally {
            MemoryUtil.nmemFree(base);
        }
    }

    private static void checkQuad(String face, float nx, float ny, float nz, float[] c,
                                  float tx, float ty, float tz) {
        int tangent = ChimeraEntityVertexData.tangent(nx, ny, nz, false,
                c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9],
                c[10], c[11], c[12], c[13], c[14]);
        float[] expected = {ChimeraEntityVertexData.unpack(tangent, 0),
                ChimeraEntityVertexData.unpack(tangent, 1), ChimeraEntityVertexData.unpack(tangent, 2)};
        assertTrue(Math.abs(expected[0] - tx) < 0.01f && Math.abs(expected[1] - ty) < 0.01f
                        && Math.abs(expected[2] - tz) < 0.01f,
                face + " fixture tangent is not the Minecraft U direction");
        int word = TerrainTangentCodec.encode(ChimeraEntityVertexData.pack(nx, ny, nz, 0), tangent);
        float[] decoded = TerrainTangentCodec.decode(word);
        requireNear(decoded, tangent, face + " round trip");
        assertTrue(decoded[3] == (ChimeraEntityVertexData.unpack(tangent, 3) < 0 ? -1.0f : 1.0f),
                face + " handedness changed");
    }

    private static void requireNear(float[] decoded, int tangent, String message) {
        float dot = decoded[0] * ChimeraEntityVertexData.unpack(tangent, 0)
                + decoded[1] * ChimeraEntityVertexData.unpack(tangent, 1)
                + decoded[2] * ChimeraEntityVertexData.unpack(tangent, 2);
        // One codec step is 5.6 degrees; round-to-nearest stays within half of it.
        assertTrue(dot > Math.cos(Math.toRadians(3.0)) * 0.99f, message + ": dot=" + dot);
    }
}
