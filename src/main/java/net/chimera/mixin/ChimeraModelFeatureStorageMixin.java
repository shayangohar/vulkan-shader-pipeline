package net.chimera.mixin;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntityStorage;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separates world entity submissions from block-entity model submissions. */
@Mixin(ModelFeatureRenderer.Storage.class)
public abstract class ChimeraModelFeatureStorageMixin implements ChimeraEntityStorage {
    @Unique
    private final Map<RenderType, List<SubmitNodeStorage.ModelSubmit>> chimera$entitySubmits =
            new LinkedHashMap<>();

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$separate(
            RenderType renderType,
            SubmitNodeStorage.ModelSubmit submit,
            CallbackInfo callback
    ) {
        if (ChimeraRenderer.segmentsActive()
                && ChimeraEntityBridge.isInstalled()
                && ChimeraEntityBridge.isEntityBufferReady()
                && (Object) submit instanceof ChimeraEntitySubmission entity
                && entity.chimera$isWorldEntity()
                // Outlined submissions are consumed by the host outline
                // buffer. Keep them out of the temporary extended-format
                // batch so that buffer is never flushed with a host pipeline
                // and an entity-only stride.
                && submit.outlineColor() == 0
                && ChimeraEntityBridge.supportsWorldRenderType(renderType)) {
            chimera$entitySubmits.computeIfAbsent(renderType, ignored -> new ArrayList<>()).add(submit);
            ChimeraEntityBridge.noteSeparatedBatch(renderType,
                    chimera$entitySubmits.get(renderType).size());
            callback.cancel();
        } else if (ChimeraRenderer.segmentsActive()
                && ChimeraEntityBridge.isInstalled()
                && (Object) submit instanceof ChimeraEntitySubmission entity
                && entity.chimera$isWorldEntity()
                && submit.outlineColor() == 0
                && !ChimeraEntityBridge.supportsWorldRenderType(renderType)) {
            ChimeraEntityBridge.noteUnsupportedWorldPipeline(renderType);
        }
    }

    @Inject(method = "clear", at = @At("HEAD"), require = 1)
    private void chimera$clearEntityBatch(CallbackInfo callback) {
        chimera$entitySubmits.clear();
    }

    @Inject(method = "endFrame", at = @At("HEAD"), require = 1)
    private void chimera$clearEntityBatchAtFrameEnd(CallbackInfo callback) {
        chimera$entitySubmits.clear();
    }

    @Override
    public Map<RenderType, List<SubmitNodeStorage.ModelSubmit>> chimera$entitySubmits() {
        return chimera$entitySubmits;
    }

    @Override
    public void chimera$clearEntitySubmits() {
        chimera$entitySubmits.clear();
    }
}
