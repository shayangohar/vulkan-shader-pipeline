package net.chimera.render.shader;

import net.chimera.render.ChimeraRenderingPass;
import net.chimera.render.PackPostTargets;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * An open window: its attachments, whether it is a geometry window (else a coverage window), and
     * what its first rendering clears. {@code clearFirstColor} clears a fresh coverage window's
     * target to 0; {@code shadowClears}, when not null, clears a shadow window's depth to 1 and each
     * colour to its own clear colour, a null one loading. Both apply once, when the window first
     * begins rendering, not per terrain layer.
     */
    private record Window(List<VulkanImage> colors, VulkanImage depth, boolean geometry, boolean clearFirstColor,
                          List<float[]> shadowClears) {
        /** The window once it has begun rendering: later renderings load what it drew. */
        Window opened() {
            return new Window(this.colors, this.depth, this.geometry, false, null);
        }
    }

    /** The open window, or null. */
    private static Window window;

    private PackGeometryContext() {}

    public static void begin(VulkanImage targetImage, VulkanImage coverageImage, VulkanImage depthImage) {
        open(List.of(targetImage, coverageImage), depthImage, true, false, null);
    }

    /** Resumes a coverage window without clearing the target already drawn. */
    public static void beginPreserving(VulkanImage targetImage, VulkanImage coverageImage,
                                       VulkanImage depthImage) {
        open(List.of(targetImage, coverageImage), depthImage, false, false, null);
    }

    /** Begins a geometry window with the exact pack output attachment list. */
    public static void beginGeometry(List<VulkanImage> colorImages, VulkanImage depthImage) {
        open(colorImages, depthImage, false, true, null);
    }

    /**
     * Opens the authored shadow MRT, clearing depth and each colour with its clear colour once, not
     * per terrain layer. A null clear colour keeps that target's contents.
     */
    public static void beginShadow(List<VulkanImage> colorImages, VulkanImage depthImage, List<float[]> clears) {
        if (clears.size() != colorImages.size()) {
            throw new IllegalArgumentException("one shadow clear per colour attachment");
        }
        open(colorImages, depthImage, false, true, Collections.unmodifiableList(new ArrayList<>(clears)));
    }

    private static void open(List<VulkanImage> colorImages, VulkanImage depthImage, boolean clearFirst,
                             boolean geometry, List<float[]> shadowClears) {
        if (window != null) throw new IllegalStateException("pack geometry context already active");
        if (colorImages == null || colorImages.isEmpty() || colorImages.stream().anyMatch(value -> value == null)
                || depthImage == null) {
            throw new IllegalArgumentException("pack geometry attachments are incomplete");
        }
        window = new Window(List.copyOf(colorImages), depthImage, geometry, clearFirst, shadowClears);
        trace("begin geometry=" + geometry + " colors=" + window.colors().stream()
                .map(value -> Long.toString(value.getId())).toList()
                + " depth=" + depthImage.getId());
    }

    public static boolean active() { return window != null; }

    public static boolean coverageActive() {
        return window != null && !window.geometry() && window.colors().size() > 1;
    }

    public static boolean geometryActive() { return window != null && window.geometry(); }

    public static VulkanImage target() { return window == null ? null : window.colors().get(0); }

    public static VulkanImage coverage() {
        return window == null || window.colors().size() < 2 ? null : window.colors().get(1);
    }

    public static VulkanImage depth() { return window == null ? null : window.depth(); }

    public static List<VulkanImage> colors() { return window == null ? List.of() : window.colors(); }

    /**
     * Begins dynamic rendering into the window's attachments and returns the pass VulkanMod binds
     * for it, in GL row order when {@code glOriented}.
     */
    public static ChimeraRenderingPass beginRendering(VkCommandBuffer commandBuffer, MemoryStack stack,
                                                      boolean glOriented) {
        Window open = window;
        if (open == null) throw new IllegalStateException("no pack geometry window is pending");
        List<VulkanImage> colors = open.colors();
        VulkanImage depth = open.depth();
        if (commandBuffer == null || commandBuffer.address() == 0L
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

        VkRenderingAttachmentInfo.Buffer colorAttachments = colorAttachments(open, stack);

        VkRenderingInfo rendering = VkRenderingInfo.calloc(stack);
        VkRect2D renderArea = VkRect2D.calloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(colors.get(0).width, colors.get(0).height);
        rendering.sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(renderArea)
                .layerCount(1)
                .pColorAttachments(colorAttachments);
        rendering.pDepthAttachment(depthAttachment(open, stack));
        vkCmdBeginRenderingKHR(commandBuffer, rendering);
        window = open.opened();
        return ChimeraRenderingPass.of(colors.stream().mapToInt(image -> image.format).toArray(), depth.format,
                glOriented);
    }

    /**
     * The color attachments: a shadow window's colours clear to their own colours as it opens, a
     * fresh coverage window's target to 0, the rest load.
     */
    private static VkRenderingAttachmentInfo.Buffer colorAttachments(Window open, MemoryStack stack) {
        List<VulkanImage> colors = open.colors();
        VkRenderingAttachmentInfo.Buffer colorAttachments =
                VkRenderingAttachmentInfo.calloc(colors.size(), stack);
        for (int index = 0; index < colors.size(); index++) {
            float[] clearColor = open.shadowClears() != null ? open.shadowClears().get(index)
                    : !open.geometry() && index == 0 && open.clearFirstColor() ? new float[4] : null;
            VkRenderingAttachmentInfo info = colorAttachments.get(index);
            info.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(PackPostTargets.attachmentView(colors.get(index)))
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(clearColor != null ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (clearColor != null) {
                VkClearValue clear = VkClearValue.calloc(stack);
                PackPostTargets.clearColorValue(stack, clear.color(), colors.get(index).format, clearColor);
                info.clearValue(clear);
            }
        }
        return colorAttachments;
    }

    /** The depth attachment: cleared to 1 when a shadow window opens, loaded otherwise. */
    private static VkRenderingAttachmentInfo depthAttachment(Window open, MemoryStack stack) {
        boolean clear = open.shadowClears() != null;
        VkRenderingAttachmentInfo depthInfo = VkRenderingAttachmentInfo.calloc(stack);
        depthInfo.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                .imageView(open.depth().getImageView())
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .loadOp(clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        if (clear) {
            VkClearValue value = VkClearValue.calloc(stack);
            value.depthStencil().set(1.0f, 0);
            depthInfo.clearValue(value);
        }
        return depthInfo;
    }

    public static void endRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
        Window open = window;
        if (open == null) return;
        trace("end colors=" + open.colors().stream().map(VulkanImage::getId).toList()
                + " depth=" + open.depth().getId());
        vkCmdEndRenderingKHR(commandBuffer);
        for (VulkanImage color : open.colors()) {
            color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        open.depth().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    }

    /** Ends a logical window after its last RenderPass has closed. */
    public static void close() {
        trace("close colors=" + colors().stream().map(VulkanImage::getId).toList()
                + " depth=" + (window == null ? 0L : window.depth().getId()));
        window = null;
    }

    private static void trace(String message) {
        if (TRACE) LOGGER.info("[chimera] geometry context {}", message);
    }
}
