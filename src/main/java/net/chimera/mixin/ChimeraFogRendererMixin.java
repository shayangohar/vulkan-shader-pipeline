package net.chimera.mixin;

import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records each fog FogRenderer sets up: its density as setup begins, which Iris's fogMode and
 * fogDensity read (MixinFogRenderer), and the shape it leaves, which fogShape reads.
 */
@Mixin(FogRenderer.class)
public abstract class ChimeraFogRendererMixin {
    @Inject(method = "setupFog", at = @At("HEAD"))
    private static void chimera$captureFogDensity(Camera camera, FogRenderer.FogMode mode, float farPlaneDistance,
                                                  boolean thickFog, float partialTick, CallbackInfo callback) {
        PackUniformProvider.captureFogDensity(camera);
    }

    @Inject(method = "setupFog", at = @At("RETURN"))
    private static void chimera$captureFogShape(Camera camera, FogRenderer.FogMode mode, float farPlaneDistance,
                                                boolean thickFog, float partialTick, CallbackInfo callback) {
        PackUniformProvider.captureFogShape();
    }
}
