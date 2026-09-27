package net.chimera.render.vertex;

/**
 * Packs a terrain quad's texture tangent into the spare fourth byte of its
 * normal word, so the modern terrain format keeps its stride.
 *
 * <p>The tangent lies in the face plane, so it is one angle around the
 * normal from the same reference the terrain shader builds
 * ({@code cross(axis, N)}, axis +Y, or +X on up/down faces), plus Iris's
 * handedness. The byte is {@code handedness * (1 + step)} with 64 steps per
 * turn: block faces land on exact quarter turns, rotated models within 2.9
 * degrees. Zero means "no quad tangent", which the shader reads as the
 * reference itself with handedness +1, the pre-codec behaviour.</p>
 */
public final class TerrainTangentCodec {
    public static final int STEPS = 64;
    private static final float TINY = 1.0e-12f;

    private TerrainTangentCodec() {}

    /** The normal word with its fourth byte replaced by the encoded tangent frame. */
    public static int encode(int packedNormal, int packedTangent) {
        float[] frame = frame(packedNormal);
        int base = packedNormal & 0x00FFFFFF;
        if (frame == null) return base;
        float tx = ChimeraEntityVertexData.unpack(packedTangent, 0);
        float ty = ChimeraEntityVertexData.unpack(packedTangent, 1);
        float tz = ChimeraEntityVertexData.unpack(packedTangent, 2);
        float alongR = tx * frame[3] + ty * frame[4] + tz * frame[5];
        float alongS = tx * frame[6] + ty * frame[7] + tz * frame[8];
        if (alongR * alongR + alongS * alongS <= TINY) return base;
        double turn = Math.atan2(alongS, alongR) / (2.0 * Math.PI);
        int step = Math.floorMod((int) Math.round(turn * STEPS), STEPS);
        int handedness = ChimeraEntityVertexData.unpack(packedTangent, 3) < 0.0f ? -1 : 1;
        return base | (((handedness * (1 + step)) & 0xFF) << 24);
    }

    /** Decodes as the terrain shader does: xyz tangent and handedness w. */
    public static float[] decode(int word) {
        float[] frame = frame(word);
        if (frame == null) return new float[] {1.0f, 0.0f, 0.0f, 1.0f};
        int code = (byte) (word >>> 24);
        if (code == 0) return new float[] {frame[3], frame[4], frame[5], 1.0f};
        double angle = 2.0 * Math.PI * (Math.abs(code) - 1) / STEPS;
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        return new float[] {
                c * frame[3] + s * frame[6],
                c * frame[4] + s * frame[7],
                c * frame[5] + s * frame[8],
                code < 0 ? -1.0f : 1.0f};
    }

    /** Unit normal N, reference R = cross(axis, N) and S = cross(N, R), or null. */
    private static float[] frame(int packedNormal) {
        float nx = ChimeraEntityVertexData.unpack(packedNormal, 0);
        float ny = ChimeraEntityVertexData.unpack(packedNormal, 1);
        float nz = ChimeraEntityVertexData.unpack(packedNormal, 2);
        float length = nx * nx + ny * ny + nz * nz;
        if (!(length > TINY)) return null;
        float unit = (float) (1.0 / Math.sqrt(length));
        nx *= unit;
        ny *= unit;
        nz *= unit;
        boolean vertical = Math.abs(ny) >= 0.999f;
        float ax = vertical ? 1.0f : 0.0f;
        float ay = vertical ? 0.0f : 1.0f;
        float rx = ay * nz;
        float ry = -ax * nz;
        float rz = ax * ny - ay * nx;
        float rUnit = (float) (1.0 / Math.sqrt(rx * rx + ry * ry + rz * rz));
        rx *= rUnit;
        ry *= rUnit;
        rz *= rUnit;
        return new float[] {nx, ny, nz, rx, ry, rz,
                ny * rz - nz * ry, nz * rx - nx * rz, nx * ry - ny * rx};
    }
}
