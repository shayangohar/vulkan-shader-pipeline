package net.chimera.mixin;

import net.chimera.render.ChimeraRasterOrientation;
import net.chimera.render.shader.PackGeometryContext;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a pack geometry window is pending (PackGeometryContext), the render pass VulkanMod opens
 * on a framebuffer opens the window instead: dynamic rendering into the pack's attachments, in
 * the row order of the pass it replaces. The window's rendering pass ends it where VulkanMod ends
 * its render pass (ChimeraRenderingPass.endRenderPass).
 */
@Mixin(value = Framebuffer.class, remap = false)
public abstract class ChimeraFramebufferRenderPassMixin {
    @Shadow
    public abstract int getWidth();

    @Shadow
    public abstract int getHeight();

    @Inject(method = "beginRenderPass", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$beginPackGeometry(VkCommandBuffer commandBuffer, RenderPass renderPass, MemoryStack stack,
                                            CallbackInfo callback) {
        if (!PackGeometryContext.active()) return;
        Renderer renderer = Renderer.getInstance();
        renderer.setBoundRenderPass(PackGeometryContext.beginRendering(commandBuffer, stack,
                ChimeraRasterOrientation.isGlOriented(renderPass)));
        renderer.setBoundFramebuffer((Framebuffer) (Object) this);
        Renderer.setViewport(0, 0, getWidth(), getHeight());
        Renderer.setScissor(0, 0, getWidth(), getHeight());
        callback.cancel();
    }
}
