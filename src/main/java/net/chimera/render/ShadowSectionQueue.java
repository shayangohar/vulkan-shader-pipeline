package net.chimera.render;

import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.util.AreaSetQueue;
import net.vulkanmod.render.chunk.util.StaticQueue;
import org.joml.FrustumIntersection;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

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
 * <p>Iris's shadow culling ({@link ShadowCullingFrustum}) drops the sections
 * that cannot shadow anything the camera sees; its safe zone is kept whole,
 * as Iris keeps it for voxelizing vertex stages. Only the host's own shadow
 * is also bounded by the light volume.</p>
 */
public final class ShadowSectionQueue {
    private static final int SECTIONS_PER_AREA = 512;
    private static final ShadowSectionQueue INSTANCE = new ShadowSectionQueue();

    private final FrustumIntersection frustum = new FrustumIntersection();
    private AreaSetQueue areas;
    private StaticQueue<RenderSection>[] sections;
    /** Kept sections holding block entities, which the caster pass draws. */
    private final List<RenderSection> blockEntitySections = new ArrayList<>();
    private boolean active;

    private ShadowSectionQueue() {}

    public static boolean active() {
        return INSTANCE.active;
    }

    /**
     * The kept sections that hold block entities, as Iris extracts block entities from its
     * shadow-culled section list. Valid while the queue is active.
     */
    public static List<RenderSection> blockEntitySections() {
        return INSTANCE.active ? INSTANCE.blockEntitySections : List.of();
    }

    public static AreaSetQueue areas() {
        return INSTANCE.areas;
    }

    public static StaticQueue<RenderSection> sections(ChunkArea area) {
        return INSTANCE.sections[area.index];
    }

    /**
     * Fills the queues with every non-empty section the culling frustum keeps, and activates them.
     * {@code lightVolume}, the light's projection times its view, also bounds the host's own
     * shadow; a pack's shadow pass passes null. Iris tests no projection volume
     * (ShadowRenderer.createShadowFrustum), and most packs scale shadow clip depth (BSL by 0.2), so
     * casters past the ortho's near and far planes still land in their map. The matrices are
     * camera-relative, like terrain vertices. Returns false, leaving the camera queues in use, when
     * no section grid exists yet.
     */
    static boolean begin(SectionGrid grid, int areaCount, Matrix4fc lightVolume,
                         ShadowCullingFrustum culling, double cameraX, double cameraY, double cameraZ) {
        return INSTANCE.fill(grid, areaCount, lightVolume, culling, cameraX, cameraY, cameraZ);
    }

    static void end() {
        INSTANCE.active = false;
        INSTANCE.blockEntitySections.clear();
    }

    private boolean fill(SectionGrid grid, int areaCount, Matrix4fc lightVolume,
                         ShadowCullingFrustum culling, double cameraX, double cameraY, double cameraZ) {
        this.active = false;
        this.blockEntitySections.clear();
        if (grid == null || grid.sections == null || areaCount <= 0) return false;
        resetQueues(areaCount);

        FrustumIntersection volume = lightVolume == null ? null : this.frustum.set(lightVolume);
        for (RenderSection section : grid.sections) {
            // A chest alone bakes no geometry: its section is empty but still casts.
            if (section == null || (section.isCompletelyEmpty() && !section.containsBlockEntities())) continue;
            ChunkArea area = section.getChunkArea();
            if (area == null || area.index < 0 || area.index >= areaCount) continue;
            if (!kept(volume, culling, (float) (section.xOffset - cameraX), (float) (section.yOffset - cameraY),
                    (float) (section.zOffset - cameraZ))) continue;
            if (section.containsBlockEntities()) this.blockEntitySections.add(section);
            if (section.isCompletelyEmpty()) continue;
            enqueue(area, section);
        }
        this.active = true;
        return true;
    }

    /** Empties the per-area queues, or allocates them anew when the area count changed. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void resetQueues(int areaCount) {
        if (this.areas == null || this.areas.size() != areaCount) {
            this.areas = new AreaSetQueue(areaCount);
            this.sections = new StaticQueue[areaCount];
        } else {
            for (var iterator = this.areas.iterator(); iterator.hasNext(); ) {
                this.sections[iterator.next().index].clear();
            }
            this.areas.clear();
        }
    }

    /**
     * True when the section at camera-relative ({@code x}, {@code y}, {@code z}) lies in Iris's
     * safe zone, or the culling frustum keeps it and, when one is given, the light volume holds it.
     */
    static boolean kept(FrustumIntersection lightVolume, ShadowCullingFrustum culling, float x, float y, float z) {
        // Iris tests sections with the reach of their block models.
        float m = ShadowCullingFrustum.SECTION_MARGIN;
        float minX = x - m, minY = y - m, minZ = z - m;
        float maxX = x + 16.0f + m, maxY = y + 16.0f + m, maxZ = z + 16.0f + m;
        return culling.forced(minX, minY, minZ, maxX, maxY, maxZ)
                || ((lightVolume == null || lightVolume.testAab(x, y, z, x + 16.0f, y + 16.0f, z + 16.0f))
                && culling.visible(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /** Adds a section to its area's queue, creating the queue on the area's first section. */
    private void enqueue(ChunkArea area, RenderSection section) {
        StaticQueue<RenderSection> queue = this.sections[area.index];
        if (queue == null) {
            queue = new StaticQueue<>(SECTIONS_PER_AREA);
            this.sections[area.index] = queue;
        }
        this.areas.add(area);
        queue.add(section);
    }
}
