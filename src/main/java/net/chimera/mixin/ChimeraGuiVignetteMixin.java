package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips Minecraft's HUD vignette while a pack renders the world without {@code vignette=true},
 * as Iris's MixinGui does. The vignette darkens the screen edges by the player's light level,
 * so in dark places it dimmed the pack's finished image a second time.
 */
@Mixin(Gui.class)
public abstract class ChimeraGuiVignetteMixin {
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void chimera$packVignette(GuiGraphics graphics, Entity entity, CallbackInfo callback) {
        var overlays = ChimeraRenderer.packScreenOverlays();
        if (overlays != null && !overlays.vignette()) {
            callback.cancel();
        }
    }
}
