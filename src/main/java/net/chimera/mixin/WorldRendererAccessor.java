package net.chimera.mixin;

import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes WorldRenderer's private section graph (pinned decompile line 86;
 * no public getter exists) for chimera's shadow-pass diagnostic in
 * ChimeraMainPass.renderShadowMap.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public interface WorldRendererAccessor {

    @Accessor("sectionGraph")
    SectionGraph chimera$getSectionGraph();
}
