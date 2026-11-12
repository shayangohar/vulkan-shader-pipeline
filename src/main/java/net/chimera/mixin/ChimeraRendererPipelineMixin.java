package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.PackUniformProvider;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Uploads a pack draw's uniforms under its per-draw state, and binds each pipeline's pack images
 * with its descriptor sets. A family draw uploads with its alpha test, entityId, albedo, lightmap,
 * atlas size and Iris rendering phase; a sky or cloud draw with its albedo and lightmap and the atlas
 * size of its texture, in the phase its LevelRenderer hooks set. Every draw uploads with its unit-0
 * texture size and blend (gtextureSize, blendFunc).
 */
@Mixin(value = Renderer.class, remap = false)
public abstract class ChimeraRendererPipelineMixin {
    @WrapMethod(method = "uploadAndBindUBOs")
    private void chimera$drawState(Pipeline pipeline, Operation<Void> original) {
        if (pipeline == ChimeraSkyBridge.pipeline()) {
            PackUniformProvider.updateDrawAtlasSize(VTextureSelector.getBoundTexture(0));
            PackUniformProvider.updateDrawState(pipeline);
            VulkanImage[] bound = ChimeraSkyBridge.bindLevelSamplers();
            try {
                original.call(pipeline);
            } finally {
                ChimeraSkyBridge.restoreLevelSamplers(bound);
                PackUniformProvider.restoreFrameAtlasSize();
            }
            return;
        }
        if (pipeline != ChimeraEntityBridge.pipeline()) {
            PackUniformProvider.updateDrawState(pipeline);
            original.call(pipeline);
            return;
        }
        PackUniformProvider.updateDrawAlphaReference(ChimeraEntityBridge.alphaReference());
        PackUniformProvider.updateDrawEntityId(ChimeraEntityBridge.hostLayoutEntityId());
        // Iris reads atlasSize and gtextureSize from RenderSystem's shader texture 0; a textureless
        // draw samples its white pixel from another unit (IrisSamplers.addLevelSamplers).
        PackUniformProvider.updateDrawAtlasSize(VTextureSelector.getBoundTexture(0));
        PackUniformProvider.updateDrawState(pipeline);
        // Restored with the family's texture snapshot when the draw ends.
        if (ChimeraEntityBridge.whiteAlbedo()) VTextureSelector.bindTexture(0, VTextureSelector.getWhiteTexture());
        if (ChimeraEntityBridge.whiteLightmap()) VTextureSelector.bindTexture(2, VTextureSelector.getWhiteTexture());
        var previous = PackUniformProvider.setRenderingPhase(ChimeraEntityBridge.renderingPhase());
        try {
            original.call(pipeline);
        } finally {
            PackUniformProvider.setRenderingPhase(previous);
            PackUniformProvider.restoreFrameAtlasSize();
        }
    }

    @SuppressWarnings("try") // the binding scope restores descriptor state on close
    @WrapOperation(method = "uploadAndBindUBOs", at = @At(value = "INVOKE",
            target = "Lnet/vulkanmod/vulkan/shader/Pipeline;bindDescriptorSets(Lorg/lwjgl/vulkan/VkCommandBuffer;I)V"),
                    require = 1)
    private void chimera$bindImages(Pipeline pipeline, VkCommandBuffer commandBuffer, int frame,
                                    Operation<Void> original) {
        var pass = ChimeraRenderer.getMainPass();
        if (pass == null || !(pipeline instanceof GraphicsPipeline graphics)) {
            original.call(pipeline, commandBuffer, frame);
            return;
        }
        try (var bindings = pass.bindDescriptorImages(graphics)) {
            original.call(pipeline, commandBuffer, frame);
        }
    }
}
