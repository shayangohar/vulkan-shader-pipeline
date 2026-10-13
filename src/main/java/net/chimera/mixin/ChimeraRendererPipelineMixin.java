package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.PackRenderingPhase;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Routes VulkanMod's direct CloudRenderer pipeline bind through Chimera. */
@Mixin(value = Renderer.class, remap = false)
public abstract class ChimeraRendererPipelineMixin {
    @WrapMethod(method = "uploadAndBindUBOs")
    private void chimera$cloudPhase(Pipeline pipeline,
                                   Operation<Void> original) {
        if (!ChimeraSkyBridge.isCloudPipeline(pipeline)) {
            original.call(pipeline);
            return;
        }
        var previous = PackUniformProvider.setRenderingPhase(PackRenderingPhase.CLOUDS);
        try {
            original.call(pipeline);
        } finally {
            PackUniformProvider.setRenderingPhase(previous);
        }
    }
    @ModifyVariable(method = "bindGraphicsPipeline", at = @At("HEAD"), argsOnly = true, require = 1)
    private GraphicsPipeline chimera$replaceCloudPipeline(GraphicsPipeline host) {
        return ChimeraSkyBridge.replaceCloudPipeline(host);
    }

    @SuppressWarnings("try") // the binding scope restores descriptor state on close
    @Redirect(method = "uploadAndBindUBOs", at = @At(value = "INVOKE",
            target = "Lnet/vulkanmod/vulkan/shader/Pipeline;bindDescriptorSets(Lorg/lwjgl/vulkan/VkCommandBuffer;I)V"), require = 1)
    private void chimera$bindImages(Pipeline pipeline,
            VkCommandBuffer commandBuffer, int frame) {
        var pass = ChimeraRenderer.getMainPass();
        if (pass == null || !(pipeline instanceof GraphicsPipeline graphics)) {
            pipeline.bindDescriptorSets(commandBuffer, frame);
            return;
        }
        try (var bindings = pass.bindDescriptorImages(graphics)) {
            pipeline.bindDescriptorSets(commandBuffer, frame);
        }
    }
}
