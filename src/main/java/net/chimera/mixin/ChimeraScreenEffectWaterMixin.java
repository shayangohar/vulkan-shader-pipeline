package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips Minecraft's underwater texture overlay while a pack renders the world without
 * {@code underwaterOverlay=true}, as Iris's MixinScreenEffectRenderer does; packs draw their
 * own underwater look.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ChimeraScreenEffectWaterMixin {
    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void chimera$packUnderwaterOverlay(Minecraft minecraft, PoseStack pose, CallbackInfo callback) {
        var overlays = ChimeraRenderer.packScreenOverlays();
        if (overlays != null && !overlays.underwaterOverlay()) {
            callback.cancel();
        }
    }
}
