package net.chimera.mixin;

import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SwapChain.recreate() destroys the swapchain, its image views and its depth image when the
 * surface extent becomes zero (window minimized), but leaves the image list populated, so the
 * later cleanUp() destroys the freed views a second time and frees the missing depth image: the
 * access violation on game exit after a minimize. Skip cleanUp when that branch already reaped
 * the swapchain (swapChainId == 0).
 */
@Mixin(value = SwapChain.class, remap = false)
public abstract class SwapChainMixin {

    @Shadow
    private long swapChainId;

    @Inject(method = "cleanUp", at = @At("HEAD"), cancellable = true)
    private void chimera$skipReapedSwapChain(CallbackInfo ci) {
        if (this.swapChainId == 0L) {
            ci.cancel();
        }
    }
}
