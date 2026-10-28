package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.PreSkyPasses;
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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
 * - Just before the sky, the shadow pass and the prepare passes run, as in Iris (PreSkyPasses).
 * - The level's family batches are held to Iris's translucent boundary, where they draw before the
 *   centre-depth sample and the solid hand; with separateEntityDraws the late kinds wait for the
 *   deferred passes at the translucent terrain layer (WorldRendererMixin).
 * - Particles and weather draw in their lanes' windows; particles move between NeoForge's early
 *   and late calls as the pack's particle ordering asks.
 * - RETURN draws the translucent hand and resolves the finished HDR world into the stable
 *   output target used by the hand, the GUI and VulkanMod's post chain.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraLevelRendererMixin {
    private static final String PARTICLES = "Lnet/minecraft/client/particle/ParticleEngine;render("
            + "Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F"
            + "Lnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V";
    private static final String RENDER_ENTITY = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity("
            + "Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;)V";
    private static final String RENDER_SKY = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky("
            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V";

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void chimera$beginFrame(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                    GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelView,
                                    Matrix4f projection, CallbackInfo ci) {
        ChimeraHandRenderer.beginFrame();
        FamilyBufferSource.beginLevel();
        PreSkyPasses.beginFrame();
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

    /**
     * Iris's shadow pass and prepare passes, just before the sky (PreSkyPasses), with the frustum
     * renderLevel then passes setupRender: the captured one if the debug key froze it, else the
     * culling frustum.
     */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_SKY), require = 1)
    private void chimera$beforeSky(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                   GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelView,
                                   Matrix4f projection, CallbackInfo ci) {
        ChimeraMainPass pass = ChimeraRenderer.segmentsActive() ? ChimeraRenderer.getMainPass() : null;
        if (pass == null) return;
        var renderer = (ChimeraLevelRendererAccessor) this;
        Frustum captured = renderer.chimera$capturedFrustum();
        Player player = Minecraft.getInstance().player;
        PreSkyPasses.render(pass, camera, captured != null ? captured : renderer.chimera$cullingFrustum(),
                captured != null, player != null && player.isSpectator());
    }

    /**
     * One entity's render is one of Iris's batch groups (batchedentityrendering MixinLevelRenderer):
     * the order its render types are written in orders the level's held batches (IrisBatchOrder).
     */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_ENTITY), require = 1)
    private void chimera$entityGroup(LevelRenderer renderer, Entity entity, double cameraX, double cameraY,
                                     double cameraZ, float partialTick, PoseStack poses, MultiBufferSource buffers,
                                     Operation<Void> original) {
        boolean opened = FamilyBufferSource.startLevelGroup();
        try {
            original.call(renderer, entity, cameraX, cameraY, cameraZ, partialTick, poses, buffers);
        } finally {
            if (opened) FamilyBufferSource.endLevelGroup();
        }
    }

    /**
     * Iris's translucent boundary, in Iris's order: the level's entity batches
     * (batchedentityrendering), then beginHand's centre-depth sample, then the solid hand, whose
     * segment copies the pre-hand depth (depthtex2) with every entity in it.
     */
    @Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=translucent"), require = 1)
    private void chimera$translucentBoundary(CallbackInfo callback) {
        ChimeraMainPass pass = ChimeraRenderer.segmentsActive() ? ChimeraRenderer.getMainPass() : null;
        FamilyBufferSource.endLevelBatchesAtBoundary(pass);
        if (pass != null) pass.sampleCenterDepthBeforeHand();
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
            ChimeraMainPass pass = ChimeraRenderer.segmentsActive() ? ChimeraRenderer.getMainPass() : null;
            if (pass != null) pass.markWorldDrawn();
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
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            // Family batches a mod's render-stage drawing left behind, crumbling included.
            FamilyBufferSource.endHeldLevelBatches(pass);
            if (pass != null) {
                ChimeraHandRenderer.drawTranslucent();
                pass.finishLevelSegment();
            }
            ChimeraEntityBridge.endWorldWindow();
        }
    }
}
