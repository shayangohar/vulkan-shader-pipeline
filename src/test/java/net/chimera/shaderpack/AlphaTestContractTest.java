package net.chimera.shaderpack;

import net.chimera.testing.InstanceOptions;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The terrain alpha-test contract: how each family's alpha rejection is planned and converted. */
final class AlphaTestContractTest {
    // The terrain plans depend on the CHIMERA instance's options, as at runtime.
    @BeforeAll
    static void instanceOptions() {
        InstanceOptions.ROUTES.apply();
    }

    @AfterAll
    static void clearInstanceOptions() {
        InstanceOptions.ROUTES.clear();
    }

    @Test
    @Tag("packs")
    void complementaryTerrain() {
        verifyRealPackTerrain(TestPacks.complementary(), "Complementary");
    }

    @Test
    @Tag("packs")
    void bslTerrain() {
        verifyRealPackTerrain(TestPacks.bsl(), "BSL");
    }

    /**
     * Entity programs test against the host render type's threshold per draw
     * (Iris iris_currentAlphaTest); a pack alphaTest directive fixes it. The
     * injected source must compile, and an output-less fragment stays authored.
     */
    @Test
    void entityAlphaTest() {
        PackAlphaTestPlan dynamic = PackAlphaTestPlan.forProgram("gbuffers_entities", settings(Map.of()));
        assertTrue(dynamic.mode() == PackAlphaTestPlan.Mode.DYNAMIC_ENTITY && dynamic.active(),
                "entity default is not the dynamic host threshold");
        String legacy = "#version 120\nuniform sampler2D texture;\nvarying vec2 uv;\n"
                + "void main() {\n    gl_FragData[0] = texture2D(texture, uv);\n}\n";
        String injected = dynamic.injectDrawTest(legacy);
        assertTrue(injected.startsWith("#version 120\nuniform float " + UniformRegistry.ENTITY_ALPHA_REFERENCE + ";\n"),
                "entity reference uniform is not declared after the version line");
        assertTrue(injected.contains("if (" + UniformRegistry.ENTITY_ALPHA_REFERENCE + " > 0.0 && !(gl_FragData[0].a > "
                        + UniformRegistry.ENTITY_ALPHA_REFERENCE + "))"),
                "entity test does not follow Iris's GREATER reference");

        String modern = "#version 460\nlayout(location = 0) out vec4 albedo;\n"
                + "void main() { albedo = vec4(1.0, 0.0, 0.0, 0.05); }\n";
        PackAlphaTestPlan fixed = PackAlphaTestPlan.forProgram("gbuffers_entities",
                settings(Map.of("alphaTest.gbuffers_entities", "GREATER 0.5")));
        String fixedSource = fixed.injectDrawTest(modern);
        assertTrue(fixed.mode() == PackAlphaTestPlan.Mode.FIXED && fixedSource.contains("albedo.a > 0.5")
                        && !fixedSource.contains(UniformRegistry.ENTITY_ALPHA_REFERENCE),
                "pack alphaTest directive does not fix the entity test");
        compileFragment(fixedSource);
        compileFragment(dynamic.injectDrawTest(modern).replace(
                "uniform float " + UniformRegistry.ENTITY_ALPHA_REFERENCE + ";",
                "layout(binding = 0) uniform Ref { float " + UniformRegistry.ENTITY_ALPHA_REFERENCE + "; };"));

        String noOutput = "#version 120\nvoid main() {}\n";
        assertTrue(dynamic.injectDrawTest(noOutput).equals(noOutput), "output-less entity fragment was changed");
        assertTrue(PackAlphaTestPlan.forProgram("gbuffers_entities", settings(Map.of(
                        "alphaTest.gbuffers_entities", "off"))).injectDrawTest(legacy).equals(legacy),
                "alphaTest off still injected an entity test");

        // The shadow program tests against the per-layer reference the shadow
        // pass sets (0.1 solid/cutout, 0 translucent); alphaTest.shadow fixes it.
        PackAlphaTestPlan shadow = PackAlphaTestPlan.forProgram("shadow", settings(Map.of()));
        assertTrue(shadow.mode() == PackAlphaTestPlan.Mode.DYNAMIC_SHADOW && shadow.active()
                        && shadow.injectDrawTest(legacy).contains("if (" + UniformRegistry.ENTITY_ALPHA_REFERENCE
                        + " > 0.0 && !(gl_FragData[0].a > "),
                "shadow default is not the per-layer reference test");
        assertTrue(PackAlphaTestPlan.forProgram("shadow", settings(Map.of("alphaTest.shadow", "GREATER 0.5")))
                        .injectDrawTest(legacy).contains("gl_FragData[0].a > 0.5"),
                "alphaTest.shadow does not fix the shadow test");
    }

