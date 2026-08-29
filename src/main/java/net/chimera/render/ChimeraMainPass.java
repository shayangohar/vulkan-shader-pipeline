package net.chimera.render;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackConfig;
import net.chimera.shaderpack.PackProgram;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackSource;
import net.chimera.shaderpack.PackMaterialResolver;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Chimera's main render pass uses the same world/output split as Beryl and
 * VulkShade:
 *
 *   - renderLevel HEAD opens the internal RGBA16F world target.
 *   - The SOLID layer tail temporarily records the shadow map and resumes the
 *     world target.
 *   - renderLevel RETURN resolves the HDR world into a separate output target.
 *     Entity passes inside renderLevel follow the HDR alias; hand, GUI, and
 *     post-chain passes follow the output alias.
 *   - MainPass.end presents the output target to the swapchain.
 *
 * The output target is the stable Minecraft main target. Screen resource churn
 * may retire internal world resources at their frame fence, but it never makes
 * GUI/post passes and world rendering transition the same Vulkan image.
 */
public class ChimeraMainPass implements MainPass {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("chimera");

    /** Diagnostic: -Dchimera.traceTransitions logs every chimera image transition. */
    private static final boolean TRACE_TRANSITIONS = ChimeraRenderer.debugFlag("chimera.traceTransitions");
    private Framebuffer hdrFramebuffer;
    private RenderPass hdrRenderPass;
    private RenderPass hdrAuxRenderPass;
    private RenderPass hdrAuxClearDepthRenderPass;
    private Framebuffer compositeFramebuffer;
    private RenderPass compositeRenderPass;
    private RenderPass compositeAuxRenderPass;
    private RenderPass compositeAuxClearDepthRenderPass;
    private RenderPass presentRenderPass;
    private Framebuffer currentFramebuffer;

    private GraphicsPipeline presentPipeline;
    private GraphicsPipeline compositePipeline;

    /** Pack programs swapped onto the resolve/present seams (-Dchimera.pack); null = identity. */
    private GraphicsPipeline packCompositePipeline;
    private GraphicsPipeline packFinalPipeline;
    private int[] packCompositeSlots;
    private int[] packFinalSlots;
    /** Parsed pack consts and programs (-Dchimera.pack); session-long, the property is fixed at launch. */
    private PackConfig.PackConfigData packConfig;
    private List<PackProgram> packPrograms;
    /** Static pack inventory plus the actual compile/install disposition. */
    private ConformanceReport conformanceReport;
    /** Pack pipelines are retained until the renderer session is torn down. */
    private boolean packPipelinesLoaded;
    /** HDR buffer format from the pack's colortex0Format; 97 (RGBA16F) when the pack says nothing. */
    private int packHdrFormat = 97;
    /** True when a loaded post source may sample the HDR depth attachment. */
    private boolean packNeedsHdrDepth;
    /** Tracks the one explicit HDR depth transition for the current level segment. */
    private boolean hdrDepthReadable;
    /** Pack geometry program (gbuffers_terrain) on the terrain path; null = chimera's terrain pipeline. */
    private GraphicsPipeline packGeometryPipeline;
    private int[] packGeometrySlots;
    /** Pack water program on the host translucent terrain path; null = host/chimera fallback. */
    private GraphicsPipeline packTranslucentPipeline;
    private int[] packTranslucentSlots;
    /** Pack shadow program on the shadow terrain path; null means fixed identity shadow. */
    private GraphicsPipeline packShadowPipeline;
    /** GL-registry slot-5 view of the shadow depth, for pack geometry sampling (shadowtex0). */
    private GpuTexture packShadowTexture;
    private GpuTextureView packShadowView;
    private long packShadowSourceId;

    private ChimeraShadowMap shadowMap = new ChimeraShadowMap();

    /** True after renderLevel opens the internal HDR world target. */
    private boolean levelPhase;
    /** Set when vanilla requests a depth clear while no pass is recording. */
    private boolean pendingDepthClear;
    /** Opens the stable output target before a screen frame starts. */
    private boolean earlyOutputPass = true;
    /** Internal world resources can retire independently from the output. */
    private boolean worldResourcesReady;
    /** The stable Minecraft main/output target and aliases are live. */
    private boolean outputResourcesReady;
    /**
     * While true, rebindMainTarget() opens the SHADOW render pass instead of
     * the current main target. renderSectionLayer always rebinds to the main
     * target, so this redirects terrain into the shadow map.
     */
    private boolean shadowPassActive;

    /**
     * Armed at level-segment HEAD, consumed at the SOLID layer tail
     * (WorldRendererMixin): guarantees exactly one shadow segment per frame.
     */
    private boolean shadowPending;
    /** Runtime report/log marker for the host's unique opaque/cutout policy. */
    private boolean shadowCutoutDispositionLogged;

