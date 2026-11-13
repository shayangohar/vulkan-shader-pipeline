package net.chimera.render;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.CompiledStages;
import net.chimera.render.shader.InstalledLanes;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.PackEntityIdResolver;
import net.chimera.shaderpack.PackFeatureFlags;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.SkippedPrograms;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The pipelines one pack session installed, by family, and the routes in use, and how they load:
 * each admitted program is installed through its family's installer (PackPipelineInstaller), and
 * the whole session is held to the host frame when a mask-critical family is missing. A null
 * family has no pack pipeline and keeps its host draw.
 */
final class PackPipelineSet {
    static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    /** Pack programs swapped onto the resolve/present seams; null = identity. */
    GraphicsPipeline compositePipeline;
    GraphicsPipeline finalPipeline;
    PackPipelines.PackTerrain terrainRoute;
    PackPipelines.PackTerrain waterRoute;
    int[] compositeSlots;
    int[] finalSlots;
    List<String> compositeSamplerNames = List.of();
    List<String> finalSamplerNames = List.of();

    /** All successfully built post programs, sorted by deterministic pass order. */
    final List<PackPipelines.PackPost> postStages = new ArrayList<>();
    /** The shadowcomp passes' programs, over the shadowcolor targets (PackShadowComposites). */
    final List<PackPipelines.PackPost> shadowcompStages = new ArrayList<>();

    /** The final pass is retained separately because it runs before hand/GUI composition. */
    PackPipelines.PackPost finalPost;

    /** Pack geometry program (gbuffers_terrain) on the terrain path; null = chimera's terrain pipeline. */
    GraphicsPipeline terrainPipeline;

    /** Pack water program on the host translucent terrain path; null = host/chimera fallback. */
    GraphicsPipeline waterPipeline;

    /** Pack shadow program on the shadow terrain path; null means fixed identity shadow. */
    GraphicsPipeline shadowPipeline;
    int[] shadowSlots;
    /** shadow_water for the shadow pass's translucent terrain (Iris SHADOW_TRANS); null draws it with shadow. */
    GraphicsPipeline shadowWaterPipeline;
    /** shadow_cutout for the shadow pass's cutout terrain layers (Iris SHADOW_CUTOUT); null draws them with shadow. */
    GraphicsPipeline shadowCutoutPipeline;

    /** shadow_entities on the shadow pass's entity lane; null leaves entities out of the shadow map. */
    PackPipelines.PackEntity shadowEntities;
    /** shadow_entities on the block layout, for moving blocks in the shadow pass. */
    PackPipelines.PackEntity shadowMovingBlock;
    /** shadow_block, for block entities' models in the shadow pass; null leaves them to shadow_entities. */
    PackPipelines.PackEntity shadowBlock;

    /** Pack world-entity program on the guarded delayed model batch. */
    PackPipelines.PackEntity entity;

    /** Pack translucent world-entity program on the guarded delayed model batch. */
    PackPipelines.PackEntity translucentEntity;

    /** Pack spider-eyes program (Iris EYES and ENTITY_TRANSLUCENT_EMISSIVE) on the delayed model batch. */
    PackPipelines.PackEntity spiderEyes;

    /** Pack block-entity program on the guarded delayed model batch. */
    PackPipelines.PackEntity block;

    /** gbuffers_block_translucent: block entities' translucent models (Iris BE_TRANSLUCENT). */
    PackPipelines.PackEntity blockTranslucent;

    /** gbuffers_block on the block layout: falling blocks and moving pistons (Iris MOVING_BLOCK). */
    PackPipelines.PackEntity movingBlock;

    /** Pack damaged-block program on the guarded block draw lane. */
    PackPipelines.PackEntity damagedBlock;

    /** The translucent-entity and block-translucent programs on Iris's GLYPH text layout (TEXT, TEXT_BE). */
    PackPipelines.PackEntity translucentEntityText;
    PackPipelines.PackEntity blockTranslucentText;

    /** Pack first-person hand program on the guarded hand draw window. */
    PackPipelines.PackHand hand;

    /** Pack first-person water program on the guarded hand draw window. */
    PackPipelines.PackHand handWater;

    /** Pack particle program on the host particle draw window. */
    PackPipelines.PackParticle particle;

    /** Pack translucent particle program on the host translucent particle window. */
    PackPipelines.PackParticle translucentParticle;

    /** gbuffers_beaconbeam on the beam's widened block layout (Iris BEACON). */
    PackPipelines.PackParticle beaconBeam;

