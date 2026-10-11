package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraCustomGeometryStorage;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.chimera.render.shader.HostLayoutLane;
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
 * Holds world custom geometry back from the host batch with its submitter's identity, as
 * ChimeraModelFeatureStorageMixin does for models.
 *
 * <p>Entity-format geometry draws with the pack family of its submitter: block entities
 * (the end portal and gateway, after Iris's entitySolid swap) with the block program, and
 * entities (paintings, experience orbs, guardian beams, fishing bobbers) with the entity
 * programs. Iris picks these programs per pipeline at draw time; drawn by the host they
 * wrote only colour into an image deferred packs never show.</p>
 *
 * <p>Geometry a host-layout lane serves (lightning, dragon rays, beacon beams, fishing
 * lines) keeps its host format and lane, in the main pass or the shadow pass, and is held
 * only so each batch draws with its submitter's entity id, the value Iris's fallback
 * entityId uniform carries.</p>
 */
@Mixin(CustomFeatureRenderer.Storage.class)
public abstract class ChimeraCustomGeometryStorageMixin implements ChimeraCustomGeometryStorage {
    @Unique
    private final Map<RenderType, List<HeldSubmit>> chimera$blockSubmits = new LinkedHashMap<>();
    @Unique
    private final Map<RenderType, List<HeldSubmit>> chimera$entitySubmits = new LinkedHashMap<>();
    @Unique
    private final Map<RenderType, List<HeldSubmit>> chimera$laneSubmits = new LinkedHashMap<>();

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$separate(PoseStack poseStack, RenderType renderType,
                                  SubmitNodeCollector.CustomGeometryRenderer renderer, CallbackInfo callback) {
        if (!ChimeraRenderer.segmentsActive()
                || !ChimeraEntityBridge.isInstalled()
                || !ChimeraEntityBridge.isWorldSubmissionWindow()
                || !ChimeraEntityBridge.isSubmittingEntity()) {
            return;
        }
        Map<RenderType, List<HeldSubmit>> held = chimera$destination(renderType);
        if (held == null) {
            return;
        }
        held.computeIfAbsent(renderType, ignored -> new ArrayList<>())
                .add(new HeldSubmit(poseStack.last().copy(), renderer, ChimeraEntityBridge.currentEntityId(),
                        ChimeraEntityBridge.currentBlockEntityId()));
        callback.cancel();
    }

    /** The held list a submission belongs to, or null when it stays in the host batch. */
    @Unique
    private Map<RenderType, List<HeldSubmit>> chimera$destination(RenderType renderType) {
        // World geometry: the shadow caster window holds it for the shadow pass's lanes.
        HostLayoutLane.Pass pass = ChimeraEntityBridge.isShadowCasterPass()
                ? HostLayoutLane.Pass.SHADOW : HostLayoutLane.Pass.WORLD;
        if (ChimeraEntityBridge.hostLayoutInstalled(
                HostLayoutLane.forDraw(renderType.pipeline(), renderType.format(), pass))) {
            return chimera$laneSubmits;
        }
        int family = ChimeraEntityBridge.currentSubmissionFamily();
        if (!ChimeraEntityBridge.isEntityBufferReady()
                || !ChimeraEntityBridge.supportsWorldRenderType(renderType)
                || !ChimeraEntityBridge.supportsSubmissionFamily(family)) {
            return null;
        }
        return family == ChimeraEntitySubmission.FAMILY_BLOCK ? chimera$blockSubmits : chimera$entitySubmits;
    }

    @Inject(method = "clear", at = @At("HEAD"), require = 1)
    private void chimera$clear(CallbackInfo callback) {
        chimera$clearHeld();
    }

    @Inject(method = "endFrame", at = @At("HEAD"), require = 1)
    private void chimera$clearAtFrameEnd(CallbackInfo callback) {
        chimera$clearHeld();
    }

    @Unique
    private void chimera$clearHeld() {
        chimera$blockSubmits.clear();
        chimera$entitySubmits.clear();
        chimera$laneSubmits.clear();
    }

    @Override
    public Map<RenderType, List<HeldSubmit>> chimera$blockSubmits() {
        return chimera$blockSubmits;
    }

    @Override
    public Map<RenderType, List<HeldSubmit>> chimera$entitySubmits() {
        return chimera$entitySubmits;
    }

    @Override
    public Map<RenderType, List<HeldSubmit>> chimera$laneSubmits() {
        return chimera$laneSubmits;
    }
}
