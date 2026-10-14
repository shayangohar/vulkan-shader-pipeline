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
 * Stores the submitting entity's identity on a name tag node, as text nodes do: the name tag
 * renders later, in NameTagFeatureRenderer, through the entity's text family (Iris TEXT).
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
        this.chimera$identity = SubmissionIdentity.current();
    }

    @Override
    public SubmissionIdentity chimera$identity() {
        return chimera$identity;
    }
}
