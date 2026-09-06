package net.chimera.shaderpack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Deterministic checks for the bounded M7.2 token translation boundary. */
public final class M72ConformanceHarness {
    private M72ConformanceHarness() {}

    public static void main(String[] args) throws Exception {
        testLegacyPostTranslation();
        testLocalCatalogNameDoesNotBecomeUniform();
        testUnsupportedProjectedShadowFallback();
        testInterfaceAndOutputOrdering();
        testFixtureSources();
        System.out.println("M7.2 conformance harness passed");
    }

    private static void testLocalCatalogNameDoesNotBecomeUniform() {
        String source = "#version 120\n"
                + "uniform mat4 gbufferProjectionInverse;\n"
                + "void main() { float timeAngle = 0.5;\n"
                + "    gl_FragColor = vec4(gbufferProjectionInverse[0].x + timeAngle); }\n";
        PostTargetPlan target = PostTargetPlan.parse("local", source).plan();
        UniformRegistry.ProgramInterface plan =
                UniformRegistry.planPreparedPost(source, target);
        assertTrue(!plan.deviations().contains("UNIFORM_IMPLICIT_DECLARATION:timeAngle"),
                "local timeAngle was misidentified as a catalog uniform");
        assertTrue(plan.executableUniforms().stream()
                        .anyMatch(value -> value.name().equals("gbufferProjectionInverse")),
                "declared catalog matrix was not retained");
        String converted = LegacyGlslConverter.convertPostFragment(
                source, null, plan, target, Map.of());
        assertTrue(converted != null, "local catalog-name source did not convert");
        assertTrue(!converted.contains("float timeAngle;"),
                "local timeAngle leaked into the generated uniform block");
    }

    private static void testLegacyPostTranslation() {
        String source = """
                #version 130
                #extension GL_ARB_shader_texture_lod : enable
                uniform mat4 gbufferProjection, gbufferProjectionInverse;
                uniform sampler2D colortex0;
                uniform sampler2D shadowtex0;
                noperspective varying vec2 texCoord;
                void main() {
                    vec4 nested = texture2D(colortex0, texCoord + texture2D(colortex0, texCoord).xy);
                    vec4 shadow = shadow2D(shadowtex0, vec3(texCoord, 0.5));
                    gl_FragColor = nested * shadow + vec4(gbufferProjectionInverse[0].x);
                }
                """;
        PostTargetPlan target = PostTargetPlan.parse("composite", source).plan();
        UniformRegistry.ProgramInterface plan = UniformRegistry.planPreparedPost(source, target);
        String converted = LegacyGlslConverter.convertPostFragment(
                source, null, plan, target, Map.of());
        assertTrue(converted != null, "legacy post source did not convert: " + plan.deviations());
        assertEquals(1, count(converted, "#version 460"), "version was not normalized");
        assertTrue(!converted.contains("#version 130"), "legacy version remained");
        assertTrue(!converted.contains("GL_ARB_shader_texture_lod"), "legacy extension remained");
        assertTrue(converted.contains("step(vec3(texCoord, 0.5).z"),
                "shadow2D was not inlined");
        assertTrue(!converted.contains("sampler2D sampler"),
                "opaque sampler was emitted as a helper parameter");
        assertTrue(converted.contains("textureLod") || converted.contains("texture("),
                "texture operation was not translated");
        assertTrue(converted.contains("gbufferProjectionInverse"),
                "canonical matrix declaration was lost");

        String nestedShadowSource = "#version 120\n"
                + "uniform sampler2D shadowtex0;\n"
                + "void main() { gl_FragColor = shadow2D(shadowtex0, "
                + "vec3(shadow2D(shadowtex0, vec3(0.0)).x)); }\n";
        PostTargetPlan nestedTarget = PostTargetPlan.parse("nested", nestedShadowSource).plan();
        UniformRegistry.ProgramInterface nestedPlan =
                UniformRegistry.planPreparedPost(nestedShadowSource, nestedTarget);
        String nested = LegacyGlslConverter.convertPostFragment(
                nestedShadowSource, null, nestedPlan, nestedTarget, Map.of());
        assertTrue(nested != null && !nested.contains("shadow2D"),
                "nested shadow lookup was not fully inlined");

        String implicitUniformSource = "#version 120\n"
                + "void main() { gl_FragColor = vec4(gbufferProjectionInverse[0].x); }\n";
        PostTargetPlan implicitTarget =
                PostTargetPlan.parse("implicit", implicitUniformSource).plan();
        UniformRegistry.ProgramInterface implicitPlan =
                UniformRegistry.planPreparedPost(implicitUniformSource, implicitTarget);
        assertTrue(implicitPlan.executableUniforms().stream()
                        .anyMatch(value -> value.name().equals("gbufferProjectionInverse")),
                "implicit catalog matrix was incorrectly marked unused");
        String implicit = LegacyGlslConverter.convertPostFragment(
                implicitUniformSource, null, implicitPlan, implicitTarget, Map.of());
        assertTrue(implicit != null && implicit.contains("gbufferProjectionInverse"),
                "catalog matrix was not synthesized from a prepared reference");
        assertTrue(implicit.contains("mat4 gbufferProjectionInverse;"),
                "implicit catalog matrix declaration was not generated");

        String comparisonSource = "#version 120\n"
                + "uniform sampler2DShadow shadowtex0;\n"
                + "void main() { gl_FragColor = shadow2D(shadowtex0, vec3(0.0)); }\n";
        PostTargetPlan comparisonTarget = PostTargetPlan.parse("comparison", comparisonSource).plan();
        UniformRegistry.ProgramInterface comparisonPlan =
                UniformRegistry.planPreparedPost(comparisonSource, comparisonTarget);
        String comparison = LegacyGlslConverter.convertPostFragment(
                comparisonSource, null, comparisonPlan, comparisonTarget, Map.of());
        assertTrue(comparison != null && comparison.contains("vec4(texture(shadowtex0"),
                "sampler2DShadow lookup was not inlined");
    }

