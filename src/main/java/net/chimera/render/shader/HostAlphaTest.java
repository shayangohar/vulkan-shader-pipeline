package net.chimera.render.shader;

import net.chimera.render.shader.ChimeraEntityBridge.Family;

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

    private HostAlphaTest() {}

    /**
     * Iris 1.21.1's threshold for a draw of {@code family} with the host shader {@code shader}:
     * ENTITIES_SOLID and the entity-solid key draw untested; ENTITIES_EYES and HAND_TEXT test
     * NON_ZERO_ALPHA; every other entity, block-entity, hand, text, particle, crumbling and shadow
     * caster key tests ONE_TENTH_ALPHA. Untested, a flat item or a glyph quad wrote depth and
     * gbuffer data across its transparent texels.
     */
    public static float reference(String shader, Family family, boolean shadowCaster) {
        if (shadowCaster) return ONE_TENTH_ALPHA;
        if ("rendertype_eyes".equals(shader)) return NON_ZERO_ALPHA;
        boolean hand = family == Family.HAND || family == Family.HAND_WATER;
        if (hand && isText(shader)) return NON_ZERO_ALPHA;
        if (family == Family.ENTITY && "rendertype_entity_solid".equals(shader)) return 0.0F;
        return ONE_TENTH_ALPHA;
    }

    private static boolean isText(String shader) {
        return "rendertype_text".equals(shader) || "rendertype_text_see_through".equals(shader)
                || "position_color_tex_lightmap".equals(shader);
    }
}
