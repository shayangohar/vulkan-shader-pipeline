package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Draws a host mesh that a host-layout lane owns (particles, crumbling, weather, lines, glint,
 * lightning, beams, leads, text backgrounds and the shadow lanes) with its pack program, inside
 * the lane's pack outputs. The lane is chosen by the draw's shader. In the shadow pass a draw no
 * shadow lane serves stays out of the shadow map. Family batches arrive with their draw already
 * open (FamilyRenderType) and pass through.
 */
@Mixin(BufferUploader.class)
public abstract class ChimeraBufferUploaderMixin {
    @WrapMethod(method = "drawWithShader")
    private static void chimera$hostLane(MeshData mesh, Operation<Void> original) {
        if (ChimeraEntityBridge.isDrawActive()) {
            original.call(mesh);
            return;
        }
        ShaderInstance shader = RenderSystem.getShader();
        String name = shader == null ? null : shader.getName();
        VertexFormat format = mesh.drawState().format();
        ChimeraEntityBridge.Family lane = ChimeraEntityBridge.hostLaneFor(name, format);
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (lane == null || pass == null) {
            if (ChimeraEntityBridge.dropsHostDraw(name)) {
                mesh.close();
            } else {
                original.call(mesh);
            }
            return;
        }
        pass.prepareProgramImages(ChimeraEntityBridge.lanePipeline(lane, format));
        GeometryOutputPlan outputs = pass.laneWindowOutputs(lane);
        if (outputs != null) {
            if (!pass.beginPackFamilyWindow(outputs)) {
                // The host mesh keeps its host program when the lane's outputs cannot open.
                original.call(mesh);
                return;
            }
            // The host already set up its pass: reopen rendering inside the window.
            pass.rebindMainTarget();
        }
        try {
            ChimeraEntityBridge.beginDraw(lane, format, name);
            original.call(mesh);
        } finally {
            if (ChimeraEntityBridge.isDrawActive()) ChimeraEntityBridge.endDraw();
            if (outputs != null) pass.endPackFamilyWindow(outputs);
        }
    }
}
