package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes Minecraft.setScreen to drive chimera's screen mode.
 *
 * With ChimeraRenderer.SCREEN_SHADING_PARITY=true (current), this hook is
 * advisory only: chimera stays installed and the world renders shaded
 * behind every screen, including the pause-menu blur chain.
 *
 * With the flag false, the legacy behavior applies: any non-null screen
 * fully unhands the renderer (host MainPass + host terrain until close).
 * That configuration existed because the old phase-flip design crashed
 * inside VulkanMod's post-chain barriers during the pause blur (KNOW-47);
 * it is kept verbatim as the one-flag rollback path.
 * Level teardown during an open screen additionally hands off to the host
 * via WorldRendererLevelMixin until a live level returns.
 */
@Mixin(Minecraft.class)
public abstract class ChimeraMinecraftMixin {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void chimera$onSetScreen(Screen screen, CallbackInfo ci) {
        if (!ChimeraRenderer.isReady()) {
            return;
        }

        if (screen != null) {
            ChimeraRenderer.setScreenOpen(screen != null);
            ChimeraRenderer.enterScreenMode();
        } else {
            ChimeraRenderer.exitScreenMode();
        }
    }
}
