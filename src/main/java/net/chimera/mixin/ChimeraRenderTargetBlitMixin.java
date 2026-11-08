package net.chimera.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod Reforged draws every render target but the main one in GL row order (GlFramebuffer
 * opens it with an inverted viewport), then blits it to the screen row for row (DrawUtil.fastBlit,
 * which takes its orientation from the viewport). LevelRenderer.doEntityOutline therefore put a
 * glowing mob's outline mirrored top to bottom, up in the sky. Blitting under the inverted
 * viewport turns the rows back; the viewport then ends at (0, 0, width, height), as vanilla's
 * _blitToScreen leaves it.
 */
@Mixin(RenderTarget.class)
public abstract class ChimeraRenderTargetBlitMixin {
    @Inject(method = "blitToScreen(IIZ)V", at = @At("HEAD"), require = 1)
    private void chimera$blitGlRows(int width, int height, boolean disableBlend, CallbackInfo callback) {
        if (!((Object) this instanceof MainTarget)) Renderer.setInvertedViewport(0, 0, width, height);
    }

    @Inject(method = "blitToScreen(IIZ)V", at = @At("RETURN"), require = 1)
    private void chimera$restoreViewport(int width, int height, boolean disableBlend, CallbackInfo callback) {
        if (!((Object) this instanceof MainTarget)) Renderer.setViewport(0, 0, width, height);
    }
}
