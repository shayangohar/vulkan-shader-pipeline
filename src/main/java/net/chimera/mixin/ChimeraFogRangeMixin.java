package net.chimera.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.chimera.render.shader.PackUniformProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Serves each fog range RenderSystem takes to the pack draws that follow, as Iris's fogStart and
 * fogEnd follow these setters: the sky's, the terrain's, a boss's or water's fog, and the
 * Float.MAX_VALUE start FogRenderer.setupNoFog leaves for the hand.
 */
@Mixin(RenderSystem.class)
public abstract class ChimeraFogRangeMixin {
    @Inject(method = "setShaderFogStart", at = @At("RETURN"), require = 1)
    private static void chimera$fogStart(float start, CallbackInfo callback) {
        PackUniformProvider.updateFogRange(start, RenderSystem.getShaderFogEnd());
    }

    @Inject(method = "setShaderFogEnd", at = @At("RETURN"), require = 1)
    private static void chimera$fogEnd(float end, CallbackInfo callback) {
        PackUniformProvider.updateFogRange(RenderSystem.getShaderFogStart(), end);
    }
}
