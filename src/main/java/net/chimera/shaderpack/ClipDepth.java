package net.chimera.shaderpack;

import java.util.Map;

/**
 * The one conversion of a clip position's depth from GL's range, [-w, w], to Vulkan's, [0, w], and
 * the stage that runs it. Every path to the rasterizer runs it exactly once when its stages work in
 * GL's range, and never when they keep the host's, whose matrices already produce Vulkan's depth:
 * <ul>
 *   <li>a vertex stage with nothing after it converts after its authored main: a world stage that
 *   reads a pack projection (WorldClipRange.convert), a shadow stage (ShadowStageBuiltins), and the
 *   hand, sky and hand-glint draws (EntityVertexBridge);</li>
 *   <li>a vertex stage ahead of a geometry or tessellation stage leaves GL's range for it;</li>
 *   <li>a geometry stage converts each vertex it emits (GeometryStageBridge.emitClipRange);</li>
 *   <li>a tessellation evaluation stage converts after its main, and the control stage ahead of it
 *   leaves GL's range for it (TessellationStageBridge).</li>
 * </ul>
 * Converting twice halves every depth a later pass rebuilds; missing it puts the near half of the
 * view in front of the near plane (KNOW-361).
 */
final class ClipDepth {
    /** The conversion of the stage's final clip position. */
    static final String TO_VULKAN = "gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);";

    private ClipDepth() {}

    /**
     * {@code source} with every identifier {@code main} renamed {@code entry}, and a main that calls
     * it, then converts the clip depth.
     */
    static String afterEntry(String source, String entry) {
        return GlslTokenRewriter.replaceIdentifiers(source, Map.of("main", entry))
                + "\nvoid main() {\n"
                + "    " + entry + "();\n"
                + "    " + TO_VULKAN + "\n"
                + "}\n";
    }

    /**
     * {@code source}, whose main the entity bridge already renamed {@code entry}, and a one-line main
     * that calls it, then converts the clip depth: the hand, sky and hand-glint draws.
     */
    static String afterRenamedEntry(String source, String entry) {
        return source + "\nvoid main() { " + entry + "(); " + TO_VULKAN + " }\n";
    }

    /** How many conversions {@code source} writes; a stage on a path to the rasterizer writes at most one. */
    static int count(String source) {
        if (source == null) return 0;
        int count = 0;
        for (int at = source.indexOf(TO_VULKAN); at >= 0; at = source.indexOf(TO_VULKAN, at + 1)) count++;
        return count;
    }
}
