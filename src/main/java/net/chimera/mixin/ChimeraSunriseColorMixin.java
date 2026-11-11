package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No sunrise or sunset colour with the camera in a fluid or under blindness or darkness while a
 * pack renders the world, as Iris 1.21.1's MixinDimensionSpecialEffects drops it in high fog. Both
 * readers lose it: renderSky's sunrise fan (which draws only in water, the sky being skipped in the
 * other cases) and FogRenderer.setupColor's tint of the fog colour toward the sun, which the
 * pack's fogColor reads (under blindness or darkness; a fluid takes its own fog colour there).
 * The overworld's effects inherit this method; the End's return no colour of their own. Iris does
 * this with or without a pack; Chimera without one leaves Minecraft's sky alone.
 */
@Mixin(DimensionSpecialEffects.class)
public abstract class ChimeraSunriseColorMixin {
    @Inject(method = "getSunriseColor", at = @At("HEAD"), cancellable = true)
    private void chimera$noSunriseInHighFog(float timeOfDay, float partialTick,
                                            CallbackInfoReturnable<float[]> callback) {
        if (!ChimeraRenderer.packProjectionActive()) return;
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() != FogType.NONE
                || ((ChimeraLevelRendererAccessor) minecraft.levelRenderer).chimera$doesMobEffectBlockSky(camera)) {
            callback.setReturnValue(null);
        }
    }
}
