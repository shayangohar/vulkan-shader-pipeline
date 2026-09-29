package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Stores the world-submission identity on an item node, as model nodes do:
 * an item submitted inside the world entity window (held in third person,
 * dropped, framed) renders later through the pack family of its holder.
 */
@Mixin(SubmitNodeStorage.ItemSubmit.class)
public abstract class ChimeraItemSubmitMixin implements ChimeraEntitySubmission {
    @Unique
    private boolean chimera$worldEntity;
    @Unique
    private int chimera$entityId;
    @Unique
    private int chimera$family;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void chimera$capture(
            PoseStack.Pose pose,
            ItemDisplayContext displayContext,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            int[] tintLayers,
            List<BakedQuad> quads,
            RenderType renderType,
            ItemStackRenderState.FoilType foilType,
            CallbackInfo callback
    ) {
        this.chimera$worldEntity = ChimeraEntityBridge.isSubmittingEntity();
        this.chimera$entityId = ChimeraEntityBridge.currentEntityId();
        this.chimera$family = ChimeraEntityBridge.currentSubmissionFamily();
    }

    @Override
    public boolean chimera$isWorldEntity() {
        return chimera$worldEntity;
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
