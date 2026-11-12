package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Records each fog FogRenderer sets up: its density as setup begins, which Iris's fogMode and
 * fogDensity read (MixinFogRenderer), and the shape it leaves, which fogShape reads.
 */
@Mixin(FogRenderer.class)
public abstract class ChimeraFogRendererMixin {
    @WrapMethod(method = "setupFog")
    private static void chimera$captureFog(Camera camera, FogRenderer.FogMode mode, float farPlaneDistance,
                                           boolean thickFog, float partialTick, Operation<Void> original) {
        PackUniformProvider.captureFogDensity(camera);
        original.call(camera, mode, farPlaneDistance, thickFog, partialTick);
        PackUniformProvider.captureFogShape();
    }
}
