package net.chimera.render;

import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.DepthGraphPlan;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.util.Arrays;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
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
    private final boolean[] valid = new boolean[3];
    private final boolean[] previousValid = new boolean[3];
    /** Images holding a capture, or the far plane before the first: a draw may sample them. */
    private final boolean[] defined = new boolean[3];
    private DepthGraphPlan plan = DepthGraphPlan.empty();
    private GraphicsPipeline conversionPipeline;
    private final DepthSampleView depthSampleView = new DepthSampleView();
    private boolean configured;

    public boolean configure(int width, int height, DepthGraphPlan plan) {
        cleanUp();
        this.plan = plan == null ? DepthGraphPlan.empty() : plan;
        if (!this.plan.any()) return false;
        Arrays.fill(this.valid, false);
        Arrays.fill(this.previousValid, false);
        Arrays.fill(this.defined, false);
        MrtPipelineContext.begin(new int[] {this.plan.format()}, 1);
        try {
            this.conversionPipeline = ChimeraPostPipelines.createDepthPipeline();
        } finally {
            MrtPipelineContext.end();
        }
        for (int index = 0; index < images.length; index++) {
            if (!required(index)) continue;
            this.images[index] = VulkanImage.builder(Math.max(1, width), Math.max(1, height))
                    .setFormat(this.plan.format())
                    .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    // Iris depthtex0/1/2 are point samples, not interpolated surface depths.
                    .setLinearFiltering(false)
                    .setClamp(true)
                    .createVulkanImage();
        }
        this.configured = this.conversionPipeline != null;
        return this.configured;
    }

    public boolean captureScene(VkCommandBuffer commandBuffer, VulkanImage rawDepth) {
        if (!this.configured || rawDepth == null) return false;
        return !required(0) || convert(commandBuffer, rawDepth, 0, false);
    }

    /**
     * Captures depthtex0 as deferred passes see it: the opaque depth at the
     * pre-translucent seam. The level-end capture replaces it for composite.
     */
    public boolean captureOpaqueScene(VkCommandBuffer commandBuffer, VulkanImage rawDepth) {
        return configured && rawDepth != null && required(0)
                && convert(commandBuffer, rawDepth, 0, true);
    }

    /** Captures one seam-specific depth snapshot and restores the source layout. */
    public boolean captureOpaque(VkCommandBuffer commandBuffer, VulkanImage rawDepth) {
        return configured && rawDepth != null && required(1)
                && convert(commandBuffer, rawDepth, 1, true);
    }

    /** Captures the pre-hand seam. It is intentionally separate from scene depth. */
    public boolean capturePreHand(VkCommandBuffer commandBuffer, VulkanImage rawDepth) {
        return configured && rawDepth != null && required(2)
                && convert(commandBuffer, rawDepth, 2, true);
    }

    /**
     * Starts a new world frame while retaining the previous capture validity. A target not yet
     * captured is cleared to the far plane first, so a draw that samples it reads defined depth.
     */
    public void beginFrame(VkCommandBuffer commandBuffer) {
        System.arraycopy(this.valid, 0, this.previousValid, 0, this.valid.length);
        Arrays.fill(this.valid, false);
        for (int index = 0; index < images.length; index++) {
            if (images[index] != null && !defined[index]) clearToFarPlane(commandBuffer, index);
        }
    }

    private void clearToFarPlane(VkCommandBuffer commandBuffer, int index) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            images[index].transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            vkCmdBeginRenderingKHR(commandBuffer, snapshotRendering(stack, images[index], 1.0f));
            vkCmdEndRenderingKHR(commandBuffer);
            images[index].transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        defined[index] = true;
    }

    /** Publishes the captures made during the current world frame. */
    public void commitFrame() {
        System.arraycopy(this.valid, 0, this.previousValid, 0, this.valid.length);
    }

    private boolean convert(
            VkCommandBuffer commandBuffer,
            VulkanImage rawDepth,
            int index,
            boolean restoreDepthAttachment
    ) {
        Renderer renderer = Renderer.getInstance();
        if (!canRecordConversion(commandBuffer, rawDepth, index)) {
            return false;
        }
        VulkanImage previousDepthBinding = VTextureSelector.getImage(6);
        // The copy must overwrite: under an inherited blend (a glint's
        // SRC_COLOR/ONE) every captured depth was squared.
        HostPipelineState previousState = HostPipelineState.capture();
        HostPipelineState.prepareFullscreen();
        boolean renderingActive = false;
        boolean depthReadLayoutActive = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (renderer.getBoundRenderPass() != null) {
                renderer.endRenderPass(commandBuffer);
            }
            long depthView = this.depthSampleView.ensure(rawDepth);
            if (depthView == 0L) {
                return false;
            }
            rawDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            depthReadLayoutActive = true;
            images[index].transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            ChimeraDepthViewOverride.bind(rawDepth, depthView);
            VTextureSelector.bindTexture(6, rawDepth);
            vkCmdBeginRenderingKHR(commandBuffer, snapshotRendering(stack, images[index], 0.0f));
            renderer.setBoundRenderPass(ChimeraRenderingPass.of(new int[] {images[index].format}, 0, false));
            renderingActive = true;
            Renderer.setViewport(0, 0, images[index].width, images[index].height, stack);
            renderer.bindGraphicsPipeline(this.conversionPipeline);
            renderer.uploadAndBindUBOs(this.conversionPipeline);
            vkCmdDraw(commandBuffer, ChimeraPostPipelines.FULLSCREEN_VERTICES, 1, 0, 0);
            vkCmdEndRenderingKHR(commandBuffer);
            renderingActive = false;
            images[index].transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (restoreDepthAttachment) {
                rawDepth.transitionImageLayout(stack, commandBuffer,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                depthReadLayoutActive = false;
            }
            valid[index] = true;
            defined[index] = true;
            renderer.setBoundRenderPass(null);
            renderer.setBoundFramebuffer(null);
            return true;
        } catch (RuntimeException failure) {
            if (renderingActive) {
                try {
                    vkCmdEndRenderingKHR(commandBuffer);
                } catch (RuntimeException ignored) {
                    // Keep the original conversion failure as the outcome.
                }
            }
            if (restoreDepthAttachment && depthReadLayoutActive) {
                try (MemoryStack cleanupStack = MemoryStack.stackPush()) {
                    rawDepth.transitionImageLayout(cleanupStack, commandBuffer,
                            VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                } catch (RuntimeException ignored) {
                    // The host shadow/depth path will remain authoritative.
                }
            }
            renderer.setBoundRenderPass(null);
            renderer.setBoundFramebuffer(null);
            return false;
        } finally {
            previousState.restore();
            ChimeraDepthViewOverride.clear();
            VTextureSelector.bindTexture(6, previousDepthBinding);
        }
    }

    /**
     * A depth snapshot owns a separate fullscreen render pass. It cannot
     * be recorded while the guarded terrain context is active because the
     * RenderPass mixin intentionally redirects dynamic rendering in that
     * context. Refuse the nested operation and keep the host depth path.
     */
    private boolean canRecordConversion(VkCommandBuffer commandBuffer, VulkanImage rawDepth, int index) {
        return !PackGeometryContext.active()
                && commandBuffer != null
                && commandBuffer.address() != 0L
                && rawDepth.getId() != 0L
                && images[index] != null
                && images[index].getId() != 0L
                && conversionPipeline != null;
    }

    /** Dynamic rendering that clears one snapshot image to {@code depth} over its whole extent. */
    private static VkRenderingInfo snapshotRendering(MemoryStack stack, VulkanImage snapshot, float depth) {
        VkClearValue clear = VkClearValue.calloc(stack);
        clear.color().float32(stack.floats(depth, 0.0f, 0.0f, 0.0f));
        VkRenderingAttachmentInfo.Buffer attachments = VkRenderingAttachmentInfo.calloc(1, stack);
        attachments.get(0)
                .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                .imageView(snapshot.getImageView())
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .clearValue(clear);
        VkRect2D area = VkRect2D.calloc(stack);
        area.offset().set(0, 0);
        area.extent().set(snapshot.width, snapshot.height);
        return VkRenderingInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(area)
                .layerCount(1)
                .pColorAttachments(attachments);
    }

    public VulkanImage image(String name) {
        return switch (name) {
            case "depthtex0" -> valid[0] ? images[0] : null;
            case "depthtex1" -> valid[1] ? images[1] : null;
            case "depthtex2" -> valid[2] ? images[2] : null;
            default -> null;
        };
    }

    /**
     * The image a draw samples as a depth texture: this frame's capture, or before it the last
     * one, as Iris's depth textures persist between their copies. NeoForge draws solid particles
     * before translucent terrain, ahead of the depthtex1 capture.
     */
    public VulkanImage sampledImage(String name) {
        int index = switch (name) {
            case "depthtex0" -> 0;
            case "depthtex1" -> 1;
            case "depthtex2" -> 2;
            default -> -1;
        };
        return index >= 0 && defined[index] ? images[index] : null;
    }

    public boolean isConfigured() { return configured; }
    public DepthGraphPlan plan() { return plan; }

    public boolean currentAvailable(String name) {
        return switch (name) {
            case "depthtex0" -> valid[0];
            case "depthtex1" -> valid[1];
            case "depthtex2" -> valid[2];
            default -> false;
        };
    }

    public boolean previousAvailable(String name) {
        return switch (name) {
            case "depthtex0" -> previousValid[0];
            case "depthtex1" -> previousValid[1];
            case "depthtex2" -> previousValid[2];
            default -> false;
        };
    }

    public void cleanUp() {
        for (VulkanImage image : images) if (image != null) image.free();
        if (conversionPipeline != null) conversionPipeline.cleanUp();
        // VulkanImage.free() is deferred by VulkanMod, but this view is a raw
        // handle owned by Chimera; destroy() waits for idle first.
        this.depthSampleView.destroy();
        Arrays.fill(images, null);
        Arrays.fill(valid, false);
        Arrays.fill(previousValid, false);
        Arrays.fill(defined, false);
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
