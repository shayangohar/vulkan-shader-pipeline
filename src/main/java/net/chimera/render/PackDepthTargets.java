package net.chimera.render;

import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.shaderpack.DepthGraphPlan;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;

/** Owns converted pack-visible depth snapshots for one pack variant. */
public final class PackDepthTargets {
    private final VulkanImage[] images = new VulkanImage[3];
    private final Framebuffer[] framebuffers = new Framebuffer[3];
    private final RenderPass[] renderPasses = new RenderPass[3];
    private final boolean[] valid = new boolean[3];
    private DepthGraphPlan plan = DepthGraphPlan.empty();
    private GraphicsPipeline conversionPipeline;
    private boolean configured;

    public boolean configure(int width, int height, DepthGraphPlan plan) {
        cleanUp();
        this.plan = plan == null ? DepthGraphPlan.empty() : plan;
        if (!this.plan.any()) return false;
        java.util.Arrays.fill(this.valid, false);
        MrtPipelineContext.begin(new int[] {this.plan.format()}, 1);
        try {
            this.conversionPipeline = ChimeraPostPipelines.createDepthPipeline();
        } finally {
            MrtPipelineContext.end();
        }
        for (int index = 0; index < images.length; index++) {
            if (!required(index)) continue;
            this.images[index] = VulkanImage.builder(Math.max(1, width), Math.max(1, height))
                    .setName("chimeraPackDepthTex" + index)
                    .setFormat(this.plan.format())
                    .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .setLinearFiltering(true)
                    .setClamp(true)
                    .createVulkanImage();
            this.framebuffers[index] = Framebuffer.builder(this.images[index], null).build();
            RenderPass.Builder builder = RenderPass.builder(this.framebuffers[index]);
            builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR,
                    VK_ATTACHMENT_STORE_OP_STORE);
            this.renderPasses[index] = builder.build();
        }
        this.configured = this.conversionPipeline != null;
        return this.configured;
    }

    public boolean captureScene(VkCommandBuffer commandBuffer, VulkanImage rawDepth) {
        if (!this.configured || rawDepth == null) return false;
        for (int index = 0; index < images.length; index++) {
            if (required(index) && !convert(commandBuffer, rawDepth, index, false)) return false;
        }
        return true;
    }

    /** Captures one seam-specific depth snapshot and restores the source layout. */
    public boolean captureOpaque(VkCommandBuffer commandBuffer, VulkanImage rawDepth) {
        return configured && rawDepth != null && required(1)
                && convert(commandBuffer, rawDepth, 1, true);
    }

    private boolean convert(
            VkCommandBuffer commandBuffer,
            VulkanImage rawDepth,
            int index,
            boolean restoreDepthAttachment
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            rawDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            images[index].transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VTextureSelector.bindTexture(6, rawDepth);
            Renderer renderer = Renderer.getInstance();
            renderer.beginRenderPass(renderPasses[index], framebuffers[index]);
            Renderer.setViewport(0, 0, images[index].width, images[index].height, stack);
            renderer.bindGraphicsPipeline(this.conversionPipeline);
            renderer.uploadAndBindUBOs(this.conversionPipeline);
            vkCmdDraw(commandBuffer, 3, 1, 0, 0);
            renderer.endRenderPass(commandBuffer);
            images[index].transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (restoreDepthAttachment) {
                rawDepth.transitionImageLayout(stack, commandBuffer,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }
            valid[index] = true;
            renderer.setBoundRenderPass(null);
            renderer.setBoundFramebuffer(null);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    public VulkanImage image(String name) {
        return switch (name) {
            case "depthtex0" -> valid[0] ? images[0] : null;
            case "depthtex1" -> valid[1] ? images[1] : null;
            case "depthtex2" -> valid[2] ? images[2] : null;
            default -> null;
        };
    }

    public boolean isConfigured() { return configured; }
    public DepthGraphPlan plan() { return plan; }

    public void cleanUp() {
        for (RenderPass pass : renderPasses) if (pass != null) pass.cleanUp();
        for (Framebuffer framebuffer : framebuffers) if (framebuffer != null) framebuffer.cleanUp(false);
        for (VulkanImage image : images) if (image != null) image.free();
        if (conversionPipeline != null) conversionPipeline.cleanUp();
        java.util.Arrays.fill(renderPasses, null);
        java.util.Arrays.fill(framebuffers, null);
        java.util.Arrays.fill(images, null);
        java.util.Arrays.fill(valid, false);
        conversionPipeline = null;
        configured = false;
        plan = DepthGraphPlan.empty();
    }

    private boolean required(int index) {
        return switch (index) {
            case 0 -> plan.depthtex0();
            case 1 -> plan.depthtex1();
            case 2 -> plan.depthtex2();
            default -> false;
        };
    }
}
