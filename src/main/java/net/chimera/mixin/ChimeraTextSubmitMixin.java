package net.chimera.mixin;

import net.chimera.render.shader.ChimeraEntitySubmission;
import net.chimera.render.shader.SubmissionIdentity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stores the world-submission identity on a text node, as item and model nodes
 * do: sign text submitted by a block entity, or text submitted by an entity,
 * renders later through the pack family Iris gives it.
 */
@Mixin(SubmitNodeStorage.TextSubmit.class)
public abstract class ChimeraTextSubmitMixin implements ChimeraEntitySubmission {
    @Unique
    private SubmissionIdentity chimera$identity;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void chimera$capture(
            Matrix4f pose,
            float x,
            float y,
            FormattedCharSequence string,
            boolean dropShadow,
            Font.DisplayMode displayMode,
            int lightCoords,
            int color,
            int backgroundColor,
            int outlineColor,
            CallbackInfo callback
    ) {
        this.chimera$identity = SubmissionIdentity.current();
    }

    @Override
    public SubmissionIdentity chimera$identity() {
        return chimera$identity;
    }
}
