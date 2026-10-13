package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Table-driven standard family registry. Unknown families remain host-only. */
public final class FamilyAdapterRegistry {
    private static final Map<String, FamilyAdapterPlan> CONTRACTS = Stream.of(
            plan("gbuffers_terrain", FamilyAdapterPlan.Family.TERRAIN,
                    UniformRegistry.Stage.GEOMETRY, FamilyAdapterPlan.VertexContract.FIXED_TERRAIN),
            plan("gbuffers_water", FamilyAdapterPlan.Family.WATER,
                    UniformRegistry.Stage.TRANSLUCENT, FamilyAdapterPlan.VertexContract.FIXED_TERRAIN),
            plan("shadow", FamilyAdapterPlan.Family.SHADOW,
                    UniformRegistry.Stage.SHADOW, FamilyAdapterPlan.VertexContract.FIXED_TERRAIN),
            // Iris SHADOW_ENTITIES_CUTOUT: every entity and block-entity pipeline in the shadow
            // pass, on the entity layout (the end portal and gateway too, after the entitySolid swap).
            plan("shadow_entities", FamilyAdapterPlan.Family.SHADOW_ENTITIES,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            // Iris IrisPipelines.assignToShadow keys drawn on the host's own layout. Each key resolves
            // its Iris program through PackResolutionPlan (shadow_lightning is itself an Iris program;
            // the others name a ShaderKey and select shadow or shadow_entities).
            plan("shadow_lines", FamilyAdapterPlan.Family.SHADOW_LINES,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_LINE),
            plan("shadow_leash", FamilyAdapterPlan.Family.SHADOW_LEASH,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_LEASH),
            plan("shadow_lightning", FamilyAdapterPlan.Family.SHADOW_LIGHTNING,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_LIGHTNING),
            plan("shadow_glint", FamilyAdapterPlan.Family.SHADOW_GLINT,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_GLINT),
            plan("shadow_text", FamilyAdapterPlan.Family.SHADOW_TEXT,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_TEXT),
            plan("shadow_text_bg", FamilyAdapterPlan.Family.SHADOW_TEXT_BG,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_TEXT_BACKGROUND),
            plan("shadow_basic", FamilyAdapterPlan.Family.SHADOW_BASIC,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.HOST_WATER_MASK),
            plan("gbuffers_entities", FamilyAdapterPlan.Family.ENTITY,
                    UniformRegistry.Stage.ENTITY, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_entities_translucent",
                    FamilyAdapterPlan.Family.ENTITY_TRANSLUCENT, UniformRegistry.Stage.ENTITY,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_spidereyes",
                    FamilyAdapterPlan.Family.SPIDER_EYES, UniformRegistry.Stage.ENTITY,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_block", FamilyAdapterPlan.Family.BLOCK,
                    UniformRegistry.Stage.BLOCK, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            // Iris TEXT_BE: a block entity's text, planned on the entity layout and installed only
            // as its GLYPH layout variant.
            plan("gbuffers_block_translucent", FamilyAdapterPlan.Family.BLOCK_TRANSLUCENT,
                    UniformRegistry.Stage.BLOCK, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_damagedblock",
                    FamilyAdapterPlan.Family.DAMAGED_BLOCK, UniformRegistry.Stage.BLOCK,
                    FamilyAdapterPlan.VertexContract.HOST_BLOCK),
            plan("gbuffers_hand", FamilyAdapterPlan.Family.HAND,
                    UniformRegistry.Stage.HAND, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_hand_water",
                    FamilyAdapterPlan.Family.HAND_WATER, UniformRegistry.Stage.HAND,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY),
            plan("gbuffers_particles", FamilyAdapterPlan.Family.PARTICLE,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_PARTICLE),
            plan("gbuffers_particles_translucent",
                    FamilyAdapterPlan.Family.PARTICLE_TRANSLUCENT, UniformRegistry.Stage.PARTICLE,
                    FamilyAdapterPlan.VertexContract.HOST_PARTICLE),
            plan("gbuffers_skybasic", FamilyAdapterPlan.Family.SKY,
                    UniformRegistry.Stage.SKY, FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR),
            plan("gbuffers_skytextured", FamilyAdapterPlan.Family.SKY,
                    UniformRegistry.Stage.SKY, FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR_UV),
            plan("gbuffers_clouds", FamilyAdapterPlan.Family.CLOUD,
                    UniformRegistry.Stage.CLOUD, FamilyAdapterPlan.VertexContract.CLOUD_POSITION_COLOR),
            plan("gbuffers_weather", FamilyAdapterPlan.Family.WEATHER,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_PARTICLE),
            // Iris LINES, LINES_TRANSLUCENT and SECONDARY_BLOCK_OUTLINE (ShaderKey.LINES) on the
            // host line layout, served like weather from the host transform blocks.
            plan("gbuffers_line", FamilyAdapterPlan.Family.LINE,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_LINE),
            // Iris GLINT (ShaderKey.GLINT) on the host POSITION_TEX glint layers.
            plan("gbuffers_armor_glint", FamilyAdapterPlan.Family.GLINT,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_GLINT),
            // Iris LIGHTNING (LIGHTNING, DRAGON_RAYS) on the host POSITION_COLOR layout.
            plan("gbuffers_lightning", FamilyAdapterPlan.Family.LIGHTNING,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_LIGHTNING),
            // Iris BEACON (BEACON_BEAM_OPAQUE, BEACON_BEAM_TRANSLUCENT) on the host BLOCK layout.
            plan("gbuffers_beaconbeam", FamilyAdapterPlan.Family.BEACON_BEAM,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_BEACON),
            // Iris LEASH (ProgramId.Basic) on the host POSITION_COLOR_LIGHTMAP lead strip.
            plan("gbuffers_basic", FamilyAdapterPlan.Family.BASIC,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_LEASH)
    ).collect(Collectors.toUnmodifiableMap(FamilyAdapterPlan::programName, plan -> plan));

    private FamilyAdapterRegistry() {}

    public static FamilyAdapterPlan forProgram(String name) {
        if (name == null || name.isBlank()) {
            return FamilyAdapterPlan.unsupported(name);
        }
        FamilyAdapterPlan exact = CONTRACTS.get(name);
        if (exact != null) {
            return exact;
        }
        if (PostTargetPlan.isPostProgramName(name)) {
            return new FamilyAdapterPlan(name, FamilyAdapterPlan.Family.POST,
                    UniformRegistry.Stage.POST, FamilyAdapterPlan.VertexContract.FULLSCREEN,
                    true, List.of());
        }
        if (name.startsWith("gbuffers_sky")) {
            return new FamilyAdapterPlan(name, FamilyAdapterPlan.Family.SKY, UniformRegistry.Stage.SKY,
                    FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR, true, List.of());
        }
        if (name.startsWith("gbuffers_cloud")) {
            return new FamilyAdapterPlan(name, FamilyAdapterPlan.Family.CLOUD, UniformRegistry.Stage.CLOUD,
                    FamilyAdapterPlan.VertexContract.CLOUD_POSITION_COLOR, true, List.of());
        }
        if (name.startsWith("gbuffers_weather")) {
            return unsupported(name, FamilyAdapterPlan.Family.WEATHER);
        }
        if (name.startsWith("gbuffers_")) {
            return unsupported(name, FamilyAdapterPlan.Family.SPECIAL);
        }
        return FamilyAdapterPlan.unsupported(name);
    }

    public static UniformRegistry.Stage stageFor(String name) {
        return forProgram(name).stage();
    }

    public static boolean isExecutableFamily(String name) {
        return forProgram(name).executable();
    }

    public static boolean isEntityLike(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.ENTITY
                || family == FamilyAdapterPlan.Family.ENTITY_TRANSLUCENT
                || family == FamilyAdapterPlan.Family.SPIDER_EYES
                || family == FamilyAdapterPlan.Family.BLOCK
                || family == FamilyAdapterPlan.Family.BLOCK_TRANSLUCENT
                || family == FamilyAdapterPlan.Family.DAMAGED_BLOCK
                || family == FamilyAdapterPlan.Family.HAND
                || family == FamilyAdapterPlan.Family.HAND_WATER;
    }

    /** The programs that draw into the shadow map: shadow (terrain), shadow_entities and the shadow lanes. */
    public static boolean isShadowFamily(String name) {
        return forProgram(name).stage().shadowPass();
    }

    /** The shadow program on the entity layout (Iris ProgramId.ShadowEntities). */
    public static boolean isShadowEntityFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.SHADOW_ENTITIES;
    }

    /** A shadow-pass key drawn on the host's own layout (lines, leads, lightning, glint, text, water mask). */
    public static boolean isShadowLaneFamily(String name) {
        FamilyAdapterPlan plan = forProgram(name);
        return plan.stage().shadowPass() && plan.vertexContract().hostLayout();
    }

    /**
     * True when Iris's EntityPatcher applies to the lane's ShaderKey format (ENTITY for shadow
     * glint, GLYPH for shadow text): entityId and blockEntityId are per-vertex attributes there,
     * which the host mesh lacks, so they read the unbound value 0. Every other host-layout lane
     * reads them as Iris's CommonUniforms fallback: the draw's entity id and blockEntityId -1.
     */
    public static boolean readsEntityIdsPerVertex(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.SHADOW_GLINT || family == FamilyAdapterPlan.Family.SHADOW_TEXT;
    }

    public static boolean isParticleLike(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.PARTICLE
                || family == FamilyAdapterPlan.Family.PARTICLE_TRANSLUCENT;
    }

    public static boolean isWeatherFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.WEATHER;
    }

