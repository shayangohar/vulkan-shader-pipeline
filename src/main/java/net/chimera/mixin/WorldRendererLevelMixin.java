package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Continuous enforcement for GUI shading parity: setScreen only fires when a
 * screen opens/closes, but level transitions happen MID-screen (respawn,
 * nether portal - the screen opens while the old level is still live, then
 * it goes null and a new one arrives). WorldRenderer.setLevel is the exact
 * teardown/swap point, so parity hands off to the host here and reinstalls
 * the moment a live level exists again.
 *
 * Crash class this prevents (hs_err_pid78880/92932): keeping the segmented
 * frame installed across level churn crashed inside vkCmdPipelineBarrier
 * from VulkanImage.transitionImageLayout during renderLevel.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererLevelMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private static final boolean TRACE = Boolean.getBoolean("chimera.traceTransitions");

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void chimera$onSetLevel(ClientLevel level, CallbackInfo ci) {
        if (!ChimeraRenderer.isReady()) {
            return;
        }

        if (TRACE) {
            LOGGER.info("[chimera] setLevel {}", level == null ? "null" : "live");
        }

        if (level == null) {
            ChimeraRenderer.onLevelUnloaded();
        } else {
            ChimeraRenderer.onLevelLoaded();
        }
    }
}
