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
 *
 * <p>Within the light volume, Iris's shadow culling ({@link ShadowCullingFrustum})
 * then drops the sections that cannot shadow anything the camera sees. Its
 * safe zone is kept whole, light volume or not, as Iris keeps it for
 * voxelizing vertex stages.</p>
 */
public final class ShadowSectionQueue {
    private static final int SECTIONS_PER_AREA = 512;
    private static final ShadowSectionQueue INSTANCE = new ShadowSectionQueue();

    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f lightMatrix = new Matrix4f();
    private AreaSetQueue areas;
    private StaticQueue<RenderSection>[] sections;
    /** Kept sections holding block entities, which the caster pass draws. */
    private final java.util.List<RenderSection> blockEntitySections = new java.util.ArrayList<>();
    private boolean active;

    private ShadowSectionQueue() {}

    public static boolean active() {
        return INSTANCE.active;
    }

    /**
     * The kept sections that hold block entities, as Iris extracts block entities from its
     * shadow-culled section list. Valid while the queue is active.
     */
    public static java.util.List<RenderSection> blockEntitySections() {
        return INSTANCE.active ? INSTANCE.blockEntitySections : java.util.List.of();
    }

    public static AreaSetQueue areas() {
        return INSTANCE.areas;
    }

    public static StaticQueue<RenderSection> sections(ChunkArea area) {
        return INSTANCE.sections[area.index];
    }

    /**
     * Fills the queues with every non-empty section inside the light volume
     * that the culling frustum keeps, and activates them. The matrices are
     * camera-relative, like terrain vertices. Returns false, leaving the
     * camera queues in use, when no section grid exists yet.
     */
    static boolean begin(SectionGrid grid, int areaCount, Matrix4f lightProjection, Matrix4f lightView,
                         ShadowCullingFrustum culling, double cameraX, double cameraY, double cameraZ) {
        return INSTANCE.fill(grid, areaCount, lightProjection, lightView, culling, cameraX, cameraY, cameraZ);
    }

    static void end() {
        INSTANCE.active = false;
        INSTANCE.blockEntitySections.clear();
    }

    @SuppressWarnings("unchecked")
    private boolean fill(SectionGrid grid, int areaCount, Matrix4f lightProjection, Matrix4f lightView,
                         ShadowCullingFrustum culling, double cameraX, double cameraY, double cameraZ) {
        this.active = false;
        this.blockEntitySections.clear();
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
            // A chest alone bakes no geometry: its section is empty but still casts.
            if (section == null || (section.isCompletelyEmpty() && !section.containsBlockEntities())) continue;
            ChunkArea area = section.getChunkArea();
            if (area == null || area.index < 0 || area.index >= areaCount) continue;
            float x = (float) (section.xOffset - cameraX);
            float y = (float) (section.yOffset - cameraY);
            float z = (float) (section.zOffset - cameraZ);
            // Iris tests sections with the reach of their block models.
            float m = ShadowCullingFrustum.SECTION_MARGIN;
            float minX = x - m, minY = y - m, minZ = z - m;
            float maxX = x + 16.0f + m, maxY = y + 16.0f + m, maxZ = z + 16.0f + m;
            if (!culling.forced(minX, minY, minZ, maxX, maxY, maxZ)
                    && (!this.frustum.testAab(x, y, z, x + 16.0f, y + 16.0f, z + 16.0f)
                    || !culling.visible(minX, minY, minZ, maxX, maxY, maxZ))) {
                continue;
            }
            if (section.containsBlockEntities()) this.blockEntitySections.add(section);
            if (section.isCompletelyEmpty()) continue;
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
