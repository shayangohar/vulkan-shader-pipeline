package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks delayed block-entity model submissions for the block family adapter. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class ChimeraBlockEntityRenderDispatcherMixin {
    @Inject(method = "submit", at = @At("HEAD"), require = 1)
    private void chimera$beginBlockEntity(
            BlockEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            net.minecraft.client.renderer.state.CameraRenderState camera,
            CallbackInfo callback
    ) {
        if (ChimeraRenderer.segmentsActive()
                && ChimeraEntityBridge.isWorldSubmissionWindow()) {
            ChimeraEntityBridge.beginBlockEntity();
        }
    }

    @Inject(method = "submit", at = @At("RETURN"), require = 1)
    private void chimera$endBlockEntity(
            BlockEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            net.minecraft.client.renderer.state.CameraRenderState camera,
            CallbackInfo callback
    ) {
        if (ChimeraRenderer.segmentsActive()
                && ChimeraEntityBridge.isWorldSubmissionWindow()) {
            ChimeraEntityBridge.endBlockEntity();
        }
    }
}
