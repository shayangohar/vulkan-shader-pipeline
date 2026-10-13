package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.chimera.shaderpack.ShadowCasterDirectives;
import net.chimera.shaderpack.ShadowCullingPlan;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The shadow pass of the main pass: records the pack's (or the host's fixed) shadow map before the
 * opaque terrain samples it, as Iris renders shadows first, with the entity casters and the
 * translucent layer, and keeps the map's sampling state and views.
 */
final class PackShadowPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    /** Iris's AlphaTests.ONE_TENTH_ALPHA, used for solid and cutout shadow terrain. */
    static final float SHADOW_CUTOUT_ALPHA = 0.1F;

    /** Set once the caster pass failed; the shadow map keeps its terrain for the rest of the session. */
    boolean shadowCastersFailed;

    /** Iris's shadow culling for this pack session (shadow.culling and the shadow distances). */
    ShadowCullingPlan shadowCulling;

    /** GL-registry slot-5 view of the shadow depth, for pack geometry sampling (shadowtex0). */
    GpuTexture packShadowTexture;
    GpuTextureView packShadowView;
    long packShadowSourceId;
    ChimeraShadowMap shadowMap = new ChimeraShadowMap();
    final PackShadowDepth packShadowDepth = new PackShadowDepth();

    /** Programs whose routed shadow sampler was already reported once. */
    final Set<String> loggedShadowSamplerPrograms = new HashSet<>();

    /**
     * While true, rebindMainTarget() opens the SHADOW render pass instead of
     * the current main target. renderSectionLayer always rebinds to the main
     * target, so this redirects terrain into the shadow map.
     */
    boolean shadowPassActive;

    /**
     * Armed at level-segment HEAD, consumed at the opaque layer's head
     * (WorldRendererMixin): guarantees exactly one shadow segment per frame.
     */
    boolean shadowPending;

    /** False when the shadow resource could not be safely prepared this frame. */
    boolean shadowFrameReady;
    boolean shadowSegmentObserved;

    /** Prevents an invalid shadow resource from producing per-frame warnings. */
    boolean shadowTransitionFallbackLogged;

    /** Runtime report/log marker for the host's unique opaque/cutout policy. */
    boolean shadowCutoutDispositionLogged;

    PackShadowPass(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /**
     * Records the shadow pass: re-renders terrain sections inside the light
     * volume, with every facing, into the shadow framebuffer, then binds the shadow texture
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
        ShadowFrame frame = new ShadowFrame(cmd, shadowColor, shadowDepth, packShadow, projection,
                cameraX, cameraY, cameraZ);
        ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> shadowBindings = null;
        try {
            // The receiver keeps sampling the previously committed map until
            // this pass finishes. Only the pack caster uses the legacy range.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ChimeraMainPass.trace("shadowPre", "shadowColor", shadowColor,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                ChimeraMainPass.trace("shadowPre", "shadowDepth", shadowDepth,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                shadowColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                shadowDepth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }

            try {
                if (packShadow) {
                    shadowBindings = bindPackShadowImages(cmd);
                }
                beginShadowDraw(frame);
                drawShadowTerrain(frame);
                if (packShadow) {
                    renderShadowCasters(cameraX, cameraY, cameraZ);
                    // shadowtex1 is the opaque, cutout and entity casters only;
                    // the translucent layer below reaches shadowtex0 alone, and
                    // packs tint through shadowcolor where the two differ.
                    if (Renderer.getInstance().getBoundRenderPass() != null) {
                        Renderer.getInstance().endRenderPass(cmd);
                    }
                    this.shadowMap.captureOpaqueDepth(cmd);
                    PackUniformProvider.updateDrawAlphaReference(0.0F);
                    WorldRenderer.getInstance().renderSectionLayer(
                            TerrainRenderType.TRANSLUCENT, cameraX, cameraY, cameraZ,
                            this.shadowMap.getLightView(), projection);
                }
            } finally {
                endShadowRecording(frame, shadowBindings);
            }
            publishShadowMap(frame);
        } catch (RuntimeException failure) {
            recoverShadowMap(frame, failure);
        } finally {
            this.shadowPassActive = false;
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
        }
    }

    /** One shadow map recording: its command buffer, attachments, caster path, projection and camera. */
    private record ShadowFrame(VkCommandBuffer cmd, VulkanImage color, VulkanImage depth, boolean pack,
            Matrix4f projection, double cameraX, double cameraY, double cameraZ) {}

    /** Readies the frame's advanced images and binds the pack shadow program's images for its draws. */
    private ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindPackShadowImages(
            VkCommandBuffer cmd) {
        if (pass.packAdvancedImageOwner != null
                && pass.packAdvancedImageOwner.capabilityEnabled()) {
            pass.packAdvancedImageOwner.prepareFrame(cmd);
        }
        pass.prepareProgramImages(pass.pipelines.shadowPipeline);
        return pass.images.bindProgramImages(pass.pipelines.shadowPipeline, null, null,
                DrawMaterialContext.captureLive());
    }

    /**
     * Redirects terrain into the shadow pass, with the light's matrices and its sections; the pack
     * caster also draws inside its shadow geometry scope.
     */
    private void beginShadowDraw(ShadowFrame frame) {
        boolean packShadow = frame.pack();
        if (packShadow) {
            PackGeometryContext.beginShadow(this.shadowMap.shadowColors(), frame.depth());
            // Pack receivers read the map with GL's v = ndc * 0.5 + 0.5.
            // The fixed host receiver compensates for VulkanMod's flip.
            ChimeraRasterOrientation.markGlOriented(this.shadowMap.getShadowRenderPass());
        }
        this.shadowPassActive = true;
        PipelineManager.setShaderGetter(rt -> {
            if (packShadow) {
                VRenderSystem.disableBlend();
                return pass.pipelines.shadowPipeline;
            }
            return this.shadowMap.getShadowPipeline();
        });
        if (packShadow) {
            // This scoped pair is exactly the one used by MVP and
            // ftransform() in the pack shadow draw.
            this.shadowMap.publishCurrentDrawState();
        }
        VRenderSystem.applyProjectionMatrix(frame.projection());
        VRenderSystem.applyModelViewMatrix(this.shadowMap.getLightView());
        VRenderSystem.calculateMVP();
        beginShadowSections(frame.cameraX(), frame.cameraY(), frame.cameraZ());
        // Iris tests solid and cutout shadow casters at alpha 0.1 and
        // translucent ones not at all; foliage quads otherwise cast
        // their whole square.
        if (packShadow) {
            PackUniformProvider.updateDrawAlphaReference(SHADOW_CUTOUT_ALPHA);
        }
    }

    /** Draws the opaque terrain layer, then the cutout layer unless the host merged it into opaque. */
    private void drawShadowTerrain(ShadowFrame frame) {
        TerrainRenderType opaqueType = TerrainRenderType.getRemapped(TerrainRenderType.SOLID);
        WorldRenderer.getInstance().renderSectionLayer(
                opaqueType,
                frame.cameraX(), frame.cameraY(), frame.cameraZ(),
                this.shadowMap.getLightView(), frame.projection());

        TerrainRenderType cutoutType = TerrainRenderType.getRemapped(TerrainRenderType.CUTOUT);
        recordShadowCutoutDisposition(cutoutType == opaqueType);
        if (cutoutType != opaqueType) {
            WorldRenderer.getInstance().renderSectionLayer(
                    cutoutType, frame.cameraX(), frame.cameraY(), frame.cameraZ(),
                    this.shadowMap.getLightView(), frame.projection());
        }
    }

    /** Closes the shadow sections, the shadow pass, the pack's shadow geometry scope and its bindings. */
    private void endShadowRecording(ShadowFrame frame,
            ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> shadowBindings) {
        ShadowSectionQueue.end();
        if (Renderer.getInstance().getBoundRenderPass() != null) {
            Renderer.getInstance().endRenderPass(frame.cmd());
        }
        if (frame.pack()) {
            PackGeometryContext.close();
            ChimeraRasterOrientation.unmark(this.shadowMap.getShadowRenderPass());
        }
        this.shadowPassActive = false;
        if (shadowBindings != null) shadowBindings.close();
    }

    /**
     * Makes the recorded map sampleable, runs shadowcomp over it, then publishes the pack map or
     * clears the pack map state for the fixed host map, and binds it for the receivers.
     */
    private void publishShadowMap(ShadowFrame frame) {
        VkCommandBuffer cmd = frame.cmd();
        if (frame.pack() && pass.packStorageBufferOwner != null) {
            pass.packStorageBufferOwner.prepareForUse(cmd);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ChimeraMainPass.trace("shadowPost", "shadowColor", frame.color(),
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            ChimeraMainPass.trace("shadowPost", "shadowDepth", frame.depth(),
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            frame.color().transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (frame.pack()) {
                this.shadowMap.shadowColor(1).transitionImageLayout(stack, cmd,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            frame.depth().transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        dispatchShadowCompute(cmd);
        this.shadowMap.markInitialSamplingLayoutReady();
        if (frame.pack()) {
            this.shadowMap.commitRenderedMap(frame.cameraX(), frame.cameraY(), frame.cameraZ());
            this.shadowMap.publishMapState(frame.cameraX(), frame.cameraY(), frame.cameraZ());
        } else {
            this.shadowMap.invalidatePackMapSnapshot();
            PackUniformProvider.clearShadowState();
        }

        this.shadowMap.bindShadowTexture();
        maintainPackShadowGoal();
    }

    /** Runs the pack's shadowcomp over the new map when its advanced images are live. */
    private void dispatchShadowCompute(VkCommandBuffer cmd) {
        if (pass.packAdvancedImageOwner != null
                && pass.packAdvancedImageOwner.capabilityEnabled()
                && pass.packShadowCompute != null && pass.packShadowCompute.isInstalled()) {
            if (pass.packShadowCompute.dispatch(cmd)) {
                if (pass.packStorageBufferOwner != null) pass.packStorageBufferOwner.recordDispatch();
            } else LOGGER.warn("[chimera] shadowcomp dispatch failed; dependent advanced resources use fallback");
        }
    }

    /**
     * After a failed shadow write, clears the map to the all-lit fallback; when that fails too,
     * the frame's shadow inputs stay unavailable.
     */
    private void recoverShadowMap(ShadowFrame frame, RuntimeException failure) {
        VkCommandBuffer cmd = frame.cmd();
        double cameraX = frame.cameraX();
        double cameraY = frame.cameraY();
        double cameraZ = frame.cameraZ();
        this.shadowMap.invalidateMapSnapshot();
        this.shadowFrameReady = false;
        if (pass.conformanceReport != null) {
            pass.conformanceReport.addDeviation("SHADOW_MAP_WRITE_FAILED");
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
    }

    /**
     * Iris ShadowRenderer: entities and block entities cast after the opaque terrain and
     * before the translucent layer, with shadow_entities. A failure leaves the terrain map
     * intact and turns the casters off for the session.
     */
    void renderShadowCasters(double cameraX, double cameraY, double cameraZ) {
        if (pass.pipelines.shadowEntities == null || this.shadowCastersFailed
                || pass.packPlan == null || pass.packConfig == null) {
            return;
        }
        ShadowCasterDirectives directives = pass.packPlan.shadowCasters();
        if (!directives.anyEntities() && !directives.anyBlockEntities()) return;
        try {
            pass.prepareProgramImages(pass.pipelines.shadowEntities.pipeline());
            ChimeraShadowCasters.render(cameraX, cameraY, cameraZ, this.shadowMap.getLightView(),
                    this.shadowMap.getPackLightProjection(), directives, shadowCullingFrustum(true));
        } catch (RuntimeException failure) {
            this.shadowCastersFailed = true;
            if (pass.conformanceReport != null) {
                pass.conformanceReport.addDeviation("SHADOW_ENTITY_DRAW_FAILED");
            }
            LOGGER.warn("[chimera] shadow casters failed; entities cast no shadow this session", failure);
        }
    }

    /**
     * Chooses the shadow pass's sections from the light volume and Iris's terrain
     * shadow frustum. The pack projection carries GL clip depth, which the frustum
     * test expects; both projections cover the same volume.
     */
    void beginShadowSections(double cameraX, double cameraY, double cameraZ) {
        if (!(WorldRenderer.getInstance() instanceof ChimeraSectionGridAccess access)) return;
        var grid = access.chimera$sectionGrid();
        if (grid == null) return;
        ShadowSectionQueue.begin(grid, grid.getChunkAreaManager().size,
                this.shadowMap.getPackLightProjection(), this.shadowMap.getLightView(),
                shadowCullingFrustum(false), cameraX, cameraY, cameraZ);
    }

    /**
     * This frame's Iris shadow frustum, terrain or entity: built from the camera's view and the
     * shadow light, with the render distance Iris reads each frame.
     */
    ShadowCullingFrustum shadowCullingFrustum(boolean entities) {
        if (this.shadowCulling == null) return ShadowCullingFrustum.none();
        int renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        ShadowCullingPlan.Culler culler = entities
                ? this.shadowCulling.entities(renderDistance) : this.shadowCulling.terrain(renderDistance);
        return new ShadowCullingFrustum(culler, PackUniformProvider.currentCameraViewProjection(new Matrix4f()),
                PackUniformProvider.currentSunLightVector());
    }

    /**
     * Renders the shadow map at the head of the opaque section layer, before
     * any terrain draw samples it, as Iris renders shadows first. Closes the
     * HDR pass opened at HEAD, draws the casters from the light's perspective
     * into the shadow map, then reopens the HDR pass (load ops) so the
     * terrain layers draw into it.
     *
     * Timing matters: by this point cullTerrain has updated VulkanMod's
     * section graph for this frame. The shadow phase draws its own sections
     * chosen from the light volume ({@link ShadowSectionQueue}), not the
     * camera's culled queues.
     */
    public void renderShadowSegment(double cameraX, double cameraY, double cameraZ) {
        if (!this.shadowMap.isInitialized() || !this.shadowFrameReady || !shadowAttachmentsLive()) {
            return;
        }
        if (!this.shadowSegmentObserved) {
            this.shadowSegmentObserved = true;
            LOGGER.info("[chimera] shadow segment active; advanced compute dispatch is eligible");
        }
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        VkCommandBuffer cmd = Renderer.getCommandBuffer();
        InterruptedGeometry interrupted = InterruptedGeometry.capture();
        Renderer.getInstance().endRenderPass(cmd);
        if (interrupted.coverage() || interrupted.terrain()) {
            // The nested shadow render must not be intercepted by the pack
            // geometry dynamic-rendering bridge. Otherwise its 2048 viewport
            // renders into the world target and its clear load-op erases the
            // terrain that was just drawn.
            PackGeometryContext.close();
        }
        try {
            this.renderShadowMap(cameraX, cameraY, cameraZ);
        } finally {
            resumeAfterShadow(interrupted);
        }
    }

    /** The pack geometry scope the shadow segment interrupts, to reopen once the map is drawn. */
    private record InterruptedGeometry(boolean coverage, boolean terrain, List<VulkanImage> colors,
            VulkanImage depth) {
        static InterruptedGeometry capture() {
            return new InterruptedGeometry(PackGeometryContext.coverageActive(), PackGeometryContext.geometryActive(),
                    PackGeometryContext.colors(), PackGeometryContext.depth());
        }
    }

    /** Reopens the interrupted pack terrain or coverage scope, or else the plain HDR pass. */
    private void resumeAfterShadow(InterruptedGeometry interrupted) {
        List<VulkanImage> resumeColors = interrupted.colors();
        if (interrupted.terrain() && !resumeColors.isEmpty() && interrupted.depth() != null
                && pass.targets.hdrFramebuffer != null) {
            PackGeometryContext.beginGeometry(resumeColors, interrupted.depth());
            pass.rebindMainTarget();
        } else if (interrupted.coverage() && pass.targets.hdrFramebuffer != null) {
            // Continue an interrupted coverage window on the same pack
            // target; its owner closes and commits it as usual.
            PackGeometryContext.beginPreserving(
                    resumeColors.get(0),
                    resumeColors.size() > 1 ? resumeColors.get(1) : pass.coverage.packCoverageOwner.image(),
                    pass.targets.hdrFramebuffer.getDepthAttachment());
            pass.rebindMainTarget();
        } else {
            // Reopen the HDR pass (load ops) for the terrain layers.
            pass.rebindMainTarget();
        }
    }

    /**
     * Readies the frame's shadow map at level-segment HEAD: computes the light once before any
     * terrain draw (the shadow pass at the opaque layer's head reuses this exact state), arms the
     * shadow segment, and binds the map for the terrain shaders' first draw.
     */
    void beginShadowFrame(double cameraX, double cameraY, double cameraZ) {
        if (this.shadowMap.isInitialized()) {
            this.shadowMap.updateLight(PackUniformProvider.currentShadowLightRotation(),
                    cameraX, cameraY, cameraZ);
            this.shadowFrameReady = prepareShadowForSampling(cameraX, cameraY, cameraZ);
            if (this.shadowFrameReady && this.shadowMap.hasMapSnapshot()) {
                this.shadowMap.publishMapState(cameraX, cameraY, cameraZ);
            } else {
                PackUniformProvider.clearShadowState();
            }
        }

        // Arm this frame's shadow segment; consumed at the opaque layer's head.
        this.shadowPending = this.shadowFrameReady;

        // Terrain shaders sample the shadow map from their first draw.
        if (this.shadowFrameReady) {
            this.shadowMap.bindShadowTexture();
            maintainPackShadowGoal();
        }
    }

    /** Consumes the frame's shadow-pending flag; true at most once per level segment. */
    public boolean consumeShadowPending() {
        boolean p = this.shadowPending;
        this.shadowPending = false;
        return p;
    }

    public void recordShadowDraw() {
        if (this.shadowPassActive && usePackShadowRuntime() && pass.packStorageBufferOwner != null) {
            pass.packStorageBufferOwner.recordDraw("shadow", VK_SHADER_STAGE_VERTEX_BIT);
        }
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
    long shadowSamplerFor(ChimeraMainPass.ProgramImages program, ProgramImageBindingManifest.Entry entry,
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
    Set<String> shadowSamplerSymbols(String program) {
        if (pass.packPlan == null) {
            return Set.of();
        }
        var plan = pass.packPlan.program(program);
        if (plan == null || plan.interfacePlan() == null) {
            return Set.of();
        }
        Set<String> symbols = new HashSet<>();
        for (var sampler : plan.interfacePlan().samplers()) {
            if (PackShadowDepth.requiresCompareSampler(sampler.glslType())) {
                symbols.add(sampler.name());
            }
        }
        return Set.copyOf(symbols);
    }

    /**
     * Keeps GL-registry slot 5 (the sampler slot pack geometry declares as
     * shadowtex0) pointing at the shadow map's depth. Terrain draws bind
     * sampler slots from that registry (VTextureSelector.bindShaderTextures),
     * unlike post seams which bind through boundTextures; the view is rebuilt
     * only when the shadow attachment is recreated (id change).
     */
    void maintainPackShadowGoal() {
        boolean geometryUsesShadow = pass.pipelines.terrainPipeline != null
                && pass.pipelines.terrainSlots != null && ChimeraMainPass.containsSlot(pass.pipelines.terrainSlots, 5);
        boolean translucentUsesShadow = pass.pipelines.waterPipeline != null
                && pass.pipelines.waterSlots != null && ChimeraMainPass.containsSlot(pass.pipelines.waterSlots, 5);
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
    boolean usePackShadowRuntime() {
        return !pass.packPostChainRejected && pass.pipelines.shadowPipeline != null
                && pass.pipelines.terrainPipeline != null;
    }

    /**
     * True while the pack renders its own shadow map. Iris then suppresses vanilla entity
     * ground shadows (IrisRenderingPipeline.shouldDisableVanillaEntityShadows: shadowRenderer != null).
     */
    public boolean packShadowPassActive() {
        return usePackShadowRuntime();
    }

    /** Makes newly created shadow images valid shader inputs exactly once. */
    boolean prepareShadowForSampling(double cameraX, double cameraY, double cameraZ) {
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

    boolean shadowAttachmentsLive() {
        if (!this.shadowMap.isInitialized() || this.shadowMap.getShadowFramebuffer() == null) {
            return false;
        }
        VulkanImage color = this.shadowMap.getShadowFramebuffer().getColorAttachment();
        VulkanImage depth = this.shadowMap.getShadowFramebuffer().getDepthAttachment();
        VulkanImage color1 = this.shadowMap.shadowColor(1);
        return color != null && color1 != null && depth != null
                && color.getId() != 0L && color1.getId() != 0L && depth.getId() != 0L;
    }

    void recordShadowTransitionFallback(String detail) {
        if (pass.conformanceReport != null) {
            pass.conformanceReport.addDeviation("SHADOW_RESOURCE_TRANSITION_SKIPPED");
        }
        if (!this.shadowTransitionFallbackLogged) {
            this.shadowTransitionFallbackLogged = true;
            LOGGER.warn("[chimera] shadow sampling fallback=IDENTITY (transition skipped: {})", detail);
        }
    }

    void recordShadowCutoutDisposition(boolean merged) {
        if (this.shadowCutoutDispositionLogged || !usePackShadowRuntime()) {
            return;
        }
        this.shadowCutoutDispositionLogged = true;
        if (merged && pass.conformanceReport != null) {
            pass.conformanceReport.addDeviation("SHADOW_CUTOUT_MERGED");
        }
        LOGGER.info("[chimera] pack shadow cutout path: {}",
                merged ? "merged by uniqueOpaqueLayer" : "separate");
    }

    void releaseOldShadowView() {
        if (this.packShadowView != null) {
            this.packShadowView.close();
        }
        if (this.packShadowTexture != null) {
            this.packShadowTexture.close();
        }
    }

    void releasePackShadowView() {
        releaseOldShadowView();
        this.packShadowTexture = null;
        this.packShadowView = null;
        this.packShadowSourceId = 0L;
    }
}
