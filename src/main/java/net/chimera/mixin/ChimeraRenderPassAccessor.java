package net.chimera.mixin;

import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Points a render pass at a recreated framebuffer of the same formats. VulkanMod's RenderPass
 * transitions the images of the framebuffer it holds, and its pipelines cache their variants by
 * RenderPass, so a pass that follows its framebuffer keeps every compiled variant.
 */
@Mixin(value = RenderPass.class, remap = false)
public interface ChimeraRenderPassAccessor {
    @Accessor("framebuffer")
    void chimera$setFramebuffer(Framebuffer framebuffer);
}
