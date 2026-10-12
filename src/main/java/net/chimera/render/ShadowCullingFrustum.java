package net.chimera.render;

import net.chimera.shaderpack.ShadowCullingPlan;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * One frame's shadow caster culling, as Iris ShadowRenderer.createShadowFrustum builds it:
 * AdvancedShadowCullingFrustum, SafeZoneCullingFrustum, BoxCullingFrustum or no culling.
 * Boxes are camera-relative.
 *
 * <p>The advanced frustum keeps what can cast a shadow into the camera's view. It keeps the
 * camera frustum's planes that face the light ("back" planes), and adds a plane through each
 * silhouette edge between a back plane and a front one, parallel to the light. Nothing on the far
 * side of these planes, seen from the light, can shadow what the camera sees. This is L. Spiro's
 * directional-light caster culling, as Iris ports it.</p>
 */
public final class ShadowCullingFrustum {
    /**
     * Iris AdvancedShadowCullingFrustum.CHUNK_SECTION_MARGIN: how far a block model reaches out of
     * its section, plus an epsilon.
     */
    public static final float SECTION_MARGIN = 1.0F + 0.125F;
    /** Six frustum planes and at most twelve silhouette edges. */
    private static final int MAX_PLANES = 18;
    /** NeighboringPlaneSet: the planes sharing an edge with each pair (x, y, near/far). */
    private static final int[][] NEIGHBOURS = {{2, 3, 4, 5}, {0, 1, 4, 5}, {0, 1, 2, 3}};

    private final ShadowCullingPlan.Culler culler;
    private final float[][] planes = new float[MAX_PLANES][];
    private int planeCount;

    /**
     * @param cameraViewProjection the camera's gbufferProjection x gbufferModelView, GL clip range
     * @param lightVector          unit world-space direction toward the shadow light
     */
    public ShadowCullingFrustum(ShadowCullingPlan.Culler culler, Matrix4fc cameraViewProjection,
                                Vector3fc lightVector) {
        this.culler = culler;
        ShadowCullingPlan.Mode mode = culler.mode();
        if (mode == ShadowCullingPlan.Mode.ADVANCED || mode == ShadowCullingPlan.Mode.SAFE_ZONE) {
            Vector4f[] base = baseClippingPlanes(cameraViewProjection);
            boolean[] back = addBackPlanes(base, lightVector);
            addEdgePlanes(base, back, lightVector);
        }
    }

    /** No culling beyond the light volume; used when no pack plan exists. */
    public static ShadowCullingFrustum none() {
        return new ShadowCullingFrustum(ShadowCullingPlan.Culler.NONE, new Matrix4f(), new Vector3f(0.0F, 1.0F, 0.0F));
    }

    public ShadowCullingPlan.Culler culler() {
        return culler;
    }

