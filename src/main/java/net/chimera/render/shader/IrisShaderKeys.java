package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.ChimeraEntityBridge.Family;

import java.util.Map;
import java.util.Set;

/**
 * How Iris 1.21.1 keys a host draw, as its MixinGameRenderer picks a ShaderKey for a host shader:
 * the family program a world or shadow-pass render type draws with, by its shader, its format and
 * the entity or block entity drawing it, and the discard threshold the key's AlphaTests set, which
 * the pack program reads per draw as iris_currentAlphaTest. Host-layout lanes carry their own
 * threshold instead (HostLayoutLane).
 */
public final class IrisShaderKeys {
    /** Iris AlphaTests.ONE_TENTH_ALPHA: GREATER 0.1. */
    static final float ONE_TENTH_ALPHA = 0.1F;
    /** Iris AlphaTests.NON_ZERO_ALPHA: GREATER 0.0001. */
    static final float NON_ZERO_ALPHA = 0.0001F;
    /**
     * Iris AlphaTests.VERTEX_ALPHA: GREATER than the vertex colour's alpha. gbuffers_entities reads
     * a negative reference as this test (PackAlphaTestPlan).
     */
    static final float VERTEX_ALPHA = -1.0F;

    /**
     * How Iris 1.21.1's MixinGameRenderer keys an entity-format shader, in an entity, in a block
     * entity and in the shadow pass.
     */
    enum EntityShader {
        /** ENTITIES_SOLID_DIFFUSE or ENTITIES_CUTOUT_DIFFUSE; BLOCK_ENTITY_DIFFUSE; SHADOW_BLOCK. */
        MODEL,
        /** ENTITIES_CUTOUT; BLOCK_ENTITY; SHADOW_ENTITIES_CUTOUT. */
        OVERLAY,
        /** ENTITIES_TRANSLUCENT; BE_TRANSLUCENT; SHADOW_BLOCK. */
        TRANSLUCENT,
        /** ENTITIES_EYES; BLOCK_ENTITY; SHADOW_ENTITIES_CUTOUT. */
        EYES,
        /** ENTITIES_EYES_TRANS; BLOCK_ENTITY; SHADOW_BLOCK. */
        EMISSIVE,
        /** ENTITIES_ALPHA (the dying dragon's dissolve) in either; Iris keeps no shadow key for it. */
        ALPHA
    }

