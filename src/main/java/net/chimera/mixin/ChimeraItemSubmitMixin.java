package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.chimera.render.shader.SubmissionIdentity;
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
    private SubmissionIdentity chimera$identity;

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
        this.chimera$identity = SubmissionIdentity.current();
    }

    @Override
    public SubmissionIdentity chimera$identity() {
        return chimera$identity;
    }
}
