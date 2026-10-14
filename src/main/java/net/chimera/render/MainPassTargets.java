package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The main pass's render targets: the HDR world framebuffer, the composite and pack-final output
 * framebuffers with their render passes, and the GPU texture views the host reads them through.
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
    GpuTexture hdrColorTexture;
    GpuTextureView hdrColorTextureView;
    GpuTexture hdrDepthTexture;
    GpuTextureView hdrDepthTextureView;
    GpuTexture compositeColorTexture;
    GpuTextureView compositeColorTextureView;
    GpuTexture compositeDepthTexture;
    GpuTextureView compositeDepthTextureView;

    MainPassTargets(ChimeraMainPass pass) {
        this.pass = pass;
    }

    void createHdrFramebuffer(int width, int height) {
        this.hdrFramebuffer = new Framebuffer.Builder("chimeraHdr", width, height, 1, true)
                .setFormat(pass.packHdrFormat)
                .build();
    }

    void createOutputFramebuffer(int width, int height) {
        // Beryl's stable MainPass output uses VK_FORMAT_R8G8B8A8_UNORM (37).
        this.compositeFramebuffer = new Framebuffer.Builder("chimeraOutput", width, height, 1, true)
                .setFormat(37)
                .build();
        // Pack final renders here in GL row order, with the output's formats.
        this.packFinalFramebuffer = new Framebuffer.Builder("chimeraPackFinal", width, height, 1, true)
                .setFormat(37)
                .build();
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

        b = RenderPass.builder(this.compositeFramebuffer);
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

        b = RenderPass.builder(Renderer.getInstance().getSwapChain());
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.presentRenderPass = b.build();
    }

    void createHdrInteropTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
        VkGpuTexture color = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getColorAttachment());
        this.hdrColorTexture = color;
        this.hdrColorTextureView = device.createTextureView(color);
        pass.mainFamilyViews.add(this.hdrColorTextureView);
        this.hdrDepthTexture = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getDepthAttachment());
        this.hdrDepthTextureView = device.createTextureView(this.hdrDepthTexture);
    }

    void createOutputInteropTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
        VkGpuTexture color = device.gpuTextureFromVulkanImage(this.compositeFramebuffer.getColorAttachment());
        this.compositeColorTexture = color;
        this.compositeColorTextureView = device.createTextureView(color);
        this.compositeDepthTexture = device.gpuTextureFromVulkanImage(this.compositeFramebuffer.getDepthAttachment());
        pass.mainFamilyViews.add(this.compositeColorTextureView);
        this.compositeDepthTextureView = device.createTextureView(this.compositeDepthTexture);
    }

    boolean hdrResourcesLive() {
        return this.hdrFramebuffer != null
                && this.hdrColorTexture != null
                && !this.hdrColorTexture.isClosed()
                && this.hdrDepthTexture != null
                && !this.hdrDepthTexture.isClosed()
                && this.hdrFramebuffer.getColorAttachment().getId() != 0L
                && this.hdrFramebuffer.getDepthAttachment().getId() != 0L;
    }

    boolean outputResourcesLive() {
        return this.compositeFramebuffer != null
                && this.compositeColorTexture != null
                && !this.compositeColorTexture.isClosed()
                && this.compositeDepthTexture != null
                && !this.compositeDepthTexture.isClosed()
                && this.compositeFramebuffer.getColorAttachment().getId() != 0L
                && this.compositeFramebuffer.getDepthAttachment().getId() != 0L;
    }

    long hdrImageId() {
        return this.hdrFramebuffer == null ? 0L : this.hdrFramebuffer.getColorAttachment().getId();
    }

    void releaseHdrInteropTextures() {
        if (this.hdrColorTextureView != null) this.hdrColorTextureView.close();
        if (this.hdrDepthTextureView != null) this.hdrDepthTextureView.close();
        if (this.hdrColorTexture != null) this.hdrColorTexture.close();
        if (this.hdrDepthTexture != null) this.hdrDepthTexture.close();
        this.hdrColorTexture = null;
        this.hdrColorTextureView = null;
        this.hdrDepthTexture = null;
        this.hdrDepthTextureView = null;
    }

    void releaseOutputInteropTextures() {
        if (this.compositeColorTextureView != null) this.compositeColorTextureView.close();
        if (this.compositeDepthTextureView != null) this.compositeDepthTextureView.close();
        if (this.compositeColorTexture != null) this.compositeColorTexture.close();
        if (this.compositeDepthTexture != null) this.compositeDepthTexture.close();
        this.compositeColorTexture = null;
        this.compositeColorTextureView = null;
        this.compositeDepthTexture = null;
        this.compositeDepthTextureView = null;
    }

    void cleanUpFramebuffersAndPasses() {
        pass.resources.worldResourcesReady = false;
        pass.resources.outputResourcesReady = false;
        pass.depth.hdrDepthReadable = false;
        releaseHdrInteropTextures();
        releaseOutputInteropTextures();
        if (this.hdrFramebuffer != null) this.hdrFramebuffer.cleanUp(false);
        if (this.compositeFramebuffer != null) this.compositeFramebuffer.cleanUp(false);
        if (this.packFinalFramebuffer != null) this.packFinalFramebuffer.cleanUp(true);
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
}
