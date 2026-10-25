package net.chimera.shaderpack;

import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The measured modern post translation subset. */
final class ModernPostTranslationTest {
    @Test
    void modernPostTranslation() {
        Path root = TestPacks.fixtures();
        PackProbe.Analysis analysis = PackProbe.analyze(root.resolve("m8_0/modern_post"));
        PackProgramPlan composite = require(analysis.plan(), "composite");
        PackProgramPlan composite1 = require(analysis.plan(), "composite1");
        PackProgramPlan unsupported = require(analysis.plan(), "composite2");

        assertTrue(composite.executable(), "modern 330 post was rejected: " + composite.deviations()
                + " interface=" + composite.interfacePlan().deviations()
                + " target=" + (composite.targetPlan() == null ? "null" : composite.targetPlan().deviations()));
        assertTrue(composite.deviations().contains("MODERN_GLSL_TRANSLATED"),
                "translation deviation is missing");
        assertTrue(composite.convertedFragment().startsWith("#version 460"),
                "generated version is missing");
        assertTrue(!composite.convertedFragment().contains("layout(location = 0) out vec4 fragColor"),
                "source output declaration was not replaced");
        assertTrue(composite.convertedFragment().contains("chimeraFragColor0"),
                "generated output is missing");
        assertTrue(composite.convertedFragment().contains("texture(colortex0"),
                "modern texture call was not retained safely");
        assertTrue(composite.interfacePlan().samplers().stream()
                        .anyMatch(value -> value.name().equals("colortex0")),
                "sampler interface is missing");

        assertTrue(composite1.executable(), "modern 400 post was rejected");
        assertTrue(!composite1.convertedFragment().contains("#version 400"),
                "old version directive remains");

        // Any desktop version converts (GlslVersion); GLSL ES is not one.
        assertTrue(!unsupported.executable(), "GLSL ES post was not rejected");
        assertTrue(unsupported.deviations().stream()
                        .anyMatch(value -> value.startsWith("TRANSLATION_UNSUPPORTED:")
                                || value.equals("POST_CONVERTER_UNSUPPORTED")),
                "GLSL ES post has no named fallback");
        assertTrue(PostVertexBridge.supportsModernPost("#version 330\nvoid main(){}")
                        && PostVertexBridge.supportsModernPost("#version 450 core\nvoid main(){}"),
                "version capability check failed");
        assertTrue(!PostVertexBridge.supportsModernPost("#version 300 es\nvoid main(){}"),
                "GLSL ES post was accepted");
        verifyPerTargetFallback(root);
        verifyOrientationFixture(root);
    }

    @Test
    void authoredFullscreenVertex() {
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
        PostVertexBridge.PostVertexConversion conversion = PostVertexBridge.convertPostVertex(
                vertex, vs, fs, GlslInterfaceScanner.match(vs, fs), plan);
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
        String convertedFragment = FragmentConverter.convertPostFragment(fragment, null, plan,
                PostTargetPlan.parse("composite", fragment).plan(),
                GlslInterfaceScanner.match(vs, fs).locations(),
                Map.of("texCoord", "vec2", "upVec", "vec3", "sunVec", "vec3", "vlFactor", "float"));
        assertTrue(convertedFragment != null, "Vertex-only sampler prevented paired fragment conversion");
        GlslResourceUsage.Analysis fragmentUsage = GlslResourceUsage.analyze(convertedFragment);
        assertTrue(fragmentUsage.successful() && fragmentUsage.liveSamplers().contains("colortex0"),
                "Paired fragment lost its authored color input");
        String unsupported = vertex.replace("ftransform()", "gl_ModelViewMatrixInverse * gl_Vertex");
        PostVertexBridge.PostVertexConversion rejected = PostVertexBridge.convertPostVertex(
                unsupported, vs, fs, GlslInterfaceScanner.match(vs, fs), plan);
        assertTrue(rejected.source() == null && rejected.deviations().contains(
                        "POST_VERTEX_BUILTIN_UNSUPPORTED:gl_ModelViewMatrixInverse"),
                "Unsupported authored builtin was not rejected by name");
    }

