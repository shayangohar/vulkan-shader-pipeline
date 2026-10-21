package net.chimera.shaderpack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves {@code entityColor}, the hurt tint and creeper flash, the way Iris
 * does (EntityPatcher): per vertex from the overlay texel the entity's UV1
 * points at. A single uniform answer is one colour for every mob on screen,
 * so a hit mob never flashes.
 *
 * <p>The fragment keeps the pack's name as a global it sets at the top of
 * {@code main}. The entity vertex converter forwards UV1 as the flat
 * varying {@link #UV_VARYING} whenever the fragment reads it. The fetch is
 * a {@code texelFetch}, so no sampler filter can blend the red tint row with
 * the white flash row.</p>
 */
final class EntityOverlayColor {
    static final String UV_VARYING = "chimeraOverlayUV";

    private static final String OVERLAY_FETCH = "vec4 chimeraOverlayTexel = texelFetch("
            + PackResourcePlan.OVERLAY_SAMPLER + ", " + UV_VARYING + ", 0);";
    /** An opaque empty texel: entityColor comes out (0, 0, 0, 0). */
    private static final String NO_OVERLAY_TEXEL = "vec4 chimeraOverlayTexel = vec4(0.0, 0.0, 0.0, 1.0);";

    private static final Pattern DECLARATION =
            Pattern.compile("(?m)^[ \\t]*uniform\\s+vec4\\s+entityColor\\s*;");
    private static final Pattern READ = Pattern.compile("\\bentityColor\\b");
    private static final Pattern MAIN =
            Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");

    private EntityOverlayColor() {}

    /** Rewrites a fragment that declares {@code uniform vec4 entityColor}; others are unchanged. */
    static String inject(String fragment) {
        if (fragment == null) return null;
        Matcher declaration = DECLARATION.matcher(fragment);
        if (!declaration.find()) return fragment;
        String declared = fragment.substring(0, declaration.start())
                + "uniform sampler2D " + PackResourcePlan.OVERLAY_SAMPLER + ";\nvec4 entityColor;"
                + DECLARATION.matcher(fragment.substring(declaration.end())).replaceAll("");
        Matcher main = MAIN.matcher(declared);
        if (!main.find()) return fragment;
        // Iris's three lines: the texture stores how much of the mob shows
        // through, so the alpha turns around, and a pack reading only rgb
        // sees black where there is no overlay.
        String prologue = "\n    " + OVERLAY_FETCH
                + "\n    entityColor = vec4(chimeraOverlayTexel.rgb, 1.0 - chimeraOverlayTexel.a);"
                + "\n    entityColor.rgb *= float(entityColor.a != 0.0);";
        return declared.substring(0, main.end()) + prologue + declared.substring(main.end());
    }

    /**
     * The vertex stage beside an injected fragment. Iris deletes the uniform from every stage
     * (EntityPatcher.patchOverlayColor), so a vertex stage that declares it only through a header
     * it shares with the fragment loses the declaration, which would otherwise meet the fragment's
     * global in the shared uniform block. A vertex stage that reads it keeps it.
     */
    static String withoutUnreadVertexDeclaration(String vertex) {
        if (vertex == null) return null;
        String removed = DECLARATION.matcher(vertex).replaceAll("");
        return READ.matcher(GlslLexer.stripComments(removed)).find() ? vertex : removed;
    }

    /**
     * The converted fragment for a host layout without overlay coordinates (text, a held map's
     * background, particles in the hand). Iris patches the overlay fetch only where the layout
     * has UV1 (VanillaTransformer, hasOverlay); elsewhere entityColor stays the uniform
     * CommonUniforms sets to zero. Those host draws bind no overlay, so the fetch read whatever
     * texture slot 1 last held, and tinted name tags with it from frame to frame.
     */
    static String withoutOverlay(String fragment) {
        return fragment == null ? null : fragment.replace(OVERLAY_FETCH, NO_OVERLAY_TEXEL);
    }
}
