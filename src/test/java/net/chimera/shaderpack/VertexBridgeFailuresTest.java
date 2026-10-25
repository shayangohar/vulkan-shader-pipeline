package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every vertex bridge a program attempts names why it failed, and the bridges read the material
 * attributes Iris's formats carry or leave unbound. I Like Vanilla's shadow lanes refused its
 * {@code attribute vec4 at_tangent}; Reverie's shadow read {@code uniform restrict image2D} as a
 * value uniform; a sky reading at_tangent was refused outright.
 */
final class VertexBridgeFailuresTest {
    /**
     * Iris's TERRAIN format carries at_tangent, so a legacy shadow that declares it reads the
     * tangent, and the pack-wide terrain format carries it for the shadow pass.
     */
    @Test
    void aLegacyShadowReadsAtTangent(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 120
                attribute vec4 mc_Entity;
                attribute vec4 at_tangent;
                varying vec2 texCoord;
                varying float handedness;
                void main() {
                    texCoord = gl_MultiTexCoord0.xy;
                    handedness = at_tangent.w * mc_Entity.x;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 120
                uniform sampler2D tex;
                varying vec2 texCoord;
                varying float handedness;
                void main() { gl_FragData[0] = texture2D(tex, texCoord) * handedness; }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackProgramPlan shadow = analysis.plan().program("shadow");
        assertTrue(shadow.executable(), shadow.deviations().toString());
        assertTrue(shadow.convertedVertex().contains("chimeraTangentValue()"), shadow.convertedVertex());
        assertTrue(analysis.plan().terrainMaterial().modern(),
                "the shadow's material inputs need the extended terrain format");
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), compiled.failures().toString());
    }

    /** The sky formats carry no mid-coordinates or tangents: Iris leaves both unbound. */
    @Test
    void aSkyReadsMaterialAttributesAsUnbound(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_skybasic.vsh"), """
                #version 120
                attribute vec4 at_tangent;
                attribute vec2 mc_midTexCoord;
                varying vec4 skyColor;
                void main() {
                    skyColor = gl_Color * at_tangent.w + vec4(mc_midTexCoord, 0.0, 0.0);
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_skybasic.fsh"), """
                #version 120
                varying vec4 skyColor;
                void main() { gl_FragData[0] = skyColor; }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackProgramPlan sky = analysis.plan().program("gbuffers_skybasic");
        assertTrue(sky.executable() && sky.deviations().contains("SKY_VERTEX_BRIDGE"), sky.deviations().toString());
        assertTrue(sky.convertedVertex().contains("vec4(0.0, 0.0, 0.0, 1.0).w"), sky.convertedVertex());
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), compiled.failures().toString());
    }

    /** A bridge that runs and fails says why, beside the code that keeps the program out. */
    @Test
    void anAttemptedBridgeNamesItsFailure(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_skybasic.vsh"), """
                #version 120
                varying vec3 skyColor;
                void main() { skyColor = gl_Color.rgb; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_skybasic.fsh"), """
                #version 120
                varying float skyColor;
                void main() { gl_FragData[0] = vec4(skyColor); }
                """);
        PackProgramPlan sky = PackProbe.analyze(root).plan().program("gbuffers_skybasic");
        assertTrue(!sky.executable() && sky.deviations().contains("SKY_VERTEX_BRIDGE_UNSUPPORTED")
                && sky.deviations().stream().anyMatch(value -> value.startsWith("SKY_VERTEX_CONVERSION_FAILED:")),
                sky.deviations().toString());
    }

    /**
     * Only a uniform block, or a uniform whose type is no sampler or image, is a value uniform; a
     * memory qualifier between {@code uniform} and the type does not make one. The failure names it.
     */
    @Test
    void aValueUniformIsNamedByItsType() {
        String fragment = """
                #version 330
                in vec2 uv;
                out vec4 color;
                void main() { color = vec4(uv, 0.0, 1.0); }
                """;
        String image = """
                #version 330
                layout(rgba16f) uniform restrict image2D image0;
                in vec3 vaPosition;
                out vec2 uv;
                void main() { uv = vaPosition.xy; gl_Position = vec4(vaPosition, 1.0); }
                """;
        String value = image.replace("layout(rgba16f) uniform restrict image2D image0;",
                "uniform PackValues { float wetness; } packValues;");
        assertTrue(!failure(image, fragment).contains("value uniform"), failure(image, fragment));
        assertTrue(failure(value, fragment).contains("value uniform is unplanned: PackValues"),
                failure(value, fragment));
    }

    /** Vulkan GLSL has gl_VertexIndex, not gl_VertexID; RenderPearl's terrain reads gl_VertexID & 3. */
    @Test
    void aTerrainVertexReadsGlVertexIdAsTheVertexIndex() {
        String fragment = """
                #version 330
                in vec2 uv;
                out vec4 color;
                void main() { color = vec4(uv, 0.0, 1.0); }
                """;
        String vertex = """
                #version 330
                in vec3 vaPosition;
                out vec2 uv;
                void main() { uv = vec2(float(gl_VertexID & 3)); gl_Position = vec4(vaPosition, 1.0); }
                """;
        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.planProgram(fragment, vertex,
                        UniformRegistry.Stage.GEOMETRY, null, false)
                .project("vertex", UniformRegistry.Stage.GEOMETRY);
        String converted = TerrainVertexBridge.convertModernTerrainVertexOrThrow(vertex, null, fragment,
                GlslVertexPreambles.MODERN_TERRAIN_VERTEX_PREAMBLE, interfacePlan, false, false, false).source();
        assertTrue(converted.contains("gl_VertexIndex & 3") && !converted.contains("gl_VertexID"), converted);
    }

    /** The modern terrain bridge's first failure for a pair, or an empty string when it converts. */
    private static String failure(String vertex, String fragment) {
        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.planProgram(fragment, vertex,
                        UniformRegistry.Stage.GEOMETRY, null, false)
                .project("vertex", UniformRegistry.Stage.GEOMETRY);
        try {
            TerrainVertexBridge.convertModernTerrainVertexOrThrow(vertex, null, fragment,
                    GlslVertexPreambles.MODERN_TERRAIN_VERTEX_PREAMBLE, interfacePlan, false, false, false);
            return "";
        } catch (IllegalArgumentException failure) {
            return String.valueOf(failure.getMessage());
        }
    }
}
