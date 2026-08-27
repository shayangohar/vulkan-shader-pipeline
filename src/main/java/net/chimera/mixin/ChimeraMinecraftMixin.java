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
 * GUI shading is always on: chimera stays installed and the world renders
 * shaded behind every screen over a live level, including the pause-menu
 * blur chain. Level-less screens (loading/transition) hand the frame to
 * the host (legacyUnhand) because the teardown/swap churn around them
 * crashed the segmented frame's image transitions (TASK-63 round 4 bisect,
 * KNOW-47).
 *
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

        ChimeraRenderer.setScreenOpen(screen != null);

        if (screen != null) {
            ChimeraRenderer.enterScreenMode();
        } else {
            ChimeraRenderer.exitScreenMode();
        }
    }
}
