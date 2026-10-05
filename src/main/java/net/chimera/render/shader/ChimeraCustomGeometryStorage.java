package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.List;
import java.util.Map;

/** Block-entity custom geometry held back from the host batch for the block family. */
public interface ChimeraCustomGeometryStorage {
    record BlockSubmit(PoseStack.Pose pose, SubmitNodeCollector.CustomGeometryRenderer renderer, int entityId) {}

    Map<RenderType, List<BlockSubmit>> chimera$blockSubmits();
}