    public static boolean isGlintFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.GLINT;
    }

    /**
     * Lanes whose pack program runs on a host vertex layout Iris also draws natively: lines,
     * glint, lightning, beacon beams and leads, and in the shadow pass also text and the
     * water mask.
     */
    public static boolean isHostLayoutFamily(String name) {
        return forProgram(name).vertexContract().hostLayout();
    }

    /** The lane label a host-layout program's deviations carry (LINE, GLINT, SHADOW_LINES, ...), or null. */
    public static String hostLayoutLane(String name) {
        FamilyAdapterPlan plan = forProgram(name);
        return plan.vertexContract().hostLayout() ? plan.family().name() : null;
    }

    /** Every host-layout lane label. */
    public static Set<String> hostLayoutLanes() {
        return CONTRACTS.values().stream().filter(plan -> plan.vertexContract().hostLayout())
                .map(plan -> plan.family().name())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public static boolean isSkyFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.SKY;
    }

    public static boolean isCloudFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.CLOUD;
    }

    public static boolean isWorldEntityFamily(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.ENTITY
                || family == FamilyAdapterPlan.Family.ENTITY_TRANSLUCENT
                || family == FamilyAdapterPlan.Family.SPIDER_EYES;
    }

    public static boolean isBlockFamily(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.BLOCK
                || family == FamilyAdapterPlan.Family.BLOCK_TRANSLUCENT
                || family == FamilyAdapterPlan.Family.DAMAGED_BLOCK;
    }

    public static boolean isHandFamily(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.HAND
                || family == FamilyAdapterPlan.Family.HAND_WATER;
    }

    private static FamilyAdapterPlan plan(
            String name,
            FamilyAdapterPlan.Family family,
            UniformRegistry.Stage stage,
            FamilyAdapterPlan.VertexContract contract
    ) {
        return new FamilyAdapterPlan(name, family, stage, contract, true, List.of());
    }

    private static FamilyAdapterPlan unsupported(String name, FamilyAdapterPlan.Family family) {
        return new FamilyAdapterPlan(name, family, UniformRegistry.Stage.POST,
                FamilyAdapterPlan.VertexContract.HOST_FALLBACK, false,
                List.of("FAMILY_LANE_UNSUPPORTED"));
    }
}
