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
        /** shadow_lines: line draws in the shadow pass (Iris SHADOW_LINES, ProgramId.Shadow). */
        SHADOW_LINES,
        /** shadow_leash: leads in the shadow pass (Iris SHADOW_LEASH, ProgramId.Shadow). */
        SHADOW_LEASH,
        /** shadow_lightning: lightning bolts in the shadow pass (Iris ProgramId.ShadowLightning). */
        SHADOW_LIGHTNING,
        /**
         * shadow_glint: glint layers in the shadow pass. Iris draws them with SHADOW_ENTITIES_CUTOUT,
         * the entity-layout program, on the glint's own POSITION_TEX mesh.
         */
        SHADOW_GLINT,
        /** shadow_text: world text and name tags in the shadow pass (Iris SHADOW_TEXT, ProgramId.ShadowEntities). */
        SHADOW_TEXT,
        /** shadow_text_bg: text backgrounds in the shadow pass (Iris SHADOW_TEXT_BG, ProgramId.ShadowEntities). */
        SHADOW_TEXT_BG,
        /** shadow_basic: the boat water mask in the shadow pass (Iris SHADOW_BASIC, ProgramId.Shadow). */
        SHADOW_BASIC,
        ENTITY,
        ENTITY_TRANSLUCENT,
        SPIDER_EYES,
        BLOCK,
        /**
         * gbuffers_block_translucent: text drawn by block entities, such as signs (Iris TEXT_BE,
         * ProgramId.BlockTrans). Iris 1.21.11 assigns it no block-entity model draws.
         */
        BLOCK_TRANSLUCENT,
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
        /**
         * text_bg: text and name-tag backgrounds (Iris ShaderKey.TEXT_BG), drawn with
         * gbuffers_entities_translucent on the host POSITION_COLOR_LIGHTMAP quads.
         */
        TEXT_BG,
        SPECIAL,
        POST,
        UNKNOWN
    }

    public enum VertexContract {
        FIXED_TERRAIN,
        EXTENDED_ENTITY,
        EXTENDED_PARTICLE,
        HOST_PARTICLE,
        /** DefaultVertexFormat.POSITION_COLOR_NORMAL lines (outlines, hitboxes, debug lines). */
        HOST_LINE,
        /** DefaultVertexFormat.POSITION_TEX glint layers. */
        HOST_GLINT,
        /** DefaultVertexFormat.POSITION_COLOR lightning and dragon rays. */
        HOST_LIGHTNING,
        /** DefaultVertexFormat.BLOCK beacon beams. */
        HOST_BEACON,
        /** DefaultVertexFormat.POSITION_COLOR_LIGHTMAP leads, drawn as a triangle strip. */
        HOST_LEASH,
        /** DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP glyph quads (world text and name tags). */
        HOST_TEXT,
        /** DefaultVertexFormat.POSITION_COLOR_LIGHTMAP text background quads. */
        HOST_TEXT_BACKGROUND,
        /** DefaultVertexFormat.POSITION boat water mask. */
        HOST_WATER_MASK,
        HOST_BLOCK,
        SKY_POSITION,
        SKY_POSITION_COLOR,
        SKY_POSITION_UV,
        SKY_POSITION_COLOR_UV,
        /** Vanilla's cloud mesh, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL. */
        CLOUD_POSITION_UV_COLOR_NORMAL,
        FULLSCREEN,
        HOST_FALLBACK;

        /**
         * Lanes whose program runs on a host vertex layout Iris also draws natively
         * (VanillaTransformer): converted by LegacyGlslConverter.convertHostLayoutVertexChecked in
         * the main pass and convertShadowLaneVertexChecked in the shadow pass.
         */
        public boolean hostLayout() {
            return switch (this) {
                case HOST_LINE, HOST_GLINT, HOST_LIGHTNING, HOST_BEACON, HOST_LEASH,
                     HOST_TEXT, HOST_TEXT_BACKGROUND, HOST_WATER_MASK -> true;
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
