package net.chimera.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntityStorage;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.chimera.render.ChimeraFamilyDraw;
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
            ChimeraEntityBridge.beginModelEntity(entity.chimera$entityId());
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
            ChimeraEntityBridge.endModelEntity();
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
        // When no Chimera family adapter is installed, the storage mixin leaves
        // entity and block-entity submissions in the host model list. Do not
        // clear the side maps merely because the pack has no entity adapter;
        // that would turn a normal host fallback into a missing-draw path.
        if (!ChimeraRenderer.segmentsActive()) {
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
                || (storage.chimera$entitySubmits().isEmpty()
                && storage.chimera$blockSubmits().isEmpty())) {
            return;
        }
        try {
            chimera$renderFamily(ChimeraEntityBridge.Family.ENTITY,
                    storage.chimera$entitySubmits(), bufferSource, outlineBufferSource,
                    crumblingBufferSource);
            chimera$renderFamily(ChimeraEntityBridge.Family.BLOCK,
                    storage.chimera$blockSubmits(), bufferSource, outlineBufferSource,
                    crumblingBufferSource);
        } finally {
            if (ChimeraEntityBridge.isDrawActive()) {
                ChimeraEntityBridge.endDraw();
            }
            storage.chimera$clearEntitySubmits();
        }
    }

    private void chimera$renderFamily(
            ChimeraEntityBridge.Family family,
            Map<RenderType, List<SubmitNodeStorage.ModelSubmit>> submits,
            MultiBufferSource.BufferSource hostBufferSource,
            OutlineBufferSource outlineBufferSource,
            MultiBufferSource.BufferSource crumblingBufferSource
    ) {
        if (submits.isEmpty()) {
            return;
        }
        for (Map.Entry<RenderType, List<SubmitNodeStorage.ModelSubmit>> entry : submits.entrySet()) {
            Map<RenderType, List<SubmitNodeStorage.ModelSubmit>> batch =
                    Map.of(entry.getKey(), entry.getValue());
            if (!ChimeraFamilyDraw.emit(family, entry.getKey(), source ->
                    chimera$renderBatch(source, outlineBufferSource, batch, crumblingBufferSource))) {
                chimera$renderBatch(hostBufferSource, outlineBufferSource, batch, crumblingBufferSource);
            }
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
