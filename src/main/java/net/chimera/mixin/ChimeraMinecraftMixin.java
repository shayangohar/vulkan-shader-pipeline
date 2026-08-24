package net.chimera.mixin;

import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Screen transitions flip chimera between its segmented frame and a simple
 * DefaultMainPass-style presentation (single final-buffer target), mirroring
 * the proven Beryl pattern: shader-pipeline resources are released via a
 * frame op when a screen opens, and the simple path renders menus, the pause
 * blur chain, and the world-behind-pause until the screen closes.
 */
@Mixin(Minecraft.class)
public abstract class ChimeraMinecraftMixin {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void chimera$onSetScreen(Screen screen, CallbackInfo ci) {
        if (!ChimeraRenderer.isInstalled()) {
            return;
        }

        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass == null) {
            return;
        }

        if (screen != null && !pass.isSimpleMode()) {
            // Defer resource-affecting switch to the frame boundary.
            MemoryManager.getInstance().addFrameOp(pass::enterSimpleMode);
        } else if (screen == null && pass.isSimpleMode()) {
            pass.exitSimpleMode();
        }
    }
}
