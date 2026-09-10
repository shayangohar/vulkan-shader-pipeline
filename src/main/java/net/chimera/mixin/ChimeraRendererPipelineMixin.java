package net.chimera.mixin;

import net.chimera.render.shader.ChimeraSkyBridge;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Routes VulkanMod's direct CloudRenderer pipeline bind through Chimera. */
@Mixin(value = Renderer.class, remap = false)
public abstract class ChimeraRendererPipelineMixin {
    @ModifyVariable(method = "bindGraphicsPipeline", at = @At("HEAD"), argsOnly = true, require = 1)
    private GraphicsPipeline chimera$replaceCloudPipeline(GraphicsPipeline host) {
        return ChimeraSkyBridge.replaceCloudPipeline(host);
    }
}
