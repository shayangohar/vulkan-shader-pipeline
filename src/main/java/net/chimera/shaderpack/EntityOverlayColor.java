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

    private static final Pattern DECLARATION =
            Pattern.compile("(?m)^[ \\t]*uniform\\s+vec4\\s+entityColor\\s*;");
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
        String prologue = "\n    vec4 chimeraOverlayTexel = texelFetch(" + PackResourcePlan.OVERLAY_SAMPLER
                + ", " + UV_VARYING + ", 0);"
                + "\n    entityColor = vec4(chimeraOverlayTexel.rgb, 1.0 - chimeraOverlayTexel.a);"
                + "\n    entityColor.rgb *= float(entityColor.a != 0.0);";
        return declared.substring(0, main.end()) + prologue + declared.substring(main.end());
    }
}
