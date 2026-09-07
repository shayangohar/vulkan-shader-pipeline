package net.chimera.render.shader;

import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.List;
import java.util.Map;

/** Access to the second, entity-only delayed model batch. */
public interface ChimeraEntityStorage {
    Map<RenderType, List<SubmitNodeStorage.ModelSubmit>> chimera$entitySubmits();

    Map<RenderType, List<SubmitNodeStorage.ModelSubmit>> chimera$blockSubmits();

    void chimera$clearEntitySubmits();
}
