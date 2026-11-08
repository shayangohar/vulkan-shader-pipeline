package net.chimera.shaderpack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The clip range a world stage writes. The host draws the world with matrices that already map
 * clip depth onto Vulkan's [0, w], and the legacy built-ins a world stage reads stand for them. The
 * pack's own projection uniforms carry GL's [-w, w], as under Iris (KNOW-361), so a stage that
 * projects with them (Ebin's and Glimmer's gbuffers_main: gl_Position = gbufferProjection * view)
 * stored GL's normalized depth, 2d - 1 where GL keeps the window depth d, and every position a
 * later pass rebuilt from that depth landed at half its distance.
 *
 * <p>Such a stage runs in GL's range throughout ({@link #GL}), as shadow (ShadowStageBuiltins),
 * hand and sky stages do: its clip built-ins take the host matrices into GL's range, and its final
 * clip depth comes back once, after the authored entry point. ftransform() keeps the host's depth
 * exactly: 2z - w and its sum with w are exact while 2z lies within a factor of two of w (Sterbenz),
 * which holds for every window depth from 0.25 on. A stage that reads no pack projection keeps the
 * host's matrices and depth ({@link #HOST}).</p>
 */
enum WorldClipRange {
    HOST,
    GL;

    /**
     * The pack's camera projections and their inverses, in GL's clip range. RenderPearl's terrain
     * clamps gl_ProjectionMatrix * view to the frustum and unprojects it with
     * gbufferProjectionInverse alone; with the host's range there it placed each light at about
     * twice its distance and culled every one past a few blocks.
     */
    private static final Set<String> PACK_PROJECTIONS = Set.of(
            "gbufferProjection", "gbufferProjectionInverse", "gbufferPreviousProjection", "dhProjection",
            "dhProjectionInverse", "dhPreviousProjection");
    /** The legacy built-ins that carry the host's clip depth. */
    private static final List<String> CLIP_BUILTINS = List.of("gl_ProjectionMatrix", "gl_ModelViewProjectionMatrix");
    /** Name the authored entry point keeps once the conversion claims {@code main}. */
    static final String AUTHORED_ENTRY = "chimeraAuthoredWorldMain";
    /** Takes a host clip position, or a matrix that produces one, into GL's range: z becomes 2z - w. */
    private static final String HELPERS = """
            vec4 chimeraGlClip(vec4 clip) {
                return vec4(clip.xy, 2.0 * clip.z - clip.w, clip.w);
            }
            mat4 chimeraGlClip(mat4 projection) {
                return mat4(chimeraGlClip(projection[0]), chimeraGlClip(projection[1]),
                        chimeraGlClip(projection[2]), chimeraGlClip(projection[3]));
            }
            """;

    /** The range of a stage that reads {@code source}, without comments or unreachable functions. */
    static WorldClipRange of(String source) {
        return GlslResourceUsage.referencedValueIdentifiers(source, PACK_PROJECTIONS).isEmpty() ? HOST : GL;
    }

    /** {@code host}'s built-in replacements, each clip built-in's expression in this range. */
    Map<String, String> builtins(Map<String, String> host) {
        if (this == HOST) return host;
        Map<String, String> converted = new HashMap<>(host);
        CLIP_BUILTINS.forEach(name -> converted.computeIfPresent(name, (key, value) -> "chimeraGlClip(" + value + ")"));
        return converted;
    }

    /** The replacement for {@code ftransform()}: the host's {@code transform}, in this range. */
    String ftransform(String transform) {
        return this == HOST ? transform : "chimeraGlClip(" + transform + ")";
    }

    /** The converted stage, and in GL's range the helpers ahead of it and its clip depth converted after it. */
    String convert(String converted) {
        if (this == HOST) return converted;
        return HELPERS + GlslTokenRewriter.replaceIdentifiers(converted, Map.of("main", AUTHORED_ENTRY))
                + "\nvoid main() {\n"
                + "    " + AUTHORED_ENTRY + "();\n"
                + "    gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);\n"
                + "}\n";
    }

    /**
     * A stage before the last one ahead of the rasterizer, in this range: GL's helpers ahead of it
     * and its clip depth left in GL's range, for a later stage (TessellationStageBridge) to convert.
     */
    String withHelpers(String converted) {
        return this == HOST ? converted : HELPERS + converted;
    }

    /**
     * The range a modern vertex stage runs in, from its source without comments. A shadow stage's
     * is the host's: it converts its own clip depth (ShadowStageBuiltins). A world stage ahead of
     * tessellation ({@code laterStageConverts}) runs in GL's, so the control stage reads GL's clip
     * position, as under Iris.
     */
    static WorldClipRange forVertex(String stripped, boolean shadowStage, boolean laterStageConverts) {
        if (shadowStage) return HOST;
        return laterStageConverts ? GL : of(stripped);
    }

    /**
     * A converted vertex stage with its clip depth converted, unless a later stage converts it. A
     * stage ahead of another that writes no position (Shrimple's Nether and End shadow.vsh, whose
     * shadow.gsh only voxelizes) writes a zero one: GL leaves the next stage's gl_in position
     * undefined, and Vulkan pairs that stage's input with a written output.
     */
    String vertexBody(String converted, boolean shadowStage, boolean laterStageConverts) {
        String body = laterStageConverts && !GlslTokenRewriter.containsIdentifier(converted, "gl_Position")
                ? LegacyGlslConverter.injectMainPrologue(converted, "gl_Position = vec4(0.0);") : converted;
        if (shadowStage) return ShadowStageBuiltins.vertexClipRange(body, laterStageConverts);
        return laterStageConverts ? withHelpers(body) : convert(body);
    }

    /** Whether {@code converted} is a stage {@link #convert} took into GL's range. */
    static boolean converts(String converted) {
        return GlslTokenRewriter.containsIdentifier(converted, AUTHORED_ENTRY);
    }
}
