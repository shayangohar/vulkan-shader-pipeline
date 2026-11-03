package net.chimera.render;

import java.util.function.IntBinaryOperator;

/**
 * The cloud mesh of Sodium 0.8.13, the clouds Iris 1.8.14 on 1.21.1 runs a pack's gbuffers_clouds
 * over: a box per opaque texel of the cloud texture, 12 blocks wide and 4 high, out to
 * max(32, 2 x render distance + 9) cells on every side, the texture repeating. Under FANCY a cell
 * emits each face its neighbours leave open that can face the camera, and the camera's cell and its
 * four neighbours also emit their six faces wound inward, the walls seen from inside a cloud. Under
 * FAST a cell is one quad at the cloud base. Cells come out in rings of growing taxicab distance
 * from the camera's cell, near to far along every ray.
 *
 * <p>A face's colour is its texel's colour times Sodium's face shade; the cloud colour and opacity
 * come from the shader colour. Positions are in blocks from the camera cell's lower corner at the
 * cloud base. Vanilla's cloud layout carries a texture coordinate and a normal: each face reads its
 * own texel's centre, so a program that samples the cloud texture sees the texel the cell stands
 * for, and the face's axis normal.</p>
 */
public final class CloudMesh {
    /** A cell's width and depth, in blocks. */
    public static final float CELL = 12.0F;
    /** A cell's height, in blocks. */
    public static final float HEIGHT = 4.0F;
    /** A texel below this alpha is no cloud. */
    private static final int OPAQUE_ALPHA = 10;
    /** The faces in emission order; bit f of an open-face mask stands for face f. */
    static final int BOTTOM = 0;
    static final int TOP = 1;
    static final int WEST = 2;
    static final int EAST = 3;
    static final int NORTH = 4;
    static final int SOUTH = 5;
    private static final int FACES = 6;
    /** Each face's corners on the unit box, counterclockwise seen from outside. */
    private static final int[][][] CORNERS = {
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}};
    private static final int[][] NORMALS = {{0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
    /** Sodium's face shades as bytes: 0.7 below, 1.0 on top, 0.9 on the X faces, 0.8 on the Z faces. */
    private static final int[] SHADES = {178, 255, 229, 229, 204, 204};
    private static final int UNSHADED = 255;

    private CloudMesh() {}

    /** Where the camera's eye is against the cloud layer. */
    public enum Orientation {
        BELOW, INSIDE, ABOVE;

        /** Sodium's: within an eighth of a block of the base or the top counts as outside the layer. */
        public static Orientation of(double cameraY, float base) {
            if (cameraY <= base + 0.125F) return BELOW;
            return cameraY >= base + HEIGHT - 0.125F ? ABOVE : INSIDE;
        }
    }

    /** Takes one vertex of vanilla's cloud layout. */
    @FunctionalInterface
    public interface Sink {
        void vertex(float x, float y, float z, float u, float v, int argb, int normalX, int normalY, int normalZ);
    }

    /**
     * The cloud texture as cells: each texel's ARGB colour and the faces it leaves open, 0 for a
     * texel with no cloud.
     */
    public record Cells(int width, int height, int[] colors, byte[] openFaces) {
        /**
         * Reads {@code abgr(x, y)}, NativeImage's pixel order; null when no texel is cloud. Top and
         * bottom are always open; a side is open where the neighbour's pixel differs from this one,
         * the neighbours wrapping at the edges.
         */
        public static Cells of(int width, int height, IntBinaryOperator abgr) {
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) pixels[y * width + x] = abgr.applyAsInt(x, y);
            }
            int[] colors = new int[pixels.length];
            byte[] open = new byte[pixels.length];
            boolean any = false;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[y * width + x];
                    if (pixel >>> 24 < OPAQUE_ALPHA) continue;
                    colors[y * width + x] = argb(pixel);
                    open[y * width + x] = (byte) (1 << BOTTOM | 1 << TOP
                            | differs(pixels, width, height, x - 1, y, pixel) << WEST
                            | differs(pixels, width, height, x + 1, y, pixel) << EAST
                            | differs(pixels, width, height, x, y - 1, pixel) << NORTH
                            | differs(pixels, width, height, x, y + 1, pixel) << SOUTH);
                    any = true;
                }
            }
            return any ? new Cells(width, height, colors, open) : null;
        }

        private static int differs(int[] pixels, int width, int height, int x, int y, int pixel) {
            return pixels[Math.floorMod(y, height) * width + Math.floorMod(x, width)] != pixel ? 1 : 0;
        }
    }

    /** Sodium's reach in cells for an effective render distance in chunks. */
    public static int radius(int renderDistance) {
        return Math.max(32, 2 * renderDistance + 9);
    }

    /**
     * Emits the cells within {@code radius} of origin cell (originX, originZ) on both axes:
     * FANCY's faces for {@code orientation}, or FAST's base quads for null.
     */
    public static void build(Cells cells, int originX, int originZ, int radius, Orientation orientation, Sink sink) {
        cell(cells, originX, originZ, 0, 0, orientation, sink);
        for (int ring = 1; ring <= 2 * radius; ring++) {
            for (int dx = Math.max(-ring, -radius); dx <= Math.min(ring, radius); dx++) {
                int dz = ring - Math.abs(dx);
                if (dz > radius) continue;
                cell(cells, originX, originZ, dx, dz, orientation, sink);
                if (dz != 0) cell(cells, originX, originZ, dx, -dz, orientation, sink);
            }
        }
    }

    private static void cell(Cells cells, int originX, int originZ, int dx, int dz, Orientation orientation,
                             Sink sink) {
        int texelX = Math.floorMod(originX + dx, cells.width());
        int texelZ = Math.floorMod(originZ + dz, cells.height());
        int index = texelZ * cells.width() + texelX;
        int open = cells.openFaces()[index];
        if (open == 0) return;
        float u = (texelX + 0.5F) / cells.width();
        float v = (texelZ + 0.5F) / cells.height();
        int color = cells.colors()[index];
        if (orientation == null) {
            face(sink, BOTTOM, dx, dz, u, v, color, UNSHADED, false);
            return;
        }
        int faces = open & visibleFaces(dx, dz, orientation);
        if (faces == 0) return;
        for (int face = 0; face < FACES; face++) {
            if ((faces & 1 << face) != 0) face(sink, face, dx, dz, u, v, color, SHADES[face], false);
        }
        if (Math.abs(dx) + Math.abs(dz) > 1) return;
        for (int face = 0; face < FACES; face++) face(sink, face, dx, dz, u, v, color, SHADES[face], true);
    }

    /** The faces of the cell at (dx, dz) that can face a camera in the origin cell. */
    static int visibleFaces(int dx, int dz, Orientation orientation) {
        int faces = 0;
        if (orientation != Orientation.ABOVE) faces |= 1 << BOTTOM;
        if (orientation != Orientation.BELOW) faces |= 1 << TOP;
        if (dx >= 0) faces |= 1 << WEST;
        if (dx <= 0) faces |= 1 << EAST;
        if (dz >= 0) faces |= 1 << NORTH;
        if (dz <= 0) faces |= 1 << SOUTH;
        return faces;
    }

    /** One face of the cell at (dx, dz), wound and turned inward for the walls seen from inside. */
    private static void face(Sink sink, int face, int dx, int dz, float u, float v, int color, int shade,
                             boolean inward) {
        int argb = shade(color, shade);
        int sign = inward ? -1 : 1;
        int[] normal = NORMALS[face];
        for (int i = 0; i < 4; i++) {
            int[] corner = CORNERS[face][inward ? (4 - i) % 4 : i];
            sink.vertex(CELL * (dx + corner[0]), HEIGHT * corner[1], CELL * (dz + corner[2]), u, v, argb,
                    sign * normal[0], sign * normal[1], sign * normal[2]);
        }
    }

    /** Sodium's shading: each colour byte c becomes (c * shade + 255) >> 8; alpha stays. */
    static int shade(int argb, int shade) {
        int red = ((argb >> 16 & 0xFF) * shade + 255) >> 8;
        int green = ((argb >> 8 & 0xFF) * shade + 255) >> 8;
        int blue = ((argb & 0xFF) * shade + 255) >> 8;
        return argb & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static int argb(int abgr) {
        return abgr & 0xFF00FF00 | (abgr & 0xFF) << 16 | abgr >> 16 & 0xFF;
    }
}
