package net.chimera.mixin;

import net.chimera.render.shader.PipelineSide;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives every GraphicsPipeline the PipelineSide Chimera keeps its own state for it on, in place of
 * maps keyed by the pipeline: the state lives and goes with the pipeline, and a draw reads it
 * without a lookup.
 */
@Mixin(value = GraphicsPipeline.class, remap = false)
public abstract class ChimeraPipelineSideMixin implements PipelineSide.Holder {
    @Unique
    private final PipelineSide chimera$side = new PipelineSide();

    @Override
    public PipelineSide chimera$side() {
        return this.chimera$side;
    }
}
