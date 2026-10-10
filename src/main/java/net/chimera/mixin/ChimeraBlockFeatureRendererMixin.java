package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraFamilyDraw;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.feature.BlockFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws block models and blocks that entities submit (item frames, falling blocks,
 * TNT, minecart contents) with the pack family of their submitter, as Iris does: their
 * render types use the entity pipelines, which Iris maps to gbuffers_entities or the
 * block program. Drawn by the host they wrote only colour into a buffer deferred packs
 * never show, so item frames vanished. Outline passes and moving (piston) blocks keep
 * their host paths.
 */
@Mixin(BlockFeatureRenderer.class)
public abstract class ChimeraBlockFeatureRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;renderModel(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/renderer/block/model/BlockStateModel;FFFII)V",
            ordinal = 0), require = 1)
    private void chimera$packBlockModel(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            BlockStateModel model,
            float r,
            float g,
            float b,
            int lightCoords,
            int overlayCoords,
            Operation<Void> original,
            @Local SubmitNodeStorage.BlockModelSubmit submit
    ) {
        RenderType renderType = submit.renderType();
        if (!chimera$packed((Object) submit, renderType, source -> original.call(pose,
                source.getBuffer(renderType), model, r, g, b, lightCoords, overlayCoords))) {
            original.call(pose, consumer, model, r, g, b, lightCoords, overlayCoords);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderSingleBlock(Lnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            ordinal = 0), require = 1)
    private void chimera$packBlock(
            net.minecraft.client.renderer.block.BlockRenderDispatcher dispatcher,
            BlockState state,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int lightCoords,
            int overlayCoords,
            Operation<Void> original,
            @Local SubmitNodeStorage.BlockSubmit submit
    ) {
        // renderSingleBlock draws one model with this one render type.
        RenderType renderType = ItemBlockRenderTypes.getRenderType(state);
        if (!chimera$packed((Object) submit, renderType, source -> original.call(dispatcher, state,
                poseStack, source, lightCoords, overlayCoords))) {
            original.call(dispatcher, state, poseStack, buffers, lightCoords, overlayCoords);
        }
    }

    /** Emits the submission through its family; false when the host must draw it. */
    private static boolean chimera$packed(
            Object submit,
            RenderType renderType,
            java.util.function.Consumer<MultiBufferSource.BufferSource> body
    ) {
        if (!ChimeraRenderer.segmentsActive() || !ChimeraEntityBridge.isInstalled()) {
            return false;
        }
        String rejected = !(submit instanceof ChimeraEntitySubmission) ? "no-identity"
                : !((ChimeraEntitySubmission) submit).chimera$isWorldEntity() ? "not-world-entity"
                : ChimeraEntityBridge.isDrawActive() ? "draw-active"
                : !ChimeraEntityBridge.supportsSubmissionFamily(((ChimeraEntitySubmission) submit).chimera$family())
                ? "family-unsupported"
                : !ChimeraEntityBridge.supportsWorldPipeline(renderType.pipeline()) ? "pipeline-unsupported"
                : !ChimeraEntityBridge.supportsWorldRenderType(renderType) ? "format-unsupported"
                : null;
        if (rejected != null) {
            ChimeraEntityBridge.noteSubmissionKeptOnHost(rejected, renderType);
            return false;
        }
        ChimeraEntitySubmission entity = (ChimeraEntitySubmission) submit;
        ChimeraEntityBridge.Family base = entity.chimera$family() == ChimeraEntitySubmission.FAMILY_BLOCK
                ? ChimeraEntityBridge.Family.BLOCK : ChimeraEntityBridge.Family.ENTITY;
        return ChimeraFamilyDraw.emit(base, renderType, source -> {
            ChimeraEntityBridge.beginModelEntity(entity.chimera$entityId(), entity.chimera$blockEntityId());
            try {
                body.accept(source);
            } finally {
                ChimeraEntityBridge.endModelEntity();
            }
        });
    }
}
