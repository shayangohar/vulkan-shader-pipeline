package net.chimera.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.vertex.ChimeraVertexFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes host entity uploads use the append-only format only in the guarded draw window. */
@Mixin(RenderPipeline.class)
public abstract class ChimeraRenderPipelineMixin {
    @Inject(method = "getVertexFormat", at = @At("RETURN"), cancellable = true, require = 1)
    private void chimera$entityFormat(CallbackInfoReturnable<VertexFormat> callback) {
        if (ChimeraEntityBridge.isDrawActive()
                && callback.getReturnValue() == DefaultVertexFormat.NEW_ENTITY) {
            callback.setReturnValue(ChimeraVertexFormats.EXTENDED_ENTITY);
        }
    }
}
