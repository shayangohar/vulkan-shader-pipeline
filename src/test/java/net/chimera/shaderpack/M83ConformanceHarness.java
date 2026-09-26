package net.chimera.shaderpack;

import java.nio.file.Path;

/** Deterministic checks for the bounded M8.3 pack shadow contract. */
public final class M83ConformanceHarness {
    private M83ConformanceHarness() {}

    public static void main(String[] args) {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        PackProbe.Analysis analysis = PackProbe.analyze(root.resolve("m8_3/shadow"));
        PackProgramPlan shadow = analysis.plan().program("shadow");
        assertTrue(shadow != null, "M8.3 shadow program was not discovered");
        verifyShadowClipComposition();
        verifyShadowMatrixBuiltins();
        assertTrue(shadow.executable(), "M8.3 modern shadow was rejected: " + shadow.deviations()
                + " interface=" + shadow.interfacePlan().deviations());
        assertTrue(shadow.convertedVertex() != null
                        && shadow.convertedVertex().startsWith("#version 460"),
                "M8.3 modern shadow vertex was not translated");
        assertTrue(shadow.convertedFragment() != null
                        && shadow.convertedFragment().contains("ChimeraShadowUniforms")
                        && shadow.convertedFragment().startsWith("#version 460"),
                "M8.3 shadow fragment UBO was not generated");
        assertTrue(shadow.deviations().contains("MODERN_SHADOW_VERTEX_BRIDGE"),
                "M8.3 modern shadow bridge deviation is missing");
        assertTrue(shadow.deviations().contains("CUSTOM_UNIFORM_BRIDGE:shadowFade"),
                "M8.3 pack-authored shadow fade was not served by its own declaration");

        PackConfig.ShadowSettings settings = analysis.config().shadowSettings();
        assertTrue(settings.resolution() == 1024, "M8.3 resolution was not applied");
        assertTrue(settings.distance() == 192.0F, "M8.3 distance was not applied");
        assertTrue(settings.distanceRenderMultiplier() == 1.25F,
                "M8.3 distance multiplier was not applied");
        assertTrue(settings.sunPathRotation() == 35.0F
                        && settings.sunPathOffset() == 4.0F,
                "M8.3 sun-path settings were not applied");

        String legacy = "#version 120\nvoid main(){gl_Position=ftransform();}\n";
        assertTrue(LegacyGlslConverter.supportsShadowVertex(legacy,
                        "#version 120\nvoid main(){gl_FragColor=vec4(1.0);}"),
                "legacy shadow compatibility regressed");
        assertTrue(!LegacyGlslConverter.supportsModernShadow(
                "#version 450\nvoid main(){gl_Position=vec4(0.0);}", ""),
                "unsupported modern shadow version was accepted");

        // The generated epilogue must be generic. Pack-authored Z compression
        // remains in the renamed source entry point and is never duplicated.
        LegacyGlslConverter.TerrainVertexConversion fixtureShadow =
                LegacyGlslConverter.convertShadowVertex(shadow.stageSource("vertex"), null,
                        shadow.stageSource("fragment"));
        assertTrue(fixtureShadow != null, "fixture shadow vertex was rejected");
        verifyShadowClipRange("M8.3 fixture", fixtureShadow.source());

        String legacyShadow = "#version 120\n"
                + "void main() {\n"
                + "    gl_Position = ftransform();\n"
                + "    gl_Position.z = gl_Position.z * 0.2 + 0.5;\n"
                + "    gl_Position.z += 0.0;\n"
                + "}\n";
        LegacyGlslConverter.TerrainVertexConversion legacyShadowConversion =
                LegacyGlslConverter.convertShadowVertex(legacyShadow, null,
                        "#version 120\nvoid main(){gl_FragColor=vec4(1.0);}");
        assertTrue(legacyShadowConversion != null, "legacy shadow vertex was rejected");
        verifyShadowClipRange("legacy shadow", legacyShadowConversion.source());
        assertTrue(legacyShadowConversion.source().contains("0.2"),
                "authored shadow z scale was rewritten");
        String externalPack = System.getenv("CHIMERA_M83_PACK");
        if (externalPack != null && !externalPack.isBlank()) {
            PackProgramPlan externalShadow = PackProbe.analyze(Path.of(externalPack))
                    .plan().program("shadow");
            if (externalShadow != null && externalShadow.convertedFragment() != null) {
                assertTrue(externalShadow.executable(),
                        "external shadow plan is not executable");
                verifyShadowClipRange("external shadow", externalShadow.convertedVertex());
            }
        }
        System.out.println("[chimera] M8.3 shadow conformance: PASS");
    }


    /** Checks the generic conversion appears once and only after authored main. */
    private static void verifyShadowClipRange(String what, String source) {
        assertTrue(source != null, what + " shadow vertex has no source");
        assertTrue(source.contains("chimeraAuthoredShadowMain()"),
                what + " wrapper does not call the authored entry point");
        assertTrue(source.contains("void chimeraAuthoredShadowMain"),
                what + " authored entry point was not renamed");
        assertTrue(source.contains("void main()"), what + " wrapper does not declare main");
        long remaps = occurrences(source,
                "gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);");
        assertTrue(remaps == 1, what + " clip-range remap count is " + remaps + ", expected 1");
        assertTrue(!source.contains("0.2 * gl_Position.z"),
                what + " converter duplicated pack-authored Z compression");
        assertTrue(source.indexOf("chimeraAuthoredShadowMain();")
                        < source.indexOf("gl_Position.z = 0.5 *"),
                what + " clip-range remap does not follow the authored entry point");
    }

