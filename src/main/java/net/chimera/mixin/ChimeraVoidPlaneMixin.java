package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No void plane with the camera in a fluid while a pack renders the world, as Iris 1.21.1's
 * MixinClientLevelData_DisableVoidPlane keeps the fog illusion in oceans and lava: the horizon
 * height is negative infinity, so renderSky never finds the eye below it and skips the dark disc.
 * renderSky draws the sky body only in water or out of any fluid, so water is the case that shows.
 * Iris does this with or without a pack; Chimera without one leaves Minecraft's sky alone.
 */
@Mixin(ClientLevel.ClientLevelData.class)
public abstract class ChimeraVoidPlaneMixin {
    @Inject(method = "getHorizonHeight", at = @At("HEAD"), cancellable = true)
    private void chimera$noVoidPlaneInFluid(LevelHeightAccessor level, CallbackInfoReturnable<Double> callback) {
        if (ChimeraRenderer.packProjectionActive()
                && Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
            callback.setReturnValue(Double.NEGATIVE_INFINITY);
        }
    }
}
