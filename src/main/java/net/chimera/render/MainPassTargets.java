package net.chimera.render;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.MainDepthSnapshot;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The main pass's render targets: the HDR world framebuffer, the composite and pack-final output
 * framebuffers with their render passes, and the OpenGL texture names the host reads the world
 * and output images through (Minecraft's main target asks the main pass for them).
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

    MainPassTargets(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /**
     * The HDR framebuffer and its render passes, together: Reforged's RenderPass transitions the
     * images of the framebuffer it was built for, so passes outliving a recreated framebuffer would
     * transition its freed images.
     */
    void createHdrFramebuffer(int width, int height) {
        this.hdrFramebuffer = framebuffer(width, height, pass.packHdrFormat);
        createHdrRenderPasses();
    }

    void releaseHdrFramebuffer() {
        if (this.hdrFramebuffer != null) this.hdrFramebuffer.cleanUp();
        ChimeraRasterOrientation.unmark(this.hdrRenderPass);
        ChimeraRasterOrientation.unmark(this.hdrAuxRenderPass);
        ChimeraRasterOrientation.unmark(this.hdrAuxClearDepthRenderPass);
        if (this.hdrRenderPass != null) this.hdrRenderPass.cleanUp();
        if (this.hdrAuxRenderPass != null) this.hdrAuxRenderPass.cleanUp();
        if (this.hdrAuxClearDepthRenderPass != null) this.hdrAuxClearDepthRenderPass.cleanUp();
        this.hdrFramebuffer = null;
        this.hdrRenderPass = null;
        this.hdrAuxRenderPass = null;
        this.hdrAuxClearDepthRenderPass = null;
    }

    void createOutputFramebuffer(int width, int height) {
        // Beryl's stable MainPass output uses VK_FORMAT_R8G8B8A8_UNORM (37).
        this.compositeFramebuffer = framebuffer(width, height, 37);
        // Pack final renders here in GL row order, with the output's formats.
        this.packFinalFramebuffer = framebuffer(width, height, 37);
    }

    /**
     * A framebuffer as Reforged's builder makes one, except that its colour image also allows
     * transfers: the post chain copies colortex0 into and out of the HDR image, and screenshots copy
     * the output. Reforged's builder drops the transfer usage upstream VulkanMod gave colour images.
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

    private void createHdrRenderPasses() {
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
    }

    void createOutputRenderPasses() {
        RenderPass.Builder b = RenderPass.builder(this.compositeFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.compositeRenderPass = b.build();

        b = RenderPass.builder(this.compositeFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.compositeAuxRenderPass = b.build();

        b = RenderPass.builder(this.compositeFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
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
        this.hdrColorGlId = GlImageAlias.create(this.hdrFramebuffer.getColorAttachment());
        this.hdrDepthGlId = GlImageAlias.create(this.hdrFramebuffer.getDepthAttachment());
    }

    void createOutputInteropTextures() {
        this.compositeColorGlId = GlImageAlias.create(this.compositeFramebuffer.getColorAttachment());
        this.compositeDepthGlId = GlImageAlias.create(this.compositeFramebuffer.getDepthAttachment());
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
        GlImageAlias.retire(this.hdrColorGlId);
        GlImageAlias.retire(this.hdrDepthGlId);
        this.hdrColorGlId = 0;
        this.hdrDepthGlId = 0;
    }

    void releaseOutputInteropTextures() {
        GlImageAlias.retire(this.compositeColorGlId);
        GlImageAlias.retire(this.compositeDepthGlId);
        this.compositeColorGlId = 0;
        this.compositeDepthGlId = 0;
    }

    void cleanUpFramebuffersAndPasses() {
        pass.resources.worldResourcesReady = false;
        pass.resources.outputResourcesReady = false;
        pass.depth.hdrDepthReadable = false;
        releaseHdrInteropTextures();
        releaseOutputInteropTextures();
        releaseHdrFramebuffer();
        if (this.compositeFramebuffer != null) this.compositeFramebuffer.cleanUp();
        if (this.packFinalFramebuffer != null) this.packFinalFramebuffer.cleanUp();
        if (this.compositeRenderPass != null) this.compositeRenderPass.cleanUp();
        if (this.packFinalRenderPass != null) this.packFinalRenderPass.cleanUp();
        if (this.compositeAuxRenderPass != null) this.compositeAuxRenderPass.cleanUp();
        if (this.compositeAuxClearDepthRenderPass != null) this.compositeAuxClearDepthRenderPass.cleanUp();
        if (this.presentRenderPass != null) this.presentRenderPass.cleanUp();
        this.compositeFramebuffer = null;
        this.packFinalFramebuffer = null;
        pass.currentFramebuffer = null;
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
}
