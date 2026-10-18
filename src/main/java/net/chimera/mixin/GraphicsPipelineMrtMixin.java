package net.chimera.mixin;

import net.chimera.render.ChimeraRenderingPass;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.shaderpack.PackBlendPlan;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

/**
 * Pipelines for Chimera's dynamic-rendering windows. A variant created while a
 * {@link ChimeraRenderingPass} is bound renders without a Vulkan render pass, into the window's
 * color and depth formats, with one blend attachment per color attachment: each starts from the
 * host draw's blend (the vanilla render type's, as in Iris) and takes the pack's blend directive
 * for that buffer where the pipeline's MRT contract declares one. Variants for VulkanMod's own
 * render passes are untouched.
 */
@Mixin(value = GraphicsPipeline.class, remap = false)
public abstract class GraphicsPipelineMrtMixin {
    /** The window the variant being created renders into; null for a VulkanMod render pass. */
    @Unique
    private ChimeraRenderingPass chimera$window;

    @Inject(method = "createGraphicsPipeline", at = @At("HEAD"))
    private void chimera$beginVariant(PipelineState state, CallbackInfoReturnable<Long> callback) {
        this.chimera$window = ((ChimeraPipelineStateAccessor) state).chimera$renderPass()
                instanceof ChimeraRenderingPass window ? window : null;
        MrtPipelineContext.beginPipeline((GraphicsPipeline) (Object) this);
    }

    @Inject(method = "createGraphicsPipeline", at = @At("RETURN"))
    private void chimera$endVariant(PipelineState state, CallbackInfoReturnable<Long> callback) {
        MrtPipelineContext.endPipeline();
        this.chimera$window = null;
    }

    @Inject(method = "cleanUp", at = @At("HEAD"))
    private void chimera$unregisterMrt(CallbackInfo callback) {
        MrtPipelineContext.unregister((GraphicsPipeline) (Object) this);
    }

    /** A window variant has no render pass. */
    @Redirect(method = "createGraphicsPipeline",
            at = @At(value = "INVOKE", target = "Lnet/vulkanmod/vulkan/framebuffer/RenderPass;getId()J"))
    private long chimera$renderPass(RenderPass renderPass) {
        return this.chimera$window != null ? VK_NULL_HANDLE : renderPass.getId();
    }

    @Redirect(method = "createGraphicsPipeline",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState;"
                    + "calloc(ILorg/lwjgl/system/MemoryStack;)"
                    + "Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState$Buffer;"))
    private VkPipelineColorBlendAttachmentState.Buffer chimera$colorBlendAttachments(int hostCount,
                                                                                      MemoryStack stack) {
        int count = this.chimera$window == null ? hostCount : this.chimera$window.colorFormats().length;
        return VkPipelineColorBlendAttachmentState.calloc(count, stack);
    }

    @ModifyArg(method = "createGraphicsPipeline",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkPipelineColorBlendStateCreateInfo;"
                    + "pAttachments(Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState$Buffer;)"
                    + "Lorg/lwjgl/vulkan/VkPipelineColorBlendStateCreateInfo;"))
    private VkPipelineColorBlendAttachmentState.Buffer chimera$copyBlendState(
            VkPipelineColorBlendAttachmentState.Buffer attachments) {
        if (this.chimera$window == null) return attachments;
        PackBlendPlan.Mode[] blends = MrtPipelineContext.attachmentBlends();
        // A contract for another attachment set does not describe this window.
        if (blends != null && blends.length != attachments.capacity()) blends = null;
        VkPipelineColorBlendAttachmentState host = attachments.get(0);
        for (int index = 0; index < attachments.capacity(); index++) {
            VkPipelineColorBlendAttachmentState target = attachments.get(index);
            PackBlendPlan.Mode mode = blends == null ? null : blends[index];
            target.colorWriteMask(host.colorWriteMask());
            if (mode == null) {
                target.blendEnable(host.blendEnable())
                        .srcColorBlendFactor(host.srcColorBlendFactor())
                        .dstColorBlendFactor(host.dstColorBlendFactor())
                        .colorBlendOp(host.colorBlendOp())
                        .srcAlphaBlendFactor(host.srcAlphaBlendFactor())
                        .dstAlphaBlendFactor(host.dstAlphaBlendFactor())
                        .alphaBlendOp(host.alphaBlendOp());
            } else {
                target.blendEnable(mode.enabled())
                        .srcColorBlendFactor(mode.srcColor())
                        .dstColorBlendFactor(mode.dstColor())
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(mode.srcAlpha())
                        .dstAlphaBlendFactor(mode.dstAlpha())
                        .alphaBlendOp(VK_BLEND_OP_ADD);
            }
        }
        return attachments;
    }

    /** Names the window's attachment formats in place of the render pass. */
    @ModifyArg(method = "createGraphicsPipeline",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK10;vkCreateGraphicsPipelines("
                    + "Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;"
                    + "Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
            index = 2)
    private VkGraphicsPipelineCreateInfo.Buffer chimera$renderingFormats(VkGraphicsPipelineCreateInfo.Buffer infos) {
        if (this.chimera$window == null) return infos;
        MemoryStack stack = stackGet();
        int depth = this.chimera$window.depthFormat();
        VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)
                .pColorAttachmentFormats(stack.ints(this.chimera$window.colorFormats()))
                .depthAttachmentFormat(depth == 0 ? VK_FORMAT_UNDEFINED : depth)
                .stencilAttachmentFormat(VulkanImage.hasStencilComponent(depth) ? depth : VK_FORMAT_UNDEFINED);
        infos.get(0).pNext(rendering.address());
        return infos;
    }
}
