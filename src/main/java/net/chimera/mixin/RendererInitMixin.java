package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod Renderer's lifecycle: Chimera takes over the main pass once the
 * Renderer singleton is fully constructed (swapchain, default main pass, sync
 * objects all exist), replaces pack resources before a frame records, and
 * cleans its own pass at final teardown when the host pass is the installed one.
 */
@Mixin(value = Renderer.class, remap = false)
public abstract class RendererInitMixin {

    @Inject(method = "initRenderer", at = @At("TAIL"))
    private static void chimera$onRendererInit(CallbackInfo ci) {
        ChimeraRenderer.onHostRendererReady();
    }

    /**
     * cleanUpResources cleans only the installed main pass. This runs first, while the device
     * and the host pass are alive, so Chimera's pass is cleaned before the host's.
     */
    @Inject(method = "cleanUpResources", at = @At("HEAD"))
    private void chimera$beforeCleanUpResources(CallbackInfo ci) {
        ChimeraRenderer.beforeHostCleanup();
    }

    /**
     * Renderer.beginFrame has already waited for the current frame-slot fence
     * here, but command recording has not started. This is the one safe seam for
     * replacing pack-owned Vulkan resources during a dimension transition.
     */
    @Inject(method = "beginRenderPass(Lorg/lwjgl/system/MemoryStack;)V", at = @At("HEAD"))
    private void chimera$beforeMainCommandBuffer(MemoryStack stack, CallbackInfo ci) {
        ChimeraRenderer.beforeMainCommandBuffer();
    }
}
