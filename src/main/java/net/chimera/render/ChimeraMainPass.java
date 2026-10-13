package net.chimera.render;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.chimera.mixin.ChimeraDeviceAccessor;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.PackConfig;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackPlan;
import net.chimera.shaderpack.PackProgram;
import net.chimera.shaderpack.PackSource;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.chimera.shaderpack.ScreenOverlayDirectives;
import net.chimera.shaderpack.TargetSpec;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Chimera's main render pass uses the same world/output split as Beryl and
 * VulkShade:
 *
 *   - renderLevel HEAD opens the internal RGBA16F world target.
 *   - The opaque layer's head temporarily records the shadow map and resumes
 *     the world target.
 *   - renderLevel RETURN resolves the HDR world into a separate output target.
 *     Entity passes inside renderLevel follow the HDR alias; hand, GUI, and
 *     post-chain passes follow the output alias.
 *   - MainPass.end presents the output target to the swapchain.
 *
 * The output target is the stable Minecraft main target. Screen resource churn
 * may retire internal world resources at their frame fence, but it never makes
 * GUI/post passes and world rendering transition the same Vulkan image.
 *
 * <p>This class schedules the frame and owns the pack session's state. Its
 * collaborators each own one part of the work: the render targets and the
 * resources behind them, the installed pipelines and how they load, the
 * shadow pass, the post chain, scene coverage, image bindings, the family
 * windows, the opaque depth snapshot, material maps, and the session
 * lifecycle that loads and retires packs.</p>
 */
public final class ChimeraMainPass implements MainPass {
    // Collaborators only keep this pass; they are created before any other field.
    final PackSessionLifecycle sessions = new PackSessionLifecycle(this);
    final PackPostChain postChain = new PackPostChain(this);
    final MainPassTargets targets = new MainPassTargets(this);
    final PackPipelineSet pipelines = new PackPipelineSet();
    final PackShadowPass shadowPass = new PackShadowPass(this);
    final PackSceneCoverage coverage = new PackSceneCoverage(this);
    final PackImageBindings images = new PackImageBindings(this);
    final MainPassResources resources = new MainPassResources(this);
    final PackPipelineLoad load = new PackPipelineLoad(this);
    final OpaqueDepthCapture depth = new OpaqueDepthCapture(this);
    final MaterialMapRuntime materials = new MaterialMapRuntime(this);
    final PackFamilyWindows windows = new PackFamilyWindows(this);

    private static final HandBoundaryPolicy HAND_BOUNDARY_POLICY =
            new HandBoundaryPolicy(true, true);

    /** The host hand continues from loaded color with a fresh cleared depth. */
    static HandBoundaryPolicy handBoundaryPolicy() {
        return HAND_BOUNDARY_POLICY;
    }

    static record HandBoundaryPolicy(boolean loadColor, boolean clearDepth) {}

    static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    /** Complete host state snapshot for one pack-owned fullscreen seam. */
    record PackRenderState(
            HostPipelineState pipeline,
            int viewportWidth,
            int viewportHeight
    ) {}

    PackRenderState capturePackRenderState() {
        Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
        if (boundFramebuffer == null) {
            boundFramebuffer = this.currentFramebuffer;
        }
        int viewportWidth = boundFramebuffer == null ? 0 : boundFramebuffer.getWidth();
        int viewportHeight = boundFramebuffer == null ? 0 : boundFramebuffer.getHeight();
        return new PackRenderState(HostPipelineState.capture(), viewportWidth, viewportHeight);
    }

    static void preparePackFullscreenState() {
        HostPipelineState.prepareFullscreen();
    }

    void restorePackRenderState(PackRenderState state) {
        if (state == null) return;
        state.pipeline().restore();
        if (state.viewportWidth() > 0 && state.viewportHeight() > 0) {
            Renderer.setViewport(0, 0, state.viewportWidth(), state.viewportHeight());
            Renderer.setScissor(0, 0, state.viewportWidth(), state.viewportHeight());
        }
    }

    /** Diagnostic: -Dchimera.traceTransitions logs every chimera image transition. */
    static final boolean TRACE_TRANSITIONS = ChimeraRenderer.debugFlag("chimera.traceTransitions");
    Framebuffer currentFramebuffer;

