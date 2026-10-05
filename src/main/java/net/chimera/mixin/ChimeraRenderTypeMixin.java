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
        if (pass != null && ChimeraEntityBridge.isWorldSubmissionWindow()
                && (type.pipeline() == RenderPipelines.WEATHER_DEPTH_WRITE
                || type.pipeline() == RenderPipelines.WEATHER_NO_DEPTH_WRITE)
                && mesh != null && mesh.drawState().format() == DefaultVertexFormat.PARTICLE
                && ChimeraEntityBridge.beginWeatherDraw()) {
            var weather = pass.particlePipeline(ChimeraEntityBridge.Family.WEATHER);
            boolean window = false;
            try {
                pass.prepareProgramImages(weather.pipeline());
                if (weather.requiresDynamicAttachments()) {
                    window = pass.beginPackFamilyWindow(weather.outputPlan());
                    if (!window) {
                        ChimeraEntityBridge.endDraw();
                        original.call(mesh); // Weather keeps its native layout.
                        return;
                    }
                }
                original.call(mesh);
            } finally {
                if (window) pass.endPackFamilyWindow(weather.outputPlan());
                ChimeraEntityBridge.endDraw();
            }
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
