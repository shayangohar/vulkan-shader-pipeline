package net.chimera.render;

import net.chimera.render.shader.ChimeraEntityBridge;
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
        ChimeraEntityBridge.noteFamilyBatch(selected, 1);
        if (!ChimeraEntityBridge.beginDraw(selected)) {
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
                ChimeraEntityBridge.noteFamilyHostFallback(selected, renderType);
            }
        }
    }
}