    /** True when a caster in this box may shadow something the camera sees. */
    public boolean visible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return switch (culler.mode()) {
            case NONE -> true;
            case DISTANCE -> !outside(culler.box(), minX, minY, minZ, maxX, maxY, maxZ);
            case ADVANCED -> !outside(culler.box(), minX, minY, minZ, maxX, maxY, maxZ)
                    && insidePlanes(minX, minY, minZ, maxX, maxY, maxZ);
            case SAFE_ZONE -> !outside(culler.box(), minX, minY, minZ, maxX, maxY, maxZ)
                    && (!outside(culler.safeZone(), minX, minY, minZ, maxX, maxY, maxZ)
                    || insidePlanes(minX, minY, minZ, maxX, maxY, maxZ));
        };
    }

    /**
     * True inside the safe zone, which Iris draws whatever the light volume and the view: a
     * voxelizing vertex stage must see every caster there.
     */
    public boolean forced(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return culler.mode() == ShadowCullingPlan.Mode.SAFE_ZONE
                && !outside(culler.box(), minX, minY, minZ, maxX, maxY, maxZ)
                && !outside(culler.safeZone(), minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Iris BoxCuller: outside the camera-centred cube of this half-size; never for infinity. */
    private static boolean outside(double half, float minX, float minY, float minZ,
                                   float maxX, float maxY, float maxZ) {
        return maxX < -half || minX > half || maxY < -half || minY > half || maxZ < -half || minZ > half;
    }

    private boolean insidePlanes(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int i = 0; i < planeCount; i++) {
            float[] plane = planes[i];
            // The corner farthest along the plane normal: outside it, the whole box is.
            float x = plane[0] < 0.0F ? minX : maxX;
            float y = plane[1] < 0.0F ? minY : maxY;
            float z = plane[2] < 0.0F ? minZ : maxZ;
            if (plane[0] * x + plane[1] * y + plane[2] * z < -plane[3]) return false;
        }
        return true;
    }

    /** Iris BaseClippingPlanes: right, left, top, bottom, far and near, normals inward. */
    private static Vector4f[] baseClippingPlanes(Matrix4fc viewProjection) {
        Matrix4f transposed = new Matrix4f(viewProjection).transpose();
        return new Vector4f[] {
                clippingPlane(transposed, -1.0F, 0.0F, 0.0F),
                clippingPlane(transposed, 1.0F, 0.0F, 0.0F),
                clippingPlane(transposed, 0.0F, -1.0F, 0.0F),
                clippingPlane(transposed, 0.0F, 1.0F, 0.0F),
                clippingPlane(transposed, 0.0F, 0.0F, -1.0F),
                clippingPlane(transposed, 0.0F, 0.0F, 1.0F)};
    }

    private static Vector4f clippingPlane(Matrix4fc transposed, float x, float y, float z) {
        return new Vector4f(x, y, z, 1.0F).mul(transposed).normalize();
    }

    /** Planes whose normal faces the light; a caster behind one shadows only what lies farther away. */
    private boolean[] addBackPlanes(Vector4f[] base, Vector3fc light) {
        boolean[] back = new boolean[base.length];
        for (int i = 0; i < base.length; i++) {
            Vector4f plane = base[i];
            float dot = plane.x * light.x() + plane.y * light.y() + plane.z * light.z();
            back[i] = dot > 0.0F;
            if (dot >= 0.0F) addPlane(plane.x, plane.y, plane.z, plane.w);
        }
        return back;
    }

    private void addEdgePlanes(Vector4f[] base, boolean[] back, Vector3fc light) {
        for (int i = 0; i < base.length; i++) {
            if (!back[i]) continue;
            for (int neighbour : NEIGHBOURS[i >>> 1]) {
                if (!back[neighbour]) addEdgePlane(base[i], base[neighbour], light);
            }
        }
    }

    /**
     * The plane through the line where a back plane meets a front plane, parallel to the light.
     * Its normal (the edge direction crossed with the light) is a positive mix of both inward
     * normals, so it faces into the volume as they do.
     */
    private void addEdgePlane(Vector4f backPlane, Vector4f frontPlane, Vector3fc light) {
        Vector3f backNormal = new Vector3f(backPlane.x, backPlane.y, backPlane.z);
        Vector3f frontNormal = new Vector3f(frontPlane.x, frontPlane.y, frontPlane.z);
        Vector3f intersection = new Vector3f(backNormal).cross(frontNormal);
        Vector3f edgeNormal = new Vector3f(intersection).cross(light);
        // A point on both planes: Graphics Gems' intersection of two planes.
        Vector3f point = new Vector3f(intersection).cross(backNormal).mul(-frontPlane.w)
                .add(new Vector3f(frontNormal).cross(intersection).mul(-backPlane.w))
                .div(intersection.lengthSquared());
        addPlane(edgeNormal.x, edgeNormal.y, edgeNormal.z, -edgeNormal.dot(point));
    }

    private void addPlane(float x, float y, float z, float w) {
        planes[planeCount++] = new float[] {x, y, z, w};
    }
}
