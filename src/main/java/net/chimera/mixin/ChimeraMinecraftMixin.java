package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a vanilla screen is open (pause, inventory, menus), chimera fully
 * unhands the renderer: the captured host MainPass and host terrain take over
 * until the screen closes. This is the exact configuration proven crash-free
 * on ESC (chimera toggled off), applied for the whole lifetime of the screen
 * including its post chains. The switch is immediate - the blur chain runs in
 * the same frame the screen opens, so a deferred switch is too late.
 */
@Mixin(Minecraft.class)
public abstract class ChimeraMinecraftMixin {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void chimera$onSetScreen(Screen screen, CallbackInfo ci) {
        if (!ChimeraRenderer.isReady()) {
            return;
        }

        if (screen != null) {
            ChimeraRenderer.enterScreenMode();
        } else {
            ChimeraRenderer.exitScreenMode();
        }
    }
}
