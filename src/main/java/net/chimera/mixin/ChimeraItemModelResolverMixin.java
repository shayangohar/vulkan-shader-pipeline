package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.RenderedItemIds;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Resolves the Iris item id of a stack while its model appends layers, so each layer it creates
 * carries the id (Iris MixinItemRenderer and ItemStackStateLayerMixin read the same item).
 */
@Mixin(ItemModelResolver.class)
public abstract class ChimeraItemModelResolverMixin {
    @WrapMethod(method = "appendItemLayers")
    private void chimera$resolveItemId(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context,
                                       Level level, ItemOwner owner, int seed, Operation<Void> original) {
        boolean tagged = ChimeraEntityBridge.isInstalled();
        long previous = RenderedItemIds.beginResolving(tagged ? RenderedItemIds.idOf(stack) : 0,
                tagged && RenderedItemIds.isBlockItem(stack));
        try {
            original.call(state, stack, context, level, owner, seed);
        } finally {
            RenderedItemIds.endResolving(previous);
        }
    }
}
