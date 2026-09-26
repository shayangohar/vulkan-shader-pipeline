package net.chimera.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.chimera.render.ChimeraRasterOrientation;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * VulkanMod bakes a counter-clockwise front face into every pipeline. A GL
 * row-order pass mirrors the image, so pipelines created for it take the
 * mirrored face. PipelineState is keyed by RenderPass, so the host and GL
 * variants of one pipeline never share a cache entry.
 */
@Mixin(value = GraphicsPipeline.class, remap = false)
public abstract class ChimeraPipelineFrontFaceMixin {
    @ModifyArg(method = "createGraphicsPipeline", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkPipelineRasterizationStateCreateInfo;frontFace(I)"
                    + "Lorg/lwjgl/vulkan/VkPipelineRasterizationStateCreateInfo;"), require = 1)
    private int chimera$orientedFrontFace(int hostFrontFace,
                                          @Local(argsOnly = true) PipelineState state) {
        return ChimeraRasterOrientation.frontFace(
                ((ChimeraPipelineStateAccessor) state).chimera$renderPass(), hostFrontFace);
    }
}
