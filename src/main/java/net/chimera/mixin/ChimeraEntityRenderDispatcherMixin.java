package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Captures entity identity at submission time for delayed world model draws. */
@Mixin(EntityRenderDispatcher.class)
public abstract class ChimeraEntityRenderDispatcherMixin {
    @Inject(method = "submit", at = @At("HEAD"), require = 1)
    private <S extends EntityRenderState> void chimera$beginEntity(
            S state,
            CameraRenderState camera,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CallbackInfo callback
    ) {
        if (!ChimeraRenderer.segmentsActive() || !ChimeraEntityBridge.isInstalled()
                || !ChimeraEntityBridge.isWorldSubmissionWindow()) {
            return;
        }
        String name;
        try {
            name = BuiltInRegistries.ENTITY_TYPE.getKey(state.entityType).toString();
        } catch (RuntimeException failure) {
            name = null;
        }
        ChimeraEntityBridge.beginEntity(name);
    }

    /**
     * Iris MixinEntityRenderDispatcher: a pack that renders its own shadow map replaces the
     * vanilla ground-shadow decal, so the decal is not submitted. Without a pack shadow
     * pass the decal stays.
     */
    @WrapWithCondition(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitShadow(Lcom/mojang/blaze3d/vertex/PoseStack;FLjava/util/List;)V"),
            require = 1)
    private boolean chimera$vanillaEntityShadow(
            SubmitNodeCollector collector,
            PoseStack poseStack,
            float radius,
            List<EntityRenderState.ShadowPiece> pieces
    ) {
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        return !(ChimeraRenderer.segmentsActive() && pass != null && pass.packShadowPassActive());
    }

    @Inject(method = "submit", at = @At("RETURN"), require = 1)
    private <S extends EntityRenderState> void chimera$endEntity(
            S state,
            CameraRenderState camera,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CallbackInfo callback
    ) {
        if (ChimeraRenderer.segmentsActive()
                && ChimeraEntityBridge.isInstalled()
                && ChimeraEntityBridge.isWorldSubmissionWindow()) {
            ChimeraEntityBridge.endEntity();
        }
    }
}
