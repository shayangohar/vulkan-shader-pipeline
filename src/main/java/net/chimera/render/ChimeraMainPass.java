package net.chimera.render;
import java.util.Iterator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.chimera.mixin.WorldRendererAccessor;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.buffer.DrawParametersBuffer;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
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
 * Chimera's main render pass — two-buffer segmented frame:
 *
 *   - renderLevel HEAD opens the HDR segment (world -> RGBA16F).
 *   - At the SOLID section-layer tail (WorldRendererMixin RETURN),
 *     renderShadowSegment closes the HDR pass, records the shadow map
 *     (terrain re-rendered from the light's perspective via
 *     renderSectionLayer + the shadowPassActive redirect), and reopens HDR.
 *     Running after cullTerrain AND after the main SOLID draws is required:
 *     those are what fill VulkanMod's section queues and params buffers,
 *     and the shadow phase must see exactly the state the solid pass drew.
 *   - Terrain, entities, hand, and GUI draw into the HDR buffer via
 *     rebindMainTarget / the encoder alias.
 *   - MainPass.end: close HDR -> present HDR directly to swapchain via a
 *     fullscreen passthrough.
 *
 * No intermediate final buffer, no phase flip, no composite pass. One target,
 * one post pass at frame end. GUI values are LDR and stored correctly in
 * RGBA16F. When a post-processing stack is needed later, a composite pass
 * slots in between the HDR close and the present inside end().
 */
public class ChimeraMainPass implements MainPass {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("chimera");

    /** Diagnostic: -Dchimera.traceTransitions logs every chimera image transition. */
    private static final boolean TRACE_TRANSITIONS = ChimeraRenderer.debugFlag("chimera.traceTransitions");

    private Framebuffer hdrFramebuffer;
    private RenderPass hdrRenderPass;
    private RenderPass hdrAuxRenderPass;
    private RenderPass hdrAuxClearDepthRenderPass;
    private RenderPass presentRenderPass;
    private Framebuffer compositeFramebuffer;
    private RenderPass compositeRenderPass;

    private GraphicsPipeline presentPipeline;
    private GraphicsPipeline compositePipeline;

    private ChimeraShadowMap shadowMap = new ChimeraShadowMap();

    /** True while inside the level segment (HDR pass recording). */
    private boolean levelPhase;
    /** Set when vanilla requests a depth clear while no pass is recording. */
    private boolean pendingDepthClear;
    /**
     * While true, rebindMainTarget() opens the SHADOW render pass instead of
     * the HDR target. renderSectionLayer always rebinds to the main target, so
     * this flag is what makes it draw terrain into the shadow map from the
     * light's perspective during the shadow phase.
     */
    private boolean shadowPassActive;
    /** Caps the empty-graph shadow diagnostic to a few lines per session. */
    private int shadowDiagLogs = 3;

    /**
     * Armed at level-segment HEAD, consumed at the SOLID layer tail
     * (WorldRendererMixin): guarantees exactly one shadow segment per
     * frame, rendered after the SOLID pass drew from the same section
     * state.
     */
    private boolean shadowPending;

    private GpuTexture hdrColorTexture;
    private GpuTextureView hdrColorTextureView;
    private GpuTexture hdrDepthTexture;
    private GpuTextureView hdrDepthTextureView;

    public ChimeraMainPass() {
        this.levelPhase = true;
        createResources();
        this.shadowMap.init();
        Renderer.getInstance().addOnResizeCallback(this::onResize);
    }

