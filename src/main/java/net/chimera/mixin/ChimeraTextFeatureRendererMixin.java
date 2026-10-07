package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraFamilyDraw;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Draws world text with the pack program Iris gives it (IrisPipelines.getText):
 * a block entity's text (signs) through its block program, other world text
 * through gbuffers_entities_translucent. Drawn by the host, text wrote only
 * colour into a buffer deferred packs never show, so signs read blank.
 *
 * <p>One text node can use several render types (glyph pages, background). The
 * glyph layout is replayed once to learn them, then each type is drawn through
 * its family window on its own; a type the pack does not serve (backgrounds,
 * see-through and intensity text) keeps its host draw. Outline text keeps its
 * host path.</p>
 */
@Mixin(TextFeatureRenderer.class)
public abstract class ChimeraTextFeatureRendererMixin {
    private static final VertexConsumer DISCARD = new VertexConsumer() {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int argb) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    };

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V"),
            require = 1)
    private void chimera$packText(
            Font font,
            FormattedCharSequence string,
            float x,
            float y,
            int color,
            boolean dropShadow,
            Matrix4f pose,
            MultiBufferSource buffers,
            Font.DisplayMode displayMode,
            int backgroundColor,
            int lightCoords,
            Operation<Void> original,
            @Local SubmitNodeStorage.TextSubmit submit
    ) {
        if (!((Object) submit instanceof ChimeraEntitySubmission text)
                || !text.chimera$isWorldEntity()
                || !ChimeraRenderer.segmentsActive()
                || !ChimeraEntityBridge.isInstalled()
                || ChimeraEntityBridge.isDrawActive()) {
            original.call(font, string, x, y, color, dropShadow, pose, buffers, displayMode,
                    backgroundColor, lightCoords);
            return;
        }
        Set<RenderType> types = new LinkedHashSet<>();
        original.call(font, string, x, y, color, dropShadow, pose,
                (MultiBufferSource) type -> {
                    types.add(type);
                    return DISCARD;
                }, displayMode, backgroundColor, lightCoords);
        ChimeraEntityBridge.Family base = text.chimera$family() == ChimeraEntitySubmission.FAMILY_BLOCK
                ? ChimeraEntityBridge.Family.BLOCK : ChimeraEntityBridge.Family.ENTITY;
        for (RenderType renderType : types) {
            boolean packed = ChimeraEntityBridge.familyForRenderType(base, renderType)
                    != ChimeraEntityBridge.Family.HOST_FALLBACK
                    && ChimeraEntityBridge.isTextPipeline(renderType.pipeline())
                    && ChimeraFamilyDraw.emit(base, renderType, source -> {
                        ChimeraEntityBridge.beginModelEntity(text.chimera$entityId(), text.chimera$blockEntityId());
                        try {
                            original.call(font, string, x, y, color, dropShadow, pose,
                                    only(renderType, source), displayMode, backgroundColor, lightCoords);
                        } finally {
                            ChimeraEntityBridge.endModelEntity();
                        }
                    });
            if (!packed) {
                original.call(font, string, x, y, color, dropShadow, pose,
                        only(renderType, buffers), displayMode, backgroundColor, lightCoords);
            }
        }
    }

    private static MultiBufferSource only(RenderType renderType, MultiBufferSource buffers) {
        return type -> type == renderType ? buffers.getBuffer(type) : DISCARD;
    }
}
