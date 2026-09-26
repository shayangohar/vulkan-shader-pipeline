package net.chimera.shaderpack;

import java.util.regex.Pattern;

/**
 * Load-time proof that an authored program writes nothing.
 *
 * Packs switch a family off by discarding every fragment in the selected
 * branch (BSL gbuffers_clouds with CLOUDS != 3, Complementary with
 * CLOUD_STYLE_DEFINE != 50). Iris executes such a program and draws
 * nothing. The proof reads prepared sources, whose inactive preprocessor
 * branches are already removed: the fragment main must begin with an
 * unconditional discard, and neither stage may write images, storage
 * buffers or atomics, which are the only effects a discarded draw keeps.
 */
public final class AuthoredOutput {
    private static final Pattern DISCARDING_MAIN = Pattern.compile(
            "\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{\\s*discard\\s*;");
    private static final Pattern SIDE_EFFECT = Pattern.compile(
            "\\b(?:imageStore|imageAtomic\\w*|atomic\\w*|buffer)\\b");
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    private AuthoredOutput() {}

    /** True when the prepared stages provably produce no fragment and no side effect. */
    public static boolean producesNothing(String preparedVertex, String preparedFragment) {
        if (preparedFragment == null) return false;
        String fragment = strip(preparedFragment);
        return DISCARDING_MAIN.matcher(fragment).find()
                && !SIDE_EFFECT.matcher(fragment).find()
                && !SIDE_EFFECT.matcher(strip(preparedVertex)).find();
    }

    private static String strip(String source) {
        return source == null ? "" : COMMENTS.matcher(source).replaceAll(" ");
    }
}
