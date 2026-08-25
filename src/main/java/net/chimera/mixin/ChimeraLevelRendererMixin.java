package net.chimera.mixin;

import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives chimera's segment points inside LevelRenderer.renderLevel:
 *
 * - HEAD: opens the HDR segment (world renders into RGBA16F).
 * - After cullTerrain (the compileSections call follows it): renders the
   shadow map. cullTerrain is what fills VulkanMod's section draw queues,
   so the shadow phase must run after it to see this frame's section
   data; a fresh SectionGraph (allChanged) is empty until then.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraLevelRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void chimera$openHdrSegment(CallbackInfo ci) {
        if (ChimeraRenderer.segmentsActive()) {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass != null) {
                pass.openLevelSegment();
            }
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;compileSections(Lnet/minecraft/client/Camera;)V"))
    private void chimera$renderShadowSegment(CallbackInfo ci) {
        if (ChimeraRenderer.segmentsActive()) {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass != null) {
                pass.renderShadowSegment();
            }
        }
    }
}