    private GpuTexture hdrColorTexture;
    private GpuTextureView hdrColorTextureView;
    private GpuTexture hdrDepthTexture;
    private GpuTextureView hdrDepthTextureView;
    private GpuTexture compositeColorTexture;
    private GpuTextureView compositeColorTextureView;
    private GpuTexture compositeDepthTexture;
    private GpuTextureView compositeDepthTextureView;
    /** Pooled vanilla passes can retain any prior main-target view identity. */
    private final Set<GpuTextureView> mainFamilyViews =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public ChimeraMainPass() {
        this.levelPhase = true;
        createResources();
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

        // Prepare both shadow attachments for the render pass. The depth
        // attachment was shader-readable after the previous shadow segment.
        VulkanImage shadowColor = this.shadowMap.getShadowFramebuffer().getColorAttachment();
        VulkanImage shadowDepth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            trace("shadowPre", "shadowColor", shadowColor, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            trace("shadowPre", "shadowDepth", shadowDepth, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            shadowColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            shadowDepth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }

        // Make renderSectionLayer's rebindMainTarget() open the SHADOW pass
        // rather than the HDR target. renderSectionLayer itself opens, draws,
        // and leaves the pass open; we close it afterward.
        this.shadowPassActive = true;
        try {
            // Switch to the pack shadow pipeline when it is valid. The fixed
            // pipeline remains the identity fallback for the shadow family.
            PipelineManager.setShaderGetter(rt -> this.packShadowPipeline != null
                    ? this.packShadowPipeline : this.shadowMap.getShadowPipeline());
            VRenderSystem.applyProjectionMatrix(this.shadowMap.getLightProjection());
            VRenderSystem.applyModelViewMatrix(this.shadowMap.getLightView());
            VRenderSystem.calculateMVP();

            // uniqueOpaqueLayer folds SOLID into CUTOUT at mesh upload, so
            // the shadow pass must render the remapped opaque layer.
            TerrainRenderType opaqueType = TerrainRenderType.getRemapped(TerrainRenderType.SOLID);
            WorldRenderer.getInstance().renderSectionLayer(
                    opaqueType,
                    cameraX, cameraY, cameraZ,
                    this.shadowMap.getLightView(),
                    this.shadowMap.getLightProjection()
            );

            // uniqueOpaqueLayer maps SOLID and CUTOUT to the same queue. Do
            // not render that queue twice, but retain the separate path when
            // the host has distinct solid and cutout buffers.
            TerrainRenderType cutoutType = TerrainRenderType.getRemapped(TerrainRenderType.CUTOUT);
            recordShadowCutoutDisposition(cutoutType == opaqueType);
            if (cutoutType != opaqueType) {
                WorldRenderer.getInstance().renderSectionLayer(
                        cutoutType,
                        cameraX, cameraY, cameraZ,
                        this.shadowMap.getLightView(), this.shadowMap.getLightProjection());
            }
        } finally {
            this.shadowPassActive = false;
        }

        // Close the shadow render pass renderSectionLayer left open.
        Renderer.getInstance().endRenderPass(cmd);

        // Transition both shadow attachments for sampling.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            trace("shadowPost", "shadowColor", shadowColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            trace("shadowPost", "shadowDepth", shadowDepth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            shadowColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            shadowDepth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        // Restore the family-aware terrain getter after the shadow segment.
        PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);

        // Bind shadow texture for terrain fragment shader sampling
        this.shadowMap.bindShadowTexture();
        maintainPackShadowGoal();
    }


    /**
     * Renders the shadow map at the tail of the SOLID section layer.
     * Closes the HDR pass opened at HEAD, draws terrain from the light's
     * perspective into the shadow map, then reopens the HDR pass (load ops)
     * so the remaining layers continue into it.
     *
     * Timing matters: by this point cullTerrain has filled VulkanMod's
     * section draw queues and the main SOLID pass has just drawn from them,
     * so the shadow phase sees exactly the state the solid pass saw -
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
    /**
     * Mirrors Beryl's setScreen contract: keep the stable output target alive
     * for GUI/post work and retire only internal world resources when this
     * frame slot reaches its fence.
     */
    public void scheduleScreenResourceReset() {
        this.earlyOutputPass = true;
        if (TRACE_TRANSITIONS) {
            LOGGER.info("[chimera] scheduled world attachment reset frame={} hdrId={}",
                    Renderer.getCurrentFrame(), hdrImageId());
        }
        MemoryManager.getInstance().addFrameOp(this::refreshWorldResources);
    }

    /** Restores the stable output invariant before Renderer exposes this pass. */
    void prepareForInstall() {
        ensureOutputResources();
    }


    // ------------------------------------------------------------------
    // Segment control
    // ------------------------------------------------------------------

    public void openLevelSegment() {
        ensureWorldResources();
        Renderer.getInstance().endRenderPass();
        this.currentFramebuffer = this.hdrFramebuffer;
        this.hdrDepthReadable = false;

        // Compute the light once before any terrain draw. The shadow pass at
        // the opaque-layer tail reuses this exact state.
        if (this.shadowMap.isInitialized()) {
            this.shadowMap.updateLight(PackUniformProvider.currentCelestialAngle());
            prepareShadowForSampling();
        }

        // Arm this frame's shadow segment; consumed at the SOLID layer tail.
        this.shadowPending = true;

        // Terrain shaders sample the shadow map from their first draw.
        this.shadowMap.bindShadowTexture();
        maintainPackShadowGoal();

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            trace("openHdr", "hdrColor", hdrColor, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            Renderer.getInstance().beginRenderPass(this.hdrRenderPass, this.hdrFramebuffer);
            Renderer.setViewport(0, 0, this.hdrFramebuffer.getWidth(), this.hdrFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.hdrFramebuffer.scissor(stack));
            Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            this.levelPhase = true;
            this.pendingDepthClear = false;
        }
    }

