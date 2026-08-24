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

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Chimera's main render pass - Beryl-shaped multi-segment frame for MC 1.21.11:
 *
 *   - Nothing opens at frame start.
 *   - LevelRenderer.renderLevel HEAD opens the HDR segment (world -> RGBA16F).
 *   - renderLevel TAIL closes it and runs the composite segment (HDR -> final
 *     RGBA8 buffer). The swapchain is NOT touched yet.
 *   - Post chains / hand / GUI then run pass-closed or re-enter the FINAL
 *     buffer through rebindMainTarget (color LOAD, depth CLEAR) - drawing on
 *     top of the composited scene.
 *   - MainPass.end (real frame end, after GUI) blits final -> swapchain and
 *     transitions to present.
 *
 * This ordering is proven on VulkanMod 0.6.8+1.21.11 by Beryl 0.2.1-alpha;
 * foreign encoder work (post-chain barriers, texture clears) only ever sees a
 * closed pass outside the level segment.
 */
public class ChimeraMainPass implements MainPass {

    private Framebuffer hdrFramebuffer;
    private Framebuffer finalFramebuffer;

    private RenderPass hdrRenderPass;
    private RenderPass finalAuxRenderPass;
    private RenderPass compositeRenderPass;
    private RenderPass presentRenderPass;

    private GraphicsPipeline compositePipeline;
    private GraphicsPipeline presentPipeline;

    /** True while we are inside the level segment (HDR pass recording). */
    private boolean inLevelSegment;
    /** True once the composite segment has run for the current frame. */
    private boolean compositedThisFrame;

    // Blaze3D interop views of the FINAL buffer (what vanilla treats as the
    // main render target) - mirrors the host DefaultMainPass behavior.
    private GpuTexture colorAttachmentTexture;
    private GpuTextureView colorAttachmentTextureView;
    private GpuTexture depthAttachmentTexture;

    public ChimeraMainPass() {
        createResources();
        Renderer.getInstance().addOnResizeCallback(this::onResize);
    }

    // ------------------------------------------------------------------
    // Segment control (invoked by mixins around level rendering)
    // ------------------------------------------------------------------

    /** Opens the HDR segment. Called at LevelRenderer.renderLevel HEAD. */
    public void openLevelSegment() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            openLevelSegment(commandBuffer, stack);
        }
    }

    private void openLevelSegment(VkCommandBuffer commandBuffer, MemoryStack stack) {
        VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
        hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        Renderer.getInstance().beginRenderPass(this.hdrRenderPass, this.hdrFramebuffer);

        Renderer.setViewport(0, 0, this.hdrFramebuffer.getWidth(), this.hdrFramebuffer.getHeight(), stack);
        VK10.vkCmdSetScissor(commandBuffer, 0, this.hdrFramebuffer.scissor(stack));

        Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        this.inLevelSegment = true;
        this.compositedThisFrame = false;
    }

    /** Closes the HDR segment and composites HDR -> final. Called at TAIL. */
    public void closeLevelSegmentAndComposite() {
        if (!this.inLevelSegment) {
            return;
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        this.inLevelSegment = false;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
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

            this.compositedThisFrame = true;
        }
    }

    private void drawFullscreen(VkCommandBuffer commandBuffer, GraphicsPipeline pipeline) {
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
        VK10.vkCmdDraw(commandBuffer, 3, 1, 0, 0);
    }

    // ------------------------------------------------------------------
    // MainPass contract
    // ------------------------------------------------------------------

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        // Intentionally empty: segments open/close around level rendering.
        this.inLevelSegment = false;
        this.compositedThisFrame = false;
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        // Close whatever is open (aux rebinds from hand/GUI phase).
        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Present segment only: the final buffer already holds the frame -
            // world+hand composited at level-render tail, GUI/hand overlays on
            // top via aux rebinding. Never re-composite here: menus draw
            // straight into the final buffer and a late composite would wipe
            // them.
            VulkanImage finalColor = this.finalFramebuffer.getColorAttachment();

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

    // ------------------------------------------------------------------
    // Main-target interop
    // ------------------------------------------------------------------

    @Override
    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Framebuffer target = this.finalFramebuffer;

        if (Renderer.getInstance().getBoundFramebuffer() == target) {
            return;
        }

        // Foreign code may have flipped layouts while the pass was closed.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            target.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }

        Renderer.getInstance().beginRenderPass(this.finalAuxRenderPass, target);
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().endRenderPass(commandBuffer);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.finalFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(this.finalFramebuffer.getColorAttachment());
    }

    @Override
    public Framebuffer getMainFramebuffer() {
        return this.finalFramebuffer;
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
        // HDR segment: cleared at open, color stored for the composite read.
        RenderPass.Builder b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.hdrRenderPass = b.build();

        // Post-level re-entry into the FINAL buffer: preserve the composited
        // scene (hand/GUI draw on top), give hand rendering a clean depth.
        b = RenderPass.builder(this.finalFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.finalAuxRenderPass = b.build();

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

        VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(this.finalFramebuffer.getColorAttachment());
        this.colorAttachmentTexture = attachmentTexture;
        this.colorAttachmentTextureView = device.createTextureView(attachmentTexture);
        this.depthAttachmentTexture = device.gpuTextureFromVulkanImage(this.finalFramebuffer.getDepthAttachment());
    }

    private void cleanUpFramebuffersAndPasses() {
        if (this.hdrFramebuffer != null) this.hdrFramebuffer.cleanUp(true);
        if (this.finalFramebuffer != null) this.finalFramebuffer.cleanUp(true);
        if (this.hdrRenderPass != null) this.hdrRenderPass.cleanUp();
        if (this.finalAuxRenderPass != null) this.finalAuxRenderPass.cleanUp();
        if (this.compositeRenderPass != null) this.compositeRenderPass.cleanUp();
        if (this.presentRenderPass != null) this.presentRenderPass.cleanUp();
        this.hdrFramebuffer = null;
        this.finalFramebuffer = null;
        this.hdrRenderPass = null;
        this.finalAuxRenderPass = null;
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
