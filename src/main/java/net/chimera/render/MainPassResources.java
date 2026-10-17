package net.chimera.render;

import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.PackConfig;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;

import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Creates and refreshes the pass's GPU resources: the world targets and attachments, recreated on a
 * resize or a pack change, and the output resources the frame presents through.
 */
final class MainPassResources {
    private final ChimeraMainPass pass;

    /** Internal world resources can retire independently from the output. */
    boolean worldResourcesReady;

    /** The stable Minecraft main/output target and aliases are live. */
    boolean outputResourcesReady;

    MainPassResources(ChimeraMainPass pass) {
        this.pass = pass;
    }

    void createResources() {
        pass.packPostChainActive = false;
        pass.packPostTargets.cleanUp();
        pass.packDepthTargets.cleanUp();
        pass.targets.cleanUpFramebuffersAndPasses();
        pass.cleanUpPipelines();

        SwapChain swapChain = Vulkan.getSwapChain();
        int width = Math.max(swapChain.getWidth(), 1);
        int height = Math.max(swapChain.getHeight(), 1);

        pass.sessions.loadPackConfig();
        PackConfig.ShadowSettings shadowSettings = pass.packConfig != null
                ? pass.packConfig.shadowSettings()
                : new PackConfig.ShadowSettings(
                        PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION,
                        PackConfig.DEFAULT_SHADOW_DISTANCE,
                        Map.of(), List.of());
        pass.shadowPass.shadowMap.init(shadowSettings);
        pass.targets.createHdrFramebuffer(width, height);
        pass.targets.createOutputFramebuffer(width, height);
        pass.targets.createOutputRenderPasses();
        pass.createPipelines();
        pass.targets.createHdrInteropTextures();
        pass.targets.createOutputInteropTextures();
        this.worldResourcesReady = true;
        this.outputResourcesReady = true;
        pass.currentFramebuffer = pass.targets.compositeFramebuffer;
        pass.earlyOutputPass = true;
        pass.postChain.configurePackPostTargets(width, height);
        pass.load.loadPackPipelines();
        if (pass.shadowPass.usePackShadowRuntime() && pass.shadowPass.shadowMap.isInitialized()) {
            pass.shadowPass.packShadowDepth.install();
        }
    }

    void invalidateWorldResources() {
        long oldHdrId = pass.targets.hdrImageId();
        Framebuffer oldHdr = pass.targets.hdrFramebuffer;
        this.worldResourcesReady = false;
        pass.levelPhase = false;
        pass.pendingDepthClear = false;
        pass.shadowPass.shadowPending = false;
        if (oldHdr == null && pass.targets.hdrColorGlId == 0 && pass.targets.hdrDepthGlId == 0) {
            return;
        }

        // The host composition window borrows the HDR depth attachment. Close
        // that logical window before retiring the framebuffer so no later
        // RenderPass can dereference the old depth image.
        if (PackGeometryContext.active()) {
            Renderer.getInstance().endRenderPass();
            PackGeometryContext.close();
        }
        if (ChimeraMainPass.boundFramebuffer() == oldHdr) {
            Renderer.getInstance().endRenderPass();
        }
        pass.targets.releaseHdrInteropTextures();
        pass.targets.releaseHdrFramebuffer();
        if (pass.currentFramebuffer == oldHdr) {
            pass.currentFramebuffer = pass.targets.compositeFramebuffer;
        }
        if (ChimeraMainPass.TRACE_TRANSITIONS) {
            ChimeraMainPass.LOGGER.info(
                    "[chimera] invalidated world attachments at frame boundary frame={} oldHdrId={}",
                    Renderer.getCurrentFrame(), oldHdrId);
        }
    }

    /**
     * Retires the HDR attachments at a frame-op boundary (the slot fence was
     * waited) and recreates them at the same safe point. Leaving
     * hdrFramebuffer null here is the respawn NPE: the next frame's
     * openLevelSegment can run before another recreate boundary when screens
     * suppress segments, and when the level dies the host handoff leaves no
     * chimera frame to recreate at all.
     */
    void refreshWorldResources() {
        invalidateWorldResources();
        recreateWorldAttachments();
        if (ChimeraMainPass.TRACE_TRANSITIONS) {
            ChimeraMainPass.LOGGER.info("[chimera] recreated world attachments at frame boundary frame={} newHdrId={}",
                    Renderer.getCurrentFrame(), pass.targets.hdrImageId());
        }
    }

    void recreateWorldAttachments() {
        Framebuffer output = pass.targets.compositeFramebuffer;
        int width = output != null ? output.getWidth() : Math.max(Vulkan.getSwapChain().getWidth(), 1);
        int height = output != null ? output.getHeight()
                : Math.max(Vulkan.getSwapChain().getHeight(), 1);
        pass.targets.createHdrFramebuffer(width, height);
        pass.targets.createHdrInteropTextures();
        pass.depth.hdrDepthReadable = false;
        this.worldResourcesReady = true;
        pass.earlyOutputPass = false;
    }

    void ensureWorldResources() {
        if (this.worldResourcesReady && pass.targets.hdrResourcesLive()) {
            return;
        }

        invalidateWorldResources();
        recreateWorldAttachments();
        if (ChimeraMainPass.TRACE_TRANSITIONS) {
            ChimeraMainPass.LOGGER.info("[chimera] recreated world attachments before level frame={} newHdrId={}",
                    Renderer.getCurrentFrame(), pass.targets.hdrImageId());
        }
    }

    void ensureOutputResources() {
        if (this.outputResourcesReady && pass.targets.outputResourcesLive()) {
            return;
        }
        ChimeraMainPass.LOGGER.warn("[chimera] stable output attachment closed; rebuilding full pass resources");
        createResources();
    }
}
