package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.chimera.render.shader.SubmissionIdentity;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stores the world-submission identity on a BlockSubmit (falling blocks, TNT, minecart contents), as text,
 * item and model nodes do, so it renders later through the pack family Iris gives it.
 */
@Mixin(SubmitNodeStorage.BlockSubmit.class)
public abstract class ChimeraBlockSubmitMixin implements ChimeraEntitySubmission {
    @Unique
    private SubmissionIdentity chimera$identity;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void chimera$capture(
            PoseStack.Pose pose,
            BlockState state,
            int lightCoords,
            int overlayCoords,
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