    private GraphicsPipeline presentPipeline;
    GraphicsPipeline compositePipeline;
    final PackPostTargets packPostTargets = new PackPostTargets();
    final PackDepthTargets packDepthTargets = new PackDepthTargets();
    /** Immutable target and depth schedule for the active pack variant. */
    PackTargetGraphPlan packTargetGraph;
    /** Immutable world/post order for the active pack variant. */
    PackFrameSchedulePlan packFrameSchedule = PackFrameSchedulePlan.empty();
    boolean packPostChainActive;
    /** Target images may be needed by geometry even when no post pass is installed. */
    boolean packTargetResourcesReady;
    /** Set only when the target graph itself cannot be configured. */
    boolean packPostChainRejected;
    /**
     * Mask-critical families this pack session could not install, or null.
     * While set, the session loads as if no pack were selected, so resizes and
     * screen resets cannot rebuild a half-installed frame. A pack change,
     * reload or dimension variant clears it and re-evaluates.
     */
    String packFrameUnsupported;
    /** True after the current frame has opened the target executor state. */
    boolean packPostFrameStarted;
    boolean packEarlyPostCompleted;
    /** True after the world image has been resolved to the stable GUI target. */
    boolean packWorldResolved;
    /** True after the final pack pass has run for the current frame. */
    boolean packFinalApplied;
    final VulkanImage[] packFinalInputs = new VulkanImage[PostTargetPlan.MAX_TARGET + 1];
    /** Parsed pack constants and programs for the active selection. */
    PackConfig.PackConfigData packConfig;
    PackPlan packPlan;
    List<PackProgram> packPrograms;
    /** Owns temporary source extraction for a ZIP pack until session teardown. */
    PackSource.LoadResult packSource;
    /** All pack-owned sampled images, retained for the pack session. */
    PackResourceOwner packResourceOwner;
    /** All pack-owned writable images, retained for the pack session. */
    PackAdvancedImageOwner packAdvancedImageOwner;
    /** Owns exact-size pack storage buffers for the active session. */
    PackStorageBufferOwner packStorageBufferOwner;
    boolean packStorageInitializationLogged;
    /** Suppresses the secondary frame-not-started flood until the next pack session. */
    boolean packTargetFrameNotStartedLogged;
    /** Optional bounded shadow compute pipeline for the active pack session. */
    PackShadowCompute packShadowCompute;
    /** Static pack inventory plus the actual compile/install disposition. */
    ConformanceReport conformanceReport;
    /** Pack pipelines are retained until the renderer session is torn down. */
    boolean packPipelinesLoaded;
    /** HDR buffer format from the pack's colortex0Format; 97 (RGBA16F) when the pack says nothing. */
    int packHdrFormat = 97;
    /** True when a loaded post source may sample the HDR depth attachment. */
    boolean packNeedsHdrDepth;
    /** Previous selector values for pack-owned geometry textures during one terrain draw. */
    ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> packGeometryBindings;
    record ProgramImages(String name, ProgramImageBindingManifest manifest,
                                Set<String> shadowSamplerSymbols) {}
    /** The current translucent layer draw uses the fixed pipeline: pack inputs were not live. */
    boolean packTranslucentSuspended;
    boolean packTranslucentSuspendLogged;
    /** Which of Minecraft's screen overlays this pack session keeps (Iris vignette and underwaterOverlay). */
    ScreenOverlayDirectives screenOverlays;
    /** True after renderLevel opens the internal HDR world target. */
    boolean levelPhase;
    /** Set when vanilla requests a depth clear while no pass is recording. */
    boolean pendingDepthClear;
    private final ChimeraHorizonRenderer horizonRenderer = new ChimeraHorizonRenderer();

    /** Opens the stable output target before a screen frame starts. */
    boolean earlyOutputPass = true;
    /** Pooled vanilla passes can retain any prior main-target view identity. */
    final Set<GpuTextureView> mainFamilyViews =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public ChimeraMainPass() {
        this(PackSessionLifecycle.startupPackPath());
    }

    ChimeraMainPass(Path initialPackPath) {
        this.sessions.packPath = initialPackPath;
        this.levelPhase = true;
        resources.createResources();
        // VulkanMod already resizes the current main pass; this covers the
        // frames where the host owns it, so Chimera's targets follow the
        // swapchain without being rebuilt twice.
        Renderer.getInstance().addOnResizeCallback(() -> {
            if (Renderer.getInstance().getMainPass() != this) onResize();
        });
    }

