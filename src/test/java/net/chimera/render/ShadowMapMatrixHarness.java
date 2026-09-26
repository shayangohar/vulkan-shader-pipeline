package net.chimera.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Pure matrix and snapshot-state checks for the M8.7 pack shadow contract. */
public final class ShadowMapMatrixHarness {
    private ShadowMapMatrixHarness() {}

    public static void main(String[] args) {
        verifyProjectionRanges();
        verifyPackMvpInverseCancellation();
        verifyCameraOriginCompensation();
        verifySnapshotPublicationAndInvalidation();
        verifyFixedReceiverFollowsMapWriter();
        System.out.println("[chimera] M8.7 shadow matrix contract: PASS (GPU map sampling remains a runtime gate)");
    }

    private static void verifyProjectionRanges() {
        float near = 0.1f;
        float far = 512.0f;
        Matrix4f legacy = new Matrix4f();
        Matrix4f host = new Matrix4f();
        ChimeraShadowMap.createLightProjection(legacy, 128.0f, 128.0f, false);
        ChimeraShadowMap.createLightProjection(host, 128.0f, 128.0f, true);
        assertNear(-1.0f, legacy.transformPosition(new Vector3f(0.0f, 0.0f, -near)).z,
                "legacy projection near plane");
        assertNear(1.0f, legacy.transformPosition(new Vector3f(0.0f, 0.0f, -far)).z,
                "legacy projection far plane");
        assertNear(0.0f, host.transformPosition(new Vector3f(0.0f, 0.0f, -near)).z,
                "host forward-Z near plane");
        assertNear(1.0f, host.transformPosition(new Vector3f(0.0f, 0.0f, -far)).z,
                "host forward-Z far plane");
    }

    private static void verifyPackMvpInverseCancellation() {
        Vector3f light = new Vector3f(0.31f, 0.82f, -0.27f).normalize();
        ChimeraShadowMap map = new ChimeraShadowMap();
        map.updateLight(light);
        Matrix4f view = new Matrix4f(map.getLightView());
        Matrix4f projection = new Matrix4f(map.getPackLightProjection());
        Matrix4f mvp = new Matrix4f(projection).mul(view);
        Matrix4f inverseRoundTrip = new Matrix4f(view).invert()
                .mul(new Matrix4f(projection).invert()).mul(mvp);
        Vector3f point = new Vector3f(7.25f, -3.5f, 19.0f);
        Vector3f reconstructed = inverseRoundTrip.transformPosition(new Vector3f(point));
        assertVectorNear(point, reconstructed, "inverse shadow V/P cancellation");
    }

    private static void verifyCameraOriginCompensation() {
        // Large world coordinates retain sub-block motion until the final local
        // delta is converted to float, matching the section-relative renderer.
        double mapX = 30_000_000.25;
        double mapY = 64.75;
        double mapZ = -20_000_000.125;
        double nowX = 30_000_002.25;
        double nowY = 65.5;
        double nowZ = -20_000_004.125;
        double worldX = mapX + 12.25;
        double worldY = mapY + 4.5;
        double worldZ = mapZ - 7.75;

        Matrix4f mapView = new Matrix4f().rotateY(0.63f).rotateX(-0.27f)
                .translate(3.0f, -2.0f, 8.0f);
        Matrix4f atMap = new Matrix4f(mapView);
        Matrix4f atNow = ChimeraShadowMap.compensatedView(new Matrix4f(), mapView,
                mapX, mapY, mapZ, nowX, nowY, nowZ);
        Vector3f relativeAtMap = new Vector3f((float) (worldX - mapX),
                (float) (worldY - mapY), (float) (worldZ - mapZ));
        Vector3f relativeNow = new Vector3f((float) (worldX - nowX),
                (float) (worldY - nowY), (float) (worldZ - nowZ));
        Vector3f mapped = atMap.transformPosition(relativeAtMap);
        Vector3f compensated = atNow.transformPosition(relativeNow);
        assertVectorNear(mapped, compensated, "stationary world point across camera motion");
    }

