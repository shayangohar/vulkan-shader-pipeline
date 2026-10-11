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
        /**
         * shadow_entities: entities and block entities in the shadow pass (Iris
         * SHADOW_ENTITIES_CUTOUT, ProgramId.ShadowEntities, falling back to shadow).
         */
        SHADOW_ENTITIES,
        ENTITY,
        ENTITY_TRANSLUCENT,
        SPIDER_EYES,
        BLOCK,
        DAMAGED_BLOCK,
        HAND,
        HAND_WATER,
        PARTICLE,
        PARTICLE_TRANSLUCENT,
        SKY,
        CLOUD,
        WEATHER,
        /** gbuffers_line: block outlines and other LINES draws (Iris ProgramId.Line). */
        LINE,
        /** gbuffers_armor_glint: enchantment glint layers (Iris ProgramId.ArmorGlint). */
        GLINT,
        /** gbuffers_lightning: lightning bolts and dragon death rays (Iris ProgramId.Lightning). */
        LIGHTNING,
        /** gbuffers_beaconbeam: beacon and end gateway beams (Iris ProgramId.BeaconBeam). */
        BEACON_BEAM,
        /** gbuffers_basic: leads (Iris ShaderKey.LEASH, ProgramId.Basic). */
        BASIC,
        SPECIAL,
        POST,
        UNKNOWN
    }

    public enum VertexContract {
        FIXED_TERRAIN,
        EXTENDED_ENTITY,
        EXTENDED_PARTICLE,
        HOST_PARTICLE,
        /** DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, drawn as quads. */
        HOST_LINE,
        /** DefaultVertexFormat.POSITION_TEX glint layers. */
        HOST_GLINT,
        /** DefaultVertexFormat.POSITION_COLOR lightning and dragon rays. */
        HOST_LIGHTNING,
        /** DefaultVertexFormat.BLOCK beacon beams. */
        HOST_BEACON,
        /** DefaultVertexFormat.POSITION_COLOR_LIGHTMAP leads, drawn as a triangle strip. */
        HOST_LEASH,
        HOST_BLOCK,
        SKY_POSITION,
        SKY_POSITION_COLOR,
        SKY_POSITION_UV,
        SKY_POSITION_COLOR_UV,
        CLOUD_POSITION_COLOR,
        /** @deprecated retained for reports produced by older M8.5b builds. */
        @Deprecated
        CLOUD_GENERATED,
        FULLSCREEN,
        HOST_FALLBACK;

        /**
         * Lanes whose program runs on a host vertex layout Iris also draws natively
         * (VanillaTransformer), converted by LegacyGlslConverter.convertHostLayoutVertexChecked.
         */
        public boolean hostLayout() {
            return switch (this) {
                case HOST_LINE, HOST_GLINT, HOST_LIGHTNING, HOST_BEACON, HOST_LEASH -> true;
                default -> false;
            };
        }
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

    /** Returns the same family contract with a source-validated host vertex shape. */
    public FamilyAdapterPlan withVertexContract(VertexContract contract) {
        return new FamilyAdapterPlan(programName, family, stage, contract, executable, deviations);
    }
}
