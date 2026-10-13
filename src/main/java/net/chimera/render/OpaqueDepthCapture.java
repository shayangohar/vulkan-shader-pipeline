package net.chimera.render;

import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Snapshots the opaque depth before translucent terrain draws, as Iris gives depthtex1 to water and
 * deferred passes, and makes the world depth readable for the passes that sample it.
 */
final class OpaqueDepthCapture {
    static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    /** Tracks the one explicit HDR depth transition for the current level segment. */
    boolean hdrDepthReadable;

    OpaqueDepthCapture(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /** Captures opaque depth at the boundary immediately before translucency. */
    public void captureOpaqueDepthBeforeTranslucent() {
        if (pass.shadowPass.shadowPassActive || !pass.levelPhase || pass.targets.hdrFramebuffer == null) {
            return;
        }
        boolean captureOpaque = pass.packDepthTargets.isConfigured()
                && pass.packDepthTargets.plan().depthtex1();
        boolean runEarlyPost = pass.packPostChainActive
                && pass.packPostTargets.isConfigured()
                && pass.packFrameSchedule.hasWindow(PackFrameSchedulePlan.PostWindow.EARLY);
        if (!captureOpaque && !runEarlyPost) {
            return;
        }
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        if (captureOpaque) {
            boolean captured = pass.packDepthTargets.captureOpaque(
                    commandBuffer, pass.targets.hdrFramebuffer.getDepthAttachment());
            if (!captured) {
                LOGGER.warn("[chimera] pack depth graph: depthtex1 capture unavailable");
            }
        }
        if (runEarlyPost) {
            runEarlyPostWindow(commandBuffer);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Renderer.getInstance().beginRenderPass(pass.targets.hdrAuxRenderPass, pass.targets.hdrFramebuffer);
            Renderer.setViewport(0, 0, pass.targets.hdrFramebuffer.getWidth(),
                    pass.targets.hdrFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, pass.targets.hdrFramebuffer.scissor(stack));
        }
        this.hdrDepthReadable = false;
    }

    /** Runs the deferred (EARLY) window over the opaque world, with the depth snapshots it reads. */
    private void runEarlyPostWindow(VkCommandBuffer commandBuffer) {
        // Iris beginHand copies depthtex2 before deferred runs. A pack solid hand
        // already captured it at its segment; otherwise no hand has drawn yet and
        // the opaque depth is the pre-hand depth.
        if (pass.packDepthTargets.isConfigured() && pass.packDepthTargets.plan().depthtex2()
                && !pass.packDepthTargets.currentAvailable("depthtex2")
                && !pass.packDepthTargets.capturePreHand(
                        commandBuffer, pass.targets.hdrFramebuffer.getDepthAttachment())) {
            LOGGER.warn("[chimera] pack depth graph: early depthtex2 capture unavailable");
        }
        // Deferred passes read depthtex0 here, before translucents: the
        // opaque depth. finishLevelSegment re-captures it for composite.
        if (pass.packFrameSchedule.earlyReadsDepthtex0()
                && pass.packDepthTargets.isConfigured()
                && !pass.packDepthTargets.captureOpaqueScene(
                        commandBuffer, pass.targets.hdrFramebuffer.getDepthAttachment())) {
            LOGGER.warn("[chimera] pack depth graph: early depthtex0 capture unavailable");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = pass.targets.hdrFramebuffer.getColorAttachment();
            hdrColor.transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            pass.postChain.runPackPostWindow(commandBuffer, hdrColor,
                    PackFrameSchedulePlan.PostWindow.EARLY);
            pass.packEarlyPostCompleted = true;
            // Translucents, particles and the rest of the world compose
            // over the deferred result, as they do over Iris' colortex0.
            if (pass.packPostChainActive) {
                pass.packPostTargets.adoptTarget0(commandBuffer, hdrColor);
            }
            hdrColor.transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
    }

    /**
     * Snapshots the world depth at level end, the pre-hand depthtex2 unless already captured and
     * the scene depth, and makes the HDR depth readable for the post passes that sample it.
     */
    void captureWorldDepth(MemoryStack stack, VkCommandBuffer commandBuffer) {
        if (pass.packDepthTargets.isConfigured()
                && pass.packDepthTargets.plan().depthtex2()
                && !pass.packDepthTargets.currentAvailable("depthtex2")
                && !pass.packDepthTargets.capturePreHand(
                        commandBuffer, pass.targets.hdrFramebuffer.getDepthAttachment())) {
            LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (pre-hand depth conversion failed)");
        }
        transitionHdrDepthForSampling(stack, commandBuffer);
        if (pass.packDepthTargets.isConfigured()
                && !pass.packDepthTargets.captureScene(
                commandBuffer, pass.targets.hdrFramebuffer.getDepthAttachment())) {
            LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (depth conversion failed)");
        }
    }

    void transitionHdrDepthForSampling(MemoryStack stack, VkCommandBuffer commandBuffer) {
        if (!pass.packNeedsHdrDepth || this.hdrDepthReadable || pass.targets.hdrFramebuffer == null) {
            return;
        }
        VulkanImage hdrDepth = pass.targets.hdrFramebuffer.getDepthAttachment();
        ChimeraMainPass.trace("resolveWorldDepth", "hdrDepth", hdrDepth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        hdrDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.hdrDepthReadable = true;
    }
}
