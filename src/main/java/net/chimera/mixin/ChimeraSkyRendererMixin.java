package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The sun and moon share a native pipeline; their semantic phase does not. */
@Mixin(SkyRenderer.class)
public abstract class ChimeraSkyRendererMixin {
    /** Iris rotates the celestial plane after vanilla's initial -90deg Y turn. */
    @Inject(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V",
            ordinal = 0, shift = At.Shift.AFTER), require = 1)
    private void chimera$sunPath(PoseStack pose, float sun, float moon, float stars, MoonPhase phase,
                                float alpha, float brightness, CallbackInfo callback) {
        if (ChimeraRenderer.segmentsActive()) {
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(PackUniformProvider.currentSunPathRotation()));
        }
    }
    @WrapMethod(method = "renderSkyDisc")
    private void chimera$sky(int color, Operation<Void> original) {
        chimera$phase(PackRenderingPhase.SKY, () -> {
            var pass = ChimeraRenderer.getMainPass();
            if (pass != null && ChimeraRenderer.segmentsActive()) pass.drawPackHorizon();
            original.call(color);
        });
    }

    @WrapMethod(method = "renderDarkDisc")
    private void chimera$void(Operation<Void> original) {
        chimera$phase(PackRenderingPhase.VOID, () -> original.call());
    }

    @WrapMethod(method = "renderSun")
    private void chimera$sun(float alpha, PoseStack pose, Operation<Void> original) {
        chimera$phase(PackRenderingPhase.SUN, () -> original.call(alpha, pose));
    }

    @WrapMethod(method = "renderMoon")
    private void chimera$moon(MoonPhase moon, float alpha, PoseStack pose, Operation<Void> original) {
        chimera$phase(PackRenderingPhase.MOON, () -> original.call(moon, alpha, pose));
    }

    @WrapMethod(method = "renderStars")
    private void chimera$stars(float alpha, PoseStack pose, Operation<Void> original) {
        chimera$phase(PackRenderingPhase.STARS, () -> original.call(alpha, pose));
    }

    @WrapMethod(method = "renderSunriseAndSunset")
    private void chimera$sunset(PoseStack pose, float angle, int color, Operation<Void> original) {
        chimera$phase(PackRenderingPhase.SUNSET, () -> original.call(pose, angle, color));
    }

    @WrapMethod(method = "renderEndSky")
    private void chimera$endSky(Operation<Void> original) {
        chimera$phase(PackRenderingPhase.SKY, () -> original.call());
    }

    @WrapMethod(method = "renderEndFlash")
    private void chimera$endFlash(PoseStack pose, float alpha, float angle, float size, Operation<Void> original) {
        chimera$phase(PackRenderingPhase.SKY, () -> original.call(pose, alpha, angle, size));
    }

    @Unique
    private void chimera$phase(PackRenderingPhase phase, Runnable draw) {
        if (!ChimeraRenderer.segmentsActive()) {
            draw.run();
            return;
        }
        var previous = PackUniformProvider.setRenderingPhase(phase);
        try {
            draw.run();
        } finally {
            PackUniformProvider.setRenderingPhase(previous);
        }
    }
}
