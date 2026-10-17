package net.chimera.render.shader;

import net.chimera.render.shader.ChimeraEntityBridge.Family;

import java.util.Set;

/**
 * The discard threshold Iris tests a family draw against (the AlphaTests of the ShaderKey it maps
 * the host shader to), which the pack program reads per draw as iris_currentAlphaTest. Host-layout
 * lanes carry their own instead.
 */
public final class HostAlphaTest {
    /** Iris AlphaTests.ONE_TENTH_ALPHA: GREATER 0.1. */
    static final float ONE_TENTH_ALPHA = 0.1F;
    /** Iris AlphaTests.NON_ZERO_ALPHA: GREATER 0.0001. */
    static final float NON_ZERO_ALPHA = 0.0001F;
    /**
     * Iris AlphaTests.VERTEX_ALPHA: GREATER than the vertex colour's alpha. gbuffers_entities reads
     * a negative reference as this test (PackAlphaTestPlan).
     */
    static final float VERTEX_ALPHA = -1.0F;
    /** The glyph shaders Iris keys HAND_TEXT and HAND_TEXT_INTENSITY on in the hand. */
    private static final Set<String> TEXT_SHADERS = Set.of("rendertype_text", "rendertype_text_see_through",
            "rendertype_text_intensity", "rendertype_text_intensity_see_through", "position_color_tex_lightmap");

    private HostAlphaTest() {}

    /**
     * Iris 1.21.1's threshold for a draw of {@code family} with the host shader {@code shader}:
     * an entity's ENTITIES_SOLID_DIFFUSE draws untested; ENTITIES_ALPHA tests the vertex alpha;
     * ENTITIES_EYES and HAND_TEXT test NON_ZERO_ALPHA; every other entity, block-entity,
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
