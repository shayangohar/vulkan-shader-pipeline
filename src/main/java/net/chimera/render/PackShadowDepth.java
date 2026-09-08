package net.chimera.render;

import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.MrtPipelineContext;
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
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;

/** Converts the engine shadow depth into one pack-readable shadowtex0 image. */
public final class PackShadowDepth {
    private VulkanImage image;
    private Framebuffer framebuffer;
    private RenderPass renderPass;
    private GraphicsPipeline conversionPipeline;
    private boolean valid;

    public boolean configure(int size) {
        cleanUp();
        int extent = Math.max(1, size);
        try {
            this.image = VulkanImage.builder(extent, extent)
                    .setName("chimeraPackShadowDepth")
                    .setFormat(100) // VK_FORMAT_R32_SFLOAT
                    .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .setLinearFiltering(true)
                    .setClamp(true)
                    .createVulkanImage();
            this.framebuffer = Framebuffer.builder(this.image, null).build();
            RenderPass.Builder builder = RenderPass.builder(this.framebuffer);
            builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR,
                    VK_ATTACHMENT_STORE_OP_STORE);
            this.renderPass = builder.build();
            MrtPipelineContext.begin(new int[] {VK_FORMAT_R32_SFLOAT}, 1);
            try {
                this.conversionPipeline = ChimeraPostPipelines.createDepthPipeline();
            } finally {
                MrtPipelineContext.end();
            }
            this.valid = this.conversionPipeline != null;
            if (!this.valid) cleanUp();
            return this.valid;
        } catch (RuntimeException failure) {
            cleanUp();
            return false;
        }
    }

    public boolean capture(VkCommandBuffer commandBuffer, VulkanImage rawDepth) {
        if (!this.valid || commandBuffer == null || rawDepth == null) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.image.transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VTextureSelector.bindTexture(6, rawDepth);
            Renderer renderer = Renderer.getInstance();
            renderer.beginRenderPass(this.renderPass, this.framebuffer);
            Renderer.setViewport(0, 0, this.image.width, this.image.height, stack);
            renderer.bindGraphicsPipeline(this.conversionPipeline);
            renderer.uploadAndBindUBOs(this.conversionPipeline);
            vkCmdDraw(commandBuffer, 3, 1, 0, 0);
            renderer.endRenderPass(commandBuffer);
            this.image.transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            renderer.setBoundRenderPass(null);
            renderer.setBoundFramebuffer(null);
            this.valid = true;
            return true;
        } catch (RuntimeException failure) {
            Renderer.getInstance().setBoundRenderPass(null);
            Renderer.getInstance().setBoundFramebuffer(null);
            this.valid = false;
            return false;
        }
    }

    public VulkanImage image() {
        return this.valid ? this.image : null;
    }

    public boolean isConfigured() {
        return this.valid && this.image != null;
    }

    public void cleanUp() {
        if (this.renderPass != null) this.renderPass.cleanUp();
        if (this.framebuffer != null) this.framebuffer.cleanUp(false);
        if (this.image != null) this.image.free();
        if (this.conversionPipeline != null) this.conversionPipeline.cleanUp();
        this.renderPass = null;
        this.framebuffer = null;
        this.image = null;
        this.conversionPipeline = null;
        this.valid = false;
    }
}
