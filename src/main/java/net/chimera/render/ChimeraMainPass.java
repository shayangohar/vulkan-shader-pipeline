package net.chimera.render;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.chimera.mixin.ChimeraDeviceAccessor;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.PackConfig;
import net.chimera.shaderpack.PackCoveragePlan;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackPlan;
import net.chimera.shaderpack.PackProgram;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PackSource;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.chimera.shaderpack.ScreenOverlayDirectives;
import net.chimera.shaderpack.SelectorNamespace;
import net.chimera.shaderpack.TargetSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
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
 * collaborators each own one part of the work: the render targets, the
 * installed pipelines, the shadow pass, the post chain, and the session
 * lifecycle that loads and retires packs.</p>
 */
public final class ChimeraMainPass implements MainPass {
    // Collaborators only keep this pass; they are created before any other field.
    final PackSessionLifecycle sessions = new PackSessionLifecycle(this);
    final PackPostChain postChain = new PackPostChain(this);
    final MainPassTargets targets = new MainPassTargets(this);
    final PackPipelineSet pipelines = new PackPipelineSet();
    final PackShadowPass shadowPass = new PackShadowPass(this);

    private static final HandBoundaryPolicy HAND_BOUNDARY_POLICY =
            new HandBoundaryPolicy(true, true);

    /** The host hand continues from loaded color with a fresh cleared depth. */
    static HandBoundaryPolicy handBoundaryPolicy() {
        return HAND_BOUNDARY_POLICY;
    }

    static record HandBoundaryPolicy(boolean loadColor, boolean clearDepth) {}

    /**
     * The coverage path remains load-time inventory until every host
     * family can share the same coverage contract. Keeping the gate explicit
     * prevents the deferred path from allocating live images or pipelines.
     */
    private static final boolean PACK_COVERAGE_RUNTIME_ENABLED = false;

    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

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
    private static final boolean TRACE_TRANSITIONS = ChimeraRenderer.debugFlag("chimera.traceTransitions");
    Framebuffer currentFramebuffer;

    private GraphicsPipeline presentPipeline;
    GraphicsPipeline compositePipeline;
    GraphicsPipeline sceneSeedPipeline;

    final PackPostTargets packPostTargets = new PackPostTargets();
    final PackDepthTargets packDepthTargets = new PackDepthTargets();
    /** Session-owned scene-seed coverage state. */
    final PackCoverageOwner packCoverageOwner = new PackCoverageOwner();
    final PackCoverageState packCoverageState = new PackCoverageState();
    PackCoveragePlan packCoveragePlan = PackCoveragePlan.disabled();
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
    /** Resource-pack material images and flat fallbacks, retained for the renderer lifetime. */
    private MaterialMapOwner materialMapOwner;
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
    /** Tracks the one explicit HDR depth transition for the current level segment. */
    boolean hdrDepthReadable;
    /** Previous selector values for pack-owned geometry textures during one terrain draw. */
    private ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> packGeometryBindings;
    final Map<GraphicsPipeline, ProgramImages> programImages = new IdentityHashMap<>();
    record ProgramImages(String name, ProgramImageBindingManifest manifest,
                                Set<String> shadowSamplerSymbols) {}
    /** The current translucent layer draw uses the fixed pipeline: pack inputs were not live. */
    private boolean packTranslucentSuspended;
    private boolean packTranslucentSuspendLogged;
    /** Which of Minecraft's screen overlays this pack session keeps (Iris vignette and underwaterOverlay). */
    ScreenOverlayDirectives screenOverlays;
    /** True after renderLevel opens the internal HDR world target. */
    boolean levelPhase;
    /** Set when vanilla requests a depth clear while no pass is recording. */
    boolean pendingDepthClear;
    private final ChimeraHorizonRenderer horizonRenderer = new ChimeraHorizonRenderer();

    /** Opens the stable output target before a screen frame starts. */
    private boolean earlyOutputPass = true;
    /** Internal world resources can retire independently from the output. */
    boolean worldResourcesReady;
    /** The stable Minecraft main/output target and aliases are live. */
    boolean outputResourcesReady;
    /** A complete pack replacement requested by an in-game command. */
    record PendingPackChange(Path path, boolean forceReload) {}
    /** Internal request queue exposed only for deterministic state checks. */
    public static final class PackChangeQueue {
        private PendingPackChange pending;

        public boolean request(Path activePath, Path requestedPath, boolean forceReload) {
            if (!forceReload && pending == null && samePath(activePath, requestedPath)) {
                return false;
            }
            if (!forceReload && pending != null && samePath(pending.path(), requestedPath)) {
                return false;
            }
            pending = new PendingPackChange(requestedPath, forceReload);
            return true;
        }

        private static boolean samePath(Path first, Path second) {
            if (first == second) {
                return true;
            }
            if (first == null || second == null) {
                return false;
            }
            return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
        }

        PendingPackChange pending() {
            return pending;
        }

        public boolean hasPending() {
            return pending != null;
        }

        public Path pendingPath() {
            return pending == null ? null : pending.path();
        }

        public boolean pendingForced() {
            return pending != null && pending.forceReload();
        }

        public void clear() {
            pending = null;
        }
    }

    /** Pooled vanilla passes can retain any prior main-target view identity. */
    final Set<GpuTextureView> mainFamilyViews =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public ChimeraMainPass() {
        this(PackSessionLifecycle.startupPackPath());
    }

