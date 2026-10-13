package net.chimera.render;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PostTargetPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        if (pass.packPipelinesLoaded) {
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
        pass.pipelines.clear();
        pass.postChain.packPostExecution.clear();
        pass.postChain.packPostBoundaryLogged.clear();
        pass.postChain.packFinalExecution = null;
        pass.packPostChainRejected = false;
        pass.windows.restorePackResourceWindow();
        pass.packTranslucentSuspended = false;
        pass.packTranslucentSuspendLogged = false;
        pass.shadowPass.shadowCastersFailed = false;
        pass.shadowPass.shadowCutoutDispositionLogged = false;
        pass.packPipelinesLoaded = true;

        if (pass.packPrograms == null) {
            // loadPackConfig found no pack (or no programs).
            if (pass.conformanceReport != null) {
                pass.conformanceReport.markUnattemptedAsFallback();
                LOGGER.info("[chimera] conformance {}", pass.conformanceReport.toJson());
                pass.sessions.logConformanceSummary();
            }
            return;
        }

        new PackPipelineInstaller(pass).installPrograms();
        ChimeraEntityBridge.install(pass.pipelines.entity, pass.pipelines.translucentEntity,
                pass.pipelines.spiderEyes, pass.pipelines.block, pass.pipelines.damagedBlock,
                pass.pipelines.hand, pass.pipelines.handWater, pass.pipelines.particle,
                pass.pipelines.translucentParticle,
                pass.packPlan == null ? null : pass.packPlan.entityIds());
        ChimeraEntityBridge.installTextVariants(pass.pipelines.translucentEntityText, pass.pipelines.blockText);
        ChimeraEntityBridge.installHostLayout(pass.pipelines.hostLayout);
        ChimeraEntityBridge.installShadowCasters(pass.pipelines.shadowEntities);
        LOGGER.info("[chimera] family bridge: targets entity={}, block={}",
                pass.pipelines.entity == null ? "none"
                        : pass.pipelines.entity.outputPlan() == null
                        ? List.of(0) : pass.pipelines.entity.outputPlan().targetSlots(),
                pass.pipelines.block == null ? "none"
                        :                 pass.pipelines.block.outputPlan() == null
                        ? List.of(0) : pass.pipelines.block.outputPlan().targetSlots());
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
            LOGGER.info("[chimera] scene seed: direct HDR composition active; coverage merge deferred families={}",
                    pass.coverage.packCoveragePlan.families());
        }
        if (pass.pipelines.shadowPipeline != null && !pass.shadowPass.usePackShadowRuntime()) {
            if (pass.conformanceReport != null) {
                pass.conformanceReport.markRuntime("shadow",
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "SHADOW_CONSUMER_UNAVAILABLE");
                pass.conformanceReport.addDeviation("SHADOW_CONSUMER_UNAVAILABLE");
            }
            LOGGER.warn("[chimera] pack shadow: fixed host fallback (terrain consumer unavailable)");
        }
        if (pass.pipelines.shadowPipeline == null
                && pass.packPrograms.stream().noneMatch(program -> program.name().equals("shadow"))) {
            if (pass.conformanceReport != null) {
                pass.conformanceReport.addDeviation("SHADOW_PROGRAM_MISSING");
            }
            LOGGER.warn("[chimera] pack shadow: fallback=IDENTITY (SHADOW_PROGRAM_MISSING)");
        }
        if (pass.conformanceReport != null) {
            pass.conformanceReport.markUnattemptedAsFallback();
            LOGGER.info("[chimera] conformance {}", pass.conformanceReport.toJson());
            pass.sessions.logConformanceSummary();
        }
        logPackStoragePath("pipelines-installed");
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
            LOGGER.info("[chimera] family bridge: mask-critical contract holds "
                    + "(entity and block installed or not authored)");
            return true;
        }
        pass.packFrameUnsupported = String.join(", ", missing);
        if (pass.conformanceReport != null) {
            pass.conformanceReport.markFrameFallback(
                    "PACK_FRAME_UNSUPPORTED:" + String.join(",", missing), "PACK_FRAME_UNSUPPORTED");
            LOGGER.info("[chimera] conformance {}", pass.conformanceReport.toJson());
            pass.sessions.logConformanceSummary();
        }
        LOGGER.warn("[chimera] pack frame unsupported: {} could not install; vanilla rendering for {}",
                pass.packFrameUnsupported, PackSessionLifecycle.packLabel(pass.sessions.packPath));
        // Nothing built this session has been submitted yet, so release it directly.
        pass.sessions.cleanUpPackVariant();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.literal(
                    "[Chimera] " + PackSessionLifecycle.packLabel(pass.sessions.packPath) + " is not supported yet ("
                            + pass.packFrameUnsupported + " could not load). Rendering vanilla. See latest.log."));
        }
        return false;
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
        LOGGER.info("[chimera] storage path: phase={},plannedBytes={},allocatedBytes={},initializedBytes={},{}",
                phase, pass.packStorageBufferOwner.plannedBytes(),
                pass.packStorageBufferOwner.allocatedBytes(),
                pass.packStorageBufferOwner.initializedBytes(), status);
    }
}
