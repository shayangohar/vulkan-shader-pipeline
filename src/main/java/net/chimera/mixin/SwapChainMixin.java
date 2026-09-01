package net.chimera.mixin;

import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SwapChain.recreate() destroys the swapchain and its image views when the
 * surface extent becomes zero (window minimized) but leaves the
 * swapChainImages list populated, so the later cleanUp() destroys the
 * already-freed views a second time - the vkDestroyImageView access violation
 * seen on game exit. Skip cleanUp entirely when the swapchain was already
 * reaped by that branch (swapChainId == 0, hasImages == false); its images,
 * views, swapchain and depth are all gone already.
 */
@Mixin(SwapChain.class)
public abstract class SwapChainMixin {

    @Shadow
    private long swapChainId;

    @Shadow
    private boolean hasImages;

    @Inject(method = "cleanUp", at = @At("HEAD"), cancellable = true)
    private void chimera$skipReapedSwapChain(CallbackInfo ci) {
        if (this.swapChainId == 0L || !this.hasImages) {
            ci.cancel();
        }
    }
}