    /**
     * Records the shadow pass: re-renders SOLID terrain from the light's
     * perspective into the shadow framebuffer, then binds the shadow texture
     * for sampling by the terrain shader. Runs with no render pass open;
     * renderSectionLayer's rebindMainTarget opens the shadow pass (via the
     * shadowPassActive redirect) and this method closes it afterward.
     */
    public void renderShadowMap(double cameraX, double cameraY, double cameraZ) {
        VkCommandBuffer cmd = Renderer.getCommandBuffer();
        // TODO: compute from level time — fixed noon angle for M3 shadow testing
        float celestialAngle = 0.25F;
        this.shadowMap.updateLight(celestialAngle);

        // Prepare the shadow color attachment for the render pass.
        VulkanImage shadowColor = this.shadowMap.getShadowFramebuffer().getColorAttachment();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            trace("shadowPre", "shadowColor", shadowColor, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            shadowColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }

        // Make renderSectionLayer's rebindMainTarget() open the SHADOW pass
        // rather than the HDR target. renderSectionLayer itself opens, draws,
        // and leaves the pass open; we close it afterward.
        this.shadowPassActive = true;
        try {
            // Switch to the shadow pipeline (fills the MVP UBO via VRenderSystem).
            PipelineManager.setShaderGetter(rt -> this.shadowMap.getShadowPipeline());
            VRenderSystem.applyProjectionMatrix(this.shadowMap.getLightProjection());
            VRenderSystem.applyModelViewMatrix(this.shadowMap.getLightView());
            VRenderSystem.calculateMVP();

            WorldRenderer worldRenderer = WorldRenderer.getInstance();
            // Capped loop-state diagnostic rationale: captures what the main
            // opaque pass just drew from. If the shadow pass ever records zero
            // draws while idx0 values are non-zero, state was mutated between
            // the two calls; all-zero idx0 means DrawParametersBuffer was
            // zeroed in between.
            // uniqueOpaqueLayer folds SOLID into CUTOUT at mesh upload, so
            // the shadow pass must render the remapped opaque layer.
            TerrainRenderType opaqueType = TerrainRenderType.getRemapped(TerrainRenderType.SOLID);
            if (this.shadowDiagLogs > 0) {
                this.shadowDiagLogs--;
                int areas = 0;
                int eligible = 0;
                int totalSections = 0;
                String idx0 = "n/a";
                SectionGraph graph = ((WorldRendererAccessor) (Object) worldRenderer).chimera$getSectionGraph();
                if (graph != null && graph.getChunkAreaQueue() != null) {
                    for (Iterator<ChunkArea> areaIt = graph.getChunkAreaQueue().iterator(); areaIt.hasNext(); ) {
                        ChunkArea area = areaIt.next();
                        areas++;
                        totalSections += area.sectionQueue.size();
                        DrawBuffers drawBuffers = area.getDrawBuffers();
                        if (drawBuffers.getAreaBuffer(opaqueType) == null || area.sectionQueue.size() == 0) {
                            continue;
                        }
                        eligible++;
                        if (!"n/a".equals(idx0)) {
                            continue;
                        }
                        StringBuilder counts = new StringBuilder();
                        int n = 0;
                        for (RenderSection section : area.sectionQueue) {
                            if (n >= 3) break;
                            long paramsPtr = DrawParametersBuffer.getParamsPtr(
                                    drawBuffers.getDrawParamsPtr(),
                                    section.inAreaIndex,
                                    opaqueType.ordinal(),
                                    QuadFacing.UNDEFINED.ordinal());
                            counts.append(n == 0 ? "" : ',').append(DrawParametersBuffer.getIndexCount(paramsPtr));
                            n++;
                        }
                        idx0 = counts.toString();
                    }
                }
                LOGGER.info("[chimera] shadow loop: areas={}, eligible={}, totalSections={}, idx0=[{}], visibleSections={}, graphNeedsUpdate={}, type={}",
                        areas, eligible, totalSections, idx0,
                        worldRenderer.getVisibleSectionsCount(), worldRenderer.graphNeedsUpdate(), opaqueType);
            }
            worldRenderer.renderSectionLayer(
                    opaqueType,
                    cameraX, cameraY, cameraZ,
                    this.shadowMap.getLightView(),
                    this.shadowMap.getLightProjection()
            );
        } finally {
            this.shadowPassActive = false;
        }

        // Close the shadow render pass renderSectionLayer left open.
        Renderer.getInstance().endRenderPass(cmd);

        // Transition shadow map for sampling
        try (MemoryStack stack = MemoryStack.stackPush()) {
            trace("shadowPost", "shadowColor", shadowColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            shadowColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        // Restore terrain pipeline getter
        PipelineManager.setShaderGetter(rt -> ChimeraTerrainPipelines.getTerrainPipeline());

        // Bind shadow texture for terrain fragment shader sampling
        this.shadowMap.bindShadowTexture();
    }


    /**
     * Renders the shadow map at the tail of the SOLID section layer.
     * Closes the HDR pass opened at HEAD, draws terrain from the light's
     * perspective into the shadow map, then reopens the HDR pass (load ops)
     * so the remaining layers continue into it.
     *
     * Timing matters: by this point cullTerrain has filled VulkanMod's
     * section draw queues and the main SOLID pass has just drawn from them,
     * so the shadow phase sees exactly the state the solid pass saw —
     * never a possibly-empty fresh SectionGraph.
     */
    public void renderShadowSegment(double cameraX, double cameraY, double cameraZ) {
        if (!this.shadowMap.isInitialized()) {
            return;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        VkCommandBuffer cmd = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(cmd);
        try {
            this.renderShadowMap(cameraX, cameraY, cameraZ);
        } finally {
            // Reopen the HDR pass (load ops) for the terrain layers.
            this.rebindMainTarget();
        }
    }

    /** Consumes the frame's shadow-pending flag; true at most once per level segment. */
    public boolean consumeShadowPending() {
        boolean p = this.shadowPending;
        this.shadowPending = false;
        return p;
    }

    // ------------------------------------------------------------------
    // Segment control
    // ------------------------------------------------------------------

    public void openLevelSegment() {
        if (this.hdrFramebuffer == null) {
            return;
        }

        // Arm this frame's shadow segment; consumed at the SOLID layer tail.
        this.shadowPending = true;

        // Terrain shaders sample the shadow map at slot 3 from their very
        // first draw of the frame; the shadow segment only rebinds it at the
        // opaque layer tail - after that draw has already bound descriptors.
        // Bind up-front so the first frames cannot hit "Sampler3 has no
        // image bound" on chimera_terrain.
        this.shadowMap.bindShadowTexture();

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            trace("openHdr", "hdrColor", hdrColor, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            Renderer.getInstance().beginRenderPass(this.hdrRenderPass, this.hdrFramebuffer);

            Renderer.setViewport(0, 0, this.hdrFramebuffer.getWidth(), this.hdrFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.hdrFramebuffer.scissor(stack));

            Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            this.levelPhase = true;
            this.pendingDepthClear = false;
        }
    }

    // ------------------------------------------------------------------
    // MainPass contract
    // ------------------------------------------------------------------

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.levelPhase = false;
        this.pendingDepthClear = false;
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        // Close the HDR pass (or whatever aux pass is open from hand/GUI).
        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            VulkanImage compositeColor = this.compositeFramebuffer.getColorAttachment();

            VRenderSystem.disableDepthTest();
            VRenderSystem.disableCull();
            VRenderSystem.disableBlend();

            // Composite segment: fullscreen pass over the finished frame.
            // Identity shader today; tone mapping / grading stack on here.
            trace("endHdrRead", "hdrColor", hdrColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            compositeColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            Renderer.getInstance().beginRenderPass(this.compositeRenderPass, this.compositeFramebuffer);
            VTextureSelector.bindTexture(hdrColor);
            drawFullscreen(commandBuffer, this.compositePipeline);
            Renderer.getInstance().endRenderPass(commandBuffer);
            trace("endCompPost", "compositeColor", compositeColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            compositeColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            // Present blit samples the composite result, not raw HDR.
            VTextureSelector.bindTexture(compositeColor);
            SwapChain swapChain = Renderer.getInstance().getSwapChain();
            if (swapChain.hasImages()) {
                trace("presentSwap", "swapchain", swapChain.getColorAttachment(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                Renderer.getInstance().beginRenderPass(this.presentRenderPass, swapChain);
                drawFullscreen(commandBuffer, this.presentPipeline);
                Renderer.getInstance().endRenderPass(commandBuffer);

                trace("presentSrc", "swapchain", swapChain.getColorAttachment(), VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
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
    // Main-target interop
    // ------------------------------------------------------------------

    @Override
    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        // Shadow-phase override: renderSectionLayer targets the shadow map.
        // beginRenderPass sets the viewport and scissor to the shadow size.
        if (this.shadowPassActive) {
            Framebuffer shadow = this.shadowMap.getShadowFramebuffer();
            if (Renderer.getInstance().getBoundFramebuffer() == shadow) {
                return;
            }
            Renderer.getInstance().beginRenderPass(this.shadowMap.getShadowRenderPass(), shadow);
            return;
        }

        Framebuffer target = this.hdrFramebuffer;

        if (Renderer.getInstance().getBoundFramebuffer() == target) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            trace("rebind", "hdrColor", target.getColorAttachment(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            target.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }

        RenderPass pass = this.pendingDepthClear ? this.hdrAuxClearDepthRenderPass : this.hdrAuxRenderPass;
        this.pendingDepthClear = false;
        Renderer.getInstance().beginRenderPass(pass, target);
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        VulkanImage color = this.hdrFramebuffer.getColorAttachment();

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
        return this.hdrFramebuffer;
    }

    @Override
    public GpuTexture getColorAttachment() {
        return this.hdrColorTexture;
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.hdrColorTextureView;
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.hdrDepthTexture;
    }

    /** Records a depth clear to apply at the next level-phase reopen. */
    public void requestPendingDepthClear() {
        this.pendingDepthClear = true;
    }

    /** True when the view belongs to chimera's HDR buffer. */
    public boolean isFamilyView(GpuTextureView view) {
        return view == this.hdrColorTextureView;
    }

    /** The live main-target color texture for the current phase. */
    public GpuTexture currentMainColorTexture() {
        return this.hdrColorTexture;
    }

    /**
     * Re-enters the HDR buffer applying any pending depth clear.
     */
    public void reopenWithPendingClear() {
        if (!this.levelPhase) {
            return;
        }
        this.requestPendingDepthClear();
        this.rebindMainTarget();
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    @Override
    public void cleanUp() {
        this.shadowMap.cleanUp();
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

        // 97 = VK_FORMAT_R16G16B16A16_SFLOAT
        this.hdrFramebuffer = new Framebuffer.Builder("chimeraHdr", width, height, 1, true)
                .setFormat(97)
                .build();

        // Composite target: identity pass proving the ping-pong machinery;
        // a real post stack replaces the shader later. Depth rides along
        // unused (DONT_CARE ops) to mirror every other chimera framebuffer.
        this.compositeFramebuffer = new Framebuffer.Builder("chimeraComposite", width, height, 1, true)
                .setFormat(97)
                .build();

        createRenderPasses();
        createPipelines();
        createInteropTextures();
    }

    private void createRenderPasses() {
        // HDR segment: cleared at open, color stored for the present read.
        RenderPass.Builder b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.hdrRenderPass = b.build();

        // Re-entry (vanilla passes alias here): preserve everything so far.
        b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.hdrAuxRenderPass = b.build();

        // Re-entry with depth clear (hand rendering after vanilla's request).
        b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.hdrAuxClearDepthRenderPass = b.build();

        // Present onto the swapchain.
        b = RenderPass.builder(Renderer.getInstance().getSwapChain());
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.presentRenderPass = b.build();

        // Composite: full overwrite each frame from the finished HDR color.
        b = RenderPass.builder(this.compositeFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.compositeRenderPass = b.build();
    }

    private void createPipelines() {
        this.presentPipeline = ChimeraPostPipelines.create("chimera_present");
        this.compositePipeline = ChimeraPostPipelines.create("chimera_composite");
    }

    private void createInteropTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();

        VkGpuTexture hdrTex = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getColorAttachment());
        this.hdrColorTexture = hdrTex;
        this.hdrColorTextureView = device.createTextureView(hdrTex);
        this.hdrDepthTexture = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getDepthAttachment());
        this.hdrDepthTextureView = device.createTextureView(this.hdrDepthTexture);
    }

    private void cleanUpFramebuffersAndPasses() {
        if (this.hdrFramebuffer != null) this.hdrFramebuffer.cleanUp(true);
        if (this.hdrRenderPass != null) this.hdrRenderPass.cleanUp();
        if (this.hdrAuxRenderPass != null) this.hdrAuxRenderPass.cleanUp();
        if (this.hdrAuxClearDepthRenderPass != null) this.hdrAuxClearDepthRenderPass.cleanUp();
        if (this.presentRenderPass != null) this.presentRenderPass.cleanUp();
        if (this.compositeFramebuffer != null) this.compositeFramebuffer.cleanUp(true);
        if (this.compositeRenderPass != null) this.compositeRenderPass.cleanUp();
        this.hdrFramebuffer = null;
        this.hdrRenderPass = null;
        this.hdrAuxRenderPass = null;
        this.hdrAuxClearDepthRenderPass = null;
        this.presentRenderPass = null;
        this.compositeFramebuffer = null;
        this.compositeRenderPass = null;
    }

    private void cleanUpPipelines() {
        if (this.presentPipeline != null) this.presentPipeline.cleanUp();
        if (this.compositePipeline != null) this.compositePipeline.cleanUp();
        this.presentPipeline = null;
        this.compositePipeline = null;
    }

    /**
     * Diagnostic: logs every chimera image transition when
     * -Dchimera.traceTransitions=true. The last traced line before a native
     * crash names the faulting site and image.
     */
    private static void trace(String site, String image, VulkanImage img, int toLayout) {
        if (!TRACE_TRANSITIONS) {
            return;
        }
        LOGGER.info("[chimera] tr {} {} {}->{}", site, image,
                layoutName(img.getCurrentLayout()), layoutName(toLayout));
    }

    private static String layoutName(int layout) {
        return switch (layout) {
            case 0 -> "UNDEFINED";
            case 2 -> "COLOR_ATTACHMENT";
            case 5 -> "SHADER_READ_ONLY";
            case 6 -> "GENERAL";
            case 7 -> "TRANSFER_DST";
            case 1000001002 -> "PRESENT_SRC";
            default -> "vk" + layout;
        };
    }
}
