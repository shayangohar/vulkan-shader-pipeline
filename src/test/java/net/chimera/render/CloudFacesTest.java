package net.chimera.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Vanilla 1.21.11's cloud faces (CloudRenderer.buildMesh), decoded as Iris decodes them. */
final class CloudFacesTest {
    /** A cell whose four neighbours are all empty: packCellData's low four bits set. */
    private static final long ISLAND = 0b1111L;

    private record Face(int x, int z, int direction, boolean inside, boolean topColor) {}

    private static List<Face> build(long[] cells, int size, int centreX, int centreZ,
                                    CloudFaces.CameraPos camera, boolean fancy, int radius) {
        List<Face> faces = new ArrayList<>();
        CloudFaces.build(cells, size, size, centreX, centreZ, camera, fancy, radius,
                (x, z, direction, inside, topColor) -> faces.add(new Face(x, z, direction, inside, topColor)));
        return faces;
    }

    @Test
    void cameraPositionAndRadiusFollowVanilla() {
        assertEquals(CloudFaces.CameraPos.BELOW, CloudFaces.cameraPos(0.5F), "layer above the camera");
        assertEquals(CloudFaces.CameraPos.INSIDE, CloudFaces.cameraPos(0.0F), "camera at the layer's bottom");
        assertEquals(CloudFaces.CameraPos.INSIDE, CloudFaces.cameraPos(-4.0F), "camera at the layer's top");
        assertEquals(CloudFaces.CameraPos.ABOVE, CloudFaces.cameraPos(-4.5F), "layer below the camera");
        assertEquals(86, CloudFaces.radius(64), "cloud range 64 chunks");
        assertEquals(3, CloudFaces.radius(2), "cloud range 2 chunks");
    }

    /** The camera's own cell shows its bottom from below, and every face from inside. */
    @Test
    void cameraCellFromBelow() {
        long[] cells = new long[16];
        cells[2 + 2 * 4] = ISLAND;
        List<Face> faces = build(cells, 4, 2, 2, CloudFaces.CameraPos.BELOW, true, 3);
        List<Face> expected = new ArrayList<>(List.of(new Face(0, 0, CloudFaces.DOWN, false, false)));
        for (int direction = CloudFaces.DOWN; direction <= CloudFaces.EAST; direction++) {
            expected.add(new Face(0, 0, direction, true, false));
        }
        assertEquals(expected, faces);
    }

    /** A cell two east shows its top from above and its west side, which faces the camera. */
    @Test
    void distantCellShowsOnlyFacesTowardTheCamera() {
        long[] cells = new long[64];
        cells[2] = ISLAND;
        assertEquals(List.of(new Face(2, 0, CloudFaces.UP, false, false),
                        new Face(2, 0, CloudFaces.WEST, false, false)),
                build(cells, 8, 0, 0, CloudFaces.CameraPos.ABOVE, true, 4));
        assertEquals(List.of(new Face(2, 0, CloudFaces.DOWN, false, true)),
                build(cells, 8, 0, 0, CloudFaces.CameraPos.ABOVE, false, 4), "fast clouds draw one top-coloured face");
    }

    /** Rings outward from the camera, each ring west to east with the north cell first. */
    @Test
    void facesFollowVanillaRingOrder() {
        long[] cells = new long[64];
        Arrays.fill(cells, 1L << 4);
        List<int[]> order = build(cells, 8, 4, 4, CloudFaces.CameraPos.ABOVE, true, 1).stream()
                .filter(face -> !face.inside()).map(face -> new int[] {face.x(), face.z()}).toList();
        assertEquals(List.of("0,0", "-1,0", "0,-1", "0,1", "1,0"),
                order.stream().map(cell -> cell[0] + "," + cell[1]).toList());
    }

    /** Iris's corner table, normals and shades; an inside face runs its corners backwards. */
    @Test
    void facesDecodeAsIrisDoes() {
        List<float[]> vertices = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        CloudFaces.VertexSink sink = (x, y, z, argb, normalX, normalY, normalZ) -> {
            vertices.add(new float[] {x, y, z, normalX, normalY, normalZ});
            colors.add(argb);
        };
        CloudFaces.emit(1, -1, CloudFaces.DOWN, false, false, sink);
        assertEquals(List.of("24.0,0.0,-12.0", "24.0,0.0,0.0", "12.0,0.0,0.0", "12.0,0.0,-12.0"),
                vertices.stream().map(v -> v[0] + "," + v[1] + "," + v[2]).toList());
        assertEquals("0.0,-1.0,0.0", vertices.get(0)[3] + "," + vertices.get(0)[4] + "," + vertices.get(0)[5]);
        assertEquals(0xCCB3B3B3, (int) colors.get(0), "bottom shade 0.7 at Iris's face alpha 0.8");

        vertices.clear();
        colors.clear();
        CloudFaces.emit(0, 0, CloudFaces.EAST, true, false, sink);
        assertEquals(List.of("12.0,0.0,12.0", "12.0,4.0,12.0", "12.0,4.0,0.0", "12.0,0.0,0.0"),
                vertices.stream().map(v -> v[0] + "," + v[1] + "," + v[2]).toList(), "inside corners reversed");
        assertEquals("1.0,0.0,0.0", vertices.get(0)[3] + "," + vertices.get(0)[4] + "," + vertices.get(0)[5]);

        colors.clear();
        CloudFaces.emit(0, 0, CloudFaces.DOWN, false, true, sink);
        assertEquals(0xCCFFFFFF, (int) colors.get(0), "fast clouds use the top colour");
    }
}
