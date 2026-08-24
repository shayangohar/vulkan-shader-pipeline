package net.chimera.mixin;

import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets level rendering with chimera's segments:
 * - HEAD: open the HDR segment (world target + clear).
 * - TAIL: close it and run the composite segment (HDR -> final buffer).
 *
 * Everything vanilla does after level rendering (post chains, hand depth
 * clear, hand, GUI) then runs against the final buffer with no chimera pass
 * open, matching the state those systems expect; re-entry happens through
 * MainPass.rebindMainTarget.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraLevelRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void chimera$openHdrSegment(CallbackInfo ci) {
        if (ChimeraRenderer.isInstalled()) {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass != null) {
                pass.openLevelSegment();
            }
        }
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void chimera$closeAndComposite(CallbackInfo ci) {
        if (ChimeraRenderer.isInstalled()) {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass != null) {
                pass.closeLevelSegmentAndComposite();
            }
        }
    }
}
