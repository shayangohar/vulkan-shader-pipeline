package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraHandRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Iris partitions submitted arms, not individual translucent render types. */
@Mixin(ItemInHandRenderer.class)
public abstract class ChimeraItemInHandRendererMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$partitionHand(AbstractClientPlayer player, float tick, float pitch,
                                      InteractionHand hand, float swing, ItemStack item, float equip,
                                      PoseStack poses, SubmitNodeCollector submits, int light, CallbackInfo callback) {
        if (ChimeraHandRenderer.skip(item)) callback.cancel();
    }
}
