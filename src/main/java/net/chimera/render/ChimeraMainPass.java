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
 *     Vanilla 1.21.11 wraps terrain/entities in its own RenderPass objects
 *     targeting the main render target; during this phase those re-enter the
 *     HDR buffer through rebindMainTarget.
 *   - renderLevel TAIL closes the HDR segment and composites HDR -> final.
 *     Phase flips: subsequent vanilla passes (and the pause-blur chain) target
 *     the FINAL buffer, drawing hand/GUI/post effects on top of the scene.
 *   - MainPass.end (real frame end) blits final -> swapchain and presents.
 *
 * This ordering is proven on VulkanMod 0.6.8+1.21.11 by Beryl 0.2.1-alpha.
 */
public class ChimeraMainPass implements MainPass {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("chimera");

    private Framebuffer hdrFramebuffer;
    private Framebuffer finalFramebuffer;

    private RenderPass hdrRenderPass;
    private RenderPass hdrAuxRenderPass;
    private RenderPass hdrAuxClearDepthRenderPass;
    private RenderPass finalAuxRenderPass;
    private RenderPass compositeRenderPass;
    private RenderPass presentRenderPass;

    private GraphicsPipeline compositePipeline;
    private GraphicsPipeline presentPipeline;

    /** True while inside the level segment (world renders into HDR). */
    private boolean levelPhase;
    /** Set when vanilla requests a depth clear while no pass is recording. */
    private boolean pendingDepthClear;
    // Blaze3D interop views, phase-selected: vanilla's "main render target"
    // must alias whichever buffer is current, or its passes bypass us.
    private GpuTexture hdrColorTexture;
    private GpuTextureView hdrColorTextureView;
    private GpuTexture hdrDepthTexture;
    private GpuTextureView hdrDepthTextureView;
    private GpuTexture finalColorTexture;
    private GpuTextureView finalColorTextureView;
    private GpuTexture finalDepthTexture;

