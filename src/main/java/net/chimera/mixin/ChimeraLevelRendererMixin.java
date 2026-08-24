package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closes chimera's HDR render pass when level rendering finishes.
 *
 * Everything vanilla does AFTER the world (post chains, hand depth clear,
 * GUI) interacts with render targets through the CommandEncoder / GL-compat
 * layers and expects a closed pass there - matching how those flows behave
 * against the host renderer's own bookkeeping. The next draw that targets the
 * main framebuffer re-enters through MainPass.rebindMainTarget(), whose aux
 * pass clears depth so first-person hand rendering starts clean.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraLevelRendererMixin {

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void chimera$closeHdrPass(CallbackInfo ci) {
        if (ChimeraRenderer.isInstalled()) {
            Renderer.getInstance().endRenderPass();
        }
    }
}
