package net.chimera.shaderpack;

import java.util.List;

/** Immutable family contract selected once during pack analysis. */
public record FamilyAdapterPlan(
        String programName,
        Family family,
        UniformRegistry.Stage stage,
        VertexContract vertexContract,
        boolean executable,
        List<String> deviations
) {
    public enum Family {
        TERRAIN,
        WATER,
        SHADOW,
        ENTITY,
        ENTITY_TRANSLUCENT,
        GLOWING,
        BLOCK,
        DAMAGED_BLOCK,
        HAND,
        HAND_WATER,
        PARTICLE,
        PARTICLE_TRANSLUCENT,
        SKY,
        CLOUD,
        WEATHER,
        OUTLINE,
        SPECIAL,
        POST,
        UNKNOWN
    }

    public enum VertexContract {
        FIXED_TERRAIN,
        EXTENDED_ENTITY,
        EXTENDED_PARTICLE,
        HOST_PARTICLE,
        FULLSCREEN,
        HOST_FALLBACK
    }

    public FamilyAdapterPlan {
        programName = programName == null ? "" : programName;
        family = family == null ? Family.UNKNOWN : family;
        stage = stage == null ? UniformRegistry.Stage.POST : stage;
        vertexContract = vertexContract == null ? VertexContract.HOST_FALLBACK : vertexContract;
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public static FamilyAdapterPlan unsupported(String name) {
        return new FamilyAdapterPlan(name, Family.UNKNOWN, UniformRegistry.Stage.POST,
                VertexContract.HOST_FALLBACK, false, List.of("FAMILY_LANE_UNSUPPORTED"));
    }
}