    /**
     * Entity-format shaders by vanilla name; a render type routes by the shader it draws with, as
     * Iris overrides shader getters, so NeoForge's and mods' render types on these shaders follow.
     * Flat held and dropped items draw with item_entity_translucent_cull: drawn by the host they
     * wrote no gbuffer data, and deferred lighting erased them to a ghost.
     */
    private static final Map<String, EntityShader> ENTITY_SHADERS = Map.ofEntries(
            Map.entry("rendertype_entity_solid", EntityShader.MODEL),
            Map.entry("rendertype_entity_cutout", EntityShader.MODEL),
            Map.entry("rendertype_entity_cutout_no_cull", EntityShader.MODEL),
            Map.entry("rendertype_entity_cutout_no_cull_z_offset", EntityShader.MODEL),
            Map.entry("rendertype_entity_decal", EntityShader.MODEL),
            Map.entry("rendertype_entity_smooth_cutout", EntityShader.MODEL),
            Map.entry("rendertype_armor_cutout_no_cull", EntityShader.MODEL),
            Map.entry("rendertype_energy_swirl", EntityShader.OVERLAY),
            Map.entry("rendertype_entity_shadow", EntityShader.OVERLAY),
            Map.entry("rendertype_entity_translucent", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_entity_translucent_cull", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_item_entity_translucent_cull", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_breeze_wind", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_entity_no_outline", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_eyes", EntityShader.EYES),
            Map.entry("rendertype_entity_translucent_emissive", EntityShader.EMISSIVE),
            Map.entry("rendertype_entity_alpha", EntityShader.ALPHA));
    /** The block-breaking overlay's shader (RenderType.CRUMBLING, ModelBakery.DESTROY_TYPES). */
    private static final String CRUMBLING_SHADER = "rendertype_crumbling";
    /** The beam shader of RenderType.beaconBeam, which beacons and end gateways draw. */
    private static final String BEACON_BEAM_SHADER = "rendertype_beacon_beam";
    /** The chunk shaders Iris draws as MOVING_BLOCK when an entity or block entity draws with them. */
    private static final Set<String> MOVING_BLOCK_SHADERS = Set.of("rendertype_solid", "rendertype_cutout_mipped",
            "rendertype_cutout", "rendertype_translucent", "rendertype_translucent_moving_block",
            "rendertype_tripwire");
    /**
     * The glyph shaders Iris routes through TEXT and TEXT_BE, and through TEXT_INTENSITY and
     * TEXT_INTENSITY_BE for TrueType glyphs, whose one-channel page reads RRRR
     * (IntensityTextureViews): world, sign and see-through text. In the hand they key HAND_TEXT and
     * HAND_TEXT_INTENSITY.
     */
    private static final Set<String> TEXT_SHADERS = Set.of("rendertype_text", "rendertype_text_see_through",
            "position_color_tex_lightmap", "rendertype_text_intensity", "rendertype_text_intensity_see_through");

    private IrisShaderKeys() {}

    /**
     * The family a world render type batches with whoever writes it: the crumbling overlay and beacon
     * beams on the block layout, as Iris widens every BLOCK buffer to TERRAIN. Null for any other
     * render type, whose family follows the entity or block entity drawing it (worldFamily).
     */
    static Family blockLayoutFamily(String shader, VertexFormat format) {
        if (format != DefaultVertexFormat.BLOCK) return null;
        if (CRUMBLING_SHADER.equals(shader)) return Family.DAMAGED_BLOCK;
        return BEACON_BEAM_SHADER.equals(shader) ? Family.BEACON_BEAM : null;
    }

    /**
     * The family Iris 1.21.1 draws a world render type with, by its shader and format, inside an
     * entity ({@code context} ENTITY) or a block entity (BLOCK); null when Iris keeps it on another
     * program. Text is TEXT (gbuffers_entities_translucent) in an entity and TEXT_BE
     * (gbuffers_block_translucent) in a block entity.
     */
    public static Family worldFamily(String shader, VertexFormat format, Family context) {
        if (shader == null) return null;
        boolean blockEntity = context == Family.BLOCK;
        if (format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP) {
            if (!TEXT_SHADERS.contains(shader)) return null;
            return blockEntity ? Family.BLOCK_TRANSLUCENT : Family.ENTITY_TRANSLUCENT;
        }
        if (format == DefaultVertexFormat.BLOCK) {
            return MOVING_BLOCK_SHADERS.contains(shader) ? Family.MOVING_BLOCK : null;
        }
        EntityShader key = format == DefaultVertexFormat.NEW_ENTITY ? ENTITY_SHADERS.get(shader) : null;
        return key == null ? null : entityFamily(key, blockEntity);
    }

    private static Family entityFamily(EntityShader key, boolean blockEntity) {
        return switch (key) {
            case MODEL, OVERLAY -> blockEntity ? Family.BLOCK : Family.ENTITY;
            case TRANSLUCENT -> blockEntity ? Family.BLOCK_TRANSLUCENT : Family.ENTITY_TRANSLUCENT;
            case EYES, EMISSIVE -> blockEntity ? Family.BLOCK : Family.SPIDER_EYES;
            case ALPHA -> Family.ENTITY;
        };
    }

    /**
     * The family Iris 1.21.1 draws a shadow caster with, by its shader and format: a block entity's
     * models SHADOW_BLOCK, falling blocks and moving pistons the shadow program on the block layout,
     * and every other entity-format caster SHADOW_ENTITIES_CUTOUT. Null leaves it out of the shadow
     * map.
     */
    public static Family shadowFamily(String shader, VertexFormat format, Family context) {
        if (format == DefaultVertexFormat.BLOCK) {
            return context != null && MOVING_BLOCK_SHADERS.contains(shader) ? Family.SHADOW_ENTITIES : null;
        }
        if (format != DefaultVertexFormat.NEW_ENTITY) return null;
        EntityShader key = shader == null ? null : ENTITY_SHADERS.get(shader);
        boolean blockModel = context == Family.BLOCK && key != null
                && key != EntityShader.OVERLAY && key != EntityShader.EYES && key != EntityShader.ALPHA;
        return blockModel ? Family.SHADOW_BLOCK : Family.SHADOW_ENTITIES;
    }

    /**
     * Iris 1.21.1's threshold for a draw of {@code family} with the host shader {@code shader}:
     * an entity's ENTITIES_SOLID_DIFFUSE and BEACON draw untested; ENTITIES_ALPHA tests the vertex
     * alpha; ENTITIES_EYES and HAND_TEXT test NON_ZERO_ALPHA; every other entity, block-entity,
     * moving-block, hand, text, particle, crumbling and shadow caster key tests ONE_TENTH_ALPHA.
     * Untested, a flat item or a glyph quad wrote depth and gbuffer data across its transparent
     * texels.
     */
    public static float reference(String shader, Family family, boolean shadowCaster) {
        if (shadowCaster) return ONE_TENTH_ALPHA;
        return switch (family) {
            case SPIDER_EYES -> "rendertype_eyes".equals(shader) ? NON_ZERO_ALPHA : ONE_TENTH_ALPHA;
            case HAND, HAND_WATER -> isText(shader) ? NON_ZERO_ALPHA : ONE_TENTH_ALPHA;
            case ENTITY -> entityReference(shader);
            case BEACON_BEAM -> 0.0F;
            default -> ONE_TENTH_ALPHA;
        };
    }

    private static float entityReference(String shader) {
        if ("rendertype_entity_solid".equals(shader)) return 0.0F;
        return "rendertype_entity_alpha".equals(shader) ? VERTEX_ALPHA : ONE_TENTH_ALPHA;
    }

    private static boolean isText(String shader) {
        return shader != null && TEXT_SHADERS.contains(shader);
    }
}
