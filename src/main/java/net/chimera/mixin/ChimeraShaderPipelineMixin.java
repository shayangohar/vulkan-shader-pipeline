package net.chimera.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The pipeline seam of every immediate and vertex-buffer draw: VulkanMod asks the draw's
 * ShaderInstance for its pipeline (ShaderInstanceM.getPipeline, which this mixin's later priority
 * lets it inject into), binds it, binds its textures and uploads its uniforms. An open pack draw
 * (a family, host lane, sky or cloud draw) answers with its pack pipeline, so the host's state and
 * draw call stay and only the program changes. A widened family mesh without an open draw is
 * skipped: no host program reads its layout.
 */
@Mixin(ShaderInstance.class)
public abstract class ChimeraShaderPipelineMixin {
    @Inject(method = "getPipeline(Lcom/mojang/blaze3d/vertex/VertexFormat;)"
            + "Lnet/vulkanmod/vulkan/shader/GraphicsPipeline;",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void chimera$packPipeline(VertexFormat format, CallbackInfoReturnable<GraphicsPipeline> callback) {
        GraphicsPipeline pack = ChimeraEntityBridge.pipeline();
        if (pack == null) pack = ChimeraSkyBridge.pipeline();
        if (pack != null || ChimeraVertexFormats.isFamilyFormat(format)) callback.setReturnValue(pack);
    }
}
