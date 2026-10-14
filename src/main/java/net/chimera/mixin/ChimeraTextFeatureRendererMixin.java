package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.chimera.render.ChimeraFamilyDraw;
import net.chimera.render.shader.SubmissionIdentity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws world text with the pack program Iris gives it (IrisPipelines.getText):
 * a block entity's text (signs) through gbuffers_block_translucent, other world
 * text through gbuffers_entities_translucent. Drawn by the host, text wrote only
 * colour into a buffer deferred packs never show, so signs read blank.
 *
 * <p>Plain text and outlined text (glowing signs, drawInBatch8xOutline) take the
 * same route, ChimeraFamilyDraw.emitText; a text background goes to the TEXT_BG
 * lane, and intensity text keeps its host draw.</p>
 */
@Mixin(TextFeatureRenderer.class)
public abstract class ChimeraTextFeatureRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/util/FormattedCharSequence;"
                    + "FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;"
                    + "Lnet/minecraft/client/gui/Font$DisplayMode;II)V"),
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
        ChimeraFamilyDraw.emitText(SubmissionIdentity.ofWorldNode(submit), buffers, target ->
                original.call(font, string, x, y, color, dropShadow, pose, target, displayMode,
                        backgroundColor, lightCoords));
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawInBatch8xOutline(Lnet/minecraft/util/FormattedCharSequence;"
                    + "FFIILorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
            require = 1)
    private void chimera$packOutlinedText(
            Font font,
            FormattedCharSequence string,
            float x,
            float y,
            int color,
            int outlineColor,
            Matrix4f pose,
            MultiBufferSource buffers,
            int lightCoords,
            Operation<Void> original,
            @Local SubmitNodeStorage.TextSubmit submit
    ) {
        ChimeraFamilyDraw.emitText(SubmissionIdentity.ofWorldNode(submit), buffers, target ->
                original.call(font, string, x, y, color, outlineColor, pose, target, lightCoords));
    }
}
