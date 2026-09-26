package net.chimera.mixin;

import net.chimera.render.ChimeraRasterOrientation;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;

/**
 * Keeps GL row order in Chimera's GL-oriented passes. Every VulkanMod
 * viewport, including setViewportState and resetViewport, reaches the
 * five-argument setViewport; its scissor reaches setScissor.
 */
@Mixin(value = Renderer.class, remap = false)
public abstract class ChimeraRasterOrientationMixin {

    @Inject(method = "setViewport(IIIILorg/lwjgl/system/MemoryStack;)V", at = @At("HEAD"),
            cancellable = true, require = 1)
    private static void chimera$glViewport(int x, int y, int width, int height, MemoryStack stack,
                                           CallbackInfo callback) {
        if (!Renderer.isRecording() || !ChimeraRasterOrientation.boundPassGlOriented()) {
            return;
        }
        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(x).y(y).width(width).height(height).minDepth(0.0f).maxDepth(1.0f);
        vkCmdSetViewport(Renderer.getCommandBuffer(), 0, viewport);
        callback.cancel();
    }

    @Inject(method = "setScissor(IIII)V", at = @At("HEAD"), cancellable = true, require = 1)
    private static void chimera$glScissor(int x, int y, int width, int height, CallbackInfo callback) {
        if (!Renderer.isRecording() || !ChimeraRasterOrientation.boundPassGlOriented()) {
            return;
        }
        Framebuffer framebuffer = Renderer.getInstance().getBoundFramebuffer();
        if (framebuffer == null) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            scissor.offset().set(Math.max(0, x), y);
            scissor.extent().set(Math.min(width, framebuffer.getWidth()), height);
            vkCmdSetScissor(Renderer.getCommandBuffer(), 0, scissor);
        }
        callback.cancel();
    }
}
