package net.chimera.render;

import net.chimera.mixin.ChimeraRenderPassAccessor;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.PackConfig;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.MainDepthSnapshot;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The main pass's render targets and the GPU resources behind them: the HDR world framebuffer, the
 * composite and pack-final output framebuffers with their render passes, and the OpenGL texture
 * names the host reads the world and output images through (Minecraft's main target asks the main
 * pass for them). It creates and refreshes them: the world targets and attachments, recreated on a
 * resize, a screen change or a pack change, and the output resources the frame presents through.
 */
final class MainPassTargets {
    private final ChimeraMainPass pass;

    Framebuffer hdrFramebuffer;
    RenderPass hdrRenderPass;
    RenderPass hdrAuxRenderPass;
    RenderPass hdrAuxClearDepthRenderPass;
    Framebuffer compositeFramebuffer;
    RenderPass compositeRenderPass;
    Framebuffer packFinalFramebuffer;
    RenderPass packFinalRenderPass;
    RenderPass compositeAuxRenderPass;
    RenderPass compositeAuxClearDepthRenderPass;
    RenderPass presentRenderPass;
    /** OpenGL names aliasing the HDR world images, 0 when none. */
    int hdrColorGlId;
    int hdrDepthGlId;
    /** OpenGL names aliasing the output images, 0 when none. */
    int compositeColorGlId;
    int compositeDepthGlId;

    /** Internal world resources can retire independently from the output. */
    boolean worldResourcesReady;

    /** The stable Minecraft main/output target and aliases are live. */
    boolean outputResourcesReady;

    /** A screen opened or closed: the world attachments are recreated at the next frame boundary. */
    boolean worldRefreshDue;

    /** The swapchain format the present render pass was built for. */
    private int presentFormat;

