package net.chimera.mixin;

import net.chimera.render.shader.MrtPipelineContext;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackGet;

/**
 * Extends only Chimera's dynamic post pipeline variants to the number of
 * declared color attachments. Normal VulkanMod pipelines keep their original
 * single-attachment behavior because the context is inactive for them.
 */
@Mixin(value = GraphicsPipeline.class, remap = false)
public abstract class GraphicsPipelineMrtMixin {
    @ModifyArg(
            method = "createGraphicsPipeline",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkPipelineRenderingCreateInfoKHR;pColorAttachmentFormats(Ljava/nio/IntBuffer;)Lorg/lwjgl/vulkan/VkPipelineRenderingCreateInfoKHR;"
            ),
            index = 0
    )
    private IntBuffer chimera$colorAttachmentFormats(IntBuffer fallback) {
        int[] formats = MrtPipelineContext.colorFormats();
        return formats == null ? fallback : stackGet().ints(formats);
    }

    @Redirect(
            method = "createGraphicsPipeline",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState;calloc(ILorg/lwjgl/system/MemoryStack;)Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState$Buffer;"
            )
    )
    private static VkPipelineColorBlendAttachmentState.Buffer chimera$colorBlendAttachments(
            int fallback,
            MemoryStack stack
    ) {
        return VkPipelineColorBlendAttachmentState.calloc(
                MrtPipelineContext.attachmentCount(fallback), stack);
    }

    @ModifyArg(
            method = "createGraphicsPipeline",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkPipelineColorBlendStateCreateInfo;pAttachments(Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState$Buffer;)Lorg/lwjgl/vulkan/VkPipelineColorBlendStateCreateInfo;"
            ),
            index = 0
    )
    private VkPipelineColorBlendAttachmentState.Buffer chimera$copyBlendState(
            VkPipelineColorBlendAttachmentState.Buffer attachments
    ) {
        if (MrtPipelineContext.colorFormats() == null || attachments.capacity() < 2) {
            return attachments;
        }
        VkPipelineColorBlendAttachmentState source = attachments.get(0);
        for (int i = 1; i < attachments.capacity(); i++) {
            VkPipelineColorBlendAttachmentState target = attachments.get(i);
            target.colorWriteMask(source.colorWriteMask());
            target.blendEnable(source.blendEnable());
            target.srcColorBlendFactor(source.srcColorBlendFactor());
            target.dstColorBlendFactor(source.dstColorBlendFactor());
            target.colorBlendOp(source.colorBlendOp());
            target.srcAlphaBlendFactor(source.srcAlphaBlendFactor());
            target.dstAlphaBlendFactor(source.dstAlphaBlendFactor());
            target.alphaBlendOp(source.alphaBlendOp());
        }
        return attachments;
    }
}
