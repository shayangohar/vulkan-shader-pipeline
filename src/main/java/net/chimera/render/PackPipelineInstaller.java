package net.chimera.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.HostLayoutLane;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.FamilyAdapterRegistry;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackPlan;
import net.chimera.shaderpack.PackProgram;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Builds one pack session's pipelines, family by family, into the pass's pipeline set: admits
 * each program the plan and the session's resources allow, builds it, attaches its storage
 * buffers and image bindings, and records its runtime disposition in the conformance report.
 */
final class PackPipelineInstaller {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    /** Diagnostic: -Dchimera.traceTransitions logs every chimera image transition. */
    private static final boolean TRACE_TRANSITIONS = ChimeraRenderer.debugFlag("chimera.traceTransitions");
    private final ChimeraMainPass pass;

    private final PackPipelineSet pipelines;
    private final List<PackProgram> programs;
    // The session's plan, report and resource owners; each is null when the session has none.
    private final PackPlan plan;
    private final ConformanceReport report;
    private final PackStorageBufferOwner storage;
    private final PackAdvancedImageOwner advancedImages;
    private final PackResourceOwner resources;
    private final PackShadowCompute shadowCompute;

    /** An installer for the session the pass has loaded. */
    PackPipelineInstaller(ChimeraMainPass pass) {
        this.pass = pass;
        this.pipelines = pass.pipelines;
        this.programs = pass.packPrograms;
        this.plan = pass.packPlan;
        this.report = pass.conformanceReport;
        this.storage = pass.packStorageBufferOwner;
        this.advancedImages = pass.packAdvancedImageOwner;
        this.resources = pass.packResourceOwner;
        this.shadowCompute = pass.packShadowCompute;
    }

    /**
     * Builds every program the plan admits, the producers of advanced images before their
     * consumers. Pack post programs and the host resolve share one fullscreen coordinate contract;
     * target flips are side-selection decisions, not per-pass UV transforms.
     */
    void installPrograms() {
        String fixedVertex = ChimeraShaderLoader.loadSource("chimera_composite/chimera_composite.vsh");
        List<PackProgram> pipelinePrograms = new ArrayList<>(programs);
        if (plan != null) {
            pipelinePrograms.sort(Comparator
                    .comparing((PackProgram value) ->
                            plan.advancedResources().graphicsImages(value.name()).isEmpty())
                    .thenComparing(PackProgram::name));
        }
        for (PackProgram program : pipelinePrograms) {
            String name = program.name();
            PackProgramPlan programPlan = plan == null ? null : plan.program(name);
            if (authoredCloudDrawsNothing(name, programPlan)) {
                pipelines.cloudsDrawNothing = true;
                markFamilyPipelineInstalled(name, PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT);
                continue;
            }
            String refusal = runtimeRefusal(name, programPlan);
            if (refusal != null) {
                markFamilyPipelineFallback(name, refusal);
                continue;
            }
            installProgram(program, programPlan, fixedVertex);
        }
    }

    /** Why a planned program may not install this session, or null when it may. */
    String runtimeRefusal(String name, PackProgramPlan programPlan) {
        boolean reportAllowed = report == null || report.shouldAttempt(name);
        boolean planAllowed = plan == null || plan.shouldAttempt(name);
        if (!reportAllowed || !planAllowed) {
            if (plan != null && plan.isProgramDisabled(name)) {
                return "PROGRAM_DISABLED:" + name;
            }
            if (plan != null && plan.isProgramAlias(name)) {
                return "PROGRAM_ALIAS_RUNTIME_FALLBACK:" + name;
            }
            if (plan != null && !plan.resources().programAllowed(name)) {
                return plan.resources().unavailableReason(name);
            }
            return programPlan != null && !programPlan.executable() ? "PLAN_INELIGIBLE:" + name : "CONTRACT_UNSUPPORTED";
        }
        PackAdvancedResourcePlan advanced = advancedResources();
        boolean advancedProducer = plan != null && !advanced.graphicsImages(name).isEmpty();
        boolean advancedAllowed = plan == null
                || !advanced.dependentPrograms().contains(name)
                || (advancedImages != null
                && advanced.capabilityPossible()
                && advancedImages.resourcesAvailable()
                && shadowCompute != null
                && shadowCompute.isInstalled()
                && (advancedProducer || advancedImages.producerCandidatesReady()));
        if (!advancedAllowed) {
            return "ADVANCED_RESOURCE_BRIDGE_UNAVAILABLE";
        }
        boolean storageAllowed = plan == null
                || !advanced.bufferDependentPrograms().contains(name)
                || (storage != null && storage.programAvailable(name));
        if (!storageAllowed) {
            return storage == null
                    ? "STORAGE_BUFFER_OWNER_UNAVAILABLE" : storage.failureReason(name);
        }
        if (resources != null && !resources.programAvailable(name)) {
            return resources.failureDeviationForProgram(name);
        }
        return null;
    }

