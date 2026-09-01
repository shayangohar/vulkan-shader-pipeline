package net.chimera.mixin;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
