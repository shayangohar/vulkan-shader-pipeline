package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.level.block.state.BlockState;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.minecraft.client.renderer.SubmitNodeStorage;
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
    private boolean chimera$worldEntity;
    @Unique
    private int chimera$entityId;
    @Unique
    private int chimera$blockEntityId;
    @Unique
    private int chimera$family;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void chimera$capture(
            PoseStack.Pose pose,
            BlockState state,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            CallbackInfo callback
    ) {
        this.chimera$worldEntity = ChimeraEntityBridge.isSubmittingEntity();
        this.chimera$entityId = ChimeraEntityBridge.currentEntityId();
        this.chimera$blockEntityId = ChimeraEntityBridge.currentBlockEntityId();
        this.chimera$family = ChimeraEntityBridge.currentSubmissionFamily();
    }

    @Override
    public boolean chimera$isWorldEntity() {
        return chimera$worldEntity;
    }

    @Override
    public int chimera$blockEntityId() {
        return chimera$blockEntityId;
    }

    @Override
    public int chimera$entityId() {
        return chimera$entityId;
    }

    @Override
    public int chimera$family() {
        return chimera$family;
    }
}
