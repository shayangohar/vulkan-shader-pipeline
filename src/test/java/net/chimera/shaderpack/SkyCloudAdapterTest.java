package net.chimera.shaderpack;

import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sky and cloud adapters. */
final class SkyCloudAdapterTest {
    @Test
    void skyCloudAdapters() throws Exception {
        Path root = TestPacks.fixtures();
        Path fixture = root.resolve("m8_5/sky_clouds");
        if (!Files.isDirectory(fixture)) {
            throw new AssertionError("fixture is missing: " + fixture);
        }
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "report is not deterministic");

        assertProgram(first.plan(), "gbuffers_skybasic", UniformRegistry.Stage.SKY,
                "SKY_VERTEX_BRIDGE", FamilyAdapterPlan.VertexContract.SKY_POSITION);
        assertProgram(first.plan(), "gbuffers_skytextured", UniformRegistry.Stage.SKY,
                "SKY_VERTEX_BRIDGE", FamilyAdapterPlan.VertexContract.SKY_POSITION_UV);
        assertProgram(first.plan(), "gbuffers_clouds", UniformRegistry.Stage.CLOUD,
                "CLOUD_VERTEX_BRIDGE", FamilyAdapterPlan.VertexContract.CLOUD_POSITION_COLOR_NORMAL);
        assertTrue(first.report().shouldAttempt("gbuffers_skybasic"),
                "skybasic report rejected an executable plan");
        assertTrue(first.report().shouldAttempt("gbuffers_clouds"),
                "cloud report rejected an executable plan");
        assertTrue(first.plan().program("gbuffers_clouds").convertedFragment().contains(".a > 0.1"),
                "clouds lost Iris's ONE_TENTH_ALPHA test");
        assertTrue(first.plan().program("gbuffers_skytextured").convertedFragment()
                        .contains("chimeraTexture"),
                "textured sky did not use the canonical atlas sampler");
        assertTrue(first.plan().program("gbuffers_skytextured").convertedFragment()
                        .contains("layout(binding = 3)"),
                "textured sky sampler did not use the geometry binding lane");
        assertNoUnstable(first.report().toJson(), fixture);
        assertTrue(Files.isRegularFile(root.resolve("baselines/m8_5b.json")),
                "baseline is missing");
    }

    /**
     * BSL (CLOUDS=3) and Complementary (CLOUD_STYLE_DEFINE=50) shade vanilla clouds by face
     * normal. Iris hands their programs the decoded face: gl_Vertex with the cloud offset, the
     * axis normal, the face colour times the cloud colour, UV0 (0.5, 0.5) and full light.
     */
    @Test
    void cloudProgramReadsIrisFaceInputs() {
        String vertex = """
                #version 120
                varying vec2 texCoord;
                varying vec3 normal;
                varying vec4 color;
                varying vec2 light;
                void main() {
                    texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    light = gl_MultiTexCoord1.xy / 240.0;
                    color = gl_Color;
                    normal = normalize(gl_NormalMatrix * gl_Normal);
                    gl_Position = ftransform();
                }
                """;
        String fragment = "#version 120\nuniform sampler2D texture;\nvarying vec2 texCoord;\nvarying vec3 normal;\n"
                + "varying vec4 color;\nvarying vec2 light;\n"
                + "void main() { gl_FragData[0] = texture2D(texture, texCoord) * color * vec4(normal, light.x); }\n";
        var conversion = HostLayoutVertexBridge.convertCloudVertex(vertex, null, fragment);
        assertTrue(conversion != null, "a normal-reading cloud program did not convert");
        String converted = conversion.source();
        for (String expected : List.of("layout(location = 2) in vec3 Normal;", "const vec2 UV0 = vec2(0.5);",
                "const ivec2 UV2 = ivec2(240);", "return vec4(Position + ModelOffset, 1.0);",
                "color = (Color * ColorModulator);", "transpose(inverse(mat3(ModelViewMat))) * Normal.xyz")) {
            assertTrue(converted.contains(expected), "cloud vertex lost " + expected + ": " + converted);
        }
        assertTrue(PackCompileTest.compile(converted, true) == null, "cloud vertex fails compilation");
    }

    private static void assertProgram(PackPlan plan, String name, UniformRegistry.Stage stage,
                                      String deviation, FamilyAdapterPlan.VertexContract contract) {
        PackProgramPlan program = plan == null ? null : plan.program(name);
        assertTrue(program != null, "program is missing: " + name);
        assertTrue(program.executable(), name + " is not executable: " + program.deviations());
        assertEquals(stage, program.interfacePlan().effective(stage).stage(),
                name + " stage");
        assertTrue(program.convertedVertex() != null && program.convertedFragment() != null,
                name + " conversion is incomplete");
        assertTrue(program.deviations().contains(deviation),
                name + " missing bridge deviation: " + deviation);
        assertEquals(contract, program.familyAdapter().vertexContract(),
                name + " host vertex contract");
    }

    private static void assertNoUnstable(String text, Path fixture) {
        assertTrue(!text.contains(fixture.toAbsolutePath().toString()),
                "report contains an absolute path");
        assertTrue(!text.matches("(?s).*\\b(?:timestamp|generatedAt)\\b.*"),
                "report contains a timestamp");
    }
}
