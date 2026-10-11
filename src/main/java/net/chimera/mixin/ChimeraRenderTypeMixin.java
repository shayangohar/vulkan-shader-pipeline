package net.chimera.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.ChimeraHandRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.HostLayoutLane;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the temporary entity buffer builder and mesh format in lockstep. */
@Mixin(RenderType.class)
public abstract class ChimeraRenderTypeMixin {
    /**
     * Own immediate-family targets before the native pass opens. VulkanMod's
     * RenderTypeM calls Drawer.draw directly, bypassing VkRenderPass.drawIndexed.
     * The crumbling mesh remains BLOCK; hand meshes retain their native prefixes.
     */
    @WrapMethod(method = "draw")
    private void chimera$immediateFamilyWindow(MeshData mesh, Operation<Void> original) {
        RenderType type = (RenderType) (Object) this;
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        // A host-layout lane (weather, lines, glint, lightning, beacon beams, leads, and in the
        // shadow pass text and the water mask) keeps its native layout; only the program and its
        // authored targets change. The host buffer flushes glint after the pack has drawn the
        // items and models it lies on (EQUAL depth).
        var format = mesh == null ? null : mesh.drawState().format();
        HostLayoutLane lane = HostLayoutLane.forDraw(type.pipeline(), format, HostLayoutLane.Pass.current());
        if (pass != null && lane != null && ChimeraEntityBridge.isWorldSubmissionWindow()
                && ChimeraEntityBridge.beginHostLayoutDraw(lane.family())) {
            var lanePipeline = pass.particlePipeline(lane.family());
            boolean window = false;
            try {
                pass.prepareProgramImages(lanePipeline.pipeline());
                if (lanePipeline.requiresDynamicAttachments()) {
                    window = pass.beginPackFamilyWindow(lanePipeline.outputPlan());
                    if (!window) {
                        ChimeraEntityBridge.endDraw();
                        original.call(mesh);
                        return;
                    }
                }
                original.call(mesh);
            } finally {
                if (window) pass.endPackFamilyWindow(lanePipeline.outputPlan());
                ChimeraEntityBridge.endDraw();
            }
            return;
        }
        if (ChimeraEntityBridge.isShadowCasterPass() && !ChimeraEntityBridge.shouldUsePackPipeline(type.pipeline())) {
            // Iris draws every shadow-pass pipeline with a shadow program. One without an
            // installed shadow lane here stays out of the shadow map: its host shader would
            // write colour and depth the pack's shadow programs never produce.
            ChimeraEntityBridge.noteShadowCasterDropped(type.pipeline());
            if (mesh != null) mesh.close();
            return;
        }
        boolean hand = ChimeraHandRenderer.active()
                && ChimeraEntityBridge.shouldUsePackPipeline(type.pipeline());
        var family = pass == null ? null : pass.familyPipeline(hand
                ? ChimeraEntityBridge.activeFamily() : ChimeraEntityBridge.Family.DAMAGED_BLOCK);
        boolean crumbling = !ChimeraHandRenderer.active()
                && ChimeraEntityBridge.isWorldSubmissionWindow() && type.pipeline() == RenderPipelines.CRUMBLING
                && family != null && mesh != null && mesh.drawState().format() == DefaultVertexFormat.BLOCK
                && ChimeraEntityBridge.beginCrumblingDraw();
        if (family == null || (!hand && !crumbling)) {
            original.call(mesh);
            return;
        }
        boolean window = false;
        try {
            if (hand) pass.prepareProgramImages(ChimeraEntityBridge.pipeline());
            if (family.requiresDynamicAttachments()) {
                window = pass.beginPackFamilyWindow(family);
                if (!window) {
                    // Do not draw a widened hand into the wrong attachments.
                    if (crumbling) {
                        ChimeraEntityBridge.endDraw();
                        original.call(mesh);
                    }
                    return;
                }
            }
            original.call(mesh);
        } finally {
            if (window) pass.endPackFamilyWindow(family);
            if (crumbling && ChimeraEntityBridge.isDrawActive()) ChimeraEntityBridge.endDraw();
        }
    }

    @Inject(method = "draw", at = @At("HEAD"), require = 1)
    private void chimera$traceEntityMesh(MeshData mesh, CallbackInfo callback) {
        if (ChimeraEntityBridge.isDrawActive() && mesh != null) {
            MeshData.DrawState state = mesh.drawState();
            ChimeraEntityBridge.noteMeshDraw(state.vertexCount(), state.indexCount(),
                    state.format().getVertexSize());
        }
    }

    @Inject(method = "format", at = @At("RETURN"), cancellable = true, require = 1)
    private void chimera$entityFormat(CallbackInfoReturnable<VertexFormat> callback) {
        if (ChimeraEntityBridge.shouldExtendEntityFormat(callback.getReturnValue())) {
            callback.setReturnValue(ChimeraEntityBridge.extendedFormat(callback.getReturnValue()));
        }
    }
}