    private static void compileFragment(String source) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                Shaderc.shaderc_glsl_fragment_shader, "entity_alpha.fsh", "main", 0L);
        try {
            assertTrue(result != 0 && Shaderc.shaderc_result_get_compilation_status(result)
                            == Shaderc.shaderc_compilation_status_success,
                    result == 0 ? "no shader result" : Shaderc.shaderc_result_get_error_message(result));
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    @Test
    void settings() {
        PackAlphaTestPlan dynamic = PackAlphaTestPlan.forProgram(
                "gbuffers_terrain", settings(Map.of()));
        assertTrue(dynamic.mode() == PackAlphaTestPlan.Mode.DYNAMIC_TERRAIN,
                "terrain default is not dynamic");
        assertTrue(dynamic.active() && dynamic.needsHostThreshold(),
                "terrain default is not active");

        for (String function : List.of("NEVER", "LESS", "EQUAL", "LEQUAL",
                "GREATER", "NOTEQUAL", "GEQUAL", "ALWAYS")) {
            PackAlphaTestPlan plan = PackAlphaTestPlan.forProgram(
                    "gbuffers_terrain", settings(Map.of("alphaTest.gbuffers_terrain",
                            "GL_" + function + " 0.5")));
            if (function.equals("ALWAYS") || function.equals("NEVER")) {
                plan = PackAlphaTestPlan.forProgram("gbuffers_terrain",
                        settings(Map.of("alphaTest.gbuffers_terrain", "GL_" + function)));
            }
            assertTrue(plan.valid() && plan.mode() == PackAlphaTestPlan.Mode.FIXED,
                    "fixed alpha function rejected: " + function);
            assertTrue(plan.function().equals(function), "fixed alpha function changed: " + function);
        }

        PackAlphaTestPlan off = PackAlphaTestPlan.forProgram(
                "gbuffers_terrain", settings(Map.of("alphaTest.gbuffers_terrain", "off")));
        assertTrue(off.mode() == PackAlphaTestPlan.Mode.OFF && off.valid(), "off was not accepted");
        PackAlphaTestPlan falseValue = PackAlphaTestPlan.forProgram(
                "gbuffers_terrain", settings(Map.of("alphaTest.gbuffers_terrain", "false")));
        assertTrue(falseValue.mode() == PackAlphaTestPlan.Mode.OFF && falseValue.valid(),
                "false was not accepted");
        PackAlphaTestPlan malformed = PackAlphaTestPlan.forProgram(
                "gbuffers_terrain", settings(Map.of("alphaTest.gbuffers_terrain", "LESS")));
        assertTrue(!malformed.valid() && malformed.deviations().get(0)
                        .equals("ALPHA_TEST_MALFORMED:gbuffers_terrain:LESS"),
                "malformed alpha setting was accepted");
        PackAlphaTestPlan water = PackAlphaTestPlan.forProgram(
                "gbuffers_water", settings(Map.of("alphaTest.gbuffers_water", "GREATER 0.5")));
        assertTrue(water.active(), "water alpha setting was not parsed");
        assertTrue(PackAlphaTestPlan.forProgram("composite", settings(Map.of(
                "alphaTest.composite", "GREATER 0.5"))).mode() == PackAlphaTestPlan.Mode.OFF,
                "non-geometry alpha setting was applied");
    }

    @Test
    void mainWrapper() {
        String source = """
                #version 460
                void chimeraAuthoredMain() { }
                void chimeraGeneratedMain() { }
                // void main() { discard; }
                void helper() { }
                layout(location = 0) out vec4 fragColor;
                void main() {
                    helper();
                    fragColor = vec4(1.0);
                }
                """;
        String wrapped = GlslTokenRewriter.appendMainEpilogue(source, "discard;");
        assertTrue(wrapped.contains("void chimeraAuthoredMain_()"),
                "authored main name was not collision-safe");
        assertTrue(wrapped.contains("void chimeraGeneratedMain_()"),
                "generated main name was not collision-safe");
        assertTrue(wrapped.lastIndexOf("discard;") > wrapped.lastIndexOf("chimeraAuthoredMain_();"),
                "main epilogue was not appended after authored main");
        expectFailure(() -> GlslTokenRewriter.appendMainEpilogue(
                "void helper() {}", "discard;"), "missing main was accepted");
        expectFailure(() -> GlslTokenRewriter.appendMainEpilogue(
                "void main() {} void main() {}", "discard;"),
                "ambiguous main was accepted");
    }

    @Test
    void geometryOutputNames() {
        GeometryOutputPlan single = GeometryOutputPlan.parse(
                "gbuffers_terrain", "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }", Map.of());
        assertTrue(single.locationZeroOutputName().equals("fragColor"),
                "single output name changed");
        GeometryOutputPlan mrt = GeometryOutputPlan.parse(
                "gbuffers_terrain", "#version 120\n#define DRAWBUFFERS0123\n"
                        + "void main() { gl_FragData[0] = vec4(1.0); }", Map.of());
        assertTrue(mrt.locationZeroOutputName().equals("chimeraFragColor0"),
                "MRT output name changed");
    }

    @Test
    void convertedTerrain() {
        Path fixture = Path.of("testpacks/m5_2/material");
        PackProbe.Analysis analysis = PackProbe.analyze(fixture);
        PackProgramPlan terrain = analysis.plan().program("gbuffers_terrain");
        assertTrue(terrain != null && terrain.executable(), "material terrain is not executable");
        assertTrue(terrain.alphaTestPlan().mode() == PackAlphaTestPlan.Mode.DYNAMIC_TERRAIN,
                "material terrain alpha plan is not dynamic");
        String fragment = terrain.convertedFragment();
        assertTrue(fragment != null && fragment.contains("AlphaCutout"),
                "converted terrain does not read the host alpha threshold");
        assertTrue(fragment.contains("discard;"), "converted terrain has no fragment termination");
        int authored = fragment.lastIndexOf("chimeraAuthoredMain");
        int rejection = fragment.lastIndexOf("AlphaCutout");
        assertTrue(authored >= 0 && rejection > authored,
                "alpha test was not appended after the authored main");
        compile(fragment);
        String covered = FragmentConverter.withCoverageOutput(fragment);
        assertTrue(covered.indexOf("discard;") < covered.lastIndexOf("chimeraCoverage ="),
                "coverage was written before alpha rejection");
    }

    @Test
    void fixedTerrain() {
        Path fixture = Path.of("testpacks/m5_2/material");
        try (PackSource.LoadResult loaded = PackSource.loadResult(fixture)) {
            PackSettingsPlan settings = settings(Map.of(
                    "alphaTest.gbuffers_terrain", "GL_GREATER 0.5"));
            PackConfig.PackConfigData config = PackConfig.parse(
                    loaded.programs(), loaded.shadersDir(), settings);
            PackProgramPlan terrain = PackPlanBuilder.build(loaded.programs(), config,
                    PackEntityIdResolver.empty(), PackResolutionPlan.empty())
                    .program("gbuffers_terrain");
            assertTrue(terrain != null && terrain.executable(),
                    "fixed alpha terrain conversion was rejected");
            assertTrue(terrain.deviations().contains(
                            "ALPHA_TEST_APPLIED:gbuffers_terrain:GREATER:0.5"),
                    "fixed alpha setting was not reported as applied");
            assertTrue(terrain.convertedFragment().contains("!(fragColor.a > 0.5)"),
                    "fixed alpha comparison was not emitted; plan=" + terrain.alphaTestPlan()
                            + ": " + terrain.convertedFragment());
        } catch (Exception failure) {
            throw new AssertionError("fixed alpha test failed", failure);
        }
    }

    @Test
    void waterRemainsUninstalled() {
        Path fixture = Path.of("testpacks/m5_5/unsupported_water");
        try (PackSource.LoadResult loaded = PackSource.loadResult(fixture)) {
            PackSettingsPlan settings = settings(Map.of(
                    "alphaTest.gbuffers_water", "GREATER 0.5"));
            PackConfig.PackConfigData config = PackConfig.parse(
                    loaded.programs(), loaded.shadersDir(), settings);
            PackPlan plan = PackPlanBuilder.build(loaded.programs(), config,
                    PackEntityIdResolver.empty(), PackResolutionPlan.empty());
            PackProgramPlan water = plan.program("gbuffers_water");
            assertTrue(water != null && water.alphaTestPlan().active(),
                    "water alpha setting was not retained in the plan");
            assertTrue(!water.executable(), "unsupported water became executable");
            assertTrue(water.deviations().contains(
                            "ALPHA_TEST_PLANNED_NOT_INSTALLED:gbuffers_water"),
                    "water alpha setting was reported as applied");
        } catch (Exception failure) {
            throw new AssertionError("water fallback test failed", failure);
        }
    }

    private static void verifyRealPackTerrain(Path pack, String label) {
        PackProbe.Analysis analysis = PackProbe.analyze(pack);
        PackProgramPlan terrain = analysis.plan().program("gbuffers_terrain");
        assertTrue(terrain != null && terrain.executable(),
                label + " terrain is not conversion-eligible: "
                        + (terrain == null ? "missing" : terrain.deviations()));
        assertTrue(terrain.alphaTestPlan().mode() == PackAlphaTestPlan.Mode.DYNAMIC_TERRAIN,
                label + " terrain alpha plan is not dynamic");
        assertTrue(terrain.convertedFragment() != null
                        && terrain.convertedFragment().contains("AlphaCutout")
                        && terrain.convertedFragment().contains("discard;"),
                label + " terrain has no generated alpha rejection");
    }

    /**
     * BSL material terrain must survive the full path that crashed the
     * game session: MATERIAL_FORMAT=1 activates explicit-gradient atlas
     * sampling, which previously reached shaderc untranslated.
     */
    @Test
    @Tag("packs")
    void bslMaterialTerrain() {
        String previousFormat = System.getProperty("chimera.option.MATERIAL_FORMAT");
        String previousAdvanced = System.getProperty("chimera.option.ADVANCED_MATERIALS");
        System.setProperty("chimera.option.MATERIAL_FORMAT", "1");
        System.setProperty("chimera.option.ADVANCED_MATERIALS", "1");
        try {
            PackProbe.Analysis analysis = PackProbe.analyze(TestPacks.bsl());
            PackProgramPlan terrain = analysis.plan().program("gbuffers_terrain");
            assertTrue(terrain != null && terrain.executable(),
                    "BSL material terrain is not conversion-eligible: "
                            + (terrain == null ? "missing" : terrain.deviations()));
            assertTrue(terrain.convertedFragment() != null
                            && !terrain.convertedFragment().contains("texture2DGradARB"),
                    "BSL material terrain kept untranslated explicit-gradient sampling");
            assertTrue(terrain.convertedFragment().contains("textureGrad("),
                    "BSL material terrain lost its explicit gradients");
            String bslFragment = terrain.convertedFragment();
            Matcher gradPrefix = Pattern.compile(
                            "(\\w*Atlas\\w*)SampleGrad\\(chimeraTexture, newCoord, dcdx, dcdy\\)")
                    .matcher(bslFragment);
            assertTrue(gradPrefix.find(),
                    "BSL parallax overwrite missed the explicit-gradient atlas helper");
            assertTrue(!bslFragment.contains("textureGrad(chimeraTexture, newCoord, dcdx, dcdy)"),
                    "raw explicit-gradient albedo sample remains outside the helper");
            // Stitched material companions share the atlas topology, so their explicit-gradient reads
            // take the same atlas helper as albedo.
            assertTrue(bslFragment.contains("chimeraAtlasSampleGrad(normals,")
                            && !bslFragment.contains("textureGrad(normals,"),
                    "material normal explicit-gradient reads left the atlas helper");
            Set<Integer> bslSlots = new HashSet<>();
            for (UniformRegistry.SamplerBinding binding : terrain.interfacePlan().samplers()) {
                bslSlots.add(binding.slot());
            }
            assertTrue(bslSlots.contains(25) && bslSlots.contains(26),
                    "BSL material selectors 25/26 missing: " + bslSlots);
            compile(terrain.convertedFragment());
            PackConfig.TargetSettings bslTarget2Contract = analysis.config()
                    .targetSettings().get(2);
            assertTrue(bslTarget2Contract != null && !bslTarget2Contract.clear(),
                    "BSL colortex2Clear=false declaration was not parsed from the pack source");
            PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                    analysis.plan().programs(), analysis.config(), 2560, 1440, 8, 16384);
            assertTrue(graph.target(2) != null && graph.target(2).persistent(),
                    "BSL temporal target lost declaration-driven cross-frame persistence");
            assertTrue(graph.target(0) != null && !graph.target(0).persistent(),
                    "BSL geometry target must not persist across frames");
        } finally {
            restoreProperty("chimera.option.MATERIAL_FORMAT", previousFormat);
            restoreProperty("chimera.option.ADVANCED_MATERIALS", previousAdvanced);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private static PackSettingsPlan settings(Map<String, String> properties) {
        return new PackSettingsPlan(Map.of(), Map.of(), Map.of(), properties, Map.of(),
                Set.of(), Set.of(), Set.of(), List.of(), "default",
                PackRuntimeSettings.empty(), Map.of(), Map.of(), false);
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void compile(String source) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                Shaderc.shaderc_glsl_fragment_shader, "terrain_alpha.fsh", "main", 0);
        try {
            assertTrue(result != 0 && Shaderc.shaderc_result_get_compilation_status(result)
                            == Shaderc.shaderc_compilation_status_success,
                    result == 0 ? "shaderc returned no result"
                            : Shaderc.shaderc_result_get_error_message(result));
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
