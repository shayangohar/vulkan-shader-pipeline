package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic checks for the measured M8.0 modern post translation subset. */
public final class M80ConformanceHarness {
    private M80ConformanceHarness() {}

    public static void main(String[] args) {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        PackProbe.Analysis analysis = PackProbe.analyze(root.resolve("m8_0/modern_post"));
        PackProgramPlan composite = require(analysis.plan(), "composite");
        PackProgramPlan composite1 = require(analysis.plan(), "composite1");
        PackProgramPlan unsupported = require(analysis.plan(), "composite2");

        assertTrue(composite.executable(), "M8.0 modern 330 post was rejected: " + composite.deviations()
                + " interface=" + composite.interfacePlan().deviations()
                + " target=" + (composite.targetPlan() == null ? "null" : composite.targetPlan().deviations()));
        assertTrue(composite.deviations().contains("MODERN_GLSL_TRANSLATED"),
                "M8.0 translation deviation is missing");
        assertTrue(composite.convertedFragment().startsWith("#version 460"),
                "M8.0 generated version is missing");
        assertTrue(!composite.convertedFragment().contains("layout(location = 0) out vec4 fragColor"),
                "M8.0 source output declaration was not replaced");
        assertTrue(composite.convertedFragment().contains("chimeraFragColor0"),
                "M8.0 generated output is missing");
        assertTrue(composite.convertedFragment().contains("texture(colortex0"),
                "M8.0 modern texture call was not retained safely");
        assertTrue(composite.interfacePlan().samplers().stream()
                        .anyMatch(value -> value.name().equals("colortex0")),
                "M8.0 sampler interface is missing");

        assertTrue(composite1.executable(), "M8.0 modern 400 post was rejected");
        assertTrue(!composite1.convertedFragment().contains("#version 400"),
                "M8.0 old version directive remains");

        assertTrue(!unsupported.executable(), "M8.0 unsupported version was not rejected");
        assertTrue(unsupported.deviations().stream()
                        .anyMatch(value -> value.equals("TRANSLATION_UNSUPPORTED:unsupported modern post GLSL version: 450")
                                || value.equals("POST_CONVERTER_UNSUPPORTED")),
                "M8.0 unsupported version has no named fallback");
        assertTrue(LegacyGlslConverter.supportsModernPost("#version 330\nvoid main(){}"),
                "M8.0 version capability check failed");
        assertTrue(!LegacyGlslConverter.supportsModernPost("#version 450\nvoid main(){}"),
                "M8.0 unsupported version was accepted");
        verifyFixedFullscreenVertex();
        verifyPerTargetFallback(root);
        System.out.println("[chimera] M8.0 modern GLSL conformance: PASS");
    }

    private static void verifyFixedFullscreenVertex() {
        String vertex = "#version 130\nvoid main() { gl_Position = ftransform(); }\n";
        String fragment = "#version 130\nvoid main() { gl_FragColor = vec4(1.0); }\n";
        GlslInterfaceScanner.StageInterface vertexInterface =
                GlslInterfaceScanner.scan(vertex, true);
        GlslInterfaceScanner.StageInterface fragmentInterface =
                GlslInterfaceScanner.scan(fragment, false);
        GlslInterfaceScanner.ProgramMatch match =
                GlslInterfaceScanner.match(vertexInterface, fragmentInterface);
        LegacyGlslConverter.PostVertexConversion conversion =
                LegacyGlslConverter.convertPostVertex(vertex, vertexInterface,
                        fragmentInterface, match);
        assertTrue(conversion != null && conversion.source().contains("gl_Position"),
                "M8.0 paired post vertex without varyings was rejected");
    }

    private static void verifyPerTargetFallback(Path root) {
        PackProbe.Analysis resources = PackProbe.analyze(root.resolve("m7_5/resources"));
        PackConfig.PackConfigData base = resources.config();
        Map<Integer, Integer> formats = new TreeMap<>(base.colortexFormats());
        formats.put(4, -1);
        PackConfig.PackConfigData unsupportedFormat = new PackConfig.PackConfigData(
                formats, base.drawBufferCount(), base.shadowSettings(), base.shaderConstants(),
                base.deviations(), base.settings(), base.targetSettings(), base.flips(), base.preFlips());
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                resources.plan().programs(), unsupportedFormat, resources.plan().resources(),
                1920, 1080, 8, 16384);
        TargetStep dependent = graph.step("composite");
        TargetStep unrelated = graph.step("final");
        assertTrue(dependent != null && !dependent.executable()
                        && dependent.deviations().contains("POST_TARGET_FORMAT_DEVICE_UNSUPPORTED:4"),
                "M8.0 unsupported target format did not reject only its dependent stage");
        assertTrue(unrelated != null && unrelated.executable(),
                "M8.0 unsupported target format rejected an unrelated stage");

        PackProbe.Analysis targetGraph = PackProbe.analyze(root.resolve("m7_4/target_graph"));
        PackConfig.PackConfigData targetConfig = targetGraph.config();
        Map<Integer, PackConfig.TargetSettings> settings = new TreeMap<>(targetConfig.targetSettings());
        PackConfig.TargetSettings target = settings.getOrDefault(1, PackConfig.TargetSettings.defaults());
        settings.put(1, new PackConfig.TargetSettings(target.sizeExpression(), target.clear(),
                target.clearColor(), true, target.deviations()));
        PackConfig.PackConfigData mipmapped = new PackConfig.PackConfigData(
                targetConfig.colortexFormats(), targetConfig.drawBufferCount(), targetConfig.shadowSettings(),
                targetConfig.shaderConstants(), targetConfig.deviations(), targetConfig.settings(),
                settings, targetConfig.flips(), targetConfig.preFlips());
        PackTargetGraphPlan mipGraph = PackTargetGraphPlan.build(
                targetGraph.plan().programs(), mipmapped, targetGraph.plan().resources(),
                1920, 1080, 8, 16384);
        TargetStep mipStep = mipGraph.step("composite1");
        assertTrue(mipStep != null && mipStep.executable()
                        && mipStep.deviations().contains("POST_TARGET_MIPMAP_BASE_LEVEL_FALLBACK:1"),
                "M8.0 mipmap request did not keep a safe base-level pass");
    }

    private static PackProgramPlan require(PackPlan plan, String name) {
        PackProgramPlan value = plan.program(name);
        if (value == null) {
            throw new AssertionError("M8.0 missing program: " + name);
        }
        return value;
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
