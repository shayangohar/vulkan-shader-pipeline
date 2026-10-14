package net.chimera.mixin;

import net.chimera.render.shader.ChimeraEntitySubmission;
import net.chimera.render.shader.SubmissionIdentity;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks a name tag a world entity submitted: it renders later, in NameTagFeatureRenderer, as
 * Iris TEXT. Unlike a text node it keeps none of the submitter's ids (SubmissionIdentity.NAME_TAG).
 */
@Mixin(SubmitNodeStorage.NameTagSubmit.class)
public abstract class ChimeraNameTagSubmitMixin implements ChimeraEntitySubmission {
    @Unique
    private SubmissionIdentity chimera$identity;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void chimera$capture(
            Matrix4f pose,
            float x,
            float y,
            Component text,
            int lightCoords,
            int color,
            int backgroundColor,
            double distanceToCameraSq,
            CallbackInfo callback
    ) {
        this.chimera$identity = SubmissionIdentity.current().worldEntity() ? SubmissionIdentity.NAME_TAG : null;
    }

    @Override
    public SubmissionIdentity chimera$identity() {
        return chimera$identity;
    }
}
