package net.chimera.mixin;

import net.chimera.gui.ShaderPackScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the HUD while the shader pack screen is open, as Iris does: the settings draw no blur so
 * the world shows for a live preview, and the hotbar, crosshair and chat would show through.
 */
@Mixin(Gui.class)
public abstract class ChimeraGuiHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void chimera$hideForShaderPackScreen(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
        if (Minecraft.getInstance().screen instanceof ShaderPackScreen) {
            callback.cancel();
        }
    }
}