    ChimeraMainPass(Path initialPackPath) {
        this.sessions.packPath = initialPackPath;
        this.levelPhase = true;
        createResources();
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
        MemoryManager.getInstance().addFrameOp(this::refreshWorldResources);
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
        ensureWorldResources();
        Renderer.getInstance().endRenderPass();
        if (this.packStorageBufferOwner != null) {
            this.packStorageBufferOwner.beginExecutionFrame();
            boolean initialized = this.packStorageBufferOwner.initialize(Renderer.getCommandBuffer());
            if (initialized || !this.packStorageInitializationLogged) {
                logPackStoragePath("storage-initialization");
                this.packStorageInitializationLogged = true;
            }
            this.packStorageBufferOwner.prepareForUse(Renderer.getCommandBuffer());
        }
        this.currentFramebuffer = this.targets.hdrFramebuffer;
        this.hdrDepthReadable = false;
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
            if (packCoverageRuntimeEnabled() && this.packPostChainActive) {
                this.packPostTargets.prepareGeometryTarget(commandBuffer);
                this.packCoverageOwner.beginFrame(commandBuffer);
                this.packCoverageState.beginFrame();
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
                transitionHdrDepthForSampling(stack, commandBuffer);
                if (this.packDepthTargets.isConfigured()
                        && !this.packDepthTargets.captureScene(
                        commandBuffer, this.targets.hdrFramebuffer.getDepthAttachment())) {
                    LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (depth conversion failed)");
                }
            }

            if (packCoverageRuntimeEnabled() && this.packCoverageState.canSeed()) {
                seedPackScene(commandBuffer, hdrColor);
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
                if (packCoverageRuntimeEnabled()) {
                    this.packCoverageState.endFrame();
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
            if (packCoverageRuntimeEnabled()) {
                this.packCoverageState.endFrame();
            }
        }
    }

    /**
     * Retained as the terrain-family seam. Pack terrain stays on the live HDR
     * render pass; the post graph reads that complete HDR image as colortex0.
     */
    public void beginPackCoverageWindow(TerrainRenderType renderType) {
        if (renderType == TerrainRenderType.TRANSLUCENT && !this.shadowPass.shadowPassActive
                && this.pipelines.waterRoute != null
                && !packTranslucentInputsReady(this.pipelines.waterRoute)) {
            // The fixed pipeline draws this layer; the pack program never
            // binds a stale or missing image.
            ChimeraTerrainPipelines.suspendTranslucentOverride(true);
            this.packTranslucentSuspended = true;
            if (!this.packTranslucentSuspendLogged) {
                this.packTranslucentSuspendLogged = true;
                LOGGER.warn("[chimera] pack gbuffers_water: inputs unavailable this frame, "
                        + "fixed translucent pipeline used");
            }
            return;
        }
        beginPackResourceWindow(renderType);
        PackPipelines.PackTerrain terrain = switch (renderType) {
            case SOLID, CUTOUT -> this.pipelines.terrainRoute;
            case TRANSLUCENT -> this.pipelines.waterRoute;
            default -> null;
        };
        if (terrain == null || !terrain.requiresDynamicAttachments()
                || !this.packTargetResourcesReady || this.shadowPass.shadowPassActive
                || this.targets.hdrFramebuffer == null || this.targets.hdrFramebuffer.getDepthAttachment() == null) {
            return;
        }
        if (PackGeometryContext.active()) {
            return;
        }
        try {
            List<Integer> outputs = terrain.outputPlan().targetSlots();
            VulkanImage hdrColor = this.targets.hdrFramebuffer.getColorAttachment();
            Renderer.getInstance().endRenderPass();
            this.packPostTargets.beginGeometry(Renderer.getCommandBuffer(), hdrColor, outputs);
            PackGeometryContext.beginGeometry(
                    this.packPostTargets.geometryAttachments(outputs),
                    this.targets.hdrFramebuffer.getDepthAttachment());
        } catch (RuntimeException failure) {
            this.packPostTargets.abortGeometry(this.targets.hdrFramebuffer.getColorAttachment());
            this.currentFramebuffer = this.targets.hdrFramebuffer;
            this.rebindMainTarget();
            logPackRuntimeFailure("geometry-begin:" + renderType, failure);
        }
    }

    public PackPipelines.PackEntity familyPipeline(ChimeraEntityBridge.Family family) {
        return pipelines.familyPipeline(family);
    }

    public PackPipelines.PackParticle particlePipeline(ChimeraEntityBridge.Family family) {
        return pipelines.particlePipeline(family);
    }

    /**
     * Opens the authored attachment window around one host geometry family
     * draw or batch. The bridge only selects the pipeline; this pass owns the target
     * transitions: it closes the host HDR pass, binds the declared targets
     * in output-location order with shared depth (target 0 uses live HDR), and
     * enters the guarded geometry context whose dynamic-rendering bridge
     * supplies the attachments. Returns true only when this call opened the
     * window; the caller closes exactly what it opened.
     */
    public boolean beginPackFamilyWindow(PackPipelines.PackEntity family) {
        return family != null && beginPackFamilyWindow(family.outputPlan());
    }

    public boolean beginPackFamilyWindow(GeometryOutputPlan outputs) {
        if (outputs == null || !outputs.requiresDynamicAttachments()
                || !this.packTargetResourcesReady || this.shadowPass.shadowPassActive
                || this.targets.hdrFramebuffer == null
                || this.targets.hdrFramebuffer.getDepthAttachment() == null
                || !this.packPostTargets.isConfigured()) {
            return false;
        }
        if (PackGeometryContext.active()) {
            return false;
        }
        try {
            VulkanImage hdrColor = this.targets.hdrFramebuffer.getColorAttachment();
            Renderer.getInstance().endRenderPass();
            this.packPostTargets.beginGeometry(Renderer.getCommandBuffer(), hdrColor, outputs.targetSlots());
            PackGeometryContext.beginGeometry(
                    this.packPostTargets.geometryAttachments(outputs.targetSlots()),
                    this.targets.hdrFramebuffer.getDepthAttachment());
            return true;
        } catch (RuntimeException failure) {
            this.packPostTargets.abortGeometry(this.targets.hdrFramebuffer.getColorAttachment());
            this.currentFramebuffer = this.targets.hdrFramebuffer;
            this.rebindMainTarget();
            logPackRuntimeFailure("family-begin", failure);
            return false;
        }
    }

    /** Commits the family window's auxiliary outputs and reopens the HDR pass. */
    public void endPackFamilyWindow(PackPipelines.PackEntity family) {
        if (family != null) endPackFamilyWindow(family.outputPlan());
    }

    public void endPackFamilyWindow(GeometryOutputPlan outputs) {
        if (outputs == null || !outputs.requiresDynamicAttachments()
                || !PackGeometryContext.geometryActive() || this.shadowPass.shadowPassActive) {
            return;
        }
        try {
            Renderer.getInstance().endRenderPass();
            PackGeometryContext.close();
            this.packPostTargets.commitGeometry(outputs.targetSlots(),
                    this.targets.hdrFramebuffer.getColorAttachment());
            this.currentFramebuffer = this.targets.hdrFramebuffer;
            this.rebindMainTarget();
        } catch (RuntimeException failure) {
            this.packPostTargets.abortGeometry(this.targets.hdrFramebuffer.getColorAttachment());
            logPackRuntimeFailure("family-commit", failure);
            if (PackGeometryContext.active()) PackGeometryContext.close();
            this.rebindMainTarget();
        }
    }
    boolean packCoverageRuntimeEnabled() {
        return PACK_COVERAGE_RUNTIME_ENABLED;
    }

    /** The direct-HDR terrain path has no separate coverage window to close. */
    public void endPackCoverageWindow(TerrainRenderType renderType) {
        if (this.shadowPass.shadowPassActive) return;
        if (this.packTranslucentSuspended && renderType == TerrainRenderType.TRANSLUCENT) {
            this.packTranslucentSuspended = false;
            ChimeraTerrainPipelines.suspendTranslucentOverride(false);
            return;
        }
        boolean geometryWindow = PackGeometryContext.geometryActive();
        if (geometryWindow) {
            PackPipelines.PackTerrain terrain = renderType == TerrainRenderType.TRANSLUCENT
                    ? this.pipelines.waterRoute : this.pipelines.terrainRoute;
            if (terrain == null || terrain.outputPlan() == null) {
                PackGeometryContext.close();
                restorePackResourceWindow();
                return;
            }
            try {
                Renderer.getInstance().endRenderPass();
                PackGeometryContext.close();
                this.packPostTargets.commitGeometry(terrain.outputPlan().targetSlots(),
                        this.targets.hdrFramebuffer.getColorAttachment());
                this.currentFramebuffer = this.targets.hdrFramebuffer;
                this.rebindMainTarget();
            } catch (RuntimeException failure) {
                this.packPostTargets.abortGeometry(this.targets.hdrFramebuffer.getColorAttachment());
                logPackRuntimeFailure("geometry-commit", failure);
                if (PackGeometryContext.active()) PackGeometryContext.close();
                this.rebindMainTarget();
            } finally {
                restorePackResourceWindow();
            }
            return;
        }
        if (renderType != TerrainRenderType.SOLID && renderType != TerrainRenderType.CUTOUT) {
            restorePackResourceWindow();
            return;
        }
        if (!PackGeometryContext.coverageActive()) {
            restorePackResourceWindow();
            return;
        }
        Renderer.getInstance().endRenderPass();
        PackGeometryContext.close();
        this.packCoverageState.commitPackWrite();
        this.currentFramebuffer = this.targets.hdrFramebuffer;
        this.rebindMainTarget();
        restorePackResourceWindow();
    }

    /** Binds pack-owned sampled images for the guarded terrain draw only. */
    private void beginPackResourceWindow(TerrainRenderType renderType) {
        if (this.shadowPass.shadowPassActive || !ChimeraRenderer.segmentsActive()) return;
        PackPipelines.PackTerrain terrain = switch (renderType) {
            case SOLID, CUTOUT -> this.pipelines.terrainRoute;
            case TRANSLUCENT -> this.pipelines.waterRoute;
            default -> null;
        };
        if (terrain == null) return;
        prepareProgramImages(terrain.pipeline());
    }

    /**
     * Pairs the real selector atlas with LevelRenderer.chunkLayerSampler.
     * WorldRenderer calls this only after VulkanMod has synchronized slot 0.
     */
    public void bindTerrainAtlasAfterSelector(TerrainRenderType renderType) {
        if (this.shadowPass.shadowPassActive) return;
        if (this.packTranslucentSuspended && renderType == TerrainRenderType.TRANSLUCENT) return;
        PackPipelines.PackTerrain terrain = renderType == TerrainRenderType.TRANSLUCENT
                ? this.pipelines.waterRoute : this.pipelines.terrainRoute;
        if (terrain == null) return;
        restorePackResourceWindow();
        // Capture the draw context before the transaction: the albedo image
        // straight from the synchronized selector plus the authoritative
        // chunk sampler profile. The resolver below binds from this
        // immutable context instead of re-querying slot state mid-draw.
        DrawMaterialContext drawContext =
                DrawMaterialContext.forTerrain(VTextureSelector.getImage(0));
        this.packGeometryBindings = bindProgramImages(terrain.pipeline(),
                this.packPostTargets.sourceImages(), this.targets.hdrFramebuffer.getColorAttachment(),
                drawContext);
        if (this.packGeometryBindings.containsSlot(0)) {
            ChimeraTextureBindingState.bindTerrainAtlas(VTextureSelector.getImage(0));
        }
    }

    /** Restores host selector values after a guarded terrain draw. */
    void restorePackResourceWindow() {
        if (this.packGeometryBindings != null) {
            this.packGeometryBindings.close();
            this.packGeometryBindings = null;
        }
    }

    // ------------------------------------------------------------------
    // MainPass contract
    // ------------------------------------------------------------------

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        ensureOutputResources();
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
                transitionHdrDepthForSampling(stack, commandBuffer);
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
        ProgramImages program = this.programImages.get(pipeline);
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
            prepareProgramImages(resolvePipeline);
            previous = bindProgramImages(resolvePipeline, null, source,
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

    /** Captures opaque depth at the boundary immediately before translucency. */
    public void captureOpaqueDepthBeforeTranslucent() {
        if (this.shadowPass.shadowPassActive || !this.levelPhase || this.targets.hdrFramebuffer == null) {
            return;
        }
        boolean captureOpaque = this.packDepthTargets.isConfigured()
                && this.packDepthTargets.plan().depthtex1();
        boolean runEarlyPost = this.packPostChainActive
                && this.packPostTargets.isConfigured()
                && this.packFrameSchedule.hasWindow(PackFrameSchedulePlan.PostWindow.EARLY);
        if (!captureOpaque && !runEarlyPost) {
            return;
        }
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        if (captureOpaque) {
            boolean captured = this.packDepthTargets.captureOpaque(
                    commandBuffer, this.targets.hdrFramebuffer.getDepthAttachment());
            if (!captured) {
                LOGGER.warn("[chimera] pack depth graph: depthtex1 capture unavailable");
            }
        }
        if (runEarlyPost) {
            // Iris beginHand copies depthtex2 before deferred runs. A pack solid hand
            // already captured it at its segment; otherwise no hand has drawn yet and
            // the opaque depth is the pre-hand depth.
            if (this.packDepthTargets.isConfigured() && this.packDepthTargets.plan().depthtex2()
                    && !this.packDepthTargets.currentAvailable("depthtex2")
                    && !this.packDepthTargets.capturePreHand(
                            commandBuffer, this.targets.hdrFramebuffer.getDepthAttachment())) {
                LOGGER.warn("[chimera] pack depth graph: early depthtex2 capture unavailable");
            }
            // Deferred passes read depthtex0 here, before translucents: the
            // opaque depth. finishLevelSegment re-captures it for composite.
            if (this.packFrameSchedule.earlyReadsDepthtex0()
                    && this.packDepthTargets.isConfigured()
                    && !this.packDepthTargets.captureOpaqueScene(
                            commandBuffer, this.targets.hdrFramebuffer.getDepthAttachment())) {
                LOGGER.warn("[chimera] pack depth graph: early depthtex0 capture unavailable");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanImage hdrColor = this.targets.hdrFramebuffer.getColorAttachment();
                hdrColor.transitionImageLayout(stack, commandBuffer,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                postChain.runPackPostWindow(commandBuffer, hdrColor,
                        PackFrameSchedulePlan.PostWindow.EARLY);
                this.packEarlyPostCompleted = true;
                // Translucents, particles and the rest of the world compose
                // over the deferred result, as they do over Iris' colortex0.
                if (this.packPostChainActive) {
                    this.packPostTargets.adoptTarget0(commandBuffer, hdrColor);
                }
                hdrColor.transitionImageLayout(stack, commandBuffer,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Renderer.getInstance().beginRenderPass(this.targets.hdrAuxRenderPass, this.targets.hdrFramebuffer);
            Renderer.setViewport(0, 0, this.targets.hdrFramebuffer.getWidth(),
                    this.targets.hdrFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.targets.hdrFramebuffer.scissor(stack));
        }
        this.hdrDepthReadable = false;
    }

    private void transitionHdrDepthForSampling(MemoryStack stack, VkCommandBuffer commandBuffer) {
        if (!this.packNeedsHdrDepth || this.hdrDepthReadable || this.targets.hdrFramebuffer == null) {
            return;
        }
        VulkanImage hdrDepth = this.targets.hdrFramebuffer.getDepthAttachment();
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
        if (this.materialMapOwner != null) this.materialMapOwner.close();
        this.materialMapOwner = null;
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

    /** Renderer-lifetime material owner for the atlas mixin; null before first use. */
    public MaterialMapOwner materialMaps() {
        return this.materialMapOwner;
    }

    /** Creates the material owner once on the render thread; later installs only ensure it. */
    void ensureMaterialMapOwner() {
        if (this.materialMapOwner == null) {
            this.materialMapOwner = MaterialMapOwner.create();
        }
    }

    /**
     * Builds queued atlas companions at the safe frame-boundary seam.
     * Material maps are independent of shader-pack state, so this runs even
     * when no pack is installed. Never throws out of the frame boundary.
     */
    public void pumpMaterialMapBuilds() {
        try {
            ensureMaterialMapOwner();
            var manager = Minecraft.getInstance().getResourceManager();
            MaterialMapOwner.ResourceLookup lookup = (namespace, path) -> {
                try {
                    return manager.getResource(Identifier.fromNamespaceAndPath(namespace, path))
                            .map(resource -> {
                                try {
                                    return resource.open();
                                } catch (IOException openFailure) {
                                    return null;
                                }
                            });
                } catch (RuntimeException lookupFailure) {
                    return Optional.empty();
                }
            };
            this.materialMapOwner.pumpPendingBuilds(lookup, MaterialMapOwner::decodeNative,
                    () -> SimpleTextureIndex.snapshot(Minecraft.getInstance().getTextureManager()));
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] material maps: pump failed", failure);
        }
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
        createResources();
    }

    void createResources() {
        this.packPostChainActive = false;
        this.packPostTargets.cleanUp();
        this.packDepthTargets.cleanUp();
        targets.cleanUpFramebuffersAndPasses();
        cleanUpPipelines();

        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        int width = Math.max(swapChain.getWidth(), 1);
        int height = Math.max(swapChain.getHeight(), 1);

        sessions.loadPackConfig();
        PackConfig.ShadowSettings shadowSettings = this.packConfig != null
                ? this.packConfig.shadowSettings()
                : new PackConfig.ShadowSettings(
                        PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION,
                        PackConfig.DEFAULT_SHADOW_DISTANCE,
                        Map.of(), List.of());
        this.shadowPass.shadowMap.init(shadowSettings);
        targets.createHdrFramebuffer(width, height);
        targets.createOutputFramebuffer(width, height);
        targets.createRenderPasses();
        createPipelines();
        targets.createHdrInteropTextures();
        targets.createOutputInteropTextures();
        this.worldResourcesReady = true;
        this.outputResourcesReady = true;
        this.currentFramebuffer = this.targets.compositeFramebuffer;
        this.earlyOutputPass = true;
        postChain.configurePackPostTargets(width, height);
        loadPackPipelines();
        if (shadowPass.usePackShadowRuntime() && this.shadowPass.shadowMap.isInitialized()) {
            this.shadowPass.packShadowDepth.install();
        }
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

    private void createPipelines() {
        // NOTE: pipeline depth state is decided at BIND time by VulkanMod
        // (GraphicsPipeline.getHandle(PipelineState) <- getCurrentPipelineState
        // snapshots VRenderSystem globals), so the present draw disables depth
        // test/write at the draw site, not here.
        this.presentPipeline = ChimeraPostPipelines.create("chimera_present");
        this.compositePipeline = ChimeraPostPipelines.create("chimera_composite");
    }

    /** Merges host HDR pixels into the pack target without overwriting covered geometry. */
    private void seedPackScene(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        VulkanImage target = this.packPostTargets.geometryTarget0();
        VulkanImage coverage = this.packCoverageOwner.image();
        if (target == null || coverage == null || this.sceneSeedPipeline == null
                || !this.packCoverageState.canSeed()) {
            return;
        }
        VulkanImage previousHdr = VTextureSelector.getBoundTexture(0);
        VulkanImage previousCoverage = VTextureSelector.getBoundTexture(SelectorNamespace.COVERAGE_SLOT);
        boolean depthTest = VRenderSystem.depthTest;
        boolean depthMask = VRenderSystem.depthMask;
        int colorMask = VRenderSystem.getColorMask();
        boolean blendEnabled = PipelineState.blendInfo.enabled;
        int previousSrcRgb = PipelineState.blendInfo.srcRgbFactor;
        int previousDstRgb = PipelineState.blendInfo.dstRgbFactor;
        int previousSrcAlpha = PipelineState.blendInfo.srcAlphaFactor;
        int previousDstAlpha = PipelineState.blendInfo.dstAlphaFactor;
        int previousBlendOp = PipelineState.blendInfo.blendOp;
        boolean cullEnabled = VRenderSystem.cull;
        VTextureSelector.bindTexture(0, hdrColor);
        VTextureSelector.bindTexture(SelectorNamespace.COVERAGE_SLOT, coverage);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            coverage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            int deviceLimit = deviceMaxColorAttachments();
            MrtPipelineContext.begin(new int[] {target.format}, deviceLimit);
            VkRenderingAttachmentInfo.Buffer attachments = VkRenderingAttachmentInfo.calloc(1, stack);
            attachments.get(0).sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(PackPostTargets.attachmentView(target))
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRect2D area = VkRect2D.calloc(stack);
            area.offset().set(0, 0);
            area.extent().set(target.width, target.height);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                    .renderArea(area).layerCount(1).pColorAttachments(attachments);
            vkCmdBeginRenderingKHR(commandBuffer, rendering);
            VRenderSystem.disableDepthTest();
            VRenderSystem.depthMask(false);
            VRenderSystem.enableBlend();
            VRenderSystem.blendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            VRenderSystem.disableCull();
            Renderer.setViewport(0, 0, target.width, target.height, stack);
            drawFullscreen(commandBuffer, this.sceneSeedPipeline);
            vkCmdEndRenderingKHR(commandBuffer);
            target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            this.packPostTargets.commitSceneSeed(target);
            this.packCoverageState.commitSeed();
        } finally {
            MrtPipelineContext.end();
            VTextureSelector.bindTexture(0, previousHdr);
            VTextureSelector.bindTexture(SelectorNamespace.COVERAGE_SLOT, previousCoverage);
            VRenderSystem.depthTest = depthTest;
            VRenderSystem.depthMask = depthMask;
            VRenderSystem.colorMask((colorMask & 1) != 0, (colorMask & 2) != 0,
                    (colorMask & 4) != 0, (colorMask & 8) != 0);
            if (blendEnabled) {
                VRenderSystem.enableBlend();
            } else {
                VRenderSystem.disableBlend();
            }
            PipelineState.blendInfo.srcRgbFactor = previousSrcRgb;
            PipelineState.blendInfo.dstRgbFactor = previousDstRgb;
            PipelineState.blendInfo.srcAlphaFactor = previousSrcAlpha;
            PipelineState.blendInfo.dstAlphaFactor = previousDstAlpha;
            PipelineState.blendInfo.blendOp = previousBlendOp;
            VRenderSystem.cull = cullEnabled;
        }
    }

    /**
     * Loads the active OptiFine-format pack and builds
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
        this.pipelines.clear();
        this.postChain.packPostExecution.clear();
        this.postChain.packPostBoundaryLogged.clear();
        this.postChain.packFinalExecution = null;
        this.packPostChainRejected = false;
        restorePackResourceWindow();
        this.packTranslucentSuspended = false;
        this.packTranslucentSuspendLogged = false;
        this.shadowPass.shadowCastersFailed = false;
        this.shadowPass.shadowCutoutDispositionLogged = false;
        this.packPipelinesLoaded = true;

        if (this.packPrograms == null) {
            // loadPackConfig found no pack (or no programs).
            if (this.conformanceReport != null) {
                this.conformanceReport.markUnattemptedAsFallback();
                LOGGER.info("[chimera] conformance {}", this.conformanceReport.toJson());
                sessions.logConformanceSummary();
            }
            return;
        }

        new PackPipelineInstaller(this).installPrograms();
        ChimeraEntityBridge.install(this.pipelines.entity, this.pipelines.translucentEntity,
                this.pipelines.spiderEyes, this.pipelines.block, this.pipelines.damagedBlock,
                this.pipelines.hand, this.pipelines.handWater, this.pipelines.particle,
                this.pipelines.translucentParticle,
                this.packPlan == null ? null : this.packPlan.entityIds());
        ChimeraEntityBridge.installTextVariants(this.pipelines.translucentEntityText, this.pipelines.blockText);
        ChimeraEntityBridge.installHostLayout(this.pipelines.hostLayout);
        ChimeraEntityBridge.installShadowCasters(this.pipelines.shadowEntities);
        LOGGER.info("[chimera] family bridge: targets entity={}, block={}",
                this.pipelines.entity == null ? "none"
                        : this.pipelines.entity.outputPlan() == null
                        ? List.of(0) : this.pipelines.entity.outputPlan().targetSlots(),
                this.pipelines.block == null ? "none"
                        :                 this.pipelines.block.outputPlan() == null
                        ? List.of(0) : this.pipelines.block.outputPlan().targetSlots());
        if (!enforceMaskCriticalFamilyContract()) {
            return;
        }
        ChimeraSkyBridge.install(this.pipelines.skyBasic, this.pipelines.skyTextured,
                this.pipelines.cloud, this.pipelines.cloudsDrawNothing,
                this.packPlan == null ? null : this.packPlan.cloudSetting());
        this.pipelines.postStages.sort(Comparator.comparing(
                PackPipelines.PackPost::name, PostTargetPlan.programComparator()));
        postChain.buildPackPostExecutionPlan();
        if (this.packCoveragePlan.enabled()) {
            LOGGER.info("[chimera] scene seed: direct HDR composition active; coverage merge deferred families={}",
                    this.packCoveragePlan.families());
        }
        if (this.pipelines.shadowPipeline != null && !shadowPass.usePackShadowRuntime()) {
            if (this.conformanceReport != null) {
                this.conformanceReport.markRuntime("shadow",
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "SHADOW_CONSUMER_UNAVAILABLE");
                this.conformanceReport.addDeviation("SHADOW_CONSUMER_UNAVAILABLE");
            }
            LOGGER.warn("[chimera] pack shadow: fixed host fallback (terrain consumer unavailable)");
        }
        if (this.pipelines.shadowPipeline == null
                && this.packPrograms.stream().noneMatch(program -> program.name().equals("shadow"))) {
            if (this.conformanceReport != null) {
                this.conformanceReport.addDeviation("SHADOW_PROGRAM_MISSING");
            }
            LOGGER.warn("[chimera] pack shadow: fallback=IDENTITY (SHADOW_PROGRAM_MISSING)");
        }
        if (this.conformanceReport != null) {
            this.conformanceReport.markUnattemptedAsFallback();
            LOGGER.info("[chimera] conformance {}", this.conformanceReport.toJson());
            sessions.logConformanceSummary();
        }
        logPackStoragePath("pipelines-installed");
    }

    private static final ProgramImageBindingManifest IDENTITY_IMAGES = new ProgramImageBindingManifest(List.of(
            new ProgramImageBindingManifest.Entry("Sampler0", 0, 1, 63,
                    ProgramImageBindingManifest.Kind.COLOR_TARGET, "colortex0", 0, 0, "colortex0")));

    private static final ProgramImageBindingTransaction.Store<ChimeraTextureBindingState.Snapshot> IMAGE_STORE =
            new ProgramImageBindingTransaction.Store<>() {
                public ChimeraTextureBindingState.Snapshot capture(int slot) {
                    return ChimeraTextureBindingState.capture(slot, VTextureSelector.getImage(slot));
                }
                public boolean available(ChimeraTextureBindingState.Snapshot value) {
                    return value != null && value.image() != null;
                }
                public void bind(int slot, ChimeraTextureBindingState.Snapshot value) {
                    VTextureSelector.bindTexture(slot, value.image());
                    ChimeraTextureBindingState.restore(slot, value);
                }
                public void restore(int slot, ChimeraTextureBindingState.Snapshot value) {
                    bind(slot, value);
                }
            };
    /**
     * Mask-critical family contract. Entity and
     * block draws cover world pixels the pack's post passes classify by
     * material metadata, and pack geometry writes authored targets only post
     * presents. A frame missing either family therefore has no coherent pack
     * form: post over host entities ghosts, and pack geometry without post is
     * invisible. The whole session falls back to the host frame, as if no pack
     * were selected, and says why. Returns false when the session was released.
     */
    private boolean enforceMaskCriticalFamilyContract() {
        List<String> missing = maskCriticalFamilyFallback();
        if (missing.isEmpty()) {
            LOGGER.info("[chimera] family bridge: mask-critical contract holds "
                    + "(entity and block installed or not authored)");
            return true;
        }
        this.packFrameUnsupported = String.join(", ", missing);
        if (this.conformanceReport != null) {
            this.conformanceReport.markFrameFallback(
                    "PACK_FRAME_UNSUPPORTED:" + String.join(",", missing), "PACK_FRAME_UNSUPPORTED");
            LOGGER.info("[chimera] conformance {}", this.conformanceReport.toJson());
            sessions.logConformanceSummary();
        }
        LOGGER.warn("[chimera] pack frame unsupported: {} could not install; vanilla rendering for {}",
                this.packFrameUnsupported, PackSessionLifecycle.packLabel(this.sessions.packPath));
        // Nothing built this session has been submitted yet, so release it directly.
        sessions.cleanUpPackVariant();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.literal(
                    "[Chimera] " + PackSessionLifecycle.packLabel(this.sessions.packPath) + " is not supported yet ("
                            + this.packFrameUnsupported + " could not load). Rendering vanilla. See latest.log."));
        }
        return false;
    }

    /**
     * Authored mask-critical families without an installed pipeline. Pure
     * predicate over install state; the enforcement above consumes it once
     * per install, and target reconfiguration honors the stored verdict so
     * a resize cannot reopen mixed frames.
     */
    private List<String> maskCriticalFamilyFallback() {
        List<String> missing = new ArrayList<>();
        if (this.packPlan == null) {
            return missing;
        }
        if (this.packPlan.program("gbuffers_entities") != null && this.pipelines.entity == null) {
            missing.add("gbuffers_entities");
        }
        if (this.packPlan.program("gbuffers_block") != null && this.pipelines.block == null) {
            missing.add("gbuffers_block");
        }
        return missing;
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
        ProgramImages program = this.programImages.get(pipeline);
        // Fullscreen and terrain seams already hold a transaction with their exact graph inputs.
        if (program == null || PostTargetPlan.isPostProgramName(program.name())
                || (!this.shadowPass.shadowPassActive && this.packGeometryBindings != null)) return null;
        return bindProgramImages(pipeline);
    }

    /** All graphics families reach this seam before descriptor upload. */
    public ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindProgramImages(
            GraphicsPipeline pipeline, VulkanImage[] colors, VulkanImage fallback,
            DrawMaterialContext drawContext) {
        ProgramImages program = this.programImages.get(pipeline);
        if (program == null) {
            if (pipeline != this.compositePipeline) return null;
            program = new ProgramImages("identity", IDENTITY_IMAGES, Set.of());
        }
        ProgramImages bound = program;
        return ProgramImageBindingTransaction.bind(bound.name(), bound.manifest(), IMAGE_STORE, entry -> {
            if (entry.kind() == ProgramImageBindingManifest.Kind.HOST_TEXTURE) {
                int hostSlot = PackResourcePlan.HOST_TEXTURE_SLOTS.get(entry.resourceKey());
                return IMAGE_STORE.capture(hostSlot);
            }
            if (entry.kind() == ProgramImageBindingManifest.Kind.MATERIAL_MAP) {
                // The context was captured before the first slot capture, so
                // the resolver sees the unmutated draw. Rollback stays with
                // the transaction.
                if (this.materialMapOwner == null) return null;
                return this.materialMapOwner.resolveDrawMaterial(drawContext, entry.resourceKey());
            }
            VulkanImage image = switch (entry.kind()) {
                case COLOR_TARGET -> PackPostChain.resolvePackColorInput(
                        PackResourcePlan.targetIndex(entry.resourceKey()), colors, fallback);
                case DEPTH_TARGET -> {
                    VulkanImage depth = this.packDepthTargets.image(entry.resourceKey());
                    yield depth == null && entry.resourceKey().equals("depthtex0")
                            && this.targets.hdrFramebuffer != null
                            ? this.targets.hdrFramebuffer.getDepthAttachment() : depth;
                }
                case SHADOW_DEPTH -> this.shadowPass.shadowMap.getShadowFramebuffer() == null ? null
                        : entry.resourceKey().equals("shadowtex1") ? this.shadowPass.shadowMap.opaqueDepth()
                        : this.shadowPass.shadowMap.getShadowFramebuffer().getDepthAttachment();
                case SHADOW_COLOR ->
                        this.shadowPass.shadowMap.shadowColor(entry.resourceKey().equals("shadowcolor1") ? 1 : 0);
                case PACK_TEXTURE -> this.packResourceOwner == null ? null
                        : this.packResourceOwner.image(entry.resourceKey());
                case ADVANCED_IMAGE -> this.packAdvancedImageOwner == null ? null
                        : this.packAdvancedImageOwner.image(entry.resourceKey());
                case MATERIAL_MAP -> throw new AssertionError();
                case HOST_TEXTURE -> throw new AssertionError();
            };
            return new ChimeraTextureBindingState.Snapshot(image,
                    image == null ? null : shadowPass.shadowSamplerFor(bound, entry, image));
        });
    }

    public ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindProgramImages(
            GraphicsPipeline pipeline) {
        return bindProgramImages(pipeline, this.packPostTargets.sourceImages(),
                this.targets.hdrFramebuffer == null ? null : this.targets.hdrFramebuffer.getColorAttachment(),
                DrawMaterialContext.captureLive());
    }

    /**
     * True when every pack target and depth snapshot the water program samples
     * holds this frame's data. A failed early window or depth capture leaves
     * one invalid, and the manifest resolver would then reject the draw.
     */
    private boolean packTranslucentInputsReady(PackPipelines.PackTerrain water) {
        ProgramImages program = this.programImages.get(water.pipeline());
        if (program == null) return false;
        for (ProgramImageBindingManifest.Entry entry : program.manifest().entries()) {
            boolean ready = switch (entry.kind()) {
                case COLOR_TARGET -> this.packPostTargets.isConfigured()
                        && this.packPostTargets.currentTargetAvailable(
                                PackResourcePlan.targetIndex(entry.resourceKey()));
                case DEPTH_TARGET -> this.packDepthTargets.image(entry.resourceKey()) != null;
                default -> true;
            };
            if (!ready) return false;
        }
        return true;
    }

    /** Called before opening attachments, including host geometry render passes. */
    public void prepareProgramImages(GraphicsPipeline pipeline) {
        ProgramImages program = this.programImages.get(pipeline);
        if (program == null || this.packAdvancedImageOwner == null
                || !this.packAdvancedImageOwner.needsGraphicsTransition(program.manifest())) return;
        boolean resume = Renderer.getInstance().getBoundRenderPass() != null;
        if (resume) Renderer.getInstance().endRenderPass();
        try {
            this.packAdvancedImageOwner.prepareGraphicsImages(Renderer.getCommandBuffer(), program.manifest());
        } finally {
            if (resume) this.rebindMainTarget();
        }
    }

    private void logPackStoragePath(String phase) {
        if (this.packStorageBufferOwner == null || this.packPlan == null) return;
        PackAdvancedResourcePlan resources = this.packPlan.advancedResources();
        boolean storageRequired = !resources.buffers().isEmpty()
                || resources.storageBuffers().values().stream().anyMatch(bindings -> !bindings.isEmpty());
        String status = storageRequired
                ? this.packStorageBufferOwner.storagePathStatus("shadow", "composite")
                : "storage=0,required=false,ready=true,missing=none";
        LOGGER.info("[chimera] storage path: phase={},plannedBytes={},allocatedBytes={},initializedBytes={},{}",
                phase, this.packStorageBufferOwner.plannedBytes(),
                this.packStorageBufferOwner.allocatedBytes(),
                this.packStorageBufferOwner.initializedBytes(), status);
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

    private void invalidateWorldResources() {
        long oldHdrId = targets.hdrImageId();
        Framebuffer oldHdr = this.targets.hdrFramebuffer;
        this.worldResourcesReady = false;
        this.levelPhase = false;
        this.pendingDepthClear = false;
        this.shadowPass.shadowPending = false;
        if (oldHdr == null && this.targets.hdrColorTexture == null && this.targets.hdrDepthTexture == null) {
            return;
        }

        // The host composition window borrows the HDR depth attachment. Close
        // that logical window before retiring the framebuffer so no later
        // RenderPass can dereference the old depth image.
        if (PackGeometryContext.active()) {
            Renderer.getInstance().endRenderPass();
            PackGeometryContext.close();
        }
        if (Renderer.getInstance().getBoundFramebuffer() == oldHdr) {
            Renderer.getInstance().endRenderPass();
        }
        targets.releaseHdrInteropTextures();
        if (oldHdr != null) oldHdr.cleanUp(false);
        this.targets.hdrFramebuffer = null;
        if (this.currentFramebuffer == oldHdr) {
            this.currentFramebuffer = this.targets.compositeFramebuffer;
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
                    Renderer.getCurrentFrame(), targets.hdrImageId());
        }
    }

    private void recreateWorldAttachments() {
        Framebuffer output = this.targets.compositeFramebuffer;
        int width = output != null ? output.getWidth() : Math.max(Renderer.getInstance().getSwapChain().getWidth(), 1);
        int height = output != null ? output.getHeight()
                : Math.max(Renderer.getInstance().getSwapChain().getHeight(), 1);
        targets.createHdrFramebuffer(width, height);
        targets.createHdrInteropTextures();
        this.hdrDepthReadable = false;
        this.worldResourcesReady = true;
        this.earlyOutputPass = false;
    }

    private void ensureWorldResources() {
        if (this.worldResourcesReady && targets.hdrResourcesLive()) {
            return;
        }

        invalidateWorldResources();
        recreateWorldAttachments();
        if (TRACE_TRANSITIONS) {
            LOGGER.info("[chimera] recreated world attachments before level frame={} newHdrId={}",
                    Renderer.getCurrentFrame(), targets.hdrImageId());
        }
    }

    void ensureOutputResources() {
        if (this.outputResourcesReady && targets.outputResourcesLive()) {
            return;
        }
        LOGGER.warn("[chimera] stable output attachment closed; rebuilding full pass resources");
        createResources();
    }

    void cleanUpPipelines() {
        if (this.presentPipeline != null) this.presentPipeline.cleanUp();
        if (this.compositePipeline != null) this.compositePipeline.cleanUp();
        if (this.sceneSeedPipeline != null) this.sceneSeedPipeline.cleanUp();
        this.presentPipeline = null;
        this.compositePipeline = null;
        this.sceneSeedPipeline = null;
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
