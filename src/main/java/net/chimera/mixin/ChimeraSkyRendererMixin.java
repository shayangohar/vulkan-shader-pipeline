package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The sky and clouds under a pack, as Iris 1.21.1 MixinLevelRenderer phases them. renderSky opens
 * the sky window in the CUSTOM_SKY phase, so a sky a mod draws in its place is still the pack's
 * sky; each part of the vanilla sky then sets its phase at the call that starts it. As in Iris,
 * the sky draws even in thick fog (a boss's or a foggy dimension's), so the pack's sky programs
 * always write the sky the composite passes read. The pack
 * horizon draws as the sky disc begins, and the celestial plane turns by the pack's sun path
 * rotation after vanilla's -90 degree Y turn. renderClouds draws in the CLOUDS phase, and not at
 * all when the pack's cloud program draws nothing.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraSkyRendererMixin {
    @WrapMethod(method = "renderSky")
    private void chimera$skyWindow(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                                   boolean foggy, Runnable skyFog, Operation<Void> original) {
        if (!ChimeraRenderer.segmentsActive()) {
            original.call(frustum, projection, partialTick, camera, foggy, skyFog);
            return;
        }
        var previous = PackUniformProvider.setRenderingPhase(PackRenderingPhase.CUSTOM_SKY);
        ChimeraSkyBridge.beginSky();
        try {
            original.call(frustum, projection, partialTick, camera, false, skyFog);
        } finally {
            ChimeraSkyBridge.endSky();
            PackUniformProvider.setRenderingPhase(previous);
        }
    }

    @Inject(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FogRenderer;levelFogColor()V"), require = 1)
    private void chimera$skyDisc(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                                 boolean foggy, Runnable skyFog, CallbackInfo callback) {
        if (!chimera$phase(PackRenderingPhase.SKY)) return;
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass != null) pass.drawPackHorizon(frustum, projection);
    }

    @Inject(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getSunriseColor(FF)[F"), require = 1)
    private void chimera$sunset(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                                boolean foggy, Runnable skyFog, CallbackInfo callback) {
        chimera$phase(PackRenderingPhase.SUNSET);
    }

    @Inject(method = "renderSky", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;"
                    + "SUN_LOCATION:Lnet/minecraft/resources/ResourceLocation;"),
            require = 1)
    private void chimera$sun(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                             boolean foggy, Runnable skyFog, CallbackInfo callback) {
        chimera$phase(PackRenderingPhase.SUN);
    }

    @Inject(method = "renderSky", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;"
                    + "MOON_LOCATION:Lnet/minecraft/resources/ResourceLocation;"),
            require = 1)
    private void chimera$moon(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                              boolean foggy, Runnable skyFog, CallbackInfo callback) {
        chimera$phase(PackRenderingPhase.MOON);
    }

    @Inject(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"), require = 1)
    private void chimera$stars(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                               boolean foggy, Runnable skyFog, CallbackInfo callback) {
        chimera$phase(PackRenderingPhase.STARS);
    }

    @Inject(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"),
            require = 1)
    private void chimera$void(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                              boolean foggy, Runnable skyFog, CallbackInfo callback) {
        chimera$phase(PackRenderingPhase.VOID);
    }

    @Inject(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V", ordinal = 0,
            shift = At.Shift.AFTER),
            slice = @Slice(from = @At(value = "FIELD", target = "Lcom/mojang/math/Axis;YP:Lcom/mojang/math/Axis;")),
            require = 1)
    private void chimera$sunPath(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                                 boolean foggy, Runnable skyFog, CallbackInfo callback, @Local PoseStack pose) {
        if (ChimeraRenderer.segmentsActive()) {
            pose.mulPose(Axis.ZP.rotationDegrees(PackUniformProvider.currentSunPathRotation()));
        }
    }

    @WrapMethod(method = "renderClouds")
    private void chimera$clouds(PoseStack pose, Matrix4f frustum, Matrix4f projection, float partialTick,
                                double cameraX, double cameraY, double cameraZ, Operation<Void> original) {
        if (ChimeraSkyBridge.skipHostClouds()) return;
        if (!ChimeraRenderer.segmentsActive()) {
            original.call(pose, frustum, projection, partialTick, cameraX, cameraY, cameraZ);
            return;
        }
        var previous = PackUniformProvider.setRenderingPhase(PackRenderingPhase.CLOUDS);
        try {
            original.call(pose, frustum, projection, partialTick, cameraX, cameraY, cameraZ);
        } finally {
            PackUniformProvider.setRenderingPhase(previous);
        }
    }

    /** Enters {@code phase} for the rest of the sky window; false when no pack draws the sky. */
    @Unique
    private static boolean chimera$phase(PackRenderingPhase phase) {
        if (!ChimeraRenderer.segmentsActive()) return false;
        PackUniformProvider.setRenderingPhase(phase);
        return true;
    }
}
