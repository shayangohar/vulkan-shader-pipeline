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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
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
    private static final VertexConsumer DISCARD = new VertexConsumer() {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int argb) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    };

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderItem(Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II[ILjava/util/List;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V"),
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
        if (buffers != bufferSource
                || !((Object) submit instanceof ChimeraEntitySubmission item)
                || !item.chimera$isWorldEntity()
                || !ChimeraRenderer.segmentsActive()
                || !ChimeraEntityBridge.isInstalled()
                || ChimeraEntityBridge.isDrawActive()
                || !ChimeraEntityBridge.supportsWorldPipeline(renderType.pipeline())) {
            original.call(displayContext, poseStack, buffers, lightCoords, overlayCoords,
                    tintLayers, quads, renderType, foilType);
            return;
        }
        ChimeraEntityBridge.Family family = item.chimera$family() == ChimeraEntitySubmission.FAMILY_BLOCK
                ? ChimeraEntityBridge.Family.BLOCK : ChimeraEntityBridge.Family.ENTITY;
        boolean packed = ChimeraFamilyDraw.emit(family, renderType, source -> {
            ChimeraEntityBridge.beginModelEntity(item.chimera$entityId(), item.chimera$blockEntityId());
            try {
                original.call(displayContext, poseStack, source, lightCoords, overlayCoords,
                        tintLayers, quads, renderType, ItemStackRenderState.FoilType.NONE);
            } finally {
                ChimeraEntityBridge.endModelEntity();
            }
        });
        if (!packed) {
            original.call(displayContext, poseStack, buffers, lightCoords, overlayCoords,
                    tintLayers, quads, renderType, foilType);
            return;
        }
        if (foilType != ItemStackRenderState.FoilType.NONE) {
            // Re-emit for the glint layers only; the item layer is already drawn.
            MultiBufferSource glintOnly = type -> type == renderType ? DISCARD : buffers.getBuffer(type);
            original.call(displayContext, poseStack, glintOnly, lightCoords, overlayCoords,
                    tintLayers, quads, renderType, foilType);
        }
    }
}
