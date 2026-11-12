package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.math.Axis;
import net.chimera.render.ChimeraCloudRenderer;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.PackRenderingPhase;
import net.chimera.shaderpack.SkyDirectives;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.LevelHeightAccessor;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/**
 * The sky and clouds under a pack, as Iris 1.21.1 MixinLevelRenderer phases them. renderSky opens
 * the sky window in the CUSTOM_SKY phase, so a sky a mod draws in its place is still the pack's
 * sky; each part of the vanilla sky then sets its phase at the call that starts it. As in Iris,
 * the sky draws even in thick fog (a boss's or a foggy dimension's), so the pack's sky programs
 * always write the sky the composite passes read. The pack horizon draws before all of it, after
 * the world clear (ChimeraLevelRendererMixin), as Iris draws it ahead of the sky. The celestial
 * plane turns by the pack's sun path rotation after vanilla's -90 degree Y turn. The pack's sun,
 * moon, stars and sky switches drop those parts as Iris's MixinLevelRenderer_SunMoonToggle does,
 * and in high fog the void plane and the sunrise colour go as Iris's sky mixins drop them
 * (ChimeraVoidPlaneMixin, ChimeraSunriseColorMixin). renderClouds draws in the CLOUDS phase, and
 * not at all when the pack's cloud program draws nothing; the pack's cloud program draws over
 * Sodium's cloud mesh (ChimeraCloudRenderer), which Iris on 1.21.1 runs it on, unless the
 * dimension draws its own clouds.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraSkyRendererMixin {
    private static final String UPLOAD =
            "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V";
    private static final String SUN =
            "Lnet/minecraft/client/renderer/LevelRenderer;SUN_LOCATION:Lnet/minecraft/resources/ResourceLocation;";
    private static final String MOON =
            "Lnet/minecraft/client/renderer/LevelRenderer;MOON_LOCATION:Lnet/minecraft/resources/ResourceLocation;";
    private static final String STARS = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F";
    private static final String FOG_COLOR = "Lnet/minecraft/client/renderer/FogRenderer;levelFogColor()V";
    private static final String SUNRISE =
            "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getSunriseColor(FF)[F";

    @Shadow
    private ClientLevel level;
    @Shadow
    private int ticks;

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

    @Inject(method = "renderSky", at = @At(value = "INVOKE", target = FOG_COLOR), require = 1)
    private void chimera$skyDisc(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                                 boolean foggy, Runnable skyFog, CallbackInfo callback) {
        chimera$phase(PackRenderingPhase.SKY);
    }

    @Inject(method = "renderSky", at = @At(value = "FIELD", target = SUN), require = 1)
    private void chimera$sun(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                             boolean foggy, Runnable skyFog, CallbackInfo callback) {
        chimera$phase(PackRenderingPhase.SUN);
    }

    @Inject(method = "renderSky", at = @At(value = "FIELD", target = MOON), require = 1)
    private void chimera$moon(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                              boolean foggy, Runnable skyFog, CallbackInfo callback) {
        chimera$phase(PackRenderingPhase.MOON);
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

    /** The sun quad, dropped when the pack turns the sun off (Iris iris$beforeDrawSun). */
    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = UPLOAD),
            slice = @Slice(from = @At(value = "FIELD", target = SUN), to = @At(value = "FIELD", target = MOON)),
            allow = 1, require = 1)
    private void chimera$sunQuad(MeshData mesh, Operation<Void> original) {
        chimera$drawOrDrop(mesh, original, chimera$keeps(SkyDirectives::sun));
    }

    /** The moon quad, dropped when the pack turns the moon off (Iris iris$beforeDrawMoon). */
    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = UPLOAD),
            slice = @Slice(from = @At(value = "FIELD", target = MOON), to = @At(value = "INVOKE", target = STARS)),
            allow = 1, require = 1)
    private void chimera$moonQuad(MeshData mesh, Operation<Void> original) {
        chimera$drawOrDrop(mesh, original, chimera$keeps(SkyDirectives::moon));
    }

    /** The sky disc, dropped with sky=false (Iris iris$beforeDrawSkyDisc). */
    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;"
            + "drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V"),
            slice = @Slice(from = @At(value = "INVOKE", target = FOG_COLOR), to = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;unbind()V", ordinal = 0)),
            allow = 1, require = 1)
    private void chimera$skyDiscDraw(VertexBuffer buffer, Matrix4f modelView, Matrix4f projection,
                                     ShaderInstance shader, Operation<Void> original) {
        if (chimera$keeps(SkyDirectives::sky)) original.call(buffer, modelView, projection, shader);
    }

    /** The sunset phase begins here; no sunrise fan with sky=false: vanilla skips it for a null colour. */
    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = SUNRISE), require = 1)
    private float[] chimera$sunriseColor(DimensionSpecialEffects effects, float timeOfDay, float partialTick,
                                         Operation<float[]> original) {
        chimera$phase(PackRenderingPhase.SUNSET);
        return chimera$keeps(SkyDirectives::sky) ? original.call(effects, timeOfDay, partialTick) : null;
    }

    /** No dark disc with sky=false: the eye is never below a horizon at negative infinity. */
    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/"
            + "ClientLevel$ClientLevelData;getHorizonHeight(Lnet/minecraft/world/level/LevelHeightAccessor;)D"),
            require = 1)
    private double chimera$horizonHeight(ClientLevel.ClientLevelData data, LevelHeightAccessor level,
                                         Operation<Double> original) {
        return chimera$keeps(SkyDirectives::sky) ? original.call(data, level) : Double.NEGATIVE_INFINITY;
    }

    /** The stars phase begins here; no stars with stars=false: vanilla draws them only for a brightness above zero. */
    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = STARS), require = 1)
    private float chimera$starBrightness(ClientLevel level, float partialTick, Operation<Float> original) {
        chimera$phase(PackRenderingPhase.STARS);
        return chimera$keeps(SkyDirectives::stars) ? original.call(level, partialTick) : -0.1F;
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
            if (!ChimeraSkyBridge.drawsPackClouds() || this.level == null) {
                original.call(pose, frustum, projection, partialTick, cameraX, cameraY, cameraZ);
            } else if (!this.level.effects().renderClouds(this.level, this.ticks, partialTick, pose, cameraX, cameraY,
                    cameraZ, frustum, projection)) {
                ChimeraCloudRenderer.shared().draw(this.level, this.ticks, pose, frustum, projection, partialTick,
                        cameraX, cameraY, cameraZ);
            }
        } finally {
            PackUniformProvider.setRenderingPhase(previous);
        }
    }

    /** Sodium reads the cloud texture again on a resource reload. */
    @Inject(method = "onResourceManagerReload", at = @At("RETURN"), require = 1)
    private void chimera$cloudTexture(ResourceManager resources, CallbackInfo callback) {
        ChimeraCloudRenderer.shared().reload();
    }

    /** Enters {@code phase} for the rest of the sky window, while a pack draws the sky. */
    @Unique
    private static void chimera$phase(PackRenderingPhase phase) {
        if (ChimeraRenderer.segmentsActive()) PackUniformProvider.setRenderingPhase(phase);
    }

    /** Whether this part of the sky draws: always, unless a pack renders the world and turns it off. */
    @Unique
    private static boolean chimera$keeps(Predicate<SkyDirectives> part) {
        SkyDirectives sky = ChimeraRenderer.packSkyDirectives();
        return sky == null || part.test(sky);
    }

    /** Draws a celestial quad, or frees its mesh unused, as Iris does. */
    @Unique
    private static void chimera$drawOrDrop(MeshData mesh, Operation<Void> original, boolean keep) {
        if (keep) {
            original.call(mesh);
        } else {
            mesh.close();
        }
    }
}