    private static void verifySnapshotPublicationAndInvalidation() {
        ChimeraShadowMap map = new ChimeraShadowMap();
        assertTrue(!map.hasMapSnapshot(), "new shadow map must not advertise a snapshot");
        // init() creates image handles and sets this flag. Model that resource
        // generation without creating Vulkan objects in this pure state test.
        map.invalidateMapSnapshot();
        assertTrue(map.needsInitialSamplingLayout(), "new shadow map must require a clear");

        double cameraX = 128.25;
        double cameraY = 72.0;
        double cameraZ = -64.5;
        map.updateLight(new Vector3f(0.3f, 0.8f, -0.4f));
        map.seedClearedMap(cameraX, cameraY, cameraZ);
        assertTrue(map.hasMapSnapshot() && !map.needsInitialSamplingLayout(),
                "cleared all-lit depth must seed the first valid snapshot");
        assertTrue(map.mapGeneration() == 1L, "clear seed must create one map generation");
        Matrix4f seededView = new Matrix4f(map.mapViewAt(cameraX, cameraY, cameraZ));

        map.updateLight(new Vector3f(-0.2f, 0.7f, 0.6f));
        Matrix4f beforeCommit = new Matrix4f(map.mapViewAt(cameraX, cameraY, cameraZ));
        assertMatrixNear(seededView, beforeCommit,
                "current sun change must not relabel the already bound map");
        map.commitRenderedMap(cameraX, cameraY, cameraZ);
        Matrix4f afterCommit = new Matrix4f(map.mapViewAt(cameraX, cameraY, cameraZ));
        assertTrue(!seededView.equals(afterCommit),
                "successful new shadow draw must publish its changed light view");
        assertTrue(map.mapGeneration() == 2L, "successful draw must advance map generation");

        map.invalidateMapSnapshot();
        assertTrue(!map.hasMapSnapshot() && map.needsInitialSamplingLayout(),
                "partial write must invalidate the snapshot and require reinitialization");
        map.seedClearedMap(cameraX, cameraY, cameraZ);
        assertTrue(map.hasMapSnapshot() && map.mapGeneration() == 3L,
                "successful clear must reseed after invalidation");

        map.invalidatePackMapSnapshot();
        assertTrue(!map.hasMapSnapshot() && !map.needsInitialSamplingLayout(),
                "host-produced shadow data must invalidate only the pack matrix association");
    }

    /**
     * The fixed receiver may decode only a map the fixed caster wrote. After
     * a pack-caster commit every receiver point lands outside the map, which
     * chimera_terrain.fsh treats as lit; a host write restores the light MVP.
     */
    private static void verifyFixedReceiverFollowsMapWriter() {
        ChimeraShadowMap map = new ChimeraShadowMap();
        map.updateLight(new Vector3f(0.3f, 0.8f, -0.4f));
        Matrix4f hostMvp = new Matrix4f(map.getHostLightProjection()).mul(map.getLightView());
        assertMatrixNear(hostMvp, map.hostReceiverMatrix(), "host-written map uses the host light MVP");

        map.commitRenderedMap(0.0, 64.0, 0.0);
        for (Vector3f point : new Vector3f[] {new Vector3f(), new Vector3f(40f, -12f, 7f),
                new Vector3f(-300f, 90f, 512f)}) {
            org.joml.Vector4f clip = map.hostReceiverMatrix().transform(
                    new org.joml.Vector4f(point, 1.0f));
            float u = clip.x / clip.w * 0.5f + 0.5f;
            assertTrue(clip.w == 1.0f && u > 1.0f, "pack-written map must read as outside for the fixed receiver");
        }
        map.updateLight(new Vector3f(-0.2f, 0.7f, 0.6f));
        assertTrue(map.hostReceiverMatrix().m30() == 2.0f, "a new sun must not re-expose a pack-written map");

        map.invalidatePackMapSnapshot();
        hostMvp.set(map.getHostLightProjection()).mul(map.getLightView());
        assertMatrixNear(hostMvp, map.hostReceiverMatrix(), "host rewrite restores the host light MVP");
    }

    private static void assertMatrixNear(Matrix4f expected, Matrix4f actual, String message) {
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                assertNear(expected.get(row, column), actual.get(row, column), message);
            }
        }
    }

    private static void assertVectorNear(Vector3f expected, Vector3f actual, String message) {
        assertNear(expected.x, actual.x, message + " x");
        assertNear(expected.y, actual.y, message + " y");
        assertNear(expected.z, actual.z, message + " z");
    }

    private static void assertNear(float expected, float actual, String message) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > 1.0e-4f) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
