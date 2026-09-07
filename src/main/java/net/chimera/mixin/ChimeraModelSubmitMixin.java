package net.chimera.mixin;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeStorage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stores the delayed world-entity identity on the submission node. */
@Mixin(SubmitNodeStorage.ModelSubmit.class)
public abstract class ChimeraModelSubmitMixin implements ChimeraEntitySubmission {
    @Unique
    private boolean chimera$worldEntity;
    @Unique
    private int chimera$entityId;
    @Unique
    private int chimera$family;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void chimera$capture(
            PoseStack.Pose pose,
            Model model,
            Object state,
            int lightCoords,
            int overlayCoords,
            int tintedColor,
            TextureAtlasSprite sprite,
            int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
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