    MainPassTargets(ChimeraMainPass pass) {
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
        cleanUpFramebuffersAndPasses();
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
        createHdrFramebuffer(width, height);
        createOutputFramebuffer(width, height);
        createRenderPasses();
        pass.createPipelines();
        createHdrInteropTextures();
        createOutputInteropTextures();
        this.worldResourcesReady = true;
        this.outputResourcesReady = true;
        pass.currentFramebuffer = this.compositeFramebuffer;
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
        Framebuffer output = this.compositeFramebuffer;
        if (!this.outputResourcesReady || output == null || !outputResourcesLive()) {
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
        long oldHdrId = hdrImageId();
        Framebuffer oldHdr = this.hdrFramebuffer;
        this.worldResourcesReady = false;
        pass.levelPhase = false;
        pass.pendingDepthClear = false;
        pass.shadowPass.shadowPending = false;
        if (oldHdr == null && this.hdrColorGlId == 0 && this.hdrDepthGlId == 0) {
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
        releaseHdrInteropTextures();
        if (oldHdr != null) oldHdr.cleanUp();
        this.hdrFramebuffer = null;
        if (pass.currentFramebuffer == oldHdr) {
            pass.currentFramebuffer = this.compositeFramebuffer;
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
                    Renderer.getCurrentFrame(), hdrImageId());
        }
    }

    void recreateWorldAttachments() {
        Framebuffer output = this.compositeFramebuffer;
        int width = output != null ? output.getWidth() : Math.max(Vulkan.getSwapChain().getWidth(), 1);
        int height = output != null ? output.getHeight()
                : Math.max(Vulkan.getSwapChain().getHeight(), 1);
        createHdrFramebuffer(width, height);
        createHdrInteropTextures();
        pass.depth.hdrDepthReadable = false;
        this.worldResourcesReady = true;
        pass.earlyOutputPass = false;
    }

    void ensureWorldResources() {
        if (this.worldResourcesReady && hdrResourcesLive()) {
            return;
        }

        invalidateWorldResources();
        recreateWorldAttachments();
        if (ChimeraMainPass.TRACE_TRANSITIONS) {
            ChimeraMainPass.LOGGER.info("[chimera] recreated world attachments before level frame={} newHdrId={}",
                    Renderer.getCurrentFrame(), hdrImageId());
        }
    }

    void ensureOutputResources() {
        if (this.outputResourcesReady && outputResourcesLive()) {
            return;
        }
        ChimeraMainPass.LOGGER.warn("[chimera] stable output attachment closed; rebuilding full pass resources");
        createResources();
    }

    /**
     * Creates the HDR framebuffer and points the HDR render passes at it. A screen recreates it at a
     * frame boundary, and a Reforged RenderPass transitions the images of the framebuffer it holds,
     * so passes left on the old one transitioned its freed images. Following the framebuffer, rather
     * than rebuilding the passes, keeps the pipeline variants Reforged caches per RenderPass. The
     * format only changes in createResources, which builds new passes.
     */
    void createHdrFramebuffer(int width, int height) {
        this.hdrFramebuffer = framebuffer(width, height, pass.packHdrFormat);
        for (RenderPass hdrPass : new RenderPass[] {hdrRenderPass, hdrAuxRenderPass, hdrAuxClearDepthRenderPass}) {
            if (hdrPass != null) ((ChimeraRenderPassAccessor) hdrPass).chimera$setFramebuffer(this.hdrFramebuffer);
        }
    }

    /**
     * The pass the world target opens with each frame: clearing colour and depth, or, when the HDR
     * image carries colortex0 from the last frame (colortex0Clear=false, PackPostTargets.carriesColortex0),
     * loading colour and clearing depth. clearWorldTarget then clears colour where Iris would.
     */
    RenderPass worldOpenPass() {
        return pass.packPostTargets.carriesColortex0() ? hdrAuxClearDepthRenderPass : hdrRenderPass;
    }

    /**
     * The pass that resumes {@code bound} after Reforged 0.3.5 splits it (MainPass.resumeRenderPass):
     * Renderer.flushCmds for a readback and RenderTargetBlit for a blit end the bound pass and begin
     * it again. A world or output pass that clears resumes with its load twin, so what was drawn
     * before the split stays, as DefaultMainPass resumes its own passes. Any other pass resumes
     * as it is.
     */
    RenderPass resumePass(RenderPass bound) {
        if (bound == hdrRenderPass || bound == hdrAuxClearDepthRenderPass) return hdrAuxRenderPass;
        if (bound == compositeRenderPass || bound == compositeAuxClearDepthRenderPass) return compositeAuxRenderPass;
        return bound;
    }

    void createOutputFramebuffer(int width, int height) {
        // Beryl's stable MainPass output uses VK_FORMAT_R8G8B8A8_UNORM (37).
        this.compositeFramebuffer = framebuffer(width, height, 37);
        // Pack final renders here in GL row order, with the output's formats.
        this.packFinalFramebuffer = framebuffer(width, height, 37);
    }

    /**
     * A framebuffer as Reforged's builder makes one, with transfer usage on its colour image: the
     * post chain copies colortex0 into and out of the HDR image, and screenshots copy the output.
     * Reforged 0.3.0's builder dropped that usage; 0.3.5's gives it again, and Chimera asks for it.
     */
    private static Framebuffer framebuffer(int width, int height, int colorFormat) {
        VulkanImage color = VulkanImage.builder(width, height)
                .setFormat(colorFormat)
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .setLinearFiltering(true).setClamp(true).createVulkanImage();
        VulkanImage depth = VulkanImage.createDepthImage(Vulkan.getDefaultDepthFormat(), width, height,
                MainDepthSnapshot.depthAttachmentUsage(), false, true);
        return Framebuffer.builder(color, depth).build();
    }

    void createRenderPasses() {
        // World depth is always stored: the world pass is suspended mid-frame
        // (shadow segment, family windows) and resumed with a depth load. A
        // DONT_CARE store let every later layer test against discarded depth.
        RenderPass.Builder b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        this.hdrRenderPass = b.build();

        b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.hdrAuxRenderPass = b.build();

        b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.hdrAuxClearDepthRenderPass = b.build();
        // The world keeps GL row order for pack screen-space math.
        ChimeraRasterOrientation.markGlOriented(this.hdrRenderPass);
        ChimeraRasterOrientation.markGlOriented(this.hdrAuxRenderPass);
        ChimeraRasterOrientation.markGlOriented(this.hdrAuxClearDepthRenderPass);

        // Output depth is stored for the same reason: the hand and the GUI split
        // the output pass many times (rebindMainTarget), each resuming with a
        // depth load. A DONT_CARE store left every GUI draw after a split testing
        // against discarded depth; NVIDIA keeps it, other drivers need not.
        b = RenderPass.builder(this.compositeFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        this.compositeRenderPass = b.build();

        b = RenderPass.builder(this.compositeFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.compositeAuxRenderPass = b.build();

        b = RenderPass.builder(this.compositeFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.compositeAuxClearDepthRenderPass = b.build();

        b = RenderPass.builder(this.packFinalFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.packFinalRenderPass = b.build();

        b = RenderPass.builder(Vulkan.getSwapChain());
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.presentRenderPass = b.build();
    }

    void createHdrInteropTextures() {
        this.hdrColorGlId = createGlAlias(this.hdrFramebuffer.getColorAttachment());
        this.hdrDepthGlId = createGlAlias(this.hdrFramebuffer.getDepthAttachment());
    }

    void createOutputInteropTextures() {
        this.compositeColorGlId = createGlAlias(this.compositeFramebuffer.getColorAttachment());
        this.compositeDepthGlId = createGlAlias(this.compositeFramebuffer.getDepthAttachment());
    }

    boolean hdrResourcesLive() {
        return live(this.hdrFramebuffer, this.hdrColorGlId, this.hdrDepthGlId);
    }

    boolean outputResourcesLive() {
        return live(this.compositeFramebuffer, this.compositeColorGlId, this.compositeDepthGlId);
    }

    long hdrImageId() {
        return this.hdrFramebuffer == null ? 0L : this.hdrFramebuffer.getColorAttachment().getId();
    }

    void releaseHdrInteropTextures() {
        retireGlAlias(this.hdrColorGlId);
        retireGlAlias(this.hdrDepthGlId);
        this.hdrColorGlId = 0;
        this.hdrDepthGlId = 0;
    }

    void releaseOutputInteropTextures() {
        retireGlAlias(this.compositeColorGlId);
        retireGlAlias(this.compositeDepthGlId);
        this.compositeColorGlId = 0;
        this.compositeDepthGlId = 0;
    }

    void cleanUpFramebuffersAndPasses() {
        this.worldResourcesReady = false;
        this.outputResourcesReady = false;
        pass.depth.hdrDepthReadable = false;
        releaseHdrInteropTextures();
        releaseOutputInteropTextures();
        if (this.hdrFramebuffer != null) this.hdrFramebuffer.cleanUp();
        if (this.compositeFramebuffer != null) this.compositeFramebuffer.cleanUp();
        if (this.packFinalFramebuffer != null) this.packFinalFramebuffer.cleanUp();
        ChimeraRasterOrientation.unmark(this.hdrRenderPass);
        ChimeraRasterOrientation.unmark(this.hdrAuxRenderPass);
        ChimeraRasterOrientation.unmark(this.hdrAuxClearDepthRenderPass);
        if (this.hdrRenderPass != null) this.hdrRenderPass.cleanUp();
        if (this.hdrAuxRenderPass != null) this.hdrAuxRenderPass.cleanUp();
        if (this.hdrAuxClearDepthRenderPass != null) this.hdrAuxClearDepthRenderPass.cleanUp();
        if (this.compositeRenderPass != null) this.compositeRenderPass.cleanUp();
        if (this.packFinalRenderPass != null) this.packFinalRenderPass.cleanUp();
        if (this.compositeAuxRenderPass != null) this.compositeAuxRenderPass.cleanUp();
        if (this.compositeAuxClearDepthRenderPass != null) this.compositeAuxClearDepthRenderPass.cleanUp();
        if (this.presentRenderPass != null) this.presentRenderPass.cleanUp();
        this.hdrFramebuffer = null;
        this.compositeFramebuffer = null;
        this.packFinalFramebuffer = null;
        pass.currentFramebuffer = null;
        this.hdrRenderPass = null;
        this.hdrAuxRenderPass = null;
        this.hdrAuxClearDepthRenderPass = null;
        this.compositeRenderPass = null;
        this.packFinalRenderPass = null;
        this.compositeAuxRenderPass = null;
        this.compositeAuxClearDepthRenderPass = null;
        this.presentRenderPass = null;
    }

    private static boolean live(Framebuffer framebuffer, int colorGlId, int depthGlId) {
        return framebuffer != null && colorGlId != 0 && depthGlId != 0
                && framebuffer.getColorAttachment().getId() != 0L
                && framebuffer.getDepthAttachment().getId() != 0L;
    }

    // OpenGL texture names for Chimera-owned images. VulkanMod binds a draw's sampled images from
    // RenderSystem's texture units, which hold OpenGL names, so an image a draw samples through a unit
    // needs one, as VulkanMod names its own main images.


    /** A new OpenGL texture name for {@code image}. */
    private static int createGlAlias(VulkanImage image) {
        int id = GlTexture.genTextureId();
        GlTexture.bindIdToImage(id, image);
        return id;
    }

    /** Deletes an alias name without freeing its image, which its owner frees. */
    private static void retireGlAlias(int id) {
        if (id == 0 || GlTexture.getTexture(id) == null) return;
        GlTexture.setVulkanImage(id, null);
        GlTexture.glDeleteTextures(id);
    }
}
