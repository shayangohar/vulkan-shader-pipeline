package net.chimera.render;

import net.chimera.config.ChimeraConfig;
import net.chimera.config.ShaderpackDirectory;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.HandLightDirectives;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.PackConfig;
import net.chimera.shaderpack.PackCoveragePlan;
import net.chimera.shaderpack.PackEngineDefines;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackItemIdResolver;
import net.chimera.shaderpack.PackMaterialResolver;
import net.chimera.shaderpack.PackOptionSources;
import net.chimera.shaderpack.PackPlan;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgram;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PackSource;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.SelectorNamespace;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.chimera.shaderpack.TerrainShadingDirectives;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Loads, switches and tears down pack sessions for the main pass: reads the pack and plans it,
 * queues a pack or dimension change for the next safe frame boundary, applies it there, and
 * releases every pack-owned resource when a session ends.
 */
final class PackSessionLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    /** Original pack path used to select another dimension variant in place. */
    Path packPath;

    /** Dimension requested by WorldRenderer.setLevel, applied at a safe frame boundary. */
    String pendingPackDimension;

    /** Prevents repeated log spam while a pending change waits for an idle boundary. */
    String loggedPendingPackDimension;
    final PackChangeQueue packChangeQueue = new PackChangeQueue();

    /** Prevents a blocked request from producing per-frame diagnostics. */
    String loggedPendingPackChange;

    /** Current live dimension, also used when a new pack is selected in-game. */
    String currentDimension = "minecraft:overworld";

    PackSessionLifecycle(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /**
     * The pack to load at startup: {@code -Dchimera.pack} when given (an explicit override for
     * testing), otherwise the selection saved in {@code config/chimera.properties}.
     */
    static Path startupPackPath() {
        String value = System.getProperty("chimera.pack");
        if (value != null && !value.isBlank()) {
            try {
                return Path.of(value.trim());
            } catch (RuntimeException failure) {
                LOGGER.warn("[chimera] startup pack path is invalid: {}", value);
                return null;
            }
        }
        ChimeraConfig config = ChimeraConfig.get();
        if (!config.shadersEnabled() || config.shaderPack().isEmpty()) {
            return null;
        }
        String name = config.shaderPack().get();
        Path pack = ShaderpackDirectory.resolve(
                ShaderpackDirectory.root(), name).orElse(null);
        if (pack == null) {
            LOGGER.warn("[chimera] saved shader pack is no longer in the shaderpacks folder: {}", name);
        }
        return pack;
    }

    /** The pack the frame will run once pending changes apply; null when none. */
    Path selectedPackPath() {
        PackChangeQueue.PendingPackChange pending = this.packChangeQueue.pending();
        return pending != null ? pending.path() : this.packPath;
    }

    /** Restores the stable output invariant before Renderer exposes this pass. */
    boolean prepareForInstall() {
        if (Renderer.isRecording() || Renderer.getInstance().getBoundRenderPass() != null
                || hasPendingPackChange()) {
            return false;
        }
        pass.resources.ensureOutputResources();
        return pass.resources.outputResourcesReady && pass.targets.outputResourcesLive();
    }

    /** Called before Renderer begins a fresh main command buffer. */
    boolean applyPendingPackVariantAtFrameBoundary() {
        if (this.packChangeQueue.pending() != null) {
            return applyPendingPackChange();
        }
        return applyPendingPackVariant();
    }

    boolean hasPendingPackVariant() {
        return this.pendingPackDimension != null && pass.packSource != null && this.packPath != null
                && !this.pendingPackDimension.equals(pass.packSource.selectedDimension());
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
        PackChangeQueue.PendingPackChange pendingRequest = this.packChangeQueue.pending();
        String pending = pendingRequest == null
                ? (hasPendingPackVariant() ? "dimension " + this.pendingPackDimension : "none")
                : packLabel(pendingRequest.path());
        if (pass.packFrameUnsupported != null) {
            active += " (unsupported: " + pass.packFrameUnsupported + ")";
        }
        return "Chimera pack: active=" + active
                + ", dimension=" + this.currentDimension
                + ", pending=" + pending;
    }

    static String packLabel(Path path) {
        if (path == null) {
            return "none (vanilla)";
        }
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /** Releases only pack-owned state so a dimension variant can be rebuilt. */
    void cleanUpPackVariant() {
        ChimeraEntityBridge.setEnabled(false);
        ChimeraSkyBridge.setEnabled(false);
        // Release the dedicated entity source before its pipeline is destroyed.
        // This keeps any remaining host-side batch cleanup away from a dead
        // pack pipeline and leaves the host format/cache untouched.
        ChimeraEntityBridge.disable();
        ChimeraSkyBridge.disable();
        // Restore the host values before any pack-owned image is destroyed.
        pass.windows.restorePackResourceWindow();
        pass.images.programImages.clear();
        pass.shadowPass.loggedShadowSamplerPrograms.clear();
        // Clear every selector that may still refer to a retired pack image.
        // The host rebinds its normal slots during the next draw window.
        SelectorNamespace.clearOwned(slot -> VTextureSelector.bindTexture(slot, null));
        ChimeraTextureBindingState.reset();
        pass.shadowPass.releasePackShadowView();
        pass.shadowPass.shadowMap.cleanUp();
        pass.shadowPass.packShadowDepth.cleanUp();
        pass.packPostTargets.cleanUp();
        pass.packDepthTargets.cleanUp();
        pass.coverage.packCoverageOwner.close();
        pass.coverage.packCoverageState.endFrame();
        pass.coverage.packCoveragePlan = PackCoveragePlan.disabled();
        pass.pipelines.destroy();
        if (pass.packShadowCompute != null) pass.packShadowCompute.close();
        pass.packShadowCompute = null;
        if (pass.packStorageBufferOwner != null) {
            LOGGER.info("[chimera] storage buffers: cleanup planned={} allocated={} initialized={} failed={}",
                    pass.packStorageBufferOwner.plannedBytes(),
                    pass.packStorageBufferOwner.allocatedBytes(),
                    pass.packStorageBufferOwner.initializedBytes(),
                    pass.packStorageBufferOwner.failedBytes());
            pass.packStorageBufferOwner.close();
        }
        pass.packStorageBufferOwner = null;
        pass.packStorageInitializationLogged = false;
        pass.packTargetFrameNotStartedLogged = false;
        if (pass.packResourceOwner != null) pass.packResourceOwner.close();
        pass.packResourceOwner = null;
        if (pass.packAdvancedImageOwner != null) pass.packAdvancedImageOwner.close();
        pass.packAdvancedImageOwner = null;
        pass.pipelines.clear();
        pass.postChain.packPostExecution.clear();
        pass.postChain.packPostBoundaryLogged.clear();
        pass.postChain.packFinalExecution = null;
        pass.shadowPass.shadowCastersFailed = false;
        pass.shadowPass.shadowCutoutDispositionLogged = false;
        pass.shadowPass.shadowFrameReady = false;
        pass.shadowPass.shadowTransitionFallbackLogged = false;
        pass.packPipelinesLoaded = false;
        pass.packPostChainActive = false;
        pass.packPostChainRejected = false;
        pass.packPostFrameStarted = false;
        pass.packEarlyPostCompleted = false;
        pass.packWorldResolved = false;
        pass.packFinalApplied = false;
        pass.packConfig = null;
        pass.packPlan = null;
        pass.shadowPass.shadowCulling = null;
        pass.screenOverlays = null;
        pass.packTargetGraph = null;
        pass.packFrameSchedule = PackFrameSchedulePlan.empty();
        pass.packPrograms = null;
        pass.packHdrFormat = 97;
        pass.packNeedsHdrDepth = false;
        pass.depth.hdrDepthReadable = false;
        ChimeraTerrainPipelines.setGeometryOverride(null);
        ChimeraTerrainPipelines.setTranslucentOverride(null);
        ChimeraTerrainPipelines.setMaterialPlan(TerrainMaterialPlan.legacy());
        ChimeraTerrainPipelines.setTerrainShading(TerrainShadingDirectives.VANILLA);
        PackUniformProvider.installHeldItems(PackItemIdResolver.empty(), true);
        PackUniformProvider.resetSession();
    }

    /** Records a dimension change for application at the next safe frame boundary. */
    public void onLevelChanged(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return;
        }
        this.currentDimension = dimension;
        if (pass.packSource == null || this.packPath == null) {
            return;
        }
        if (dimension.equals(pass.packSource.selectedDimension())) {
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
    boolean applyPendingPackChange() {
        PackChangeQueue.PendingPackChange request = this.packChangeQueue.pending();
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
            pass.resources.createResources();
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
                    ChimeraMainPass.runtimeFailureReason("pack-change", rebuildFailure));
            try {
                preparePackSessionReplacement(false);
                this.packPath = previousPath;
                this.currentDimension = previousDimension;
                pass.resources.createResources();
                this.pendingPackDimension = null;
                this.loggedPendingPackDimension = null;
                LOGGER.info("[chimera] pack change rolled back: {}", packLabel(previousPath));
                reportPackChangeFailure(request.path(), rebuildFailure, "Previous pack restored.");
                return true;
            } catch (RuntimeException restoreFailure) {
                LOGGER.warn("[chimera] pack rollback failed; host seams remain active: {}",
                        ChimeraMainPass.runtimeFailureReason("pack-rollback", restoreFailure));
                this.pendingPackDimension = null;
                this.loggedPendingPackDimension = null;
                markPackVariantFallback();
                ChimeraRenderer.fallbackToHostRenderer();
                reportPackChangeFailure(request.path(), rebuildFailure, "Vanilla rendering restored; rollback failed.");
                return false;
            }
        }
    }

    static void reportPackChangeFailure(Path requested, RuntimeException failure, String recovery) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            String reason = failure.getMessage();
            if (reason == null || reason.isBlank()) reason = failure.getClass().getSimpleName();
            // Full exception details stay in latest.log, not a multi-line shader dump in chat.
            reason = reason.lines().findFirst().orElse("unknown error");
            if (reason.length() > 160) reason = reason.substring(0, 160) + "…";
            minecraft.gui.getChat().addMessage(Component.literal(
                    "[Chimera] Failed to load " + packLabel(requested) + ": " + reason
                            + ". " + recovery + " See latest.log."));
        }
    }

    /** Clears all pack-owned objects and closes the current source after the idle wait. */
    void preparePackSessionReplacement(boolean waitForIdle) {
        pass.packFrameUnsupported = null;
        clearPackOverrides();
        pass.levelPhase = false;
        pass.pendingDepthClear = false;
        pass.shadowPass.shadowPending = false;
        if (waitForIdle) {
            waitForImmediateDestruction("pack replacement");
        }
        cleanUpPackVariant();
        pass.targets.cleanUpFramebuffersAndPasses();
        pass.cleanUpPipelines();
        if (pass.packSource != null) {
            pass.packSource.close();
            pass.packSource = null;
        }
    }

    boolean rebuildBlocked() {
        return Renderer.isRecording() || pass.shadowPass.shadowPassActive || pass.packPostTargets.isRendering()
                || Renderer.getInstance().getBoundRenderPass() != null;
    }

    boolean packSourceLoadFailed() {
        if (pass.packSource == null) {
            return true;
        }
        return pass.packSource.deviations().stream().anyMatch(deviation ->
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
    boolean applyPendingPackVariant() {
        String requested = this.pendingPackDimension;
        if (requested == null || pass.packSource == null || this.packPath == null) {
            return true;
        }
        if (requested.equals(pass.packSource.selectedDimension())) {
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

        String previous = pass.packSource.selectedDimension();
        LOGGER.info("[chimera] pack dimension variant safe boundary: {}", requested);
        try {
            clearPackOverrides();
            pass.levelPhase = false;
            pass.pendingDepthClear = false;
            pass.shadowPass.shadowPending = false;
            waitForImmediateDestruction("dimension variant " + requested);
            cleanUpPackVariant();
            pass.packFrameUnsupported = null;
            pass.packSource.selectDimension(requested);
            pass.resources.createResources();
            this.pendingPackDimension = null;
            this.loggedPendingPackDimension = null;
            LOGGER.info("[chimera] pack dimension variant installed: {} ({})",
                    requested, pass.packSource.selectedVariantFolder().isBlank()
                            ? "root" : pass.packSource.selectedVariantFolder());
            return true;
        } catch (RuntimeException rebuildFailure) {
            LOGGER.warn("[chimera] pack dimension variant failed: {}, restoring {}: {}",
                    requested, previous, ChimeraMainPass.runtimeFailureReason("dimension-change", rebuildFailure));
            try {
                clearPackOverrides();
                cleanUpPackVariant();
                pass.targets.cleanUpFramebuffersAndPasses();
                pass.cleanUpPipelines();
                pass.packSource.selectDimension(previous);
                pass.resources.createResources();
                this.pendingPackDimension = null;
                this.loggedPendingPackDimension = null;
                if (pass.conformanceReport != null) {
                    pass.conformanceReport.addDeviation("PACK_VARIANT_REBUILD_FAILED");
                    LOGGER.info("[chimera] conformance {}", pass.conformanceReport.toJson());
                }
                LOGGER.info("[chimera] pack dimension variant restored: {}", previous);
                return true;
            } catch (RuntimeException restoreFailure) {
                LOGGER.warn("[chimera] pack dimension restore failed; host seams remain active: {}",
                        ChimeraMainPass.runtimeFailureReason("dimension-restore", restoreFailure));
                this.pendingPackDimension = null;
                this.loggedPendingPackDimension = null;
                markPackVariantFallback();
                ChimeraRenderer.fallbackToHostRenderer();
                return false;
            }
        }
    }

    void clearPackOverrides() {
        ChimeraTerrainPipelines.setGeometryOverride(null);
        ChimeraTerrainPipelines.setTranslucentOverride(null);
        ChimeraEntityBridge.setEnabled(false);
        ChimeraSkyBridge.setEnabled(false);
    }

    void waitForImmediateDestruction(String reason) {
        LOGGER.info("[chimera] pack GPU idle boundary: {} ({})", reason, "PACK_VARIANT_GPU_IDLE");
        Vulkan.waitIdle();
    }

    void markPackVariantFallback() {
        if (pass.conformanceReport == null) {
            return;
        }
        pass.conformanceReport.markFrameFallback("PACK_VARIANT_REBUILD_FAILED", "PACK_VARIANT_REBUILD_FAILED");
        LOGGER.info("[chimera] conformance {}", pass.conformanceReport.toJson());
    }

    /**
     * Discovers and parses the active pack once per session. Must run before
     * createHdrFramebuffer so the pack's colortex0Format can drive the HDR
     * buffer format.
     */
    void loadPackConfig() {
        if (pass.packConfig != null) {
            if (pass.conformanceReport == null) {
                if (this.packPath != null && pass.packSource != null) {
                    pass.conformanceReport = PackProbe.probe(this.packPath, pass.packSource);
                }
            }
            return;
        }
        Path dir = this.packPath;
        if (dir == null || pass.packFrameUnsupported != null) {
            if (dir == null) LOGGER.info("[chimera] no shader pack selected; vanilla rendering");
            ChimeraTerrainPipelines.setMaterialResolver(PackMaterialResolver.empty());
            ChimeraTerrainPipelines.setMaterialPlan(TerrainMaterialPlan.legacy());
            ChimeraTerrainPipelines.setTerrainShading(TerrainShadingDirectives.VANILLA);
            PackUniformProvider.installHeldItems(PackItemIdResolver.empty(), true);
            PackUniformProvider.installRuntimeSettings(null);
            pass.packNeedsHdrDepth = false;
            return;
        }
        PackSource.LoadResult result = pass.packSource != null
                ? pass.packSource : PackSource.loadResult(dir, this.currentDimension);
        pass.packSource = result;
        PackProbe.Analysis analysis = PackProbe.analyze(dir, result);
        Path optionFile = PackOptionSources.optionFile(dir);
        LOGGER.info("[chimera] pack options: file={}, applied={}", optionFile == null ? "none" : optionFile,
                PackOptionSources.overrides(dir));
        pass.conformanceReport = analysis.report();
        pass.packPrograms = analysis.plan() == null ? result.programs()
                : analysis.plan().programs().stream().map(PackProgramPlan::program).toList();
        pass.packConfig = analysis.config() != null
                ? analysis.config()
                : PackConfig.parse(pass.packPrograms, result.shadersDir());
        pass.packPlan = analysis.plan() == null
                ? new PackPlan(pass.packConfig, List.of()) : analysis.plan();
        pass.shadowPass.shadowCulling = pass.packPlan.shadowCulling();
        int renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        LOGGER.info("[chimera] shadow culling: state={}, terrain={}, entities={} (render distance {}){}",
                pass.shadowPass.shadowCulling.state(), pass.shadowPass.shadowCulling.terrain(renderDistance).describe(),
                pass.shadowPass.shadowCulling.entities(renderDistance).describe(), renderDistance,
                pass.shadowPass.shadowCulling.deviations().isEmpty() ? "" : " "
                        + pass.shadowPass.shadowCulling.deviations());
        ChimeraTerrainPipelines.setMaterialPlan(pass.packPlan.terrainMaterial());
        TerrainShadingDirectives shading = pass.packPlan.terrainShading();
        LOGGER.info("[chimera] pack terrain shading: oldLighting={}, separateAo={}{}", shading.oldLighting(),
                shading.separateAo(), shading.deviations().isEmpty() ? "" : " " + shading.deviations());
        ChimeraTerrainPipelines.setTerrainShading(shading);
        pass.screenOverlays = pass.packPlan.screenOverlays();
        LOGGER.info("[chimera] pack screen overlays: vignette={}, underwaterOverlay={}{}",
                pass.screenOverlays.vignette(), pass.screenOverlays.underwaterOverlay(),
                pass.screenOverlays.deviations().isEmpty() ? "" : " " + pass.screenOverlays.deviations());
        PackUniformProvider.installRuntimeSettings(pass.packPlan.runtimeSettings());
        LOGGER.info("[chimera] pack resolution: dimension={}, folder={}, profile=defaults, aliases={}, disabled={}, "
                + "missing={}, settingsFingerprint={}, resolutionFingerprint={}",
                pass.packPlan.selectedDimension(),
                pass.packPlan.selectedSourceFolder(),
                pass.packPlan.aliasCount(),
                pass.packPlan.disabledProgramCount(),
                pass.packPlan.missingProgramCount(),
                pass.packPlan.settingsFingerprint(),
                pass.packPlan.resolutionFingerprint());
        LOGGER.info("[chimera] uniform runtime settings: custom={}, wetnessRise={}, wetnessFall={}, "
                + "eyeBrightnessHalfLife={}",
                pass.packPlan.runtimeSettings().customDescriptors().size(),
                pass.packPlan.runtimeSettings().wetnessRiseHalfLife(),
                pass.packPlan.runtimeSettings().wetnessFallHalfLife(),
                pass.packPlan.runtimeSettings().eyeBrightnessHalfLife());
        pass.packNeedsHdrDepth = pass.conformanceReport.programs().stream()
                .filter(program -> PostTargetPlan.isPostProgramName(program.name()))
                .anyMatch(program -> program.samplers().contains("depthtex0")
                        || program.samplers().contains("depthtex1")
                        || program.samplers().contains("depthtex2"));
        PackMaterialResolver.ParseResult material = PackMaterialResolver.parse(result.shadersDir(),
                PackEngineDefines.forPack(pass.packPlan.settingsPreprocessorDefines()));
        ChimeraTerrainPipelines.setMaterialResolver(material.resolver());
        PackItemIdResolver.ParseResult items = PackItemIdResolver.parse(
                result.shadersDir(),
                PackEngineDefines.forPack(pass.packPlan.settingsPreprocessorDefines()));
        HandLightDirectives handLight = pass.packPlan.handLight();
        LOGGER.info("[chimera] pack held items: item ids={}, oldHandLight={}{}",
                items.resolver().mappings().size(), handLight.oldHandLight(),
                items.deviations().isEmpty() && handLight.deviations().isEmpty() ? ""
                        : " " + items.deviations() + handLight.deviations());
        PackUniformProvider.installHeldItems(items.resolver(), handLight.oldHandLight());
        pass.packHdrFormat = pass.packConfig.colortexFormats().getOrDefault(0, 97);
        pass.packResourceOwner = PackResourceOwner.load(pass.packPlan.resources(), result.shadersDir());
        pass.materials.ensureMaterialMapOwner();
        pass.packAdvancedImageOwner = PackAdvancedImageOwner.load(pass.packPlan.advancedResources());
        pass.packStorageBufferOwner = PackStorageBufferOwner.load(pass.packPlan.advancedResources());
        pass.packShadowCompute = PackShadowCompute.load(pass.packPlan.advancedResources(),
                pass.packAdvancedImageOwner, pass.packResourceOwner, result.shadersDir(),
                pass.packPlan.settingsPreprocessorDefines(),
                pass.packPlan.settingsOverriddenNames());
        if (pass.packShadowCompute != null && pass.packShadowCompute.isInstalled()) {
            pass.packAdvancedImageOwner.markComputeInstalled();
            if (pass.conformanceReport != null
                    && pass.conformanceReport.program("shadowcomp") != null) {
                pass.conformanceReport.markRuntime("shadowcomp",
                        ConformanceReport.RuntimeDisposition.INSTALLED,
                        "COMPUTE_NATIVE_INSTALLED");
            }
        } else if (pass.conformanceReport != null
                && pass.conformanceReport.program("shadowcomp") != null
                && pass.conformanceReport.program("shadowcomp").runtime()
                == ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED) {
            pass.conformanceReport.markRuntime("shadowcomp",
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                    pass.packPlan.advancedResources().capabilityPossible()
                            ? "COMPUTE_PIPELINE_BUILD_FAILED"
                            : "COMPUTE_CAPABILITY_UNAVAILABLE");
        }
        LOGGER.info("[chimera] pack resources: fingerprint={}, declarations={}, deviations={}",
                pass.packPlan.resources().fingerprint(),
                pass.packPlan.resources().declarations().size(),
                pass.packPlan.resources().deviations().size());
        LOGGER.info("[chimera] advanced resources: fingerprint={}, images={}, compute={}, capabilityPossible={}, "
                + "planned={}, allocated={}, initialized={}, failed={}",
                pass.packPlan.advancedResources().fingerprint(),
                pass.packPlan.advancedResources().images().size(),
                pass.packPlan.advancedResources().computeStages().size(),
                pass.packPlan.advancedResources().capabilityPossible(),
                pass.packAdvancedImageOwner.plannedImageCount(),
                pass.packAdvancedImageOwner.allocatedImageCount(),
                pass.packAdvancedImageOwner.initializedImageCount(),
                pass.packAdvancedImageOwner.failures().size());
        LOGGER.info("[chimera] storage buffers: declarations={}, bindings={}, dependent={}, allocated={}, failures={}",
                pass.packPlan.advancedResources().buffers().size(),
                pass.packPlan.advancedResources().storageBuffers().values().stream()
                        .mapToInt(List::size).sum(),
                pass.packPlan.advancedResources().bufferDependentPrograms(),
                pass.packStorageBufferOwner.allocatedBytes()
                        + "/planned=" + pass.packStorageBufferOwner.plannedBytes()
                        + "/initialized=" + pass.packStorageBufferOwner.initializedBytes(),
                pass.packStorageBufferOwner.failures()
                        + "/failedBytes=" + pass.packStorageBufferOwner.failedBytes());
        LOGGER.info("[chimera] advanced graphics producers: {}",
                pass.packPlan.advancedResources().graphicsImages().entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue().stream()
                                .map(PackAdvancedResourcePlan.GraphicsImageBinding::symbol)
                                .toList())
                        .toList());
        LOGGER.info("[chimera] advanced compute plan: {}",
                pass.packPlan.advancedResources().computeStages().values().stream()
                        .map(value -> value.program() + ":" + value.status() + ":" + value.deviations())
                        .toList());
        LOGGER.info("[chimera] advanced compute: shadowcompInstalled={}",
                pass.packShadowCompute != null && pass.packShadowCompute.isInstalled());
        if (pass.packPrograms.isEmpty()) {
            LOGGER.warn("[chimera] pack '{}' from {}: no programs found", dir.getFileName(), dir.toAbsolutePath());
            return;
        }
        LOGGER.info("[chimera] pack '{}' from {}: programs={}", dir.getFileName(),
                dir.toAbsolutePath(), pass.packPrograms.stream().map(PackProgram::name).toList());
        LOGGER.info("[chimera] pack consts: {}, shadowSettings={}, drawBuffers={}",
                formatSummary(pass.packConfig.colortexFormats()),
                pass.packConfig.shadowSettings(),
                pass.packConfig.drawBufferCount());
        for (String deviation : pass.packConfig.shadowSettings().deviations()) {
            LOGGER.warn("[chimera] pack: {}", deviation);
        }
    }

    String formatSummary(Map<Integer, Integer> formats) {
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

    void logConformanceSummary() {
        if (pass.conformanceReport != null) {
            LOGGER.info("[chimera] conformance summary: reportSha256={} {}",
                    pass.conformanceReport.sha256(), pass.conformanceReport.runtimeSummary());
        }
    }
}