    /**
     * A program compiles the shadow constants it declares as it wrote them, as Iris compiles its
     * source: nothing replaces their values, and nothing declares one the program does not.
     */
    @Test
    void authoredPostConstantsStayAsWritten() {
        String declarations = """
                const float shadowDistance = 192.0;
                const float sunPathRotation = -25.0;
                const int shadowMapResolution = 4096;
                """;
        String body = """
                varying vec4 settings;
                void main() {
                    gl_Position = ftransform();
                    settings = vec4(shadowDistance, sunPathRotation, float(shadowMapResolution), 1.0);
                }
                """;
        String fragment = """
                #version 130
                varying vec4 settings;
                void main() { gl_FragColor = settings; }
                """;
        for (String authored : new String[] {declarations, ""}) {
            String vertex = "#version 130\n" + authored + body;
            UniformRegistry.ProgramInterface plan = UniformRegistry.planProgram(fragment, vertex,
                    UniformRegistry.Stage.POST, null, true).effective(UniformRegistry.Stage.POST);
            GlslInterfaceScanner.StageInterface vs = GlslInterfaceScanner.scan(vertex, true);
            GlslInterfaceScanner.StageInterface fs = GlslInterfaceScanner.scan(fragment, false);
            PostVertexBridge.PostVertexConversion conversion = PostVertexBridge.convertPostVertex(
                    vertex, vs, fs, GlslInterfaceScanner.match(vs, fs), plan);
            assertTrue(conversion.source() != null,
                    "Post constants prevented authored vertex conversion: " + conversion.deviations());
            for (String declaration : declarations.strip().split("\n")) {
                assertTrue(conversion.source().contains(declaration) == !authored.isEmpty(),
                        "Post vertex changed its constants: " + declaration + "\n" + conversion.source());
            }
        }
    }

    @Test
    void distinctPostResources() {
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

    @Test
    void shadowColorOutputs() {
        String source = """
                #version 130
                /* DRAWBUFFERS:10 */
                void main() {
                    gl_FragData[0] = vec4(0.2, 0.3, 0.4, 0.5);
                    gl_FragData[1] = vec4(0.6, 0.7, 0.8, 0.9);
                }
                """;
        String converted = FragmentConverter.FragmentConversionRequest
                .of(source, null, true, new int[0])
                .withInterfacePlan(UniformRegistry.planPrepared(source, UniformRegistry.Stage.SHADOW))
                .convert();
        assertTrue(converted != null && converted.contains(
                        "chimeraShadowColor1 = vec4(0.2, 0.3, 0.4, 0.5)")
                        && converted.contains("chimeraShadowColor0 = vec4(0.6, 0.7, 0.8, 0.9)"),
                "Authored shadow drawbuffer routing or tint expression was lost");
        String third = FragmentConverter.FragmentConversionRequest
                .of(source.replace("DRAWBUFFERS:10", "DRAWBUFFERS:20"), null, true, new int[0])
                .withInterfacePlan(UniformRegistry.planPrepared(source, UniformRegistry.Stage.SHADOW))
                .convert();
        assertTrue(third != null && third.contains("layout(location = 2) out vec4 chimeraShadowColor2;"),
                "shadowcolor2 is the shadow pass's third attachment (HIGHER_SHADOWCOLOR)");
        assertTrue(FragmentConverter.FragmentConversionRequest
                        .of(source.replace("DRAWBUFFERS:10", "DRAWBUFFERS:80"),
                                null, true, new int[0])
                        .withInterfacePlan(UniformRegistry.planPrepared(source, UniformRegistry.Stage.SHADOW))
                        .convert() == null,
                "A shadow output beyond Iris's eight targets was silently dropped");
    }

    private static void verifyOrientationFixture(Path root) {
        PackProbe.Analysis analysis = PackProbe.analyze(root.resolve("m8_0/orientation"));
        String[] executable = {"composite", "composite1", "final"};
        for (String name : executable) {
            PackProgramPlan plan = require(analysis.plan(), name);
            assertTrue(plan.executable() && plan.convertedVertex() != null,
                    "orientation stage is not executable: " + name
                            + " deviations=" + plan.deviations()
                            + " interface=" + plan.interfacePlan().deviations()
                            + " target=" + (plan.targetPlan() == null
                            ? "null" : plan.targetPlan().deviations()));
        }

        PackProgramPlan fallback = require(analysis.plan(), "composite2");
        assertTrue(!fallback.executable(),
                "orientation middle fallback unexpectedly executes");
    }

    private static void verifyPerTargetFallback(Path root) {
        PackProbe.Analysis resources = PackProbe.analyze(root.resolve("m7_5/resources"));
        PackConfig.PackConfigData base = resources.config();
        Map<Integer, Integer> formats = new TreeMap<>(base.colortexFormats());
        formats.put(4, -1);
        PackConfig.PackConfigData unsupportedFormat = new PackConfig.PackConfigData(
                formats, base.drawBufferCount(), base.shadowSettings(), base.deviations(), base.settings(),
                base.targetSettings(), base.flips(), base.preFlips(), base.paddedAlphaTargets(), base.directives());
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                resources.plan().programs(), unsupportedFormat, resources.plan().resources(),
                1920, 1080, 8, 16384);
        TargetStep dependent = graph.step("composite");
        TargetStep unrelated = graph.step("final");
        assertTrue(dependent != null && !dependent.executable()
                        && dependent.deviations().contains("POST_TARGET_FORMAT_DEVICE_UNSUPPORTED:4"),
                "unsupported target format did not reject only its dependent stage");
        assertTrue(unrelated != null && unrelated.executable(),
                "unsupported target format rejected an unrelated stage");

        verifyMipmapDirectives(root);
    }

