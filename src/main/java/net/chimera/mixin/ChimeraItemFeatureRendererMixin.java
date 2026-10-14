package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraFamilyDraw;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraItemSubmission;
import net.chimera.render.shader.RenderedItemIds;
import net.chimera.render.shader.SubmissionIdentity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Draws world items with the pack family of their holder, as Iris does
 * (gbuffers_entities_translucent for an entity's item, the block program for a
 * block entity's). Drawn by the host they wrote only colour, and deferred
 * lighting relit their pixels from whatever stood behind them.
 *
 * <p>The item's own quads take the pack pipeline; an enchantment glint stays a
 * host draw over them, like every other glint. Outline passes, first-person
 * hand items and GUI items keep their host paths.</p>
 */
@Mixin(ItemFeatureRenderer.class)
public abstract class ChimeraItemFeatureRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;"
                    + "renderItem(Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;II[ILjava/util/List;"
                    + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
                    + "Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V"),
            require = 1)
    private void chimera$packItem(
            ItemDisplayContext displayContext,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int lightCoords,
            int overlayCoords,
            int[] tintLayers,
            List<BakedQuad> quads,
            RenderType renderType,
            ItemStackRenderState.FoilType foilType,
            Operation<Void> original,
            @Local SubmitNodeStorage.ItemSubmit submit,
            @Local(argsOnly = true) MultiBufferSource.BufferSource bufferSource
    ) {
        // Iris MixinItemFeatureRenderer: the ids the node captured are current while it draws.
        ChimeraItemSubmission ids = (ChimeraItemSubmission) (Object) submit;
        RenderedItemIds.push(ids.chimera$itemId(), ids.chimera$blockEntityId());
        try {
            chimera$drawItem(displayContext, poseStack, buffers, lightCoords, overlayCoords, tintLayers, quads,
                    renderType, foilType, original, submit, bufferSource);
        } finally {
            RenderedItemIds.pop();
        }
    }

    @Unique
    private static void chimera$drawItem(
            ItemDisplayContext displayContext,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int lightCoords,
            int overlayCoords,
            int[] tintLayers,
            List<BakedQuad> quads,
            RenderType renderType,
            ItemStackRenderState.FoilType foilType,
            Operation<Void> original,
            SubmitNodeStorage.ItemSubmit submit,
            MultiBufferSource.BufferSource bufferSource
    ) {
        SubmissionIdentity identity = SubmissionIdentity.ofWorldNode(submit);
        if (buffers != bufferSource
                || identity == null
                || !ChimeraRenderer.segmentsActive()
                || !ChimeraEntityBridge.isInstalled()
                || ChimeraEntityBridge.isDrawActive()
                || !ChimeraEntityBridge.supportsWorldPipeline(renderType.pipeline())) {
            original.call(displayContext, poseStack, buffers, lightCoords, overlayCoords,
                    tintLayers, quads, renderType, foilType);
            return;
        }
        boolean packed = ChimeraFamilyDraw.emitSubmission(identity, renderType, source ->
                original.call(displayContext, poseStack, source, lightCoords, overlayCoords,
                        tintLayers, quads, renderType, ItemStackRenderState.FoilType.NONE));
        if (!packed) {
            original.call(displayContext, poseStack, buffers, lightCoords, overlayCoords,
                    tintLayers, quads, renderType, foilType);
            return;
        }
        if (foilType != ItemStackRenderState.FoilType.NONE) {
            // Re-emit for the glint layers only; the item layer is already drawn.
            MultiBufferSource glintOnly = type -> type == renderType ? ChimeraFamilyDraw.DISCARD
                    : buffers.getBuffer(type);
            original.call(displayContext, poseStack, glintOnly, lightCoords, overlayCoords,
                    tintLayers, quads, renderType, foilType);
        }
    }
}
