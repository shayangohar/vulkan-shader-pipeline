package net.chimera.render.vertex;

/** Pure entity polygon math shared by the runtime writer and conformance tests. */
public final class ChimeraEntityVertexData {
    public static final int FLAT_TANGENT = pack(1.0f, 0.0f, 0.0f, 1.0f);
    private static final float TINY = 1.0e-12f;

    private ChimeraEntityVertexData() {}

    public static int pack(float x, float y, float z, float w) {
        return (snorm(x) & 0xff) | ((snorm(y) & 0xff) << 8)
                | ((snorm(z) & 0xff) << 16) | ((snorm(w) & 0xff) << 24);
    }

    public static float unpack(int word, int component) {
        return (byte) (word >> (component * 8)) / 127.0f;
    }

    public static boolean faceNormal(float[] output, float x0, float y0, float z0,
                                     float x1, float y1, float z1,
                                     float x2, float y2, float z2,
                                     float x3, float y3, float z3) {
        float ax = x2 - x0;
        float ay = y2 - y0;
        float az = z2 - z0;
        float bx = x3 - x1;
        float by = y3 - y1;
        float bz = z3 - z1;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = nx * nx + ny * ny + nz * nz;
        if (length <= TINY) {
            return false;
        }
        float scale = (float) (1.0 / Math.sqrt(length));
        output[0] = nx * scale;
        output[1] = ny * scale;
        output[2] = nz * scale;
        return true;
    }

    public static int tangent(float nx, float ny, float nz, boolean flatten,
                              float x0, float y0, float z0, float u0, float v0,
                              float x1, float y1, float z1, float u1, float v1,
                              float x2, float y2, float z2, float u2, float v2) {
        float px0 = x0;
        float py0 = y0;
        float pz0 = z0;
        float px1 = x1;
        float py1 = y1;
        float pz1 = z1;
        float px2 = x2;
        float py2 = y2;
        float pz2 = z2;
        if (flatten) {
            float d0 = x0 * nx + y0 * ny + z0 * nz;
            float d1 = x1 * nx + y1 * ny + z1 * nz;
            float d2 = x2 * nx + y2 * ny + z2 * nz;
            px0 -= d0 * nx;
            py0 -= d0 * ny;
            pz0 -= d0 * nz;
            px1 -= d1 * nx;
            py1 -= d1 * ny;
            pz1 -= d1 * nz;
            px2 -= d2 * nx;
            py2 -= d2 * ny;
            pz2 -= d2 * nz;
        }

        float e1x = px1 - px0;
        float e1y = py1 - py0;
        float e1z = pz1 - pz0;
        float e2x = px2 - px0;
        float e2y = py2 - py0;
        float e2z = pz2 - pz0;
        float du1 = u1 - u0;
        float dv1 = v1 - v0;
        float du2 = u2 - u0;
        float dv2 = v2 - v0;
        float determinant = du1 * dv2 - du2 * dv1;
        if (!Float.isFinite(determinant) || Math.abs(determinant) <= TINY) {
            return FLAT_TANGENT;
        }
        float scale = 1.0f / determinant;
        float tx = scale * (dv2 * e1x - dv1 * e2x);
        float ty = scale * (dv2 * e1y - dv1 * e2y);
        float tz = scale * (dv2 * e1z - dv1 * e2z);
        float length = tx * tx + ty * ty + tz * tz;
        if (length <= TINY) {
            return FLAT_TANGENT;
        }
        float unit = (float) (1.0 / Math.sqrt(length));
        tx *= unit;
        ty *= unit;
        tz *= unit;
        float bx = scale * (-du2 * e1x + du1 * e2x);
        float by = scale * (-du2 * e1y + du1 * e2y);
        float bz = scale * (-du2 * e1z + du1 * e2z);
        float handedness = bx * (ty * nz - tz * ny)
                + by * (tz * nx - tx * nz)
                + bz * (tx * ny - ty * nx);
        return pack(tx, ty, tz, handedness < 0.0f ? -1.0f : 1.0f);
    }

    private static int snorm(float value) {
        return (int) (Math.max(-1.0f, Math.min(1.0f, value)) * 127.0f);
    }
}
