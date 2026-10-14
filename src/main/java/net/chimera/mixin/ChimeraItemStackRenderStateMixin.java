package net.chimera.mixin;

import net.chimera.render.shader.ChimeraItemLayer;
import net.chimera.render.shader.RenderedItemIds;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tags each layer an item model creates, pooled layers included, with the stack's Iris item id. */
@Mixin(ItemStackRenderState.class)
public abstract class ChimeraItemStackRenderStateMixin {
    @Inject(method = "newLayer", at = @At("RETURN"), require = 1)
    private void chimera$tagLayer(CallbackInfoReturnable<ItemStackRenderState.LayerRenderState> callback) {
        ((ChimeraItemLayer) callback.getReturnValue()).chimera$tagItem(
                RenderedItemIds.resolvingItem(), RenderedItemIds.resolvingBlockItem());
    }
}
