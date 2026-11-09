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

    private static final String OVERLAY_FETCH = fetch(UV_VARYING);
    /** A geometry stage's fetch: the primitive's first vertex, as Iris reads entityColor[0]. */
    private static final String GEOMETRY_FETCH = fetch(UV_VARYING + "[0]");
    private static final Pattern UV_READ = Pattern.compile("\\b" + UV_VARYING + "\\b");
    /** An opaque empty texel: entityColor comes out (0, 0, 0, 0). */
    private static final String NO_OVERLAY_TEXEL = "vec4 chimeraOverlayTexel = vec4(0.0, 0.0, 0.0, 1.0);";

    private static final Pattern DECLARATION =
            Pattern.compile("(?m)^[ \\t]*uniform\\s+vec4\\s+entityColor\\s*;");
    private static final Pattern READ = Pattern.compile("\\bentityColor\\b");
    /** A main's head, matched outside comments: a commented one must never take the inserted text. */
    private static final Pattern MAIN =
            Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");

    private EntityOverlayColor() {}

    /**
     * The overlay texel at {@code uv}, or GL's empty texel (0, 0, 0, 1) where {@code uv} falls outside
     * the bound image. A render type that sets no overlay (eyes, energy swirl) leaves a 1x1 white
     * stand-in on the unit, while its UV1 is vanilla's NO_OVERLAY (0, 10). Vulkan leaves a fetch
     * outside the image undefined, and NVIDIA reads zero: entityColor came out opaque black, and
     * Shrimple's spider and enderman eyes drew black. Iris's GL reads unbound texture 0 there as
     * (0, 0, 0, 1), which leaves entityColor (0, 0, 0, 0).
     */
    private static String fetch(String uv) {
        String sampler = PackResourcePlan.OVERLAY_SAMPLER;
        return "vec4 chimeraOverlayTexel = all(lessThan(uvec2(" + uv + "), uvec2(textureSize(" + sampler
                + ", 0)))) ? texelFetch(" + sampler + ", " + uv + ", 0) : vec4(0.0, 0.0, 0.0, 1.0);";
    }

    /** Rewrites a fragment that declares {@code uniform vec4 entityColor}; others are unchanged. */
    static String inject(String fragment) {
        return inject(fragment, OVERLAY_FETCH, "");
    }

    /** {@code source} reading entityColor through {@code fetch}, with {@code input} declared just before main. */
    private static String inject(String source, String fetch, String input) {
        if (source == null) return null;
        // The declaration and its repeats are found outside comments, at the offsets of the source as written.
        Matcher declaration = DECLARATION.matcher(GlslLexer.maskComments(source));
        if (!declaration.find()) return source;
        StringBuilder rewritten = new StringBuilder(source.substring(0, declaration.start()))
                .append("uniform sampler2D ").append(PackResourcePlan.OVERLAY_SAMPLER).append(";\nvec4 entityColor;");
        int kept = declaration.end();
        while (declaration.find()) {
            rewritten.append(source, kept, declaration.start());
            kept = declaration.end();
        }
        String declared = rewritten.append(source.substring(kept)).toString();
        Matcher main = MAIN.matcher(GlslLexer.maskComments(declared));
        if (!main.find()) return source;
        // Iris's three lines: the texture stores how much of the mob shows
        // through, so the alpha turns around, and a pack reading only rgb
        // sees black where there is no overlay.
        String prologue = "\n    " + fetch
                + "\n    entityColor = vec4(chimeraOverlayTexel.rgb, 1.0 - chimeraOverlayTexel.a);"
                + "\n    entityColor.rgb *= float(entityColor.a != 0.0);";
        return declared.substring(0, main.start()) + input + declared.substring(main.start(), main.end()) + prologue
                + declared.substring(main.end());
    }

    /** A shadow entity program's stages once the overlay serves entityColor (injectShadow). */
    record ShadowStages(String vertex, String geometry, String fragment) {}

    /**
     * The shadow pass's entity casters (shadow_entities, shadow_block), which Iris draws on the
     * entity layout and patches as it does gbuffers_entities (VanillaTransformer, hasOverlay).
     * Rethinking Voxels' shadow geometry stage reads entityColor to light primed TNT while it
     * flashes. A running geometry stage reads the primitive's first vertex (EntityPatcher's
     * entityColor[0]); without one the fragment reads its own. The vertex stage forwards UV1 for
     * either (forwardOverlayUv). Where a geometry program's vertex or fragment stage also reads the
     * value, the uniform stays and reads zero.
     */
    static ShadowStages injectShadow(String vertex, String geometry, String fragment, boolean geometryRuns) {
        ShadowStages unchanged = new ShadowStages(vertex, geometry, fragment);
        if (!geometryRuns) {
            String injected = inject(fragment);
            return injected == null || injected.equals(fragment) ? unchanged
                    : new ShadowStages(forwardOverlayUv(withoutUnreadVertexDeclaration(vertex), injected), geometry,
                            injected);
        }
        String injected = inject(geometry, GEOMETRY_FETCH, "flat in ivec2 " + UV_VARYING + "[];\n");
        String servedVertex = withoutUnreadVertexDeclaration(vertex);
        String servedFragment = withoutUnreadVertexDeclaration(fragment);
        if (injected == null || injected.equals(geometry) || declares(servedVertex) || declares(servedFragment)) {
            return unchanged;
        }
        return new ShadowStages(forwardOverlayUv(servedVertex, injected), injected, servedFragment);
    }

    /**
     * The shadow entity vertex stage with the overlay coordinates its consumer reads (an injected
     * fragment's fetch, or a geometry stage's input) forwarded from UV1.
     */
    static String forwardOverlayUv(String vertex, String consumer) {
        if (vertex == null || consumer == null || !UV_READ.matcher(GlslLexer.stripComments(consumer)).find()
                || UV_READ.matcher(GlslLexer.stripComments(vertex)).find()) {
            return vertex;
        }
        Matcher main = MAIN.matcher(GlslLexer.maskComments(vertex));
        if (!main.find()) return vertex;
        return vertex.substring(0, main.start()) + "flat out ivec2 " + UV_VARYING + ";\n"
                + vertex.substring(main.start(), main.end()) + "\n    " + UV_VARYING + " = UV1;"
                + vertex.substring(main.end());
    }

    private static boolean declares(String source) {
        return source != null && DECLARATION.matcher(GlslLexer.maskComments(source)).find();
    }

    /**
     * The vertex stage beside an injected fragment. Iris deletes the uniform from every stage
     * (EntityPatcher.patchOverlayColor), so a vertex stage that declares it only through a header
     * it shares with the fragment loses the declaration, which would otherwise meet the fragment's
     * global in the shared uniform block. Helpers main never calls go with it, as every converter
     * prunes them. A vertex stage main reaches a read from keeps it.
     */
    static String withoutUnreadVertexDeclaration(String vertex) {
        if (vertex == null) return null;
        String removed = DECLARATION.matcher(vertex).replaceAll("");
        if (!READ.matcher(GlslLexer.stripComments(removed)).find()) return removed;
        String pruned = GlslTokenRewriter.removeUnreachableFunctions(removed, GlslResourceUsage.analyze(removed));
        return READ.matcher(GlslLexer.stripComments(pruned)).find() ? vertex : pruned;
    }

    /**
     * The converted fragment, or shadow geometry stage, for a host layout without overlay coordinates
     * (text, a held map's background, particles in the hand, a moving block's shadow, whose UV1 is a
     * constant zero). Iris patches the overlay fetch only where the layout
     * has UV1 (VanillaTransformer, hasOverlay); elsewhere entityColor stays the uniform
     * CommonUniforms sets to zero. Those host draws bind no overlay, so the fetch read whatever
     * texture slot 1 last held, and tinted name tags with it from frame to frame.
     */
    static String withoutOverlay(String fragment) {
        return fragment == null ? null
                : fragment.replace(OVERLAY_FETCH, NO_OVERLAY_TEXEL).replace(GEOMETRY_FETCH, NO_OVERLAY_TEXEL);
    }
}