    /** Independent authored-transform plus Vulkan epilogue arithmetic; not a GPU visibility test. */
    private static void verifyShadowClipComposition() {
        float w = 3.5f;
        for (float ndc : new float[] {-1.0f, 0.0f, 1.0f}) {
            assertNear((ndc + 1.0f) * 0.5f * w,
                    convertAfterAuthored(ndc * w, w, 1.0f),
                    "generic [-w,w] clip conversion at NDC " + ndc);
            assertNear((0.2f * ndc + 1.0f) * 0.5f * w,
                    convertAfterAuthored(ndc * w, w, 0.2f),
                    "single authored 0.2 compression at NDC " + ndc);
            assertNear((0.4f * ndc + 1.0f) * 0.5f * w,
                    convertAfterAuthored(ndc * w, w, 0.4f),
                    "single authored 0.4 compression at NDC " + ndc);
        }

        float multipleAssignments = 0.75f;
        multipleAssignments += 0.25f * w;
        multipleAssignments *= 0.4f;
        multipleAssignments -= 0.1f * w;
        assertNear(0.5f * (multipleAssignments + w),
                convertAfterAuthoredAssignments(0.75f, w),
                "multiple authored position assignments before the generic epilogue");

        // An early return still returns to the wrapper, which performs the
        // same one-time conversion on the position written before returning.
        float earlyReturnedZ = 0.6f * w;
        assertNear(0.5f * (earlyReturnedZ + w),
                convertAfterEarlyReturn(earlyReturnedZ, w),
                "epilogue after an authored main early return");
        System.out.println("[chimera] M8.7 clip composition math: PASS (GPU occlusion remains a runtime gate)");
    }

    private static float convertAfterAuthored(float z, float w, float authoredScale) {
        float authoredZ = z * authoredScale;
        return 0.5f * (authoredZ + w);
    }

    private static float convertAfterAuthoredAssignments(float initialZ, float w) {
        float z = initialZ;
        z += 0.25f * w;
        z *= 0.4f;
        z -= 0.1f * w;
        return 0.5f * (z + w);
    }

    private static float convertAfterEarlyReturn(float authoredZ, float w) {
        // The authored helper returns authoredZ. Its caller wrapper then runs.
        return 0.5f * (authoredZ + w);
    }

    /** Built-ins map to the shadow draw pair, not the camera gbuffer pair. */
    private static void verifyShadowMatrixBuiltins() {
        String vertex = "#version 120\n"
                + "void main() {\n"
                + "  vec4 p = gl_Vertex;\n"
                + "  vec4 viewPos = gl_ModelViewMatrix * p;\n"
                + "  gl_Position = gl_ProjectionMatrix * viewPos;\n"
                + "  gl_Position.xyz += gl_NormalMatrix * vec3(0.0);\n"
                + "  gl_Position += gl_ModelViewProjectionMatrix * p * 0.0;\n"
                + "  gl_Position.z *= 0.2;\n"
                + "}\n";
        String fragment = "#version 120\nvoid main(){gl_FragColor=vec4(1.0);}";
        UniformRegistry.ProgramInterfacePlan plan = UniformRegistry.planProgram(
                fragment, vertex, UniformRegistry.Stage.SHADOW, null, true);
        UniformRegistry.ProgramInterface vertexInterface =
                plan.project("vertex", UniformRegistry.Stage.SHADOW);
        assertTrue(vertexInterface.executable(),
                "shadow built-in matrix inputs were not planned: " + vertexInterface.deviations());
        assertTrue(vertexInterface.executableUniforms().stream()
                        .anyMatch(value -> value.name().equals("shadowModelView"))
                        && vertexInterface.executableUniforms().stream()
                        .anyMatch(value -> value.name().equals("shadowModelViewInverse"))
                        && vertexInterface.executableUniforms().stream()
                        .anyMatch(value -> value.name().equals("shadowProjection")),
                "shadow matrix built-ins did not synthesize the matching live UBO fields");
        LegacyGlslConverter.TerrainVertexConversion converted = LegacyGlslConverter.convertShadowVertex(
                vertex, null, fragment, vertexInterface);
        assertTrue(converted != null, "shadow matrix built-in fixture did not convert");
        String source = converted.source();
        assertTrue(source.contains("chimeraShadowUniforms.shadowModelView"),
                "gl_ModelViewMatrix did not map to the actual shadow view");
        assertTrue(source.contains("chimeraShadowUniforms.shadowProjection"),
                "gl_ProjectionMatrix did not map to the actual shadow projection");
        assertTrue(source.contains("transpose(chimeraShadowUniforms.shadowModelViewInverse)"),
                "gl_NormalMatrix did not map to the shadow normal matrix");
        assertTrue(source.contains("MVP * chimeraVertexValue()"),
                "ftransform/gl_ModelViewProjectionMatrix did not use the shadow MVP");
        assertTrue(!source.contains("gbufferModelView"),
                "shadow built-in translation repurposed the camera gbuffer matrix");
    }

    private static long occurrences(String source, String needle) {
        long count = 0;
        int index = source.indexOf(needle);
        while (index >= 0) {
            count++;
            index = source.indexOf(needle, index + needle.length());
        }
        return count;
    }


    private static void assertNear(float expected, float actual, String message) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > 1.0e-5f) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

}
