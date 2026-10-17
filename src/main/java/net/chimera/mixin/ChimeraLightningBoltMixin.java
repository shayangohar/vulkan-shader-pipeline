package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.MeshData;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Brackets the lightning bolt's batch, as Iris 1.21.1 wraps LightningBoltRenderer's
 * RenderType.lightning() in its LightningRenderStateShard: the lightning lanes read the pack's
 * lightning_bolt id there. Dragon rays, which share those lanes, draw with their own render type
 * and keep 0.
 */
@Mixin(RenderType.class)
public abstract class ChimeraLightningBoltMixin {
    @WrapMethod(method = "draw")
    private void chimera$lightningBolt(MeshData mesh, Operation<Void> original) {
        if ((Object) this != RenderType.lightning()) {
            original.call(mesh);
            return;
        }
        ChimeraEntityBridge.setLightningBoltDraw(true);
        try {
            original.call(mesh);
        } finally {
            ChimeraEntityBridge.setLightningBoltDraw(false);
        }
    }
}