    public ChimeraMainPass() {
        // Start in level phase so pipelines created at init see HDR formats.
        this.levelPhase = true;
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
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            Renderer.getInstance().beginRenderPass(this.hdrRenderPass, this.hdrFramebuffer);

            Renderer.setViewport(0, 0, this.hdrFramebuffer.getWidth(), this.hdrFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.hdrFramebuffer.scissor(stack));

            Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            this.levelPhase = true;

            // Redirect vanilla's level-rendering output at the Blaze3D level
            // (Beryl's mechanism): frame-graph passes targeting the main
            // render target attach our HDR views directly.
            RenderSystem.outputColorTextureOverride = this.hdrColorTextureView;
            RenderSystem.outputDepthTextureOverride = this.hdrDepthTextureView;

        }
    }

    /** Closes the HDR segment and composites HDR -> final. Called at TAIL. */
    public void closeLevelSegmentAndComposite() {
        if (!this.inLevelPass()) {
            return;
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        this.levelPhase = false;

        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;

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

        }
    }

    private boolean inLevelPass() {
        return this.levelPhase && Renderer.getInstance().getBoundFramebuffer() == this.hdrFramebuffer;
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
        this.levelPhase = false;
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        // Close whatever is open (aux rebinds from the post-level phase).
        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Present segment only: the final buffer already holds the frame -
            // world composited at level-render tail, hand/GUI/post on top via
            // aux rebinding. Never re-composite here: menus draw straight into
            // the final buffer and a late composite would wipe them.
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
    // Main-target interop (phase-aware: HDR during level, final after)
    // ------------------------------------------------------------------

    private Framebuffer currentTargetFramebuffer() {
        return this.levelPhase ? this.hdrFramebuffer : this.finalFramebuffer;
    }

    @Override
    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Framebuffer target = currentTargetFramebuffer();

        if (Renderer.getInstance().getBoundFramebuffer() == target) {
            return;
        }

        RenderPass pass;
        if (this.levelPhase) {
            // A depth clear requested while no pass was recording applies on
            // this reopen (first-person hand rendering).
            pass = this.pendingDepthClear ? this.hdrAuxClearDepthRenderPass : this.hdrAuxRenderPass;
            this.pendingDepthClear = false;
        } else {
            pass = this.finalAuxRenderPass;
        }

        // Foreign code may have flipped layouts while the pass was closed.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            target.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }

        Renderer.getInstance().beginRenderPass(pass, target);
    }

    /** Records a depth clear to apply at the next level-phase reopen. */
    public void requestPendingDepthClear() {
        this.pendingDepthClear = true;
    }

    /**
     * Re-enters the level-phase buffer applying any pending depth clear.
     * Called when vanilla requests a hand depth clear while no pass is
     * recording: waiting for a natural reopen leaves the flag unapplied when
     * the pass is still considered open, so we force the transition here.
     */
    public void reopenWithPendingClear() {
        if (!this.levelPhase) {
            return;
        }

        this.requestPendingDepthClear();
        this.rebindMainTarget();
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        VulkanImage color = currentTargetFramebuffer().getColorAttachment();

        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().endRenderPass(commandBuffer);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(color);
    }

    @Override
    public Framebuffer getMainFramebuffer() {
        return currentTargetFramebuffer();
    }

    @Override
    public GpuTexture getColorAttachment() {
        return this.levelPhase ? this.hdrColorTexture : this.finalColorTexture;
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.levelPhase ? this.hdrColorTextureView : this.finalColorTextureView;
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.levelPhase ? this.hdrDepthTexture : this.finalDepthTexture;
    }

    /** True when the view belongs to one of chimera's main-target buffers. */
    public boolean isFamilyView(GpuTextureView view) {
        return view == this.hdrColorTextureView || view == this.finalColorTextureView;
    }

    /** The live main-target color texture for the current phase. */
    public GpuTexture currentMainColorTexture() {
        return this.levelPhase ? this.hdrColorTexture : this.finalColorTexture;
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
        // Post-level re-entry into the FINAL buffer: preserve the composited
        // scene (hand/GUI draw on top), give hand rendering a clean depth.
        RenderPass.Builder b = RenderPass.builder(this.finalFramebuffer);
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

        createHdrPasses();
    }

    private void createHdrPasses() {
        // HDR segment: cleared at open, color stored for the composite read.
        RenderPass.Builder b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.hdrRenderPass = b.build();

        // Re-entry into HDR mid-level (vanilla passes alias here): preserve
        // everything drawn so far.
        b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.hdrAuxRenderPass = b.build();

        // Same, but applies a pending hand depth-clear on reopen.
        b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.hdrAuxClearDepthRenderPass = b.build();
    }

    private void createPipelines() {
        this.compositePipeline = ChimeraPostPipelines.create("chimera_composite");
        this.presentPipeline = ChimeraPostPipelines.create("chimera_present");
    }

    private void createInteropTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();

        VkGpuTexture finalTex = device.gpuTextureFromVulkanImage(this.finalFramebuffer.getColorAttachment());
        this.finalColorTexture = finalTex;
        this.finalColorTextureView = device.createTextureView(finalTex);
        this.finalDepthTexture = device.gpuTextureFromVulkanImage(this.finalFramebuffer.getDepthAttachment());

        createHdrInteropTextures();
    }

    private void createHdrInteropTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();

        VkGpuTexture hdrTex = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getColorAttachment());
        this.hdrColorTexture = hdrTex;
        this.hdrColorTextureView = device.createTextureView(hdrTex);
        this.hdrDepthTexture = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getDepthAttachment());
        this.hdrDepthTextureView = device.createTextureView(this.hdrDepthTexture);
    }

    private void cleanUpFramebuffersAndPasses() {
        if (this.hdrFramebuffer != null) this.hdrFramebuffer.cleanUp(true);
        if (this.finalFramebuffer != null) this.finalFramebuffer.cleanUp(true);
        if (this.hdrRenderPass != null) this.hdrRenderPass.cleanUp();
        if (this.hdrAuxRenderPass != null) this.hdrAuxRenderPass.cleanUp();
        if (this.hdrAuxClearDepthRenderPass != null) this.hdrAuxClearDepthRenderPass.cleanUp();
        if (this.finalAuxRenderPass != null) this.finalAuxRenderPass.cleanUp();
        if (this.compositeRenderPass != null) this.compositeRenderPass.cleanUp();
        if (this.presentRenderPass != null) this.presentRenderPass.cleanUp();
        this.hdrFramebuffer = null;
        this.finalFramebuffer = null;
        this.hdrRenderPass = null;
        this.hdrAuxRenderPass = null;
        this.hdrAuxClearDepthRenderPass = null;
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

