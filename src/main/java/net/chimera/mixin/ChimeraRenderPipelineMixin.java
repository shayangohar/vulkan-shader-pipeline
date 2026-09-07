package net.chimera.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.ChimeraEntityBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes guarded family uploads use their append-only format only in the draw window. */
@Mixin(RenderPipeline.class)
public abstract class ChimeraRenderPipelineMixin {
    @Inject(method = "getVertexFormat", at = @At("RETURN"), cancellable = true, require = 1)
    private void chimera$entityFormat(CallbackInfoReturnable<VertexFormat> callback) {
        if (ChimeraEntityBridge.shouldExtendEntityFormat(callback.getReturnValue())) {
            callback.setReturnValue(ChimeraEntityBridge.extendedFormat(callback.getReturnValue()));
        }
    }
}
