package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.function.IntSupplier;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Chimera's main render pass - the M3 multi-segment frame:
 *
 *   segment 1 (HDR):       world rendering into an RGBA16F offscreen target
 *   segment 2 (composite): fullscreen pass HDR -> final framebuffer
 *   segment 3 (present):   fullscreen pass final -> swapchain
 *
 * All segments are dynamic-rendering stretches inside VulkanMod's single
 * command buffer per frame; layout transitions between them are recorded
 * explicitly. Composite/present shaders ship as identity passes so the
 * machinery is provably neutral until effects land on top.
 */
public class ChimeraMainPass implements MainPass {

    private Framebuffer hdrFramebuffer;
    private Framebuffer finalFramebuffer;

    private RenderPass hdrRenderPass;
    private RenderPass hdrAuxRenderPass;
    private RenderPass compositeRenderPass;
    private RenderPass presentRenderPass;

    private GraphicsPipeline compositePipeline;
    private GraphicsPipeline presentPipeline;

    private GpuTexture colorAttachmentTexture;
    private GpuTextureView colorAttachmentTextureView;
    private GpuTexture depthAttachmentTexture;
    private final IntSupplier imageIdxSupplier = () -> 0;

    public ChimeraMainPass() {
        createResources();
        Renderer.getInstance().addOnResizeCallback(this::onResize);
    }

    // ------------------------------------------------------------------
    // Frame segments
    // ------------------------------------------------------------------

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
        hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        Renderer.getInstance().beginRenderPass(this.hdrRenderPass, this.hdrFramebuffer);

        Renderer.setViewport(0, 0, this.hdrFramebuffer.getWidth(), this.hdrFramebuffer.getHeight(), stack);
        VK10.vkCmdSetScissor(commandBuffer, 0, this.hdrFramebuffer.scissor(stack));

        // World code issues its own clears later through the GL-compat layer;
        // clear now so early draws (sky) never see stale memory.
        Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();

            // --- composite segment: HDR -> final ---
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VTextureSelector.bindTexture(hdrColor);

            VRenderSystem.disableDepthTest();
            VRenderSystem.disableCull();
            VRenderSystem.disableBlend();
            VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);

            VulkanImage finalColor = this.finalFramebuffer.getColorAttachment();
            finalColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            Renderer.getInstance().beginRenderPass(this.compositeRenderPass, this.finalFramebuffer);
            drawFullscreen(commandBuffer, this.compositePipeline);
            Renderer.getInstance().endRenderPass(commandBuffer);

            // --- present segment: final -> swapchain ---
            SwapChain swapChain = Renderer.getInstance().getSwapChain();
            if (swapChain.hasImages()) {
                finalColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                VTextureSelector.bindTexture(finalColor);

                swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                Renderer.getInstance().beginRenderPass(this.presentRenderPass, swapChain);
                drawFullscreen(commandBuffer, this.presentPipeline);
                Renderer.getInstance().endRenderPass(commandBuffer);

                swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            }
        }

        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer: " + result);
        }
    }

    private void drawFullscreen(VkCommandBuffer commandBuffer, GraphicsPipeline pipeline) {
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
        VK10.vkCmdDraw(commandBuffer, 3, 1, 0, 0);
    }

    // ------------------------------------------------------------------
    // Main-target interop (vanilla code re-binding / reading the target)
    // ------------------------------------------------------------------

    @Override
    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        RenderPass bound = Renderer.getInstance().getBoundRenderPass();
        if (bound == this.hdrRenderPass || bound == this.hdrAuxRenderPass) {
            return;
        }

        // Foreign code (vanilla post chains, encoder clears) may have flipped
        // our color image to shader-read while the pass was closed; restore
        // the attachment layout before re-entering.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.hdrFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }

        Renderer.getInstance().beginRenderPass(this.hdrAuxRenderPass, this.hdrFramebuffer);
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        RenderPass bound = Renderer.getInstance().getBoundRenderPass();
        if (bound == this.hdrRenderPass || bound == this.hdrAuxRenderPass) {
            Renderer.getInstance().endRenderPass(commandBuffer);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.hdrFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(this.hdrFramebuffer.getColorAttachment());
    }

    @Override
    public Framebuffer getMainFramebuffer() {
        return this.hdrFramebuffer;
    }

    @Override
    public GpuTexture getColorAttachment() {
        return this.colorAttachmentTexture;
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.colorAttachmentTextureView;
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.depthAttachmentTexture;
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    @Override
    public void cleanUp() {
        cleanUpFramebuffersAndPasses();
        cleanUpPipelines();
    }

    @Override
    public void onResize() {
        createResources();
    }

    private void createResources() {
        cleanUpFramebuffersAndPasses();
        cleanUpPipelines();

        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        int width = Math.max(swapChain.getWidth(), 1);
        int height = Math.max(swapChain.getHeight(), 1);

        // setFormat takes raw VkFormat values:
        // 97 = VK_FORMAT_R16G16B16A16_SFLOAT (HDR chain), 37 = VK_FORMAT_R8G8B8A8_UNORM (final).
        this.hdrFramebuffer = new Framebuffer.Builder("chimeraHdr", width, height, 1, true)
                .setFormat(97)
                .build();
        this.finalFramebuffer = new Framebuffer.Builder("chimeraFinal", width, height, 1, true)
                .setFormat(37)
                .build();

        createRenderPasses();
        createPipelines();
        createInteropTextures();
    }

    private void createRenderPasses() {
        // HDR: cleared at frame start, color stored for the composite read.
        RenderPass.Builder b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.hdrRenderPass = b.build();

        // Aux: mid-frame rebinding after the pass closed at level-render end.
        // Depth CLEAR gives hand rendering a clean buffer on reopen.
        b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.hdrAuxRenderPass = b.build();

        // Composite into the final buffer.
        b = RenderPass.builder(this.finalFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.compositeRenderPass = b.build();

        // Present onto the swapchain, ending in the present layout.
        b = RenderPass.builder(Renderer.getInstance().getSwapChain());
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.presentRenderPass = b.build();
    }

    private void createPipelines() {
        this.compositePipeline = ChimeraPostPipelines.create("chimera_composite");
        this.presentPipeline = ChimeraPostPipelines.create("chimera_present");
    }

    private void createInteropTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();

        VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getColorAttachment());
        this.colorAttachmentTexture = attachmentTexture;
        this.colorAttachmentTextureView = device.createTextureView(attachmentTexture);
        this.depthAttachmentTexture = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getDepthAttachment());
    }

    private void cleanUpFramebuffersAndPasses() {
        if (this.hdrFramebuffer != null) this.hdrFramebuffer.cleanUp(true);
        if (this.finalFramebuffer != null) this.finalFramebuffer.cleanUp(true);
        if (this.hdrRenderPass != null) this.hdrRenderPass.cleanUp();
        if (this.hdrAuxRenderPass != null) this.hdrAuxRenderPass.cleanUp();
        if (this.compositeRenderPass != null) this.compositeRenderPass.cleanUp();
        if (this.presentRenderPass != null) this.presentRenderPass.cleanUp();
        this.hdrFramebuffer = null;
        this.finalFramebuffer = null;
        this.hdrRenderPass = null;
        this.hdrAuxRenderPass = null;
        this.compositeRenderPass = null;
        this.presentRenderPass = null;
    }

    private void cleanUpPipelines() {
        if (this.compositePipeline != null) this.compositePipeline.cleanUp();
        if (this.presentPipeline != null) this.presentPipeline.cleanUp();
        this.compositePipeline = null;
        this.presentPipeline = null;
    }
}
