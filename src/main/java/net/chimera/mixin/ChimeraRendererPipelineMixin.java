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
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "uploadAndBindUBOs")
    private void chimera$cloudPhase(net.vulkanmod.vulkan.shader.Pipeline pipeline,
                                   com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        if (!ChimeraSkyBridge.isCloudPipeline(pipeline)) {
            original.call(pipeline);
            return;
        }
        var previous = net.chimera.render.shader.PackUniformProvider.setRenderingPhase(net.chimera.shaderpack.PackRenderingPhase.CLOUDS);
        try {
            original.call(pipeline);
        } finally {
            net.chimera.render.shader.PackUniformProvider.setRenderingPhase(previous);
        }
    }
    @ModifyVariable(method = "bindGraphicsPipeline", at = @At("HEAD"), argsOnly = true, require = 1)
    private GraphicsPipeline chimera$replaceCloudPipeline(GraphicsPipeline host) {
        return ChimeraSkyBridge.replaceCloudPipeline(host);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "uploadAndBindUBOs", at = @At(value = "INVOKE",
            target = "Lnet/vulkanmod/vulkan/shader/Pipeline;bindDescriptorSets(Lorg/lwjgl/vulkan/VkCommandBuffer;I)V"), require = 1)
    private void chimera$bindImages(net.vulkanmod.vulkan.shader.Pipeline pipeline,
            org.lwjgl.vulkan.VkCommandBuffer commandBuffer, int frame) {
        var pass = net.chimera.render.ChimeraRenderer.getMainPass();
        if (pass == null || !(pipeline instanceof GraphicsPipeline graphics)) {
            pipeline.bindDescriptorSets(commandBuffer, frame);
            return;
        }
        try (var bindings = pass.bindDescriptorImages(graphics)) {
            pipeline.bindDescriptorSets(commandBuffer, frame);
        }
    }
}
