package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.shader.RenderedItemIds;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris MixinItemRenderer: while an item renders, its id is the current rendered item, and a block
 * item's blockEntityId is 1.
 */
@Mixin(ItemRenderer.class)
public abstract class ChimeraItemRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private void chimera$beginItem(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poses,
                                   MultiBufferSource buffers, int light, int overlay, BakedModel model,
                                   CallbackInfo ci) {
        RenderedItemIds.beginItem(stack);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void chimera$endItem(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poses,
                                 MultiBufferSource buffers, int light, int overlay, BakedModel model,
                                 CallbackInfo ci) {
        RenderedItemIds.pop();
    }
}
