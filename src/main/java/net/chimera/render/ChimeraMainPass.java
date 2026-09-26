package net.chimera.render;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.mixin.ChimeraDeviceAccessor;
import net.chimera.shaderpack.DepthGraphPlan;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.ProgramImageBindingManifest;
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
import net.chimera.shaderpack.SelectorNamespace;
import net.chimera.shaderpack.PackResourceStatus;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
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
import org.joml.Matrix4f;
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
    private static final HandBoundaryPolicy HAND_BOUNDARY_POLICY =
            new HandBoundaryPolicy(true, true);

    /** The host hand continues from loaded color with a fresh cleared depth. */
    static HandBoundaryPolicy handBoundaryPolicy() {
        return HAND_BOUNDARY_POLICY;
    }

    static record HandBoundaryPolicy(boolean loadColor, boolean clearDepth) {}

    /**
     * The M8.2 coverage path remains load-time inventory until every host
     * family can share the same coverage contract. Keeping the gate explicit
     * prevents the deferred path from allocating live images or pipelines.
     */
    private static final boolean PACK_COVERAGE_RUNTIME_ENABLED = false;


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

    /** Complete host state snapshot for one pack-owned fullscreen seam. */
    private record PackRenderState(
            boolean depthTest,
            boolean depthMask,
            int colorMask,
            boolean blendEnabled,
            int srcRgbFactor,
            int dstRgbFactor,
            int srcAlphaFactor,
            int dstAlphaFactor,
            int blendOp,
            boolean cullEnabled,
            int viewportWidth,
            int viewportHeight
    ) {}

    private PackRenderState capturePackRenderState() {
        Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
        if (boundFramebuffer == null) {
            boundFramebuffer = this.currentFramebuffer;
        }
        int viewportWidth = boundFramebuffer == null ? 0 : boundFramebuffer.getWidth();
        int viewportHeight = boundFramebuffer == null ? 0 : boundFramebuffer.getHeight();
        return new PackRenderState(
                VRenderSystem.depthTest,
                VRenderSystem.depthMask,
                VRenderSystem.getColorMask(),
                PipelineState.blendInfo.enabled,
                PipelineState.blendInfo.srcRgbFactor,
                PipelineState.blendInfo.dstRgbFactor,
                PipelineState.blendInfo.srcAlphaFactor,
                PipelineState.blendInfo.dstAlphaFactor,
                PipelineState.blendInfo.blendOp,
                VRenderSystem.cull,
                viewportWidth,
                viewportHeight);
    }

    private static void preparePackFullscreenState() {
        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.colorMask(true, true, true, true);
        VRenderSystem.disableBlend();
        VRenderSystem.disableCull();
    }

    private void restorePackRenderState(PackRenderState state) {
        if (state == null) return;
        VRenderSystem.depthTest = state.depthTest();
        VRenderSystem.depthMask = state.depthMask();
        VRenderSystem.colorMask((state.colorMask() & 1) != 0, (state.colorMask() & 2) != 0,
                (state.colorMask() & 4) != 0, (state.colorMask() & 8) != 0);
        if (state.blendEnabled()) {
            VRenderSystem.enableBlend();
        } else {
            VRenderSystem.disableBlend();
        }
        PipelineState.blendInfo.srcRgbFactor = state.srcRgbFactor();
        PipelineState.blendInfo.dstRgbFactor = state.dstRgbFactor();
        PipelineState.blendInfo.srcAlphaFactor = state.srcAlphaFactor();
        PipelineState.blendInfo.dstAlphaFactor = state.dstAlphaFactor();
        PipelineState.blendInfo.blendOp = state.blendOp();
        VRenderSystem.cull = state.cullEnabled();
        if (state.viewportWidth() > 0 && state.viewportHeight() > 0) {
            Renderer.setViewport(0, 0, state.viewportWidth(), state.viewportHeight());
            Renderer.setScissor(0, 0, state.viewportWidth(), state.viewportHeight());
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
    private Framebuffer packFinalFramebuffer;
    private RenderPass packFinalRenderPass;
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
    private PackPipelines.PackTerrain packGeometryTerrain;
    private PackPipelines.PackTerrain packTranslucentTerrain;
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
    /** Immutable target and depth schedule for the active pack variant. */
    private PackTargetGraphPlan packTargetGraph;
    /** Immutable world/post order for the active pack variant. */
    private PackFrameSchedulePlan packFrameSchedule = PackFrameSchedulePlan.empty();
    private boolean packPostChainActive;
    /** Target images may be needed by geometry even when no post pass is installed. */
    private boolean packTargetResourcesReady;
    /** Set only when the target graph itself cannot be configured. */
    private boolean packPostChainRejected;
    /**
     * Set when the mask-critical family contract fails at install. Target
     * reconfiguration (resize) honors it instead of recomputing post
     * activity from targets alone; only a fresh install clears it.
     */
    private boolean postChainFamilyBlocked;
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
    /** Resource-pack material images and flat fallbacks, retained for the renderer lifetime. */
    private MaterialMapOwner materialMapOwner;
    /** All pack-owned writable images, retained for the pack session. */
    private PackAdvancedImageOwner packAdvancedImageOwner;
    /** Owns exact-size pack storage buffers for the active session. */
    private PackStorageBufferOwner packStorageBufferOwner;
    private boolean packStorageInitializationLogged;
    /** Suppresses the secondary frame-not-started flood until the next pack session. */
    private boolean packTargetFrameNotStartedLogged;
    /** Optional bounded shadow compute pipeline for the active pack session. */
    private PackShadowCompute packShadowCompute;
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
    /** Previous selector values for pack-owned geometry textures during one terrain draw. */
    private ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> packGeometryBindings;
    private final Map<GraphicsPipeline, ProgramImages> programImages = new IdentityHashMap<>();
    private record ProgramImages(String name, ProgramImageBindingManifest manifest,
                                Set<String> shadowSamplerSymbols) {}
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
    private PackPipelines.PackSky packSkyBasicPipeline;
    private PackPipelines.PackSky packSkyTexturedPipeline;
    private PackPipelines.PackSky packCloudPipeline;
    private boolean packCloudsDrawNothing;
    /** GL-registry slot-5 view of the shadow depth, for pack geometry sampling (shadowtex0). */
    private GpuTexture packShadowTexture;
    private GpuTextureView packShadowView;
    private long packShadowSourceId;
    private ChimeraShadowMap shadowMap = new ChimeraShadowMap();
    private final PackShadowDepth packShadowDepth = new PackShadowDepth();
    /** Programs whose routed shadow sampler was already reported once. */
    private final Set<String> loggedShadowSamplerPrograms = new java.util.HashSet<>();

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
    private boolean shadowSegmentObserved;
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
        VulkanImage shadowColor = this.shadowMap.getShadowFramebuffer().getColorAttachment();
        VulkanImage shadowDepth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
        boolean packShadow = usePackShadowRuntime();
        Matrix4f projection = packShadow
                ? this.shadowMap.getPackLightProjection()
                : this.shadowMap.getHostLightProjection();
        ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> shadowBindings = null;
        try {
            // The receiver keeps sampling the previously committed map until
            // this pass finishes. Only the pack caster uses the legacy range.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                trace("shadowPre", "shadowColor", shadowColor, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                trace("shadowPre", "shadowDepth", shadowDepth, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                shadowColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                shadowDepth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }

            try {
                if (packShadow) {
                    if (this.packAdvancedImageOwner != null
                            && this.packAdvancedImageOwner.capabilityEnabled()) {
                        this.packAdvancedImageOwner.prepareFrame(cmd);
                    }
                    prepareProgramImages(this.packShadowPipeline);
                    shadowBindings = bindProgramImages(this.packShadowPipeline, null, null,
                            DrawMaterialContext.captureLive());
                    PackGeometryContext.beginShadow(this.shadowMap.shadowColors(), shadowDepth);
                    // Pack receivers read the map with GL's v = ndc * 0.5 + 0.5.
                    // The fixed host receiver compensates for VulkanMod's flip.
                    ChimeraRasterOrientation.markGlOriented(this.shadowMap.getShadowRenderPass());
                }
                this.shadowPassActive = true;
                PipelineManager.setShaderGetter(rt -> {
                    if (packShadow) {
                        VRenderSystem.disableBlend();
                        return this.packShadowPipeline;
                    }
                    return this.shadowMap.getShadowPipeline();
                });
                if (packShadow) {
                    // This scoped pair is exactly the one used by MVP and
                    // ftransform() in the pack shadow draw.
                    this.shadowMap.publishCurrentDrawState();
                }
                VRenderSystem.applyProjectionMatrix(projection);
                VRenderSystem.applyModelViewMatrix(this.shadowMap.getLightView());
                VRenderSystem.calculateMVP();

                TerrainRenderType opaqueType = TerrainRenderType.getRemapped(TerrainRenderType.SOLID);
                WorldRenderer.getInstance().renderSectionLayer(
                        opaqueType,
                        cameraX, cameraY, cameraZ,
                        this.shadowMap.getLightView(), projection);

                TerrainRenderType cutoutType = TerrainRenderType.getRemapped(TerrainRenderType.CUTOUT);
                recordShadowCutoutDisposition(cutoutType == opaqueType);
                if (cutoutType != opaqueType) {
                    WorldRenderer.getInstance().renderSectionLayer(
                            cutoutType, cameraX, cameraY, cameraZ,
                            this.shadowMap.getLightView(), projection);
                }
                if (packShadow) {
                    WorldRenderer.getInstance().renderSectionLayer(
                            TerrainRenderType.TRANSLUCENT, cameraX, cameraY, cameraZ,
                            this.shadowMap.getLightView(), projection);
                }
            } finally {
                if (Renderer.getInstance().getBoundRenderPass() != null) {
                    Renderer.getInstance().endRenderPass(cmd);
                }
                if (packShadow) {
                    PackGeometryContext.close();
                    ChimeraRasterOrientation.unmark(this.shadowMap.getShadowRenderPass());
                }
                this.shadowPassActive = false;
                if (shadowBindings != null) shadowBindings.close();
            }

            if (packShadow && this.packStorageBufferOwner != null) {
                this.packStorageBufferOwner.prepareForUse(cmd);
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                trace("shadowPost", "shadowColor", shadowColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                trace("shadowPost", "shadowDepth", shadowDepth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                shadowColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                if (packShadow) {
                    this.shadowMap.shadowColor(1).transitionImageLayout(stack, cmd,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
                shadowDepth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            if (this.packAdvancedImageOwner != null
                    && this.packAdvancedImageOwner.capabilityEnabled()
                    && this.packShadowCompute != null && this.packShadowCompute.isInstalled()) {
                if (this.packShadowCompute.dispatch(cmd)) {
                    if (this.packStorageBufferOwner != null) this.packStorageBufferOwner.recordDispatch();
                } else LOGGER.warn("[chimera] shadowcomp dispatch failed; dependent advanced resources use fallback");
            }
            this.shadowMap.markInitialSamplingLayoutReady();
            if (packShadow) {
                this.shadowMap.commitRenderedMap(cameraX, cameraY, cameraZ);
                this.shadowMap.publishMapState(cameraX, cameraY, cameraZ);
            } else {
                this.shadowMap.invalidatePackMapSnapshot();
                PackUniformProvider.clearShadowState();
            }

            this.shadowMap.bindShadowTexture();
            maintainPackShadowGoal();
        } catch (RuntimeException failure) {
            this.shadowMap.invalidateMapSnapshot();
            this.shadowFrameReady = false;
            if (this.conformanceReport != null) {
                this.conformanceReport.addDeviation("SHADOW_MAP_WRITE_FAILED");
            }
            if (!this.shadowTransitionFallbackLogged) {
                this.shadowTransitionFallbackLogged = true;
                LOGGER.warn("[chimera] shadow map write failed; clearing to all-lit fallback: {}",
                        failure.toString());
            }
            try {
                if (Renderer.getInstance().getBoundRenderPass() != null) {
                    Renderer.getInstance().endRenderPass(cmd);
                }
                this.shadowPassActive = false;
                PackGeometryContext.close();
                this.shadowMap.initializeSampling(cmd);
                this.shadowMap.seedClearedMap(cameraX, cameraY, cameraZ);
                this.shadowMap.publishMapState(cameraX, cameraY, cameraZ);
                this.shadowMap.bindShadowTexture();
                this.shadowFrameReady = true;
            } catch (RuntimeException clearFailure) {
                this.shadowFrameReady = false;
                PackUniformProvider.clearShadowState();
                LOGGER.warn("[chimera] shadow map recovery failed; shadow inputs remain unavailable: {}",
                        clearFailure.toString());
            }
        } finally {
            this.shadowPassActive = false;
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
        }
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
        if (!this.shadowSegmentObserved) {
            this.shadowSegmentObserved = true;
            LOGGER.info("[chimera] shadow segment active; advanced compute dispatch is eligible");
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        VkCommandBuffer cmd = Renderer.getCommandBuffer();
        boolean resumePackCoverage = PackGeometryContext.coverageActive();
        boolean resumePackTerrain = PackGeometryContext.geometryActive();
        List<VulkanImage> resumeColors = PackGeometryContext.colors();
        VulkanImage resumeDepth = PackGeometryContext.depth();
        Renderer.getInstance().endRenderPass(cmd);
        if (resumePackCoverage || resumePackTerrain) {
            // The nested shadow render must not be intercepted by the pack
            // geometry dynamic-rendering bridge. Otherwise its 2048 viewport
            // renders into the world target and its clear load-op erases the
            // terrain that was just drawn.
            PackGeometryContext.close();
        }
        try {
            this.renderShadowMap(cameraX, cameraY, cameraZ);
        } finally {
            if (resumePackTerrain && !resumeColors.isEmpty() && resumeDepth != null
                    && this.hdrFramebuffer != null) {
                PackGeometryContext.beginGeometry(resumeColors, resumeDepth);
                this.rebindMainTarget();
            } else if (resumePackCoverage && this.hdrFramebuffer != null) {
                // Continue the interrupted opaque layer on the same pack
                // target. The outer renderSectionLayer return will close and
                // commit this resumed window normally.
                PackGeometryContext.beginPreserving(
                        resumeColors.get(0),
                        resumeColors.size() > 1 ? resumeColors.get(1) : this.packCoverageOwner.image(),
                        this.hdrFramebuffer.getDepthAttachment());
                this.rebindMainTarget();
            } else {
                // Reopen the HDR pass (load ops) for the host terrain layers.
                this.rebindMainTarget();
            }
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
        if (this.packPostTargets.isConfigured()) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            this.packPostTargets.beginFrame(commandBuffer, hdrColor);
            this.packPostFrameStarted = true;
            if (packCoverageRuntimeEnabled() && this.packPostChainActive) {
                this.packPostTargets.prepareGeometryTarget(commandBuffer);
                this.packCoverageOwner.beginFrame(commandBuffer);
                this.packCoverageState.beginFrame();
            }
        }

        // Compute the light once before any terrain draw. The shadow pass at
        // the opaque-layer tail reuses this exact state.
        if (this.shadowMap.isInitialized()) {
            this.shadowMap.updateLight(PackUniformProvider.currentSunLightVector());
            this.shadowFrameReady = prepareShadowForSampling(cameraX, cameraY, cameraZ);
            if (this.shadowFrameReady && this.shadowMap.hasMapSnapshot()) {
                this.shadowMap.publishMapState(cameraX, cameraY, cameraZ);
            } else {
                PackUniformProvider.clearShadowState();
            }
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
        if (this.packStorageBufferOwner != null) {
            this.packStorageBufferOwner.prepareForUse(commandBuffer);
        }
        if (PackGeometryContext.active()) {
            PackGeometryContext.close();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            trace("resolveWorld", "hdrColor", hdrColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (this.packNeedsHdrDepth) {
                if (this.packDepthTargets.isConfigured()
                        && this.packDepthTargets.plan().depthtex2()
                        && !this.packDepthTargets.currentAvailable("depthtex2")
                        && !this.packDepthTargets.capturePreHand(
                                commandBuffer, this.hdrFramebuffer.getDepthAttachment())) {
                    LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (pre-hand depth conversion failed)");
                }
                transitionHdrDepthForSampling(stack, commandBuffer);
                if (this.packDepthTargets.isConfigured()
                        && !this.packDepthTargets.captureScene(
                        commandBuffer, this.hdrFramebuffer.getDepthAttachment())) {
                    LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (depth conversion failed)");
                }
            }

            if (packCoverageRuntimeEnabled() && this.packCoverageState.canSeed()) {
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
                // The pack final stage is the last pack operation in the
                // world segment.  It must complete before vanilla hand
                // submission, so host hand and GUI draws remain visible.
                finishPackFinalBeforeHand();
                return;
            }

            if (this.packPostFrameStarted) {
                this.packPostTargets.commitFrame();
                this.packPostFrameStarted = false;
            }
            // Mask-critical bypass (DOC-348 repair 3): this branch runs when
            // the post chain is blocked or unconfigured. Installed pack
            // objects stay untouched for deterministic cleanup and reload,
            // but the pack composite must not execute here: it requires
            // pack auxiliary targets while only HDR target 0 exists, so
            // binding it throws RESOURCE_BINDING_UNAVAILABLE for its first
            // auxiliary sampler. The host identity resolve presents the HDR
            // image through a Sampler0-only manifest instead.
            resolveWorldToOutput(commandBuffer, hdrColor);
            this.currentFramebuffer = this.compositeFramebuffer;
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
        beginPackResourceWindow(renderType);
        PackPipelines.PackTerrain terrain = switch (renderType) {
            case SOLID, CUTOUT -> this.packGeometryTerrain;
            case TRANSLUCENT -> this.packTranslucentTerrain;
            default -> null;
        };
        if (terrain == null || !terrain.requiresDynamicAttachments()
                || !this.packTargetResourcesReady || this.shadowPassActive
                || this.hdrFramebuffer == null || this.hdrFramebuffer.getDepthAttachment() == null) {
            return;
        }
        if (PackGeometryContext.active()) {
            return;
        }
        try {
            List<Integer> outputs = terrain.outputPlan().targetSlots();
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            Renderer.getInstance().endRenderPass();
            this.packPostTargets.beginGeometry(Renderer.getCommandBuffer(), hdrColor, outputs);
            PackGeometryContext.beginGeometry(
                    this.packPostTargets.geometryAttachments(outputs),
                    this.hdrFramebuffer.getDepthAttachment());
        } catch (RuntimeException failure) {
            this.packPostTargets.abortGeometry(this.hdrFramebuffer.getColorAttachment());
            this.currentFramebuffer = this.hdrFramebuffer;
            this.rebindMainTarget();
            logPackRuntimeFailure("geometry-begin:" + renderType, failure);
        }
    }

    /** Returns the installed pack pipeline for a family window request. */
    public net.chimera.shaderpack.PackPipelines.PackEntity familyPipeline(
            ChimeraEntityBridge.Family family) {
        return switch (family) {
            case ENTITY -> this.packEntityPipeline;
            case ENTITY_TRANSLUCENT -> this.packTranslucentEntityPipeline;
            case GLOWING -> this.packGlowingEntityPipeline;
            case BLOCK -> this.packBlockPipeline;
            case DAMAGED_BLOCK -> this.packDamagedBlockPipeline;
            default -> null;
        };
    }

    /**
     * Opens the authored MRT window around one separated entity/block family
     * batch. The bridge only selects the pipeline; this pass owns the target
     * transitions: it closes the host HDR pass, binds target 0 to the live
     * HDR image plus the family's auxiliary targets and shared depth, and
     * enters the guarded geometry context whose dynamic-rendering bridge
     * supplies the attachments. Returns true only when this call opened the
     * window; the caller closes exactly what it opened.
     */
    public boolean beginPackFamilyWindow(net.chimera.shaderpack.PackPipelines.PackEntity family) {
        if (family == null || !family.requiresDynamicAttachments()
                || !this.packTargetResourcesReady || this.shadowPassActive
                || this.hdrFramebuffer == null
                || this.hdrFramebuffer.getDepthAttachment() == null
                || !this.packPostTargets.isConfigured()) {
            return false;
        }
        if (PackGeometryContext.active()) {
            return false;
        }
        try {
            List<Integer> outputs = family.outputPlan().targetSlots();
            VulkanImage hdrColor = this.hdrFramebuffer.getColorAttachment();
            Renderer.getInstance().endRenderPass();
            this.packPostTargets.beginGeometry(Renderer.getCommandBuffer(), hdrColor, outputs);
            PackGeometryContext.beginGeometry(
                    this.packPostTargets.geometryAttachments(outputs),
                    this.hdrFramebuffer.getDepthAttachment());
            return true;
        } catch (RuntimeException failure) {
            this.packPostTargets.abortGeometry(this.hdrFramebuffer.getColorAttachment());
            this.currentFramebuffer = this.hdrFramebuffer;
            this.rebindMainTarget();
            logPackRuntimeFailure("family-begin", failure);
            return false;
        }
    }

    /** Commits the family window's auxiliary outputs and reopens the HDR pass. */
    public void endPackFamilyWindow(net.chimera.shaderpack.PackPipelines.PackEntity family) {
        if (family == null || !family.requiresDynamicAttachments()
                || !PackGeometryContext.geometryActive() || this.shadowPassActive) {
            return;
        }
        try {
            Renderer.getInstance().endRenderPass();
            PackGeometryContext.close();
            this.packPostTargets.commitGeometry(family.outputPlan().targetSlots(),
                    this.hdrFramebuffer.getColorAttachment());
            this.currentFramebuffer = this.hdrFramebuffer;
            this.rebindMainTarget();
        } catch (RuntimeException failure) {
            this.packPostTargets.abortGeometry(this.hdrFramebuffer.getColorAttachment());
            logPackRuntimeFailure("family-commit", failure);
            if (PackGeometryContext.active()) PackGeometryContext.close();
            this.rebindMainTarget();
        }
    }
    private boolean packCoverageRuntimeEnabled() {
        return PACK_COVERAGE_RUNTIME_ENABLED;
    }

    /** The direct-HDR terrain path has no separate coverage window to close. */
    public void endPackCoverageWindow(TerrainRenderType renderType) {
        if (this.shadowPassActive) return;
        boolean geometryWindow = PackGeometryContext.geometryActive();
        if (geometryWindow) {
            PackPipelines.PackTerrain terrain = renderType == TerrainRenderType.TRANSLUCENT
                    ? this.packTranslucentTerrain : this.packGeometryTerrain;
            if (terrain == null || terrain.outputPlan() == null) {
                PackGeometryContext.close();
                restorePackResourceWindow();
                return;
            }
            try {
                Renderer.getInstance().endRenderPass();
                PackGeometryContext.close();
                this.packPostTargets.commitGeometry(terrain.outputPlan().targetSlots(),
                        this.hdrFramebuffer.getColorAttachment());
                this.currentFramebuffer = this.hdrFramebuffer;
                this.rebindMainTarget();
            } catch (RuntimeException failure) {
                this.packPostTargets.abortGeometry(this.hdrFramebuffer.getColorAttachment());
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
        this.currentFramebuffer = this.hdrFramebuffer;
        this.rebindMainTarget();
        restorePackResourceWindow();
    }

    /** Binds pack-owned sampled images for the guarded terrain draw only. */
    private void beginPackResourceWindow(TerrainRenderType renderType) {
        if (this.shadowPassActive || !ChimeraRenderer.segmentsActive()) return;
        PackPipelines.PackTerrain terrain = switch (renderType) {
            case SOLID, CUTOUT -> this.packGeometryTerrain;
            case TRANSLUCENT -> this.packTranslucentTerrain;
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
        if (this.shadowPassActive) return;
        PackPipelines.PackTerrain terrain = renderType == TerrainRenderType.TRANSLUCENT
                ? this.packTranslucentTerrain : this.packGeometryTerrain;
        if (terrain == null) return;
        restorePackResourceWindow();
        // Capture the draw context before the transaction: the albedo image
        // straight from the synchronized selector plus the authoritative
        // chunk sampler profile. The resolver below binds from this
        // immutable context instead of re-querying slot state mid-draw.
        DrawMaterialContext drawContext =
                DrawMaterialContext.forTerrain(VTextureSelector.getImage(0));
        this.packGeometryBindings = bindProgramImages(terrain.pipeline(),
                this.packPostTargets.sourceImages(), this.hdrFramebuffer.getColorAttachment(),
                drawContext);
        if (this.packGeometryBindings.containsSlot(0)) {
            ChimeraTextureBindingState.bindTerrainAtlas(VTextureSelector.getImage(0));
        }
    }

    /** Restores host selector values after a guarded terrain draw. */
    private void restorePackResourceWindow() {
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
            // The level-render return seam is the authoritative final seam.
            // If it was not reached, preserve the already-resolved host image
            // and let the normal host hand and GUI path continue.
            if (this.packFinalExecution != null) {
                markPostStageIdentityBoundary(this.packFinalExecution,
                        "level boundary not reached");
            }
            this.packFinalApplied = true;
        }
        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage outputColor = this.compositeFramebuffer.getColorAttachment();
            trace("presentRead", "outputColor", outputColor, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            outputColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (this.packNeedsHdrDepth) {
                transitionHdrDepthForSampling(stack, commandBuffer);
            }
            VTextureSelector.bindTexture(outputColor);

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
                    if (this.packFinalPipeline != null || this.packFinalPost != null) {
                        VTextureSelector.bindTexture(outputColor);
                    }
                } finally {
                    restorePackRenderState(previousState);
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
        ProgramImages program = this.programImages.get(pipeline);
        if (program != null && this.packStorageBufferOwner != null) {
            this.packStorageBufferOwner.recordDraw(program.name(), VK_SHADER_STAGE_FRAGMENT_BIT);
        }
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
        PackRenderState previousState = capturePackRenderState();
        try {
            if (!this.packPostFrameStarted) {
                throw new IllegalStateException("PACK_TARGET_FRAME_NOT_STARTED:post window " + window);
            }
            this.packPostTargets.requireFrameStarted();
            preparePackFullscreenState();
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
                            unavailablePostInputs(post));
                    continue;
                }
                ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> previous = null;
                boolean attempted = false;
                try {
                    prepareProgramImages(post.pipeline());
                    previous = bindProgramImages(post.pipeline(), this.packPostTargets.sourceImages(), hdrColor,
                            DrawMaterialContext.captureLive());
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
                    if (previous != null) previous.close();
                }
            }
        } catch (RuntimeException e) {
            try {
                if (this.packPostFrameStarted) {
                    this.packPostTargets.abort(commandBuffer);
                    this.packPostFrameStarted = false;
                }
            } catch (RuntimeException abortFailure) {
                logPackRuntimeFailure("post-abort:" + window, abortFailure);
            }
            String reason = runtimeFailureReason("post-window:" + window, e);
            disablePackPostChain("POST_RUNTIME_FAILED:" + reason);
            logPackRuntimeFailure("post-window:" + window, e);
        } finally {
            restorePackRenderState(previousState);
        }
        if (this.conformanceReport != null) {
            this.conformanceReport.addDeviation("PING_PONG_TARGETS_APPLIED");
            this.conformanceReport.addDeviation("UNWRITTEN_TARGET_PRESERVED");
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
    private void resolveWorldToOutput(
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
            Renderer.getInstance().beginRenderPass(this.compositeRenderPass, this.compositeFramebuffer);
            // The identity and legacy pack seams share the same transaction.
            drawFullscreen(commandBuffer, resolvePipeline);
        } finally {
            if (previous != null) previous.close();
            restorePackRenderState(previousState);
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
        if (!this.packDepthTargets.currentAvailable("depthtex2")
                && !this.packDepthTargets.capturePreHand(commandBuffer,
                this.hdrFramebuffer == null ? null : this.hdrFramebuffer.getDepthAttachment())) {
            LOGGER.warn("[chimera] pack depth graph: fallback=IDENTITY (pre-hand depth conversion failed)");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // The world resolve is already in the output image. Load its
            // color, then clear the fresh hand depth after depthtex2 capture.
            RenderPass handPass = HAND_BOUNDARY_POLICY.clearDepth()
                    ? this.compositeAuxClearDepthRenderPass : this.compositeAuxRenderPass;
            Renderer.getInstance().beginRenderPass(handPass,
                    this.compositeFramebuffer);
            Renderer.setViewport(0, 0, this.compositeFramebuffer.getWidth(),
                    this.compositeFramebuffer.getHeight(), stack);
            VK10.vkCmdSetScissor(commandBuffer, 0, this.compositeFramebuffer.scissor(stack));
        }
    }

    /** Runs the pack final stage after world post passes and before host hand. */
    public void finishPackFinalBeforeHand() {
        if (!this.packWorldResolved || this.packFinalApplied) {
            return;
        }
        Arrays.fill(this.packFinalInputs, null);
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
                            unavailablePostInputs(this.packFinalPost));
                }
            }
            this.packFinalApplied = true;
            return;
        }

        try {
            prepareProgramImages(this.packFinalPost.pipeline());
            // Final reads pack targets in GL row order (KNOW-414). It renders
            // into a GL-ordered image; the flipped resolve below moves that
            // image into the host-ordered output.
            Renderer.getInstance().endRenderPass(commandBuffer);
            Renderer.getInstance().beginRenderPass(this.packFinalRenderPass, this.packFinalFramebuffer);
            PackRenderState finalState = capturePackRenderState();
            ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> previous = null;
            try {
                preparePackFullscreenState();
                for (int target = 0; target < this.packFinalInputs.length; target++) {
                    this.packFinalInputs[target] = this.packPostTargets.activeTarget(target);
                }
                VulkanImage resolved = this.packPostTargets.activeTarget(0);
                previous = bindProgramImages(this.packFinalPost.pipeline(), this.packFinalInputs, resolved,
                        DrawMaterialContext.captureLive());
                drawFullscreen(commandBuffer, this.packFinalPost.pipeline());
            } finally {
                if (previous != null) {
                    previous.close();
                }
                restorePackRenderState(finalState);
            }
            Renderer.getInstance().endRenderPass(commandBuffer);
            VulkanImage finalColor = this.packFinalFramebuffer.getColorAttachment();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                finalColor.transitionImageLayout(stack, commandBuffer,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            resolveWorldToOutput(commandBuffer, finalColor);
            this.packFinalApplied = true;
        } catch (RuntimeException failure) {
            markPostStageFailure(this.packFinalPost, failure);
            try {
                Renderer.getInstance().endRenderPass(commandBuffer);
                renderIdentityResolve(commandBuffer, this.packPostTargets.activeTarget(0));
            } catch (RuntimeException fallbackFailure) {
                logPackRuntimeFailure("final-identity-resolve", fallbackFailure);
            }
            this.packFinalApplied = true;
            logPackRuntimeFailure("final", failure);
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
        PackRenderState previousState = capturePackRenderState();
        preparePackFullscreenState();
        try (var bindings = bindProgramImages(this.compositePipeline, null, hdrColor,
                DrawMaterialContext.captureLive())) {
            Renderer.getInstance().beginRenderPass(this.compositeRenderPass, this.compositeFramebuffer);
            drawFullscreen(commandBuffer, this.compositePipeline);
            this.currentFramebuffer = this.compositeFramebuffer;
        } finally {
            restorePackRenderState(previousState);
        }
    }


    private void markPostStageFailure(PackPipelines.PackPost post, RuntimeException failure) {
        String reason = runtimeFailureReason("post-stage:" + post.name(), failure);
        markPostStageIdentityBoundary(post, reason);
        if (this.conformanceReport != null) {
            this.conformanceReport.markRuntime(post.name(),
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                    "POST_RUNTIME_STAGE_FAILED:" + post.name() + ":" + reason);
        }
    }

    private static String runtimeFailureReason(String phase, RuntimeException failure) {
        String message = failure.getMessage();
        return "phase=" + phase + ",exception=" + failure.getClass().getName()
                + (message == null || message.isBlank() ? "" : ",message=" + message);
    }

    private boolean shouldLogPackRuntimeFailure(String reason) {
        if (!reason.contains("PACK_TARGET_FRAME_NOT_STARTED")) return true;
        if (this.packTargetFrameNotStartedLogged) return false;
        this.packTargetFrameNotStartedLogged = true;
        return true;
    }

    private void logPackRuntimeFailure(String phase, RuntimeException failure) {
        String reason = runtimeFailureReason(phase, failure);
        if (shouldLogPackRuntimeFailure(reason)) {
            LOGGER.warn("[chimera] pack runtime fallback ({})", reason, failure);
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
        if (shouldLogPackRuntimeFailure(detail)) {
            LOGGER.warn("[chimera] pack {}: fallback=IDENTITY (outputs={}, {})",
                    name, outputTargets, detail);
        }
    }

    /** Disables all pack post seams while retaining objects for safe teardown. */
    private void disablePackPostChain(String reason) {
        this.packTargetResourcesReady = false;
        this.packPostFrameStarted = false;
        restorePackResourceWindow();
        if (PackGeometryContext.active()) PackGeometryContext.close();
        this.packGeometryTerrain = null;
        this.packTranslucentTerrain = null;
        ChimeraTerrainPipelines.setGeometryOverride(null);
        ChimeraTerrainPipelines.setTranslucentOverride(null);
        ChimeraEntityBridge.setEnabled(false);
        ChimeraSkyBridge.setEnabled(false);
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


    private static VulkanImage resolvePackColorInput(
            Integer logicalTarget,
            VulkanImage[] colorInputs,
            VulkanImage fallbackColortex
    ) {
        if (logicalTarget == null) {
            return fallbackColortex;
        }
        if (logicalTarget == 0
                && (colorInputs == null || logicalTarget >= colorInputs.length
                || colorInputs[logicalTarget] == null)) {
            return fallbackColortex;
        }
        if (logicalTarget < 0 || colorInputs == null || logicalTarget >= colorInputs.length) {
            return null;
        }
        return colorInputs[logicalTarget];
    }

    private boolean packDepthInputsAvailable(List<String> samplerNames) {
        if (samplerNames == null) return true;
        for (String sampler : samplerNames) {
            if (sampler != null && sampler.startsWith("depthtex")
                    && this.packDepthTargets.image(sampler) == null) {
                return false;
            }
        }
        for (String sampler : samplerNames) {
            if (("shadowcolor0".equals(sampler) || "shadowcolor1".equals(sampler))
                    && (!this.shadowFrameReady || this.shadowMap.shadowColor(
                    sampler.equals("shadowcolor1") ? 1 : 0) == null)) return false;
        }
        return true;
    }

    private String unavailablePostInputs(PackPipelines.PackPost post) {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        for (int target : post.requiredColorInputs()) {
            if (!this.packPostTargets.currentTargetAvailable(target)) missing.add("colortex" + target);
        }
        for (String sampler : post.samplerNames()) {
            if (!packDepthInputsAvailable(List.of(sampler))) missing.add(sampler);
        }
        return "unavailable inputs " + missing;
    }

    /** Captures opaque depth at the boundary immediately before translucency. */
    public void captureOpaqueDepthBeforeTranslucent() {
        if (this.shadowPassActive || !this.levelPhase || this.hdrFramebuffer == null) {
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
        // The material owner has renderer lifetime: shader-pack replacement
        // must not close it, and final teardown closes it exactly once while
        // the device is still alive. A failed install therefore keeps the
        // previous valid companions instead of destroying them.
        if (this.materialMapOwner != null) this.materialMapOwner.close();
        this.materialMapOwner = null;
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

    /** Renderer-lifetime material owner for the atlas mixin; null before first use. */
    public MaterialMapOwner materialMaps() {
        return this.materialMapOwner;
    }

    /** Creates the material owner once on the render thread; later installs only ensure it. */
    private void ensureMaterialMapOwner() {
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

    /** Releases only pack-owned state so a dimension variant can be rebuilt. */
    private void cleanUpPackVariant() {
        ChimeraEntityBridge.setEnabled(false);
        ChimeraSkyBridge.setEnabled(false);
        // Release the dedicated entity source before its pipeline is destroyed.
        // This keeps any remaining host-side batch cleanup away from a dead
        // pack pipeline and leaves the host format/cache untouched.
        ChimeraEntityBridge.disable();
        ChimeraSkyBridge.disable();
        // Restore the host values before any pack-owned image is destroyed.
        restorePackResourceWindow();
        this.programImages.clear();
        this.loggedShadowSamplerPrograms.clear();
        // Clear every selector that may still refer to a retired pack image.
        // The host rebinds its normal slots during the next draw window.
        SelectorNamespace.clearOwned(slot -> VTextureSelector.bindTexture(slot, null));
        ChimeraTextureBindingState.reset();
        releasePackShadowView();
        this.shadowMap.cleanUp();
        this.packShadowDepth.cleanUp();
        this.packPostTargets.cleanUp();
        this.packDepthTargets.cleanUp();
        this.packCoverageOwner.close();
        this.packCoverageState.endFrame();
        this.packCoveragePlan = PackCoveragePlan.disabled();
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
        this.packGeometryTerrain = null;
        this.packTranslucentTerrain = null;
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
        if (this.packSkyBasicPipeline != null) this.packSkyBasicPipeline.pipeline().cleanUp();
        if (this.packSkyTexturedPipeline != null) this.packSkyTexturedPipeline.pipeline().cleanUp();
        if (this.packCloudPipeline != null) this.packCloudPipeline.pipeline().cleanUp();
        if (this.packShadowCompute != null) this.packShadowCompute.close();
        this.packShadowCompute = null;
        if (this.packStorageBufferOwner != null) {
            LOGGER.info("[chimera] storage buffers: cleanup planned={} allocated={} initialized={} failed={}",
                    this.packStorageBufferOwner.plannedBytes(),
                    this.packStorageBufferOwner.allocatedBytes(),
                    this.packStorageBufferOwner.initializedBytes(),
                    this.packStorageBufferOwner.failedBytes());
            this.packStorageBufferOwner.close();
        }
        this.packStorageBufferOwner = null;
        this.packStorageInitializationLogged = false;
        this.packTargetFrameNotStartedLogged = false;
        if (this.packResourceOwner != null) this.packResourceOwner.close();
        this.packResourceOwner = null;
        if (this.packAdvancedImageOwner != null) this.packAdvancedImageOwner.close();
        this.packAdvancedImageOwner = null;
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
        this.packBlockPipeline = null;
        this.packDamagedBlockPipeline = null;
        this.packHandPipeline = null;
        this.packHandWaterPipeline = null;
        this.packParticlePipeline = null;
        this.packTranslucentParticlePipeline = null;
        this.packWeatherPipeline = null;
        this.packSkyBasicPipeline = null;
        this.packSkyTexturedPipeline = null;
        this.packCloudPipeline = null;
        this.packCloudsDrawNothing = false;
        this.postChainFamilyBlocked = false;
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
                    packLabel(request.path()), packLabel(previousPath),
                    runtimeFailureReason("pack-change", rebuildFailure));
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
                        runtimeFailureReason("pack-rollback", restoreFailure));
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
                    requested, previous, runtimeFailureReason("dimension-change", rebuildFailure));
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
                        runtimeFailureReason("dimension-restore", restoreFailure));
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
        ChimeraSkyBridge.setEnabled(false);
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
        if (usePackShadowRuntime() && this.shadowMap.isInitialized()) {
            this.packShadowDepth.install();
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
            if (this.packCoveragePlan.enabled() && packCoverageRuntimeEnabled()) {
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
            this.packTargetResourcesReady = this.packPostTargets.configure(this.packTargetGraph);
            this.packPostChainActive = this.packTargetResourcesReady
                    && !this.packTargetGraph.steps().isEmpty()
                    && !this.postChainFamilyBlocked;
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
            this.packPostChainActive = false;
            this.packTargetResourcesReady = false;
            this.packTargetGraph = null;
            this.packFrameSchedule = PackFrameSchedulePlan.empty();
            this.packPostChainRejected = true;
            discardPackPostPipelines();
            if (this.conformanceReport != null) {
                this.conformanceReport.addDeviation("POST_RESOURCE_ALLOCATION_FAILED");
                LOGGER.info("[chimera] conformance {}", this.conformanceReport.toJson());
            }
            logPackRuntimeFailure("post-resource-allocation", e);
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
        // Pack final renders here in GL row order, with the output's formats.
        this.packFinalFramebuffer = new Framebuffer.Builder("chimeraPackFinal", width, height, 1, true)
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
        // The world keeps GL row order for pack screen-space math (KNOW-414).
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
        ensureMaterialMapOwner();
        this.packAdvancedImageOwner = PackAdvancedImageOwner.load(this.packPlan.advancedResources());
        this.packStorageBufferOwner = PackStorageBufferOwner.load(this.packPlan.advancedResources());
        this.packShadowCompute = PackShadowCompute.load(this.packPlan.advancedResources(),
                this.packAdvancedImageOwner, this.packResourceOwner, result.shadersDir(),
                this.packPlan.settingsPreprocessorDefines(),
                this.packPlan.settingsOverriddenNames());
        if (this.packShadowCompute != null && this.packShadowCompute.isInstalled()) {
            this.packAdvancedImageOwner.markComputeInstalled();
            if (this.conformanceReport != null
                    && this.conformanceReport.program("shadowcomp") != null) {
                this.conformanceReport.markRuntime("shadowcomp",
                        ConformanceReport.RuntimeDisposition.INSTALLED,
                        "COMPUTE_NATIVE_INSTALLED");
            }
        } else if (this.conformanceReport != null
                && this.conformanceReport.program("shadowcomp") != null
                && this.conformanceReport.program("shadowcomp").runtime()
                == ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED) {
            this.conformanceReport.markRuntime("shadowcomp",
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                    this.packPlan.advancedResources().capabilityPossible()
                            ? "COMPUTE_PIPELINE_BUILD_FAILED"
                            : "COMPUTE_CAPABILITY_UNAVAILABLE");
        }
        LOGGER.info("[chimera] pack resources: fingerprint={}, declarations={}, deviations={}",
                this.packPlan.resources().fingerprint(),
                this.packPlan.resources().declarations().size(),
                this.packPlan.resources().deviations().size());
        LOGGER.info("[chimera] advanced resources: fingerprint={}, images={}, compute={}, capabilityPossible={}, planned={}, allocated={}, initialized={}, failed={}",
                this.packPlan.advancedResources().fingerprint(),
                this.packPlan.advancedResources().images().size(),
                this.packPlan.advancedResources().computeStages().size(),
                this.packPlan.advancedResources().capabilityPossible(),
                this.packAdvancedImageOwner.plannedImageCount(),
                this.packAdvancedImageOwner.allocatedImageCount(),
                this.packAdvancedImageOwner.initializedImageCount(),
                this.packAdvancedImageOwner.failures().size());
        LOGGER.info("[chimera] storage buffers: declarations={}, bindings={}, dependent={}, allocated={}, failures={}",
                this.packPlan.advancedResources().buffers().size(),
                this.packPlan.advancedResources().storageBuffers().values().stream()
                        .mapToInt(List::size).sum(),
                this.packPlan.advancedResources().bufferDependentPrograms(),
                this.packStorageBufferOwner.allocatedBytes()
                        + "/planned=" + this.packStorageBufferOwner.plannedBytes()
                        + "/initialized=" + this.packStorageBufferOwner.initializedBytes(),
                this.packStorageBufferOwner.failures()
                        + "/failedBytes=" + this.packStorageBufferOwner.failedBytes());
        LOGGER.info("[chimera] advanced graphics producers: {}",
                this.packPlan.advancedResources().graphicsImages().entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue().stream()
                                .map(PackAdvancedResourcePlan.GraphicsImageBinding::symbol)
                                .toList())
                        .toList());
        LOGGER.info("[chimera] advanced compute plan: {}",
                this.packPlan.advancedResources().computeStages().values().stream()
                        .map(value -> value.program() + ":" + value.status() + ":" + value.deviations())
                        .toList());
        LOGGER.info("[chimera] advanced compute: shadowcompInstalled={}",
                this.packShadowCompute != null && this.packShadowCompute.isInstalled());
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
        this.packPostChainRejected = false;
        this.packGeometryPipeline = null;
        this.packGeometrySlots = null;
        restorePackResourceWindow();
        this.packGeometryTerrain = null;
        this.packTranslucentPipeline = null;
        this.packTranslucentSlots = null;
        this.packTranslucentTerrain = null;
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
        this.packSkyBasicPipeline = null;
        this.packSkyTexturedPipeline = null;
        this.packCloudPipeline = null;
        this.packCloudsDrawNothing = false;
        this.postChainFamilyBlocked = false;
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

        // Pack post programs and the host resolve share one fullscreen
        // coordinate contract. Target flips are side-selection decisions,
        // not per-pass UV transforms.
        String fixedVertex = ChimeraShaderLoader.loadSource(
                "chimera_composite/chimera_composite.vsh");
        List<PackProgram> pipelinePrograms = new ArrayList<>(this.packPrograms);
        if (this.packPlan != null) {
            pipelinePrograms.sort(Comparator
                    .comparing((PackProgram value) ->
                            this.packPlan.advancedResources().graphicsImages(value.name()).isEmpty())
                    .thenComparing(PackProgram::name));
        }
        for (PackProgram program : pipelinePrograms) {
            String name = program.name();
            PackProgramPlan programPlan = this.packPlan == null
                    ? null : this.packPlan.program(name);
            boolean reportAllowed = this.conformanceReport == null
                    || this.conformanceReport.shouldAttempt(name);
            boolean planAllowed = this.packPlan == null || this.packPlan.shouldAttempt(name);
            boolean advancedProducer = this.packPlan != null
                    && !this.packPlan.advancedResources().graphicsImages(name).isEmpty();
            boolean advancedAllowed = this.packPlan == null
                    || !this.packPlan.advancedResources().dependentPrograms().contains(name)
                    || (this.packAdvancedImageOwner != null
                    && this.packPlan.advancedResources().capabilityPossible()
                    && this.packAdvancedImageOwner.resourcesAvailable()
                    && this.packShadowCompute != null
                    && this.packShadowCompute.isInstalled()
                    && (advancedProducer || this.packAdvancedImageOwner.producerCandidatesReady()));
            boolean storageAllowed = this.packPlan == null
                    || !this.packPlan.advancedResources().bufferDependentPrograms().contains(name)
                    || (this.packStorageBufferOwner != null
                    && this.packStorageBufferOwner.programAvailable(name));
            if (authoredCloudDrawsNothing(name, programPlan)) {
                this.packCloudsDrawNothing = true;
                markFamilyPipelineInstalled(name, PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT);
                continue;
            }
            if (!reportAllowed || !planAllowed) {
                String reason = "CONTRACT_UNSUPPORTED";
                if (this.packPlan != null && this.packPlan.isProgramDisabled(name)) {
                    reason = "PROGRAM_DISABLED:" + name;
                } else if (this.packPlan != null && this.packPlan.isProgramAlias(name)) {
                    reason = "PROGRAM_ALIAS_RUNTIME_FALLBACK:" + name;
                } else if (this.packPlan != null
                        && !this.packPlan.resources().programAllowed(name)) {
                    reason = this.packPlan.resources().unavailableReason(name);
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
            if (!advancedAllowed) {
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                            "ADVANCED_RESOURCE_BRIDGE_UNAVAILABLE");
                }
                LOGGER.warn("[chimera] pack {}: fallback=IDENTITY (advanced resource bridge unavailable)", name);
                continue;
            }
            if (!storageAllowed) {
                String reason = this.packStorageBufferOwner == null
                        ? "STORAGE_BUFFER_OWNER_UNAVAILABLE"
                        : this.packStorageBufferOwner.failureReason(name);
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, reason);
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
                                : this.packPlan.terrainMaterial(),
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
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
                if (!attachPackStoragePipeline(name, shadow.pipeline(), shadow.imageBindings())) {
                    shadow.pipeline().cleanUp();
                    this.packShadowPipeline = null;
                    continue;
                }
                if (this.packAdvancedImageOwner != null) {
                    // The producer must be available before its terrain
                    // consumer can build. Runtime use remains gated by the
                    // committed producer set and usePackShadowRuntime().
                    this.packAdvancedImageOwner.markGraphicsProducerCandidate(name);
                }
                if (this.conformanceReport != null) {
                    this.conformanceReport.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.INSTALLED, null);
                }
                if (this.packStorageBufferOwner != null) {
                    this.packStorageBufferOwner.markPipelineInstalled(name);
                }
                LOGGER.info("[chimera] pack shadow: ok (samplers={})",
                        Arrays.toString(shadow.samplerSlots()));
                if (TRACE_TRANSITIONS) {
                    LOGGER.info("[chimera] pack shadow converted fragment:\n{}", shadow.convertedFragment());
                }
            } else if (PostTargetPlan.isPostProgramName(name)) {
                PackPipelines.PackPost post = PackPipelines.buildPost(programPlan, fixedVertex,
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
                if (post == null) {
                    if (this.conformanceReport != null) {
                        this.conformanceReport.markRuntime(name,
                                ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                "PIPELINE_BUILD_FAILED");
                    }
                    LOGGER.warn("[chimera] pack {}: fallback=IDENTITY (build failed)", name);
                    continue;
                }
                if (!attachPackStoragePipeline(name, post.pipeline(), post.imageBindings())) {
                    post.pipeline().cleanUp();
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
                if (this.packStorageBufferOwner != null) {
                    this.packStorageBufferOwner.markPipelineInstalled(name);
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
                        false, packGeometryTargetFormat(),
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
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
                this.packGeometryTerrain = terrain;
                this.packGeometryPipeline = terrain.pipeline();
                this.packGeometrySlots = terrain.samplerSlots();
                if (!attachPackStoragePipeline(name, terrain.pipeline(), terrain.imageBindings())) {
                    terrain.pipeline().cleanUp();
                    this.packGeometryTerrain = null;
                    this.packGeometryPipeline = null;
                    this.packGeometrySlots = null;
                    continue;
                }
                if (this.packAdvancedImageOwner != null) {
                    this.packAdvancedImageOwner.commitGraphicsProducer("shadow");
                }
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
                LOGGER.info("[chimera] pack gbuffers_terrain: outputs={} dynamicMrt={}",
                        terrain.outputPlan() == null ? List.of(0) : terrain.outputPlan().targetSlots(),
                        terrain.requiresDynamicAttachments());
            } else if (name.equals("gbuffers_water")) {
                PackPipelines.PackTerrain water = PackPipelines.buildTranslucent(programPlan,
                        ChimeraShaderLoader.loadSource("chimera_terrain/chimera_terrain.vsh"),
                        this.packPlan == null ? TerrainMaterialPlan.legacy()
                                : this.packPlan.terrainMaterial(),
                        false, packGeometryTargetFormat(),
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
                if (water == null) {
                    if (this.conformanceReport != null) {
                        this.conformanceReport.markRuntime(name,
                                ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                                "PIPELINE_BUILD_FAILED");
                    }
                    LOGGER.warn("[chimera] pack gbuffers_water: fallback=IDENTITY (build failed)");
                    continue;
                }
                this.packTranslucentTerrain = water;
                this.packTranslucentPipeline = water.pipeline();
                this.packTranslucentSlots = water.samplerSlots();
                if (!attachPackStoragePipeline(name, water.pipeline(), water.imageBindings())) {
                    water.pipeline().cleanUp();
                    this.packTranslucentTerrain = null;
                    this.packTranslucentPipeline = null;
                    this.packTranslucentSlots = null;
                    continue;
                }
                if (this.packAdvancedImageOwner != null) {
                    this.packAdvancedImageOwner.markGraphicsProducerCandidate(name);
                    this.packAdvancedImageOwner.commitGraphicsProducer(name);
                }
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
                LOGGER.info("[chimera] pack gbuffers_water: outputs={} dynamicMrt={}",
                        water.outputPlan() == null ? List.of(0) : water.outputPlan().targetSlots(),
                        water.requiresDynamicAttachments());
            } else if (name.equals("gbuffers_skybasic") || name.equals("gbuffers_skytextured")) {
                PackPipelines.PackSky sky = PackPipelines.buildSky(programPlan,
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
                if (sky == null) {
                    markFamilyPipelineFallback(name, "SKY_PIPELINE_BUILD_FAILED");
                    continue;
                }
                if (name.equals("gbuffers_skybasic")) {
                    this.packSkyBasicPipeline = sky;
                } else {
                    this.packSkyTexturedPipeline = sky;
                }
                if (!attachPackStoragePipeline(name, sky.pipeline(), sky.imageBindings())) {
                    sky.pipeline().cleanUp();
                    if (name.equals("gbuffers_skybasic")) this.packSkyBasicPipeline = null;
                    else this.packSkyTexturedPipeline = null;
                    continue;
                }
                markFamilyPipelineInstalled(name, "SKY_PIPELINE_INSTALLED");
                LOGGER.info("[chimera] pack {}: ok (sky pipeline installed, samplers={})",
                        name, Arrays.toString(sky.samplerSlots()));
            } else if (name.equals("gbuffers_clouds")) {
                PackPipelines.PackSky cloud = PackPipelines.buildCloud(programPlan,
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
                if (cloud == null) {
                    markFamilyPipelineFallback(name, "CLOUD_PIPELINE_BUILD_FAILED");
                    continue;
                }
                this.packCloudPipeline = cloud;
                if (!attachPackStoragePipeline(name, cloud.pipeline(), cloud.imageBindings())) {
                    cloud.pipeline().cleanUp();
                    this.packCloudPipeline = null;
                    continue;
                }
                markFamilyPipelineInstalled(name, "CLOUD_PIPELINE_INSTALLED");
                LOGGER.info("[chimera] pack gbuffers_clouds: ok (cloud pipeline installed, samplers={})",
                        Arrays.toString(cloud.samplerSlots()));
            } else if (name.equals("gbuffers_entities")) {
                PackPipelines.PackEntity entity = PackPipelines.buildEntity(programPlan,
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
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
                if (!attachPackStoragePipeline(name, entity.pipeline(), entity.imageBindings())) {
                    entity.pipeline().cleanUp();
                    this.packEntityPipeline = null;
                    continue;
                }
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
                PackPipelines.PackEntity entity = PackPipelines.buildEntityFamily(programPlan,
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
                if (entity == null) {
                    markFamilyPipelineFallback(name, "ENTITY_PIPELINE_BUILD_FAILED");
                    continue;
                }
                if (name.equals("gbuffers_entities_translucent")) {
                    this.packTranslucentEntityPipeline = entity;
                } else {
                    this.packGlowingEntityPipeline = entity;
                }
                if (!attachPackStoragePipeline(name, entity.pipeline(), entity.imageBindings())) {
                    entity.pipeline().cleanUp();
                    if (name.equals("gbuffers_entities_translucent")) {
                        this.packTranslucentEntityPipeline = null;
                    } else {
                        this.packGlowingEntityPipeline = null;
                    }
                    continue;
                }
                markFamilyPipelineInstalled(name, name.equals("gbuffers_entities_translucent")
                        ? "TRANSLUCENT_ENTITY_INSTALLED" : "GLOWING_ENTITY_INSTALLED");
            } else if (name.equals("gbuffers_block")) {
                PackPipelines.PackEntity block = PackPipelines.buildBlock(programPlan,
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
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
                if (!attachPackStoragePipeline(name, block.pipeline(), block.imageBindings())) {
                    block.pipeline().cleanUp();
                    this.packBlockPipeline = null;
                    continue;
                }
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
            } else if (name.equals("gbuffers_hand") || FamilyAdapterRegistry.isHandFamily(name)) {
                // The current hand hook surrounds the late host first-person
                // draw. Installing a pack hand pipeline here would execute it
                // outside the two-phase world schedule and could erase or
                // misplace the host hand. Keep host hand authoritative until a
                // true two-phase hand adapter owns the draw schedule.
                markFamilyPipelineFallback(name, "HAND_SCHEDULE_FALLBACK");
            } else if (name.equals("gbuffers_particles")) {
                PackPipelines.PackParticle particle = PackPipelines.buildParticle(programPlan,
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
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
                if (!attachPackStoragePipeline(name, particle.pipeline(), particle.imageBindings())) {
                    particle.pipeline().cleanUp();
                    this.packParticlePipeline = null;
                    continue;
                }
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
                PackPipelines.PackParticle particle = PackPipelines.buildParticleFamily(programPlan,
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
                if (particle == null) {
                    markFamilyPipelineFallback(name, "PARTICLE_PIPELINE_BUILD_FAILED");
                    continue;
                }
                this.packTranslucentParticlePipeline = particle;
                if (!attachPackStoragePipeline(name, particle.pipeline(), particle.imageBindings())) {
                    particle.pipeline().cleanUp();
                    this.packTranslucentParticlePipeline = null;
                    continue;
                }
                markFamilyPipelineInstalled(name, "PARTICLE_TRANSLUCENT_INSTALLED");
            } else if (FamilyAdapterRegistry.isWeatherFamily(name)) {
                PackPipelines.PackParticle weather = PackPipelines.buildParticleFamily(programPlan,
                        this.packPlan == null ? PackAdvancedResourcePlan.empty()
                                : this.packPlan.advancedResources(), this.packStorageBufferOwner);
                if (weather == null) {
                    markFamilyPipelineFallback(name, "WEATHER_PIPELINE_BUILD_FAILED");
                    continue;
                }
                this.packWeatherPipeline = weather;
                if (!attachPackStoragePipeline(name, weather.pipeline(), weather.imageBindings())) {
                    weather.pipeline().cleanUp();
                    this.packWeatherPipeline = null;
                    continue;
                }
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
        LOGGER.info("[chimera] family bridge: targets entity={}, block={}",
                this.packEntityPipeline == null ? "none"
                        : this.packEntityPipeline.outputPlan() == null
                        ? List.of(0) : this.packEntityPipeline.outputPlan().targetSlots(),
                this.packBlockPipeline == null ? "none"
                        :                 this.packBlockPipeline.outputPlan() == null
                        ? List.of(0) : this.packBlockPipeline.outputPlan().targetSlots());
        enforceMaskCriticalFamilyContract();
        ChimeraSkyBridge.install(this.packSkyBasicPipeline, this.packSkyTexturedPipeline,
                this.packCloudPipeline, this.packCloudsDrawNothing);
        this.packPostStages.sort(Comparator.comparing(
                PackPipelines.PackPost::name, PostTargetPlan.programComparator()));
        buildPackPostExecutionPlan();
        if (this.packCoveragePlan.enabled()) {
            LOGGER.info("[chimera] scene seed: direct HDR composition active; coverage merge deferred families={}",
                    this.packCoveragePlan.families());
        }
        if (this.packShadowPipeline != null && !usePackShadowRuntime()) {
            if (this.conformanceReport != null) {
                this.conformanceReport.markRuntime("shadow",
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "SHADOW_CONSUMER_UNAVAILABLE");
                this.conformanceReport.addDeviation("SHADOW_CONSUMER_UNAVAILABLE");
            }
            LOGGER.warn("[chimera] pack shadow: fixed host fallback (terrain consumer unavailable)");
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
     * Mask-critical family contract (DOC-338 repair 4). Entity and block
     * draws cover world pixels the pack temporal pass classifies by
     * auxiliary material metadata. When either authored family stays
     * host-fallback, its pixels would carry stale metadata under pack
     * post, so the post chain is rejected loudly instead of running a
     * mixed frame. Terrain and installed families keep rendering and
     * presentation falls back to the identity path. Loud by design: mixed
     * frames ghost, silent fallbacks lie.
     */
    private void enforceMaskCriticalFamilyContract() {
        List<String> missing = maskCriticalFamilyFallback();
        if (missing.isEmpty()) {
            LOGGER.info("[chimera] family bridge: mask-critical contract holds "
                    + "(entity and block installed or not authored)");
            return;
        }
        this.postChainFamilyBlocked = true;
        this.packPostChainActive = false;
        if (this.conformanceReport != null) {
            this.conformanceReport.addDeviation(
                    "POST_CHAIN_FAMILY_INCOMPLETE:" + String.join(",", missing));
            for (PackPipelines.PackPost post : this.packPostStages) {
                this.conformanceReport.markRuntime(post.name(),
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "POST_CHAIN_FAMILY_INCOMPLETE");
            }
            if (this.packFinalPost != null) {
                this.conformanceReport.markRuntime(this.packFinalPost.name(),
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "POST_CHAIN_FAMILY_INCOMPLETE");
            }
        }
        LOGGER.warn("[chimera] pack post chain rejected: mask-critical families fallback ({}); "
                        + "terrain renders, presentation uses the identity path",
                String.join(",", missing));
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
        if (this.packPlan.program("gbuffers_entities") != null && this.packEntityPipeline == null) {
            missing.add("gbuffers_entities");
        }
        if (this.packPlan.program("gbuffers_block") != null && this.packBlockPipeline == null) {
            missing.add("gbuffers_block");
        }
        return missing;
    }

    public boolean hasProgramImages(GraphicsPipeline pipeline) { return this.programImages.containsKey(pipeline); }

    public boolean packRuntimeRejected() { return this.packPostChainRejected; }

    /** True while a shader pack session is loaded (identity mode has none). */
    public boolean packLoaded() { return this.packPlan != null; }

    public void recordShadowDraw() {
        if (this.shadowPassActive && usePackShadowRuntime() && this.packStorageBufferOwner != null) {
            this.packStorageBufferOwner.recordDraw("shadow", VK_SHADER_STAGE_VERTEX_BIT);
        }
    }

    public ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindDescriptorImages(GraphicsPipeline pipeline) {
        ProgramImages program = this.programImages.get(pipeline);
        // Fullscreen and terrain seams already hold a transaction with their exact graph inputs.
        if (program == null || PostTargetPlan.isPostProgramName(program.name())
                || (!this.shadowPassActive && this.packGeometryBindings != null)) return null;
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
                int hostSlot = entry.resourceKey().equals("lightmap") ? 2 : 0;
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
                case COLOR_TARGET -> resolvePackColorInput(PackResourcePlan.targetIndex(entry.resourceKey()), colors, fallback);
                case DEPTH_TARGET -> {
                    VulkanImage depth = this.packDepthTargets.image(entry.resourceKey());
                    yield depth == null && entry.resourceKey().equals("depthtex0") && this.hdrFramebuffer != null
                            ? this.hdrFramebuffer.getDepthAttachment() : depth;
                }
                case SHADOW_DEPTH -> this.shadowMap.getShadowFramebuffer() == null
                        ? null : this.shadowMap.getShadowFramebuffer().getDepthAttachment();
                case SHADOW_COLOR -> this.shadowMap.shadowColor(entry.resourceKey().equals("shadowcolor1") ? 1 : 0);
                case PACK_TEXTURE -> this.packResourceOwner == null ? null : this.packResourceOwner.image(entry.resourceKey());
                case ADVANCED_IMAGE -> this.packAdvancedImageOwner == null ? null : this.packAdvancedImageOwner.image(entry.resourceKey());
                case MATERIAL_MAP -> throw new AssertionError();
                case HOST_TEXTURE -> throw new AssertionError();
            };
            return new ChimeraTextureBindingState.Snapshot(image,
                    image == null ? null : shadowSamplerFor(bound, entry, image));
        });
    }

    /**
     * Sampler one manifest entry binds.
     *
     * <p>The converted GLSL decides how a program reads a shadow texture: a
     * {@code sampler2DShadow} lookup is a depth comparison and needs the
     * compare-enabled sampler, while a program that declares the same texture as
     * {@code sampler2D} reads it directly and keeps the image's own sampler.
     * VulkanMod writes the recorded per-slot pair straight into the descriptor,
     * so one reduced image serves both programs without mutating it per draw.</p>
     */
    private long shadowSamplerFor(ProgramImages program, ProgramImageBindingManifest.Entry entry,
            VulkanImage image) {
        boolean compareRequired = entry.kind() == ProgramImageBindingManifest.Kind.SHADOW_DEPTH
                && program.shadowSamplerSymbols().contains(entry.sourceSymbol());
        long sampler = this.packShadowDepth.samplerFor(image, compareRequired);
        if (compareRequired && this.loggedShadowSamplerPrograms.add(program.name())) {
            LOGGER.info("[chimera] pack shadow sampler: program={} symbol={} slot={} image={} "
                            + "sampler={} compareOp=LESS_OR_EQUAL",
                    program.name(), entry.sourceSymbol(), entry.slot(), image.getId(), sampler);
        }
        return sampler;
    }

    /**
     * Shadow-sampler symbols of one program, taken from the plan's canonical
     * interface. The set is empty for programs that never compare.
     */
    private Set<String> shadowSamplerSymbols(String program) {
        if (this.packPlan == null) {
            return Set.of();
        }
        var plan = this.packPlan.program(program);
        if (plan == null || plan.interfacePlan() == null) {
            return Set.of();
        }
        Set<String> symbols = new java.util.HashSet<>();
        for (var sampler : plan.interfacePlan().samplers()) {
            if (PackShadowDepth.requiresCompareSampler(sampler.glslType())) {
                symbols.add(sampler.name());
            }
        }
        return Set.copyOf(symbols);
    }

    public ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindProgramImages(GraphicsPipeline pipeline) {
        return bindProgramImages(pipeline, this.packPostTargets.sourceImages(),
                this.hdrFramebuffer == null ? null : this.hdrFramebuffer.getColorAttachment(),
                DrawMaterialContext.captureLive());
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

    private boolean attachPackStoragePipeline(String program, GraphicsPipeline pipeline,
            ProgramImageBindingManifest manifest) {
        this.programImages.put(pipeline, new ProgramImages(program, manifest,
                shadowSamplerSymbols(program)));
        if (this.packStorageBufferOwner == null
                || this.packPlan == null
                || this.packPlan.advancedResources().storageBuffers(program).isEmpty()) {
            return true;
        }
        if (this.packStorageBufferOwner.attachPipeline(pipeline, program)) {
            return true;
        }
        String reason = this.packStorageBufferOwner.failureReason(program);
        if (this.conformanceReport != null) {
            this.conformanceReport.markRuntime(program,
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                    reason);
        }
        LOGGER.warn("[chimera] pack {}: fallback=IDENTITY ({})", program, reason);
        return false;
    }

    /** The pack's own resolved cloud program is active and provably draws nothing. */
    private boolean authoredCloudDrawsNothing(String name, PackProgramPlan programPlan) {
        return programPlan != null && programPlan.cloudDrawsNothing()
                && this.packPlan != null
                && !this.packPlan.isProgramDisabled(name)
                && !this.packPlan.isProgramAlias(name)
                && this.packPlan.resources().programAllowed(name);
    }

    private void markFamilyPipelineInstalled(String name, String deviation) {
        if (this.conformanceReport != null) {
            this.conformanceReport.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED, deviation);
        }
        if (this.packStorageBufferOwner != null) {
            this.packStorageBufferOwner.markPipelineInstalled(name);
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
        VulkanImage shadowDepth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
        if (this.packShadowView == null || shadowDepth.getId() != this.packShadowSourceId) {
            // A recreated shadow attachment retires the old view and texture.
            // This view is a sampler view, not a main color view, so it must
            // never enter the main-target alias set.
            releaseOldShadowView();
            VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
            VkGpuTexture texture = device.gpuTextureFromVulkanImage(shadowDepth);
            GpuTextureView view = device.createTextureView(texture);
            this.packShadowTexture = texture;
            this.packShadowView = view;
            this.packShadowSourceId = shadowDepth.getId();
        }
        VRenderSystem.setShaderTexture(5, this.packShadowView);
    }

    /**
     * Pack shadow depth is only valid when the matching pack terrain adapter
     * consumes it.  A pack shadow caster paired with host terrain has no
     * compatible depth semantics, so retain VulkanMod's fixed shadow path.
     */
    private boolean usePackShadowRuntime() {
        return !this.packPostChainRejected && this.packShadowPipeline != null && this.packGeometryPipeline != null;
    }

    /** Makes newly created shadow images valid shader inputs exactly once. */
    private boolean prepareShadowForSampling(double cameraX, double cameraY, double cameraZ) {
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
        try {
            this.shadowMap.initializeSampling(commandBuffer);
            if (!this.shadowMap.hasMapSnapshot()) {
                this.shadowMap.seedClearedMap(cameraX, cameraY, cameraZ);
            }
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
        VulkanImage color1 = this.shadowMap.shadowColor(1);
        return color != null && color1 != null && depth != null
                && color.getId() != 0L && color1.getId() != 0L && depth.getId() != 0L;
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
        if (this.shadowCutoutDispositionLogged || !usePackShadowRuntime()) {
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
        this.currentFramebuffer = null;
        this.hdrRenderPass = null;
        this.hdrAuxRenderPass = null;
        this.hdrAuxClearDepthRenderPass = null;
        this.compositeRenderPass = null;
        this.packFinalRenderPass = null;
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
