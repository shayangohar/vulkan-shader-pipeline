package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.FamilyBufferSource;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.ParticleDirectives;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/**
 * Drives Chimera's world/output split inside LevelRenderer.renderLevel:
 *
 * - HEAD captures the frame the pack uniforms read and opens the family lanes' world window.
 * - The world clear opens the internal RGBA16F world target instead, cleared as the pack asks,
 *   with the fog colour FogRenderer has just set.
 * - The entity and block-entity family batches draw where vanilla finishes each of those loops.
 * - Particles and weather draw in their lanes' windows; particles move between NeoForge's early
 *   and late calls as the pack's particle ordering asks.
 * - The translucent phase first draws the solid hand, as Iris does.
 * - RETURN draws the translucent hand and resolves the finished HDR world into the stable
 *   output target used by the hand, the GUI and VulkanMod's post chain.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraLevelRendererMixin {
    private static final String PARTICLES = "Lnet/minecraft/client/particle/ParticleEngine;render("
            + "Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F"
            + "Lnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V";

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void chimera$beginFrame(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                    GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelView,
                                    Matrix4f projection, CallbackInfo ci) {
        ChimeraHandRenderer.beginFrame();
        if (ChimeraRenderer.beginLevelSegments()) {
            ChimeraEntityBridge.beginWorldWindow();
            PackUniformProvider.beginFrame(camera, deltaTracker.getGameTimeDeltaPartialTick(false),
                    modelView, projection);
        }
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"), require = 1)
    private void chimera$openWorld(int mask, boolean checkError, Operation<Void> original,
                                   @Local(argsOnly = true) Camera camera) {
        ChimeraMainPass pass = ChimeraRenderer.segmentsActive() ? ChimeraRenderer.getMainPass() : null;
        if (pass == null) {
            original.call(mask, checkError);
            return;
        }
        float[] fog = RenderSystem.getShaderFogColor();
        Vec3 position = camera.getPosition();
        pass.openLevelSegment(position.x, position.y, position.z, new float[] {fog[0], fog[1], fog[2]});
    }

    /** After the entity loop (vanilla's first pose check), before its entity batches flush. */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;"
                    + "checkPoseStack(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            ordinal = 0), require = 1)
    private void chimera$drawEntityFamilies(CallbackInfo ci) {
        chimera$drawFamilyBatches();
    }

    /** After the block-entity loops, VulkanMod's section block entities included. */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;"
                    + "checkPoseStack(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            ordinal = 1, shift = At.Shift.AFTER), require = 1)
    private void chimera$drawBlockEntityFamilies(CallbackInfo ci) {
        chimera$drawFamilyBatches();
    }

    @Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=translucent"), require = 1)
    private void chimera$solidHandBeforeTranslucent(CallbackInfo callback) {
        ChimeraHandRenderer.drawSolid();
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = PARTICLES), require = 1)
    private void chimera$particleLanes(ParticleEngine particles, LightTexture lightTexture, Camera camera,
                                       float partialTick, Frustum frustum, Predicate<ParticleRenderType> types,
                                       Operation<Void> original) {
        ChimeraEntityBridge.beginParticles();
        try {
            original.call(particles, lightTexture, camera, partialTick, frustum, types);
        } finally {
            ChimeraEntityBridge.endParticles();
        }
    }

    /**
     * NeoForge's early particle call: opaque particles, before translucent terrain and so before the
     * pack's deferred passes. Ordinal 0 is the Fabulous path, which Iris leaves alone too.
     */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = PARTICLES, ordinal = 1), require = 1)
    private void chimera$earlyParticles(ParticleEngine particles, LightTexture lightTexture, Camera camera,
                                        float partialTick, Frustum frustum, Predicate<ParticleRenderType> types,
                                        Operation<Void> original) {
        chimera$orderParticles(true, particles, lightTexture, camera, partialTick, frustum, types, original);
    }

    /** NeoForge's late particle call: translucent particles, after translucent terrain. */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = PARTICLES, ordinal = 2), require = 1)
    private void chimera$lateParticles(ParticleEngine particles, LightTexture lightTexture, Camera camera,
                                       float partialTick, Frustum frustum, Predicate<ParticleRenderType> types,
                                       Operation<Void> original) {
        chimera$orderParticles(false, particles, lightTexture, camera, partialTick, frustum, types, original);
    }

    /**
     * Moves particles between NeoForge's two calls as the pack's ordering asks, as Iris's NeoForge
     * MixinLevelRenderer does: before draws every particle early, after every particle late, and
     * mixed keeps NeoForge's split.
     */
    private static void chimera$orderParticles(boolean early, ParticleEngine particles, LightTexture lightTexture,
                                               Camera camera, float partialTick, Frustum frustum,
                                               Predicate<ParticleRenderType> types, Operation<Void> original) {
        ParticleDirectives.Ordering ordering = ChimeraRenderer.packParticleOrdering();
        if (ordering == null) {
            original.call(particles, lightTexture, camera, partialTick, frustum, types);
        } else if (ordering.drawsIn(early)) {
            Predicate<ParticleRenderType> ordered = type -> ordering.early(type.isTranslucent()) == early;
            original.call(particles, lightTexture, camera, partialTick, frustum, ordered);
        }
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSnowAndRain("
                    + "Lnet/minecraft/client/renderer/LightTexture;FDDD)V"), require = 1)
    private void chimera$weatherLane(LevelRenderer renderer, LightTexture lightTexture, float partialTick,
                                     double x, double y, double z, Operation<Void> original) {
        ChimeraEntityBridge.beginWeather();
        try {
            original.call(renderer, lightTexture, partialTick, x, y, z);
        } finally {
            ChimeraEntityBridge.endWeather();
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void chimera$resolveWorld(CallbackInfo ci) {
        if (ChimeraRenderer.segmentsActive()) {
            // Family batches a mod's render-stage drawing left behind.
            chimera$drawFamilyBatches();
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass != null) {
                ChimeraHandRenderer.drawTranslucent();
                pass.finishLevelSegment();
            }
            ChimeraEntityBridge.endWorldWindow();
        }
    }

    private static void chimera$drawFamilyBatches() {
        if (Minecraft.getInstance().renderBuffers().bufferSource() instanceof FamilyBufferSource families) {
            families.endFamilyBatches();
        }
    }
}
