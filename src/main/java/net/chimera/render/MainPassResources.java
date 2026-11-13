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

    /** A screen opened or closed: the world attachments are recreated at the next frame boundary. */
    boolean worldRefreshDue;

    /** The swapchain format the present render pass was built for. */
    private int presentFormat;

    MainPassResources(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /**
     * Recreates the world attachments a screen change retired, before the frame records its first
     * command. Not as one of VulkanMod's frame ops: a framebuffer retired while they run is queued
     * onto the list being run and destroyed at once, while the previous frame may still use it.
     */
    void refreshWorldResourcesIfDue() {
        if (!this.worldRefreshDue) return;
        this.worldRefreshDue = false;
        refreshWorldResources();
    }

    void createResources() {
        pass.session.packPostChainActive = false;
        pass.packPostTargets.cleanUp();
        pass.packDepthTargets.cleanUp();
        pass.targets.cleanUpFramebuffersAndPasses();
        pass.cleanUpPipelines();

        SwapChain swapChain = Vulkan.getSwapChain();
        int width = Math.max(swapChain.getWidth(), 1);
        int height = Math.max(swapChain.getHeight(), 1);
        this.presentFormat = swapChain.getFormat();

        pass.sessions.loadPackConfig();
        PackConfig.ShadowSettings shadowSettings = pass.packConfig != null
                ? pass.packConfig.shadowSettings()
                : new PackConfig.ShadowSettings(
                        PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION,
                        PackConfig.DEFAULT_SHADOW_DISTANCE,
                        Map.of(), List.of());
        pass.shadowPass.shadowMap.init(shadowSettings,
                pass.packPassComputes == null ? Map.of() : pass.packPassComputes.shadowTargets());
        pass.targets.createHdrFramebuffer(width, height);
        pass.targets.createOutputFramebuffer(width, height);
        pass.targets.createRenderPasses();
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

    /**
     * Whether the live resources still serve the swapchain VulkanMod just recreated. VulkanMod
     * recreates it at 0x0 when the window is minimized and again on restore. Minecraft ignores a
     * 0x0 framebuffer and Iris sizes its targets from Minecraft's, so neither step resets a pack's
     * targets there; rebuilding them here wiped their history (Kappa's TAA then divides by a zero
     * exposure for a frame). The swapchain's own framebuffers are rebuilt per render pass on use.
     */
    boolean serveSwapchain() {
        Framebuffer output = pass.targets.compositeFramebuffer;
        if (!this.outputResourcesReady || output == null || !pass.targets.outputResourcesLive()) {
            return false;
        }
        SwapChain swapChain = Vulkan.getSwapChain();
        return serves(output.getWidth(), output.getHeight(), this.presentFormat,
                swapChain.getWidth(), swapChain.getHeight(), swapChain.getFormat());
    }

    /**
     * Whether an output built at {@code builtWidth}x{@code builtHeight}, presenting in
     * {@code builtFormat}, serves a swapchain now {@code width}x{@code height} in {@code format}.
     * A 0x0 swapchain draws nothing; otherwise the size and the present format must match.
     */
    static boolean serves(int builtWidth, int builtHeight, int builtFormat, int width, int height, int format) {
        if (width == 0 && height == 0) return true;
        return builtWidth == width && builtHeight == height && builtFormat == format;
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
        if (oldHdr != null) oldHdr.cleanUp();
        pass.targets.hdrFramebuffer = null;
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
     * Retires the HDR attachments at a frame boundary, before recording starts,
     * and recreates them at the same safe point. VulkanMod frees the retired
     * images and framebuffers once every frame that used them has finished. Leaving
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
