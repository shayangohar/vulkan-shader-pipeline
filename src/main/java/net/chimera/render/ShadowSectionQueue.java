package net.chimera.render;

import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.util.AreaSetQueue;
import net.vulkanmod.render.chunk.util.StaticQueue;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * The chunk sections the shadow pass draws, chosen from the light's view.
 *
 * <p>VulkanMod's own queues hold what the camera sees: frustum and
 * occlusion culled from the camera, with each section's faces masked to the
 * ones facing the camera. Replayed from the light, they dropped every caster
 * that was off screen, behind terrain, or turned away from the player, so
 * shadows vanished or changed as the player moved. While this queue is
 * active, {@code renderSectionLayer} reads it instead and
 * {@code DrawBuffers} draws every facing, as Iris's shadow pass does.</p>
 */
public final class ShadowSectionQueue {
    private static final int SECTIONS_PER_AREA = 512;
    private static final ShadowSectionQueue INSTANCE = new ShadowSectionQueue();

    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f lightMatrix = new Matrix4f();
    private AreaSetQueue areas;
    private StaticQueue<RenderSection>[] sections;
    private boolean active;

    private ShadowSectionQueue() {}

    public static boolean active() {
        return INSTANCE.active;
    }

    public static AreaSetQueue areas() {
        return INSTANCE.areas;
    }

    public static StaticQueue<RenderSection> sections(ChunkArea area) {
        return INSTANCE.sections[area.index];
    }

    /**
     * Fills the queues with every non-empty section inside the light volume
     * and activates them. The matrices are camera-relative, like terrain
     * vertices. Returns false, leaving the camera queues in use, when no
     * section grid exists yet.
     */
    static boolean begin(SectionGrid grid, int areaCount, Matrix4f lightProjection, Matrix4f lightView,
                         double cameraX, double cameraY, double cameraZ) {
        return INSTANCE.fill(grid, areaCount, lightProjection, lightView, cameraX, cameraY, cameraZ);
    }

    static void end() {
        INSTANCE.active = false;
    }

    @SuppressWarnings("unchecked")
    private boolean fill(SectionGrid grid, int areaCount, Matrix4f lightProjection, Matrix4f lightView,
                         double cameraX, double cameraY, double cameraZ) {
        this.active = false;
        if (grid == null || grid.sections == null || areaCount <= 0) return false;
        if (this.areas == null || this.areas.size() != areaCount) {
            this.areas = new AreaSetQueue(areaCount);
            this.sections = new StaticQueue[areaCount];
        } else {
            for (var iterator = this.areas.iterator(); iterator.hasNext(); ) {
                this.sections[iterator.next().index].clear();
            }
            this.areas.clear();
        }

        this.frustum.set(this.lightMatrix.set(lightProjection).mul(lightView));
        for (RenderSection section : grid.sections) {
            if (section == null || section.isCompletelyEmpty()) continue;
            ChunkArea area = section.getChunkArea();
            if (area == null || area.index < 0 || area.index >= areaCount) continue;
            float x = (float) (section.xOffset - cameraX);
            float y = (float) (section.yOffset - cameraY);
            float z = (float) (section.zOffset - cameraZ);
            if (!this.frustum.testAab(x, y, z, x + 16.0f, y + 16.0f, z + 16.0f)) continue;
            StaticQueue<RenderSection> queue = this.sections[area.index];
            if (queue == null) {
                queue = new StaticQueue<>(SECTIONS_PER_AREA);
                this.sections[area.index] = queue;
            }
            this.areas.add(area);
            queue.add(section);
        }
        this.active = true;
        return true;
    }
}
