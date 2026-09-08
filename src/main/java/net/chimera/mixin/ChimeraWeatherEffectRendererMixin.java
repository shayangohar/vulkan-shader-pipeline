package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens the isolated weather family window around the vanilla weather draw. */
@Mixin(WeatherEffectRenderer.class)
public abstract class ChimeraWeatherEffectRendererMixin {
    @Unique
    private boolean chimera$weatherDrawActive;

    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private void chimera$beginWeather(
            MultiBufferSource bufferSource,
            Vec3 cameraPosition,
            WeatherRenderState state,
            CallbackInfo callback
    ) {
        if (ChimeraRenderer.segmentsActive()) {
            chimera$weatherDrawActive = ChimeraEntityBridge.beginWeatherDraw();
        }
    }

    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void chimera$endWeather(
            MultiBufferSource bufferSource,
            Vec3 cameraPosition,
            WeatherRenderState state,
            CallbackInfo callback
    ) {
        if (chimera$weatherDrawActive && ChimeraEntityBridge.isWeatherDrawActive()) {
            ChimeraEntityBridge.endDraw();
        }
        chimera$weatherDrawActive = false;
    }
}
