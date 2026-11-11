package net.chimera.render;

import net.chimera.config.ChimeraConfig;
import net.chimera.config.ShaderpackDirectory;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.render.shader.RenderedItemIds;
import net.chimera.shaderpack.HandLightDirectives;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.PackConfig;
import net.chimera.shaderpack.PackCoveragePlan;
import net.chimera.shaderpack.PackDirectives;
import net.chimera.shaderpack.PackEngineDefines;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackItemIdResolver;
import net.chimera.shaderpack.PackMaterialResolver;
import net.chimera.shaderpack.PackOptionMacros;
import net.chimera.shaderpack.PackOptionSources;
import net.chimera.shaderpack.PackPlan;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgram;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PackSource;
import net.chimera.shaderpack.ParticleDirectives;
import net.chimera.shaderpack.PassComputePlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.SelectorNamespace;
import net.chimera.shaderpack.ShadowPassUse;
import net.chimera.shaderpack.StagedOptions;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.chimera.shaderpack.TerrainShadingDirectives;
import net.chimera.shaderpack.options.PendingOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    /** The loaded pack's pass computes, as its analysis translated them. */
    private PassComputePlan passComputes = PassComputePlan.empty();

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

    /** Reloads the pack the frame is about to run, so a reload never undoes a change still queued. */
    boolean queuePackReload() {
        return queuePackChange(selectedPackPath(), true);
    }

    /** The pack running now and the options it was loaded with; null without one. */
    ChimeraRenderer.LoadedPack loadedPack() {
        return this.packPath == null || pass.packSource == null
                ? null : new ChimeraRenderer.LoadedPack(this.packPath, pass.packSource.options());
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
        pass.shadowPass.shadowMap.cleanUp();
        pass.shadowPass.packShadowDepth.cleanUp();
        pass.packPostTargets.cleanUp();
        pass.packDepthTargets.cleanUp();
        pass.coverage.packCoverageOwner.close();
        pass.coverage.packCoverageState.endFrame();
        pass.coverage.packCoveragePlan = PackCoveragePlan.disabled();
        pass.pipelines.destroy();
        if (pass.packPassComputes != null) pass.packPassComputes.close();
        pass.packPassComputes = null;
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
        if (pass.packCenterDepth != null) pass.packCenterDepth.close();
        pass.packCenterDepth = null;
        pass.pipelines.clear();
        pass.postChain.packPostExecution.clear();
        pass.postChain.packPostBoundaryLogged.clear();
        pass.postChain.packFinalExecution = null;
        pass.shadowPass.shadowCastersFailed = false;
        pass.shadowPass.composites.resetSession();
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
        pass.shadowPass.shadowUse = ShadowPassUse.ALWAYS;
        pass.screenOverlays = null;
        pass.skyDirectives = null;
        pass.weatherDirectives = null;
        pass.packTargetGraph = null;
        pass.packFrameSchedule = PackFrameSchedulePlan.empty();
        pass.packPrograms = null;
        pass.packHdrFormat = 97;
        pass.packNeedsHdrDepth = false;
        pass.depth.hdrDepthReadable = false;
        resetHostDirectives();
        PackUniformProvider.resetSession();
    }

    /**
     * Returns every host-side directive a pack installs to vanilla's: the terrain overrides,
     * material map, material plan and shading, the pack atlas mips, and the held and rendered item
     * ids. Pack cleanup, the no-pack load and the host fallback after a failed rollback all run
     * this one list; the fallback ran none, so a pack whose load and rollback both failed kept its
     * terrain directives under vanilla. A material map change inside a frame-boundary handoff
     * rebuilds the level once, at its end (ChimeraTerrainPipelines.rebuildingOnce).
     */
    static void resetHostDirectives() {
        ChimeraTerrainPipelines.setGeometryOverride(null);
        ChimeraTerrainPipelines.setTranslucentOverride(null);
        ChimeraTerrainPipelines.setMaterialResolver(PackMaterialResolver.empty());
        ChimeraTerrainPipelines.setMaterialPlan(TerrainMaterialPlan.legacy());
        ChimeraTerrainPipelines.setTerrainShading(TerrainShadingDirectives.VANILLA);
        PackAtlasMipmaps.setPackInstalled(false);
        PackUniformProvider.installHeldItems(PackItemIdResolver.empty(), true);
        RenderedItemIds.install(PackItemIdResolver.empty());
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
                resetHostDirectives();
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
                resetHostDirectives();
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
            resetHostDirectives();
            PackUniformProvider.installRuntimeSettings(null);
            PackUniformProvider.installDirectives(null);
            pass.packNeedsHdrDepth = false;
            return;
        }
        PackSource.LoadResult result = loadPackPlan(dir);
        installTerrainDirectives();
        pass.screenOverlays = pass.packPlan.screenOverlays();
        LOGGER.info("[chimera] pack screen overlays: vignette={}, underwaterOverlay={}{}",
                pass.screenOverlays.vignette(), pass.screenOverlays.underwaterOverlay(),
                pass.screenOverlays.deviations().isEmpty() ? "" : " " + pass.screenOverlays.deviations());
        pass.skyDirectives = pass.packPlan.skyDirectives();
        LOGGER.info("[chimera] pack sky: sun={}, moon={}, stars={}, sky={}{}", pass.skyDirectives.sun(),
                pass.skyDirectives.moon(), pass.skyDirectives.stars(), pass.skyDirectives.sky(),
                pass.skyDirectives.deviations().isEmpty() ? "" : " " + pass.skyDirectives.deviations());
        pass.weatherDirectives = pass.packPlan.weatherDirectives();
        LOGGER.info("[chimera] pack weather: rain.depth={}{}", pass.weatherDirectives.rainDepth(),
                pass.weatherDirectives.deviations().isEmpty() ? "" : " " + pass.weatherDirectives.deviations());
        ParticleDirectives particles = pass.packPlan.particleDirectives();
        LOGGER.info("[chimera] pack particle ordering: {}, separateEntityDraws={}{}", particles.ordering(),
                particles.separateEntityDraws(), particles.deviations().isEmpty() ? "" : " " + particles.deviations());
        installRuntimeSettings();
        pass.packNeedsHdrDepth = pass.conformanceReport.programs().stream()
                .filter(program -> PostTargetPlan.isPostProgramName(program.name()))
                .anyMatch(program -> program.samplers().contains("depthtex0")
                        || program.samplers().contains("depthtex1")
                        || program.samplers().contains("depthtex2"));
        installIdMaps(result.shadersDir());
        pass.packHdrFormat = pass.packConfig.colortexFormats().getOrDefault(0, PostTargetPlan.DEFAULT_FORMAT);
        loadResourceOwners(result.shadersDir());
        logResourcePlans();
        logPackPrograms(dir);
    }

    /**
     * Loads the pack source once and installs its analysis: report, programs, constants and plan. A
     * fresh load takes the settings screen's pending values and then rewrites the option file, as
     * Iris does on every load.
     */
    private PackSource.LoadResult loadPackPlan(Path dir) {
        boolean fresh = pass.packSource == null;
        // Iris reads MC_MIPMAP_LEVEL once per pack load, and every dimension's programs and the id
        // maps use that value until the next load (ShaderPack). Read again on a dimension switch,
        // a dimension cached since the load kept the old level while a new one and the id maps
        // took the new one.
        if (fresh) {
            PackEngineDefines.setMipmapLevel(Minecraft.getInstance().options.mipmapLevels().get());
        }
        PackSource.LoadResult result = fresh
                ? PackSource.loadResult(dir, this.currentDimension, PendingOptions.drain(dir)) : pass.packSource;
        pass.packSource = result;
        PackProbe.Analysis analysis = PackProbe.analyze(dir, result);
        StagedOptions options = result.options();
        if (fresh) {
            try {
                options.saveOptionFile(dir);
            } catch (IOException failure) {
                LOGGER.warn("[chimera] cannot write the option file of {}: {}", dir, failure.getMessage());
            }
        }
        Path optionFile = PackOptionSources.optionFile(dir);
        LOGGER.info("[chimera] pack options: file={}, profile={}, applied={}, undeclared={}, disabledPrograms={}{}",
                optionFile == null ? "none" : optionFile, options.profileName(), options.applied().changes(),
                options.undeclared(), options.disabledPrograms(),
                options.pack().problems().isEmpty() ? "" : " " + options.pack().problems());
        pass.conformanceReport = analysis.report();
        this.passComputes = analysis.passComputes();
        pass.packPrograms = analysis.plan() == null ? result.programs()
                : analysis.plan().programs().stream().map(PackProgramPlan::program).toList();
        pass.packConfig = analysis.config() != null
                ? analysis.config()
                : PackConfig.parse(pass.packPrograms, result.shadersDir());
        pass.packPlan = analysis.plan() == null
                ? new PackPlan(pass.packConfig, List.of()) : analysis.plan();
        return result;
    }

    /** Hands the plan's shadow culling, terrain material and terrain shading to the host. */
    private void installTerrainDirectives() {
        pass.shadowPass.shadowCulling = pass.packPlan.shadowCulling();
        int renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        LOGGER.info("[chimera] shadow culling: state={}, terrain={}, entities={} (render distance {}){}",
                pass.shadowPass.shadowCulling.state(), pass.shadowPass.shadowCulling.terrain(renderDistance).describe(),
                pass.shadowPass.shadowCulling.entities(renderDistance).describe(), renderDistance,
                pass.shadowPass.shadowCulling.deviations().isEmpty() ? "" : " "
                        + pass.shadowPass.shadowCulling.deviations());
        ShadowPassUse shadowUse = ShadowPassUse.of(pass.packPlan, this.passComputes);
        pass.shadowPass.shadowUse = shadowUse;
        LOGGER.info("[chimera] shadow pass: runs={}, entityShadowsReplaced={} (shadow.enabled={}, readers={}, "
                + "writers={}){}", shadowUse.runs(), shadowUse.replacesEntityShadows(), shadowUse.enabled(),
                shadowUse.readers(), shadowUse.writers(),
                shadowUse.deviations().isEmpty() ? "" : " " + shadowUse.deviations());
        ChimeraTerrainPipelines.setMaterialPlan(pass.packPlan.terrainMaterial());
        TerrainShadingDirectives shading = pass.packPlan.terrainShading();
        LOGGER.info("[chimera] pack terrain shading: oldLighting={}, separateAo={}, voxelizeLightBlocks={}{}",
                shading.oldLighting(), shading.separateAo(), shading.voxelizeLightBlocks(),
                shading.deviations().isEmpty() ? "" : " " + shading.deviations());
        ChimeraTerrainPipelines.setTerrainShading(shading);
        PackAtlasMipmaps.setPackInstalled(true);
    }

    /** Installs the plan's uniform runtime settings and logs how the pack resolved. */
    private void installRuntimeSettings() {
        PackUniformProvider.installRuntimeSettings(pass.packPlan.runtimeSettings());
        PackUniformProvider.installDirectives(pass.packPlan.config() == null ? null
                : pass.packPlan.config().directives());
        LOGGER.info("[chimera] pack resolution: dimension={}, folder={}, profile=defaults, aliases={}, disabled={}, "
                + "missing={}, settingsFingerprint={}, resolutionFingerprint={}",
                pass.packPlan.selectedDimension(),
                pass.packPlan.selectedSourceFolder(),
                pass.packPlan.aliasCount(),
                pass.packPlan.disabledProgramCount(),
                pass.packPlan.missingProgramCount(),
                pass.packPlan.settingsFingerprint(),
                pass.packPlan.resolutionFingerprint());
        LOGGER.info("[chimera] uniform runtime settings: custom={}, directives={}",
                pass.packPlan.runtimeSettings().customDescriptors().size(),
                pass.packPlan.config() == null ? PackDirectives.DEFAULTS : pass.packPlan.config().directives());
    }

    /** Parses the pack's block and item id maps and installs them with its hand-light setting. */
    private void installIdMaps(Path shadersDir) {
        // Iris preprocesses the id maps with every option the pack's shader files declare.
        Map<String, String> macros = PackEngineDefines.forPack(PackOptionMacros.forIdMaps(shadersDir,
                pass.packPlan.settingsPreprocessorDefines(), pass.packPlan.settingsOverriddenNames()));
        PackMaterialResolver.ParseResult material = PackMaterialResolver.parse(shadersDir, macros);
        ChimeraTerrainPipelines.setMaterialResolver(material.resolver());
        PackItemIdResolver.ParseResult items = PackItemIdResolver.parse(shadersDir, macros);
        HandLightDirectives handLight = pass.packPlan.handLight();
        LOGGER.info("[chimera] pack held items: item ids={}, oldHandLight={}{}",
                items.resolver().mappings().size(), handLight.oldHandLight(),
                items.deviations().isEmpty() && handLight.deviations().isEmpty() ? ""
                        : " " + items.deviations() + handLight.deviations());
        PackUniformProvider.installHeldItems(items.resolver(), handLight.oldHandLight());
        RenderedItemIds.install(items.resolver());
    }

    /**
     * Loads the session's pack textures, material maps, advanced images, smoothed centre depth,
     * storage buffers and shadowcomp.
     */
    private void loadResourceOwners(Path shadersDir) {
        pass.materials.ensureMaterialMapOwner();
        pass.packResourceOwner = PackResourceOwner.load(pass.packPlan.resources(), shadersDir,
                () -> pass.materials.materialMapOwner);
        // Relative images and buffers start at the window's size; the main pass refits them to the main target.
        var window = Minecraft.getInstance().getWindow();
        int width = Math.max(window.getWidth(), 1);
        int height = Math.max(window.getHeight(), 1);
        pass.packAdvancedImageOwner = PackAdvancedImageOwner.load(pass.packPlan.advancedResources(), width, height,
                passComputes.imageFormats());
        pass.packCenterDepth = PackCenterDepth.create(pass.packPlan);
        pass.packStorageBufferOwner = PackStorageBufferOwner.load(pass.packPlan.advancedResources(), width, height,
                passComputes.storageBuffers());
        pass.packPassComputes = PackPassComputes.load(passComputes, pass.packResourceOwner,
                pass.packAdvancedImageOwner, pass.packStorageBufferOwner);
        for (String compute : pass.packPassComputes.installedNames()) {
            if (pass.conformanceReport != null && pass.conformanceReport.program(compute) != null) {
                pass.conformanceReport.markPassComputeInstalled(compute);
            }
        }
    }

    /** Logs the session's resource, advanced image, storage buffer and compute plans. */
    private void logResourcePlans() {
        LOGGER.info("[chimera] pack resources: fingerprint={}, declarations={}, deviations={}",
                pass.packPlan.resources().fingerprint(),
                pass.packPlan.resources().declarations().size(),
                pass.packPlan.resources().deviations().size());
        LOGGER.info("[chimera] advanced resources: fingerprint={}, images={}, capabilityPossible={}, "
                + "planned={}, allocated={}, initialized={}, failed={}",
                pass.packPlan.advancedResources().fingerprint(),
                pass.packPlan.advancedResources().images().size(),
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
    }

    /** Logs the pack's programs and constants, or that it has no programs. */
    private void logPackPrograms(Path dir) {
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