     Path selectedPackPath() {
        return sessions.selectedPackPath();
    }

    public void renderShadowSegment(double cameraX, double cameraY, double cameraZ) {
        shadowPass.renderShadowSegment(cameraX, cameraY, cameraZ);
    }

    public boolean consumeShadowPending() {
        return shadowPass.consumeShadowPending();
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
                    Renderer.getCurrentFrame(), targets.hdrImageId());
        }
        MemoryManager.getInstance().addFrameOp(this.resources::refreshWorldResources);
    }

     boolean prepareForInstall() {
        return sessions.prepareForInstall();
    }

     boolean applyPendingPackVariantAtFrameBoundary() {
        return sessions.applyPendingPackVariantAtFrameBoundary();
    }

     boolean hasPendingPackChange() {
        return sessions.hasPendingPackChange();
    }

     boolean queuePackChange(Path path, boolean forceReload) {
        return sessions.queuePackChange(path, forceReload);
    }

     boolean queuePackReload() {
        return sessions.queuePackReload();
    }

     String packStatus() {
        return sessions.packStatus();
    }

    // ------------------------------------------------------------------
    // Segment control
    // ------------------------------------------------------------------

    public void openLevelSegment(double cameraX, double cameraY, double cameraZ) {
        resources.ensureWorldResources();
        Renderer.getInstance().endRenderPass();
        if (this.packStorageBufferOwner != null) {
            this.packStorageBufferOwner.beginExecutionFrame();
            boolean initialized = this.packStorageBufferOwner.initialize(Renderer.getCommandBuffer());
            if (initialized || !this.packStorageInitializationLogged) {
                load.logPackStoragePath("storage-initialization");
                this.packStorageInitializationLogged = true;
            }
            this.packStorageBufferOwner.prepareForUse(Renderer.getCommandBuffer());
        }
        this.currentFramebuffer = this.targets.hdrFramebuffer;
        this.depth.hdrDepthReadable = false;
        this.shadowPass.shadowFrameReady = false;
        this.packPostFrameStarted = false;
        this.packEarlyPostCompleted = false;
        this.packWorldResolved = false;
        this.packFinalApplied = false;
        if (this.packDepthTargets.isConfigured()) {
            this.packDepthTargets.beginFrame();
        }
        if (this.packPostTargets.isConfigured()) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
            VulkanImage hdrColor = this.targets.hdrFramebuffer.getColorAttachment();
            this.packPostTargets.beginFrame(commandBuffer, hdrColor);
            this.packPostFrameStarted = true;
            this.postChain.nanTripwire.beginFrame(commandBuffer,
                    this.sessions.packPath == null ? "?" : String.valueOf(this.sessions.packPath.getFileName()));
            if (coverage.packCoverageRuntimeEnabled() && this.packPostChainActive) {
                this.packPostTargets.prepareGeometryTarget(commandBuffer);
                this.coverage.packCoverageOwner.beginFrame(commandBuffer);
                this.coverage.packCoverageState.beginFrame();
            }
        }

        // Compute the light once before any terrain draw. The shadow pass at
        // the opaque layer's head reuses this exact state.
        if (this.shadowPass.shadowMap.isInitialized()) {
            this.shadowPass.shadowMap.updateLight(PackUniformProvider.currentShadowLightRotation(),
                    cameraX, cameraY, cameraZ);
            this.shadowPass.shadowFrameReady = shadowPass.prepareShadowForSampling(cameraX, cameraY, cameraZ);
            if (this.shadowPass.shadowFrameReady && this.shadowPass.shadowMap.hasMapSnapshot()) {
                this.shadowPass.shadowMap.publishMapState(cameraX, cameraY, cameraZ);
            } else {
                PackUniformProvider.clearShadowState();
            }
        }

        // Arm this frame's shadow segment; consumed at the opaque layer's head.
        this.shadowPass.shadowPending = this.shadowPass.shadowFrameReady;

        // Terrain shaders sample the shadow map from their first draw.
        if (this.shadowPass.shadowFrameReady) {
            this.shadowPass.shadowMap.bindShadowTexture();
            shadowPass.maintainPackShadowGoal();
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.targets.hdrFramebuffer.getColorAttachment();
            trace("openHdr", "hdrColor", hdrColor, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            Renderer.getInstance().beginRenderPass(this.targets.hdrRenderPass, this.targets.hdrFramebuffer);
            Renderer.setViewport(0, 0, this.targets.hdrFramebuffer.getWidth(), this.targets.hdrFramebuffer.getHeight(),
                    stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.targets.hdrFramebuffer.scissor(stack));
            Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            this.levelPhase = true;
            this.pendingDepthClear = false;
        }
        if (this.packPostChainActive
                && this.packFrameSchedule.hasWindow(PackFrameSchedulePlan.PostWindow.PREPARE)) {
            Renderer.getInstance().endRenderPass(commandBuffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanImage hdrColor = this.targets.hdrFramebuffer.getColorAttachment();
                hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                postChain.runPackPostWindow(commandBuffer, hdrColor, PackFrameSchedulePlan.PostWindow.PREPARE);
                if (this.packPostChainActive) {
                    this.packPostTargets.adoptTarget0(commandBuffer, hdrColor);
                }
                hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }
            this.rebindMainTarget();
        }
    }

    /** Called inside the native overworld sky scope, after its clears and before its disc. */
    public void drawPackHorizon() {
        if (this.levelPhase && this.packPlan != null && this.packPlan.skyEnabled()
                && ChimeraSkyBridge.hasBasicSky()) this.horizonRenderer.draw();
    }

    /** Resolves the world into the stable output before hand and GUI work. */
    public void finishLevelSegment() {
        if (!this.levelPhase || this.targets.hdrFramebuffer == null) {
            return;
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        if (this.packStorageBufferOwner != null) {
            this.packStorageBufferOwner.prepareForUse(commandBuffer);
        }
        if (PackGeometryContext.active()) {
            PackGeometryContext.close();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.targets.hdrFramebuffer.getColorAttachment();
            trace("resolveWorld", "hdrColor", hdrColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (this.packNeedsHdrDepth) {
                if (this.packDepthTargets.isConfigured()
                        && this.packDepthTargets.plan().depthtex2()
                        && !this.packDepthTargets.currentAvailable("depthtex2")
                        && !this.packDepthTargets.capturePreHand(
                                commandBuffer, this.targets.hdrFramebuffer.getDepthAttachment())) {
                    LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (pre-hand depth conversion failed)");
                }
                depth.transitionHdrDepthForSampling(stack, commandBuffer);
                if (this.packDepthTargets.isConfigured()
                        && !this.packDepthTargets.captureScene(
                        commandBuffer, this.targets.hdrFramebuffer.getDepthAttachment())) {
                    LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (depth conversion failed)");
                }
            }

            if (coverage.packCoverageRuntimeEnabled() && this.coverage.packCoverageState.canSeed()) {
                coverage.seedPackScene(commandBuffer, hdrColor);
            }

            if (this.packPostChainActive && this.packPostTargets.isConfigured()) {
                if (!this.packEarlyPostCompleted
                        && this.packFrameSchedule.hasWindow(PackFrameSchedulePlan.PostWindow.EARLY)) {
                    postChain.runPackPostWindow(commandBuffer, hdrColor,
                            PackFrameSchedulePlan.PostWindow.EARLY);
                    this.packEarlyPostCompleted = true;
                }
                postChain.runPackPostWindow(commandBuffer, hdrColor, PackFrameSchedulePlan.PostWindow.LATE);
                if (this.packPostFrameStarted) {
                    this.packPostTargets.commitFrame();
                    this.packPostFrameStarted = false;
                }
                resolvePackWorldToOutput(commandBuffer, hdrColor);
                this.packWorldResolved = true;
                this.currentFramebuffer = this.targets.compositeFramebuffer;
                if (coverage.packCoverageRuntimeEnabled()) {
                    this.coverage.packCoverageState.endFrame();
                }
                // The pack final stage is the last pack operation in the
                // world segment.  It must complete before vanilla hand
                // submission, so host hand and GUI draws remain visible.
                postChain.finishPackFinalBeforeHand();
                return;
            }

            if (this.packPostFrameStarted) {
                this.packPostTargets.commitFrame();
                this.packPostFrameStarted = false;
            }
            // Mask-critical bypass: this branch runs when
            // the post chain is blocked or unconfigured. Installed pack
            // objects stay untouched for deterministic cleanup and reload,
            // but the pack composite must not execute here: it requires
            // pack auxiliary targets while only HDR target 0 exists, so
            // binding it throws RESOURCE_BINDING_UNAVAILABLE for its first
            // auxiliary sampler. The host identity resolve presents the HDR
            // image through a Sampler0-only manifest instead.
            resolveWorldToOutput(commandBuffer, hdrColor);
            this.currentFramebuffer = this.targets.compositeFramebuffer;
            if (coverage.packCoverageRuntimeEnabled()) {
                this.coverage.packCoverageState.endFrame();
            }
        }
    }

    public void beginPackCoverageWindow(TerrainRenderType renderType) {
        coverage.beginPackCoverageWindow(renderType);
    }

    public PackPipelines.PackEntity familyPipeline(ChimeraEntityBridge.Family family) {
        return pipelines.familyPipeline(family);
    }

    public PackPipelines.PackParticle particlePipeline(ChimeraEntityBridge.Family family) {
        return pipelines.particlePipeline(family);
    }

    public boolean beginPackFamilyWindow(PackPipelines.PackEntity family) {
        return windows.beginPackFamilyWindow(family);
    }

    public boolean beginPackFamilyWindow(GeometryOutputPlan outputs) {
        return windows.beginPackFamilyWindow(outputs);
    }

    public void endPackFamilyWindow(PackPipelines.PackEntity family) {
        windows.endPackFamilyWindow(family);
    }

    public void endPackFamilyWindow(GeometryOutputPlan outputs) {
        windows.endPackFamilyWindow(outputs);
    }
     boolean packCoverageRuntimeEnabled() {
        return coverage.packCoverageRuntimeEnabled();
    }

    public void endPackCoverageWindow(TerrainRenderType renderType) {
        coverage.endPackCoverageWindow(renderType);
    }

    public void bindTerrainAtlasAfterSelector(TerrainRenderType renderType) {
        windows.bindTerrainAtlasAfterSelector(renderType);
    }

    // ------------------------------------------------------------------
    // MainPass contract
    // ------------------------------------------------------------------

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        resources.ensureOutputResources();
        this.currentFramebuffer = this.targets.compositeFramebuffer;
        this.levelPhase = false;
        this.pendingDepthClear = false;

        if (this.earlyOutputPass) {
            trace("beginOutput", "outputColor",
                    this.targets.compositeFramebuffer.getColorAttachment(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            Renderer.getInstance().beginRenderPass(this.targets.compositeRenderPass, this.targets.compositeFramebuffer);
            Renderer.clearAttachments(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        if (this.levelPhase && this.currentFramebuffer == this.targets.hdrFramebuffer) {
            finishLevelSegment();
        }
        if (this.packWorldResolved && !this.packFinalApplied) {
            // The level-render return seam is the authoritative final seam.
            // If it was not reached, preserve the already-resolved host image
            // and let the normal host hand and GUI path continue.
            if (this.postChain.packFinalExecution != null) {
                postChain.markPostStageIdentityBoundary(this.postChain.packFinalExecution,
                        "level boundary not reached");
            }
            this.packFinalApplied = true;
        }
        Renderer.getInstance().endRenderPass(commandBuffer);

        this.postChain.nanTripwire.finishFrame(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage outputColor = this.targets.compositeFramebuffer.getColorAttachment();
            trace("presentRead", "outputColor", outputColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            outputColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (this.packNeedsHdrDepth) {
                depth.transitionHdrDepthForSampling(stack, commandBuffer);
            }
            VTextureSelector.bindTexture(outputColor);

            SwapChain swapChain = Renderer.getInstance().getSwapChain();
            if (swapChain.hasImages()) {
                trace("presentSwap", "swapchain", swapChain.getColorAttachment(),
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                Renderer.getInstance().beginRenderPass(this.targets.presentRenderPass, swapChain);
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
                PackRenderState previousState = capturePackRenderState();
                preparePackFullscreenState();
                try {
                    // Pack final is executed before GUI by the hand seam.
                    // The swapchain always uses the host present pipeline.
                    drawFullscreen(commandBuffer, this.presentPipeline);
                    // Identity's end-of-frame state after the present is
                    // slot 0 = outputColor. The captured slot 0 can be
                    // chimera's own bindAsTexture residue (hdrColor), which
                    // the next world-leave churn destroys; leaving it would
                    // hand the host a dead image at the transition.
                    if (this.pipelines.finalPipeline != null || this.pipelines.finalPost != null) {
                        VTextureSelector.bindTexture(outputColor);
                    }
                } finally {
                    restorePackRenderState(previousState);
                }
                Renderer.getInstance().endRenderPass(commandBuffer);

                trace("presentSrc", "swapchain", swapChain.getColorAttachment(), VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
                swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer,
                        VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            }
        }

        this.levelPhase = false;
        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer: " + result);
        }
    }

    void drawFullscreen(VkCommandBuffer commandBuffer, GraphicsPipeline pipeline) {
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
        VK10.vkCmdDraw(commandBuffer, ChimeraPostPipelines.FULLSCREEN_VERTICES, 1, 0, 0);
        ProgramImages program = this.images.programImages.get(pipeline);
        if (program != null && this.packStorageBufferOwner != null) {
            this.packStorageBufferOwner.recordDraw(program.name(), VK_SHADER_STAGE_FRAGMENT_BIT);
        }
    }

    /** Resolves the current pack world target to the stable output target. */
    private void resolvePackWorldToOutput(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        VulkanImage resolved = this.packPostTargets.activeTarget(0);
        resolveWorldToOutput(commandBuffer, resolved == null ? hdrColor : resolved);
    }

    /**
     * Host resolve used when the pack target graph is not active. Always
     * presents through the host identity pipeline: callers on this path
     * hold only the HDR image, never the pack auxiliary targets a pack
     * composite program would require.
     */
    void resolveWorldToOutput(
            VkCommandBuffer commandBuffer,
            VulkanImage source
    ) {
        GraphicsPipeline resolvePipeline = this.compositePipeline;
        PackRenderState previousState = capturePackRenderState();
        preparePackFullscreenState();
        ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> previous = null;
        try {
            images.prepareProgramImages(resolvePipeline);
            previous = images.bindProgramImages(resolvePipeline, null, source,
                    DrawMaterialContext.captureLive());
            Renderer.getInstance().beginRenderPass(this.targets.compositeRenderPass, this.targets.compositeFramebuffer);
            // The identity and legacy pack seams share the same transaction.
            drawFullscreen(commandBuffer, resolvePipeline);
        } finally {
            if (previous != null) previous.close();
            restorePackRenderState(previousState);
        }
    }

    /** Captures the pre-hand depth seam before the host hand draw begins. */
    public void beginHandSegment() {
        if (this.levelPhase && this.packDepthTargets.isConfigured()
                && this.packDepthTargets.plan().depthtex2()) {
            var commandBuffer = Renderer.getCommandBuffer();
            Renderer.getInstance().endRenderPass(commandBuffer);
            if (!this.packDepthTargets.currentAvailable("depthtex2")) {
                this.packDepthTargets.capturePreHand(commandBuffer, this.targets.hdrFramebuffer.getDepthAttachment());
            }
            this.rebindMainTarget();
            return;
        }
        if (!this.packWorldResolved || !this.packDepthTargets.isConfigured()
                || !this.packDepthTargets.plan().depthtex2()) {
            return;
        }
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().endRenderPass(commandBuffer);
        }
        if (!this.packDepthTargets.currentAvailable("depthtex2")
                && !this.packDepthTargets.capturePreHand(commandBuffer,
                this.targets.hdrFramebuffer == null ? null : this.targets.hdrFramebuffer.getDepthAttachment())) {
            LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (pre-hand depth conversion failed)");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // The world resolve is already in the output image. Load its
            // color, then clear the fresh hand depth after depthtex2 capture.
            RenderPass handPass = HAND_BOUNDARY_POLICY.clearDepth()
                    ? this.targets.compositeAuxClearDepthRenderPass : this.targets.compositeAuxRenderPass;
            Renderer.getInstance().beginRenderPass(handPass,
                    this.targets.compositeFramebuffer);
            Renderer.setViewport(0, 0, this.targets.compositeFramebuffer.getWidth(),
                    this.targets.compositeFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.targets.compositeFramebuffer.scissor(stack));
        }
    }

    /** Divert the whole hand only when both halves can participate in the live pack frame. */
    public boolean hasPackHandSchedule() {
        return this.levelPhase && this.packPostChainActive && this.packTargetResourcesReady
                && this.pipelines.hand != null && this.pipelines.handWater != null;
    }

    static String runtimeFailureReason(String phase, RuntimeException failure) {
        String message = failure.getMessage();
        return "phase=" + phase + ",exception=" + failure.getClass().getName()
                + (message == null || message.isBlank() ? "" : ",message=" + message);
    }

    boolean shouldLogPackRuntimeFailure(String reason) {
        if (!reason.contains("PACK_TARGET_FRAME_NOT_STARTED")) return true;
        if (this.packTargetFrameNotStartedLogged) return false;
        this.packTargetFrameNotStartedLogged = true;
        return true;
    }

    void logPackRuntimeFailure(String phase, RuntimeException failure) {
        String reason = runtimeFailureReason(phase, failure);
        if (shouldLogPackRuntimeFailure(reason)) {
            LOGGER.warn("[chimera] pack runtime fallback ({})", reason, failure);
        }
    }

    public void captureOpaqueDepthBeforeTranslucent() {
        depth.captureOpaqueDepthBeforeTranslucent();
    }

    // ------------------------------------------------------------------
    // Main-target interop
    // ------------------------------------------------------------------

    @Override
    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        if (this.shadowPass.shadowPassActive) {
            Framebuffer shadow = this.shadowPass.shadowMap.getShadowFramebuffer();
            if (Renderer.getInstance().getBoundFramebuffer() != shadow) {
                Renderer.getInstance().beginRenderPass(this.shadowPass.shadowMap.getShadowRenderPass(), shadow);
            }
            return;
        }

        Framebuffer target = this.currentFramebuffer != null
                ? this.currentFramebuffer
                : this.targets.compositeFramebuffer;
        boolean forceDepthClear = this.pendingDepthClear;
        if (Renderer.getInstance().getBoundFramebuffer() == target && !forceDepthClear) {
            return;
        }

        RenderPass pass;
        String imageName;
        if (target == this.targets.hdrFramebuffer) {
            pass = forceDepthClear ? this.targets.hdrAuxClearDepthRenderPass : this.targets.hdrAuxRenderPass;
            imageName = "hdrColor";
        } else {
            pass = forceDepthClear ? this.targets.compositeAuxClearDepthRenderPass
                    : this.targets.compositeAuxRenderPass;
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
        return this.currentFramebuffer == this.targets.hdrFramebuffer
                ? this.targets.hdrColorTexture
                : this.targets.compositeColorTexture;
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.currentFramebuffer == this.targets.hdrFramebuffer
                ? this.targets.hdrColorTextureView
                : this.targets.compositeColorTextureView;
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.currentFramebuffer == this.targets.hdrFramebuffer
                ? this.targets.hdrDepthTexture
                : this.targets.compositeDepthTexture;
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

    Framebuffer currentFramebuffer() {
        return this.currentFramebuffer != null
                ? this.currentFramebuffer
                : this.targets.compositeFramebuffer;
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    @Override
    public void cleanUp() {
        stopRenderWorkForCleanup();
        sessions.waitForImmediateDestruction("final pack cleanup");
        this.horizonRenderer.close();
        sessions.cleanUpPackVariant();
        // The material owner has renderer lifetime: shader-pack replacement
        // must not close it, and final teardown closes it exactly once while
        // the device is still alive. A failed install therefore keeps the
        // previous valid companions instead of destroying them.
        if (this.materials.materialMapOwner != null) this.materials.materialMapOwner.close();
        this.materials.materialMapOwner = null;
        this.postChain.nanTripwire.close();
        targets.cleanUpFramebuffersAndPasses();
        cleanUpPipelines();
        if (this.packSource != null) this.packSource.close();
        this.packSource = null;
        this.sessions.packPath = null;
        this.sessions.packChangeQueue.clear();
        this.sessions.loggedPendingPackChange = null;
        this.sessions.pendingPackDimension = null;
        this.sessions.loggedPendingPackDimension = null;
        this.conformanceReport = null;
        this.mainFamilyViews.clear();
    }

    public MaterialMapOwner materialMaps() {
        return materials.materialMaps();
    }

    public void pumpMaterialMapBuilds() {
        materials.pumpMaterialMapBuilds();
    }

    /** Ends only active Chimera work before final Vulkan object destruction. */
    private void stopRenderWorkForCleanup() {
        if (this.packPostTargets.isRendering()) {
            try {
                this.packPostTargets.abort(Renderer.getCommandBuffer());
            } catch (RuntimeException failure) {
                logPackRuntimeFailure("post-cleanup-abort", failure);
            }
        }
        if (Renderer.isRecording()) {
            Renderer.getInstance().endRenderPass();
        }
        if (PackGeometryContext.active()) {
            PackGeometryContext.close();
        }
        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().setBoundRenderPass(null);
            Renderer.getInstance().setBoundFramebuffer(null);
        }
    }

    public void onLevelChanged(String dimension) {
        sessions.onLevelChanged(dimension);
    }

    @Override
    public void onResize() {
        // Renderer idles the device before resize and may change its frames-in-flight
        // count. Recreate the diagnostic ring at this same safe boundary.
        this.postChain.nanTripwire.close();
        resources.createResources();
    }

    static int deviceMaxColorAttachments() {
        if (DeviceManager.device == null) {
            return PackTargetGraphPlan.LOGICAL_ATTACHMENT_LIMIT;
        }
        return ((ChimeraDeviceAccessor) DeviceManager.device)
                .chimera$properties().limits().maxColorAttachments();
    }

    static int deviceMaxImageDimension() {
        if (DeviceManager.device == null) {
            return Integer.MAX_VALUE;
        }
        return ((ChimeraDeviceAccessor) DeviceManager.device)
                .chimera$properties().limits().maxImageDimension2D();
    }

    int packGeometryTargetFormat() {
        TargetSpec target = this.packTargetGraph == null ? null : this.packTargetGraph.target(0);
        return target == null ? this.packHdrFormat : target.format();
    }

    void createPipelines() {
        // NOTE: pipeline depth state is decided at BIND time by VulkanMod
        // (GraphicsPipeline.getHandle(PipelineState) <- getCurrentPipelineState
        // snapshots VRenderSystem globals), so the present draw disables depth
        // test/write at the draw site, not here.
        this.presentPipeline = ChimeraPostPipelines.create("chimera_present");
        this.compositePipeline = ChimeraPostPipelines.create("chimera_composite");
    }

    public boolean packRuntimeRejected() { return this.packPostChainRejected; }

    /** True while a shader pack session is loaded (identity mode has none). */
    public boolean packLoaded() { return this.packPlan != null; }

    /** The loaded pack's screen overlay switches, or null while no pack session is loaded. */
    public ScreenOverlayDirectives screenOverlays() { return this.screenOverlays; }

    public void recordShadowDraw() {
        shadowPass.recordShadowDraw();
    }

    public ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindDescriptorImages(
            GraphicsPipeline pipeline) {
        return images.bindDescriptorImages(pipeline);
    }

    public void prepareProgramImages(GraphicsPipeline pipeline) {
        images.prepareProgramImages(pipeline);
    }

    public boolean packShadowPassActive() {
        return shadowPass.packShadowPassActive();
    }

    static boolean containsSlot(int[] slots, int slot) {
        for (int candidate : slots) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }

    void cleanUpPipelines() {
        if (this.presentPipeline != null) this.presentPipeline.cleanUp();
        if (this.compositePipeline != null) this.compositePipeline.cleanUp();
        if (this.coverage.sceneSeedPipeline != null) this.coverage.sceneSeedPipeline.cleanUp();
        this.presentPipeline = null;
        this.compositePipeline = null;
        this.coverage.sceneSeedPipeline = null;
    }

    /**
     * Diagnostic: logs every chimera image transition when
     * -Dchimera.traceTransitions=true. The last traced line before a native
     * crash names the faulting site and image.
     */
    static void trace(String site, String image, VulkanImage img, int toLayout) {
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
