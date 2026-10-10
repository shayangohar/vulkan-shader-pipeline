package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.List;
import java.util.Map;

/** Custom geometry held back from the host batch so a pack family or lane can draw it. */
public interface ChimeraCustomGeometryStorage {
    /** One held-back submission with the identity its submitter had. */
    record HeldSubmit(PoseStack.Pose pose, SubmitNodeCollector.CustomGeometryRenderer renderer,
                      int entityId, int blockEntityId) {}

    /** Entity-format geometry submitted by block entities (the end portal and gateway). */
    Map<RenderType, List<HeldSubmit>> chimera$blockSubmits();

    /** Entity-format geometry submitted by entities (paintings, experience orbs, guardian beams). */
    Map<RenderType, List<HeldSubmit>> chimera$entitySubmits();

    /**
     * Geometry a host-layout lane draws (lightning, dragon rays, beacon beams, fishing
     * lines), held so each batch draws with its submitter's entity id.
     */
    Map<RenderType, List<HeldSubmit>> chimera$laneSubmits();
}
