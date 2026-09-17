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
        verifyAuthoredFullscreenVertex();
        verifyAuthoredPostConstants();
        verifyDistinctPostResources();
        verifyShadowColorOutputs();
        verifyPerTargetFallback(root);
        verifyOrientationFixture(root);
        System.out.println("[chimera] M8.0 modern GLSL conformance: PASS");
    }

    private static void verifyAuthoredFullscreenVertex() {
        String vertex = """
                #version 130
                uniform sampler2D colortex5;
                uniform mat4 gbufferModelView;
                uniform vec3 sunPosition;
                uniform float viewWidth, viewHeight;
                uniform int frameCounter;
                varying vec2 texCoord;
                flat out vec3 upVec, sunVec;
                flat out float vlFactor;
                vec3 authoredSun() { return normalize(sunPosition); }
                void main() {
                    gl_Position = ftransform();
                    texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    upVec = normalize(gbufferModelView[1].xyz);
                    sunVec = authoredSun();
                    vlFactor = texelFetch(colortex5, ivec2(viewWidth-1, viewHeight-1), 0).a;
                    if (frameCounter % 2 == 0) vlFactor = max(vlFactor - 0.1, 0.0);
                }
                """;
        String fragment = """
                #version 130
                uniform sampler2D colortex0;
                varying vec2 texCoord;
                flat in vec3 upVec, sunVec;
                flat in float vlFactor;
                void main() {
                    gl_FragColor = texture2D(colortex0, texCoord) * vlFactor
                        + vec4(upVec + sunVec, 0.0);
                }
                """;
        UniformRegistry.ProgramInterface plan = UniformRegistry.planProgram(fragment, vertex,
                UniformRegistry.Stage.POST, null, true).effective(UniformRegistry.Stage.POST);
        GlslInterfaceScanner.StageInterface vs = GlslInterfaceScanner.scan(vertex, true);
        GlslInterfaceScanner.StageInterface fs = GlslInterfaceScanner.scan(fragment, false);
        LegacyGlslConverter.PostVertexConversion conversion = LegacyGlslConverter.convertPostVertex(
                vertex, vs, fs, GlslInterfaceScanner.match(vs, fs), plan, Map.of());
        assertTrue(conversion.source() != null, "Authored post vertex rejected: " + conversion.deviations());
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(conversion.source());
        assertTrue(usage.successful() && usage.liveSamplers().contains("colortex5"),
                "Authored temporal texture read was discarded");
        assertTrue(plan.hasSampler("colortex0") && plan.hasSampler("colortex5"),
                "Post interface lost a stage's sampler dependency");
        for (String expression : new String[] {
                "upVec = normalize(gbufferModelView[1].xyz)", "sunVec = authoredSun()",
                "vlFactor = texelFetch(colortex5, ivec2(viewWidth-1, viewHeight-1), 0).a",
                "if (frameCounter % 2 == 0) vlFactor = max(vlFactor - 0.1, 0.0)"}) {
            assertTrue(conversion.source().contains(expression), "Authored expression changed: " + expression);
        }
        assertTrue(GlslInterfaceScanner.match(GlslInterfaceScanner.scan(conversion.source(), true), fs)
                        .executable(), "Translated vertex no longer provides its fragment inputs");
        String convertedFragment = LegacyGlslConverter.convertPostFragment(fragment, null, plan,
                PostTargetPlan.parse("composite", fragment).plan(), Map.of(),
                GlslInterfaceScanner.match(vs, fs).locations(),
                Map.of("texCoord", "vec2", "upVec", "vec3", "sunVec", "vec3", "vlFactor", "float"));
        assertTrue(convertedFragment != null, "Vertex-only sampler prevented paired fragment conversion");
        GlslResourceUsage.Analysis fragmentUsage = GlslResourceUsage.analyze(convertedFragment);
        assertTrue(fragmentUsage.successful() && fragmentUsage.liveSamplers().contains("colortex0"),
                "Paired fragment lost its authored color input");
        String unsupported = vertex.replace("ftransform()", "gl_ModelViewMatrixInverse * gl_Vertex");
        LegacyGlslConverter.PostVertexConversion rejected = LegacyGlslConverter.convertPostVertex(
                unsupported, vs, fs, GlslInterfaceScanner.match(vs, fs), plan, Map.of());
        assertTrue(rejected.source() == null && rejected.deviations().contains(
                        "POST_VERTEX_BUILTIN_UNSUPPORTED:gl_ModelViewMatrixInverse"),
                "Unsupported authored builtin was not rejected by name");
    }

    private static void verifyAuthoredPostConstants() {
        String declarations = """
                const float shadowDistance = 192.0;
                const float sunPathRotation = -25.0;
                const int shadowMapResolution = 4096;
                """;
        String body = """
                varying vec4 settings;
                void main() {
                    gl_Position = ftransform();
                    settings = vec4(shadowDistance, sunPathRotation,
                            float(shadowMapResolution), shadowDistanceRenderMul);
                }
                """;
        String fragment = """
                #version 130
                varying vec4 settings;
                void main() { gl_FragColor = settings; }
                """;
        Map<String, String> constants = Map.of("shadowDistance", "192.0",
                "sunPathRotation", "-25.0", "shadowMapResolution", "4096",
                "shadowDistanceRenderMul", "0.75");
        for (String authored : new String[] {declarations, ""}) {
            String vertex = "#version 130\n" + authored + body;
            UniformRegistry.ProgramInterface plan = UniformRegistry.planProgram(fragment, vertex,
                    UniformRegistry.Stage.POST, null, true).effective(UniformRegistry.Stage.POST);
            GlslInterfaceScanner.StageInterface vs = GlslInterfaceScanner.scan(vertex, true);
            GlslInterfaceScanner.StageInterface fs = GlslInterfaceScanner.scan(fragment, false);
            LegacyGlslConverter.PostVertexConversion conversion = LegacyGlslConverter.convertPostVertex(
                    vertex, vs, fs, GlslInterfaceScanner.match(vs, fs), plan, constants);
            assertTrue(conversion.source() != null,
                    "Post constants prevented authored vertex conversion: " + conversion.deviations());
            var tokens = GlslLexer.lex(conversion.source()).stream()
                    .filter(GlslLexer.Token::significant).toList();
            Map<String, Double> values = new TreeMap<>();
            for (int index = 1; index + 2 < tokens.size(); index++) {
                String name = tokens.get(index).text();
                if (!constants.containsKey(name) || !tokens.get(index + 1).symbol("=")) continue;
                String type = tokens.get(index - 1).text();
                assertTrue(type.equals(name.equals("shadowMapResolution") ? "int" : "float"),
                        "Post constant has the wrong GLSL type: " + name);
                StringBuilder initializer = new StringBuilder();
                int end = index + 2;
                while (end < tokens.size() && !tokens.get(end).symbol(";")) {
                    initializer.append(tokens.get(end++).text());
                }
                assertTrue(end < tokens.size(), "Post constant initializer is incomplete: " + name);
                assertTrue(values.putIfAbsent(name, Double.parseDouble(initializer.toString())) == null,
                        "Post vertex redeclares pack constant: " + name);
            }
            Map<String, Double> expected = new TreeMap<>();
            constants.forEach((name, value) -> expected.put(name, Double.parseDouble(value)));
            assertTrue(values.equals(expected),
                    "Post vertex lost authored or injected constant values: " + values);
            assertTrue(GlslInterfaceScanner.match(GlslInterfaceScanner.scan(conversion.source(), true), fs)
                            .executable(), "Post constants broke the authored fragment interface");
        }
    }

    private static void verifyDistinctPostResources() {
        String source = """
                uniform sampler2D colortex3, shadowcolor0, shadowcolor1, colortex8;
                void main() {
                    gl_FragColor = texture2D(colortex3, vec2(0.5))
                        + texture2D(shadowcolor0, vec2(0.5))
                        + texture2D(shadowcolor1, vec2(0.5))
                        + texture2D(colortex8, vec2(0.5));
                }
                """;
        UniformRegistry.ProgramInterface plan = UniformRegistry.planPrepared(source, UniformRegistry.Stage.POST);
        assertTrue(plan.executable() && plan.samplers().stream().map(UniformRegistry.SamplerBinding::slot)
                        .distinct().count() == 4, "Distinct post resources collide: " + plan.deviations());
        assertTrue(Integer.valueOf(8).equals(PackResourcePlan.targetIndex("colortex8")),
                "Higher target lost its canonical identity");
        UniformRegistry.ProgramInterfacePlan missing = UniformRegistry.planProgram(
                "uniform sampler2D absent; void main() { gl_FragColor = texture2D(absent, vec2(0.5)); }",
                "uniform sampler2D absent; void main() { gl_Position = vec4(0.0); }",
                UniformRegistry.Stage.POST, null, true);
        assertTrue(!missing.executable() && missing.deviations().contains("SAMPLER_NOT_MAPPED:absent")
                        && !missing.deviations().contains("SAMPLER_DECLARATION_UNUSED:absent"),
                "Live unmapped input was mislabeled unused: " + missing.deviations());
    }

    private static void verifyShadowColorOutputs() {
        String source = """
                #version 130
                /* DRAWBUFFERS:10 */
                void main() {
                    gl_FragData[0] = vec4(0.2, 0.3, 0.4, 0.5);
                    gl_FragData[1] = vec4(0.6, 0.7, 0.8, 0.9);
                }
                """;
        String converted = LegacyGlslConverter.convertFragment(source, null, true, new int[0], null,
                UniformRegistry.planPrepared(source, UniformRegistry.Stage.SHADOW));
        assertTrue(converted != null && converted.contains(
                        "chimeraShadowColor1 = vec4(0.2, 0.3, 0.4, 0.5)")
                        && converted.contains("chimeraShadowColor0 = vec4(0.6, 0.7, 0.8, 0.9)"),
                "Authored shadow drawbuffer routing or tint expression was lost");
        assertTrue(LegacyGlslConverter.convertFragment(source.replace("DRAWBUFFERS:10", "DRAWBUFFERS:20"),
                        null, true, new int[0], null,
                        UniformRegistry.planPrepared(source, UniformRegistry.Stage.SHADOW)) == null,
                "Unprovided shadow output target was silently dropped");
    }

    private static void verifyOrientationFixture(Path root) {
        PackProbe.Analysis analysis = PackProbe.analyze(root.resolve("m8_0/orientation"));
        String[] executable = {"composite", "composite1", "final"};
        for (String name : executable) {
            PackProgramPlan plan = require(analysis.plan(), name);
            assertTrue(plan.executable() && plan.convertedVertex() != null,
                    "M8.0 orientation stage is not executable: " + name
                            + " deviations=" + plan.deviations()
                            + " interface=" + plan.interfacePlan().deviations()
                            + " target=" + (plan.targetPlan() == null
                            ? "null" : plan.targetPlan().deviations()));
        }

        PackProgramPlan fallback = require(analysis.plan(), "composite2");
        assertTrue(!fallback.executable(),
                "M8.0 orientation middle fallback unexpectedly executes");

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
