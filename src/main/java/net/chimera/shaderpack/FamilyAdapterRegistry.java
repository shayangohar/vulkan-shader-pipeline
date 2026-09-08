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
            Map.entry("gbuffers_entities", plan("gbuffers_entities", FamilyAdapterPlan.Family.ENTITY,
                    UniformRegistry.Stage.ENTITY, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_entities_translucent", plan("gbuffers_entities_translucent",
                    FamilyAdapterPlan.Family.ENTITY_TRANSLUCENT, UniformRegistry.Stage.ENTITY,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_entities_glowing", plan("gbuffers_entities_glowing",
                    FamilyAdapterPlan.Family.GLOWING, UniformRegistry.Stage.ENTITY,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_block", plan("gbuffers_block", FamilyAdapterPlan.Family.BLOCK,
                    UniformRegistry.Stage.BLOCK, FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_damagedblock", plan("gbuffers_damagedblock",
                    FamilyAdapterPlan.Family.DAMAGED_BLOCK, UniformRegistry.Stage.BLOCK,
                    FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY)),
            Map.entry("gbuffers_hand", plan("gbuffers_hand", FamilyAdapterPlan.Family.HAND,
                    UniformRegistry.Stage.HAND, FamilyAdapterPlan.VertexContract.EXTENDED_PARTICLE)),
            Map.entry("gbuffers_hand_water", plan("gbuffers_hand_water",
                    FamilyAdapterPlan.Family.HAND_WATER, UniformRegistry.Stage.HAND,
                    FamilyAdapterPlan.VertexContract.EXTENDED_PARTICLE)),
            Map.entry("gbuffers_particles", plan("gbuffers_particles", FamilyAdapterPlan.Family.PARTICLE,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_PARTICLE)),
            Map.entry("gbuffers_particles_translucent", plan("gbuffers_particles_translucent",
                    FamilyAdapterPlan.Family.PARTICLE_TRANSLUCENT, UniformRegistry.Stage.PARTICLE,
                    FamilyAdapterPlan.VertexContract.HOST_PARTICLE)),
            Map.entry("gbuffers_skybasic", unsupported("gbuffers_skybasic", FamilyAdapterPlan.Family.SKY)),
            Map.entry("gbuffers_skytextured", unsupported("gbuffers_skytextured", FamilyAdapterPlan.Family.SKY)),
            Map.entry("gbuffers_clouds", unsupported("gbuffers_clouds", FamilyAdapterPlan.Family.CLOUD)),
            Map.entry("gbuffers_weather", plan("gbuffers_weather", FamilyAdapterPlan.Family.WEATHER,
                    UniformRegistry.Stage.PARTICLE, FamilyAdapterPlan.VertexContract.HOST_PARTICLE))
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
            return unsupported(name, FamilyAdapterPlan.Family.SKY);
        }
        if (name.startsWith("gbuffers_cloud")) {
            return unsupported(name, FamilyAdapterPlan.Family.CLOUD);
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
                || family == FamilyAdapterPlan.Family.GLOWING
                || family == FamilyAdapterPlan.Family.BLOCK
                || family == FamilyAdapterPlan.Family.DAMAGED_BLOCK
                || family == FamilyAdapterPlan.Family.HAND
                || family == FamilyAdapterPlan.Family.HAND_WATER;
    }

    public static boolean isParticleLike(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.PARTICLE
                || family == FamilyAdapterPlan.Family.PARTICLE_TRANSLUCENT;
    }

    public static boolean isWeatherFamily(String name) {
        return forProgram(name).family() == FamilyAdapterPlan.Family.WEATHER;
    }

    public static boolean isWorldEntityFamily(String name) {
        FamilyAdapterPlan.Family family = forProgram(name).family();
        return family == FamilyAdapterPlan.Family.ENTITY
                || family == FamilyAdapterPlan.Family.ENTITY_TRANSLUCENT
                || family == FamilyAdapterPlan.Family.GLOWING;
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
