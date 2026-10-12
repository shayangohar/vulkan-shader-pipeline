package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.blockentity.TheEndGatewayRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Iris MixinTheEndGatewayRenderer: the gateway overrides renderType, so it needs the same swap. */
@Mixin(TheEndGatewayRenderer.class)
public abstract class ChimeraEndGatewayRendererMixin {
    @Inject(method = "renderType", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$renderType(CallbackInfoReturnable<RenderType> callback) {
        if (ChimeraRenderer.packProjectionActive()) {
            callback.setReturnValue(RenderTypes.entitySolid(AbstractEndPortalRenderer.END_PORTAL_LOCATION));
        }
    }
}
