package net.chimera.mixin;

import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.PipelineState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the render pass a pipeline state was keyed with. */
@Mixin(value = PipelineState.class, remap = false)
public interface ChimeraPipelineStateAccessor {
    @Accessor("renderPass")
    RenderPass chimera$renderPass();
}
