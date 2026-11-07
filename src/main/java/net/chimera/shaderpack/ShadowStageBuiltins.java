package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;

/**
 * What a shadow program's stages before the rasterizer share: the legacy built-ins they read as
 * the shadow draw's own (TerrainVertexBridge, GeometryStageBridge), and the conversion of GL's
 * clip depth, [-w, w], to Vulkan's [0, w], which the last of them applies once to what it emits.
 */
final class ShadowStageBuiltins {
    /** Name the pack-authored shadow entry point keeps after the wrapper claims {@code main}. */
    static final String AUTHORED_SHADOW_ENTRY = "chimeraAuthoredShadowMain";
    /** The function a geometry stage's EmitVertex calls become: the clip-depth conversion, then the emit. */
    static final String SHADOW_EMIT = "chimeraEmitShadowVertex";
    /** The prefix of the global that keeps a geometry stage output's value across an emit. */
    static final String KEPT_PREFIX = "chimeraKept_";

    private ShadowStageBuiltins() {}

    /**
     * Converts the vertex stage's final clip depth after the pack's authored vertex code runs,
     * unless a geometry stage follows ({@code geometryNext}): that stage converts what it emits.
     */
    static String vertexClipRange(String source, boolean geometryNext) {
        if (geometryNext) return source;
        return GlslTokenRewriter.replaceIdentifiers(source, Map.of("main", AUTHORED_SHADOW_ENTRY))
                + "\nvoid main() {\n"
                + "    " + AUTHORED_SHADOW_ENTRY + "();\n"
                + "    gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);\n"
                + "}\n";
    }

    /**
     * Converts the clip depth of every vertex a geometry stage emits, just before the emit, once
     * the stage has written its final position; a terrain program's geometry stage, which reads GL's
     * clip position too, converts its own the same way (GeometryStageBridge.convertWorld). The
     * {@code passOn} statements, which write the outputs the stage passes on for the vertex stage,
     * run there too.
     *
     * <p>GLSL leaves every output undefined after EmitVertex. NVIDIA's GL driver, which Iris runs on,
     * keeps each output's last value, and NVIDIA's Vulkan driver does not: Spring's terrain stage writes
     * its flat textureResolution once and emits three vertices, and RenderDoc read garbage on the last
     * two (KNOW-1088). So the emit keeps every output of {@code source}, and the position the stage
     * wrote, and writes them back after the emit: a later emit reads what GL would, and a position
     * emitted twice is converted once each time.</p>
     *
     * <p>The emit is declared ahead of the stage's first function, which may call it, and defined at
     * the end, after every output: Rethinking Voxels declares its outputs below included functions.</p>
     */
    static String emitClipRange(String source, List<String> passOn) {
        List<GlslInterfaceScanner.Declaration> outputs = GlslInterfaceScanner.scan(source,
                GlslInterfaceScanner.Stage.GEOMETRY).outputs();
        String renamed = GlslTokenRewriter.replaceIdentifiers(source, Map.of("EmitVertex", SHADOW_EMIT));
        StringBuilder helper = new StringBuilder("\n");
        for (GlslInterfaceScanner.Declaration output : outputs) {
            helper.append(output.type()).append(' ').append(KEPT_PREFIX).append(output.name())
                    .append(output.arraySize() == 0 ? "" : "[" + output.arraySize() + "]").append(";\n");
        }
        helper.append("void " + SHADOW_EMIT + "() {\n");
        passOn.forEach(statement -> helper.append("    ").append(statement).append('\n'));
        outputs.forEach(output -> helper.append("    ").append(KEPT_PREFIX).append(output.name()).append(" = ")
                .append(output.name()).append(";\n"));
        helper.append("    vec4 chimeraKeptPosition = gl_Position;\n")
                .append("    gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);\n")
                .append("    EmitVertex();\n")
                .append("    gl_Position = chimeraKeptPosition;\n");
        outputs.forEach(output -> helper.append("    ").append(output.name()).append(" = ").append(KEPT_PREFIX)
                .append(output.name()).append(";\n"));
        helper.append("}\n");
        GlslTree tree = GlslTree.parse(renamed);
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Function) {
                int at = tree.offset(external.range().start());
                return renamed.substring(0, at) + "void " + SHADOW_EMIT + "();\n" + renamed.substring(at) + helper;
            }
        }
        throw new IllegalArgumentException("geometry stage has no function to emit from");
    }

    /**
     * The shadow adapter's legacy built-ins describe the matrices used by the
     * current shadow draw. Camera gbuffer matrices remain separately exposed
     * through their Iris names.
     */
    static Map<String, String> matrixReplacements() {
        return Map.of(
                "gl_ModelViewMatrix", "shadowModelView",
                "gl_NormalMatrix", "mat3(transpose(shadowModelViewInverse))",
                "gl_ProjectionMatrix", "shadowProjection",
                "gl_ModelViewProjectionMatrix", "MVP");
    }

    /** Maps legacy matrix built-ins to the fixed terrain inputs used by the shadow adapter. */
    static String legacyBuiltins(String source) {
        String result = LegacyGlslConverter.replaceLightmapTextureMatrices(source).replaceAll(
                "\\bgl_TextureMatrix\\s*\\[\\s*\\d+\\s*\\]", "mat4(1.0)");
        return GlslTokenRewriter.replaceIdentifiers(result,
                Map.of(
                        // Vulkan GLSL exposes the vertex invocation index as
                        // gl_VertexIndex.  Iris legacy shadow code commonly
                        // uses gl_VertexID for quad/voxel producer gating.
                        "gl_VertexID", "gl_VertexIndex"));
    }
}
