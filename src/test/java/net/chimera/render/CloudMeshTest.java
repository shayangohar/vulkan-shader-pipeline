package net.chimera.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sodium 0.8.13's cloud mesh, which Iris 1.8.14 on 1.21.1 runs a pack's gbuffers_clouds over:
 * RenderPearl 2.8 never samples the cloud texture, so on vanilla's tile sheets its clouds were one
 * solid haze; on Sodium's cells they keep the texture's shapes.
 */
final class CloudMeshTest {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int CLEAR = 0x00000000;

    private record Vertex(float x, float y, float z, float u, float v, int argb, int nx, int ny, int nz) {}

    private record Quad(List<Vertex> corners) {
        Vertex first() {
            return corners.get(0);
        }

        /** A base quad's cell offset along X and Z. */
        int cellX() {
            return (int) Math.floor(minimum(0) / CloudMesh.CELL + 1.0e-3);
        }

        int cellZ() {
            return (int) Math.floor(minimum(2) / CloudMesh.CELL + 1.0e-3);
        }

        private float minimum(int axis) {
            float min = Float.MAX_VALUE;
            for (Vertex corner : corners) min = Math.min(min, axis == 0 ? corner.x() : corner.z());
            return min;
        }

        /** (v1 - v0) x (v2 - v0): the normal a counterclockwise quad faces. */
        float[] winding() {
            Vertex a = corners.get(0);
            Vertex b = corners.get(1);
            Vertex c = corners.get(2);
            float[] u = {b.x() - a.x(), b.y() - a.y(), b.z() - a.z()};
            float[] v = {c.x() - a.x(), c.y() - a.y(), c.z() - a.z()};
            return new float[] {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
        }
    }

    private static List<Quad> build(CloudMesh.Cells cells, int originX, int originZ, int radius,
                                    CloudMesh.Orientation orientation) {
        List<Vertex> vertices = new ArrayList<>();
        CloudMesh.build(cells, originX, originZ, radius, orientation,
                (x, y, z, u, v, argb, nx, ny, nz) -> vertices.add(new Vertex(x, y, z, u, v, argb, nx, ny, nz)));
        assertEquals(0, vertices.size() % 4, "the mesh is quads");
        List<Quad> quads = new ArrayList<>();
        for (int i = 0; i < vertices.size(); i += 4) quads.add(new Quad(vertices.subList(i, i + 4)));
        return quads;
    }

    /** A texture with one cloud texel at (x, z), every other texel clear. */
    private static CloudMesh.Cells single(int width, int height, int x, int z) {
        return CloudMesh.Cells.of(width, height, (tx, tz) -> tx == x && tz == z ? WHITE : CLEAR);
    }

    @Test
    void reachFollowsTheRenderDistance() {
        assertEquals(32, CloudMesh.radius(2));
        assertEquals(32, CloudMesh.radius(11));
        assertEquals(41, CloudMesh.radius(16));
        assertEquals(73, CloudMesh.radius(32));
    }

    @Test
    void theEyeWithinAnEighthOfABlockOfTheLayerIsOutsideIt() {
        float base = 192.33F;
        assertEquals(CloudMesh.Orientation.BELOW, CloudMesh.Orientation.of(base + 0.1, base));
        assertEquals(CloudMesh.Orientation.INSIDE, CloudMesh.Orientation.of(base + 0.2, base));
        assertEquals(CloudMesh.Orientation.INSIDE, CloudMesh.Orientation.of(base + 3.8, base));
        assertEquals(CloudMesh.Orientation.ABOVE, CloudMesh.Orientation.of(base + 3.9, base));
    }

    @Test
    void aTextureWithoutCloudHasNoCells() {
        assertNull(CloudMesh.Cells.of(4, 4, (x, z) -> 0x09FFFFFF));
    }

    /** Sides open where the neighbour differs, wrapping at the texture's edges; top and bottom always. */
    @Test
    void sidesOpenOntoDifferentNeighboursAcrossTheEdges() {
        CloudMesh.Cells cells = CloudMesh.Cells.of(4, 1, (x, z) -> x == 0 || x == 3 ? WHITE : CLEAR);
        int west = 1 << CloudMesh.WEST;
        int east = 1 << CloudMesh.EAST;
        int base = 1 << CloudMesh.BOTTOM | 1 << CloudMesh.TOP | 1 << CloudMesh.NORTH | 1 << CloudMesh.SOUTH;
        // A one-row texture is its own neighbour north and south: those sides stay closed.
        assertEquals(1 << CloudMesh.BOTTOM | 1 << CloudMesh.TOP | east, cells.openFaces()[0]);
        assertEquals(1 << CloudMesh.BOTTOM | 1 << CloudMesh.TOP | west, cells.openFaces()[3]);
        assertEquals(0, cells.openFaces()[1], "a clear texel opens nothing");
        CloudMesh.Cells lone = single(3, 3, 1, 1);
        assertEquals(base | west | east, lone.openFaces()[4]);
    }

