package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraCustomGeometryStorage;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
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

/**
 * Separates block-entity custom geometry (the end portal and gateway, after
 * Iris's entitySolid swap) from the host batch, as ChimeraModelFeatureStorageMixin
 * does for block-entity models, so it can draw with the pack block program.
 */
@Mixin(CustomFeatureRenderer.Storage.class)
public abstract class ChimeraCustomGeometryStorageMixin implements ChimeraCustomGeometryStorage {
    @Unique
    private final Map<RenderType, List<BlockSubmit>> chimera$blockSubmits = new LinkedHashMap<>();

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$separate(PoseStack poseStack, RenderType renderType,
                                  SubmitNodeCollector.CustomGeometryRenderer renderer, CallbackInfo callback) {
        if (ChimeraRenderer.segmentsActive()
                && ChimeraEntityBridge.isInstalled()
                && ChimeraEntityBridge.isWorldSubmissionWindow()
                && ChimeraEntityBridge.isEntityBufferReady()
                && ChimeraEntityBridge.isSubmittingEntity()
                && ChimeraEntityBridge.currentSubmissionFamily() == ChimeraEntitySubmission.FAMILY_BLOCK
                && ChimeraEntityBridge.supportsWorldRenderType(renderType)) {
            chimera$blockSubmits.computeIfAbsent(renderType, ignored -> new ArrayList<>())
                    .add(new BlockSubmit(poseStack.last().copy(), renderer, ChimeraEntityBridge.currentEntityId()));
            callback.cancel();
        }
    }

    @Inject(method = "clear", at = @At("HEAD"), require = 1)
    private void chimera$clear(CallbackInfo callback) {
        chimera$blockSubmits.clear();
    }

    @Inject(method = "endFrame", at = @At("HEAD"), require = 1)
    private void chimera$clearAtFrameEnd(CallbackInfo callback) {
        chimera$blockSubmits.clear();
    }

    @Override
    public Map<RenderType, List<BlockSubmit>> chimera$blockSubmits() {
        return chimera$blockSubmits;
    }
}
