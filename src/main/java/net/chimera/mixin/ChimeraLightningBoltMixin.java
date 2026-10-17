package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.chimera.render.shader.LightningBoltRenderType;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Draws the lightning bolt with LightningBoltRenderType, as Iris swaps in its own lightning type. */
@Mixin(LightningBoltRenderer.class)
public abstract class ChimeraLightningBoltMixin {
    @WrapOperation(method = "render(Lnet/minecraft/world/entity/LightningBolt;FFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderType;lightning()Lnet/minecraft/client/renderer/RenderType;"),
            require = 1)
    private RenderType chimera$boltType(Operation<RenderType> original) {
        return LightningBoltRenderType.INSTANCE;
    }
}
