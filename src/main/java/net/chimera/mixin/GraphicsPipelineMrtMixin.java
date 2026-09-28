package net.chimera.mixin;

import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.shaderpack.PackBlendPlan;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;

/**
 * Extends only Chimera's dynamic post pipeline variants to the number of
 * declared color attachments, with the pack's per-attachment blend
 * directives. Normal VulkanMod pipelines keep their original
 * single-attachment behavior because the context is inactive for them.
 */
@Mixin(value = GraphicsPipeline.class, remap = false)
public abstract class GraphicsPipelineMrtMixin {
    @Inject(method = "createGraphicsPipeline", at = @At("HEAD"))
    private void chimera$beginRegisteredMrt(
            PipelineState state,
            CallbackInfoReturnable<Long> callback
    ) {
        MrtPipelineContext.beginPipeline((GraphicsPipeline) (Object) this);
    }

    @Inject(method = "createGraphicsPipeline", at = @At("RETURN"))
    private void chimera$endRegisteredMrt(
            PipelineState state,
            CallbackInfoReturnable<Long> callback
    ) {
        MrtPipelineContext.endPipeline();
    }

    @Inject(method = "cleanUp", at = @At("HEAD"))
    private void chimera$unregisterMrt(CallbackInfo callback) {
        MrtPipelineContext.unregister((GraphicsPipeline) (Object) this);
    }

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
        if (MrtPipelineContext.colorFormats() == null) {
            return attachments;
        }
        PackBlendPlan.Mode[] blends = MrtPipelineContext.attachmentBlends();
        if (attachments.capacity() < 2 && blends == null) {
            return attachments;
        }
        // Every attachment starts from the host draw's blend (the vanilla
        // render type's, as in Iris), then takes the pack's blend.<program>
        // or blend.<program>.<buffer> mode where one is declared.
        VkPipelineColorBlendAttachmentState host = attachments.get(0);
        int writeMask = host.colorWriteMask();
        boolean enabled = host.blendEnable();
        int srcColor = host.srcColorBlendFactor();
        int dstColor = host.dstColorBlendFactor();
        int colorOp = host.colorBlendOp();
        int srcAlpha = host.srcAlphaBlendFactor();
        int dstAlpha = host.dstAlphaBlendFactor();
        int alphaOp = host.alphaBlendOp();
        for (int i = 0; i < attachments.capacity(); i++) {
            VkPipelineColorBlendAttachmentState target = attachments.get(i);
            PackBlendPlan.Mode mode = blends == null || i >= blends.length ? null : blends[i];
            target.colorWriteMask(writeMask);
            if (mode == null) {
                target.blendEnable(enabled);
                target.srcColorBlendFactor(srcColor);
                target.dstColorBlendFactor(dstColor);
                target.colorBlendOp(colorOp);
                target.srcAlphaBlendFactor(srcAlpha);
                target.dstAlphaBlendFactor(dstAlpha);
                target.alphaBlendOp(alphaOp);
            } else {
                target.blendEnable(mode.enabled());
                target.srcColorBlendFactor(mode.srcColor());
                target.dstColorBlendFactor(mode.dstColor());
                target.colorBlendOp(VK_BLEND_OP_ADD);
                target.srcAlphaBlendFactor(mode.srcAlpha());
                target.dstAlphaBlendFactor(mode.dstAlpha());
                target.alphaBlendOp(VK_BLEND_OP_ADD);
            }
        }
        return attachments;
    }
}
