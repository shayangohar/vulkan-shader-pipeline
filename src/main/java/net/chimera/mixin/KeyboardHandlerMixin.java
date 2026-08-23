package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * F8 toggles chimera's main pass while no screen is open. Temporary M1
 * affordance; a proper keybind/config arrives with the settings work.
 *
 * Target signature as of MC 1.21.11:
 * private void keyPress(long windowPointer, int action, KeyEvent event)
 * where KeyEvent is a record of (key, scancode, modifiers).
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void chimera$onKeyPress(long windowPointer, int action, KeyEvent event, CallbackInfo ci) {
        if (!ChimeraRenderer.isReady()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            return;
        }

        if (action == GLFW.GLFW_PRESS && event.key() == GLFW.GLFW_KEY_F8) {
            ChimeraRenderer.toggle();
        }
    }
}
