package net.chimera.render;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.mixin.ChimeraDeviceAccessor;
import net.chimera.shaderpack.DepthGraphPlan;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackPlan;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PackConfig;
import net.chimera.shaderpack.PackProgram;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackSource;
import net.chimera.shaderpack.PackMaterialResolver;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.TargetStep;
import net.chimera.shaderpack.TargetSpec;
import net.chimera.shaderpack.PackResourceBinding;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PackResourceStatus;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.chimera.shaderpack.PackCoveragePlan;
import net.chimera.shaderpack.FamilyAdapterRegistry;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
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
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

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

    /** Immutable post-stage order and output contract retained for the pack session. */
    private record PackPostExecution(
            String name,
            List<Integer> outputTargets,
            PackPipelines.PackPost post,
            boolean finalStage
    ) {
        private PackPostExecution {
            outputTargets = outputTargets == null
                    ? List.of()
                    : outputTargets.stream().distinct().sorted().toList();
        }
    }

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
    private GraphicsPipeline sceneSeedPipeline;

    /** Pack programs swapped onto the resolve/present seams; null = identity. */
    private GraphicsPipeline packCompositePipeline;
    private GraphicsPipeline packFinalPipeline;
    private int[] packCompositeSlots;
    private int[] packFinalSlots;
    private List<String> packCompositeSamplerNames = List.of();
    private List<String> packFinalSamplerNames = List.of();
    /** All successfully built post programs, sorted by deterministic pass order. */
    private final List<PackPipelines.PackPost> packPostStages = new ArrayList<>();
    /** All discovered post programs, including static fallback stages. */
    private final List<PackPostExecution> packPostExecution = new ArrayList<>();
    /** Identity-boundary diagnostics are emitted once per pack session. */
    private final Set<String> packPostBoundaryLogged = new java.util.HashSet<>();
    /** The final pass is retained separately because it runs before hand/GUI composition. */
    private PackPipelines.PackPost packFinalPost;
    private PackPostExecution packFinalExecution;
    private final PackPostTargets packPostTargets = new PackPostTargets();
    private final PackDepthTargets packDepthTargets = new PackDepthTargets();
    /** Session-owned scene-seed coverage state. */
    private final PackCoverageOwner packCoverageOwner = new PackCoverageOwner();
    private final PackCoverageState packCoverageState = new PackCoverageState();
    private PackCoveragePlan packCoveragePlan = PackCoveragePlan.disabled();
    private boolean coverageGeometryReady;
    /** Immutable target and depth schedule for the active pack variant. */
    private PackTargetGraphPlan packTargetGraph;
    /** Immutable world/post order for the active pack variant. */
    private PackFrameSchedulePlan packFrameSchedule = PackFrameSchedulePlan.empty();
    private boolean packPostChainActive;
    /** Set only when the target graph itself cannot be configured. */
    private boolean packPostChainRejected;
    /** True after the current frame has opened the target executor state. */
    private boolean packPostFrameStarted;
    private boolean packEarlyPostCompleted;
    /** True after the world image has been resolved to the stable GUI target. */
    private boolean packWorldResolved;
    /** True after the final pack pass has run for the current frame. */
    private boolean packFinalApplied;
    private final VulkanImage[] packFinalInputs = new VulkanImage[PostTargetPlan.MAX_TARGET + 1];
    /** Parsed pack constants and programs for the active selection. */
    private PackConfig.PackConfigData packConfig;
    private PackPlan packPlan;
    private List<PackProgram> packPrograms;
    /** Owns temporary source extraction for a ZIP pack until session teardown. */
    private PackSource.LoadResult packSource;
    /** Original pack path used to select another dimension variant in place. */
    private Path packPath;
    /** All pack-owned sampled images, retained for the pack session. */
    private PackResourceOwner packResourceOwner;
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
    private int[] packShadowSlots;
    /** Pack world-entity program on the guarded delayed model batch. */
    private PackPipelines.PackEntity packEntityPipeline;
    /** Pack translucent world-entity program on the guarded delayed model batch. */
    private PackPipelines.PackEntity packTranslucentEntityPipeline;
    /** Pack glowing world-entity program on the guarded delayed model batch. */
    private PackPipelines.PackEntity packGlowingEntityPipeline;
    /** Pack block-entity program on the guarded delayed model batch. */
    private PackPipelines.PackEntity packBlockPipeline;
    /** Pack damaged-block program on the guarded block draw lane. */
    private PackPipelines.PackEntity packDamagedBlockPipeline;
    /** Pack first-person hand program on the guarded hand draw window. */
    private PackPipelines.PackEntity packHandPipeline;
    /** Pack first-person water program on the guarded hand draw window. */
    private PackPipelines.PackEntity packHandWaterPipeline;
    /** Pack particle program on the host particle draw window. */
    private PackPipelines.PackParticle packParticlePipeline;
    /** Pack translucent particle program on the host translucent particle window. */
    private PackPipelines.PackParticle packTranslucentParticlePipeline;
    /** Pack weather program on the host weather draw window. */
    private PackPipelines.PackParticle packWeatherPipeline;
    /** GL-registry slot-5 view of the shadow depth, for pack geometry sampling (shadowtex0). */
    private GpuTexture packShadowTexture;
    private GpuTextureView packShadowView;
    private long packShadowSourceId;

    private ChimeraShadowMap shadowMap = new ChimeraShadowMap();
    private final PackShadowDepth packShadowDepth = new PackShadowDepth();

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

    /** Dimension requested by WorldRenderer.setLevel, applied at a safe frame boundary. */
    private String pendingPackDimension;
    /** Prevents repeated log spam while a pending change waits for an idle boundary. */
    private String loggedPendingPackDimension;
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

    private final PackChangeQueue packChangeQueue = new PackChangeQueue();
    /** Prevents a blocked request from producing per-frame diagnostics. */
    private String loggedPendingPackChange;
    /** Current live dimension, also used when a new pack is selected in-game. */
    private String currentDimension = "minecraft:overworld";

    /**
     * Armed at level-segment HEAD, consumed at the SOLID layer tail
     * (WorldRendererMixin): guarantees exactly one shadow segment per frame.
     */
    private boolean shadowPending;
    /** False when the shadow resource could not be safely prepared this frame. */
    private boolean shadowFrameReady;
    /** Prevents an invalid shadow resource from producing per-frame warnings. */
    private boolean shadowTransitionFallbackLogged;
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
        this(startupPackPath());
    }

    ChimeraMainPass(Path initialPackPath) {
        this.packPath = initialPackPath;
        this.levelPhase = true;
        createResources();
        Renderer.getInstance().addOnResizeCallback(this::onResize);
    }

    private static Path startupPackPath() {
        String value = System.getProperty("chimera.pack");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value.trim());
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] startup pack path is invalid: {}", value);
            return null;
        }
    }

    /**
     * Records the shadow pass: re-renders SOLID terrain from the light's
     * perspective into the shadow framebuffer, then binds the shadow texture
     * for sampling by the terrain shader. Runs with no render pass open;
     * renderSectionLayer's rebindMainTarget opens the shadow pass (via the
     * shadowPassActive redirect) and this method closes it afterward.
     */
    public void renderShadowMap(double cameraX, double cameraY, double cameraZ) {
        if (!this.shadowFrameReady || !shadowAttachmentsLive()) {
            return;
        }
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
        VulkanImage[] previousShadowSlots = null;
        if (this.packShadowPipeline != null) {
            previousShadowSlots = bindPackSamplers("shadow", this.packShadowSlots,
                    null, null, null);
        }
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
            restorePackSamplers(this.packShadowSlots, previousShadowSlots);
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
        if (this.packShadowPipeline != null && this.packShadowDepth.isConfigured()
                && !this.packShadowDepth.capture(cmd, shadowDepth)) {
            LOGGER.warn("[chimera] pack shadow depth conversion failed; using raw shadow depth fallback");
        }
        this.shadowMap.markInitialSamplingLayoutReady();

        // Restore the family-aware terrain getter after the shadow segment.
        PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);

        // Bind shadow texture for terrain fragment shader sampling
        this.shadowMap.bindShadowTexture();
        if (this.packShadowDepth.image() != null) {
            VTextureSelector.bindTexture(5, this.packShadowDepth.image());
        }
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
        if (!this.shadowMap.isInitialized() || !this.shadowFrameReady || !shadowAttachmentsLive()) {
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
    boolean prepareForInstall() {
        if (Renderer.isRecording() || Renderer.getInstance().getBoundRenderPass() != null
                || hasPendingPackChange()) {
            return false;
        }
        ensureOutputResources();
        return this.outputResourcesReady && outputResourcesLive();
    }

    /** Called before Renderer begins a fresh main command buffer. */
    boolean applyPendingPackVariantAtFrameBoundary() {
        if (this.packChangeQueue.pending() != null) {
            return applyPendingPackChange();
        }
        return applyPendingPackVariant();
    }

    boolean hasPendingPackVariant() {
        return this.pendingPackDimension != null && this.packSource != null && this.packPath != null
                && !this.pendingPackDimension.equals(this.packSource.selectedDimension());
    }

    boolean hasPendingPackChange() {
        return this.packChangeQueue.pending() != null || hasPendingPackVariant();
    }

    boolean queuePackChange(Path path, boolean forceReload) {
        if (!this.packChangeQueue.request(this.packPath, path, forceReload)) {
            return false;
        }
        this.loggedPendingPackChange = null;
        LOGGER.info("[chimera] pack change queued: {}{}",
                packLabel(path), forceReload ? " (reload)" : "");
        return true;
    }

    boolean queuePackReload() {
        return queuePackChange(this.packPath, true);
    }

    String packStatus() {
        String active = packLabel(this.packPath);
        PendingPackChange pendingRequest = this.packChangeQueue.pending();
        String pending = pendingRequest == null
                ? (hasPendingPackVariant() ? "dimension " + this.pendingPackDimension : "none")
                : packLabel(pendingRequest.path());
        return "Chimera pack: active=" + active
                + ", dimension=" + this.currentDimension
                + ", pending=" + pending;
    }

    private static String packLabel(Path path) {
        if (path == null) {
            return "identity";
        }
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }


    // ------------------------------------------------------------------
    // Segment control
    // ------------------------------------------------------------------

    public void openLevelSegment() {
        ensureWorldResources();
        Renderer.getInstance().endRenderPass();
        this.currentFramebuffer = this.hdrFramebuffer;
        this.hdrDepthReadable = false;
        this.shadowFrameReady = false;
        this.packPostFrameStarted = false;
        this.packEarlyPostCompleted = false;
        this.packWorldResolved = false;
        this.packFinalApplied = false;
        if (this.packDepthTargets.isConfigured()) {
            this.packDepthTargets.beginFrame();
        }
        if (packCoverageRuntimeEnabled() && this.packPostChainActive) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            this.packPostTargets.beginFrame(commandBuffer, hdrColor);
            this.packPostTargets.prepareGeometryTarget(commandBuffer);
            this.packCoverageOwner.beginFrame(commandBuffer);
            this.packCoverageState.beginFrame();
            this.packPostFrameStarted = true;
        }

        // Compute the light once before any terrain draw. The shadow pass at
        // the opaque-layer tail reuses this exact state.
        if (this.shadowMap.isInitialized()) {
            this.shadowMap.updateLight(PackUniformProvider.currentCelestialAngle());
            this.shadowFrameReady = prepareShadowForSampling();
        }

        // Arm this frame's shadow segment; consumed at the SOLID layer tail.
        this.shadowPending = this.shadowFrameReady;

        // Terrain shaders sample the shadow map from their first draw.
        if (this.shadowFrameReady) {
            this.shadowMap.bindShadowTexture();
            maintainPackShadowGoal();
        }

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

    /** Resolves the world into the stable output before hand and GUI work. */
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
            if (this.packNeedsHdrDepth) {
                transitionHdrDepthForSampling(stack, commandBuffer);
                if (this.packDepthTargets.isConfigured()
                        && !this.packDepthTargets.captureScene(
                        commandBuffer, this.hdrFramebuffer.getDepthAttachment())) {
                    LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (depth conversion failed)");
                }
            }

            if (packCoverageRuntimeEnabled()) {
                seedPackScene(commandBuffer, hdrColor);
            }

            if (this.packPostChainActive && this.packPostTargets.isConfigured()) {
                if (!this.packEarlyPostCompleted
                        && this.packFrameSchedule.hasWindow(PackFrameSchedulePlan.PostWindow.EARLY)) {
                    runPackPostWindow(commandBuffer, hdrColor,
                            PackFrameSchedulePlan.PostWindow.EARLY);
                    this.packEarlyPostCompleted = true;
                }
                runPackPostWindow(commandBuffer, hdrColor, PackFrameSchedulePlan.PostWindow.LATE);
                if (this.packPostFrameStarted) {
                    this.packPostTargets.commitFrame();
                    this.packPostFrameStarted = false;
                }
                resolvePackWorldToOutput(commandBuffer, hdrColor);
                this.packWorldResolved = true;
                this.currentFramebuffer = this.compositeFramebuffer;
                if (packCoverageRuntimeEnabled()) {
                    this.packCoverageState.endFrame();
                }
                return;
            }

            resolveWorldToOutput(commandBuffer, hdrColor,
                    this.packCompositePipeline, this.packCompositeSlots,
                    this.packCompositeSamplerNames, "composite");
            this.currentFramebuffer = this.compositeFramebuffer;
            if (packCoverageRuntimeEnabled()) {
                this.packCoverageState.endFrame();
            }
        }
    }

    /** Opens the two-attachment geometry target only for installed pack terrain. */
    public void beginPackCoverageWindow(TerrainRenderType renderType) {
        if (!packCoverageRuntimeEnabled() || !this.packPostChainActive
                || this.shadowPassActive
                || this.hdrFramebuffer == null || this.packCoverageOwner.image() == null
                || (renderType != TerrainRenderType.SOLID
                    && renderType != TerrainRenderType.CUTOUT)) {
            return;
        }
        if (PackGeometryContext.active()) {
            return;
        }
        Renderer.getInstance().endRenderPass();
        PackGeometryContext.begin(
                this.packPostTargets.geometryTarget0(),
                this.packCoverageOwner.image(),
                this.hdrFramebuffer.getDepthAttachment());
        this.packCoverageState.beginPackWrite();
    }

    /**
     * Static family inventory is not enough to activate the scene-seed path.
     * The coverage target is safe to use only after the pack terrain adapter
     * was built and the session-owned seed resources are live.
     */
    private boolean packCoverageRuntimeEnabled() {
        return this.packCoveragePlan.enabled()
                && this.coverageGeometryReady
                && this.packCoverageOwner.isLive()
                && this.sceneSeedPipeline != null;
    }

    /** Closes the coverage geometry window and reopens the normal HDR target. */
    public void endPackCoverageWindow(TerrainRenderType renderType) {
        if (!PackGeometryContext.active()) {
            return;
        }
        Renderer.getInstance().endRenderPass();
        this.packCoverageState.commitPackWrite();
        this.currentFramebuffer = this.hdrFramebuffer;
        this.rebindMainTarget();
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
        if (this.packWorldResolved && !this.packFinalApplied) {
            finishPackFinalAfterHand();
        }
        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage outputColor = this.compositeFramebuffer.getColorAttachment();
            trace("presentRead", "outputColor", outputColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            outputColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (this.packNeedsHdrDepth) {
                transitionHdrDepthForSampling(stack, commandBuffer);
            }
            boolean packFinalAlreadyApplied = this.packFinalApplied;
            VulkanImage[] prevPackSlots = null;
            if (!packFinalAlreadyApplied && this.packFinalPipeline != null) {
                prevPackSlots = bindPackSamplers("final", this.packFinalSlots,
                        this.packFinalSamplerNames, null, outputColor);
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
                    GraphicsPipeline presentPipeline = !packFinalAlreadyApplied && this.packFinalPipeline != null
                            ? this.packFinalPipeline : this.presentPipeline;
                    drawFullscreen(commandBuffer, presentPipeline);
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
                    if (this.packFinalPipeline != null || this.packFinalPost != null) {
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

    /** Runs one scheduled non-final post window through the pack target graph. */
    private void runPackPostWindow(
            VkCommandBuffer commandBuffer,
            VulkanImage hdrColor,
            PackFrameSchedulePlan.PostWindow window
    ) {
        if (!this.packPostChainActive || !this.packPostTargets.isConfigured()) {
            return;
        }
        boolean depthTest = VRenderSystem.depthTest;
        boolean depthMask = VRenderSystem.depthMask;
        int colorMask = VRenderSystem.getColorMask();
        boolean blendEnabled = PipelineState.blendInfo.enabled;
        boolean cullEnabled = VRenderSystem.cull;
        try {
            if (!this.packPostFrameStarted) {
                this.packPostTargets.beginFrame(commandBuffer, hdrColor);
                this.packPostFrameStarted = true;
            }
            VRenderSystem.disableDepthTest();
            VRenderSystem.depthMask(false);
            VRenderSystem.colorMask(true, true, true, true);
            VRenderSystem.disableBlend();
            VRenderSystem.disableCull();
            for (PackPostExecution stage : this.packPostExecution) {
                PackFrameSchedulePlan.PostStage scheduled = this.packFrameSchedule.postStage(stage.name());
                if (stage.finalStage() || scheduled == null || scheduled.window() != window) {
                    continue;
                }
                PackPipelines.PackPost post = stage.post();
                if (post == null) {
                    markPostStageIdentityBoundary(stage, "not installed");
                    continue;
                }
                TargetStep graphStep = postGraphStep(stage);
                if (graphStep == null || !graphStep.executable()) {
                    markPostGraphIdentityBoundary(stage, graphStep);
                    continue;
                }
                if (!this.packPostTargets.areInputsAvailable(post.requiredColorInputs())
                        || !packDepthInputsAvailable(post.samplerNames())) {
                    markPostStageIdentityBoundary(stage,
                            "unavailable inputs " + post.requiredColorInputs()
                                    + " depth=" + post.samplerNames());
                    continue;
                }
                VulkanImage[] previous = null;
                boolean attempted = false;
                try {
                    previous = bindPackSamplers(
                            post.name(),
                            post.samplerSlots(), post.samplerNames(),
                            this.packPostTargets.sourceImages(), hdrColor);
                    attempted = true;
                    this.packPostTargets.prepare(post, commandBuffer);
                    drawFullscreen(commandBuffer, post.pipeline());
                    this.packPostTargets.finish(commandBuffer);
                } catch (RuntimeException stageFailure) {
                    if (attempted) {
                        this.packPostTargets.abort(commandBuffer);
                    }
                    markPostStageFailure(post, stageFailure);
                    throw stageFailure;
                } finally {
                    restorePackSamplers(post.samplerSlots(), previous);
                }
            }
        } catch (RuntimeException e) {
            try {
                if (this.packPostFrameStarted) {
                    this.packPostTargets.abort(commandBuffer);
                    this.packPostFrameStarted = false;
                }
            } catch (RuntimeException abortFailure) {
                LOGGER.warn("[chimera] pack post chain abort failed: {}", abortFailure.getMessage());
            }
            disablePackPostChain("POST_RUNTIME_FAILED");
            LOGGER.warn("[chimera] pack post chain: fallback=IDENTITY (runtime failure: {})",
                    e.getMessage());
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
        if (this.conformanceReport != null) {
            this.conformanceReport.addDeviation("PING_PONG_TARGETS_APPLIED");
            this.conformanceReport.addDeviation("UNWRITTEN_TARGET_PRESERVED");
        }
    }

    /** Resolves the current pack world target to the stable output target. */
    private void resolvePackWorldToOutput(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        VulkanImage resolved = this.packPostTargets.activeTarget(0);
        resolveWorldToOutput(commandBuffer, resolved == null ? hdrColor : resolved,
                null, null, List.of(), "identity");
    }

    /** Host resolve used when the pack target graph is not active. */
    private void resolveWorldToOutput(
            VkCommandBuffer commandBuffer,
            VulkanImage source,
            GraphicsPipeline packPipeline,
            int[] packSlots,
            List<String> samplerNames,
            String programName
    ) {
        GraphicsPipeline resolvePipeline = packPipeline != null ? packPipeline : this.compositePipeline;
        boolean depthTest = VRenderSystem.depthTest;
        boolean depthMask = VRenderSystem.depthMask;
        int colorMask = VRenderSystem.getColorMask();
        boolean blendEnabled = PipelineState.blendInfo.enabled;
        boolean cullEnabled = VRenderSystem.cull;
        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.colorMask(true, true, true, true);
        VRenderSystem.disableBlend();
        VRenderSystem.disableCull();
        VulkanImage[] previous = null;
        try {
            Renderer.getInstance().beginRenderPass(this.compositeRenderPass, this.compositeFramebuffer);
            if (packPipeline != null) {
                previous = bindPackSamplers(programName, packSlots, samplerNames, null, source);
            } else {
                VTextureSelector.bindTexture(source);
            }
            drawFullscreen(commandBuffer, resolvePipeline);
        } finally {
            if (previous != null) {
                restorePackSamplers(packSlots, previous);
            }
            VRenderSystem.depthTest = depthTest;
            VRenderSystem.depthMask = depthMask;
            VRenderSystem.colorMask((colorMask & 1) != 0, (colorMask & 2) != 0,
                    (colorMask & 4) != 0, (colorMask & 8) != 0);
            if (blendEnabled) VRenderSystem.enableBlend(); else VRenderSystem.disableBlend();
            VRenderSystem.cull = cullEnabled;
        }
    }

    /** Captures the pre-hand depth seam before the host hand draw begins. */
    public void beginHandSegment() {
        if (!this.packWorldResolved || !this.packDepthTargets.isConfigured()
                || !this.packDepthTargets.plan().depthtex2()) {
            return;
        }
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().endRenderPass(commandBuffer);
        }
        this.packDepthTargets.capturePreHand(commandBuffer,
                this.hdrFramebuffer == null ? null : this.hdrFramebuffer.getDepthAttachment());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // The world resolve is already in the output image. The hand
            // seam must load it; the clear variant would erase the world
            // whenever a pack requests the pre-hand depth snapshot.
            Renderer.getInstance().beginRenderPass(this.compositeAuxRenderPass,
                    this.compositeFramebuffer);
            Renderer.setViewport(0, 0, this.compositeFramebuffer.getWidth(),
                    this.compositeFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.compositeFramebuffer.scissor(stack));
        }
    }

    /** Runs final after hand submission and before GUI composition. */
    public void finishPackFinalAfterHand() {
        if (!this.packWorldResolved || this.packFinalApplied) {
            return;
        }
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if (this.packDepthTargets.isConfigured()) {
            this.packDepthTargets.commitFrame();
        }
        if (this.packFinalPost == null || !this.packPostChainActive) {
            this.packFinalApplied = true;
            return;
        }
        TargetStep finalGraphStep = postGraphStep(this.packFinalExecution);
        boolean finalGraphExecutable = finalGraphStep != null && finalGraphStep.executable();
        boolean finalInputsAvailable = finalGraphExecutable
                && this.packPostTargets.areInputsAvailable(this.packFinalPost.requiredColorInputs())
                && packDepthInputsAvailable(this.packFinalPost.samplerNames());
        if (!finalInputsAvailable) {
            if (this.packFinalExecution != null) {
                if (!finalGraphExecutable) {
                    markPostGraphIdentityBoundary(this.packFinalExecution, finalGraphStep);
                } else {
                    markPostStageIdentityBoundary(this.packFinalExecution,
                            "unavailable inputs " + this.packFinalPost.requiredColorInputs()
                                    + " depth=" + this.packFinalPost.samplerNames());
                }
            }
            this.packFinalApplied = true;
            return;
        }

        boolean finalPassOpen = false;
        try {
            if (Renderer.getInstance().getBoundRenderPass() != null) {
                Renderer.getInstance().endRenderPass(commandBuffer);
            }
            Renderer.getInstance().beginRenderPass(this.compositeRenderPass, this.compositeFramebuffer);
            finalPassOpen = true;
            Arrays.fill(this.packFinalInputs, null);
            for (int target = 0; target < this.packFinalInputs.length; target++) {
                this.packFinalInputs[target] = this.packPostTargets.activeTarget(target);
            }
            VulkanImage resolved = this.packPostTargets.activeTarget(0);
            VulkanImage[] previous = bindPackSamplers(this.packFinalPost.name(),
                    this.packFinalPost.samplerSlots(), this.packFinalPost.samplerNames(),
                    this.packFinalInputs, resolved);
            try {
                drawFullscreen(commandBuffer, this.packFinalPost.pipeline());
            } finally {
                restorePackSamplers(this.packFinalPost.samplerSlots(), previous);
            }
            this.packFinalApplied = true;
        } catch (RuntimeException failure) {
            markPostStageFailure(this.packFinalPost, failure);
            if (finalPassOpen) {
                Renderer.getInstance().endRenderPass(commandBuffer);
            }
            renderIdentityResolve(commandBuffer,
                    this.hdrFramebuffer == null ? null : this.hdrFramebuffer.getColorAttachment());
            this.packFinalApplied = true;
            LOGGER.warn("[chimera] pack final: fallback=IDENTITY (runtime failure: {})",
                    failure.getMessage());
        }
    }

    private TargetStep postGraphStep(PackPostExecution stage) {
        if (stage == null || this.packPostTargets.graph() == null) {
            return null;
        }
        return this.packPostTargets.graph().step(stage.name());
    }

    /** Converts a static graph rejection into a per-stage identity boundary. */
    private void markPostGraphIdentityBoundary(PackPostExecution stage, TargetStep step) {
        if (this.packPostBoundaryLogged.contains(stage.name())) {
            if (!stage.finalStage()) {
                this.packPostTargets.invalidateOutputs(step == null
                        ? stage.outputTargets() : step.outputTargets());
            }
            return;
        }
        String detail = step == null
                ? "graph step unavailable"
                : "graph step rejected " + step.deviations();
        if (step != null && this.conformanceReport != null) {
            String code = graphRejectionCode(stage.name(), step);
            this.conformanceReport.addDeviation(code);
            this.conformanceReport.markRuntime(stage.name(),
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, code);
        }
        markPostStageIdentityBoundary(
                stage.name(),
                step == null ? stage.outputTargets() : step.outputTargets(),
                stage.finalStage(),
                detail);
    }

    private static String graphRejectionCode(String name, TargetStep step) {
        for (String deviation : step.deviations()) {
            if (deviation.startsWith("POST_TARGET_MIPMAP_UNSUPPORTED:")
                    || deviation.startsWith("POST_TARGET_ATTACHMENT_LIMIT:")
                    || deviation.startsWith("POST_TARGET_SIZE_CONFLICT:")
                    || deviation.startsWith("POST_TARGET_FORMAT_DEVICE_UNSUPPORTED:")
                    || deviation.startsWith("POST_TARGET_FEEDBACK_UNSUPPORTED:")
                    || deviation.equals("FINAL_MRT_UNSUPPORTED")) {
                return deviation;
            }
        }
        return "POST_STAGE_GRAPH_REJECTED:" + name;
    }

    /** Restores the stable host resolve after a pack post pass fails. */
    private void renderIdentityResolve(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.colorMask(true, true, true, true);
        VRenderSystem.disableBlend();
        VRenderSystem.disableCull();
        Renderer.getInstance().beginRenderPass(this.compositeRenderPass, this.compositeFramebuffer);
        VTextureSelector.bindTexture(hdrColor);
        drawFullscreen(commandBuffer, this.compositePipeline);
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.enableCull();
        this.currentFramebuffer = this.compositeFramebuffer;
    }

    private void markPostStageFailure(PackPipelines.PackPost post, RuntimeException failure) {
        markPostStageIdentityBoundary(post,
                "runtime failure " + String.valueOf(failure.getMessage()));
        if (this.conformanceReport != null) {
            this.conformanceReport.markRuntime(post.name(),
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                    "POST_RUNTIME_STAGE_FAILED:" + post.name());
        }
    }

    private void markPostStageIdentityBoundary(PackPipelines.PackPost post, String detail) {
        PostTargetPlan plan = post.targetPlan();
        markPostStageIdentityBoundary(
                post.name(),
                plan == null ? List.of() : plan.targetSlots(),
                plan != null && plan.isFinal(),
                detail);
    }

    private void markPostStageIdentityBoundary(PackPostExecution stage, String detail) {
        markPostStageIdentityBoundary(stage.name(), stage.outputTargets(), stage.finalStage(), detail);
    }

    private void markPostStageIdentityBoundary(
            String name,
            List<Integer> outputTargets,
            boolean finalStage,
            String detail
    ) {
        if (!finalStage) {
            this.packPostTargets.invalidateOutputs(outputTargets);
        }
        if (!this.packPostBoundaryLogged.add(name)) {
            return;
        }
        String boundary = "POST_STAGE_IDENTITY_BOUNDARY:" + name;
        if (this.conformanceReport != null) {
            this.conformanceReport.addDeviation(boundary);
            this.conformanceReport.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                    boundary);
        }
        LOGGER.warn("[chimera] pack {}: fallback=IDENTITY (outputs={}, {})",
                name, outputTargets, detail);
    }

    /** Disables all pack post seams while retaining objects for safe teardown. */
    private void disablePackPostChain(String reason) {
        this.packPostChainActive = false;
        this.packCompositePipeline = null;
        this.packCompositeSlots = null;
        this.packCompositeSamplerNames = List.of();
        this.packFinalPipeline = null;
        this.packFinalSlots = null;
        this.packFinalSamplerNames = List.of();
        this.packFinalPost = null;
        this.packPostChainRejected = true;
        if (this.conformanceReport != null) {
            this.conformanceReport.addDeviation(reason);
            for (PackPipelines.PackPost post : this.packPostStages) {
                ConformanceReport.ProgramReport current = this.conformanceReport.program(post.name());
                if (current == null
                        || current.runtime() != ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK) {
                    this.conformanceReport.markRuntime(post.name(),
                            ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, reason);
                }
            }
            LOGGER.info("[chimera] conformance {}", this.conformanceReport.toJson());
            logConformanceSummary();
        }
    }

    private void restorePackSamplers(int[] slots, VulkanImage[] previous) {
        if (slots == null || previous == null) {
            return;
        }
        for (int i = 0; i < slots.length; i++) {
            VTextureSelector.bindTexture(slots[i], previous[i]);
        }
    }

    /**
     * Binds the textures for a pack post program's declared slots. colortexN
     * slots read the current post source (HDR world for the legacy composite
     * path, active pack targets for M5.6, or stable output for the legacy
     * final path); shadowtex0 reads the shadow map; depthtex0 reads the HDR
     * depth attachment. Every declared slot must
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
        return bindPackSamplers("", slots, null, null, colortexImage);
    }

    private VulkanImage[] bindPackSamplers(
            String programName,
            int[] slots,
            List<String> samplerNames,
            VulkanImage[] colorInputs,
            VulkanImage fallbackColortex
    ) {
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
            String samplerName = samplerNames != null && i < samplerNames.size()
                    ? samplerNames.get(i) : "";
            PackResourceBinding resourceBinding = this.packResourceOwner == null
                    ? null : this.packResourceOwner.binding(programName, samplerName);
            if (resourceBinding != null
                    && (resourceBinding.status() == PackResourceStatus.PACK_FILE
                    || resourceBinding.status() == PackResourceStatus.GAME_RESOURCE)) {
                VulkanImage resourceImage = this.packResourceOwner.image(resourceBinding.resourceKey());
                if (resourceImage != null) {
                    VTextureSelector.bindTexture(slot, resourceImage);
                }
                continue;
            }
            if (samplerName.equals("shadowcolor0") || samplerName.equals("shadowcolor1")) {
                if (this.shadowMap.getShadowFramebuffer() != null) {
                    VTextureSelector.bindTexture(3,
                            this.shadowMap.getShadowFramebuffer().getColorAttachment());
                }
            } else if (slot >= 0 && slot <= 3) {
                VulkanImage colorInput = colorInputs != null
                        ? colorInputs[slot] : fallbackColortex;
                if (colorInput != null) {
                    VTextureSelector.bindTexture(slot, colorInput);
                }
            } else if (slot >= 8 && slot <= 11) {
                Integer logicalTarget = PackResourcePlan.targetIndex(samplerName);
                if (logicalTarget != null && logicalTarget >= 4
                        && colorInputs != null && logicalTarget < colorInputs.length) {
                    VulkanImage colorInput = colorInputs[logicalTarget];
                    if (colorInput != null) {
                        VTextureSelector.bindTexture(slot, colorInput);
                    }
                }
            } else if (slot == 5) {
                VulkanImage shadowDepth = this.packShadowDepth.image();
                if (shadowDepth == null && this.shadowMap.getShadowFramebuffer() != null) {
                    shadowDepth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
                }
                if (shadowDepth != null) {
                    VTextureSelector.bindTexture(5, shadowDepth);
                }
            } else if (slot == 6) {
                VulkanImage depth = this.packDepthTargets.image("depthtex0");
                if (depth == null && this.hdrFramebuffer != null) {
                    depth = this.hdrFramebuffer.getDepthAttachment();
                }
                if (depth != null) {
                    VTextureSelector.bindTexture(6, depth);
                }
            } else if (slot == 12 || slot == 13) {
                VulkanImage depth = this.packDepthTargets.image(samplerName);
                if (depth != null) {
                    VTextureSelector.bindTexture(slot, depth);
                }
            } else if (slot == 7 && this.packResourceOwner != null) {
                VulkanImage noise = this.packResourceOwner.image("noisetex");
                if (noise != null) {
                    VTextureSelector.bindTexture(7, noise);
                }
            }
        }
        return previous;
    }

    private boolean packDepthInputsAvailable(List<String> samplerNames) {
        if (samplerNames == null) return true;
        for (String sampler : samplerNames) {
            if (sampler != null && sampler.startsWith("depthtex")
                    && this.packDepthTargets.image(sampler) == null) {
                return false;
            }
        }
        return true;
    }

    /** Captures opaque depth at the boundary immediately before translucency. */
    public void captureOpaqueDepthBeforeTranslucent() {
        if (!this.levelPhase || this.hdrFramebuffer == null) {
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
                    commandBuffer, this.hdrFramebuffer.getDepthAttachment());
            if (!captured) {
                LOGGER.warn("[chimera] pack depth graph: depthtex1 capture unavailable");
            }
        }
        if (runEarlyPost) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
                hdrColor.transitionImageLayout(stack, commandBuffer,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                runPackPostWindow(commandBuffer, hdrColor,
                        PackFrameSchedulePlan.PostWindow.EARLY);
                this.packEarlyPostCompleted = true;
                hdrColor.transitionImageLayout(stack, commandBuffer,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Renderer.getInstance().beginRenderPass(this.hdrAuxRenderPass, this.hdrFramebuffer);
            Renderer.setViewport(0, 0, this.hdrFramebuffer.getWidth(),
                    this.hdrFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.hdrFramebuffer.scissor(stack));
        }
        this.hdrDepthReadable = false;
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
        stopRenderWorkForCleanup();
        waitForImmediateDestruction("final pack cleanup");
        cleanUpPackVariant();
        cleanUpFramebuffersAndPasses();
        cleanUpPipelines();
        if (this.packSource != null) this.packSource.close();
        this.packSource = null;
        this.packPath = null;
        this.packChangeQueue.clear();
        this.loggedPendingPackChange = null;
        this.pendingPackDimension = null;
        this.loggedPendingPackDimension = null;
        this.conformanceReport = null;
        this.mainFamilyViews.clear();
    }

    /** Ends only active Chimera work before final Vulkan object destruction. */
    private void stopRenderWorkForCleanup() {
        if (this.packPostTargets.isRendering()) {
            try {
                this.packPostTargets.abort(Renderer.getCommandBuffer());
            } catch (RuntimeException failure) {
                LOGGER.warn("[chimera] pack post cleanup abort failed: {}", failure.getMessage());
            }
        }
        if (Renderer.isRecording()) {
            Renderer.getInstance().endRenderPass();
        }
        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().setBoundRenderPass(null);
            Renderer.getInstance().setBoundFramebuffer(null);
        }
    }

    /** Releases only pack-owned state so a dimension variant can be rebuilt. */
    private void cleanUpPackVariant() {
        ChimeraEntityBridge.setEnabled(false);
        // Release the dedicated entity source before its pipeline is destroyed.
        // This keeps any remaining host-side batch cleanup away from a dead
        // pack pipeline and leaves the host format/cache untouched.
        ChimeraEntityBridge.disable();
        VTextureSelector.bindTexture(7, null);
        for (int slot = 8; slot <= PackResourcePlan.PACK_SLOT_LAST; slot++) {
            VTextureSelector.bindTexture(slot, null);
        }
        releasePackShadowView();
        this.shadowMap.cleanUp();
        this.packShadowDepth.cleanUp();
        this.packPostTargets.cleanUp();
        this.packDepthTargets.cleanUp();
        this.packCoverageOwner.close();
        this.packCoverageState.endFrame();
        this.packCoveragePlan = PackCoveragePlan.disabled();
        this.coverageGeometryReady = false;
        Set<GraphicsPipeline> postPipelines =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (PackPipelines.PackPost post : this.packPostStages) {
            postPipelines.add(post.pipeline());
        }
        if (this.packCompositePipeline != null) postPipelines.add(this.packCompositePipeline);
        if (this.packFinalPipeline != null) postPipelines.add(this.packFinalPipeline);
        for (GraphicsPipeline pipeline : postPipelines) {
            pipeline.cleanUp();
        }
        if (this.packGeometryPipeline != null) this.packGeometryPipeline.cleanUp();
        if (this.packTranslucentPipeline != null) this.packTranslucentPipeline.cleanUp();
        if (this.packShadowPipeline != null) this.packShadowPipeline.cleanUp();
        if (this.packEntityPipeline != null) this.packEntityPipeline.pipeline().cleanUp();
        if (this.packTranslucentEntityPipeline != null) this.packTranslucentEntityPipeline.pipeline().cleanUp();
        if (this.packGlowingEntityPipeline != null) this.packGlowingEntityPipeline.pipeline().cleanUp();
        if (this.packBlockPipeline != null) this.packBlockPipeline.pipeline().cleanUp();
        if (this.packDamagedBlockPipeline != null) this.packDamagedBlockPipeline.pipeline().cleanUp();
        if (this.packHandPipeline != null) this.packHandPipeline.pipeline().cleanUp();
        if (this.packHandWaterPipeline != null) this.packHandWaterPipeline.pipeline().cleanUp();
        if (this.packParticlePipeline != null) this.packParticlePipeline.pipeline().cleanUp();
        if (this.packTranslucentParticlePipeline != null) this.packTranslucentParticlePipeline.pipeline().cleanUp();
        if (this.packWeatherPipeline != null) this.packWeatherPipeline.pipeline().cleanUp();
        if (this.packResourceOwner != null) this.packResourceOwner.close();
        this.packResourceOwner = null;
        this.packPostStages.clear();
        this.packPostExecution.clear();
        this.packPostBoundaryLogged.clear();
        this.packCompositePipeline = null;
        this.packCompositeSlots = null;
        this.packCompositeSamplerNames = List.of();
        this.packFinalPipeline = null;
        this.packFinalSlots = null;
        this.packFinalSamplerNames = List.of();
        this.packFinalPost = null;
        this.packFinalExecution = null;
        this.packGeometryPipeline = null;
        this.packGeometrySlots = null;
        this.packTranslucentPipeline = null;
        this.packTranslucentSlots = null;
        this.packShadowPipeline = null;
        this.packShadowSlots = null;
        this.packEntityPipeline = null;
        this.packTranslucentEntityPipeline = null;
        this.packGlowingEntityPipeline = null;
        this.coverageGeometryReady = false;
        this.packBlockPipeline = null;
        this.packDamagedBlockPipeline = null;
        this.packHandPipeline = null;
        this.packHandWaterPipeline = null;
        this.packParticlePipeline = null;
        this.packTranslucentParticlePipeline = null;
        this.packWeatherPipeline = null;
        this.shadowCutoutDispositionLogged = false;
        this.shadowFrameReady = false;
        this.shadowTransitionFallbackLogged = false;
        this.packPipelinesLoaded = false;
        this.packPostChainActive = false;
        this.packPostChainRejected = false;
        this.packPostFrameStarted = false;
        this.packEarlyPostCompleted = false;
        this.packWorldResolved = false;
        this.packFinalApplied = false;
        this.packConfig = null;
        this.packPlan = null;
        this.packTargetGraph = null;
        this.packFrameSchedule = PackFrameSchedulePlan.empty();
        this.packPrograms = null;
        this.packHdrFormat = 97;
        this.packNeedsHdrDepth = false;
        this.hdrDepthReadable = false;
        ChimeraTerrainPipelines.setGeometryOverride(null);
        ChimeraTerrainPipelines.setTranslucentOverride(null);
        ChimeraTerrainPipelines.setMaterialPlan(TerrainMaterialPlan.legacy());
        PackUniformProvider.resetSession();
    }

    /** Records a dimension change for application at the next safe frame boundary. */
    public void onLevelChanged(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return;
        }
        this.currentDimension = dimension;
        if (this.packSource == null || this.packPath == null) {
            return;
        }
        if (dimension.equals(this.packSource.selectedDimension())) {
            this.pendingPackDimension = null;
            this.loggedPendingPackDimension = null;
            return;
        }
        this.pendingPackDimension = dimension;
        if (!dimension.equals(this.loggedPendingPackDimension)) {
            this.loggedPendingPackDimension = dimension;
            LOGGER.info("[chimera] pack dimension variant pending: {} ({})",
                    dimension, "PACK_VARIANT_REBUILD_DEFERRED");
        }
    }

    /** Queues a complete pack replacement for the next safe command boundary. */
    private boolean applyPendingPackChange() {
        PendingPackChange request = this.packChangeQueue.pending();
        if (request == null) {
            return true;
        }
        if (rebuildBlocked()) {
            String label = packLabel(request.path());
            if (!label.equals(this.loggedPendingPackChange)) {
                this.loggedPendingPackChange = label;
                LOGGER.warn("[chimera] pack change still deferred: {}", label);
            }
            return false;
        }

        Path previousPath = this.packPath;
        String previousDimension = this.currentDimension;
        this.packChangeQueue.clear();
        this.loggedPendingPackChange = null;
        LOGGER.info("[chimera] pack change safe boundary: {}", packLabel(request.path()));
        try {
            preparePackSessionReplacement(true);
            this.packPath = request.path();
            this.currentDimension = previousDimension;
            createResources();
            if (request.path() != null && packSourceLoadFailed()) {
                throw new IllegalStateException("shaderpack source could not be loaded");
            }
            this.pendingPackDimension = null;
            this.loggedPendingPackDimension = null;
            LOGGER.info("[chimera] pack change installed: {}", packLabel(this.packPath));
            return true;
        } catch (RuntimeException rebuildFailure) {
            LOGGER.warn("[chimera] pack change failed: {}, restoring {}: {}",
                    packLabel(request.path()), packLabel(previousPath), rebuildFailure.getMessage());
            try {
                preparePackSessionReplacement(false);
                this.packPath = previousPath;
                this.currentDimension = previousDimension;
                createResources();
                this.pendingPackDimension = null;
                this.loggedPendingPackDimension = null;
                LOGGER.info("[chimera] pack change rolled back: {}", packLabel(previousPath));
                return true;
            } catch (RuntimeException restoreFailure) {
                LOGGER.warn("[chimera] pack rollback failed; host seams remain active: {}",
                        restoreFailure.getMessage());
                this.pendingPackDimension = null;
                this.loggedPendingPackDimension = null;
                markPackVariantFallback();
                ChimeraRenderer.fallbackToHostRenderer();
                return false;
            }
        }
    }

    /** Clears all pack-owned objects and closes the current source after the idle wait. */
    private void preparePackSessionReplacement(boolean waitForIdle) {
        clearPackOverrides();
        this.levelPhase = false;
        this.pendingDepthClear = false;
        this.shadowPending = false;
        if (waitForIdle) {
            waitForImmediateDestruction("pack replacement");
        }
        cleanUpPackVariant();
        cleanUpFramebuffersAndPasses();
        cleanUpPipelines();
        if (this.packSource != null) {
            this.packSource.close();
            this.packSource = null;
        }
    }

    private boolean rebuildBlocked() {
        return Renderer.isRecording() || this.shadowPassActive || this.packPostTargets.isRendering()
                || Renderer.getInstance().getBoundRenderPass() != null;
    }

    private boolean packSourceLoadFailed() {
        if (this.packSource == null) {
            return true;
        }
        return this.packSource.deviations().stream().anyMatch(deviation ->
                deviation.equals("PACK_PATH_INVALID")
                        || deviation.equals("NO_SHADERS_DIRECTORY")
                        || deviation.equals("PACK_SHADERS_NOT_FOUND")
                        || deviation.equals("PACK_ARCHIVE_INVALID")
                        || deviation.startsWith("PACK_ARCHIVE_"));
    }

    /**
     * Applies a recorded level variant only when no Chimera-owned render work
     * is open. The GPU idle wait is deliberately at this single boundary so
     * immediate Vulkan object destruction cannot race an older frame.
     */
    private boolean applyPendingPackVariant() {
        String requested = this.pendingPackDimension;
        if (requested == null || this.packSource == null || this.packPath == null) {
            return true;
        }
        if (requested.equals(this.packSource.selectedDimension())) {
            this.pendingPackDimension = null;
            this.loggedPendingPackDimension = null;
            return true;
        }
        if (rebuildBlocked()) {
            if (!requested.equals(this.loggedPendingPackDimension)) {
                this.loggedPendingPackDimension = requested;
                LOGGER.warn("[chimera] pack dimension variant still deferred: {}",
                        requested);
            }
            return false;
        }

        String previous = this.packSource.selectedDimension();
        LOGGER.info("[chimera] pack dimension variant safe boundary: {}", requested);
        try {
            clearPackOverrides();
            this.levelPhase = false;
            this.pendingDepthClear = false;
            this.shadowPending = false;
            waitForImmediateDestruction("dimension variant " + requested);
            cleanUpPackVariant();
            this.packSource.selectDimension(requested);
            createResources();
            this.pendingPackDimension = null;
            this.loggedPendingPackDimension = null;
            LOGGER.info("[chimera] pack dimension variant installed: {} ({})",
                    requested, this.packSource.selectedVariantFolder().isBlank()
                            ? "root" : this.packSource.selectedVariantFolder());
            return true;
        } catch (RuntimeException rebuildFailure) {
            LOGGER.warn("[chimera] pack dimension variant failed: {}, restoring {}: {}",
                    requested, previous, rebuildFailure.getMessage());
            try {
                clearPackOverrides();
                cleanUpPackVariant();
                cleanUpFramebuffersAndPasses();
                cleanUpPipelines();
                this.packSource.selectDimension(previous);
                createResources();
                this.pendingPackDimension = null;
                this.loggedPendingPackDimension = null;
                if (this.conformanceReport != null) {
                    this.conformanceReport.addDeviation("PACK_VARIANT_REBUILD_FAILED");
                    LOGGER.info("[chimera] conformance {}", this.conformanceReport.toJson());
                }
                LOGGER.info("[chimera] pack dimension variant restored: {}", previous);
                return true;
            } catch (RuntimeException restoreFailure) {
                LOGGER.warn("[chimera] pack dimension restore failed; host seams remain active: {}",
                        restoreFailure.getMessage());
                this.pendingPackDimension = null;
                this.loggedPendingPackDimension = null;
                markPackVariantFallback();
                ChimeraRenderer.fallbackToHostRenderer();
                return false;
            }
        }
    }

    private void clearPackOverrides() {
        ChimeraTerrainPipelines.setGeometryOverride(null);
        ChimeraTerrainPipelines.setTranslucentOverride(null);
        ChimeraEntityBridge.setEnabled(false);
    }

    private void waitForImmediateDestruction(String reason) {
        LOGGER.info("[chimera] pack GPU idle boundary: {} ({})", reason, "PACK_VARIANT_GPU_IDLE");
        Vulkan.waitIdle();
    }

    private void markPackVariantFallback() {
        if (this.conformanceReport == null) {
            return;
        }
        this.conformanceReport.addDeviation("PACK_VARIANT_REBUILD_FAILED");
        for (ConformanceReport.ProgramReport program : this.conformanceReport.programs()) {
            if (program.runtime() == ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED
                    || program.runtime() == ConformanceReport.RuntimeDisposition.INSTALLED) {
                this.conformanceReport.markRuntime(program.name(),
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "PACK_VARIANT_REBUILD_FAILED");
            }
        }
        LOGGER.info("[chimera] conformance {}", this.conformanceReport.toJson());
    }

    @Override
    public void onResize() {
        createResources();
    }

    private void createResources() {
        this.packPostChainActive = false;
        this.packPostTargets.cleanUp();
        this.packDepthTargets.cleanUp();
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
        this.shadowMap.init(shadowSettings);
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
        configurePackPostTargets(width, height);
        loadPackPipelines();
        if (this.packShadowPipeline != null && this.shadowMap.isInitialized()) {
            this.packShadowDepth.configure(this.shadowMap.getShadowMapSize());
        }
    }

    private void configurePackPostTargets(int width, int height) {
        if (this.packPostChainRejected) {
            return;
        }
        try {
            if (this.packPlan == null) {
                return;
            }
            int maxAttachments = deviceMaxColorAttachments();
            this.packTargetGraph = PackTargetGraphPlan.build(
                    this.packPlan.programs(), this.packConfig, this.packPlan.resources(), width, height,
                    maxAttachments, deviceMaxImageDimension());
            this.packCoveragePlan = PackCoveragePlan.from(
                    this.packTargetGraph, this.packPlan, width, height);
            if (this.packCoveragePlan.enabled()) {
                this.packCoverageOwner.ensure(width, height);
                if (this.sceneSeedPipeline != null) {
                    this.sceneSeedPipeline.cleanUp();
                }
                MrtPipelineContext.begin(new int[] {packGeometryTargetFormat()}, maxAttachments);
                try {
                    this.sceneSeedPipeline = ChimeraPostPipelines.createSceneSeedPipeline();
                } finally {
                    MrtPipelineContext.end();
                }
                LOGGER.info("[chimera] scene seed: planned families={} deviations={}",
                        this.packCoveragePlan.families(), this.packCoveragePlan.deviations());
            } else if (!this.packCoveragePlan.deviations().isEmpty()) {
                LOGGER.info("[chimera] scene seed: fallback deviations={}",
                        this.packCoveragePlan.deviations());
            }
            this.packFrameSchedule = PackFrameSchedulePlan.build(
                    this.packPlan.programs(), this.packTargetGraph);
            this.packNeedsHdrDepth = this.packTargetGraph.depth().any();
            this.packDepthTargets.configure(width, height, this.packTargetGraph.depth());
            this.packPostChainActive = this.packPostTargets.configure(this.packTargetGraph);
            if (this.packPostChainActive) {
                LOGGER.info("[chimera] pack frame schedule: phases={}, early={}, late={}, final={}, depth={}, deviations={}",
                        this.packFrameSchedule.phases(),
                        this.packFrameSchedule.stages(PackFrameSchedulePlan.PostWindow.EARLY).stream()
                                .map(PackFrameSchedulePlan.PostStage::name).toList(),
                        this.packFrameSchedule.stages(PackFrameSchedulePlan.PostWindow.LATE).stream()
                                .map(PackFrameSchedulePlan.PostStage::name).toList(),
                        this.packFrameSchedule.stages(PackFrameSchedulePlan.PostWindow.FINAL).stream()
                                .map(PackFrameSchedulePlan.PostStage::name).toList(),
                        this.packFrameSchedule.deviations());
                LOGGER.info("[chimera] pack target graph: targets={}, steps={}, depth={}, maxAttachments={}, fingerprint={}",
                        this.packTargetGraph.targets().size(), this.packTargetGraph.steps().size(),
                        this.packTargetGraph.depth().names(), this.packTargetGraph.maxAttachments(),
                        this.packTargetGraph.fingerprint());
            }
        } catch (RuntimeException e) {
            this.packPostTargets.cleanUp();
            this.packDepthTargets.cleanUp();
            this.packCoverageOwner.close();
            this.packCoverageState.endFrame();
            this.packCoveragePlan = PackCoveragePlan.disabled();
            this.coverageGeometryReady = false;
            this.packPostChainActive = false;
            this.packTargetGraph = null;
            this.packFrameSchedule = PackFrameSchedulePlan.empty();
            this.packPostChainRejected = true;
            discardPackPostPipelines();
            if (this.conformanceReport != null) {
                this.conformanceReport.addDeviation("POST_RESOURCE_ALLOCATION_FAILED");
                LOGGER.info("[chimera] conformance {}", this.conformanceReport.toJson());
            }
            LOGGER.warn("[chimera] pack post target chain: fallback=IDENTITY (resource allocation failed: {})",
                    e.getMessage());
        }
    }

    private static int deviceMaxColorAttachments() {
        if (DeviceManager.device == null) {
            return PackTargetGraphPlan.LOGICAL_ATTACHMENT_LIMIT;
        }
        return ((ChimeraDeviceAccessor) DeviceManager.device)
                .chimera$properties().limits().maxColorAttachments();
    }

    private static int deviceMaxImageDimension() {
        if (DeviceManager.device == null) {
            return Integer.MAX_VALUE;
        }
        return ((ChimeraDeviceAccessor) DeviceManager.device)
                .chimera$properties().limits().maxImageDimension2D();
    }

    private int packGeometryTargetFormat() {
        TargetSpec target = this.packTargetGraph == null ? null : this.packTargetGraph.target(0);
        return target == null ? this.packHdrFormat : target.format();
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

    /** Merges host HDR pixels into the pack target without overwriting covered geometry. */
    private void seedPackScene(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        VulkanImage target = this.packPostTargets.geometryTarget0();
        VulkanImage coverage = this.packCoverageOwner.image();
        if (target == null || coverage == null || this.sceneSeedPipeline == null
                || !this.packCoverageState.canSeed()) {
            return;
        }
        VulkanImage previousHdr = VTextureSelector.getBoundTexture(0);
        VulkanImage previousCoverage = VTextureSelector.getBoundTexture(14);
        boolean depthTest = VRenderSystem.depthTest;
        boolean depthMask = VRenderSystem.depthMask;
        int colorMask = VRenderSystem.getColorMask();
        boolean blendEnabled = PipelineState.blendInfo.enabled;
        boolean cullEnabled = VRenderSystem.cull;
        VTextureSelector.bindTexture(0, hdrColor);
        VTextureSelector.bindTexture(14, coverage);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            coverage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            int deviceLimit = deviceMaxColorAttachments();
            MrtPipelineContext.begin(new int[] {target.format}, deviceLimit);
            VkRenderingAttachmentInfo.Buffer attachments = VkRenderingAttachmentInfo.calloc(1, stack);
            attachments.get(0).sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(target.getImageView())
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
            VRenderSystem.disableBlend();
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
            VTextureSelector.bindTexture(14, previousCoverage);
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
    }

    /**
     * Discovers and parses the active pack once per session. Must run before
     * createHdrFramebuffer so the pack's colortex0Format can drive the HDR
     * buffer format.
     */
    private void loadPackConfig() {
        if (this.packConfig != null) {
            if (this.conformanceReport == null) {
                if (this.packPath != null && this.packSource != null) {
                    this.conformanceReport = PackProbe.probe(this.packPath, this.packSource);
                }
            }
            return;
        }
        Path dir = this.packPath;
        if (dir == null) {
            LOGGER.info("[chimera] pack disabled (identity selection)");
            ChimeraTerrainPipelines.setMaterialResolver(PackMaterialResolver.empty());
            ChimeraTerrainPipelines.setMaterialPlan(TerrainMaterialPlan.legacy());
            PackUniformProvider.installRuntimeSettings(null);
            this.packNeedsHdrDepth = false;
            return;
        }
        PackSource.LoadResult result = this.packSource != null
                ? this.packSource : PackSource.loadResult(dir, this.currentDimension);
        this.packSource = result;
        PackProbe.Analysis analysis = PackProbe.analyze(dir, result);
        this.conformanceReport = analysis.report();
        this.packPrograms = result.programs();
        this.packConfig = analysis.config() != null
                ? analysis.config()
                : PackConfig.parse(this.packPrograms, result.shadersDir());
        this.packPlan = analysis.plan() == null
                ? new PackPlan(this.packConfig, List.of()) : analysis.plan();
        ChimeraTerrainPipelines.setMaterialPlan(this.packPlan.terrainMaterial());
        PackUniformProvider.installRuntimeSettings(this.packPlan.runtimeSettings());
        LOGGER.info("[chimera] pack resolution: dimension={}, folder={}, profile=defaults, aliases={}, disabled={}, missing={}, settingsFingerprint={}, resolutionFingerprint={}",
                this.packPlan.selectedDimension(),
                this.packPlan.selectedSourceFolder(),
                this.packPlan.aliasCount(),
                this.packPlan.disabledProgramCount(),
                this.packPlan.missingProgramCount(),
                this.packPlan.settingsFingerprint(),
                this.packPlan.resolutionFingerprint());
        LOGGER.info("[chimera] uniform runtime settings: custom={}, wetnessRise={}, wetnessFall={}, eyeBrightnessHalfLife={}",
                this.packPlan.runtimeSettings().customDescriptors().size(),
                this.packPlan.runtimeSettings().wetnessRiseHalfLife(),
                this.packPlan.runtimeSettings().wetnessFallHalfLife(),
                this.packPlan.runtimeSettings().eyeBrightnessHalfLife());
        this.packNeedsHdrDepth = this.conformanceReport.programs().stream()
                .filter(program -> PostTargetPlan.isPostProgramName(program.name()))
                .anyMatch(program -> program.samplers().contains("depthtex0")
                        || program.samplers().contains("depthtex1")
                        || program.samplers().contains("depthtex2"));
        PackMaterialResolver.ParseResult material = PackMaterialResolver.parse(result.shadersDir());
        ChimeraTerrainPipelines.setMaterialResolver(material.resolver());
        this.packHdrFormat = this.packConfig.colortexFormats().getOrDefault(0, 97);
        this.packResourceOwner = PackResourceOwner.load(this.packPlan.resources(), result.shadersDir());
        LOGGER.info("[chimera] pack resources: fingerprint={}, declarations={}, deviations={}",
                this.packPlan.resources().fingerprint(),
                this.packPlan.resources().declarations().size(),
                this.packPlan.resources().deviations().size());
        if (this.packPrograms.isEmpty()) {
            LOGGER.warn("[chimera] pack '{}' from {}: no programs found", dir.getFileName(), dir.toAbsolutePath());
            return;
        }
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

    private void logConformanceSummary() {
        if (this.conformanceReport != null) {
            LOGGER.info("[chimera] conformance summary: reportSha256={} {}",
                    this.conformanceReport.sha256(), this.conformanceReport.runtimeSummary());
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
        this.packCompositePipeline = null;
        this.packCompositeSlots = null;
        this.packCompositeSamplerNames = List.of();
        this.packFinalPipeline = null;
        this.packFinalSlots = null;
        this.packFinalSamplerNames = List.of();
        this.packPostStages.clear();
        this.packPostExecution.clear();
        this.packPostBoundaryLogged.clear();
        this.packFinalPost = null;
        this.packFinalExecution = null;
        this.packPostChainActive = false;
        this.packPostChainRejected = false;
        this.packGeometryPipeline = null;
        this.packGeometrySlots = null;
        this.packTranslucentPipeline = null;
        this.packTranslucentSlots = null;
        this.packShadowPipeline = null;
        this.packShadowSlots = null;
        this.packEntityPipeline = null;
        this.packTranslucentEntityPipeline = null;
        this.packGlowingEntityPipeline = null;
        this.packBlockPipeline = null;
        this.packDamagedBlockPipeline = null;
        this.packHandPipeline = null;
        this.packHandWaterPipeline = null;
        this.packParticlePipeline = null;
        this.packTranslucentParticlePipeline = null;
        this.packWeatherPipeline = null;
        this.shadowCutoutDispositionLogged = false;
        this.packPipelinesLoaded = true;

        if (this.packPrograms == null) {
            // loadPackConfig found no pack (or no programs).
            if (this.conformanceReport != null) {
                this.conformanceReport.markUnattemptedAsFallback();
                LOGGER.info("[chimera] conformance {}", this.conformanceReport.toJson());
                logConformanceSummary();
            }
            return;
        }

        String fixedVertex = ChimeraShaderLoader.loadSource("chimera_composite/chimera_composite.vsh");
        for (PackProgram program : this.packPrograms) {
            String name = program.name();
            PackProgramPlan programPlan = this.packPlan == null
                    ? null : this.packPlan.program(name);
            boolean reportAllowed = this.conformanceReport == null
                    || this.conformanceReport.shouldAttempt(name);
            boolean planAllowed = this.packPlan == null || this.packPlan.shouldAttempt(name);
            if (!reportAllowed || !planAllowed) {
                String reason = "CONTRACT_UNSUPPORTED";
                if (this.packPlan != null && this.packPlan.isProgramDisabled(name)) {
                    reason = "PROGRAM_DISABLED:" + name;
                } else if (this.packPlan != null && this.packPlan.isProgramAlias(name)) {
                    reason = "PROGRAM_ALIAS_RUNTIME_FALLBACK:" + name;
                } else if (this.packPlan != null
                        && !this.packPlan.resources().programAllowed(name)) {
                    reason = this.packPlan.resources().deviationsForProgram(name).stream()
                            .filter(value -> value.startsWith("PACK_TEXTURE_")
                                    || value.startsWith("STANDARD_RESOURCE_UNAVAILABLE:")
                                    || value.startsWith("MATERIAL_MAP_DEFERRED:"))
                            .findFirst().orElse("PACK_RESOURCE_UNAVAILABLE");
                } else if (programPlan != null && !programPlan.executable()) {
                    reason = "PLAN_INELIGIBLE:" + name;
                }
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                            reason);
                }
                LOGGER.warn("[chimera] pack {}: fallback=IDENTITY ({})", name, reason);
                continue;
            }
            if (this.packResourceOwner != null && !this.packResourceOwner.programAvailable(name)) {
                String reason = this.packResourceOwner.failureDeviationForProgram(name);
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, reason);
                }
                LOGGER.warn("[chimera] pack {}: fallback=IDENTITY ({})", name, reason);
                continue;
            }
            if (name.equals("shadow")) {
                PackPipelines.PackShadow shadow = PackPipelines.buildShadow(programPlan,
                        this.packPlan == null
                                ? TerrainMaterialPlan.legacy()
                                : this.packPlan.terrainMaterial());
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
                this.packShadowSlots = shadow.samplerSlots();
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED, null);
                }
                LOGGER.info("[chimera] pack shadow: ok (samplers={})",
                        Arrays.toString(shadow.samplerSlots()));
                if (TRACE_TRANSITIONS) {
                    LOGGER.info("[chimera] pack shadow converted fragment:\n{}", shadow.convertedFragment());
                }
            } else if (PostTargetPlan.isPostProgramName(name)) {
                PackPipelines.PackPost post = PackPipelines.buildPost(programPlan, fixedVertex);
                if (post == null) {
                    if (this.conformanceReport != null) {
                        this.conformanceReport.markRuntime(name,
                                ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                "PIPELINE_BUILD_FAILED");
                    }
                    LOGGER.warn("[chimera] pack {}: fallback=IDENTITY (build failed)", name);
                    continue;
                }
                this.packPostStages.add(post);
                if (name.equals("composite")) {
                    this.packCompositePipeline = post.pipeline();
                    this.packCompositeSlots = post.samplerSlots();
                    this.packCompositeSamplerNames = post.samplerNames();
                } else if (name.equals("final")) {
                    this.packFinalPipeline = post.pipeline();
                    this.packFinalSlots = post.samplerSlots();
                    this.packFinalSamplerNames = post.samplerNames();
                    this.packFinalPost = post;
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
                PackPipelines.PackTerrain terrain = PackPipelines.buildTerrain(programPlan,
                        ChimeraShaderLoader.loadSource("chimera_terrain/chimera_terrain.vsh"),
                        this.packPlan == null ? TerrainMaterialPlan.legacy()
                                : this.packPlan.terrainMaterial(),
                        this.packCoveragePlan.enabled(), packGeometryTargetFormat());
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
                this.coverageGeometryReady |= this.packCoveragePlan.enabled();
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
                PackPipelines.PackTerrain water = PackPipelines.buildTranslucent(programPlan,
                        ChimeraShaderLoader.loadSource("chimera_terrain/chimera_terrain.vsh"),
                        this.packPlan == null ? TerrainMaterialPlan.legacy()
                                : this.packPlan.terrainMaterial(),
                        false, packGeometryTargetFormat());
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
            } else if (name.equals("gbuffers_entities")) {
                PackPipelines.PackEntity entity = PackPipelines.buildEntity(programPlan);
                if (entity == null) {
                    if (this.conformanceReport != null) {
                        this.conformanceReport.markRuntime(name,
                                ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                "ENTITY_PIPELINE_BUILD_FAILED");
                    }
                    LOGGER.warn("[chimera] pack gbuffers_entities: fallback=IDENTITY "
                            + "(ENTITY_PIPELINE_BUILD_FAILED)");
                    continue;
                }
                this.packEntityPipeline = entity;
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "ENTITY_VERTEX_FORMAT_EXTENDED");
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "ENTITY_PIPELINE_INSTALLED");
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "ENTITY_STATE_FIXED_TO_HOST");
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "ENTITY_BATCH_ORIGIN_SPLIT");
                }
                LOGGER.info("[chimera] pack gbuffers_entities: ok (entity pipeline installed, "
                        + "stride={}, samplers={})",
                        net.chimera.render.vertex.ChimeraVertexFormats.EXTENDED_ENTITY.getVertexSize(),
                        Arrays.toString(entity.samplerSlots()));
                if (TRACE_TRANSITIONS) {
                    LOGGER.info("[chimera] pack gbuffers_entities converted vertex:\n{}",
                            entity.convertedVertex());
                    LOGGER.info("[chimera] pack gbuffers_entities converted fragment:\n{}",
                            entity.convertedFragment());
                }
            } else if (FamilyAdapterRegistry.isWorldEntityFamily(name)) {
                PackPipelines.PackEntity entity = PackPipelines.buildEntityFamily(programPlan);
                if (entity == null) {
                    markFamilyPipelineFallback(name, "ENTITY_PIPELINE_BUILD_FAILED");
                    continue;
                }
                if (name.equals("gbuffers_entities_translucent")) {
                    this.packTranslucentEntityPipeline = entity;
                } else {
                    this.packGlowingEntityPipeline = entity;
                }
                markFamilyPipelineInstalled(name, name.equals("gbuffers_entities_translucent")
                        ? "TRANSLUCENT_ENTITY_INSTALLED" : "GLOWING_ENTITY_INSTALLED");
            } else if (name.equals("gbuffers_block")) {
                PackPipelines.PackEntity block = PackPipelines.buildBlock(programPlan);
                if (block == null) {
                    if (this.conformanceReport != null) {
                        this.conformanceReport.markRuntime(name,
                                ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                "BLOCK_PIPELINE_BUILD_FAILED");
                    }
                    LOGGER.warn("[chimera] pack gbuffers_block: fallback=IDENTITY "
                            + "(BLOCK_PIPELINE_BUILD_FAILED)");
                    continue;
                }
                this.packBlockPipeline = block;
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "FAMILY_ADAPTER_INSTALLED");
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "ENTITY_VERTEX_FORMAT_EXTENDED");
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "BLOCK_ENTITY_ID_DEFAULTED");
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "ENTITY_STATE_FIXED_TO_HOST");
                }
                LOGGER.info("[chimera] pack gbuffers_block: ok (block adapter installed, "
                        + "stride={}, samplers={})",
                        net.chimera.render.vertex.ChimeraVertexFormats.EXTENDED_ENTITY.getVertexSize(),
                        Arrays.toString(block.samplerSlots()));
            } else if (FamilyAdapterRegistry.isBlockFamily(name)) {
                // The host crumbling lane uses a distinct format and render
                // pass. EXTENDED_ENTITY is not a safe substitute. Keep the
                // host damage overlay until a matching crumbling adapter is
                // available rather than corrupting the block batch.
                markFamilyPipelineFallback(name, "DAMAGED_BLOCK_HOST_FORMAT_UNSUPPORTED");
                this.packDamagedBlockPipeline = null;
            } else if (name.equals("gbuffers_hand")) {
                PackPipelines.PackEntity hand = PackPipelines.buildHand(programPlan);
                if (hand == null) {
                    if (this.conformanceReport != null) {
                        this.conformanceReport.markRuntime(name,
                                ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                "HAND_PIPELINE_BUILD_FAILED");
                    }
                    LOGGER.warn("[chimera] pack gbuffers_hand: fallback=IDENTITY "
                            + "(HAND_PIPELINE_BUILD_FAILED)");
                    continue;
                }
                this.packHandPipeline = hand;
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "FAMILY_ADAPTER_INSTALLED");
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "ENTITY_VERTEX_FORMAT_EXTENDED");
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "HAND_ITEM_ID_DEFAULTED");
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "HAND_STATE_FIXED_TO_HOST");
                }
                LOGGER.info("[chimera] pack gbuffers_hand: ok (hand adapter installed, "
                        + "stride={}, samplers={})",
                        net.chimera.render.vertex.ChimeraVertexFormats.EXTENDED_ENTITY.getVertexSize(),
                        Arrays.toString(hand.samplerSlots()));
            } else if (FamilyAdapterRegistry.isHandFamily(name)) {
                PackPipelines.PackEntity hand = PackPipelines.buildEntityFamily(programPlan);
                if (hand == null) {
                    markFamilyPipelineFallback(name, "HAND_PIPELINE_BUILD_FAILED");
                    continue;
                }
                this.packHandWaterPipeline = hand;
                markFamilyPipelineInstalled(name, "HAND_WATER_INSTALLED");
            } else if (name.equals("gbuffers_particles")) {
                PackPipelines.PackParticle particle = PackPipelines.buildParticle(programPlan);
                if (particle == null) {
                    if (this.conformanceReport != null) {
                        this.conformanceReport.markRuntime(name,
                                ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                "PARTICLE_PIPELINE_BUILD_FAILED");
                    }
                    LOGGER.warn("[chimera] pack gbuffers_particles: fallback=IDENTITY "
                            + "(PARTICLE_PIPELINE_BUILD_FAILED)");
                    continue;
                }
                this.packParticlePipeline = particle;
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "FAMILY_ADAPTER_INSTALLED");
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED,
                            "PARTICLE_STATE_FIXED_TO_HOST");
                }
                LOGGER.info("[chimera] pack gbuffers_particles: ok (particle adapter installed, "
                        + "stride={}, samplers={})",
                        com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE.getVertexSize(),
                        Arrays.toString(particle.samplerSlots()));
            } else if (FamilyAdapterRegistry.isParticleLike(name)) {
                PackPipelines.PackParticle particle = PackPipelines.buildParticleFamily(programPlan);
                if (particle == null) {
                    markFamilyPipelineFallback(name, "PARTICLE_PIPELINE_BUILD_FAILED");
                    continue;
                }
                this.packTranslucentParticlePipeline = particle;
                markFamilyPipelineInstalled(name, "PARTICLE_TRANSLUCENT_INSTALLED");
            } else if (FamilyAdapterRegistry.isWeatherFamily(name)) {
                PackPipelines.PackParticle weather = PackPipelines.buildParticleFamily(programPlan);
                if (weather == null) {
                    markFamilyPipelineFallback(name, "WEATHER_PIPELINE_BUILD_FAILED");
                    continue;
                }
                this.packWeatherPipeline = weather;
                markFamilyPipelineInstalled(name, "WEATHER_PIPELINE_INSTALLED");
            } else {
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                            "CONTRACT_UNSUPPORTED");
                }
                LOGGER.warn("[chimera] pack {}: fallback=IDENTITY (unsupported program family)", name);
            }
        }
        ChimeraEntityBridge.install(this.packEntityPipeline, this.packTranslucentEntityPipeline,
                this.packGlowingEntityPipeline, this.packBlockPipeline, this.packDamagedBlockPipeline,
                this.packHandPipeline, this.packHandWaterPipeline, this.packParticlePipeline,
                this.packTranslucentParticlePipeline, this.packWeatherPipeline,
                this.packPlan == null ? null : this.packPlan.entityIds());
        this.packPostStages.sort(Comparator.comparing(
                PackPipelines.PackPost::name, PostTargetPlan.programComparator()));
        buildPackPostExecutionPlan();
        if (this.packCoveragePlan.enabled()) {
            if (packCoverageRuntimeEnabled()) {
                LOGGER.info("[chimera] scene seed: runtime enabled families={}",
                        this.packCoveragePlan.families());
            } else {
                LOGGER.info("[chimera] scene seed: runtime fallback (no installed pack terrain adapter)");
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
            logConformanceSummary();
        }
    }

    private void markFamilyPipelineInstalled(String name, String deviation) {
        if (this.conformanceReport != null) {
            this.conformanceReport.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED, deviation);
        }
        LOGGER.info("[chimera] pack {}: ok ({})", name, deviation);
    }

    private void markFamilyPipelineFallback(String name, String reason) {
        if (this.conformanceReport != null) {
            this.conformanceReport.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, reason);
        }
        LOGGER.warn("[chimera] pack {}: fallback=IDENTITY ({})", name, reason);
    }

    /** Builds the immutable post execution order once for the active pack. */
    private void buildPackPostExecutionPlan() {
        this.packPostExecution.clear();
        this.packFinalExecution = null;
        if (this.packPrograms == null) {
            return;
        }
        List<PackProgram> postPrograms = this.packPrograms.stream()
                .filter(program -> PostTargetPlan.isPostProgramName(program.name()))
                .sorted(Comparator.comparing(PackProgram::name, PostTargetPlan.programComparator()))
                .toList();
        String previousName = null;
        for (PackProgram program : postPrograms) {
            if (program.name().equals(previousName)) {
                continue;
            }
            previousName = program.name();
            PackPipelines.PackPost installed = null;
            for (PackPipelines.PackPost candidate : this.packPostStages) {
                if (candidate.name().equals(program.name())) {
                    installed = candidate;
                    break;
                }
            }
            List<Integer> outputs = List.of();
            PackProgramPlan planned = this.packPlan == null
                    ? null : this.packPlan.program(program.name());
            if (planned != null && planned.targetPlan() != null) {
                outputs = planned.targetPlan().targetSlots();
            } else if (installed != null && installed.targetPlan() != null) {
                outputs = installed.targetPlan().targetSlots();
            } else if (this.conformanceReport != null) {
                ConformanceReport.ProgramReport report = this.conformanceReport.program(program.name());
                if (report != null) {
                    outputs = report.targets();
                }
            }
            PackPostExecution execution = new PackPostExecution(
                    program.name(), outputs, installed, program.name().equals("final"));
            this.packPostExecution.add(execution);
            if (execution.finalStage()) {
                this.packFinalExecution = execution;
            }
        }
    }

    private boolean requiresNoise(PackProgram program) {
        if (this.conformanceReport == null) {
            return false;
        }
        ConformanceReport.ProgramReport report = this.conformanceReport.program(program.name());
        return report != null
                && report.samplers().contains("noisetex")
                && !report.deviations().contains("SAMPLER_DECLARATION_UNUSED:noisetex");
    }

    /** Discards installed post pipelines when target graph setup fails. */
    private void discardPackPostPipelines() {
        for (PackPipelines.PackPost post : this.packPostStages) {
            post.pipeline().cleanUp();
            if (this.conformanceReport != null) {
                this.conformanceReport.markRuntime(post.name(),
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "POST_CHAIN_FAILED");
            }
        }
        this.packPostStages.clear();
        this.packPostExecution.clear();
        this.packPostBoundaryLogged.clear();
        this.packFinalPost = null;
        this.packFinalExecution = null;
        this.packCompositePipeline = null;
        this.packCompositeSlots = null;
        this.packCompositeSamplerNames = List.of();
        this.packFinalPipeline = null;
        this.packFinalSlots = null;
        this.packFinalSamplerNames = List.of();
        LOGGER.warn("[chimera] pack post chain: fallback=IDENTITY (POST_CHAIN_FAILED)");
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
        VulkanImage shadowDepth = this.packShadowDepth.image();
        if (shadowDepth == null) {
            shadowDepth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
        }
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

    /** Makes newly created shadow images valid shader inputs exactly once. */
    private boolean prepareShadowForSampling() {
        if (!this.shadowMap.needsInitialSamplingLayout()) {
            return true;
        }
        if (!Renderer.isRecording() || Renderer.getInstance().getBoundRenderPass() != null) {
            recordShadowTransitionFallback("unsafe command state");
            return false;
        }
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if (commandBuffer == null || !shadowAttachmentsLive()) {
            recordShadowTransitionFallback("missing command buffer or attachment");
            return false;
        }
        VulkanImage shadowColor = this.shadowMap.getShadowFramebuffer().getColorAttachment();
        VulkanImage shadowDepth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            trace("shadowInitial", "shadowColor", shadowColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            trace("shadowInitial", "shadowDepth", shadowDepth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            shadowColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            shadowDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            this.shadowMap.markInitialSamplingLayoutReady();
            this.shadowTransitionFallbackLogged = false;
            return true;
        } catch (RuntimeException failure) {
            recordShadowTransitionFallback(String.valueOf(failure.getMessage()));
            return false;
        }
    }

    private boolean shadowAttachmentsLive() {
        if (!this.shadowMap.isInitialized() || this.shadowMap.getShadowFramebuffer() == null) {
            return false;
        }
        VulkanImage color = this.shadowMap.getShadowFramebuffer().getColorAttachment();
        VulkanImage depth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
        return color != null && depth != null && color.getId() != 0L && depth.getId() != 0L;
    }

    private void recordShadowTransitionFallback(String detail) {
        if (this.conformanceReport != null) {
            this.conformanceReport.addDeviation("SHADOW_RESOURCE_TRANSITION_SKIPPED");
        }
        if (!this.shadowTransitionFallbackLogged) {
            this.shadowTransitionFallbackLogged = true;
            LOGGER.warn("[chimera] shadow sampling fallback=IDENTITY (transition skipped: {})", detail);
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
        if (this.sceneSeedPipeline != null) this.sceneSeedPipeline.cleanUp();
        this.presentPipeline = null;
        this.compositePipeline = null;
        this.sceneSeedPipeline = null;
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
