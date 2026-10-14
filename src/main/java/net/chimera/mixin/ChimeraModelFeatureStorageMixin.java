package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntityStorage;
import net.chimera.render.shader.FamilyDrawTrace;
import net.chimera.render.shader.SubmissionIdentity;
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
    private final Map<RenderType, List<SubmitNodeStorage.ModelSubmit<?>>> chimera$entitySubmits =
            new LinkedHashMap<>();
    @Unique
    private final Map<RenderType, List<SubmitNodeStorage.ModelSubmit<?>>> chimera$blockSubmits =
            new LinkedHashMap<>();

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$separate(
            RenderType renderType,
            SubmitNodeStorage.ModelSubmit<?> submit,
            CallbackInfo callback
    ) {
        if (!ChimeraRenderer.segmentsActive()
                || !ChimeraEntityBridge.isInstalled()
                || !ChimeraEntityBridge.isWorldSubmissionWindow()
                || !(SubmissionIdentity.ofWorldNode(submit) instanceof SubmissionIdentity entity)
                // Outlined submissions are consumed by the host outline
                // buffer. Keep them out of the temporary extended-format
                // batch so that buffer is never flushed with a host pipeline
                // and an entity-only stride.
                || submit.outlineColor() != 0) {
            return;
        }
        if (!ChimeraEntityBridge.supportsWorldRenderType(renderType)) {
            FamilyDrawTrace.noteUnsupportedWorldPipeline(renderType);
            return;
        }
        if (ChimeraEntityBridge.isEntityBufferReady()
                && ChimeraEntityBridge.supportsSubmissionFamily(entity.family())) {
            Map<RenderType, List<SubmitNodeStorage.ModelSubmit<?>>> destination =
                    entity.family() == SubmissionIdentity.FAMILY_BLOCK
                            ? chimera$blockSubmits : chimera$entitySubmits;
            destination.computeIfAbsent(renderType, ignored -> new ArrayList<>()).add(submit);
            FamilyDrawTrace.noteSeparatedBatch(renderType,
                    destination.get(renderType).size());
            callback.cancel();
        }
    }

    @Inject(method = "clear", at = @At("HEAD"), require = 1)
    private void chimera$clearEntityBatch(CallbackInfo callback) {
        chimera$entitySubmits.clear();
        chimera$blockSubmits.clear();
    }

    @Inject(method = "endFrame", at = @At("HEAD"), require = 1)
    private void chimera$clearEntityBatchAtFrameEnd(CallbackInfo callback) {
        chimera$entitySubmits.clear();
        chimera$blockSubmits.clear();
    }

    @Override
    public Map<RenderType, List<SubmitNodeStorage.ModelSubmit<?>>> chimera$entitySubmits() {
        return chimera$entitySubmits;
    }

    @Override
    public Map<RenderType, List<SubmitNodeStorage.ModelSubmit<?>>> chimera$blockSubmits() {
        return chimera$blockSubmits;
    }

    @Override
    public void chimera$clearEntitySubmits() {
        chimera$entitySubmits.clear();
        chimera$blockSubmits.clear();
    }
}
