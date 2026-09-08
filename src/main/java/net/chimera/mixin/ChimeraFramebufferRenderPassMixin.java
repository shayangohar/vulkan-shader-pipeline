package net.chimera.mixin;

import net.chimera.render.shader.PackGeometryContext;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the second color attachment only during Chimera pack geometry draws. */
@Mixin(value = RenderPass.class, remap = false)
public abstract class ChimeraFramebufferRenderPassMixin {
    @Inject(method = "beginDynamicRendering", at = @At("HEAD"), cancellable = true)
    private void chimera$beginPackGeometry(VkCommandBuffer commandBuffer, MemoryStack stack,
                                            CallbackInfo ci) {
        if (!PackGeometryContext.active()) return;
        PackGeometryContext.beginRendering(commandBuffer, stack);
        ci.cancel();
    }

    @Inject(method = "endRenderPass", at = @At("HEAD"), cancellable = true)
    private void chimera$endPackGeometry(VkCommandBuffer commandBuffer, CallbackInfo ci) {
        if (!PackGeometryContext.active()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PackGeometryContext.endRendering(commandBuffer, stack);
        }
        ci.cancel();
    }
}
