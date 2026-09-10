package net.chimera.render.shader;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;

/** Render-thread context for the pack geometry coverage window. */
public final class PackGeometryContext {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private static final boolean TRACE = Boolean.getBoolean("chimera.traceTransitions");
    private static VulkanImage target;
    private static VulkanImage coverage;
    private static VulkanImage depth;
    private static boolean active;
    private static boolean clearCoverageTargetOnNextRendering;

    private PackGeometryContext() {}

    public static void begin(VulkanImage targetImage, VulkanImage coverageImage, VulkanImage depthImage) {
        beginInternal(targetImage, coverageImage, depthImage, true);
    }

    /** Resumes a coverage window without clearing the target already drawn. */
    public static void beginPreserving(VulkanImage targetImage, VulkanImage coverageImage,
                                       VulkanImage depthImage) {
        beginInternal(targetImage, coverageImage, depthImage, false);
    }

    private static void beginInternal(VulkanImage targetImage, VulkanImage coverageImage,
                                      VulkanImage depthImage, boolean clearTarget) {
        if (active) throw new IllegalStateException("pack geometry context already active");
        if (targetImage == null || coverageImage == null || depthImage == null) {
            throw new IllegalArgumentException("pack geometry attachments are incomplete");
        }
        target = targetImage;
        coverage = coverageImage;
        depth = depthImage;
        active = true;
        clearCoverageTargetOnNextRendering = clearTarget;
        trace("begin coverage target=" + targetImage.getId() + " coverage=" + coverageImage.getId()
                + " depth=" + depthImage.getId());
    }

    public static boolean active() { return active; }

    public static boolean coverageActive() { return active && coverage != null; }

    public static VulkanImage target() { return target; }

    public static void beginRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (!active) return;
        if (commandBuffer == null || commandBuffer.address() == 0L
                || target == null || target.getId() == 0L
                || depth == null || depth.getId() == 0L
                || (coverage != null && coverage.getId() == 0L)) {
            throw new IllegalStateException("pack geometry attachments are not live: cmd="
                    + (commandBuffer == null ? 0L : commandBuffer.address())
                    + " target=" + (target == null ? 0L : target.getId())
                    + " coverage=" + (coverage == null ? 0L : coverage.getId())
                    + " depth=" + (depth == null ? 0L : depth.getId()));
        }
        if (TRACE) {
            LOGGER.info("[chimera] geometry attachments: cmd={} target={} layout={} coverage={} coverageLayout={} depth={} depthLayout={}",
                    commandBuffer.address(), target.getId(), target.getCurrentLayout(),
                    coverage.getId(), coverage.getCurrentLayout(),
                    depth.getId(), depth.getCurrentLayout());
        }
        target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        if (coverage != null) {
            coverage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
        depth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

        VkRenderingAttachmentInfo.Buffer colors = VkRenderingAttachmentInfo.calloc(2, stack);
        for (int i = 0; i < 2; i++) {
            VkRenderingAttachmentInfo info = colors.get(i);
            info.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            info.imageView(i == 0 ? target.getImageView() : coverage.getImageView());
            info.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            boolean clearTarget = coverage != null && i == 0 && clearCoverageTargetOnNextRendering;
            info.loadOp(clearTarget ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD);
            info.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (clearTarget) {
                VkClearValue clear = VkClearValue.calloc(stack);
                clear.color().float32(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));
                info.clearValue(clear);
            }
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
        // A coverage window is cleared only when it is first opened. If a
        // nested shadow pass interrupts it, the resumed window must load the
        // existing pack target instead of erasing the terrain already drawn.
        clearCoverageTargetOnNextRendering = false;
    }

    public static void endRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (!active) return;
        trace("end target=" + (target == null ? 0L : target.getId())
                + " coverage=" + (coverage == null ? 0L : coverage.getId())
                + " depth=" + (depth == null ? 0L : depth.getId()));
        vkCmdEndRenderingKHR(commandBuffer);
        target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        if (coverage != null) {
            coverage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        depth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    }

    /** Ends a logical window after its last RenderPass has closed. */
    public static void close() {
        trace("close target=" + (target == null ? 0L : target.getId())
                + " coverage=" + (coverage == null ? 0L : coverage.getId())
                + " depth=" + (depth == null ? 0L : depth.getId()));
        target = null;
        coverage = null;
        depth = null;
        active = false;
        clearCoverageTargetOnNextRendering = false;
    }

    private static void trace(String message) {
        if (TRACE) LOGGER.info("[chimera] geometry context {}", message);
    }
}