    /** Iris ProgramDirectives: per program, in source order, legacy names included. */
    private static void verifyMipmapDirectives(Path root) {
        PostTargetPlan directives = PostTargetPlan.parse("composite", """
                const bool colortex0MipmapEnabled = true;
                const bool gaux2MipmapEnabled = true;
                const bool colortex3MipmapEnabled = true;
                const bool colortex3MipmapEnabled = false;
                const bool colortex16MipmapEnabled = true;
                // const bool colortex7MipmapEnabled = true;
                /* RENDERTARGETS: 0 */
                """).plan();
        assertTrue(directives.mipmappedTargets().equals(List.of(0, 5)),
                "mipmap directives: " + directives.mipmappedTargets());

        PackProbe.Analysis targetGraph = PackProbe.analyze(root.resolve("m7_4/target_graph"));
        String source = readFixture(
                root.resolve("m7_4/target_graph/shaders/composite1.fsh"));
        PostTargetPlan mipmapped = PostTargetPlan.parse("composite1", source.replaceFirst("\n",
                "\nconst bool colortex0MipmapEnabled = true;\nconst bool colortex9MipmapEnabled = true;\n"),
                targetGraph.config().colortexFormats()).plan();
        List<PackProgramPlan> programs = targetGraph.plan().programs().stream()
                .map(plan -> plan.name().equals("composite1") ? withTargetPlan(plan, mipmapped) : plan)
                .toList();
        PackTargetGraphPlan mipGraph = PackTargetGraphPlan.build(
                programs, targetGraph.config(), targetGraph.plan().resources(), 1920, 1080, 8, 16384);
        TargetStep mipStep = mipGraph.step("composite1");
        assertTrue(mipStep != null && mipStep.executable()
                        && mipStep.mipmapTargets().equals(List.of(0)),
                "mipmap step must regenerate allocated colortex0 only: " + mipStep);
        TargetSpec scene = mipGraph.targets().stream().filter(spec -> spec.index() == 0).findFirst().orElseThrow();
        assertTrue(scene.mipmapped() && scene.mipLevels() == 11,
                "mipmapped colortex0 needs a full 1920x1080 chain");
        assertTrue(mipGraph.targets().stream().noneMatch(spec -> spec.index() != 0 && spec.mipmapped()),
                "only directed targets carry a mip chain");
        assertTrue(mipGraph.deviations().stream().noneMatch(value -> value.contains("MIPMAP")),
                "mipmapping must not report a fallback");
        assertTrue(!mipGraph.fingerprint().equals(PackTargetGraphPlan.build(targetGraph.plan().programs(),
                        targetGraph.config(), targetGraph.plan().resources(), 1920, 1080, 8, 16384).fingerprint()),
                "mip schedule must change the graph fingerprint");
    }

    private static PackProgramPlan withTargetPlan(PackProgramPlan plan, PostTargetPlan targetPlan) {
        return new PackProgramPlan(plan.program(), plan.stages(), plan.interfacePlan(),
                plan.stageInterfaces(), plan.varyingLocations(), targetPlan, plan.convertedFragment(),
                plan.convertedVertex(), plan.vertexLayout(), plan.deviations(), plan.executable(),
                plan.familyAdapter(), plan.terrainMaterial(), plan.geometryOutputPlan(),
                plan.alphaTestPlan(), plan.blendPlan(), plan.shadowAttachmentFormats(), plan.convertedGeometry());
    }

    private static String readFixture(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static PackProgramPlan require(PackPlan plan, String name) {
        PackProgramPlan value = plan.program(name);
        if (value == null) {
            throw new AssertionError("missing program: " + name);
        }
        return value;
    }
}
