package net.chimera.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntityStorage;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/** Renders the separated entity batch with the guarded pack pipeline. */
@Mixin(ModelFeatureRenderer.class)
public abstract class ChimeraModelFeatureRendererMixin {

    @Inject(method = "renderModel", at = @At("HEAD"), require = 1)
    private void chimera$beginModel(
            SubmitNodeStorage.ModelSubmit submit,
            RenderType renderType,
            VertexConsumer vertexConsumer,
            OutlineBufferSource outlineBufferSource,
            MultiBufferSource.BufferSource bufferSource,
            CallbackInfo callback
    ) {
        if (ChimeraEntityBridge.isDrawActive()
                && (Object) submit instanceof ChimeraEntitySubmission entity
                && entity.chimera$isWorldEntity()) {
            ChimeraEntityBridge.noteModelDraw(renderType);
            ChimeraEntityBridge.setCurrentEntityId(entity.chimera$entityId());
        }
    }

    @Inject(method = "renderModel", at = @At("RETURN"), require = 1)
    private void chimera$endModel(
            SubmitNodeStorage.ModelSubmit submit,
            RenderType renderType,
            VertexConsumer vertexConsumer,
            OutlineBufferSource outlineBufferSource,
            MultiBufferSource.BufferSource bufferSource,
            CallbackInfo callback
    ) {
        if (ChimeraEntityBridge.isDrawActive()
                && (Object) submit instanceof ChimeraEntitySubmission entity
                && entity.chimera$isWorldEntity()) {
            ChimeraEntityBridge.setCurrentEntityId(0);
        }
    }
    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private void chimera$beginEntityBatch(
            SubmitNodeCollection collection,
            MultiBufferSource.BufferSource bufferSource,
            OutlineBufferSource outlineBufferSource,
            MultiBufferSource.BufferSource crumblingBufferSource,
            CallbackInfo callback
    ) {
        if (!(collection.getModelSubmits() instanceof ChimeraEntityStorage storage)) {
            return;
        }
        if (!ChimeraRenderer.segmentsActive() || !ChimeraEntityBridge.isInstalled()) {
            storage.chimera$clearEntitySubmits();
        }
    }

    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void chimera$renderEntityBatch(
            SubmitNodeCollection collection,
            MultiBufferSource.BufferSource bufferSource,
            OutlineBufferSource outlineBufferSource,
            MultiBufferSource.BufferSource crumblingBufferSource,
            CallbackInfo callback
    ) {
        if (!(collection.getModelSubmits() instanceof ChimeraEntityStorage storage)
                || !ChimeraRenderer.segmentsActive()
                || !ChimeraEntityBridge.isInstalled()
                || storage.chimera$entitySubmits().isEmpty()) {
            return;
        }
        if (!ChimeraEntityBridge.beginDraw()) {
            storage.chimera$clearEntitySubmits();
            return;
        }
        try {
            MultiBufferSource.BufferSource entityBufferSource =
                    ChimeraEntityBridge.entityBufferSource();
            if (entityBufferSource == null) {
                return;
            }
            chimera$renderBatch(entityBufferSource, outlineBufferSource,
                    storage.chimera$entitySubmits(), crumblingBufferSource);
            // ModelFeatureRenderer normally flushes the caller's source later in the
            // frame. This source is Chimera-owned, so flush it before the guarded
            // format and descriptor window closes.
            ChimeraEntityBridge.endEntityBatch();
        } finally {
            ChimeraEntityBridge.endDraw();
            storage.chimera$clearEntitySubmits();
        }
    }

    @Invoker("renderBatch")
    abstract void chimera$renderBatch(
            MultiBufferSource.BufferSource bufferSource,
            OutlineBufferSource outlineBufferSource,
            Map<RenderType, List<SubmitNodeStorage.ModelSubmit>> submits,
            MultiBufferSource.BufferSource crumblingBufferSource
    );
}