    /**
     * From below, the camera's own cell shows its bottom and every side, then its six faces again
     * wound inward; a lone cell two east of it, level on Z, shows only its bottom, its west face and
     * both Z faces.
     */
    @Test
    void fancyCellsShowTheFacesThatCanFaceTheCamera() {
        assertEquals(List.of("0,-1,0", "-1,0,0", "1,0,0", "0,0,-1", "0,0,1",
                        "0,1,0", "0,-1,0", "1,0,0", "-1,0,0", "0,0,1", "0,0,-1"),
                normals(build(single(3, 3, 1, 1), 1, 1, 0, CloudMesh.Orientation.BELOW)));
        List<Quad> east = build(single(5, 5, 2, 2), 0, 2, 2, CloudMesh.Orientation.BELOW);
        assertEquals(List.of("0,-1,0", "-1,0,0", "0,0,-1", "0,0,1"), normals(east));
        assertTrue(east.stream().allMatch(q -> q.corners().stream().allMatch(c -> c.x() >= 24 && c.x() <= 36)),
                "the cell two east spans x 24..36: " + east);
    }

    /** Every outer face winds counterclockwise toward its normal, every inner one toward its own. */
    @Test
    void facesWindTowardTheirNormals() {
        for (CloudMesh.Orientation orientation : CloudMesh.Orientation.values()) {
            for (Quad quad : build(single(8, 8, 0, 0), 0, 0, 32, orientation)) {
                float[] winding = quad.winding();
                Vertex corner = quad.first();
                float facing = winding[0] * corner.nx() + winding[1] * corner.ny() + winding[2] * corner.nz();
                float length = (float) Math.sqrt(winding[0] * winding[0] + winding[1] * winding[1]
                        + winding[2] * winding[2]);
                assertEquals(length, facing, 1.0e-3F, orientation + " quad winds against its normal: " + quad);
            }
        }
    }

    /** Inside the layer a lone cell shows all six faces, then all six inward, each shaded by its plane. */
    @Test
    void facesTakeSodiumsShadesAndTheirTexelsCentre() {
        CloudMesh.Cells cells = CloudMesh.Cells.of(4, 4, (x, z) -> x == 2 && z == 1 ? 0xC0FFFFFF : CLEAR);
        List<Quad> quads = build(cells, 2, 1, 0, CloudMesh.Orientation.INSIDE);
        assertEquals(List.of("0,-1,0", "0,1,0", "-1,0,0", "1,0,0", "0,0,-1", "0,0,1",
                "0,1,0", "0,-1,0", "1,0,0", "-1,0,0", "0,0,1", "0,0,-1"), normals(quads));
        int[] shades = {178, 255, 229, 229, 204, 204};
        for (int i = 0; i < quads.size(); i++) {
            Vertex corner = quads.get(i).first();
            int shade = shades[i % shades.length];
            assertEquals(0xC0 << 24 | shade << 16 | shade << 8 | shade, corner.argb(), "shade of " + quads.get(i));
            assertEquals(0.625F, corner.u(), 1.0e-6F, "the texel centre");
            assertEquals(0.375F, corner.v(), 1.0e-6F, "the texel centre");
            assertTrue(quads.get(i).corners().stream().allMatch(c -> c.argb() == corner.argb()
                    && c.u() == corner.u() && c.v() == corner.v()), "a face is flat: one colour and one texel");
        }
    }

    @Test
    void fastDrawsOneBaseQuadPerCell() {
        CloudMesh.Cells cells = CloudMesh.Cells.of(2, 2, (x, z) -> x == 0 ? WHITE : CLEAR);
        List<Quad> quads = build(cells, 0, 0, 32, null);
        assertEquals(65 * 33, quads.size(), "every cloud cell within reach, once");
        for (Quad quad : quads) {
            assertTrue(quad.corners().stream().allMatch(c -> c.y() == 0.0F && c.ny() == -1),
                    "a FAST cell is its base, facing down");
            assertEquals(WHITE, quad.first().argb(), "FAST keeps the texel colour");
        }
    }

    /** Cells come out near to far, which Sodium's single pass relies on along each ray. */
    @Test
    void cellsComeOutInRingsOfGrowingDistance() {
        CloudMesh.Cells cells = CloudMesh.Cells.of(1, 1, (x, z) -> WHITE);
        int last = 0;
        Set<String> seen = new HashSet<>();
        for (Quad quad : build(cells, 5, -7, 32, null)) {
            int distance = Math.abs(quad.cellX()) + Math.abs(quad.cellZ());
            assertTrue(distance >= last, "cell " + quad + " came after a farther one");
            assertTrue(Math.abs(quad.cellX()) <= 32 && Math.abs(quad.cellZ()) <= 32, "within reach: " + quad);
            assertTrue(seen.add(quad.cellX() + "," + quad.cellZ()), "each cell once: " + quad);
            last = distance;
        }
        assertEquals(65 * 65, seen.size(), "the full square of cells");
    }

    @Test
    void shadingRoundsAsSodiumDoes() {
        assertEquals(0xFFB2B2B2, CloudMesh.shade(WHITE, 178));
        assertEquals(0x80FFFFFF, CloudMesh.shade(0x80FFFFFF, 255));
        assertEquals(0xFF000000, CloudMesh.shade(0xFF000000, 229));
        assertEquals(0xFF668000, CloudMesh.shade(0xFFCCFF00, 128));
    }

    private static List<String> normals(List<Quad> quads) {
        return quads.stream().map(q -> q.first().nx() + "," + q.first().ny() + "," + q.first().nz()).toList();
    }
}
