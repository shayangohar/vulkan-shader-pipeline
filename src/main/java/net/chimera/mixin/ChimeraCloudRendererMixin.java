package net.chimera.mixin;

import net.chimera.render.shader.ChimeraSkyBridge;
import net.vulkanmod.render.sky.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips VulkanMod's host cloud mesh while the active pack cloud program
 * provably draws nothing. Iris would run that program and output nothing;
 * drawing the host clouds instead overlays vanilla clouds on the pack's own.
 */
@Mixin(value = CloudRenderer.class, remap = false)
public abstract class ChimeraCloudRendererMixin {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$skipAuthoredEmptyClouds(CallbackInfo callback) {
        if (ChimeraSkyBridge.skipHostClouds()) {
            callback.cancel();
        }
    }
}
