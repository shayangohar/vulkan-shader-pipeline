package net.chimera.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the temporary entity buffer builder and mesh format in lockstep. */
@Mixin(RenderType.class)
public abstract class ChimeraRenderTypeMixin {
    @Inject(method = "draw", at = @At("HEAD"), require = 1)
    private void chimera$traceEntityMesh(MeshData mesh, CallbackInfo callback) {
        if (ChimeraEntityBridge.isDrawActive() && mesh != null) {
            MeshData.DrawState state = mesh.drawState();
            ChimeraEntityBridge.noteMeshDraw(state.vertexCount(), state.indexCount(),
                    state.format().getVertexSize());
        }
    }

    @Inject(method = "format", at = @At("RETURN"), cancellable = true, require = 1)
    private void chimera$entityFormat(CallbackInfoReturnable<VertexFormat> callback) {
        if (ChimeraEntityBridge.shouldExtendEntityFormat(callback.getReturnValue())) {
            callback.setReturnValue(ChimeraEntityBridge.extendedFormat(callback.getReturnValue()));
        }
    }
}
