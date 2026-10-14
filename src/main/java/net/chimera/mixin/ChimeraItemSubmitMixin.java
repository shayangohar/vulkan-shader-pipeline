package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.chimera.render.shader.ChimeraItemSubmission;
import net.chimera.render.shader.RenderedItemIds;
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
 * Every item node, first-person ones included, also keeps the rendered item
 * and block entity ids current at submission (Iris MixinItemSubmit).
 */
@Mixin(SubmitNodeStorage.ItemSubmit.class)
public abstract class ChimeraItemSubmitMixin implements ChimeraEntitySubmission, ChimeraItemSubmission {
    @Unique
    private SubmissionIdentity chimera$identity;
    @Unique
    private int chimera$itemId;
    @Unique
    private int chimera$blockEntityId;

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
        this.chimera$itemId = RenderedItemIds.current();
        this.chimera$blockEntityId = ChimeraEntityBridge.currentBlockEntityId();
    }

    @Override
    public SubmissionIdentity chimera$identity() {
        return chimera$identity;
    }

    @Override
    public int chimera$itemId() {
        return chimera$itemId;
    }

    @Override
    public int chimera$blockEntityId() {
        return chimera$blockEntityId;
    }
}
