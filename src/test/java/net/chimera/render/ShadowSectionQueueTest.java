package net.chimera.render;

import net.chimera.shaderpack.ShadowCullingPlan;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which chunk sections the shadow pass draws. */
final class ShadowSectionQueueTest {
    /**
     * Iris tests no projection volume when it culls shadow casters (ShadowRenderer
     * .createShadowFrustum), and BSL's shadow vertex stage scales clip depth by 0.2, so a section far
     * past the ortho's far plane (156 blocks) or near plane (-100.05) still lands in its map. With
     * the sun overhead, a village 180 blocks below and a ridge 120 blocks above must both cast. Only
     * the host's own shadow keeps the light-volume bound.
     */
    @Test
    void aPackShadowKeepsCastersPastTheOrthoDepthPlanes() {
        Matrix4f projection = new Matrix4f().setOrtho(-160.0F, 160.0F, -160.0F, 160.0F, -100.05F, 156.0F);
        Matrix4f overhead = new Matrix4f().lookAt(0.0F, 0.0F, 0.0F, 0.0F, -1.0F, 0.0F, 0.0F, 0.0F, -1.0F);
        FrustumIntersection hostVolume = new FrustumIntersection(new Matrix4f(projection).mul(overhead));
        ShadowCullingFrustum box = new ShadowCullingFrustum(
                new ShadowCullingPlan.Culler(ShadowCullingPlan.Mode.DISTANCE, 256.0, 0.0),
                new Matrix4f(), new Vector3f(0.0F, 1.0F, 0.0F));

        assertTrue(ShadowSectionQueue.kept(null, box, 0.0F, -180.0F, 0.0F), "a section past the far plane casts");
        assertTrue(ShadowSectionQueue.kept(null, box, 0.0F, 120.0F, 0.0F), "a section past the near plane casts");
        assertFalse(ShadowSectionQueue.kept(null, box, 0.0F, -300.0F, 0.0F), "the BoxCuller still bounds it");

        assertFalse(ShadowSectionQueue.kept(hostVolume, box, 0.0F, -180.0F, 0.0F), "the host shadow stops at far");
        assertFalse(ShadowSectionQueue.kept(hostVolume, box, 0.0F, 120.0F, 0.0F), "and at near");
        assertTrue(ShadowSectionQueue.kept(hostVolume, box, 0.0F, -40.0F, 0.0F), "a section inside casts in both");
    }

    /**
     * VulkanMod's StaticQueue.clear keeps its slots, so the queues held the last world's sections
     * (and through them its level) after the player left it. A level change releases them.
     */
    @Test
    void aLevelChangeReleasesTheQueues() {
        ShadowSectionQueue.release();
        assertTrue(ShadowSectionQueue.areas() == null, "the area queues are dropped");
        assertFalse(ShadowSectionQueue.active(), "and the queue is inactive");
        assertTrue(ShadowSectionQueue.blockEntitySections().isEmpty());
    }
}
