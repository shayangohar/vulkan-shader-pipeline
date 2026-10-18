package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.PackUniformProvider;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Uploads a pack draw's uniforms under its per-draw state, and binds each pipeline's pack images
 * with its descriptor sets. A family draw uploads with its alpha test, entityId, albedo, atlas size
 * and Iris rendering phase; a sky or cloud draw with the atlas size of its texture, in the phase its
 * LevelRenderer hooks set.
 */
@Mixin(value = Renderer.class, remap = false)
public abstract class ChimeraRendererPipelineMixin {
    @WrapMethod(method = "uploadAndBindUBOs")
    private void chimera$drawState(Pipeline pipeline, Operation<Void> original) {
        if (pipeline == ChimeraSkyBridge.pipeline()) {
            PackUniformProvider.updateDrawAtlasSize(VTextureSelector.getBoundTexture(0));
            try {
                original.call(pipeline);
            } finally {
                PackUniformProvider.restoreFrameAtlasSize();
            }
            return;
        }
        if (pipeline != ChimeraEntityBridge.pipeline()) {
            original.call(pipeline);
            return;
        }
        PackUniformProvider.updateDrawAlphaReference(ChimeraEntityBridge.alphaReference());
        PackUniformProvider.updateDrawEntityId(ChimeraEntityBridge.hostLayoutEntityId());
        // Restored with the family's texture snapshot when the draw ends.
        if (ChimeraEntityBridge.whiteAlbedo()) VTextureSelector.bindTexture(0, VTextureSelector.getWhiteTexture());
        PackUniformProvider.updateDrawAtlasSize(VTextureSelector.getBoundTexture(0));
        var previous = PackUniformProvider.setRenderingPhase(ChimeraEntityBridge.renderingPhase());
        try {
            original.call(pipeline);
        } finally {
            PackUniformProvider.setRenderingPhase(previous);
            PackUniformProvider.restoreFrameAtlasSize();
        }
    }

    @SuppressWarnings("try") // the binding scope restores descriptor state on close
    @Redirect(method = "uploadAndBindUBOs", at = @At(value = "INVOKE",
            target = "Lnet/vulkanmod/vulkan/shader/Pipeline;bindDescriptorSets(Lorg/lwjgl/vulkan/VkCommandBuffer;I)V"),
                    require = 1)
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
