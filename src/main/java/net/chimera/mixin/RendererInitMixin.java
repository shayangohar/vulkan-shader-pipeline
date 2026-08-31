package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.system.MemoryStack;

/**
 * Fires once VulkanMod's Renderer singleton is fully constructed (swapchain,
 * default main pass, sync objects all exist), which is the earliest safe
 * moment for chimera to take over the main pass.
 */
@Mixin(value = Renderer.class, remap = false)
public abstract class RendererInitMixin {

    @Inject(method = "initRenderer", at = @At("TAIL"))
    private static void chimera$onRendererInit(CallbackInfo ci) {
        ChimeraRenderer.onHostRendererReady();
    }

    /**
     * Renderer.beginFrame has already waited for the current frame-slot fence
     * here, but command recording has not started. This is the one safe seam for
     * replacing pack-owned Vulkan resources during a dimension transition.
     */
    @Inject(method = "beginMainRenderPass", at = @At("HEAD"))
    private void chimera$beforeMainCommandBuffer(MemoryStack stack, CallbackInfo ci) {
        ChimeraRenderer.beforeMainCommandBuffer();
    }
}
