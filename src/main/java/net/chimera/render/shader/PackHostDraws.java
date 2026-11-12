package net.chimera.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.PackPipelines;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.function.BooleanSupplier;

/**
 * Draws a host mesh that a pack program serves with that program, inside the program's pack
 * outputs: the sky and clouds (ChimeraSkyBridge) and the host-layout lanes (particles,
 * weather, lines, glint, lightning, leads, text backgrounds and the shadow lanes). The
 * program is chosen by the draw's shader and vertex format; the host's state and draw call stay,
 * and the ShaderInstance pipeline seam answers with the open draw's pipeline. In the shadow pass a
 * draw no shadow lane serves stays out of the shadow map. Without a gbuffers_skybasic the horizon
 * and the sky disc keep their host shader but draw unfogged, as Iris's SKY_BASIC fallback does.
 * Family batches arrive with their draw already open (FamilyRenderType) and pass through.
 */
public final class PackHostDraws {
    /** True while a host draw runs under the program this class chose for it. */
    private static boolean serving;

    private PackHostDraws() {}

    /**
     * Runs {@code draw} under the pack program serving it; {@code drop} discards the mesh instead. A
     * draw inside one this class already serves runs as it is: Reforged 0.3.5 draws an immediate mesh
     * by uploading it to its immediate VertexBuffer and drawing that (BufferUploaderM), so
     * ChimeraBufferUploaderMixin's draw reaches ChimeraVertexBufferMixin's with the same mesh.
     */
    public static void draw(ShaderInstance shader, VertexFormat format, Runnable draw, Runnable drop) {
        if (serving || ChimeraEntityBridge.isDrawActive()) {
            draw.run();
            return;
        }
        serving = true;
        try {
            serve(shader, format, draw, drop);
        } finally {
            serving = false;
        }
    }

    static boolean serving() {
        return serving;
    }

    private static void serve(ShaderInstance shader, VertexFormat format, Runnable draw, Runnable drop) {
        String name = shader == null ? null : shader.getName();
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        PackPipelines.PackSky sky = pass == null ? null : ChimeraSkyBridge.programFor(name, format);
        if (sky != null) {
            GeometryOutputPlan outputs = sky.outputPlan();
            drawInWindow(pass, sky.pipeline(), outputs != null && outputs.requiresDynamicAttachments() ? outputs : null,
                    () -> {
                        ChimeraSkyBridge.beginDraw(sky);
                        return true;
                    }, ChimeraSkyBridge::endDraw, draw);
            return;
        }
        if (ChimeraSkyBridge.drawsUnfoggedSky(name)) {
            drawUnfogged(draw);
            return;
        }
        ChimeraEntityBridge.Family lane = pass == null ? null : ChimeraEntityBridge.hostLaneFor(name, format);
        if (lane != null) {
            drawInWindow(pass, ChimeraEntityBridge.lanePipeline(lane, format), pass.laneWindowOutputs(lane),
                    () -> ChimeraEntityBridge.beginDraw(lane, format, name), ChimeraEntityBridge::endDraw, draw);
        } else if (ChimeraEntityBridge.dropsHostDraw(name)) {
            drop.run();
        } else {
            draw.run();
        }
    }

    /** Runs a host draw with no fog, as FogRenderer.setupNoFog leaves it, then restores the fog start. */
    private static void drawUnfogged(Runnable draw) {
        float start = RenderSystem.getShaderFogStart();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        try {
            draw.run();
        } finally {
            RenderSystem.setShaderFogStart(start);
        }
    }

    private static void drawInWindow(ChimeraMainPass pass, GraphicsPipeline pipeline, GeometryOutputPlan outputs,
                                     BooleanSupplier begin, Runnable end, Runnable draw) {
        pass.prepareProgramImages(pipeline);
        if (outputs != null) {
            if (!pass.beginPackFamilyWindow(outputs)) {
                // The host mesh keeps its host program when the program's outputs cannot open.
                draw.run();
                return;
            }
            // The host already set up its pass: reopen rendering inside the window.
            pass.rebindMainTarget();
        }
        boolean open = false;
        try {
            open = begin.getAsBoolean();
            draw.run();
        } finally {
            if (open) end.run();
            if (outputs != null) pass.endPackFamilyWindow(outputs);
        }
    }
}