    /** Pack programs of the installed host-layout lanes (weather, lines, glint, lightning, ...). */
    final EnumMap<ChimeraEntityBridge.Family, PackPipelines.PackParticle> hostLayout =
            new EnumMap<>(ChimeraEntityBridge.Family.class);

    PackPipelines.PackSkyFamily skyBasic;
    PackPipelines.PackSkyFamily skyTextured;
    PackPipelines.PackSky cloud;
    boolean cloudsDrawNothing;

    PackPipelineSet(ChimeraMainPass pass) {
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
        ChimeraSkyBridge.install(this.skyBasic, this.skyTextured,
                this.cloud, this.cloudsDrawNothing,
                pass.packPlan == null ? null : pass.packPlan.cloudSetting());
        this.postStages.sort(Comparator.comparing(
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
     * start clear: cleanUpPackVariant replaced them (PackSessionState) before any load. All but one:
     * createResources configures the post targets before it loads the pipelines, so a target
     * allocation that failed just now has already marked the chain rejected. The load clears that
     * mark, so the world programs, entity bridge and pack shadows still run without post, as
     * PackPostChain's failure notice says; a later failure, mid-session, keeps the mark.
     */
    private void resetSessionPipelines() {
        clear();
        pass.windows.restorePackResourceWindow();
        pass.packTranslucentSuspended = false;
        pass.session.packPostChainRejected = false;
        pass.session.packPipelinesLoaded = true;
    }

    /** Hands the installed entity, block, hand, particle and caster pipelines to the family bridge. */
    private void installFamilyBridge() {
        ChimeraEntityBridge.install(lanes(pass.packPlan == null ? null : pass.packPlan.entityIds()));
        LOGGER.debug("[chimera] family bridge: targets entity={}, block={}",
                familyTargets(this.entity), familyTargets(this.block));
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
        if (this.shadowPipeline != null && !pass.shadowPass.usePackShadowRuntime()) {
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
            String reason = this.shadowPipeline == null
                    ? "SHADOW_PROGRAM_UNAVAILABLE" : "SHADOW_CONSUMER_UNAVAILABLE";
            if (this.shadowCutoutPipeline != null) {
                pass.conformanceReport.markRuntime("shadow_cutout",
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, reason);
            }
            if (this.shadowWaterPipeline != null) {
                pass.conformanceReport.markRuntime("shadow_water",
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, reason);
            }
        }
        if (this.shadowPipeline == null
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
        if (pass.packPlan.program("gbuffers_entities") != null && this.entity == null) {
            missing.add("gbuffers_entities");
        }
        if (pass.packPlan.program("gbuffers_block") != null && this.block == null) {
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

    /**
     * Destroys every installed pipeline. A post program installed as composite or final is one
     * pipeline, destroyed once.
     */
    void destroy() {
        Set<GraphicsPipeline> postPipelines = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PackPipelines.PackPost post : postStages) {
            postPipelines.add(post.pipeline());
        }
        shadowcompStages.forEach(post -> postPipelines.add(post.pipeline()));
        if (compositePipeline != null) postPipelines.add(compositePipeline);
        if (finalPipeline != null) postPipelines.add(finalPipeline);
        postPipelines.forEach(GraphicsPipeline::cleanUp);
        Stream.of(terrainPipeline, waterPipeline, shadowPipeline, shadowWaterPipeline, shadowCutoutPipeline)
                .filter(Objects::nonNull).forEach(GraphicsPipeline::cleanUp);
        terrainRoute = null;
        waterRoute = null;
        Stream.of(shadowEntities, shadowMovingBlock, shadowBlock, entity, translucentEntity, spiderEyes, block,
                        blockTranslucent, movingBlock, damagedBlock, translucentEntityText, blockTranslucentText)
                .filter(Objects::nonNull).forEach(value -> value.pipeline().cleanUp());
        if (hand != null) hand.cleanUp();
        if (handWater != null) handWater.cleanUp();
        if (particle != null) particle.pipeline().cleanUp();
        if (translucentParticle != null) translucentParticle.pipeline().cleanUp();
        if (beaconBeam != null) beaconBeam.pipeline().cleanUp();
        hostLayout.values().forEach(lane -> lane.pipeline().cleanUp());
        if (skyBasic != null) skyBasic.cleanUp();
        if (skyTextured != null) skyTextured.cleanUp();
        if (cloud != null) cloud.pipeline().cleanUp();
    }

    /** Forgets every pipeline, leaving each family to its host draw. */
    void clear() {
        postStages.clear();
        shadowcompStages.clear();
        compositePipeline = null;
        compositeSlots = null;
        compositeSamplerNames = List.of();
        finalPipeline = null;
        finalSlots = null;
        finalSamplerNames = List.of();
        finalPost = null;
        terrainPipeline = null;
        terrainRoute = null;
        waterPipeline = null;
        waterRoute = null;
        shadowPipeline = null;
        shadowSlots = null;
        shadowWaterPipeline = null;
        shadowCutoutPipeline = null;
        shadowEntities = null;
        shadowMovingBlock = null;
        shadowBlock = null;
        entity = null;
        translucentEntity = null;
        spiderEyes = null;
        block = null;
        blockTranslucent = null;
        movingBlock = null;
        damagedBlock = null;
        translucentEntityText = null;
        blockTranslucentText = null;
        hand = null;
        handWater = null;
        particle = null;
        translucentParticle = null;
        beaconBeam = null;
        hostLayout.clear();
        skyBasic = null;
        skyTextured = null;
        cloud = null;
        cloudsDrawNothing = false;
    }

    /** The installed program of a family lane, whose outputs its window opens. */
    private PackPipelines.PackEntity familyPipeline(ChimeraEntityBridge.Family family) {
        return switch (family) {
            case ENTITY -> this.entity;
            case ENTITY_TRANSLUCENT -> this.translucentEntity;
            case SPIDER_EYES -> this.spiderEyes;
            case BLOCK -> this.block;
            // Block-entity models and text draw in gbuffers_block_translucent's own target window.
            case BLOCK_TRANSLUCENT -> this.blockTranslucent != null ? this.blockTranslucent : this.blockTranslucentText;
            case MOVING_BLOCK -> this.movingBlock;
            case DAMAGED_BLOCK -> this.damagedBlock;
            case HAND -> this.hand == null ? null : this.hand.primary();
            case HAND_WATER -> this.handWater == null ? null : this.handWater.primary();
            // The shadow pass owns the shadow map's attachments; casters open no window.
            case SHADOW_ENTITIES -> this.shadowEntities;
            case SHADOW_BLOCK -> this.shadowBlock;
            default -> null;
        };
    }

    /** The authored outputs of a lane's installed program, or null when the lane has none. */
    GeometryOutputPlan laneOutputs(ChimeraEntityBridge.Family family) {
        PackPipelines.PackEntity entity = familyPipeline(family);
        if (entity != null) return entity.outputPlan();
        PackPipelines.PackParticle lane = particlePipeline(family);
        return lane == null ? null : lane.outputPlan();
    }

    /** A particle, beacon-beam or host-layout lane retains its own authored output contract. */
    private PackPipelines.PackParticle particlePipeline(ChimeraEntityBridge.Family family) {
        return switch (family) {
            case PARTICLE -> this.particle;
            case PARTICLE_TRANSLUCENT -> this.translucentParticle;
            case BEACON_BEAM -> this.beaconBeam;
            default -> this.hostLayout.get(family);
        };
    }

    /** The lanes these pipelines give the family bridge, each field into its namesake. */
    InstalledLanes lanes(PackEntityIdResolver entityIds) {
        EnumMap<ChimeraEntityBridge.Family, GraphicsPipeline> hostLayoutPipelines =
                new EnumMap<>(ChimeraEntityBridge.Family.class);
        this.hostLayout.forEach((family, lane) -> hostLayoutPipelines.put(family, pipeline(lane)));
        return new InstalledLanes(
                pipeline(this.entity),
                pipeline(this.translucentEntity),
                pipeline(this.translucentEntityText),
                pipeline(this.spiderEyes),
                pipeline(this.block),
                pipeline(this.blockTranslucent),
                pipeline(this.blockTranslucentText),
                pipeline(this.movingBlock),
                pipeline(this.damagedBlock),
                pipeline(this.beaconBeam),
                this.hand,
                this.handWater,
                pipeline(this.particle),
                pipeline(this.translucentParticle),
                hostLayoutPipelines,
                pipeline(this.shadowEntities),
                pipeline(this.shadowMovingBlock),
                pipeline(this.shadowBlock),
                entityIds);
    }

    private static GraphicsPipeline pipeline(PackPipelines.PackEntity entity) {
        return entity == null ? null : entity.pipeline();
    }

    private static GraphicsPipeline pipeline(PackPipelines.PackParticle lane) {
        return lane == null ? null : lane.pipeline();
    }
}
