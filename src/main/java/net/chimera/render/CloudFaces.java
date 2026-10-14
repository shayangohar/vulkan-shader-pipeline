package net.chimera.render;

/**
 * Vanilla 1.21.11's cloud faces, as Iris hands them to a pack's gbuffers_clouds program.
 *
 * <p>The walk is CloudRenderer.buildMesh's: rings of cells outward from the camera's cell,
 * clipped to a circle, so the translucent faces keep vanilla's draw order. Each face is decoded
 * as Iris's VanillaTransformer cloud patch decodes vanilla's face buffer: the shared corner
 * table, an axis normal per direction, and a per-face shade with alpha 0.8 (vanilla's own
 * shader uses alpha 1.0; Iris's copy uses 0.8, and the cloud colour multiplies both).
 * Positions are relative to the camera's cell; the frame's CloudOffset is added on the GPU.
 */
final class CloudFaces {
    static final float CELL_WIDTH = 12.0F;
    static final float CELL_HEIGHT = 4.0F;

    /** Direction.get3DDataValue order, which the corner, normal and shade tables follow. */
    static final int DOWN = 0;
    static final int UP = 1;
    static final int NORTH = 2;
    static final int SOUTH = 3;
    static final int WEST = 4;
    static final int EAST = 5;

    /** CloudRenderer.packCellData: the neighbour bits below the cell colour. */
    private static final int NORTH_EMPTY_BIT = 3;
    private static final int EAST_EMPTY_BIT = 2;
    private static final int SOUTH_EMPTY_BIT = 1;
    private static final int WEST_EMPTY_BIT = 0;

    private static final float[][] CORNERS = {
            {1, 0, 0}, {1, 0, 1}, {0, 0, 1}, {0, 0, 0},
            {0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0},
            {0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0},
            {1, 0, 1}, {1, 1, 1}, {0, 1, 1}, {0, 0, 1},
            {0, 0, 1}, {0, 1, 1}, {0, 1, 0}, {0, 0, 0},
            {1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}};
    private static final float[][] NORMALS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    private static final float[] SHADES = {0.7F, 1.0F, 0.8F, 0.8F, 0.9F, 0.9F};
    private static final float FACE_ALPHA = 0.8F;

    /** CloudRenderer.RelativeCameraPos. */
    enum CameraPos { ABOVE, INSIDE, BELOW }

    /** One face, as vanilla encodes it: the cell, its direction and the two face flags. */
    interface FaceSink {
        void face(int cellX, int cellZ, int direction, boolean inside, boolean topColor);
    }

    /** One vertex of a decoded face. */
    interface VertexSink {
        void vertex(float x, float y, float z, int argb, float normalX, float normalY, float normalZ);
    }

    private CloudFaces() {}

    /** Where the camera is against the cloud layer, from the layer's height above it. */
    static CameraPos cameraPos(float relativeHeight) {
        if (relativeHeight + CELL_HEIGHT < 0.0F) return CameraPos.ABOVE;
        return relativeHeight > 0.0F ? CameraPos.BELOW : CameraPos.INSIDE;
    }

    /** The cell radius vanilla builds for a cloud range in chunks. */
    static int radius(int cloudRangeChunks) {
        return (int) Math.ceil((float) (cloudRangeChunks * 16) / CELL_WIDTH);
    }

    /**
     * CloudRenderer.buildMesh: every face around the centre cell, ring by ring. {@code cells} is
     * vanilla's TextureData, row-major, 0 for an empty cell.
     */
    static void build(long[] cells, int width, int height, int centreX, int centreZ, CameraPos camera,
                      boolean fancy, int radius, FaceSink sink) {
        for (int ring = 0; ring <= 2 * radius; ring++) {
            for (int x = -ring; x <= ring; x++) {
                int z = ring - Math.abs(x);
                if (z < 0 || z > radius || x * x + z * z > radius * radius) continue;
                if (z != 0) {
                    cell(cells, width, height, centreX, centreZ, camera, fancy, x, -z, sink);
                }
                cell(cells, width, height, centreX, centreZ, camera, fancy, x, z, sink);
            }
        }
    }

    /** CloudRenderer.tryBuildCell and buildFlatCell. */
    private static void cell(long[] cells, int width, int height, int centreX, int centreZ, CameraPos camera,
                             boolean fancy, int x, int z, FaceSink sink) {
        long data = cells[Math.floorMod(centreX + x, width) + Math.floorMod(centreZ + z, height) * width];
        if (data == 0L) return;
        if (fancy) {
            extrudedCell(camera, x, z, data, sink);
        } else {
            sink.face(x, z, DOWN, false, true);
        }
    }

    /**
     * CloudRenderer.buildExtrudedCell: the top and bottom the camera can see, the open sides that
     * face it, and every face inside the cells around it.
     */
    private static void extrudedCell(CameraPos camera, int x, int z, long data, FaceSink sink) {
        if (camera != CameraPos.BELOW) sink.face(x, z, UP, false, false);
        if (camera != CameraPos.ABOVE) sink.face(x, z, DOWN, false, false);
        if (empty(data, NORTH_EMPTY_BIT) && z > 0) sink.face(x, z, NORTH, false, false);
        if (empty(data, SOUTH_EMPTY_BIT) && z < 0) sink.face(x, z, SOUTH, false, false);
        if (empty(data, WEST_EMPTY_BIT) && x > 0) sink.face(x, z, WEST, false, false);
        if (empty(data, EAST_EMPTY_BIT) && x < 0) sink.face(x, z, EAST, false, false);
        if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
            for (int direction = DOWN; direction <= EAST; direction++) {
                sink.face(x, z, direction, true, false);
            }
        }
    }

    private static boolean empty(long data, int bit) {
        return ((data >> bit) & 1L) != 0L;
    }

    /**
     * Iris's iris_cloudsMain for one face: an inside face runs its corners backwards, so it
     * faces into the cell.
     */
    static void emit(int cellX, int cellZ, int direction, boolean inside, boolean topColor, VertexSink sink) {
        int argb = argb(SHADES[topColor ? UP : direction]);
        float[] normal = NORMALS[direction];
        for (int corner = 0; corner < 4; corner++) {
            float[] vertex = CORNERS[direction * 4 + (inside ? 3 - corner : corner)];
            sink.vertex((vertex[0] + cellX) * CELL_WIDTH, vertex[1] * CELL_HEIGHT, (vertex[2] + cellZ) * CELL_WIDTH,
                    argb, normal[0], normal[1], normal[2]);
        }
    }

    private static int argb(float shade) {
        int grey = Math.round(shade * 255.0F);
        return Math.round(FACE_ALPHA * 255.0F) << 24 | grey << 16 | grey << 8 | grey;
    }
}
