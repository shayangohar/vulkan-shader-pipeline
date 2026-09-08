package net.chimera.render.shader;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;

/** Render-thread context for pack geometry target-0 plus coverage output. */
public final class PackGeometryContext {
    private static VulkanImage target;
    private static VulkanImage coverage;
    private static VulkanImage depth;
    private static boolean active;

    private PackGeometryContext() {}

    public static void begin(VulkanImage targetImage, VulkanImage coverageImage, VulkanImage depthImage) {
        if (active) throw new IllegalStateException("pack geometry context already active");
        if (targetImage == null || coverageImage == null || depthImage == null) {
            throw new IllegalArgumentException("pack geometry attachments are incomplete");
        }
        target = targetImage;
        coverage = coverageImage;
        depth = depthImage;
        active = true;
    }

    public static boolean active() { return active; }

    public static void beginRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (!active) return;
        target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        coverage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        depth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

        VkRenderingAttachmentInfo.Buffer colors = VkRenderingAttachmentInfo.calloc(2, stack);
        for (int i = 0; i < 2; i++) {
            VkRenderingAttachmentInfo info = colors.get(i);
            info.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            info.imageView(i == 0 ? target.getImageView() : coverage.getImageView());
            info.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            info.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            info.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        }
        VkRenderingInfo rendering = VkRenderingInfo.calloc(stack);
        org.lwjgl.vulkan.VkRect2D renderArea = org.lwjgl.vulkan.VkRect2D.calloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(target.width, target.height);
        rendering.sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(renderArea)
                .layerCount(1)
                .pColorAttachments(colors);
        VkRenderingAttachmentInfo depthInfo = VkRenderingAttachmentInfo.calloc(stack);
        depthInfo.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                .imageView(depth.getImageView())
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        rendering.pDepthAttachment(depthInfo);
        vkCmdBeginRenderingKHR(commandBuffer, rendering);
    }

    public static void endRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (!active) return;
        vkCmdEndRenderingKHR(commandBuffer);
        target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        coverage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        depth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        target = null;
        coverage = null;
        depth = null;
        active = false;
    }
}