    PackAdvancedResourcePlan advancedResources() {
        return plan == null ? PackAdvancedResourcePlan.empty() : plan.advancedResources();
    }

    TerrainMaterialPlan terrainMaterial() {
        return plan == null ? TerrainMaterialPlan.legacy() : plan.terrainMaterial();
    }

    /** Installs one admitted program's pipelines through its family's installer. */
    void installProgram(PackProgram program, PackProgramPlan programPlan, String fixedVertex) {
        String name = program.name();
        if (name.equals("shadow")) {
            installShadow(name, programPlan);
        } else if (FamilyAdapterRegistry.isShadowEntityFamily(name)) {
            installShadowEntities(name, programPlan);
        } else if (PostTargetPlan.isPostProgramName(name)) {
            installPost(name, programPlan, fixedVertex);
        } else if (name.equals("gbuffers_terrain")) {
            installTerrain(program, name, programPlan);
        } else if (name.equals("gbuffers_water")) {
            installWater(name, programPlan);
        } else if (name.equals("gbuffers_skybasic") || name.equals("gbuffers_skytextured")) {
            installSky(name, programPlan);
        } else if (name.equals("gbuffers_clouds")) {
            installCloud(name, programPlan);
        } else if (name.equals("gbuffers_entities")) {
            installEntities(name, programPlan);
        } else if (FamilyAdapterRegistry.isWorldEntityFamily(name)) {
            installWorldEntity(name, programPlan);
        } else if (name.equals("gbuffers_block")) {
            installBlock(name, programPlan);
        } else if (FamilyAdapterRegistry.isBlockFamily(name)) {
            installDamagedBlock(name, programPlan);
        } else if (name.equals("gbuffers_hand") || FamilyAdapterRegistry.isHandFamily(name)) {
            installHand(name, programPlan);
        } else if (name.equals("gbuffers_particles")) {
            installParticles(name, programPlan);
        } else if (FamilyAdapterRegistry.isParticleLike(name)) {
            installTranslucentParticles(name, programPlan);
        } else if (!HostLayoutLane.forProgram(name).isEmpty()) {
            installHostLayoutLanes(name, programPlan);
        } else {
            markFamilyPipelineFallback(name, "CONTRACT_UNSUPPORTED");
        }
    }

