package net.chimera.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.FamilyDrawTrace;
import net.chimera.render.shader.SubmissionIdentity;
import net.chimera.shaderpack.PackPipelines;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.function.Consumer;

/**
 * Emits one world render-type batch through the pack family that serves it:
 * entity models and world items share this admission sequence.
 *
 * <p>The upload widens the host vertex format for as long as the draw is
 * active, so every admission check runs before the batch is emitted. A family
 * window that cannot open, or a batch whose pipeline or buffer is unavailable,
 * leaves the batch to the host format and host draw from the outset: a widened
 * mesh can never be handed back to the host pipeline.</p>
 */
public final class ChimeraFamilyDraw {
    /** Drops every vertex: replays a draw only to learn its render types, or to skip one. */
    public static final VertexConsumer DISCARD = new VertexConsumer() {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int argb) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    };

    private ChimeraFamilyDraw() {}

    /**
     * Emits {@code body} into the separated pack buffer and flushes it with the
     * selected family pipeline. Returns false, having emitted nothing, when the
     * batch must be drawn by the host instead.
     */
    public static boolean emit(
            ChimeraEntityBridge.Family base,
            RenderType renderType,
            Consumer<MultiBufferSource.BufferSource> body
    ) {
        ChimeraEntityBridge.Family selected = ChimeraEntityBridge.familyForRenderType(base, renderType);
        FamilyDrawTrace.noteFamilyBatch(selected, 1);
        if (!ChimeraEntityBridge.beginDraw(selected)) {
            FamilyDrawTrace.noteFamilyHostFallback(selected, renderType);
            return false;
        }
        // The pipeline follows the selected family, not the base family:
        // translucent and spider-eyes draws need their own MRT windows.
        ChimeraMainPass mainPass = ChimeraRenderer.getMainPass();
        PackPipelines.PackEntity selectedPipeline = mainPass == null
                ? null : mainPass.familyPipeline(selected);
        boolean windowOpen = false;
        boolean admitted = false;
        try {
            if (selectedPipeline != null && selectedPipeline.requiresDynamicAttachments()) {
                windowOpen = mainPass.beginPackFamilyWindow(selectedPipeline);
                if (!windowOpen) {
                    return false;
                }
            }
            MultiBufferSource.BufferSource source = ChimeraEntityBridge.entityBufferSource();
            if (source == null) {
                return false;
            }
            admitted = true;
            body.accept(source);
            ChimeraEntityBridge.endEntityBatch(renderType);
            return true;
        } finally {
            if (windowOpen) {
                mainPass.endPackFamilyWindow(selectedPipeline);
            }
            if (ChimeraEntityBridge.isDrawActive()) {
                ChimeraEntityBridge.endDraw();
            }
            if (!admitted) {
                FamilyDrawTrace.noteFamilyHostFallback(selected, renderType);
            }
        }
    }

    /**
     * Emits one render type of a delayed world node through the family of the entity that
     * submitted it, with that entity's ids current while {@code body} draws. Returns false,
     * having emitted nothing, when the host must draw it.
     */
    public static boolean emitSubmission(
            SubmissionIdentity identity,
            RenderType renderType,
            Consumer<MultiBufferSource.BufferSource> body
    ) {
        return emit(identity.baseFamily(), renderType, source -> {
            ChimeraEntityBridge.beginModelEntity(identity.entityId(), identity.blockEntityId());
            try {
                body.accept(source);
            } finally {
                ChimeraEntityBridge.endModelEntity();
            }
        });
    }
}
