package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;

/** Table-driven standard family registry. Unknown families remain host-only. */
public final class FamilyAdapterRegistry {
    private static final Map<String, FamilyAdapterPlan> CONTRACTS = Map.ofEntries(
            Map.entry("gbuffers_terrain", plan("gbuffers_terrain", FamilyAdapterPlan.Family.TERRAIN,
                    UniformRegistry.Stage.GEOMETRY, FamilyAdapterPlan.VertexContract.FIXED_TERRAIN)),
            Map.entry("gbuffers_water", plan("gbuffers_water", FamilyAdapterPlan.Family.WATER,
                    UniformRegistry.Stage.TRANSLUCENT, FamilyAdapterPlan.VertexContract.FIXED_TERRAIN)),
            Map.entry("shadow", plan("shadow", FamilyAdapterPlan.Family.SHADOW,
                    UniformRegistry.Stage.SHADOW, FamilyAdapterPlan.VertexContract.FIXED_TERRAIN)),
            // Iris SHADOW_ENTITIES_CUTOUT: every entity and block-entity pipeline in the shadow
            // pass, on the entity layout (the end portal and gateway too, after the entitySolid swap).
            Map.entry("shadow_entities", plan("shadow_entities", FamilyAdapterPlan.Family.SHADOW_ENTITIES,
                    UniformRegistry.Stage.SHADOW_ENTITY, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_entities", plan("gbuffers_entities", FamilyAdapterPlan.Family.ENTITY,
                    UniformRegistry.Stage.ENTITY, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_entities_translucent", plan("gbuffers_entities_translucent",
                    FamilyAdapterPlan.Family.ENTITY_TRANSLUCENT, UniformRegistry.Stage.ENTITY,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_spidereyes", plan("gbuffers_spidereyes",
                    FamilyAdapterPlan.Family.SPIDER_EYES, UniformRegistry.Stage.ENTITY,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_block", plan("gbuffers_block", FamilyAdapterPlan.Family.BLOCK,
                    UniformRegistry.Stage.BLOCK, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_damagedblock", plan("gbuffers_damagedblock",
                    FamilyAdapterPlan.Family.DAMAGED_BLOCK, UniformRegistry.Stage.BLOCK,
                    FamilyAdapterPlan.VertexContract.HOST_BLOCK)),
            Map.entry("gbuffers_hand", plan("gbuffers_hand", FamilyAdapterPlan.Family.HAND,
                    UniformRegistry.Stage.HAND, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_hand_water", plan("gbuffers_hand_water",
                    FamilyAdapterPlan.Family.HAND_WATER, UniformRegistry.Stage.HAND,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_particles", plan("gbuffers_particles", FamilyAdapterPlan.Family.PARTICLE,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_PARTICLE)),
            Map.entry("gbuffers_particles_translucent", plan("gbuffers_particles_translucent",
                    FamilyAdapterPlan.Family.PARTICLE_TRANSLUCENT, UniformRegistry.Stage.PARTICLE,
                    FamilyAdapterPlan.VertexContract.HOST_PARTICLE)),
            Map.entry("gbuffers_skybasic", plan("gbuffers_skybasic", FamilyAdapterPlan.Family.SKY,
                    UniformRegistry.Stage.SKY, FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR)),
            Map.entry("gbuffers_skytextured", plan("gbuffers_skytextured", FamilyAdapterPlan.Family.SKY,
                    UniformRegistry.Stage.SKY, FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR_UV)),
            Map.entry("gbuffers_clouds", plan("gbuffers_clouds", FamilyAdapterPlan.Family.CLOUD,
                    UniformRegistry.Stage.CLOUD, FamilyAdapterPlan.VertexContract.CLOUD_POSITION_COLOR)),
            Map.entry("gbuffers_weather", plan("gbuffers_weather", FamilyAdapterPlan.Family.WEATHER,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_PARTICLE)),
            // Iris LINES, LINES_TRANSLUCENT and SECONDARY_BLOCK_OUTLINE (ShaderKey.LINES) on the
            // host line layout, served like weather from the host transform blocks.
            Map.entry("gbuffers_line", plan("gbuffers_line", FamilyAdapterPlan.Family.LINE,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_LINE)),
            // Iris GLINT (ShaderKey.GLINT) on the host POSITION_TEX glint layers.
            Map.entry("gbuffers_armor_glint", plan("gbuffers_armor_glint", FamilyAdapterPlan.Family.GLINT,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_GLINT)),
            // Iris LIGHTNING (LIGHTNING, DRAGON_RAYS) on the host POSITION_COLOR layout.
            Map.entry("gbuffers_lightning", plan("gbuffers_lightning", FamilyAdapterPlan.Family.LIGHTNING,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_LIGHTNING)),
            // Iris BEACON (BEACON_BEAM_OPAQUE, BEACON_BEAM_TRANSLUCENT) on the host BLOCK layout.
            Map.entry("gbuffers_beaconbeam", plan("gbuffers_beaconbeam", FamilyAdapterPlan.Family.BEACON_BEAM,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_BEACON)),
            // Iris LEASH (ProgramId.Basic) on the host POSITION_COLOR_LIGHTMAP lead strip.
            Map.entry("gbuffers_basic", plan("gbuffers_basic", FamilyAdapterPlan.Family.BASIC,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_LEASH))
    );

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
                || family == FamilyAdapterPlan.Family.DAMAGED_BLOCK
                || family == FamilyAdapterPlan.Family.HAND
                || family == FamilyAdapterPlan.Family.HAND_WATER;
    }

    /** The programs that draw into the shadow map: shadow (terrain) and shadow_entities. */
    public static boolean isShadowFamily(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.SHADOW || family == FamilyAdapterPlan.Family.SHADOW_ENTITIES;
    }

    /** The shadow program on the entity layout (Iris ProgramId.ShadowEntities). */
    public static boolean isShadowEntityFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.SHADOW_ENTITIES;
    }

    public static boolean isParticleLike(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.PARTICLE
                || family == FamilyAdapterPlan.Family.PARTICLE_TRANSLUCENT;
    }

    public static boolean isWeatherFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.WEATHER;
    }

    public static boolean isLineFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.LINE;
    }

    public static boolean isGlintFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.GLINT;
    }

    /**
     * Lanes whose pack program runs on a host vertex layout Iris also draws natively: lines,
     * glint, lightning, beacon beams and leads.
     */
    public static boolean isHostLayoutFamily(String name) {
        return forProgram(name).vertexContract().hostLayout();
    }

    /** The lane label a host-layout program's deviations carry (LINE, GLINT, ...), or null. */
    public static String hostLayoutLane(String name) {
        FamilyAdapterPlan plan = forProgram(name);
        return plan.vertexContract().hostLayout() ? plan.family().name() : null;
    }

    /** Every host-layout lane label. */
    public static java.util.Set<String> hostLayoutLanes() {
        return CONTRACTS.values().stream().filter(plan -> plan.vertexContract().hostLayout())
                .map(plan -> plan.family().name())
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
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
