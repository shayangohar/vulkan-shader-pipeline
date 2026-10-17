package net.chimera.mixin;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the framebuffer VulkanMod's renderer is recording into; it has no getter for it. */
@Mixin(value = Renderer.class, remap = false)
public interface ChimeraRendererAccessor {
    @Accessor("boundFramebuffer")
    Framebuffer chimera$boundFramebuffer();
}
