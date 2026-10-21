package net.chimera.render;

import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
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
 *
 * <p>Iris's depthtex0 is the live depth texture, so a program that runs while the world still
 * draws reads the depth drawn so far. Vulkan forbids sampling the depth attachment being drawn
 * into, so such a program reads a depthtex0 capture of the world drawn before its group of draws
 * instead. The capture goes stale at the frame's start and after each terrain layer, family
 * flush and particle call, and is retaken before the next draw that samples depthtex0.</p>
 */
final class OpaqueDepthCapture {
    static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    /** Tracks the one explicit HDR depth transition for the current level segment. */
    boolean hdrDepthReadable;

    /** The world has drawn since depthtex0 last captured it this frame. */
    boolean drawnDepthStale = true;

    OpaqueDepthCapture(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /** Marks a group of world draws done: the next program that samples depthtex0 captures it again. */
    void markWorldDrawn() {
        this.drawnDepthStale = true;
    }

    /**
     * True when {@code pipeline}'s program samples depthtex0 while the world depth is still the
     * attachment world programs draw into, and the world has drawn since depthtex0's capture.
     */
    boolean needsDrawnDepth(GraphicsPipeline pipeline) {
        ChimeraMainPass.ProgramImages program = pass.images.programImages.get(pipeline);
        return this.drawnDepthStale && program != null && program.manifest().readsDepth("depthtex0")
                && drawnDepthCapturable();
    }

    /** Whether depthtex0 can be captured from the world depth the world is drawing into now. */
    boolean drawnDepthCapturable() {
        return pass.levelPhase && !this.hdrDepthReadable
                && !pass.shadowPass.shadowPassActive && !PackGeometryContext.active()
                && pass.targets.hdrFramebuffer != null
                && pass.currentFramebuffer == pass.targets.hdrFramebuffer
                && pass.packDepthTargets.isConfigured() && pass.packDepthTargets.plan().depthtex0();
    }

    /** Captures depthtex0 as the world drawn so far. No render pass may be open. */
    void captureDrawnDepth(VkCommandBuffer commandBuffer) {
        VulkanImage worldDepth = pass.targets.hdrFramebuffer.getDepthAttachment();
        if (pass.packDepthTargets.captureDrawnScene(commandBuffer, worldDepth)) {
            this.drawnDepthStale = false;
        } else {
            LOGGER.warn("[chimera] pack depth graph: depthtex0 capture of the drawn world unavailable");
        }
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
            Renderer.getInstance().beginRendering(pass.targets.hdrAuxRenderPass, pass.targets.hdrFramebuffer);
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
        // Deferred passes read depthtex0 here, before translucents: the opaque depth, as Iris's
        // live depth is when translucent terrain begins. finishLevelSegment re-captures it for
        // composite.
        if (pass.packFrameSchedule.earlyReadsDepthtex0() && this.drawnDepthStale && drawnDepthCapturable()) {
            captureDrawnDepth(commandBuffer);
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