    private static void testUnsupportedProjectedShadowFallback() {
        String source = "#version 120\n"
                + "uniform sampler2D shadowtex0;\n"
                + "void main() { gl_FragColor = shadow2DProj(shadowtex0, vec4(0.0)); }\n";
        PostTargetPlan target = PostTargetPlan.parse("composite", source).plan();
        UniformRegistry.ProgramInterface plan = UniformRegistry.planPreparedPost(source, target);
        assertTrue(LegacyGlslConverter.convertPostFragment(source, null, plan, target, Map.of()) == null,
                "projected shadow lookup did not fail closed");
    }

    private static void testInterfaceAndOutputOrdering() {
        String source = "#version 120\n"
                + "uniform float unused, frameTimeCounter;\n"
                + "uniform sampler2D colortex0, colortex1;\n"
                + "varying vec2 texCoord, unusedUv;\n"
                + "void main() { gl_FragData[0] = texture2D(colortex0, texCoord); }\n";
        PostTargetPlan target = PostTargetPlan.parse("composite", source).plan();
        UniformRegistry.ProgramInterface plan = UniformRegistry.planPreparedPost(source, target);
        String converted = LegacyGlslConverter.convertPostFragment(source, null, plan, target, Map.of());
        assertTrue(converted != null, "multi-declaration post source did not convert: " + plan.deviations());
        assertTrue(converted.indexOf("layout(location = 0) out") < converted.indexOf("void main"),
                "generated output declaration follows its first use");
        assertTrue(!converted.contains("unusedUv"), "unused varying was retained");
    }

    private static void testFixtureSources() throws Exception {
        Path root = Path.of("testpacks/m7_2/translation");
        assertTrue(Files.isRegularFile(root.resolve("composite.fsh")), "M7.2 fixture is missing");
        assertTrue(Files.isRegularFile(root.resolve("unsupported.fsh")), "M7.2 fallback fixture is missing");
    }

    private static int count(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
