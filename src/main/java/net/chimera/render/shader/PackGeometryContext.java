package net.chimera.render.shader;

import net.chimera.render.PackPostTargets;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.util.List;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

/** Render-thread context for a guarded pack geometry dynamic-rendering window. */
public final class PackGeometryContext {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private static final boolean TRACE = Boolean.getBoolean("chimera.traceTransitions");

    private static List<VulkanImage> colors = List.of();
    private static VulkanImage depth;
    private static boolean active;
    private static boolean geometryWindow;
    private static boolean clearFirstColor;
    private static boolean clearShadowAttachments;

    private PackGeometryContext() {}

    public static void begin(VulkanImage targetImage, VulkanImage coverageImage, VulkanImage depthImage) {
        beginInternal(List.of(targetImage, coverageImage), depthImage, true, false);
    }

    /** Resumes a coverage window without clearing the target already drawn. */
    public static void beginPreserving(VulkanImage targetImage, VulkanImage coverageImage,
                                       VulkanImage depthImage) {
        beginInternal(List.of(targetImage, coverageImage), depthImage, false, false);
    }

    /** Begins a geometry window with the exact pack output attachment list. */
    public static void beginGeometry(List<VulkanImage> colorImages, VulkanImage depthImage) {
        beginInternal(colorImages, depthImage, false, true);
    }

    /** Opens the authored shadow MRT, clearing colors and depth once, not per terrain layer. */
    public static void beginShadow(List<VulkanImage> colorImages, VulkanImage depthImage) {
        beginInternal(colorImages, depthImage, false, true);
        clearShadowAttachments = true;
    }

    private static void beginInternal(List<VulkanImage> colorImages, VulkanImage depthImage,
                                      boolean clearFirst, boolean geometry) {
        if (active) throw new IllegalStateException("pack geometry context already active");
        if (colorImages == null || colorImages.isEmpty() || colorImages.stream().anyMatch(value -> value == null)
                || depthImage == null) {
            throw new IllegalArgumentException("pack geometry attachments are incomplete");
        }
        colors = List.copyOf(colorImages);
        depth = depthImage;
        clearFirstColor = clearFirst;
        geometryWindow = geometry;
        active = true;
        trace("begin geometry=" + geometry + " colors=" + colors.stream()
                .map(value -> Long.toString(value.getId())).toList()
                + " depth=" + depthImage.getId());
    }

    public static boolean active() { return active; }

    public static boolean coverageActive() { return active && !geometryWindow && colors.size() > 1; }

    public static boolean geometryActive() { return active && geometryWindow; }

    public static VulkanImage target() { return colors.isEmpty() ? null : colors.get(0); }

    public static VulkanImage coverage() { return colors.size() < 2 ? null : colors.get(1); }

    public static VulkanImage depth() { return depth; }

    public static List<VulkanImage> colors() { return colors; }

    public static void beginRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (!active) return;
        if (commandBuffer == null || commandBuffer.address() == 0L || depth == null
                || depth.getId() == 0L || colors.stream().anyMatch(value -> value.getId() == 0L)) {
            throw new IllegalStateException("pack geometry attachments are not live");
        }
        if (TRACE) {
            LOGGER.info("[chimera] geometry attachments: cmd={} colors={} depth={} depthLayout={}",
                    commandBuffer.address(), colors.stream().map(VulkanImage::getId).toList(),
                    depth.getId(), depth.getCurrentLayout());
        }
        for (VulkanImage color : colors) {
            color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
        depth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

        VkRenderingAttachmentInfo.Buffer colorAttachments =
                VkRenderingAttachmentInfo.calloc(colors.size(), stack);
        for (int index = 0; index < colors.size(); index++) {
            VkRenderingAttachmentInfo info = colorAttachments.get(index);
            info.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(PackPostTargets.attachmentView(colors.get(index)))
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(clearShadowAttachments || (!geometryWindow && index == 0 && clearFirstColor)
                            ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (clearShadowAttachments || (!geometryWindow && index == 0 && clearFirstColor)) {
                VkClearValue clear = VkClearValue.calloc(stack);
                float value = clearShadowAttachments ? 1.0f : 0.0f;
                clear.color().float32(stack.floats(value, value, value, value));
                info.clearValue(clear);
            }
        }

        VkRenderingInfo rendering = VkRenderingInfo.calloc(stack);
        org.lwjgl.vulkan.VkRect2D renderArea = org.lwjgl.vulkan.VkRect2D.calloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(colors.get(0).width, colors.get(0).height);
        rendering.sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(renderArea)
                .layerCount(1)
                .pColorAttachments(colorAttachments);
        VkRenderingAttachmentInfo depthInfo = VkRenderingAttachmentInfo.calloc(stack);
        depthInfo.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                .imageView(depth.getImageView())
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .loadOp(clearShadowAttachments ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        if (clearShadowAttachments) {
            VkClearValue clear = VkClearValue.calloc(stack);
            clear.depthStencil().set(1.0f, 0);
            depthInfo.clearValue(clear);
        }
        rendering.pDepthAttachment(depthInfo);
        vkCmdBeginRenderingKHR(commandBuffer, rendering);
        clearFirstColor = false;
        clearShadowAttachments = false;
    }

    public static void endRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (!active) return;
        trace("end colors=" + colors.stream().map(VulkanImage::getId).toList()
                + " depth=" + (depth == null ? 0L : depth.getId()));
        vkCmdEndRenderingKHR(commandBuffer);
        for (VulkanImage color : colors) {
            color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        depth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    }

    /** Ends a logical window after its last RenderPass has closed. */
    public static void close() {
        trace("close colors=" + colors.stream().map(VulkanImage::getId).toList()
                + " depth=" + (depth == null ? 0L : depth.getId()));
        colors = List.of();
        depth = null;
        active = false;
        geometryWindow = false;
        clearFirstColor = false;
        clearShadowAttachments = false;
    }

    private static void trace(String message) {
        if (TRACE) LOGGER.info("[chimera] geometry context {}", message);
    }
}