    /**
     * Resolves the internal HDR world into the stable output target before
     * hand, GUI, and post-chain rendering begin.
     */
    public void finishLevelSegment() {
        if (!this.levelPhase || this.hdrFramebuffer == null) {
            return;
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            trace("resolveWorld", "hdrColor", hdrColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (this.packCompositePipeline != null && containsSlot(this.packCompositeSlots, 6)) {
                transitionHdrDepthForSampling(stack, commandBuffer);
            }

            GraphicsPipeline resolvePipeline =
                    this.packCompositePipeline != null ? this.packCompositePipeline : this.compositePipeline;
            VRenderSystem.disableDepthTest();
            VRenderSystem.disableCull();
            VRenderSystem.disableBlend();
            Renderer.getInstance().beginRenderPass(this.compositeRenderPass, this.compositeFramebuffer);
            VulkanImage[] prevPackSlots = null;
            if (this.packCompositePipeline != null) {
                prevPackSlots = bindPackSamplers(this.packCompositeSlots, hdrColor);
            } else {
                VTextureSelector.bindTexture(hdrColor);
            }
            drawFullscreen(commandBuffer, resolvePipeline);
            // boundTextures is a global table shared with the host renderer;
            // restore every slot the pack composite touched so identity-level
            // state survives the seam (and world-leave boundaries).
            if (prevPackSlots != null) {
                for (int i = 0; i < prevPackSlots.length; i++) {
                    VTextureSelector.bindTexture(this.packCompositeSlots[i], prevPackSlots[i]);
                }
            }
            // VulkShade restores these immediately after resolveForGui.
            // Without it, the first fullscreen GUI overlay inherits the
            // composite pass' disabled depth/cull state and destroys output.
            VRenderSystem.enableDepthTest();
            VRenderSystem.depthMask(true);
            VRenderSystem.enableCull();
            this.currentFramebuffer = this.compositeFramebuffer;
        }
    }

    // ------------------------------------------------------------------
    // MainPass contract
    // ------------------------------------------------------------------

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        ensureOutputResources();
        this.currentFramebuffer = this.compositeFramebuffer;
        this.levelPhase = false;
        this.pendingDepthClear = false;

        if (this.earlyOutputPass) {
            trace("beginOutput", "outputColor",
                    this.compositeFramebuffer.getColorAttachment(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            Renderer.getInstance().beginRenderPass(this.compositeRenderPass, this.compositeFramebuffer);
            Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        if (this.levelPhase && this.currentFramebuffer == this.hdrFramebuffer) {
            finishLevelSegment();
        }
        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage outputColor = this.compositeFramebuffer.getColorAttachment();
            trace("presentRead", "outputColor", outputColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            outputColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (this.packFinalPipeline != null && containsSlot(this.packFinalSlots, 6)) {
                transitionHdrDepthForSampling(stack, commandBuffer);
            }
            VulkanImage[] prevPackSlots = null;
            if (this.packFinalPipeline != null) {
                prevPackSlots = bindPackSamplers(this.packFinalSlots, outputColor);
            } else {
                VTextureSelector.bindTexture(outputColor);
            }

            SwapChain swapChain = Renderer.getInstance().getSwapChain();
            if (swapChain.hasImages()) {
                trace("presentSwap", "swapchain", swapChain.getColorAttachment(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                Renderer.getInstance().beginRenderPass(this.presentRenderPass, swapChain);
                // The present draw otherwise inherits the GUI's last viewport and
                // scissor, leaving the swapchain on its clear color; pin the full
                // swapchain like DefaultMainPass.begin does.
                Renderer.setViewport(0, 0, swapChain.getWidth(), swapChain.getHeight(), stack);
                VK10.vkCmdSetScissor(commandBuffer, 0, swapChain.scissor(stack));
                // VulkanMod binds a pipeline variant keyed on the CURRENT
                // VRenderSystem state (getCurrentPipelineState at bind). The
                // swapchain depth attachment is DONT_CARE (a depth-tested
                // present quad is culled by stale/self-written depth), and the
                // GUI draws just before this bind leave blend/colorMask at GUI
                // values: with the leftover SRC_ALPHA blend applied to the
                // output's zero alpha, or a cleared color mask, the present
                // quad writes nothing and the swapchain stays on the pass
                // clear. Force the same plain opaque blit state the composite
                // resolve uses, and restore the globals right after.
                boolean depthTest = VRenderSystem.depthTest;
                boolean depthMask = VRenderSystem.depthMask;
                int colorMask = VRenderSystem.getColorMask();
                boolean blendEnabled = PipelineState.blendInfo.enabled;
                boolean cullEnabled = VRenderSystem.cull;
                VRenderSystem.depthTest = false;
                VRenderSystem.depthMask = false;
                VRenderSystem.colorMask(true, true, true, true);
                VRenderSystem.disableBlend();
                VRenderSystem.disableCull();
                try {
                    drawFullscreen(commandBuffer,
                            this.packFinalPipeline != null ? this.packFinalPipeline : this.presentPipeline);
                    if (prevPackSlots != null) {
                        for (int i = 0; i < prevPackSlots.length; i++) {
                            int slot = this.packFinalSlots[i];
                            if (slot != 0) {
                                VTextureSelector.bindTexture(slot, prevPackSlots[i]);
                            }
                        }
                    }
                    // Identity's end-of-frame state after the present is
                    // slot 0 = outputColor. The captured slot 0 can be
                    // chimera's own bindAsTexture residue (hdrColor), which
                    // the next world-leave churn destroys; leaving it would
                    // hand the host a dead image at the transition.
                    if (this.packFinalPipeline != null) {
                        VTextureSelector.bindTexture(outputColor);
                    }
                } finally {
                    VRenderSystem.depthTest = depthTest;
                    VRenderSystem.depthMask = depthMask;
                    VRenderSystem.colorMask((colorMask & 1) != 0, (colorMask & 2) != 0,
                            (colorMask & 4) != 0, (colorMask & 8) != 0);
                    if (blendEnabled) {
                        VRenderSystem.enableBlend();
                    } else {
                        VRenderSystem.disableBlend();
                    }
                    VRenderSystem.cull = cullEnabled;
                }
                Renderer.getInstance().endRenderPass(commandBuffer);

                trace("presentSrc", "swapchain", swapChain.getColorAttachment(), VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
                swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            }
        }

        this.levelPhase = false;
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

    /**
     * Binds the textures for a pack post program's declared slots. colortexN
     * slots read the seam's color attachment (HDR world in the composite
     * resolve, composite output in the present); shadowtex0 reads the shadow
     * map; depthtex0 reads the HDR depth attachment. Every declared slot must
     * be bound so no descriptor references a slot that was never filled;
     * missing sources are skipped, never fatal.
     *
     * boundTextures is a global table shared with the host renderer, so this
     * returns the previously bound image for each slot (captured once per
     * slot, duplicates share their first capture) and the caller MUST restore
     * them after the seam draw. Descriptors are written at draw time from
     * boundTextures, so the restore is safe for the already-recorded frame.
     */
    private VulkanImage[] bindPackSamplers(int[] slots, VulkanImage colortexImage) {
        if (slots == null) {
            return null;
        }
        VulkanImage[] previous = new VulkanImage[slots.length];
        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            boolean duplicate = false;
            for (int j = 0; j < i; j++) {
                if (slots[j] == slot) {
                    previous[i] = previous[j];
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            previous[i] = VTextureSelector.getImage(slot);
            if (slot <= 3) {
                VTextureSelector.bindTexture(slot, colortexImage);
            } else if (slot == 5) {
                VulkanImage shadowDepth = this.shadowMap.getShadowFramebuffer() != null
                        ? this.shadowMap.getShadowFramebuffer().getDepthAttachment()
                        : null;
                if (shadowDepth != null) {
                    VTextureSelector.bindTexture(5, shadowDepth);
                }
            } else if (slot == 6) {
                if (this.hdrFramebuffer != null) {
                    VTextureSelector.bindTexture(6, this.hdrFramebuffer.getDepthAttachment());
                }
            }
        }
        return previous;
    }

    private void transitionHdrDepthForSampling(MemoryStack stack, VkCommandBuffer commandBuffer) {
        if (!this.packNeedsHdrDepth || this.hdrDepthReadable || this.hdrFramebuffer == null) {
            return;
        }
        VulkanImage hdrDepth = this.hdrFramebuffer.getDepthAttachment();
        trace("resolveWorldDepth", "hdrDepth", hdrDepth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        hdrDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.hdrDepthReadable = true;
    }

    // ------------------------------------------------------------------
    // Main-target interop
    // ------------------------------------------------------------------

    @Override
    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        if (this.shadowPassActive) {
            Framebuffer shadow = this.shadowMap.getShadowFramebuffer();
            if (Renderer.getInstance().getBoundFramebuffer() != shadow) {
                Renderer.getInstance().beginRenderPass(this.shadowMap.getShadowRenderPass(), shadow);
            }
            return;
        }

        Framebuffer target = this.currentFramebuffer != null
                ? this.currentFramebuffer
                : this.compositeFramebuffer;
        boolean forceDepthClear = this.pendingDepthClear;
        if (Renderer.getInstance().getBoundFramebuffer() == target && !forceDepthClear) {
            return;
        }

        RenderPass pass;
        String imageName;
        if (target == this.hdrFramebuffer) {
            pass = forceDepthClear ? this.hdrAuxClearDepthRenderPass : this.hdrAuxRenderPass;
            imageName = "hdrColor";
        } else {
            pass = forceDepthClear ? this.compositeAuxClearDepthRenderPass : this.compositeAuxRenderPass;
            imageName = "outputColor";
        }

        this.pendingDepthClear = false;
        trace("rebind", imageName, target.getColorAttachment(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        if (forceDepthClear) {
            Renderer.getInstance().endRenderPass(commandBuffer);
        }
        Renderer.getInstance().beginRenderPass(pass, target);
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        VulkanImage color = currentFramebuffer().getColorAttachment();

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
        return currentFramebuffer();
    }

    @Override
    public GpuTexture getColorAttachment() {
        return this.currentFramebuffer == this.hdrFramebuffer
                ? this.hdrColorTexture
                : this.compositeColorTexture;
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.currentFramebuffer == this.hdrFramebuffer
                ? this.hdrColorTextureView
                : this.compositeColorTextureView;
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.currentFramebuffer == this.hdrFramebuffer
                ? this.hdrDepthTexture
                : this.compositeDepthTexture;
    }

    /** Records a depth clear to apply when the current target reopens. */
    public void requestPendingDepthClear() {
        this.pendingDepthClear = true;
    }

    /** True when a pooled view belongs to either Chimera main-target phase. */
    public boolean isFamilyView(GpuTextureView view) {
        return this.mainFamilyViews.contains(view);
    }

    /** The live main-target color texture for the current phase. */
    public GpuTexture currentMainColorTexture() {
        return getColorAttachment();
    }

    /** Re-enters the current target applying any pending depth clear. */
    public void reopenWithPendingClear() {
        if (!this.levelPhase) {
            return;
        }
        this.requestPendingDepthClear();
        this.rebindMainTarget();
    }

    private Framebuffer currentFramebuffer() {
        return this.currentFramebuffer != null
                ? this.currentFramebuffer
                : this.compositeFramebuffer;
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    @Override
    public void cleanUp() {
        releasePackShadowView();
        this.shadowMap.cleanUp();
        cleanUpFramebuffersAndPasses();
        cleanUpPipelines();
        if (this.packCompositePipeline != null) this.packCompositePipeline.cleanUp();
        if (this.packFinalPipeline != null) this.packFinalPipeline.cleanUp();
        if (this.packGeometryPipeline != null) this.packGeometryPipeline.cleanUp();
        if (this.packTranslucentPipeline != null) this.packTranslucentPipeline.cleanUp();
        if (this.packShadowPipeline != null) this.packShadowPipeline.cleanUp();
        this.packCompositePipeline = null;
        this.packCompositeSlots = null;
        this.packFinalPipeline = null;
        this.packFinalSlots = null;
        this.packGeometryPipeline = null;
        this.packGeometrySlots = null;
        this.packTranslucentPipeline = null;
        this.packTranslucentSlots = null;
        this.packShadowPipeline = null;
        this.shadowCutoutDispositionLogged = false;
        this.packPipelinesLoaded = false;
        this.conformanceReport = null;
        this.packNeedsHdrDepth = false;
        this.hdrDepthReadable = false;
        ChimeraTerrainPipelines.setGeometryOverride(null);
        ChimeraTerrainPipelines.setTranslucentOverride(null);
        this.mainFamilyViews.clear();
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

        loadPackConfig();
        PackConfig.ShadowSettings shadowSettings = this.packConfig != null
                ? this.packConfig.shadowSettings()
                : new PackConfig.ShadowSettings(
                        PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION,
                        PackConfig.DEFAULT_SHADOW_DISTANCE,
                        Map.of(), List.of());
        this.shadowMap.init(shadowSettings.resolution(), shadowSettings.distance());
        createHdrFramebuffer(width, height);
        createOutputFramebuffer(width, height);
        createRenderPasses();
        createPipelines();
        createHdrInteropTextures();
        createOutputInteropTextures();
        this.worldResourcesReady = true;
        this.outputResourcesReady = true;
        this.currentFramebuffer = this.compositeFramebuffer;
        this.earlyOutputPass = true;
        loadPackPipelines();
    }

    private void createHdrFramebuffer(int width, int height) {
        this.hdrFramebuffer = new Framebuffer.Builder("chimeraHdr", width, height, 1, true)
                .setFormat(this.packHdrFormat)
                .build();
    }

    private void createOutputFramebuffer(int width, int height) {
        // Beryl's stable MainPass output uses VK_FORMAT_R8G8B8A8_UNORM (37).
        this.compositeFramebuffer = new Framebuffer.Builder("chimeraOutput", width, height, 1, true)
                .setFormat(37)
                .build();
    }

    private void createRenderPasses() {
        RenderPass.Builder b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, hdrDepthStoreOp());
        this.hdrRenderPass = b.build();

        b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, hdrDepthStoreOp());
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.hdrAuxRenderPass = b.build();

        b = RenderPass.builder(this.hdrFramebuffer);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, hdrDepthStoreOp());
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.hdrAuxClearDepthRenderPass = b.build();

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

        b = RenderPass.builder(Renderer.getInstance().getSwapChain());
        b.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        b.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        b.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        this.presentRenderPass = b.build();
    }

    private void createPipelines() {
        // NOTE: pipeline depth state is decided at BIND time by VulkanMod
        // (GraphicsPipeline.getHandle(PipelineState) <- getCurrentPipelineState
        // snapshots VRenderSystem globals), so the present draw disables depth
        // test/write at the draw site, not here.
        this.presentPipeline = ChimeraPostPipelines.create("chimera_present");
        this.compositePipeline = ChimeraPostPipelines.create("chimera_composite");
    }

    /**
     * Discovers and parses the pack once per session (-Dchimera.pack is fixed
     * at launch). Must run before createHdrFramebuffer so the pack's
     * colortex0Format can drive the HDR buffer format.
     */
    private void loadPackConfig() {
        if (this.packConfig != null) {
            if (this.conformanceReport == null) {
                String packDir = System.getProperty("chimera.pack");
                if (packDir != null && !packDir.isBlank()) {
                    this.conformanceReport = PackProbe.probe(Path.of(packDir));
                }
            }
            return;
        }
        String packDir = System.getProperty("chimera.pack");
        if (packDir == null || packDir.isBlank()) {
            LOGGER.info("[chimera] pack disabled (no -Dchimera.pack)");
            ChimeraTerrainPipelines.setMaterialResolver(PackMaterialResolver.empty());
            this.packNeedsHdrDepth = false;
            return;
        }

        Path dir = Path.of(packDir);
        PackSource.LoadResult result = PackSource.loadResult(dir);
        this.conformanceReport = PackProbe.probe(dir);
        this.packPrograms = result.programs();
        this.packNeedsHdrDepth = this.conformanceReport.programs().stream()
                .filter(program -> program.name().equals("composite") || program.name().equals("final"))
                .anyMatch(program -> program.samplers().contains("depthtex0"));
        PackMaterialResolver.ParseResult material = PackMaterialResolver.parse(result.shadersDir());
        ChimeraTerrainPipelines.setMaterialResolver(material.resolver());
        if (this.packPrograms.isEmpty()) {
            LOGGER.warn("[chimera] pack '{}' from {}: no programs found", dir.getFileName(), dir.toAbsolutePath());
            return;
        }
        this.packConfig = PackConfig.parse(this.packPrograms, result.shadersDir());
        this.packHdrFormat = this.packConfig.colortexFormats().getOrDefault(0, 97);
        LOGGER.info("[chimera] pack '{}' from {}: programs={}", dir.getFileName(),
                dir.toAbsolutePath(), this.packPrograms.stream().map(PackProgram::name).toList());
        LOGGER.info("[chimera] pack consts: {}, shadowSettings={}, drawBuffers={}",
                formatSummary(this.packConfig.colortexFormats()),
                this.packConfig.shadowSettings(),
                this.packConfig.drawBufferCount());
        for (String deviation : this.packConfig.shadowSettings().deviations()) {
            LOGGER.warn("[chimera] pack: {}", deviation);
        }
    }

    private String formatSummary(Map<Integer, Integer> formats) {
        if (formats.isEmpty()) {
            return "none";
        }
        StringBuilder summary = new StringBuilder();
        formats.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append("colortex").append(entry.getKey()).append("Format=").append(entry.getValue())
                    .append(" (").append(PackConfig.formatName(entry.getValue())).append(')');
        });
        return summary.toString();
    }

    /**
     * Loads the OptiFine-format pack pointed at by -Dchimera.pack and builds
     * its composite/final programs onto the post seams, gbuffers_terrain onto
     * the opaque terrain path, and gbuffers_water onto the translucent path.
     * A program that fails to load, convert, or
     * compile keeps the identity pipeline for its seam, so a bad pack can
     * never break the frame.
     */
    private void loadPackPipelines() {
        if (this.packPipelinesLoaded) {
            // Pack pipelines are pass-agnostic (VulkanMod builds pipeline
            // variants from the state at bind, render pass included), so a
            // retained object stays correct across createResources' pass
            // recreations. Keeping them session-long removes pack
            // pipeline/descriptor-set destruction (immediate vkDestroyPipeline
            // in GraphicsPipeline.cleanUp) from the createResources churn
            // that fires at screen openings - wedge objects must not churn
            // at frame boundaries. They are destroyed in cleanUp() only.
            return;
        }
        this.packCompositePipeline = null;
        this.packCompositeSlots = null;
        this.packFinalPipeline = null;
        this.packFinalSlots = null;
        this.packGeometryPipeline = null;
        this.packGeometrySlots = null;
        this.packTranslucentPipeline = null;
        this.packTranslucentSlots = null;
        this.packShadowPipeline = null;
        this.shadowCutoutDispositionLogged = false;
        this.packPipelinesLoaded = true;

        if (this.packPrograms == null) {
            // loadPackConfig found no pack (or no programs).
            if (this.conformanceReport != null) {
                this.conformanceReport.markUnattemptedAsFallback();
                LOGGER.info("[chimera] conformance {}", this.conformanceReport.toJson());
            }
            return;
        }

        String fixedVertex = ChimeraShaderLoader.loadSource("chimera_composite/chimera_composite.vsh");
        for (PackProgram program : this.packPrograms) {
            String name = program.name();
            if (this.conformanceReport != null && !this.conformanceReport.shouldAttempt(name)) {
                this.conformanceReport.markRuntime(name,
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "CONTRACT_UNSUPPORTED");
                LOGGER.warn("[chimera] pack {}: fallback=IDENTITY (contract unsupported)", name);
                continue;
            }
            if (name.equals("shadow")) {
                PackPipelines.PackShadow shadow = PackPipelines.buildShadow(program);
                if (shadow == null) {
                    if (this.conformanceReport != null) {
                        this.conformanceReport.markRuntime(name,
                                ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                "PIPELINE_BUILD_FAILED");
                    }
                    LOGGER.warn("[chimera] pack shadow: fallback=IDENTITY (build failed)");
                    continue;
                }
                this.packShadowPipeline = shadow.pipeline();
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED, null);
                }
                LOGGER.info("[chimera] pack shadow: ok (samplers={})",
                        Arrays.toString(shadow.samplerSlots()));
                if (TRACE_TRANSITIONS) {
                    LOGGER.info("[chimera] pack shadow converted fragment:\n{}", shadow.convertedFragment());
                }
            } else if (name.equals("composite") || name.equals("final")) {
                PackPipelines.PackPost post = PackPipelines.buildPost(program, fixedVertex);
                if (post == null) {
                    if (this.conformanceReport != null) {
                        this.conformanceReport.markRuntime(name,
                                ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                "PIPELINE_BUILD_FAILED");
                    }
                    LOGGER.warn("[chimera] pack {}: fallback=IDENTITY (build failed)", name);
                    continue;
                }
                if (name.equals("composite")) {
                    this.packCompositePipeline = post.pipeline();
                    this.packCompositeSlots = post.samplerSlots();
                } else {
                    this.packFinalPipeline = post.pipeline();
                    this.packFinalSlots = post.samplerSlots();
                }
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED, null);
                }
                LOGGER.info("[chimera] pack {}: ok (samplers={})", name, Arrays.toString(post.samplerSlots()));
                if (TRACE_TRANSITIONS) {
                    LOGGER.info("[chimera] pack {} converted fragment:\n{}", name, post.convertedFragment());
                }
            } else if (name.equals("gbuffers_terrain")) {
                PackPipelines.PackTerrain terrain = PackPipelines.buildTerrain(program,
                        ChimeraShaderLoader.loadSource("chimera_terrain/chimera_terrain.vsh"));
                if (terrain == null) {
                    if (this.conformanceReport != null) {
                        this.conformanceReport.markRuntime(name,
                                ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                "PIPELINE_BUILD_FAILED");
                        if (program.vertexSource() != null) {
                            this.conformanceReport.markRuntime(name,
                                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                    "TERRAIN_VERTEX_PIPELINE_BUILD_FAILED");
                        }
                    }
                    LOGGER.warn("[chimera] pack gbuffers_terrain: fallback=IDENTITY (build failed)");
                    continue;
                }
                this.packGeometryPipeline = terrain.pipeline();
                this.packGeometrySlots = terrain.samplerSlots();
                ChimeraTerrainPipelines.setGeometryOverride(terrain.pipeline());
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED, null);
                }
                LOGGER.info("[chimera] pack gbuffers_terrain: ok (geometry, samplers={})",
                        Arrays.toString(terrain.samplerSlots()));
                if (TRACE_TRANSITIONS) {
                    LOGGER.info("[chimera] pack gbuffers_terrain converted fragment:\n{}", terrain.convertedFragment());
                }
                if (this.packConfig != null && this.packConfig.drawBufferCount() > 1) {
                    LOGGER.warn("[chimera] pack gbuffers_terrain: DRAWBUFFERS={} not honored in M4 "
                            + "(single attachment; multi-buffer gbuffers deferred to M5.6)",
                            this.packConfig.drawBufferCount());
                }
            } else if (name.equals("gbuffers_water")) {
                PackPipelines.PackTerrain water = PackPipelines.buildTranslucent(program,
                        ChimeraShaderLoader.loadSource("chimera_terrain/chimera_terrain.vsh"));
                if (water == null) {
                    if (this.conformanceReport != null) {
                        this.conformanceReport.markRuntime(name,
                                ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                "PIPELINE_BUILD_FAILED");
                    }
                    LOGGER.warn("[chimera] pack gbuffers_water: fallback=IDENTITY (build failed)");
                    continue;
                }
                this.packTranslucentPipeline = water.pipeline();
                this.packTranslucentSlots = water.samplerSlots();
                ChimeraTerrainPipelines.setTranslucentOverride(water.pipeline());
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED, null);
                }
                LOGGER.info("[chimera] pack gbuffers_water: ok (translucent, samplers={})",
                        Arrays.toString(water.samplerSlots()));
                if (TRACE_TRANSITIONS) {
                    LOGGER.info("[chimera] pack gbuffers_water converted fragment:\n{}",
                            water.convertedFragment());
                }
            } else {
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                            "CONTRACT_UNSUPPORTED");
                }
                LOGGER.warn("[chimera] pack {}: fallback=IDENTITY (unsupported program family)", name);
            }
        }
        if (this.packShadowPipeline == null
                && this.packPrograms.stream().noneMatch(program -> program.name().equals("shadow"))) {
            if (this.conformanceReport != null) {
                this.conformanceReport.addDeviation("SHADOW_PROGRAM_MISSING");
            }
            LOGGER.warn("[chimera] pack shadow: fallback=IDENTITY (SHADOW_PROGRAM_MISSING)");
        }
        if (this.conformanceReport != null) {
            this.conformanceReport.markUnattemptedAsFallback();
            LOGGER.info("[chimera] conformance {}", this.conformanceReport.toJson());
        }
    }

    /**
     * Keeps GL-registry slot 5 (the sampler slot pack geometry declares as
     * shadowtex0) pointing at the shadow map's depth. Terrain draws bind
     * sampler slots from that registry (VTextureSelector.bindShaderTextures),
     * unlike post seams which bind through boundTextures; the view is rebuilt
     * only when the shadow attachment is recreated (id change).
     */
    private void maintainPackShadowGoal() {
        boolean geometryUsesShadow = this.packGeometryPipeline != null
                && this.packGeometrySlots != null && containsSlot(this.packGeometrySlots, 5);
        boolean translucentUsesShadow = this.packTranslucentPipeline != null
                && this.packTranslucentSlots != null && containsSlot(this.packTranslucentSlots, 5);
        if (!geometryUsesShadow && !translucentUsesShadow) {
            return;
        }
        if (!this.shadowMap.isInitialized() || this.shadowMap.getShadowFramebuffer() == null) {
            return;
        }
        VulkanImage shadowDepth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
        if (this.packShadowView == null || shadowDepth.getId() != this.packShadowSourceId) {
            // A recreated shadow attachment retires the old view: drop it from
            // the main-family set (it stays closed below) and register the new
            // one, so isFamilyView tracks exactly the live view.
            if (this.packShadowView != null) {
                this.mainFamilyViews.remove(this.packShadowView);
            }
            VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
            VkGpuTexture texture = device.gpuTextureFromVulkanImage(shadowDepth);
            GpuTextureView view = device.createTextureView(texture);
            releaseOldShadowView();
            this.packShadowTexture = texture;
            this.packShadowView = view;
            this.packShadowSourceId = shadowDepth.getId();
            this.mainFamilyViews.add(view);
        }
        VRenderSystem.setShaderTexture(5, this.packShadowView);
    }

    /** Makes the previous (or initial cleared) shadow images valid shader inputs. */
    private void prepareShadowForSampling() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        VulkanImage shadowColor = this.shadowMap.getShadowFramebuffer().getColorAttachment();
        VulkanImage shadowDepth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            shadowColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            shadowDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    private void recordShadowCutoutDisposition(boolean merged) {
        if (this.shadowCutoutDispositionLogged || this.packShadowPipeline == null) {
            return;
        }
        this.shadowCutoutDispositionLogged = true;
        if (merged && this.conformanceReport != null) {
            this.conformanceReport.addDeviation("SHADOW_CUTOUT_MERGED");
        }
        LOGGER.info("[chimera] pack shadow cutout path: {}",
                merged ? "merged by uniqueOpaqueLayer" : "separate");
    }

    private void releaseOldShadowView() {
        if (this.packShadowView != null) {
            this.packShadowView.close();
        }
        if (this.packShadowTexture != null) {
            this.packShadowTexture.close();
        }
    }

    private void releasePackShadowView() {
        releaseOldShadowView();
        this.packShadowTexture = null;
        this.packShadowView = null;
        this.packShadowSourceId = 0L;
    }

    private static boolean containsSlot(int[] slots, int slot) {
        for (int candidate : slots) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }

    private void createHdrInteropTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
        VkGpuTexture color = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getColorAttachment());
        this.hdrColorTexture = color;
        this.hdrColorTextureView = device.createTextureView(color);
        this.mainFamilyViews.add(this.hdrColorTextureView);
        this.hdrDepthTexture = device.gpuTextureFromVulkanImage(this.hdrFramebuffer.getDepthAttachment());
        this.hdrDepthTextureView = device.createTextureView(this.hdrDepthTexture);
    }

    private void createOutputInteropTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
        VkGpuTexture color = device.gpuTextureFromVulkanImage(this.compositeFramebuffer.getColorAttachment());
        this.compositeColorTexture = color;
        this.compositeColorTextureView = device.createTextureView(color);
        this.compositeDepthTexture = device.gpuTextureFromVulkanImage(this.compositeFramebuffer.getDepthAttachment());
        this.mainFamilyViews.add(this.compositeColorTextureView);
        this.compositeDepthTextureView = device.createTextureView(this.compositeDepthTexture);
    }

    private boolean hdrResourcesLive() {
        return this.hdrFramebuffer != null
                && this.hdrColorTexture != null
                && !this.hdrColorTexture.isClosed()
                && this.hdrDepthTexture != null
                && !this.hdrDepthTexture.isClosed()
                && this.hdrFramebuffer.getColorAttachment().getId() != 0L
                && this.hdrFramebuffer.getDepthAttachment().getId() != 0L;
    }

    private boolean outputResourcesLive() {
        return this.compositeFramebuffer != null
                && this.compositeColorTexture != null
                && !this.compositeColorTexture.isClosed()
                && this.compositeDepthTexture != null
                && !this.compositeDepthTexture.isClosed()
                && this.compositeFramebuffer.getColorAttachment().getId() != 0L
                && this.compositeFramebuffer.getDepthAttachment().getId() != 0L;
    }

    private long hdrImageId() {
        return this.hdrFramebuffer == null ? 0L : this.hdrFramebuffer.getColorAttachment().getId();
    }

    private void invalidateWorldResources() {
        long oldHdrId = hdrImageId();
        Framebuffer oldHdr = this.hdrFramebuffer;
        this.worldResourcesReady = false;
        this.levelPhase = false;
        this.pendingDepthClear = false;
        this.shadowPending = false;
        if (oldHdr == null && this.hdrColorTexture == null && this.hdrDepthTexture == null) {
            return;
        }

        if (Renderer.getInstance().getBoundFramebuffer() == oldHdr) {
            Renderer.getInstance().endRenderPass();
        }
        releaseHdrInteropTextures();
        if (oldHdr != null) oldHdr.cleanUp(false);
        this.hdrFramebuffer = null;
        if (this.currentFramebuffer == oldHdr) {
            this.currentFramebuffer = this.compositeFramebuffer;
        }
        if (TRACE_TRANSITIONS) {
            LOGGER.info("[chimera] invalidated world attachments at frame boundary frame={} oldHdrId={}",
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
    private void refreshWorldResources() {
        invalidateWorldResources();
        recreateWorldAttachments();
        if (TRACE_TRANSITIONS) {
            LOGGER.info("[chimera] recreated world attachments at frame boundary frame={} newHdrId={}",
                    Renderer.getCurrentFrame(), hdrImageId());
        }
    }

    private void recreateWorldAttachments() {
        Framebuffer output = this.compositeFramebuffer;
        int width = output != null ? output.getWidth() : Math.max(Renderer.getInstance().getSwapChain().getWidth(), 1);
        int height = output != null ? output.getHeight() : Math.max(Renderer.getInstance().getSwapChain().getHeight(), 1);
        createHdrFramebuffer(width, height);
        createHdrInteropTextures();
        this.hdrDepthReadable = false;
        this.worldResourcesReady = true;
        this.earlyOutputPass = false;
    }

    private void ensureWorldResources() {
        if (this.worldResourcesReady && hdrResourcesLive()) {
            return;
        }

        invalidateWorldResources();
        recreateWorldAttachments();
        if (TRACE_TRANSITIONS) {
            LOGGER.info("[chimera] recreated world attachments before level frame={} newHdrId={}",
                    Renderer.getCurrentFrame(), hdrImageId());
        }
    }

    private void ensureOutputResources() {
        if (this.outputResourcesReady && outputResourcesLive()) {
            return;
        }
        LOGGER.warn("[chimera] stable output attachment closed; rebuilding full pass resources");
        createResources();
    }

    private void releaseHdrInteropTextures() {
        if (this.hdrColorTextureView != null) this.hdrColorTextureView.close();
        if (this.hdrDepthTextureView != null) this.hdrDepthTextureView.close();
        if (this.hdrColorTexture != null) this.hdrColorTexture.close();
        if (this.hdrDepthTexture != null) this.hdrDepthTexture.close();
        this.hdrColorTexture = null;
        this.hdrColorTextureView = null;
        this.hdrDepthTexture = null;
        this.hdrDepthTextureView = null;
    }

    private void releaseOutputInteropTextures() {
        if (this.compositeColorTextureView != null) this.compositeColorTextureView.close();
        if (this.compositeDepthTextureView != null) this.compositeDepthTextureView.close();
        if (this.compositeColorTexture != null) this.compositeColorTexture.close();
        if (this.compositeDepthTexture != null) this.compositeDepthTexture.close();
        this.compositeColorTexture = null;
        this.compositeColorTextureView = null;
        this.compositeDepthTexture = null;
        this.compositeDepthTextureView = null;
    }

    private void cleanUpFramebuffersAndPasses() {
        this.worldResourcesReady = false;
        this.outputResourcesReady = false;
        this.hdrDepthReadable = false;
        releaseHdrInteropTextures();
        releaseOutputInteropTextures();
        if (this.hdrFramebuffer != null) this.hdrFramebuffer.cleanUp(false);
        if (this.compositeFramebuffer != null) this.compositeFramebuffer.cleanUp(false);
        if (this.hdrRenderPass != null) this.hdrRenderPass.cleanUp();
        if (this.hdrAuxRenderPass != null) this.hdrAuxRenderPass.cleanUp();
        if (this.hdrAuxClearDepthRenderPass != null) this.hdrAuxClearDepthRenderPass.cleanUp();
        if (this.compositeRenderPass != null) this.compositeRenderPass.cleanUp();
        if (this.compositeAuxRenderPass != null) this.compositeAuxRenderPass.cleanUp();
        if (this.compositeAuxClearDepthRenderPass != null) this.compositeAuxClearDepthRenderPass.cleanUp();
        if (this.presentRenderPass != null) this.presentRenderPass.cleanUp();
        this.hdrFramebuffer = null;
        this.compositeFramebuffer = null;
        this.currentFramebuffer = null;
        this.hdrRenderPass = null;
        this.hdrAuxRenderPass = null;
        this.hdrAuxClearDepthRenderPass = null;
        this.compositeRenderPass = null;
        this.compositeAuxRenderPass = null;
        this.compositeAuxClearDepthRenderPass = null;
        this.presentRenderPass = null;
    }

    private void cleanUpPipelines() {
        if (this.presentPipeline != null) this.presentPipeline.cleanUp();
        if (this.compositePipeline != null) this.compositePipeline.cleanUp();
        this.presentPipeline = null;
        this.compositePipeline = null;
    }

    private int hdrDepthStoreOp() {
        return this.packNeedsHdrDepth
                ? VK_ATTACHMENT_STORE_OP_STORE
                : VK_ATTACHMENT_STORE_OP_DONT_CARE;
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
        LOGGER.info("[chimera] tr {} frame={} {} id={} {}->{}", site,
                Renderer.getCurrentFrame(), image, img.getId(),
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
