package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves view bobbing, hurt shake and nausea from the projection into the model view while a
 * shader pack renders the world, as Iris MixinModelViewBobbing (and OptiFine) do. Packs assume
 * gbufferProjection is a plain perspective: Photon's project() reads only its diagonal and last
 * column, so a bobbing projection broke its screen-space reconstruction whenever the player
 * walked (GTAO bent normals, and with them the sky light, flickered). The raster transform is
 * unchanged: projection * (bob * rotation) equals (projection * bob) * rotation.
 */
@Mixin(GameRenderer.class)
public abstract class ChimeraModelViewBobbingMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private Camera mainCamera;
    @Shadow private int confusionAnimationTick;

    @Shadow
    protected abstract void bobHurt(PoseStack poses, float tick);

    @Shadow
    protected abstract void bobView(PoseStack poses, float tick);

    @Unique private boolean chimera$packView;

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 1)
    private void chimera$decideView(DeltaTracker delta, CallbackInfo callback) {
        chimera$packView = ChimeraRenderer.packProjectionActive();
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"),
            require = 1)
    private void chimera$hurtOffProjection(GameRenderer self, PoseStack poses, float tick, Operation<Void> original) {
        if (!chimera$packView) original.call(self, poses, tick);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"),
            require = 1)
    private void chimera$bobOffProjection(GameRenderer self, PoseStack poses, float tick, Operation<Void> original) {
        if (!chimera$packView) original.call(self, poses, tick);
    }

    /** The screen effect scale only feeds the projection's nausea warp, which moves below. */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F"),
            require = 1)
    private float chimera$nauseaOffProjection(Double scale, Operation<Float> original) {
        return chimera$packView ? 0.0F : original.call(scale);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4f;rotation(Lorg/joml/Quaternionfc;)Lorg/joml/Matrix4f;", remap = false),
            require = 1)
    private Matrix4f chimera$bobModelView(Matrix4f modelView, Quaternionfc rotation, Operation<Matrix4f> original,
                                          @Local(argsOnly = true) DeltaTracker delta) {
        if (!chimera$packView) return original.call(modelView, rotation);
        PoseStack poses = new PoseStack();
        float tick = mainCamera.getPartialTickTime();
        bobHurt(poses, tick);
        if (minecraft.options.bobView().get()) bobView(poses, tick);
        modelView.set(poses.last().pose());
        chimera$applyNausea(modelView, delta.getGameTimeDeltaPartialTick(true));
        return modelView.rotate(rotation);
    }

    /** GameRenderer.renderLevel's nausea and portal warp, applied to the model view. */
    @Unique
    private void chimera$applyNausea(Matrix4f modelView, float partialTick) {
        LocalPlayer player = minecraft.player;
        if (player == null) return;
        float scale = minecraft.options.screenEffectScale().get().floatValue();
        float strength = Mth.lerp(partialTick, player.oSpinningEffectIntensity, player.spinningEffectIntensity)
                * scale * scale;
        if (strength <= 0.0F) return;
        int speed = player.hasEffect(MobEffects.CONFUSION) ? 7 : 20;
        float squash = 5.0F / (strength * strength + 5.0F) - strength * 0.04F;
        squash *= squash;
        Vector3f axis = new Vector3f(0.0F, Mth.SQRT_OF_TWO / 2.0F, Mth.SQRT_OF_TWO / 2.0F);
        float angle = (confusionAnimationTick + partialTick) * speed * ((float) Math.PI / 180.0F);
        modelView.rotate(angle, axis);
        modelView.scale(1.0F / squash, 1.0F, 1.0F);
        modelView.rotate(-angle, axis);
    }
}
