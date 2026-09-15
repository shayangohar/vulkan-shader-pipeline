package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens the hand family adapter only for the vanilla first-person draw. */
@Mixin(ItemInHandRenderer.class)
public abstract class ChimeraItemInHandRendererMixin {
    @Unique
    private boolean chimera$handDrawActive;

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), require = 1)
    private void chimera$beginHands(
            float tickDelta,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            LocalPlayer player,
            int packedLight,
            CallbackInfo callback
    ) {
        if (ChimeraRenderer.segmentsActive()) {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass != null) {
                pass.beginHandSegment();
            }
            chimera$handDrawActive = ChimeraEntityBridge.beginHandDraw();
        }
    }

    @Inject(method = "renderHandsWithItems", at = @At("RETURN"), require = 1)
    private void chimera$endHands(
            float tickDelta,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            LocalPlayer player,
            int packedLight,
            CallbackInfo callback
    ) {
        if (chimera$handDrawActive && ChimeraEntityBridge.isHandDrawActive()) {
            ChimeraEntityBridge.endDraw();
        }
        chimera$handDrawActive = false;
    }
}