    /** The shadow terrain program, on the pack-wide terrain format. */
    void installShadow(String name, PackProgramPlan programPlan) {
        PackPipelines.PackShadow shadow = PackPipelines.buildShadow(programPlan,
                terrainMaterial(),
                advancedResources(), storage);
        if (shadow == null) {
            if (report != null) {
                report.markRuntime(name,
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "PIPELINE_BUILD_FAILED");
            }
            LOGGER.warn("[chimera] pack shadow: fallback=IDENTITY (build failed)");
            return;
        }
        pipelines.shadowPipeline = shadow.pipeline();
        pipelines.shadowSlots = shadow.samplerSlots();
        if (!attachPackStoragePipeline(name, shadow.pipeline(), shadow.imageBindings())) {
            shadow.pipeline().cleanUp();
            pipelines.shadowPipeline = null;
            return;
        }
        if (advancedImages != null) {
            // The producer must be available before its terrain
            // consumer can build. Runtime use remains gated by the
            // committed producer set and usePackShadowRuntime().
            advancedImages.markGraphicsProducerCandidate(name);
        }
        if (report != null) {
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED, null);
        }
        if (storage != null) {
            storage.markPipelineInstalled(name);
        }
        LOGGER.info("[chimera] pack shadow: ok (samplers={})",
                Arrays.toString(shadow.samplerSlots()));
        if (TRACE_TRANSITIONS) {
            LOGGER.info("[chimera] pack shadow converted fragment:\n{}", shadow.convertedFragment());
        }
    }

    /** shadow_entities, the entity and block-entity casters of the shadow pass. */
    void installShadowEntities(String name, PackProgramPlan programPlan) {
        PackPipelines.PackEntity casters = PackPipelines.buildShadowEntities(programPlan,
                advancedResources(), storage);
        if (casters == null) {
            markFamilyPipelineFallback(name, "SHADOW_ENTITY_PIPELINE_BUILD_FAILED");
            return;
        }
        if (!attachPackStoragePipeline(name, casters.pipeline(), casters.imageBindings())) {
            casters.pipeline().cleanUp();
            return;
        }
        pipelines.shadowEntities = casters;
        markFamilyPipelineInstalled(name, "SHADOW_ENTITY_PIPELINE_INSTALLED");
    }

    /** A post program; composite and final are also kept for the resolve and present seams. */
    void installPost(String name, PackProgramPlan programPlan, String fixedVertex) {
        PackPipelines.PackPost post = PackPipelines.buildPost(programPlan, fixedVertex,
                advancedResources(), storage);
        if (post == null) {
            if (report != null) {
                report.markRuntime(name,
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "PIPELINE_BUILD_FAILED");
            }
            LOGGER.warn("[chimera] pack {}: fallback=IDENTITY (build failed)", name);
            return;
        }
        if (!attachPackStoragePipeline(name, post.pipeline(), post.imageBindings())) {
            post.pipeline().cleanUp();
            return;
        }
        pipelines.postStages.add(post);
        if (name.equals("composite")) {
            pipelines.compositePipeline = post.pipeline();
            pipelines.compositeSlots = post.samplerSlots();
            pipelines.compositeSamplerNames = post.samplerNames();
        } else if (name.equals("final")) {
            pipelines.finalPipeline = post.pipeline();
            pipelines.finalSlots = post.samplerSlots();
            pipelines.finalSamplerNames = post.samplerNames();
            pipelines.finalPost = post;
        }
        if (report != null) {
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED, null);
        }
        if (storage != null) {
            storage.markPipelineInstalled(name);
        }
        LOGGER.info("[chimera] pack {}: ok (samplers={})", name, Arrays.toString(post.samplerSlots()));
        if (TRACE_TRANSITIONS) {
            LOGGER.info("[chimera] pack {} converted fragment:\n{}", name, post.convertedFragment());
        }
    }

    /** gbuffers_terrain, which replaces Chimera's terrain pipeline for solid and cutout layers. */
    void installTerrain(PackProgram program, String name, PackProgramPlan programPlan) {
        PackPipelines.PackTerrain terrain = PackPipelines.buildTerrain(programPlan,
                ChimeraShaderLoader.loadSource("chimera_terrain/chimera_terrain.vsh"),
                terrainMaterial(),
                false, pass.packGeometryTargetFormat(),
                advancedResources(), storage);
        if (terrain == null) {
            if (report != null) {
                report.markRuntime(name,
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "PIPELINE_BUILD_FAILED");
                if (program.vertexSource() != null) {
                    report.markRuntime(name,
                            ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                            "TERRAIN_VERTEX_PIPELINE_BUILD_FAILED");
                }
            }
            LOGGER.warn("[chimera] pack gbuffers_terrain: fallback=IDENTITY (build failed)");
            return;
        }
        pipelines.terrainRoute = terrain;
        pipelines.terrainPipeline = terrain.pipeline();
        pipelines.terrainSlots = terrain.samplerSlots();
        if (!attachPackStoragePipeline(name, terrain.pipeline(), terrain.imageBindings())) {
            terrain.pipeline().cleanUp();
            pipelines.terrainRoute = null;
            pipelines.terrainPipeline = null;
            pipelines.terrainSlots = null;
            return;
        }
        if (advancedImages != null) {
            advancedImages.commitGraphicsProducer("shadow");
        }
        ChimeraTerrainPipelines.setGeometryOverride(terrain.pipeline());
        if (report != null) {
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED, null);
        }
        LOGGER.info("[chimera] pack gbuffers_terrain: ok (geometry, samplers={})",
                Arrays.toString(terrain.samplerSlots()));
        if (TRACE_TRANSITIONS) {
            LOGGER.info("[chimera] pack gbuffers_terrain converted fragment:\n{}", terrain.convertedFragment());
        }
        LOGGER.info("[chimera] pack gbuffers_terrain: outputs={} dynamicAttachments={}",
                terrain.outputPlan() == null ? List.of(0) : terrain.outputPlan().targetSlots(),
                terrain.requiresDynamicAttachments());
    }

    /** gbuffers_water, on the host translucent terrain path. */
    void installWater(String name, PackProgramPlan programPlan) {
        PackPipelines.PackTerrain water = PackPipelines.buildTranslucent(programPlan,
                ChimeraShaderLoader.loadSource("chimera_terrain/chimera_terrain.vsh"),
                terrainMaterial(),
                false, pass.packGeometryTargetFormat(),
                advancedResources(), storage);
        if (water == null) {
            if (report != null) {
                report.markRuntime(name,
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "PIPELINE_BUILD_FAILED");
            }
            LOGGER.warn("[chimera] pack gbuffers_water: fallback=IDENTITY (build failed)");
            return;
        }
        pipelines.waterRoute = water;
        pipelines.waterPipeline = water.pipeline();
        pipelines.waterSlots = water.samplerSlots();
        if (!attachPackStoragePipeline(name, water.pipeline(), water.imageBindings())) {
            water.pipeline().cleanUp();
            pipelines.waterRoute = null;
            pipelines.waterPipeline = null;
            pipelines.waterSlots = null;
            return;
        }
        if (advancedImages != null) {
            advancedImages.markGraphicsProducerCandidate(name);
            advancedImages.commitGraphicsProducer(name);
        }
        ChimeraTerrainPipelines.setTranslucentOverride(water.pipeline());
        if (report != null) {
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED, null);
        }
        LOGGER.info("[chimera] pack gbuffers_water: ok (translucent, samplers={})",
                Arrays.toString(water.samplerSlots()));
        if (TRACE_TRANSITIONS) {
            LOGGER.info("[chimera] pack gbuffers_water converted fragment:\n{}",
                    water.convertedFragment());
        }
        LOGGER.info("[chimera] pack gbuffers_water: outputs={} dynamicAttachments={}",
                water.outputPlan() == null ? List.of(0) : water.outputPlan().targetSlots(),
                water.requiresDynamicAttachments());
    }

    /** A sky program, one pipeline per native sky layout. */
    void installSky(String name, PackProgramPlan programPlan) {
        PackPipelines.PackSkyFamily sky = PackPipelines.buildSky(programPlan,
                advancedResources(), storage);
        if (sky == null) {
            markFamilyPipelineFallback(name, "SKY_PIPELINE_BUILD_FAILED");
            return;
        }
        if (name.equals("gbuffers_skybasic")) {
            pipelines.skyBasic = sky;
        } else {
            pipelines.skyTextured = sky;
        }
        boolean attached = true;
        for (var variant : sky.variants().values()) {
            attached &= attachPackStoragePipeline(name, variant.pipeline(), variant.imageBindings());
        }
        if (!attached) {
            sky.cleanUp();
            if (name.equals("gbuffers_skybasic")) pipelines.skyBasic = null;
            else pipelines.skyTextured = null;
            return;
        }
        markFamilyPipelineInstalled(name, "SKY_PIPELINE_INSTALLED");
        LOGGER.info("[chimera] pack {}: ok (sky installed, native layouts={})", name, sky.variants().size());
    }

    /** The cloud program. */
    void installCloud(String name, PackProgramPlan programPlan) {
        PackPipelines.PackSky cloud = PackPipelines.buildCloud(programPlan,
                advancedResources(), storage);
        if (cloud == null) {
            markFamilyPipelineFallback(name, "CLOUD_PIPELINE_BUILD_FAILED");
            return;
        }
        pipelines.cloud = cloud;
        if (!attachPackStoragePipeline(name, cloud.pipeline(), cloud.imageBindings())) {
            cloud.pipeline().cleanUp();
            pipelines.cloud = null;
            return;
        }
        markFamilyPipelineInstalled(name, "CLOUD_PIPELINE_INSTALLED");
        LOGGER.info("[chimera] pack gbuffers_clouds: ok (cloud pipeline installed, samplers={})",
                Arrays.toString(cloud.samplerSlots()));
    }

    /** gbuffers_entities on the delayed entity model batch. */
    void installEntities(String name, PackProgramPlan programPlan) {
        PackPipelines.PackEntity entity = PackPipelines.buildEntity(programPlan,
                advancedResources(), storage);
        if (entity == null) {
            if (report != null) {
                report.markRuntime(name,
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "ENTITY_PIPELINE_BUILD_FAILED");
            }
            LOGGER.warn("[chimera] pack gbuffers_entities: fallback=IDENTITY "
                    + "(ENTITY_PIPELINE_BUILD_FAILED)");
            return;
        }
        pipelines.entity = entity;
        if (!attachPackStoragePipeline(name, entity.pipeline(), entity.imageBindings())) {
            entity.pipeline().cleanUp();
            pipelines.entity = null;
            return;
        }
        if (report != null) {
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED,
                    "ENTITY_VERTEX_FORMAT_EXTENDED");
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED,
                    "ENTITY_PIPELINE_INSTALLED");
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED,
                    "ENTITY_STATE_FIXED_TO_HOST");
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED,
                    "ENTITY_BATCH_ORIGIN_SPLIT");
        }
        LOGGER.info("[chimera] pack gbuffers_entities: ok (entity pipeline installed, "
                + "stride={}, samplers={})",
                ChimeraVertexFormats.EXTENDED_ENTITY.getVertexSize(),
                Arrays.toString(entity.samplerSlots()));
        if (TRACE_TRANSITIONS) {
            LOGGER.info("[chimera] pack gbuffers_entities converted vertex:\n{}",
                    entity.convertedVertex());
            LOGGER.info("[chimera] pack gbuffers_entities converted fragment:\n{}",
                    entity.convertedFragment());
        }
    }

    /** gbuffers_entities_translucent or gbuffers_spidereyes; the translucent program also gets its text layout. */
    void installWorldEntity(String name, PackProgramPlan programPlan) {
        PackPipelines.PackEntity entity = PackPipelines.buildEntityFamily(programPlan,
                advancedResources(), storage);
        if (entity == null) {
            markFamilyPipelineFallback(name, "ENTITY_PIPELINE_BUILD_FAILED");
            return;
        }
        if (name.equals("gbuffers_entities_translucent")) {
            pipelines.translucentEntity = entity;
        } else {
            pipelines.spiderEyes = entity;
        }
        if (!attachPackStoragePipeline(name, entity.pipeline(), entity.imageBindings())) {
            entity.pipeline().cleanUp();
            if (name.equals("gbuffers_entities_translucent")) {
                pipelines.translucentEntity = null;
            } else {
                pipelines.spiderEyes = null;
            }
            return;
        }
        markFamilyPipelineInstalled(name, name.equals("gbuffers_entities_translucent")
                ? "TRANSLUCENT_ENTITY_INSTALLED" : "SPIDER_EYES_INSTALLED");
        if (name.equals("gbuffers_entities_translucent")) {
            pipelines.translucentEntityText = buildTextVariant(name, programPlan);
        }
    }

    /** gbuffers_block, with its text layout for block-entity text. */
    void installBlock(String name, PackProgramPlan programPlan) {
        PackPipelines.PackEntity block = PackPipelines.buildBlock(programPlan,
                advancedResources(), storage);
        if (block == null) {
            if (report != null) {
                report.markRuntime(name,
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "BLOCK_PIPELINE_BUILD_FAILED");
            }
            LOGGER.warn("[chimera] pack gbuffers_block: fallback=IDENTITY "
                    + "(BLOCK_PIPELINE_BUILD_FAILED)");
            return;
        }
        pipelines.block = block;
        if (!attachPackStoragePipeline(name, block.pipeline(), block.imageBindings())) {
            block.pipeline().cleanUp();
            pipelines.block = null;
            return;
        }
        if (report != null) {
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED,
                    "FAMILY_ADAPTER_INSTALLED");
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED,
                    "ENTITY_VERTEX_FORMAT_EXTENDED");
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED,
                    "BLOCK_ENTITY_ID_DEFAULTED");
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED,
                    "ENTITY_STATE_FIXED_TO_HOST");
        }
        LOGGER.info("[chimera] pack gbuffers_block: ok (block adapter installed, "
                + "stride={}, samplers={})",
                ChimeraVertexFormats.EXTENDED_ENTITY.getVertexSize(),
                Arrays.toString(block.samplerSlots()));
        pipelines.blockText = buildTextVariant(name, programPlan);
    }

    /** gbuffers_damagedblock on the host block format. */
    void installDamagedBlock(String name, PackProgramPlan programPlan) {
        PackPipelines.PackEntity damaged = PackPipelines.buildEntityFamily(programPlan,
                advancedResources(), storage);
        if (damaged == null) {
            markFamilyPipelineFallback(name, "DAMAGED_BLOCK_PIPELINE_BUILD_FAILED");
            return;
        }
        if (!attachPackStoragePipeline(name, damaged.pipeline(), damaged.imageBindings())) {
            damaged.pipeline().cleanUp();
            return;
        }
        pipelines.damagedBlock = damaged;
        markFamilyPipelineInstalled(name, "DAMAGED_BLOCK_HOST_FORMAT_INSTALLED");
    }

    /** gbuffers_hand or gbuffers_hand_water, one pipeline per native hand mesh shape. */
    void installHand(String name, PackProgramPlan programPlan) {
        var hand = PackPipelines.buildHand(programPlan,
                advancedResources(),
                storage);
        if (hand == null) {
            markFamilyPipelineFallback(name, "HAND_PIPELINE_BUILD_FAILED");
        } else {
            boolean ready = true;
            for (var variant : hand.variants().values()) {
                if (!attachPackStoragePipeline(name, variant.pipeline(), variant.imageBindings())) {
                    ready = false;
                    break;
                }
            }
            if (!ready) { hand.cleanUp(); return; }
            if (name.equals("gbuffers_hand")) pipelines.hand = hand;
            else pipelines.handWater = hand;
            markFamilyPipelineInstalled(name, "HAND_TWO_PHASE_INSTALLED");
        }
    }

    /** gbuffers_particles on the host particle format. */
    void installParticles(String name, PackProgramPlan programPlan) {
        PackPipelines.PackParticle particle = PackPipelines.buildParticle(programPlan,
                advancedResources(), storage);
        if (particle == null) {
            if (report != null) {
                report.markRuntime(name,
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "PARTICLE_PIPELINE_BUILD_FAILED");
            }
            LOGGER.warn("[chimera] pack gbuffers_particles: fallback=IDENTITY "
                    + "(PARTICLE_PIPELINE_BUILD_FAILED)");
            return;
        }
        pipelines.particle = particle;
        if (!attachPackStoragePipeline(name, particle.pipeline(), particle.imageBindings())) {
            particle.pipeline().cleanUp();
            pipelines.particle = null;
            return;
        }
        if (report != null) {
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED,
                    "FAMILY_ADAPTER_INSTALLED");
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED,
                    "PARTICLE_STATE_FIXED_TO_HOST");
        }
        LOGGER.info("[chimera] pack gbuffers_particles: ok (particle adapter installed, "
                + "stride={}, samplers={})",
                DefaultVertexFormat.PARTICLE.getVertexSize(),
                Arrays.toString(particle.samplerSlots()));
    }

    /** gbuffers_particles_translucent on the host particle format. */
    void installTranslucentParticles(String name, PackProgramPlan programPlan) {
        PackPipelines.PackParticle particle = PackPipelines.buildParticleFamily(programPlan,
                advancedResources(), storage);
        if (particle == null) {
            markFamilyPipelineFallback(name, "PARTICLE_PIPELINE_BUILD_FAILED");
            return;
        }
        pipelines.translucentParticle = particle;
        if (!attachPackStoragePipeline(name, particle.pipeline(), particle.imageBindings())) {
            particle.pipeline().cleanUp();
            pipelines.translucentParticle = null;
            return;
        }
        markFamilyPipelineInstalled(name, "PARTICLE_TRANSLUCENT_INSTALLED");
    }

    boolean attachPackStoragePipeline(String program, GraphicsPipeline pipeline,
            ProgramImageBindingManifest manifest) {
        pass.programImages.put(pipeline, new ChimeraMainPass.ProgramImages(program, manifest,
                pass.shadowPass.shadowSamplerSymbols(program)));
        if (storage == null
                || plan == null
                || plan.advancedResources().storageBuffers(program).isEmpty()) {
            return true;
        }
        if (storage.attachPipeline(pipeline, program)) {
            return true;
        }
        String reason = storage.failureReason(program);
        if (report != null) {
            report.markRuntime(program,
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                    reason);
        }
        LOGGER.warn("[chimera] pack {}: fallback=IDENTITY ({})", program, reason);
        return false;
    }

    /** The pack's own resolved cloud program is active and provably draws nothing. */
    boolean authoredCloudDrawsNothing(String name, PackProgramPlan programPlan) {
        return programPlan != null && programPlan.cloudDrawsNothing()
                && plan != null
                && !plan.isProgramDisabled(name)
                && !plan.isProgramAlias(name)
                && plan.resources().programAllowed(name);
    }

    /**
     * The program on Iris's GLYPH text layout, sharing its descriptors and outputs.
     * Without it the text keeps its host draw.
     */
    PackPipelines.PackEntity buildTextVariant(String name, PackProgramPlan programPlan) {
        PackPipelines.PackEntity text = PackPipelines.buildLayoutVariant(programPlan,
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                advancedResources(),
                storage);
        if (text == null) {
            LOGGER.warn("[chimera] pack {}: text layout variant unavailable; text stays host-drawn", name);
            return null;
        }
        if (!attachPackStoragePipeline(name, text.pipeline(), text.imageBindings())) {
            text.pipeline().cleanUp();
            return null;
        }
        LOGGER.info("[chimera] pack {}: text layout variant installed (stride={})", name,
                ChimeraVertexFormats.EXTENDED_GLYPH.getVertexSize());
        return text;
    }

    /**
     * Builds the host-layout lanes one program serves (HostLayoutLane), world lane first. A
     * world lane that fails falls back to the host draw for this program. A hand variant
     * (first-person glint) needs its world lane; without it that draw stays host-drawn. A
     * shadow lane that fails leaves its draws out of the shadow map.
     */
    void installHostLayoutLanes(String name, PackProgramPlan programPlan) {
        PackAdvancedResourcePlan advanced = advancedResources();
        for (HostLayoutLane lane : HostLayoutLane.forProgram(name)) {
            PackPipelines.PackParticle built = switch (lane) {
                case WEATHER -> PackPipelines.buildParticleFamily(programPlan, advanced, storage);
                case HAND_GLINT -> PackPipelines.buildHandGlint(programPlan, advanced, storage);
                default -> PackPipelines.buildHostLayout(programPlan, advanced, storage);
            };
            if (built == null) {
                if (lane.pass() == HostLayoutLane.Pass.HAND) {
                    LOGGER.warn("[chimera] pack {}: {} variant unavailable; that draw stays host-drawn", name,
                            lane.name().toLowerCase());
                    continue;
                }
                markFamilyPipelineFallback(name, lane + "_PIPELINE_BUILD_FAILED");
                return;
            }
            if (!attachPackStoragePipeline(name, built.pipeline(), built.imageBindings())) {
                // The storage owner has reported this fallback.
                built.pipeline().cleanUp();
                if (lane.pass() == HostLayoutLane.Pass.HAND) continue;
                return;
            }
            pipelines.hostLayout.put(lane.family(), built);
            if (lane.pass() == HostLayoutLane.Pass.HAND) {
                LOGGER.info("[chimera] pack {}: {} variant installed", name, lane.name().toLowerCase());
            } else {
                markFamilyPipelineInstalled(name, lane + "_PIPELINE_INSTALLED");
            }
        }
    }

    void markFamilyPipelineInstalled(String name, String deviation) {
        if (report != null) {
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.INSTALLED, deviation);
        }
        if (storage != null) {
            storage.markPipelineInstalled(name);
        }
        LOGGER.info("[chimera] pack {}: ok ({})", name, deviation);
    }

    void markFamilyPipelineFallback(String name, String reason) {
        if (report != null) {
            report.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, reason);
        }
        LOGGER.warn("[chimera] pack {}: fallback=IDENTITY ({})", name, reason);
    }
}
