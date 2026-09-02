package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.chimera.render.PackPostTargetsHarness;

/**
 * Small dependency-free conformance check for the locked M5.1 through M5.7 fixtures
 * and the M5.7 post-target availability boundary.
 * Gradle runs this class before a normal build.
 */
public final class ConformanceHarness {
    private static final List<String> REQUIRED_PROGRAMS = List.of(
            "gbuffers_terrain", "composite", "final");

    private ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        PackPostTargetsHarness.run();
        assertEquals(List.of(0, 2, 3), PackPipelines.colorInputTargets(
                        List.of("colortex3", "depthtex0", "colortex0", "colortex2")),
                "post color input ordering");
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        verifySimplex(fixtureRoot.resolve("simplex"), fixtureRoot.resolve("baselines/simplex.json"));
        verifySupported(fixtureRoot.resolve("m5_1/optifine"), "optifine");
        verifySupported(fixtureRoot.resolve("m5_1/iris"), "iris");
        verifyUnsupported(fixtureRoot.resolve("m5_1/unsupported"));
        verifyM52Material(fixtureRoot.resolve("m5_2/material"), fixtureRoot.resolve("baselines/m5_2.json"));
        verifyM52Unsupported(fixtureRoot.resolve("m5_2/unsupported_vertex"), fixtureRoot.resolve("baselines/m5_2.json"));
        verifyM53LiveUniforms(fixtureRoot.resolve("m5_3/live_uniforms"), fixtureRoot.resolve("baselines/m5_3.json"));
        verifyM53Unsupported(fixtureRoot.resolve("m5_3/unsupported_uniform"), fixtureRoot.resolve("baselines/m5_3.json"));
        verifyM54Shadow(fixtureRoot.resolve("m5_4/shadow"), fixtureRoot.resolve("baselines/m5_4.json"));
        verifyM54Unsupported(fixtureRoot.resolve("m5_4/unsupported_shadow"), fixtureRoot.resolve("baselines/m5_4.json"));
        verifyM55Water(fixtureRoot.resolve("m5_5/water"), fixtureRoot.resolve("baselines/m5_5.json"));
        verifyM55Unsupported(fixtureRoot.resolve("m5_5/unsupported_water"), fixtureRoot.resolve("baselines/m5_5.json"));
        verifyM56PostChain(fixtureRoot.resolve("m5_6/post_chain"), fixtureRoot.resolve("baselines/m5_6.json"));
        verifyM56Unsupported(fixtureRoot.resolve("m5_6/unsupported_targets"),
                fixtureRoot.resolve("baselines/m5_6.json"));
        System.out.println("[chimera] M5.1 through M5.7 conformance harness: PASS");
    }

    private static void verifySimplex(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        verifyRequiredPrograms(report, "simplex");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                report.program("gbuffers_terrain").support(), "simplex terrain support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                report.program("composite").support(), "simplex composite support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                report.program("final").support(), "simplex final support");
        verifyBaseline(report, baselinePath);
    }

    private static void verifySupported(Path pack, String label) {
        ConformanceReport report = probe(pack);
        verifyRequiredPrograms(report, label);
        for (String name : REQUIRED_PROGRAMS) {
            ConformanceReport.ProgramReport program = report.program(name);
            assertTrue(program.dialect().equals("LEGACY_GLSL"),
                    label + " must remain in the legacy converter dialect");
            assertTrue(program.support() == ConformanceReport.SupportStatus.SUPPORTED
                            || program.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                    label + " program must be executable: " + name);
            assertTrue(report.shouldAttempt(name), label + " program was rejected: " + name);
            assertTrue(program.targets().equals(List.of(0)),
                    label + " must use the single colortex0 target: " + name);
            assertEquals(ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED,
                    program.runtime(), label + " static probe runtime state: " + name);
        }
        assertEquals(List.of("SHADOW_SETTING_LOGGED_ONLY:shadowMapResolution"),
                report.deviations(), label + " deviations");
        assertEquals(List.of(), report.program("gbuffers_terrain").deviations(),
                label + " terrain deviations");
        assertEquals(List.of("FIXED_VERTEX_SUBSTITUTION"),
                report.program("composite").deviations(), label + " composite deviations");
        assertEquals(List.of("FIXED_VERTEX_SUBSTITUTION"),
                report.program("final").deviations(), label + " final deviations");
        assertStable(report, label);
    }

    private static void verifyUnsupported(Path pack) {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport terrain = report.program("gbuffers_terrain");
        ConformanceReport.ProgramReport numbered = report.program("composite1");
        ConformanceReport.ProgramReport compute = report.program("setup");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                terrain.support(), "unsupported MRT terrain support");
        assertTrue(terrain.deviations().contains("MRT_NOT_SUPPORTED"),
                "unsupported MRT deviation");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                numbered.support(), "numbered post pass support");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED,
                compute.support(), "compute stage support");
        assertTrue(compute.deviations().contains("UNSUPPORTED_PACK_STAGE"),
                "compute stage deviation");
        assertTrue(report.deviations().contains("SETTING_NOT_APPLIED:customImage0"),
                "custom image deviation");
        assertTrue(report.deviations().contains("SETTING_NOT_APPLIED:iris.features.required"),
                "feature flag deviation");
        assertTrue(!report.shouldAttempt("gbuffers_terrain"), "MRT terrain must not execute");
        assertTrue(report.shouldAttempt("composite1"), "numbered post pass must execute");
        assertEquals(List.of(
                        "SETTING_NOT_APPLIED:customImage0",
                        "SETTING_NOT_APPLIED:iris.features.required"),
                report.deviations(), "unsupported deviations");
        assertStable(report, "unsupported");
    }

    private static void verifyM52Material(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        verifyRequiredPrograms(report, "m5.2 material");
        ConformanceReport.ProgramReport terrain = report.program("gbuffers_terrain");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                terrain.support(), "m5.2 material terrain support");
        assertEquals(List.of("fragment", "vertex"), terrain.stages(), "m5.2 material terrain stages");
        assertEquals(List.of("LEGACY_TERRAIN_VERTEX_BRIDGE"), terrain.deviations(),
                "m5.2 material terrain deviations");
        assertTrue(report.shouldAttempt("gbuffers_terrain"), "m5.2 material terrain was rejected");
        assertTrue(metadataHashes(report).containsKey("block.properties"),
                "m5.2 material block.properties hash is missing");

        PackMaterialResolver.ParseResult material = PackMaterialResolver.parse(pack.resolve("shaders"));
        assertTrue(material.present(), "m5.2 material properties are missing");
        assertEquals(1, material.resolver().resolveName("minecraft:stone"), "stone material id");
        assertEquals(2, material.resolver().resolveName("dirt"), "unqualified dirt material id");
        assertEquals(2, material.resolver().resolveName("minecraft:grass_block"), "grass material id");
        assertEquals(-1, material.resolver().resolveName("minecraft:diamond_block"), "unmapped material id");
        assertEquals(List.of(), material.deviations(), "m5.2 material parser deviations");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        PackProgram terrainProgram = loaded.programs().stream()
                .filter(program -> program.name().equals("gbuffers_terrain"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("m5.2 material terrain source is missing"));
        assertTrue(terrainProgram.vertexSource() != null, "m5.2 material vertex source was not retained");
        LegacyGlslConverter.TerrainVertexConversion converted = LegacyGlslConverter.convertTerrainVertex(
                terrainProgram.vertexSource(), terrainProgram.vertexPath(), terrainProgram.fragmentSource());
        assertTrue(converted != null, "m5.2 material vertex conversion failed");
        assertTrue(converted.source().contains("layout(location = 0) out"),
                "m5.2 material varying layout was not emitted");
        assertTrue(converted.source().contains("inMaterialId"),
                "m5.2 material input attribute was not emitted");
        String convertedFragment = LegacyGlslConverter.convertFragment(
                terrainProgram.fragmentSource(), terrainProgram.fragmentPath(), true,
                new int[] {0, 2}, converted.layout());
        assertTrue(convertedFragment != null && convertedFragment.contains("layout(location = 0) in"),
                "m5.2 material fragment varying bridge failed");

        assertExtendedFormat();
        verifyMaterialParserEdgeCases();
        assertTrue(LegacyGlslConverter.convertTerrainVertex(
                "#version 330\nvoid main() { gl_Position = vec4(0.0); }", null, "") == null,
                "modern terrain vertex source was accepted");
        assertTrue(LegacyGlslConverter.convertTerrainVertex(
                "#version 120\nvoid main() { gl_Position = ftransform(); float n = gl_Normal.x; }",
                null, "") == null,
                "unsupported terrain normal was accepted");
        verifyM52Baseline(report, baselinePath, "material");
    }

    private static void verifyM52Unsupported(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport terrain = report.program("gbuffers_terrain");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                terrain.support(), "m5.2 unsupported vertex support");
        assertTrue(terrain.deviations().contains("TERRAIN_VERTEX_BRIDGE_UNSUPPORTED"),
                "m5.2 unsupported vertex deviation");
        assertTrue(!report.shouldAttempt("gbuffers_terrain"),
                "m5.2 unsupported vertex must not execute");
        assertTrue(metadataHashes(report).containsKey("block.properties"),
                "m5.2 unsupported block.properties hash is missing");
        verifyM52Baseline(report, baselinePath, "unsupported_vertex");
    }

    private static void verifyM53LiveUniforms(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        verifyRequiredPrograms(report, "m5.3 live uniforms");
        ConformanceReport.ProgramReport composite = report.program("composite");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                composite.support(), "m5.3 live composite support");
        assertTrue(report.shouldAttempt("composite"), "m5.3 live composite was rejected");
        assertEquals(List.of("colortex0", "depthtex0"), composite.samplers(),
                "m5.3 live composite samplers");
        assertEquals(List.of(
                        "cameraPosition", "colortex0", "depthtex0", "fogColor",
                        "frameTimeCounter", "sunPosition", "viewHeight", "viewWidth",
                        "wetness", "worldTime"), composite.uniforms(),
                "m5.3 live composite declarations");
        assertEquals(List.of(
                        "DEPTH_INPUT_FIXED_TO_HDR",
                        "LIVE_UNIFORM_BRIDGE",
                        "UNIFORM_DEFAULTED:wetness"), composite.deviations(),
                "m5.3 live composite deviations");
        assertEquals(List.of(), report.deviations(), "m5.3 live global deviations");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                Files.readString(pack.resolve("shaders/composite.fsh"), StandardCharsets.UTF_8),
                UniformRegistry.Stage.POST);
        assertEquals(List.of(
                        new UniformRegistry.UniformDeclaration("cameraPosition", "vec3"),
                        new UniformRegistry.UniformDeclaration("fogColor", "vec4"),
                        new UniformRegistry.UniformDeclaration("frameTimeCounter", "float"),
                        new UniformRegistry.UniformDeclaration("sunPosition", "vec3"),
                        new UniformRegistry.UniformDeclaration("viewHeight", "float"),
                        new UniformRegistry.UniformDeclaration("viewWidth", "float"),
                        new UniformRegistry.UniformDeclaration("wetness", "float"),
                        new UniformRegistry.UniformDeclaration("worldTime", "int")),
                interfacePlan.uniforms(), "m5.3 uniform ordering");
        assertEquals(List.of(
                        new UniformRegistry.SamplerBinding("colortex0", 0),
                        new UniformRegistry.SamplerBinding("depthtex0", 6)),
                interfacePlan.samplers(), "m5.3 sampler ordering");
        assertTrue(interfacePlan.executable(), "m5.3 live interface is not executable");
        assertEquals("float", UniformRegistry.pipelineType("vec3"), "m5.3 vec3 pipeline type");
        assertEquals(3, UniformRegistry.pipelineCount("vec3"), "m5.3 vec3 pipeline count");
        assertEquals("int", UniformRegistry.pipelineType("int"), "m5.3 int pipeline type");
        assertEquals(16, UniformRegistry.pipelineCount("mat4"), "m5.3 mat4 pipeline count");
        verifyM53InterfaceEdgeCases();

        PackProgram compositeProgram = PackSource.loadResult(pack).programs().stream()
                .filter(program -> program.name().equals("composite"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("m5.3 live composite source is missing"));
        String converted = LegacyGlslConverter.convertFragment(
                compositeProgram.fragmentSource(), compositeProgram.fragmentPath(), false, null, null, interfacePlan);
        assertTrue(converted != null, "m5.3 live composite conversion failed");
        assertTrue(converted.contains("layout(binding = 0) uniform ChimeraPackUniforms"),
                "m5.3 generated UBO is missing");
        assertTrue(converted.contains("layout(binding = 1) uniform sampler2D colortex0"),
                "m5.3 colortex0 binding is not after the UBO");
        assertTrue(converted.contains("layout(binding = 2) uniform sampler2D depthtex0"),
                "m5.3 depthtex0 binding is not after the UBO");
        assertTrue(!converted.contains("uniform vec3 cameraPosition"),
                "m5.3 source uniform was not removed");
        verifyM53Baseline(report, baselinePath, "live_uniforms");
    }

    private static void verifyM53InterfaceEdgeCases() {
        UniformRegistry.ProgramInterface array = UniformRegistry.plan(
                "uniform float wetness[2];", UniformRegistry.Stage.POST);
        assertTrue(!array.executable(), "m5.3 uniform arrays were accepted");
        assertTrue(array.deviations().contains("UNIFORM_TYPE_UNSUPPORTED:wetness"),
                "m5.3 array deviation is missing");

        UniformRegistry.ProgramInterface struct = UniformRegistry.plan(
                "uniform PackValues { float wetness; };", UniformRegistry.Stage.POST);
        assertTrue(!struct.executable(), "m5.3 uniform blocks were accepted");
        assertTrue(struct.deviations().contains("UNIFORM_TYPE_UNSUPPORTED:PackValues"),
                "m5.3 uniform block deviation is missing");

        UniformRegistry.ProgramInterface unknown = UniformRegistry.plan(
                "uniform float arbitraryValue;", UniformRegistry.Stage.POST);
        assertTrue(!unknown.executable(), "m5.3 unknown uniform was accepted");
        assertTrue(unknown.deviations().contains("UNIFORM_NAME_UNSUPPORTED:arbitraryValue"),
                "m5.3 unknown uniform deviation is missing");

        UniformRegistry.ProgramInterface conflict = UniformRegistry.plan(
                "uniform float wetness; uniform int wetness;", UniformRegistry.Stage.POST);
        assertTrue(!conflict.executable(), "m5.3 conflicting uniform declarations were accepted");
        assertTrue(conflict.deviations().contains("UNIFORM_CONFLICT:wetness"),
                "m5.3 conflicting uniform deviation is missing");

        UniformRegistry.ProgramInterface alias = UniformRegistry.plan(
                "uniform sampler2D colortex1;", UniformRegistry.Stage.POST);
        assertTrue(alias.hasSampler("colortex1"), "m5.3 colortex1 alias was not mapped");
        assertTrue(alias.deviations().contains("COLORTEX_ALIAS_TO_SEAM"),
                "m5.3 colortex alias deviation is missing");

        UniformRegistry.ProgramInterface slotConflict = UniformRegistry.plan(
                "uniform sampler2D colortex3; uniform sampler2D shadowcolor0;",
                UniformRegistry.Stage.POST);
        assertTrue(!slotConflict.executable(), "m5.7 conflicting slot aliases were accepted");
        assertTrue(slotConflict.deviations().contains("SAMPLER_SLOT_CONFLICT:3"),
                "m5.7 conflicting slot alias deviation is missing");
    }

    private static void verifyM53Unsupported(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport composite = report.program("composite");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                composite.support(), "m5.3 unsupported composite support");
        assertTrue(composite.deviations().contains("SAMPLER_NOT_MAPPED:unknownTexture"),
                "m5.3 unknown sampler deviation is missing");
        assertTrue(composite.deviations().contains("UNIFORM_TYPE_UNSUPPORTED:unsupportedToggle"),
                "m5.3 unsupported uniform type deviation is missing");
        assertTrue(!report.shouldAttempt("composite"), "m5.3 unsupported composite must not execute");
        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                Files.readString(pack.resolve("shaders/composite.fsh"), StandardCharsets.UTF_8),
                UniformRegistry.Stage.POST);
        assertTrue(!interfacePlan.executable(), "m5.3 unsupported interface was accepted");
        assertTrue(LegacyGlslConverter.convertFragment(
                Files.readString(pack.resolve("shaders/composite.fsh"), StandardCharsets.UTF_8),
                pack.resolve("shaders/composite.fsh"), false, null) == null,
                "m5.3 unsupported source converted");
        verifyM53Baseline(report, baselinePath, "unsupported_uniform");
    }

    private static void verifyM56PostChain(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        assertEquals(List.of("composite", "final"), report.passInventory(),
                "m5.6 post pass inventory must preserve shaders.json order");
        for (String name : List.of("deferred", "composite", "composite1", "final")) {
            ConformanceReport.ProgramReport program = report.program(name);
            assertTrue(program != null, "m5.6 post program was not discovered: " + name);
            assertEquals(List.of("fragment"), program.stages(),
                    "m5.6 post stages: " + name);
            assertEquals(ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED,
                    program.runtime(), "m5.6 static post runtime: " + name);
            assertTrue(report.shouldAttempt(name), "m5.6 post program was rejected: " + name);
        }
        assertEquals(List.of(0, 1), report.program("deferred").targets(),
                "m5.6 deferred targets");
        assertEquals(List.of(0, 1), report.program("composite").targets(),
                "m5.6 composite targets");
        assertEquals(List.of(0), report.program("composite1").targets(),
                "m5.6 numbered composite targets");
        assertEquals(List.of(0), report.program("final").targets(),
                "m5.6 final target");
        assertEquals(List.of("MRT_POST_BRIDGE", "POST_TARGET_ROUTE_APPLIED"),
                report.program("deferred").deviations(), "m5.6 deferred deviations");
        assertEquals(List.of("MRT_POST_BRIDGE", "POST_TARGET_ROUTE_APPLIED"),
                report.program("composite").deviations(), "m5.6 composite deviations");
        assertEquals(List.of(),
                report.program("composite1").deviations(), "m5.6 numbered composite deviations");
        assertEquals(List.of(), report.program("final").deviations(), "m5.6 final deviations");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                report.program("deferred").support(), "m5.6 deferred support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                report.program("composite").support(), "m5.6 composite support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                report.program("composite1").support(), "m5.6 numbered composite support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                report.program("final").support(), "m5.6 final support");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        List<String> loadedNames = loaded.programs().stream().map(PackProgram::name)
                .distinct().sorted(PostTargetPlan.programComparator()).toList();
        assertEquals(List.of("deferred", "composite", "composite1", "final"), loadedNames,
                "m5.6 discovered post order");
        assertEquals(4L, loaded.programs().stream()
                .filter(program -> PostTargetPlan.isPostProgramName(program.name())).count(),
                "m5.6 duplicate post programs");

        String deferred = Files.readString(pack.resolve("shaders/deferred.fsh"), StandardCharsets.UTF_8);
        String composite = Files.readString(pack.resolve("shaders/composite.fsh"), StandardCharsets.UTF_8);
        PostTargetPlan deferredPlan = PostTargetPlan.parse("deferred", deferred,
                Map.of(0, 97, 1, 37)).plan();
        PostTargetPlan compositePlan = PostTargetPlan.parse("composite", composite,
                Map.of(0, 97, 1, 37)).plan();
        assertEquals(List.of(0, 1), deferredPlan.targetSlots(), "m5.6 deferred route");
        assertEquals(List.of(0, 1), compositePlan.targetSlots(), "m5.6 composite route");
        assertEquals(List.of(97, 37), compositePlan.outputFormats(), "m5.6 target formats");
        assertTrue(compositePlan.requiresMrt(), "m5.6 composite must require MRT");
        String converted = LegacyGlslConverter.convertPostFragment(
                composite, pack.resolve("shaders/composite.fsh"),
                UniformRegistry.planPost(composite, compositePlan), compositePlan);
        assertTrue(converted != null, "m5.6 multi-target conversion failed");
        assertTrue(converted.contains("layout(location = 0) out vec4 chimeraFragColor0"),
                "m5.6 output location 0 is missing");
        assertTrue(converted.contains("layout(location = 1) out vec4 chimeraFragColor1"),
                "m5.6 output location 1 is missing");
        assertTrue(!converted.contains("DRAWBUFFERS01"),
                "m5.6 DRAWBUFFERS directive was not removed");
        verifyM56TargetParserEdgeCases();
        verifyM56Baseline(report, baselinePath, "post_chain");
    }

    private static void verifyM56TargetParserEdgeCases() {
        PostTargetPlan drawBuffers = PostTargetPlan.parse(
                "composite", "#version 120\n#define DRAWBUFFERS0123\n").plan();
        assertEquals(List.of(0, 1, 2, 3), drawBuffers.targetSlots(),
                "m5.6 DRAWBUFFERS digits are target slots");

        PostTargetPlan renderTargets = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0, 2, 3 */").plan();
        assertEquals(List.of(0, 2, 3), renderTargets.targetSlots(),
                "m5.6 RENDERTARGETS route");

        PostTargetPlan malformed = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0, nope */").plan();
        assertTrue(!malformed.executable(), "m5.6 malformed target directive was accepted");
        assertTrue(malformed.deviations().contains("POST_TARGET_DIRECTIVE_MALFORMED"),
                "m5.6 malformed target deviation is missing");

        PostTargetPlan conflict = PostTargetPlan.parse(
                "composite", "#define DRAWBUFFERS01\n/* RENDERTARGETS: 0,2 */").plan();
        assertTrue(!conflict.executable(), "m5.6 conflicting target directives were accepted");
        assertTrue(conflict.deviations().contains("POST_TARGET_DIRECTIVE_CONFLICT"),
                "m5.6 target conflict deviation is missing");

        PostTargetPlan unsupported = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0,8 */").plan();
        assertTrue(!unsupported.executable(), "m5.6 unsupported target was accepted");
        assertTrue(unsupported.deviations().contains("POST_TARGET_INDEX_UNSUPPORTED:8"),
                "m5.6 unsupported target deviation is missing");

        PostTargetPlan finalMrt = PostTargetPlan.parse(
                "final", "/* RENDERTARGETS: 0,1 */ gl_FragData[0] = vec4(1.0);").plan();
        assertTrue(!finalMrt.executable(), "m5.6 final MRT was accepted");
        assertTrue(finalMrt.deviations().contains("FINAL_MRT_UNSUPPORTED"),
                "m5.6 final MRT deviation is missing");
    }

    private static void verifyM56Unsupported(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport composite = report.program("composite");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                composite.support(), "m5.6 unsupported target support");
        assertTrue(composite.deviations().contains("POST_TARGET_INDEX_UNSUPPORTED:8"),
                "m5.6 unsupported target deviation is missing");
        assertTrue(!report.shouldAttempt("composite"),
                "m5.6 unsupported target must not execute");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                report.program("final").support(), "m5.6 unsupported fixture final probe");
        verifyM56Baseline(report, baselinePath, "unsupported_targets");
    }

    private static void verifyM55Water(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        assertTrue(report.program("composite") != null, "m5.5 water is missing program composite");
        assertTrue(report.program("final") != null, "m5.5 water is missing program final");
        ConformanceReport.ProgramReport water = report.program("gbuffers_water");
        assertTrue(water != null, "m5.5 water program was not discovered");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                water.support(), "m5.5 water support");
        assertEquals(List.of("fragment", "vertex"), water.stages(), "m5.5 water stages");
        assertTrue(water.deviations().contains("TRANSLUCENT_VERTEX_BRIDGE"),
                "m5.5 water vertex bridge deviation is missing");
        assertTrue(water.deviations().contains("TRANSLUCENT_STATE_FIXED_TO_HOST"),
                "m5.5 host translucent state deviation is missing");
        assertEquals(List.of("lightmap", "shadowtex0", "texture"), water.samplers(),
                "m5.5 water sampler inventory");
        assertEquals(List.of("composite", "final"), report.passInventory(),
                "m5.5 water must be discovered outside shaders.json");
        assertTrue(report.programs().stream().filter(program -> program.name().equals("gbuffers_water")).count() == 1,
                "m5.5 water program was duplicated");
        assertPostPassThrough(pack, "m5.5 water");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        PackProgram waterProgram = loaded.programs().stream()
                .filter(program -> program.name().equals("gbuffers_water"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("m5.5 water source is missing"));
        assertTrue(waterProgram.vertexSource() != null, "m5.5 water vertex source is missing");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                waterProgram.fragmentSource(), UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(interfacePlan.executable(), "m5.5 water interface is not executable");
        assertEquals(List.of(
                        new UniformRegistry.UniformDeclaration("fogColor", "vec4"),
                        new UniformRegistry.UniformDeclaration("fogEnd", "float"),
                        new UniformRegistry.UniformDeclaration("fogStart", "float"),
                        new UniformRegistry.UniformDeclaration("texelSize", "vec2"),
                        new UniformRegistry.UniformDeclaration("textureSize", "ivec2")),
                interfacePlan.uniforms(), "m5.5 water uniform ordering");
        assertEquals(List.of(
                        new UniformRegistry.SamplerBinding("texture", 0),
                        new UniformRegistry.SamplerBinding("lightmap", 2),
                        new UniformRegistry.SamplerBinding("shadowtex0", 5)),
                interfacePlan.samplers(), "m5.5 water sampler bindings");
        assertTrue(interfacePlan.deviations().contains("TRANSLUCENT_FIXED_UNIFORM_BRIDGE"),
                "m5.5 fixed fog bridge deviation is missing");
        assertEquals("FogRenderDistanceStart",
                UniformRegistry.translucentUniformField("fogStart"),
                "m5.5 fogStart alias");
        assertEquals("TextureSize",
                UniformRegistry.translucentUniformField("textureSize"),
                "m5.5 textureSize alias");

        LegacyGlslConverter.TerrainVertexConversion vertex = LegacyGlslConverter.convertTerrainVertex(
                waterProgram.vertexSource(), waterProgram.vertexPath(), waterProgram.fragmentSource());
        assertTrue(vertex != null, "m5.5 water vertex conversion failed");
        String convertedFragment = LegacyGlslConverter.convertFragment(
                waterProgram.fragmentSource(), waterProgram.fragmentPath(), true,
                new int[] {0, 2, 5}, vertex.layout(), interfacePlan);
        assertTrue(convertedFragment != null, "m5.5 water fragment conversion failed");
        assertTrue(convertedFragment.contains("layout(binding = 1) uniform ChimeraTerrainUniforms"),
                "m5.5 terrain UBO is missing");
        assertTrue(convertedFragment.contains("FogRenderDistanceStart"),
                "m5.5 fogStart alias was not rewritten");
        assertTrue(!convertedFragment.contains("uniform float fogStart"),
                "m5.5 fogStart declaration was not removed");
        assertTrue(convertedFragment.contains("layout(binding = 3) uniform sampler2D chimeraTexture"),
                "m5.5 water atlas binding is missing");
        assertTrue(convertedFragment.contains("layout(binding = 4) uniform sampler2D lightmap"),
                "m5.5 water lightmap binding is missing");
        assertTrue(convertedFragment.contains("layout(binding = 5) uniform sampler2D shadowtex0"),
                "m5.5 water shadow binding is missing");
        assertTrue(convertedFragment.contains("layout(location = " + vertex.layout().location("renderType")
                        + ") in float renderType"),
                "m5.5 water varying location was not shared");

        assertExtendedFormat();
        verifyM55InterfaceEdgeCases();
        verifyM55Baseline(report, baselinePath, "water");
    }

    private static void verifyM55InterfaceEdgeCases() {
        UniformRegistry.ProgramInterface depth = UniformRegistry.plan(
                "uniform sampler2D texture; uniform sampler2D depthtex0;",
                UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!depth.executable(), "m5.5 depth input was accepted");
        assertTrue(depth.deviations().contains("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED"),
                "m5.5 depth input deviation is missing");

        UniformRegistry.ProgramInterface unknownSampler = UniformRegistry.plan(
                "uniform sampler2D noise;", UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!unknownSampler.executable(), "m5.5 unknown water sampler was accepted");
        assertTrue(unknownSampler.deviations().contains("TRANSLUCENT_SAMPLER_UNSUPPORTED:noise"),
                "m5.5 unknown water sampler deviation is missing");

        UniformRegistry.ProgramInterface unknownUniform = UniformRegistry.plan(
                "uniform float waterLevel;", UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!unknownUniform.executable(), "m5.5 unknown water uniform was accepted");
        assertTrue(unknownUniform.deviations().contains("UNIFORM_NAME_UNSUPPORTED:waterLevel"),
                "m5.5 unknown water uniform deviation is missing");
    }

    private static void verifyM55Unsupported(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport water = report.program("gbuffers_water");
        assertTrue(water != null, "m5.5 unsupported water program was not discovered");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                water.support(), "m5.5 unsupported water support");
        assertTrue(water.deviations().contains("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED"),
                "m5.5 unsupported water depth deviation is missing");
        assertTrue(!report.shouldAttempt("gbuffers_water"),
                "m5.5 unsupported water must not execute");

        ConformanceReport.ProgramReport entities = report.program("gbuffers_entities");
        assertTrue(entities != null, "m5.5 unsupported entity family was not inventoried");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED,
                entities.support(), "m5.5 entity family support");
        assertTrue(!report.shouldAttempt("gbuffers_entities"),
                "m5.5 entity family must not execute");
        assertPostPassThrough(pack, "m5.5 unsupported water");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                Files.readString(pack.resolve("shaders/gbuffers_water.fsh"), StandardCharsets.UTF_8),
                UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!interfacePlan.executable(), "m5.5 unsupported water interface was accepted");
        assertTrue(LegacyGlslConverter.convertFragment(
                Files.readString(pack.resolve("shaders/gbuffers_water.fsh"), StandardCharsets.UTF_8),
                pack.resolve("shaders/gbuffers_water.fsh"), true, new int[] {0, 2}, null,
                interfacePlan) == null,
                "m5.5 unsupported water source converted");
        verifyM55Baseline(report, baselinePath, "unsupported_water");
    }

    private static void assertPostPassThrough(Path pack, String label) throws IOException {
        for (String name : List.of("composite", "final")) {
            String source = Files.readString(pack.resolve("shaders/" + name + ".fsh"), StandardCharsets.UTF_8);
            assertTrue(source.contains("uniform sampler2D colortex0;"),
                    label + " " + name + " must declare colortex0");
            assertTrue(source.contains("texture2D(colortex0, texcoord)"),
                    label + " " + name + " must preserve the input image");
        }
    }

    private static void verifyM54Shadow(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport shadow = report.program("shadow");
        assertTrue(shadow != null, "m5.4 shadow program was not discovered");
        assertEquals(List.of("fragment", "vertex"), shadow.stages(), "m5.4 shadow stages");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                shadow.support(), "m5.4 shadow support");
        assertEquals(List.of("SHADOW_VERTEX_BRIDGE"), shadow.deviations(),
                "m5.4 shadow deviations");
        assertEquals(List.of("lightmap", "texture"), shadow.samplers(),
                "m5.4 shadow sampler inventory");
        assertTrue(report.passInventory().equals(List.of("composite", "final")),
                "m5.4 shadow must be discovered outside shaders.json");
        assertTrue(report.programs().stream().filter(program -> program.name().equals("shadow")).count() == 1,
                "m5.4 shadow program was duplicated");
        assertTrue(report.deviations().contains("SHADOW_SETTING_APPLIED:shadowDistance"),
                "m5.4 shadow distance application is missing");
        assertTrue(report.deviations().contains("SHADOW_SETTING_APPLIED:shadowMapResolution"),
                "m5.4 shadow resolution application is missing");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        PackProgram shadowProgram = loaded.programs().stream()
                .filter(program -> program.name().equals("shadow"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("m5.4 shadow source is missing"));
        assertTrue(shadowProgram.vertexSource() != null, "m5.4 shadow vertex source is missing");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                shadowProgram.fragmentSource(), UniformRegistry.Stage.SHADOW);
        assertEquals(List.of(
                        new UniformRegistry.SamplerBinding("texture", 0),
                        new UniformRegistry.SamplerBinding("lightmap", 2)),
                interfacePlan.samplers(), "m5.4 shadow sampler bindings");
        assertTrue(interfacePlan.executable(), "m5.4 shadow interface is not executable");

        LegacyGlslConverter.TerrainVertexConversion vertex = LegacyGlslConverter.convertShadowVertex(
                shadowProgram.vertexSource(), shadowProgram.vertexPath(), shadowProgram.fragmentSource());
        assertTrue(vertex != null, "m5.4 shadow vertex conversion failed");
        assertTrue(vertex.source().contains("layout(binding = 0) uniform ViewUBO"),
                "m5.4 shadow MVP UBO is missing");
        assertTrue(vertex.source().contains("mat4 MVP;"), "m5.4 shadow MVP field is missing");
        assertTrue(!vertex.source().contains("LightMVP"),
                "m5.4 pack shadow bridge retained the fixed LightMVP name");
        assertTrue(vertex.source().contains("layout(binding = 2) uniform SectionData"),
                "m5.4 shadow section UBO is missing");
        assertTrue(vertex.source().contains("layout(location = 3) in int inMaterialId"),
                "m5.4 shadow material input is missing");
        assertTrue(vertex.source().contains("layout(location = 4) in int inRenderType"),
                "m5.4 shadow render-type input is missing");

        String convertedFragment = LegacyGlslConverter.convertFragment(
                shadowProgram.fragmentSource(), shadowProgram.fragmentPath(), true,
                new int[] {0, 2}, vertex.layout(), interfacePlan);
        assertTrue(convertedFragment != null, "m5.4 shadow fragment conversion failed");
        assertTrue(convertedFragment.contains("layout(binding = 3) uniform sampler2D chimeraTexture"),
                "m5.4 shadow atlas binding is missing");
        assertTrue(convertedFragment.contains("layout(binding = 4) uniform sampler2D lightmap"),
                "m5.4 shadow lightmap binding is missing");
        assertTrue(convertedFragment.contains("layout(location = "),
                "m5.4 shadow varying locations are missing");

        JsonObject shadowConfig = PackPipelines.shadowPipelineJson();
        JsonArray shadowUbos = shadowConfig.getAsJsonArray("UBOs");
        assertTrue(shadowUbos != null && shadowUbos.toString().contains("\"binding\":2"),
                "m5.4 shadow section UBO binding was not preserved");
        assertTrue(shadowUbos.toString().contains("\"name\":\"MVP\""),
                "m5.4 shadow pack config MVP field is missing");
        assertEquals(List.of(0, 2), toList(PackPipelines.shadowSamplerSlots(new int[] {2})),
                "m5.4 shadow lightmap descriptor reservation");
        assertEquals(List.of(0), toList(PackPipelines.shadowSamplerSlots(new int[] {0})),
                "m5.4 shadow atlas descriptor ordering");

        PackConfig.PackConfigData config = PackConfig.parse(loaded.programs(), loaded.shadersDir());
        assertEquals(1024, config.shadowSettings().resolution(),
                "m5.4 shaders.properties resolution precedence");
        assertEquals(256.0F, config.shadowSettings().distance(),
                "m5.4 shaders.properties distance precedence");
        assertTrue(config.shadowSettings().rawValues().get("shadowMapResolution").equals("1024"),
                "m5.4 resolution raw value");
        assertTrue(config.shadowSettings().rawValues().get("shadowDistance").equals("256"),
                "m5.4 distance raw value");
        verifyM54SettingValidation();
        verifyM54Baseline(report, baselinePath, "shadow");
    }

    private static void verifyM54Unsupported(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport shadow = report.program("shadow");
        assertTrue(shadow != null, "m5.4 unsupported shadow program was not discovered");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                shadow.support(), "m5.4 unsupported shadow support");
        assertTrue(shadow.deviations().contains("SHADOW_SAMPLER_UNSUPPORTED:shadowtex1"),
                "m5.4 unsupported shadow sampler deviation is missing");
        assertTrue(!report.shouldAttempt("shadow"),
                "m5.4 unsupported shadow must not execute");
        assertTrue(report.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowDistance"),
                "m5.4 invalid distance default is missing");
        assertTrue(report.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowMapResolution"),
                "m5.4 invalid resolution default is missing");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                Files.readString(pack.resolve("shaders/shadow.fsh"), StandardCharsets.UTF_8),
                UniformRegistry.Stage.SHADOW);
        assertTrue(!interfacePlan.executable(), "m5.4 unsupported shadow interface was accepted");
        UniformRegistry.ProgramInterface colorInput = UniformRegistry.plan(
                "uniform sampler2D texture; uniform sampler2D shadowcolor0;",
                UniformRegistry.Stage.SHADOW);
        assertTrue(colorInput.deviations().contains("SHADOW_COLOR_INPUT_UNSUPPORTED"),
                "m5.4 shadow color input deviation is missing");
        assertTrue(!colorInput.executable(), "m5.4 shadow color input was accepted");
        assertTrue(LegacyGlslConverter.convertShadowVertex(
                Files.readString(pack.resolve("shaders/shadow.vsh"), StandardCharsets.UTF_8),
                pack.resolve("shaders/shadow.vsh"),
                Files.readString(pack.resolve("shaders/shadow.fsh"), StandardCharsets.UTF_8)) != null,
                "m5.4 unsupported fixture vertex was rejected for the wrong reason");
        verifyM54Baseline(report, baselinePath, "unsupported_shadow");
    }

    private static void verifyM54SettingValidation() {
        Path root;
        try {
            root = Files.createTempDirectory("chimera-m54-settings-");
            Path shaders = Files.createDirectories(root.resolve("shaders"));
            Files.writeString(shaders.resolve("shaders.properties"),
                    "shadowMapResolution=64\nshadowDistance=4096\nshadowMapFov=90\n");
            PackConfig.ShadowSettings settings = PackConfig.parse(List.of(
                    new PackProgram("shadow", "#version 120\nconst int shadowMapResolution = 2048;",
                            shaders.resolve("shadow.fsh"), null, null)), shaders).shadowSettings();
            assertEquals(PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION, settings.resolution(),
                    "m5.4 invalid resolution default");
            assertEquals(PackConfig.DEFAULT_SHADOW_DISTANCE, settings.distance(),
                    "m5.4 invalid distance default");
            assertTrue(settings.deviations().contains("SHADOW_SETTING_UNSUPPORTED:shadowMapFov"),
                    "m5.4 unsupported shadow setting");
            assertTrue(settings.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowMapResolution"),
                    "m5.4 invalid resolution deviation");
            assertTrue(settings.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowDistance"),
                    "m5.4 invalid distance deviation");
            Files.deleteIfExists(shaders.resolve("shaders.properties"));
            Files.deleteIfExists(shaders);
            Files.deleteIfExists(root);
        } catch (IOException e) {
            throw new AssertionError("m5.4 setting validation setup failed", e);
        }
    }

    private static void assertExtendedFormat() {
        var format = net.chimera.render.vertex.ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN;
        assertEquals(24, format.getVertexSize(), "m5.2 terrain stride");
        assertEquals(5, format.getElements().size(), "m5.2 terrain attribute count");
        for (int i = 3; i < 5; i++) {
            var element = format.getElements().get(i);
            assertEquals(com.mojang.blaze3d.vertex.VertexFormatElement.Type.INT,
                    element.type(), "m5.2 generic attribute type " + i);
            assertEquals(com.mojang.blaze3d.vertex.VertexFormatElement.Usage.GENERIC,
                    element.usage(), "m5.2 generic attribute usage " + i);
            assertEquals(1, element.count(), "m5.2 generic attribute count " + i);
        }
    }

    private static void verifyMaterialParserEdgeCases() throws IOException {
        Path root = Files.createTempDirectory("chimera-m52-material-");
        try {
            Files.writeString(root.resolve("block.properties"),
                    "block.-7 = diorite\n"
                            + "block.8 = minecraft:dirt\n"
                            + "block.8 = minecraft:dirt\n"
                            + "block.9 = minecraft:oak_planks:axis=x\n"
                            + "block.10 = %minecraft:logs\n"
                            + "block.40000 = bad\n"
                            + "block.11 = minecraft:stone\n"
                            + "block.12 = minecraft:stone\n");
            PackMaterialResolver.ParseResult result = PackMaterialResolver.parse(root);
            assertEquals(-7, result.resolver().resolveName("diorite"), "signed material id");
            assertEquals(8, result.resolver().resolveName("minecraft:dirt"), "duplicate material id");
            assertEquals(-1, result.resolver().resolveName("minecraft:oak_planks"), "selector mapping");
            assertEquals(-1, result.resolver().resolveName("minecraft:stone"), "conflicting mapping");
            assertTrue(result.deviations().contains("BLOCK_SELECTOR_UNSUPPORTED"),
                "selector deviation is missing");
            assertTrue(result.deviations().contains("BLOCK_TAG_UNSUPPORTED"),
                    "tag deviation is missing");
            assertTrue(result.deviations().contains("BLOCK_PROPERTIES_INVALID"),
                "invalid properties deviation is missing");
            assertTrue(result.deviations().contains("BLOCK_MAPPING_CONFLICT"),
                    "mapping conflict deviation is missing");
        } finally {
            Files.deleteIfExists(root.resolve("block.properties"));
            Files.deleteIfExists(root);
        }
    }

    private static ConformanceReport probe(Path pack) {
        assertTrue(Files.isDirectory(pack), "fixture is missing: " + pack);
        return PackProbe.probe(pack);
    }

    private static void verifyRequiredPrograms(ConformanceReport report, String label) {
        for (String name : REQUIRED_PROGRAMS) {
            assertTrue(report.program(name) != null, label + " is missing program " + name);
        }
    }

    private static void verifyBaseline(ConformanceReport report, Path baselinePath) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "Simplex baseline is missing: " + baselinePath);
        JsonObject baseline = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(baseline.get("reportSha256").getAsString(),
                report.sha256(), "Simplex report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "Simplex pass inventory");

        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry :
                baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        Map<String, String> actualHashes = sourceHashes(report);
        assertEquals(expectedHashes, actualHashes, "Simplex source hashes");
        assertStable(report, "simplex");
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> hashes = new TreeMap<>();
        JsonObject root = JsonParser.parseString(report.toJson()).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry :
                root.getAsJsonObject("metadataHashes").entrySet()) {
            hashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        for (JsonElement element : root.getAsJsonArray("programs")) {
            JsonObject program = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry :
                    program.getAsJsonObject("sourceHashes").entrySet()) {
                hashes.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return hashes;
    }

    private static Map<String, String> metadataHashes(ConformanceReport report) {
        Map<String, String> hashes = new TreeMap<>();
        JsonObject root = JsonParser.parseString(report.toJson()).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("metadataHashes").entrySet()) {
            hashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        return hashes;
    }

    private static void verifyM52Baseline(
            ConformanceReport report,
            Path baselinePath,
            String fixture
    ) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "M5.2 baseline is missing: " + baselinePath);
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject baseline = root.getAsJsonObject("fixtures").getAsJsonObject(fixture);
        assertTrue(baseline != null, "M5.2 baseline fixture is missing: " + fixture);
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M5.2 " + fixture + " report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "M5.2 " + fixture + " pass inventory");
        assertEquals(baseline.get("expectedStages").toString(), expectedStages(report).toString(),
                "M5.2 " + fixture + " stages");
        assertEquals(baseline.get("expectedDeviations").toString(), expectedDeviations(report).toString(),
                "M5.2 " + fixture + " deviations");
        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedHashes, sourceHashes(report), "M5.2 " + fixture + " source hashes");
        assertStable(report, "m5.2 " + fixture);
    }

    private static void verifyM53Baseline(
            ConformanceReport report,
            Path baselinePath,
            String fixture
    ) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "M5.3 baseline is missing: " + baselinePath);
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject baseline = root.getAsJsonObject("fixtures").getAsJsonObject(fixture);
        assertTrue(baseline != null, "M5.3 baseline fixture is missing: " + fixture);
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M5.3 " + fixture + " report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "M5.3 " + fixture + " pass inventory");
        assertEquals(baseline.get("expectedStages").toString(), expectedStages(report).toString(),
                "M5.3 " + fixture + " stages");
        assertEquals(baseline.get("expectedDeviations").toString(), expectedDeviations(report).toString(),
                "M5.3 " + fixture + " deviations");
        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedHashes, sourceHashes(report), "M5.3 " + fixture + " source hashes");
        assertStable(report, "m5.3 " + fixture);
    }

    private static void verifyM54Baseline(
            ConformanceReport report,
            Path baselinePath,
            String fixture
    ) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "M5.4 baseline is missing: " + baselinePath);
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject baseline = root.getAsJsonObject("fixtures").getAsJsonObject(fixture);
        assertTrue(baseline != null, "M5.4 baseline fixture is missing: " + fixture);
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M5.4 " + fixture + " report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "M5.4 " + fixture + " pass inventory");
        assertEquals(baseline.get("expectedStages").toString(), expectedStages(report).toString(),
                "M5.4 " + fixture + " stages");
        assertEquals(baseline.get("expectedDeviations").toString(), expectedDeviations(report).toString(),
                "M5.4 " + fixture + " deviations");
        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedHashes, sourceHashes(report), "M5.4 " + fixture + " source hashes");
        assertStable(report, "m5.4 " + fixture);
    }

    private static void verifyM55Baseline(
            ConformanceReport report,
            Path baselinePath,
            String fixture
    ) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "M5.5 baseline is missing: " + baselinePath);
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject baseline = root.getAsJsonObject("fixtures").getAsJsonObject(fixture);
        assertTrue(baseline != null, "M5.5 baseline fixture is missing: " + fixture);
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M5.5 " + fixture + " report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "M5.5 " + fixture + " pass inventory");
        assertEquals(baseline.get("expectedStages").toString(), expectedStages(report).toString(),
                "M5.5 " + fixture + " stages");
        assertEquals(baseline.get("expectedDeviations").toString(), expectedDeviations(report).toString(),
                "M5.5 " + fixture + " deviations");
        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedHashes, sourceHashes(report), "M5.5 " + fixture + " source hashes");
        assertStable(report, "m5.5 " + fixture);
    }

    private static void verifyM56Baseline(
            ConformanceReport report,
            Path baselinePath,
            String fixture
    ) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "M5.6 baseline is missing: " + baselinePath);
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject baseline = root.getAsJsonObject("fixtures").getAsJsonObject(fixture);
        assertTrue(baseline != null, "M5.6 baseline fixture is missing: " + fixture);
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M5.6 " + fixture + " report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "M5.6 " + fixture + " pass inventory");
        assertEquals(baseline.get("expectedStages").toString(), expectedStages(report).toString(),
                "M5.6 " + fixture + " stages");
        assertEquals(baseline.get("expectedDeviations").toString(), expectedDeviations(report).toString(),
                "M5.6 " + fixture + " deviations");
        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedHashes, sourceHashes(report), "M5.6 " + fixture + " source hashes");
        assertStable(report, "m5.6 " + fixture);
    }

    private static JsonObject expectedStages(ConformanceReport report) {
        JsonObject stages = new JsonObject();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            stages.add(program.name(), strings(program.stages()));
        }
        return stages;
    }

    private static JsonObject expectedDeviations(ConformanceReport report) {
        JsonObject deviations = new JsonObject();
        deviations.add("global", strings(report.deviations()));
        for (ConformanceReport.ProgramReport program : report.programs()) {
            deviations.add(program.name(), strings(program.deviations()));
        }
        return deviations;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> result = new ArrayList<>();
        for (int value : values) {
            result.add(value);
        }
        return result;
    }

    private static void assertStable(ConformanceReport report, String label) {
        String first = report.toJson();
        String second = report.toJson();
        assertEquals(first, second, label + " report must be deterministic");
        assertTrue(!first.contains("C:\\") && !first.contains("file:") && !first.contains("\"/"),
                label + " report contains an absolute path");
        JsonObject root = JsonParser.parseString(first).getAsJsonObject();
        for (JsonElement element : root.getAsJsonArray("programs")) {
            for (JsonElement hash : element.getAsJsonObject()
                    .getAsJsonObject("sourceHashes").entrySet().stream()
                    .map(Map.Entry::getValue).toList()) {
                assertTrue(hash.getAsString().matches("[0-9a-f]{64}"),
                        label + " contains an invalid source hash");
            }
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
