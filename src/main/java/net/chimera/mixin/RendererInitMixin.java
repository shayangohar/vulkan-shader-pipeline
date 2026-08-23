package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
}
