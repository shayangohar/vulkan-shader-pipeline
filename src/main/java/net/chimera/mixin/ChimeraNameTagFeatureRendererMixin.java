package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.chimera.render.ChimeraFamilyDraw;
import net.chimera.render.shader.SubmissionIdentity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws name tags as Iris does: the text (TEXT and TEXT_SEE_THROUGH) with the entity's
 * gbuffers_entities_translucent and the backgrounds through the TEXT_BG lane. The renderer
 * draws every see-through tag, then every normal one; each call routes on its own.
 */
@Mixin(NameTagFeatureRenderer.class)
public abstract class ChimeraNameTagFeatureRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;"
                    + "FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;"
                    + "Lnet/minecraft/client/gui/Font$DisplayMode;II)V"),
            require = 2)
    private void chimera$packNameTag(
            Font font,
            Component text,
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
            @Local SubmitNodeStorage.NameTagSubmit submit
    ) {
        ChimeraFamilyDraw.emitText(SubmissionIdentity.ofWorldNode(submit), buffers, target ->
                original.call(font, text, x, y, color, dropShadow, pose, target, displayMode,
                        backgroundColor, lightCoords));
    }
}
