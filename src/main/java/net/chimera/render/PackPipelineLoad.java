package net.chimera.render;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.CompiledStages;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.PackFeatureFlags;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.SkippedPrograms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Loads a pack's pipelines into the pass: installs each admitted program through its family's
 * installer, then holds the whole session to the host frame when a mask-critical family is missing.
 */
final class PackPipelineLoad {
    static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    PackPipelineLoad(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /**
     * Loads the active OptiFine-format pack and builds
     * its composite/final programs onto the post seams, gbuffers_terrain onto
     * the opaque terrain path, and gbuffers_water onto the translucent path.
     * A program that fails to load, convert, or
     * compile keeps the identity pipeline for its seam, so a bad pack can
     * never break the frame.
     */
    void loadPackPipelines() {
        if (pass.session.packPipelinesLoaded) {
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
        resetSessionPipelines();

        if (pass.packPrograms == null) {
            // loadPackConfig found no pack (or no programs).
            reportConformance();
            return;
        }

        if (!enforceRequiredFeatures()) {
            return;
        }
        new PackPipelineInstaller(pass).installPrograms();
        installFamilyBridge();
        LOGGER.debug("[chimera] pack stages: {}", CompiledStages.endLoad());
        if (!enforceMaskCriticalFamilyContract()) {
            return;
        }
        ChimeraSkyBridge.install(pass.pipelines.skyBasic, pass.pipelines.skyTextured,
                pass.pipelines.cloud, pass.pipelines.cloudsDrawNothing,
                pass.packPlan == null ? null : pass.packPlan.cloudSetting());
        pass.pipelines.postStages.sort(Comparator.comparing(
                PackPipelines.PackPost::name, PostTargetPlan.programComparator()));
        pass.postChain.buildPackPostExecutionPlan();
        if (pass.coverage.packCoveragePlan.enabled()) {
            LOGGER.debug("[chimera] scene seed: direct HDR; coverage merge deferred for {}",
                    pass.coverage.packCoveragePlan.families());
        }
        reportShadowFallbacks();
        reportConformance();
        logPackStoragePath("pipelines-installed");
    }

    /**
     * Clears the previous session's pipelines and marks this load. The session's flags and logs
     * start clear: cleanUpPackVariant replaced them (PackSessionState) before any load.
     */
    private void resetSessionPipelines() {
        pass.pipelines.clear();
        pass.windows.restorePackResourceWindow();
        pass.packTranslucentSuspended = false;
        pass.session.packPipelinesLoaded = true;
    }

    /** Hands the installed entity, block, hand, particle and caster pipelines to the family bridge. */
    private void installFamilyBridge() {
        ChimeraEntityBridge.install(pass.pipelines.lanes(pass.packPlan == null ? null : pass.packPlan.entityIds()));
        LOGGER.debug("[chimera] family bridge: targets entity={}, block={}",
                familyTargets(pass.pipelines.entity), familyTargets(pass.pipelines.block));
    }

    /** The targets a family pipeline writes, for the bridge log; "none" when it is not installed. */
    private static Object familyTargets(PackPipelines.PackEntity family) {
        if (family == null) {
            return "none";
        }
        return family.outputPlan() == null ? List.of(0) : family.outputPlan().targetSlots();
    }

    /** Reports a pack shadow program the terrain cannot consume, or a pack with no shadow program. */
    private void reportShadowFallbacks() {
        if (pass.pipelines.shadowPipeline != null && !pass.shadowPass.usePackShadowRuntime()) {
            if (pass.conformanceReport != null) {
                pass.conformanceReport.markRuntime("shadow",
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "SHADOW_CONSUMER_UNAVAILABLE");
                pass.conformanceReport.addDeviation("SHADOW_CONSUMER_UNAVAILABLE");
            }
            LOGGER.warn("[chimera] pack shadow: fixed host fallback (terrain consumer unavailable)");
        }
        // shadow_cutout and shadow_water draw only inside the pack shadow pass, so without it they never run.
        if (!pass.shadowPass.usePackShadowRuntime() && pass.conformanceReport != null) {
            String reason = pass.pipelines.shadowPipeline == null
                    ? "SHADOW_PROGRAM_UNAVAILABLE" : "SHADOW_CONSUMER_UNAVAILABLE";
            if (pass.pipelines.shadowCutoutPipeline != null) {
                pass.conformanceReport.markRuntime("shadow_cutout",
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, reason);
            }
            if (pass.pipelines.shadowWaterPipeline != null) {
                pass.conformanceReport.markRuntime("shadow_water",
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, reason);
            }
        }
        if (pass.pipelines.shadowPipeline == null
                && pass.packPrograms.stream().noneMatch(program -> program.name().equals("shadow"))) {
            if (pass.conformanceReport != null) {
                pass.conformanceReport.addDeviation("SHADOW_PROGRAM_MISSING");
            }
            // A pack without a shadow program has no shadow pass in Iris either: nothing changes.
            LOGGER.debug("[chimera] pack shadow: fallback=IDENTITY (SHADOW_PROGRAM_MISSING)");
        }
    }

    /**
     * Marks every program never attempted as a fallback, logs the session's conformance report, and
     * tells in chat of each program skipped where Iris would have run it (SkippedPrograms).
     */
    private void reportConformance() {
        if (pass.conformanceReport != null) {
            pass.conformanceReport.markUnattemptedAsFallback();
            pass.sessions.logConformance();
            recordSkippedPrograms(pass.conformanceReport);
        }
        PackFailureNotices.publish();
    }

    /**
     * Records each program the load skipped where Iris would have run it. A program that did not
     * build was recorded with its compiler's message first, and keeps that record.
     */
    static void recordSkippedPrograms(ConformanceReport report) {
        String pack = PackFailureNotices.currentPack();
        for (ConformanceReport.ProgramReport row : report.programs()) {
            if (row.runtime() != ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK
                    || SkippedPrograms.expected(row)) {
                continue;
            }
            List<String> refusals = SkippedPrograms.refusals(row.deviations());
            PackFailureNotices.record(new PackFailureNotices.Failure(pack, row.name(), "load",
                    PackFailureNotices.Outcome.SKIPPED, SkippedPrograms.reason(row.deviations()),
                    "Chimera skipped " + row.name() + " in " + pack + " at load: "
                            + (refusals.isEmpty() ? "not installed" : String.join(", ", refusals))));
        }
    }

    /**
     * Mask-critical family contract. Entity and
     * block draws cover world pixels the pack's post passes classify by
     * material metadata, and pack geometry writes authored targets only post
     * presents. A frame missing either family therefore has no coherent pack
     * form: post over host entities ghosts, and pack geometry without post is
     * invisible. The whole session falls back to the host frame, as if no pack
     * were selected, and says why. Returns false when the session was released.
     */
    boolean enforceMaskCriticalFamilyContract() {
        List<String> missing = maskCriticalFamilyFallback();
        if (missing.isEmpty()) {
            LOGGER.debug("[chimera] family bridge: mask-critical contract holds "
                    + "(entity and block installed or not authored)");
            return true;
        }
        String families = String.join(", ", missing);
        renderVanilla(families, "PACK_FRAME_UNSUPPORTED:" + String.join(",", missing),
                families + " could not load");
        return false;
    }

    /**
     * Iris refuses a pack whose {@code iris.features.required} names a feature it cannot provide.
     * Chimera renders vanilla for it, as for a missing mask-critical family, and names the features.
     */
    private boolean enforceRequiredFeatures() {
        Set<String> missing = pass.packPlan == null ? Set.of() : pass.packPlan.unsupportedRequiredFeatures();
        if (missing.isEmpty()) {
            return true;
        }
        String features = missing.stream().map(PackFeatureFlags::readableName).collect(Collectors.joining(", "));
        renderVanilla("required features " + features, "PACK_FRAME_UNSUPPORTED:" + String.join(",", missing),
                "needs unsupported features (" + features + ")");
        return false;
    }

    /** Drops the session to the host frame, as if no pack were selected, and says why in chat. */
    private void renderVanilla(String reason, String deviation, String chatReason) {
        pass.packFrameUnsupported = reason;
        if (pass.conformanceReport != null) {
            pass.conformanceReport.markFrameFallback(deviation, "PACK_FRAME_UNSUPPORTED");
            pass.sessions.logConformance();
        }
        LOGGER.warn("[chimera] pack frame unsupported: {}; vanilla rendering for {}",
                reason, PackSessionLifecycle.packLabel(pass.sessions.packPath));
        // Nothing built this session has drawn yet, but a raw texture's upload may still be running:
        // its handles wait for their frames (HandleRetirement), outside any idle teardown.
        pass.sessions.cleanUpPackVariant();
        String pack = PackSessionLifecycle.packLabel(pass.sessions.packPath);
        PackFailureNotices.record(new PackFailureNotices.Failure(pack, "pack", "load",
                PackFailureNotices.Outcome.VANILLA, chatReason,
                "Chimera renders vanilla for " + pack + ": " + chatReason + ".\n" + deviation));
        PackFailureNotices.publish();
    }

    /**
     * Authored mask-critical families without an installed pipeline. Pure
     * predicate over install state; the enforcement above consumes it once
     * per install, and target reconfiguration honors the stored verdict so
     * a resize cannot reopen mixed frames.
     */
    List<String> maskCriticalFamilyFallback() {
        List<String> missing = new ArrayList<>();
        if (pass.packPlan == null) {
            return missing;
        }
        if (pass.packPlan.program("gbuffers_entities") != null && pass.pipelines.entity == null) {
            missing.add("gbuffers_entities");
        }
        if (pass.packPlan.program("gbuffers_block") != null && pass.pipelines.block == null) {
            missing.add("gbuffers_block");
        }
        return missing;
    }

    void logPackStoragePath(String phase) {
        if (pass.packStorageBufferOwner == null || pass.packPlan == null) return;
        PackAdvancedResourcePlan resources = pass.packPlan.advancedResources();
        boolean storageRequired = !resources.buffers().isEmpty()
                || resources.storageBuffers().values().stream().anyMatch(bindings -> !bindings.isEmpty());
        String status = storageRequired
                ? pass.packStorageBufferOwner.storagePathStatus("shadow", "composite")
                : "storage=0,required=false,ready=true,missing=none";
        LOGGER.debug("[chimera] storage path: phase={},plannedBytes={},allocatedBytes={},initializedBytes={},{}",
                phase, pass.packStorageBufferOwner.plannedBytes(),
                pass.packStorageBufferOwner.allocatedBytes(),
                pass.packStorageBufferOwner.initializedBytes(), status);
    }
}
