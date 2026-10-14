package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.shader.ChimeraItemLayer;
import net.chimera.render.shader.RenderedItemIds;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Iris ItemStackStateLayerMixin: while an item layer submits, its item is the current rendered
 * item, and a block item's blockEntityId is 1. The submit nodes it creates capture both.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public abstract class ChimeraItemLayerMixin implements ChimeraItemLayer {
    @Unique private int chimera$itemId;
    @Unique private boolean chimera$blockItem;

    @Override
    public void chimera$tagItem(int itemId, boolean blockItem) {
        this.chimera$itemId = itemId;
        this.chimera$blockItem = blockItem;
    }

    @WrapMethod(method = "submit")
    private void chimera$submitWithItem(PoseStack poses, SubmitNodeCollector submits, int light, int overlay,
                                        int outline, Operation<Void> original) {
        RenderedItemIds.beginLayer(chimera$itemId, chimera$blockItem);
        try {
            original.call(poses, submits, light, overlay, outline);
        } finally {
            RenderedItemIds.pop();
        }
    }
}
