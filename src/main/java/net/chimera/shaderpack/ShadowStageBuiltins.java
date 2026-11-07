package net.chimera.shaderpack;

import java.util.Map;

/**
 * What a shadow program's stages before the rasterizer share: the legacy built-ins they read as
 * the shadow draw's own (TerrainVertexBridge, GeometryStageBridge), and the vertex stage's
 * conversion of GL's clip depth, [-w, w], to Vulkan's [0, w] when no geometry stage follows it (a
 * geometry stage converts what it emits, GeometryStageBridge.emitClipRange).
 */
final class ShadowStageBuiltins {
    /** Name the pack-authored shadow entry point keeps after the wrapper claims {@code main}. */
    static final String AUTHORED_SHADOW_ENTRY = "chimeraAuthoredShadowMain";

    private ShadowStageBuiltins() {}

    /**
     * Converts the vertex stage's final clip depth after the pack's authored vertex code runs,
     * unless a geometry stage follows ({@code geometryNext}): that stage converts what it emits
     * (GeometryStageBridge.emitClipRange).
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
