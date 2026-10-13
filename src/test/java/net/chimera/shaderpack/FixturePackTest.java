package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checked-in fixture packs: each report matches its recorded baseline, and each unsupported
 * shape falls back to identity with the deviation that names it.
 */
final class FixturePackTest {
    private static final List<String> REQUIRED_PROGRAMS = List.of(
            "gbuffers_terrain", "composite", "final");

    @Test
    void simplex() throws IOException {
        verifySimplex(TestPacks.fixture("simplex"), TestPacks.baseline("simplex"));
    }

    @Test
    void optifineLayout() {
        verifySupported(TestPacks.fixture("m5_1/optifine"), "optifine");
    }

    @Test
    void irisLayout() {
        verifySupported(TestPacks.fixture("m5_1/iris"), "iris");
    }

    @Test
    void unsupportedPrograms() {
        verifyUnsupported(TestPacks.fixture("m5_1/unsupported"));
    }

    @Test
    void material() throws IOException {
        verifyMaterial(TestPacks.fixture("m5_2/material"), TestPacks.baseline("m5_2"));
    }

    @Test
    void unsupportedVertex() throws IOException {
        verifyUnsupportedVertex(TestPacks.fixture("m5_2/unsupported_vertex"), TestPacks.baseline("m5_2"));
    }

    @Test
    void liveUniforms() throws IOException {
        verifyLiveUniforms(TestPacks.fixture("m5_3/live_uniforms"), TestPacks.baseline("m5_3"));
    }

    @Test
    void unsupportedUniform() throws IOException {
        verifyUnsupportedUniform(TestPacks.fixture("m5_3/unsupported_uniform"), TestPacks.baseline("m5_3"));
    }

    @Test
    void shadow() throws IOException {
        verifyShadow(TestPacks.fixture("m5_4/shadow"), TestPacks.baseline("m5_4"));
    }

    @Test
    void unsupportedShadow() throws IOException {
        verifyUnsupportedShadow(TestPacks.fixture("m5_4/unsupported_shadow"), TestPacks.baseline("m5_4"));
    }

    @Test
    void water() throws IOException {
        verifyWater(TestPacks.fixture("m5_5/water"), TestPacks.baseline("m5_5"));
    }

    @Test
    void unsupportedWater() throws IOException {
        verifyUnsupportedWater(TestPacks.fixture("m5_5/unsupported_water"), TestPacks.baseline("m5_5"));
    }

    @Test
    void postChain() throws IOException {
        verifyPostChain(TestPacks.fixture("m5_6/post_chain"), TestPacks.baseline("m5_6"));
    }

    @Test
    void unsupportedTargets() throws IOException {
        verifyUnsupportedTargets(TestPacks.fixture("m5_6/unsupported_targets"), TestPacks.baseline("m5_6"));
    }

    @Test
    void postColorInputOrder() {
        assertEquals(List.of(0, 2, 3), PackPipelines.colorInputTargets(
                List.of("colortex3", "depthtex0", "colortex0", "colortex2")), "post color input ordering");
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
        for (String name : List.of("composite", "final")) {
            assertTrue(report.shouldAttempt(name), "simplex authored post pair was rejected: " + name);
            assertEquals(List.of(0), report.program(name).targets(), "simplex post target route: " + name);
            assertTrue(!report.program(name).deviations().contains("FIXED_VERTEX_SUBSTITUTION"),
                    "simplex authored post vertex was replaced: " + name);
        }
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

    private static void verifyMaterial(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        verifyRequiredPrograms(report, "material");
        ConformanceReport.ProgramReport terrain = report.program("gbuffers_terrain");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                terrain.support(), "material terrain support");
        assertEquals(List.of("fragment", "vertex"), terrain.stages(), "material terrain stages");
        assertEquals(List.of("ALPHA_TEST_DYNAMIC:gbuffers_terrain",
                        "LEGACY_TERRAIN_VERTEX_BRIDGE"), terrain.deviations(),
                "material terrain deviations");
        assertTrue(report.shouldAttempt("gbuffers_terrain"), "material terrain was rejected");
        assertTrue(metadataHashes(report).containsKey("block.properties"),
                "material block.properties hash is missing");

        PackMaterialResolver.ParseResult material = PackMaterialResolver.parse(pack.resolve("shaders"));
        assertTrue(material.present(), "material properties are missing");
        assertEquals(1, material.resolver().resolveName("minecraft:stone"), "stone material id");
        assertEquals(2, material.resolver().resolveName("dirt"), "unqualified dirt material id");
        assertEquals(2, material.resolver().resolveName("minecraft:grass_block"), "grass material id");
        assertEquals(-1, material.resolver().resolveName("minecraft:diamond_block"), "unmapped material id");
        assertEquals(List.of(), material.deviations(), "material parser deviations");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        PackProgram terrainProgram = loaded.programs().stream()
                .filter(program -> program.name().equals("gbuffers_terrain"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("material terrain source is missing"));
        assertTrue(terrainProgram.vertexSource() != null, "material vertex source was not retained");
        LegacyGlslConverter.TerrainVertexConversion converted = TerrainVertexBridge.convertTerrainVertex(
                terrainProgram.vertexSource(), terrainProgram.vertexPath(), terrainProgram.fragmentSource());
        assertTrue(converted != null, "material vertex conversion failed");
        assertTrue(converted.source().contains("layout(location = 0) out"),
                "material varying layout was not emitted");
        assertTrue(converted.source().contains("inMaterialId"),
                "material input attribute was not emitted");
        String convertedFragment = FragmentConverter.FragmentConversionRequest.withAutoPlan(
                terrainProgram.fragmentSource(), terrainProgram.fragmentPath(), true,
                new int[] {0, 2}, converted.layout()).convert();
        assertTrue(convertedFragment != null && convertedFragment.contains("layout(location = 0) in"),
                "material fragment varying bridge failed");

        assertExtendedFormat();
        // The version number no longer gates (GlslVersion); the legacy converter still refuses
        // modern syntax, which the modern terrain path converts, and GLSL ES.
        assertTrue(TerrainVertexBridge.convertTerrainVertex(
                "#version 330\nin vec3 Position;\nvoid main() { gl_Position = vec4(Position, 1.0); }", null,
                        "") == null,
                "modern terrain vertex syntax was accepted by the legacy converter");
        assertTrue(TerrainVertexBridge.convertTerrainVertex(
                "#version 300 es\nvoid main() { gl_Position = vec4(0.0); }", null, "") == null,
                "GLSL ES terrain vertex source was accepted");
        assertTrue(TerrainVertexBridge.convertTerrainVertex(
                "#version 120\nvoid main() { gl_Position = ftransform(); float n = gl_Normal.x; }",
                null, "") == null,
                "unsupported terrain normal was accepted");
        verifyFixtureBaseline(report, baselinePath, "material");
    }

    private static void verifyUnsupportedVertex(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport terrain = report.program("gbuffers_terrain");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                terrain.support(), "unsupported vertex support");
        assertTrue(terrain.deviations().contains("TERRAIN_VERTEX_BRIDGE_UNSUPPORTED"),
                "unsupported vertex deviation");
        assertTrue(!report.shouldAttempt("gbuffers_terrain"),
                "unsupported vertex must not execute");
        assertTrue(metadataHashes(report).containsKey("block.properties"),
                "unsupported block.properties hash is missing");
        verifyFixtureBaseline(report, baselinePath, "unsupported_vertex");
    }

    private static void verifyLiveUniforms(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        verifyRequiredPrograms(report, "live uniforms");
        ConformanceReport.ProgramReport composite = report.program("composite");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                composite.support(), "live composite support");
        assertTrue(report.shouldAttempt("composite"), "live composite was rejected");
        assertEquals(List.of("colortex0", "depthtex0"), composite.samplers(),
                "live composite samplers");
        assertEquals(List.of(
                        "cameraPosition", "colortex0", "depthtex0", "fogColor",
                        "frameTimeCounter", "sunPosition", "viewHeight", "viewWidth",
                        "wetness", "worldTime"), composite.uniforms(),
                "live composite declarations");
        assertEquals(List.of(
                        "DEPTH_INPUT_FIXED_TO_HDR",
                        "LIVE_UNIFORM_BRIDGE"), composite.deviations(),
                "live composite deviations");
        assertEquals(List.of(), report.deviations(), "live global deviations");

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
                interfacePlan.uniforms(), "uniform ordering");
        assertEquals(List.of(
                        new UniformRegistry.SamplerBinding("colortex0", 0),
                        new UniformRegistry.SamplerBinding("depthtex0", 6)),
                interfacePlan.samplers(), "sampler ordering");
        assertTrue(interfacePlan.executable(), "live interface is not executable");
        assertEquals("float", UniformRegistry.pipelineType("vec3"), "vec3 pipeline type");
        assertEquals(3, UniformRegistry.pipelineCount("vec3"), "vec3 pipeline count");
        assertEquals("int", UniformRegistry.pipelineType("int"), "int pipeline type");
        assertEquals(16, UniformRegistry.pipelineCount("mat4"), "mat4 pipeline count");

        PackProgram compositeProgram = PackSource.loadResult(pack).programs().stream()
                .filter(program -> program.name().equals("composite"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("live composite source is missing"));
        String converted = FragmentConverter.FragmentConversionRequest
                .of(compositeProgram.fragmentSource(), compositeProgram.fragmentPath(), false, null)
                .withInterfacePlan(interfacePlan)
                .convert();
        assertTrue(converted != null, "live composite conversion failed");
        assertTrue(converted.contains("layout(binding = 0) uniform ChimeraPackUniforms"),
                "generated UBO is missing");
        assertTrue(converted.contains("layout(binding = 1) uniform sampler2D colortex0"),
                "colortex0 binding is not after the UBO");
        assertTrue(converted.contains("layout(binding = 2) uniform sampler2D depthtex0"),
                "depthtex0 binding is not after the UBO");
        assertTrue(!converted.contains("uniform vec3 cameraPosition"),
                "source uniform was not removed");
        verifyFixtureBaseline(report, baselinePath, "live_uniforms");
    }

    @Test
    void uniformInterfaceEdgeCases() {
        UniformRegistry.ProgramInterface array = UniformRegistry.plan(
                "uniform float wetness[2];", UniformRegistry.Stage.POST);
        assertTrue(!array.executable(), "uniform arrays were accepted");
        assertTrue(array.deviations().contains("UNIFORM_TYPE_UNSUPPORTED:wetness"),
                "array deviation is missing");

        UniformRegistry.ProgramInterface struct = UniformRegistry.plan(
                "uniform PackValues { float wetness; };", UniformRegistry.Stage.POST);
        assertTrue(!struct.executable(), "uniform blocks were accepted");
        assertTrue(struct.deviations().contains("UNIFORM_TYPE_UNSUPPORTED:PackValues"),
                "uniform block deviation is missing");

        // Iris leaves a uniform it does not provide unset, so GL reads zero and the program runs.
        UniformRegistry.ProgramInterface unknown = UniformRegistry.plan(
                "uniform float arbitraryValue; void main() { gl_FragColor = vec4(arbitraryValue); }",
                UniformRegistry.Stage.POST);
        assertTrue(unknown.executable(), "a name Iris does not serve must run on zero");
        assertTrue(unknown.deviations().contains("UNIFORM_UNSET_ZERO:arbitraryValue"),
                "unset uniform deviation is missing");
        // An Iris name Chimera does not serve yet must not run on a zero Iris would never give.
        UniformRegistry.ProgramInterface unserved = UniformRegistry.plan(
                "uniform float fogDensity; void main() { gl_FragColor = vec4(fogDensity); }",
                UniformRegistry.Stage.POST);
        assertTrue(!unserved.executable(), "unserved Iris uniform was accepted");
        assertTrue(unserved.deviations().contains("UNIFORM_NAME_UNSUPPORTED:fogDensity"),
                "unserved Iris uniform deviation is missing");

        UniformRegistry.ProgramInterface conflict = UniformRegistry.plan(
                "uniform float wetness; uniform int wetness;", UniformRegistry.Stage.POST);
        assertTrue(!conflict.executable(), "conflicting uniform declarations were accepted");
        assertTrue(conflict.deviations().contains("UNIFORM_CONFLICT:wetness"),
                "conflicting uniform deviation is missing");

        UniformRegistry.ProgramInterface alias = UniformRegistry.plan(
                "uniform sampler2D colortex1;", UniformRegistry.Stage.POST);
        assertTrue(alias.hasSampler("colortex1"), "colortex1 alias was not mapped");
        assertTrue(alias.deviations().contains("COLORTEX_ALIAS_TO_SEAM"),
                "colortex alias deviation is missing");

        UniformRegistry.ProgramInterface distinctResources = UniformRegistry.plan(
                "uniform sampler2D colortex3; uniform sampler2D shadowcolor0;",
                UniformRegistry.Stage.POST);
        assertTrue(distinctResources.executable(), "Independent color and shadow resources were rejected");
        assertEquals(2L, distinctResources.samplers().stream()
                .map(UniformRegistry.SamplerBinding::slot).distinct().count(),
                "Color and shadow resources share a selector");
    }

    private static void verifyUnsupportedUniform(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport composite = report.program("composite");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                composite.support(), "unsupported composite support");
        assertTrue(composite.deviations().contains("SAMPLER_NOT_MAPPED:unknownTexture"),
                "unknown sampler deviation is missing");
        assertTrue(composite.deviations().contains("UNIFORM_TYPE_UNSUPPORTED:unsupportedToggle"),
                "unsupported uniform type deviation is missing");
        assertTrue(!report.shouldAttempt("composite"), "unsupported composite must not execute");
        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                Files.readString(pack.resolve("shaders/composite.fsh"), StandardCharsets.UTF_8),
                UniformRegistry.Stage.POST);
        assertTrue(!interfacePlan.executable(), "unsupported interface was accepted");
        assertTrue(FragmentConverter.FragmentConversionRequest.withAutoPlan(
                Files.readString(pack.resolve("shaders/composite.fsh"), StandardCharsets.UTF_8),
                pack.resolve("shaders/composite.fsh"), false, null, null).convert() == null,
                "unsupported source converted");
        verifyFixtureBaseline(report, baselinePath, "unsupported_uniform");
    }

    private static void verifyPostChain(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        assertEquals(List.of("composite", "final"), report.passInventory(),
                "post pass inventory must preserve shaders.json order");
        for (String name : List.of("deferred", "composite", "composite1", "final")) {
            ConformanceReport.ProgramReport program = report.program(name);
            assertTrue(program != null, "post program was not discovered: " + name);
            assertEquals(List.of("fragment"), program.stages(),
                    "post stages: " + name);
            assertEquals(ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED,
                    program.runtime(), "static post runtime: " + name);
            assertTrue(report.shouldAttempt(name), "post program was rejected: " + name);
        }
        assertEquals(List.of(0, 1), report.program("deferred").targets(),
                "deferred targets");
        assertEquals(List.of(0, 1), report.program("composite").targets(),
                "composite targets");
        assertEquals(List.of(0), report.program("composite1").targets(),
                "numbered composite targets");
        assertEquals(List.of(0), report.program("final").targets(),
                "final target");
        assertEquals(List.of("MRT_POST_BRIDGE", "POST_TARGET_ROUTE_APPLIED"),
                report.program("deferred").deviations(), "deferred deviations");
        assertEquals(List.of("MRT_POST_BRIDGE", "POST_TARGET_ROUTE_APPLIED"),
                report.program("composite").deviations(), "composite deviations");
        assertEquals(List.of(),
                report.program("composite1").deviations(), "numbered composite deviations");
        assertEquals(List.of(), report.program("final").deviations(), "final deviations");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                report.program("deferred").support(), "deferred support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                report.program("composite").support(), "composite support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                report.program("composite1").support(), "numbered composite support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                report.program("final").support(), "final support");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        List<String> loadedNames = loaded.programs().stream().map(PackProgram::name)
                .distinct().sorted(PostTargetPlan.programComparator()).toList();
        assertEquals(List.of("deferred", "composite", "composite1", "final"), loadedNames,
                "discovered post order");
        assertEquals(4L, loaded.programs().stream()
                .filter(program -> PostTargetPlan.isPostProgramName(program.name())).count(),
                "duplicate post programs");

        String deferred = Files.readString(pack.resolve("shaders/deferred.fsh"), StandardCharsets.UTF_8);
        String composite = Files.readString(pack.resolve("shaders/composite.fsh"), StandardCharsets.UTF_8);
        PostTargetPlan deferredPlan = PostTargetPlan.parse("deferred", deferred,
                Map.of(0, 97, 1, 37)).plan();
        PostTargetPlan compositePlan = PostTargetPlan.parse("composite", composite,
                Map.of(0, 97, 1, 37)).plan();
        assertEquals(List.of(0, 1), deferredPlan.targetSlots(), "deferred route");
        assertEquals(List.of(0, 1), compositePlan.targetSlots(), "composite route");
        assertEquals(List.of(97, 37), compositePlan.outputFormats(), "target formats");
        assertTrue(compositePlan.requiresMrt(), "composite must require MRT");
        String converted = FragmentConverter.convertPostFragment(
                composite, pack.resolve("shaders/composite.fsh"),
                UniformRegistry.planPost(composite, compositePlan), compositePlan);
        assertTrue(converted != null, "multi-target conversion failed");
        assertTrue(converted.contains("layout(location = 0) out vec4 chimeraFragColor0"),
                "output location 0 is missing");
        assertTrue(converted.contains("layout(location = 1) out vec4 chimeraFragColor1"),
                "output location 1 is missing");
        assertTrue(!converted.contains("DRAWBUFFERS01"),
                "DRAWBUFFERS directive was not removed");
        verifyFixtureBaseline(report, baselinePath, "post_chain");
    }

    @Test
    void targetParserEdgeCases() {
        PostTargetPlan drawBuffers = PostTargetPlan.parse(
                "composite", "#version 120\n#define DRAWBUFFERS0123\n").plan();
        assertEquals(List.of(0, 1, 2, 3), drawBuffers.targetSlots(),
                "DRAWBUFFERS digits are target slots");

        PostTargetPlan renderTargets = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0, 2, 3 */").plan();
        assertEquals(List.of(0, 2, 3), renderTargets.targetSlots(),
                "RENDERTARGETS route");

        PostTargetPlan malformed = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0, nope */").plan();
        assertTrue(!malformed.executable(), "malformed target directive was accepted");
        assertTrue(malformed.deviations().contains("POST_TARGET_DIRECTIVE_MALFORMED"),
                "malformed target deviation is missing");

        PostTargetPlan conflict = PostTargetPlan.parse(
                "composite", "#define DRAWBUFFERS01\n/* RENDERTARGETS: 0,2 */").plan();
        assertTrue(!conflict.executable(), "conflicting target directives were accepted");
        assertTrue(conflict.deviations().contains("POST_TARGET_DIRECTIVE_CONFLICT"),
                "target conflict deviation is missing");

        PostTargetPlan unsupported = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0,16 */").plan();
        assertTrue(!unsupported.executable(), "unsupported target was accepted");
        assertTrue(unsupported.deviations().contains("POST_TARGET_INDEX_UNSUPPORTED:16"),
                "unsupported target deviation is missing");

        PostTargetPlan finalMrt = PostTargetPlan.parse(
                "final", "/* RENDERTARGETS: 0,1 */ gl_FragData[0] = vec4(1.0);").plan();
        assertTrue(!finalMrt.executable(), "final MRT was accepted");
        assertTrue(finalMrt.deviations().contains("FINAL_MRT_UNSUPPORTED"),
                "final MRT deviation is missing");
    }

    private static void verifyUnsupportedTargets(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport composite = report.program("composite");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                composite.support(), "unsupported target support");
        assertTrue(composite.deviations().contains("POST_TARGET_INDEX_UNSUPPORTED:16"),
                "unsupported target deviation is missing");
        assertTrue(!report.shouldAttempt("composite"),
                "unsupported target must not execute");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                report.program("final").support(), "unsupported fixture final probe");
        verifyFixtureBaseline(report, baselinePath, "unsupported_targets");
    }

    private static void verifyWater(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        assertTrue(report.program("composite") != null, "water is missing program composite");
        assertTrue(report.program("final") != null, "water is missing program final");
        ConformanceReport.ProgramReport water = report.program("gbuffers_water");
        assertTrue(water != null, "water program was not discovered");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                water.support(), "water support");
        assertEquals(List.of("fragment", "vertex"), water.stages(), "water stages");
        assertTrue(water.deviations().contains("TRANSLUCENT_VERTEX_BRIDGE"),
                "water vertex bridge deviation is missing");
        assertTrue(water.deviations().contains("TRANSLUCENT_STATE_FIXED_TO_HOST"),
                "host translucent state deviation is missing");
        assertEquals(List.of("lightmap", "shadowtex0", "texture"), water.samplers(),
                "water sampler inventory");
        assertEquals(List.of("composite", "final"), report.passInventory(),
                "water must be discovered outside shaders.json");
        assertTrue(report.programs().stream().filter(program -> program.name().equals("gbuffers_water")).count() == 1,
                "water program was duplicated");
        assertPostPassThrough(pack, "water");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        PackProgram waterProgram = loaded.programs().stream()
                .filter(program -> program.name().equals("gbuffers_water"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("water source is missing"));
        assertTrue(waterProgram.vertexSource() != null, "water vertex source is missing");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                waterProgram.fragmentSource(), UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(interfacePlan.executable(), "water interface is not executable");
        // Water plans on the terrain contract, so its uniforms are the
        // live Iris catalog (atlasSize, fogStart), not host UBO field aliases.
        assertEquals(List.of(
                        new UniformRegistry.UniformDeclaration("atlasSize", "ivec2"),
                        new UniformRegistry.UniformDeclaration("fogColor", "vec4"),
                        new UniformRegistry.UniformDeclaration("fogEnd", "float"),
                        new UniformRegistry.UniformDeclaration("fogStart", "float")),
                interfacePlan.uniforms(), "water uniform ordering");
        assertEquals(List.of(
                        new UniformRegistry.SamplerBinding("texture", 0),
                        new UniformRegistry.SamplerBinding("lightmap", 2),
                        new UniformRegistry.SamplerBinding("shadowtex0", 5)),
                interfacePlan.samplers(), "water sampler bindings");
        assertTrue(interfacePlan.deviations().contains("LIVE_UNIFORM_BRIDGE"),
                "water live uniform bridge deviation is missing");

        LegacyGlslConverter.TerrainVertexConversion vertex = TerrainVertexBridge.convertTerrainVertex(
                waterProgram.vertexSource(), waterProgram.vertexPath(), waterProgram.fragmentSource());
        assertTrue(vertex != null, "water vertex conversion failed");
        String convertedFragment = FragmentConverter.FragmentConversionRequest
                .of(waterProgram.fragmentSource(), waterProgram.fragmentPath(), true,
                        new int[] {0, 2, 5})
                .withTerrainLayout(vertex.layout())
                .withInterfacePlan(interfacePlan)
                .convert();
        assertTrue(convertedFragment != null, "water fragment conversion failed");
        assertTrue(convertedFragment.contains("layout(binding = 3) uniform ChimeraTerrainPackUniforms"),
                "terrain pack uniform block is missing");
        assertTrue(!convertedFragment.contains("uniform float fogStart"),
                "fogStart declaration was not removed");
        assertTrue(convertedFragment.contains("layout(binding = 4) uniform sampler2D chimeraTexture"),
                "water atlas binding is missing");
        assertTrue(convertedFragment.contains("layout(binding = 5) uniform sampler2D lightmap"),
                "water lightmap binding is missing");
        assertTrue(convertedFragment.contains("layout(binding = 6) uniform sampler2D shadowtex0"),
                "water shadow binding is missing");
        assertTrue(convertedFragment.contains("layout(location = " + vertex.layout().location("renderType")
                        + ") in float renderType"),
                "water varying location was not shared");

        assertExtendedFormat();
        verifyFixtureBaseline(report, baselinePath, "water");
    }

    @Test
    void translucentInterfaceEdgeCases() {
        UniformRegistry.ProgramInterface depth = UniformRegistry.plan(
                "uniform sampler2D texture; uniform sampler2D depthtex0;",
                UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!depth.executable(), "depth input was accepted");
        assertTrue(depth.deviations().contains("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED"),
                "depth input deviation is missing");

        UniformRegistry.ProgramInterface unknownSampler = UniformRegistry.plan(
                "uniform sampler2D noise;", UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!unknownSampler.executable(), "unknown water sampler was accepted");
        assertTrue(unknownSampler.deviations().contains("TRANSLUCENT_SAMPLER_UNSUPPORTED:noise"),
                "unknown water sampler deviation is missing");

        UniformRegistry.ProgramInterface unknownUniform = UniformRegistry.plan(
                "uniform float fogDensity; void main() { gl_FragColor = vec4(fogDensity); }",
                UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!unknownUniform.executable(), "unserved Iris water uniform was accepted");
        assertTrue(unknownUniform.deviations().contains("UNIFORM_NAME_UNSUPPORTED:fogDensity"),
                "unknown water uniform deviation is missing");
    }

    private static void verifyUnsupportedWater(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport water = report.program("gbuffers_water");
        assertTrue(water != null, "unsupported water program was not discovered");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                water.support(), "unsupported water support");
        assertTrue(water.deviations().contains("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED"),
                "unsupported water depth deviation is missing");
        assertTrue(!report.shouldAttempt("gbuffers_water"),
                "unsupported water must not execute");

        ConformanceReport.ProgramReport entities = report.program("gbuffers_entities");
        assertTrue(entities != null, "unsupported entity family was not inventoried");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED,
                entities.support(), "entity family support");
        assertTrue(!report.shouldAttempt("gbuffers_entities"),
                "entity family must not execute");
        assertPostPassThrough(pack, "unsupported water");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                Files.readString(pack.resolve("shaders/gbuffers_water.fsh"), StandardCharsets.UTF_8),
                UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!interfacePlan.executable(), "unsupported water interface was accepted");
        assertTrue(FragmentConverter.FragmentConversionRequest
                .of(Files.readString(pack.resolve("shaders/gbuffers_water.fsh"), StandardCharsets.UTF_8),
                        pack.resolve("shaders/gbuffers_water.fsh"), true, new int[] {0, 2})
                .withInterfacePlan(interfacePlan)
                .convert() == null,
                "unsupported water source converted");
        verifyFixtureBaseline(report, baselinePath, "unsupported_water");
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

    private static void verifyShadow(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport shadow = report.program("shadow");
        assertTrue(shadow != null, "shadow program was not discovered");
        assertEquals(List.of("fragment", "vertex"), shadow.stages(), "shadow stages");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                shadow.support(), "shadow support");
        // LIVE_UNIFORM_BRIDGE: the per-layer shadow alpha-test reference.
        assertEquals(List.of("LIVE_UNIFORM_BRIDGE", "SHADOW_VERTEX_BRIDGE"), shadow.deviations(),
                "shadow deviations");
        assertEquals(List.of("lightmap", "texture"), shadow.samplers(),
                "shadow sampler inventory");
        assertTrue(report.passInventory().equals(List.of("composite", "final")),
                "shadow must be discovered outside shaders.json");
        assertTrue(report.programs().stream().filter(program -> program.name().equals("shadow")).count() == 1,
                "shadow program was duplicated");
        assertTrue(report.deviations().contains("SHADOW_SETTING_APPLIED:shadowDistance"),
                "shadow distance application is missing");
        assertTrue(report.deviations().contains("SHADOW_SETTING_APPLIED:shadowMapResolution"),
                "shadow resolution application is missing");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        PackProgram shadowProgram = loaded.programs().stream()
                .filter(program -> program.name().equals("shadow"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("shadow source is missing"));
        assertTrue(shadowProgram.vertexSource() != null, "shadow vertex source is missing");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                shadowProgram.fragmentSource(), UniformRegistry.Stage.SHADOW);
        assertEquals(List.of(
                        new UniformRegistry.SamplerBinding("texture", 0),
                        new UniformRegistry.SamplerBinding("lightmap", 2)),
                interfacePlan.samplers(), "shadow sampler bindings");
        assertTrue(interfacePlan.executable(), "shadow interface is not executable");

        LegacyGlslConverter.TerrainVertexConversion vertex = TerrainVertexBridge.convertShadowVertex(
                shadowProgram.vertexSource(), shadowProgram.vertexPath(), shadowProgram.fragmentSource());
        assertTrue(vertex != null, "shadow vertex conversion failed");
        assertTrue(vertex.source().contains("layout(binding = 0) uniform ViewUBO"),
                "shadow MVP UBO is missing");
        assertTrue(vertex.source().contains("mat4 MVP;"), "shadow MVP field is missing");
        assertTrue(!vertex.source().contains("LightMVP"),
                "pack shadow bridge retained the fixed LightMVP name");
        assertTrue(vertex.source().contains("layout(binding = 2) uniform SectionData"),
                "shadow section UBO is missing");
        assertTrue(vertex.source().contains("layout(location = 3) in int inMaterialId"),
                "shadow material input is missing");
        assertTrue(vertex.source().contains("layout(location = 4) in int inRenderType"),
                "shadow render-type input is missing");

        String convertedFragment = FragmentConverter.FragmentConversionRequest
                .of(shadowProgram.fragmentSource(), shadowProgram.fragmentPath(), true,
                        new int[] {0, 2})
                .withTerrainLayout(vertex.layout())
                .withInterfacePlan(interfacePlan)
                .convert();
        assertTrue(convertedFragment != null, "shadow fragment conversion failed");
        assertTrue(convertedFragment.contains("layout(binding = 3) uniform sampler2D chimeraTexture"),
                "shadow atlas binding is missing");
        assertTrue(convertedFragment.contains("layout(binding = 4) uniform sampler2D lightmap"),
                "shadow lightmap binding is missing");
        assertTrue(convertedFragment.contains("layout(location = "),
                "shadow varying locations are missing");

        JsonObject shadowConfig = PackPipelines.shadowPipelineJson();
        JsonArray shadowUbos = shadowConfig.getAsJsonArray("UBOs");
        assertTrue(shadowUbos != null && shadowUbos.toString().contains("\"binding\":2"),
                "shadow section UBO binding was not preserved");
        assertTrue(shadowUbos.toString().contains("\"name\":\"MVP\""),
                "shadow pack config MVP field is missing");
        assertEquals(List.of(0, 2), toList(PackPipelines.shadowSamplerSlots(new int[] {2})),
                "shadow lightmap descriptor reservation");
        assertEquals(List.of(0), toList(PackPipelines.shadowSamplerSlots(new int[] {0})),
                "shadow atlas descriptor ordering");

        PackConfig.PackConfigData config = PackConfig.parse(loaded.programs(), loaded.shadersDir());
        assertEquals(1024, config.shadowSettings().resolution(),
                "shaders.properties resolution precedence");
        assertEquals(256.0F, config.shadowSettings().distance(),
                "shaders.properties distance precedence");
        assertTrue(config.shadowSettings().rawValues().get("shadowMapResolution").equals("1024"),
                "resolution raw value");
        assertTrue(config.shadowSettings().rawValues().get("shadowDistance").equals("256"),
                "distance raw value");
        verifyFixtureBaseline(report, baselinePath, "shadow");
    }

    private static void verifyUnsupportedShadow(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport shadow = report.program("shadow");
        assertTrue(shadow != null, "unsupported shadow program was not discovered");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                shadow.support(), "unsupported shadow support");
        assertTrue(shadow.deviations().contains("SHADOW_SAMPLER_UNSUPPORTED:shadowtex1"),
                "unsupported shadow sampler deviation is missing");
        assertTrue(!report.shouldAttempt("shadow"),
                "unsupported shadow must not execute");
        assertTrue(report.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowDistance"),
                "invalid distance default is missing");
        assertTrue(report.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowMapResolution"),
                "invalid resolution default is missing");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                Files.readString(pack.resolve("shaders/shadow.fsh"), StandardCharsets.UTF_8),
                UniformRegistry.Stage.SHADOW);
        assertTrue(!interfacePlan.executable(), "unsupported shadow interface was accepted");
        UniformRegistry.ProgramInterface colorInput = UniformRegistry.plan(
                "uniform sampler2D texture; uniform sampler2D shadowcolor0;",
                UniformRegistry.Stage.SHADOW);
        assertTrue(colorInput.deviations().contains("SHADOW_COLOR_INPUT_UNSUPPORTED"),
                "shadow color input deviation is missing");
        assertTrue(!colorInput.executable(), "shadow color input was accepted");
        assertTrue(TerrainVertexBridge.convertShadowVertex(
                Files.readString(pack.resolve("shaders/shadow.vsh"), StandardCharsets.UTF_8),
                pack.resolve("shaders/shadow.vsh"),
                Files.readString(pack.resolve("shaders/shadow.fsh"), StandardCharsets.UTF_8)) != null,
                "unsupported fixture vertex was rejected for the wrong reason");
        verifyFixtureBaseline(report, baselinePath, "unsupported_shadow");
    }

    @Test
    void shadowSettingValidation(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "shadowMapResolution=64\nshadowDistance=4096\nshadowMapFov=90\n");
        PackConfig.ShadowSettings settings = PackConfig.parse(List.of(
                new PackProgram("shadow", "#version 120\nconst int shadowMapResolution = 2048;",
                        shaders.resolve("shadow.fsh"), null, null)), shaders).shadowSettings();
        assertEquals(PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION, settings.resolution(),
                "invalid resolution default");
        assertEquals(PackConfig.DEFAULT_SHADOW_DISTANCE, settings.distance(),
                "invalid distance default");
        assertTrue(settings.deviations().contains("SHADOW_SETTING_UNSUPPORTED:shadowMapFov"),
                "unsupported shadow setting");
        assertTrue(settings.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowMapResolution"),
                "invalid resolution deviation");
        assertTrue(settings.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowDistance"),
                "invalid distance deviation");
    }

    private static void assertExtendedFormat() {
        var format = ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN;
        assertEquals(24, format.getVertexSize(), "terrain stride");
        assertEquals(5, format.getElements().size(), "terrain attribute count");
        for (int i = 3; i < 5; i++) {
            var element = format.getElements().get(i);
            assertEquals(VertexFormatElement.Type.INT,
                    element.type(), "generic attribute type " + i);
            assertEquals(VertexFormatElement.Usage.GENERIC,
                    element.usage(), "generic attribute usage " + i);
            assertEquals(1, element.count(), "generic attribute count " + i);
        }
    }

    @Test
    void materialParserEdgeCases(@TempDir Path root) throws IOException {
        try {
            Files.writeString(root.resolve("block.properties"),
                    "block.-7 = diorite\n"
                            + "block.8 = minecraft:dirt\n"
                            + "block.8 = minecraft:dirt\n"
                            + "block.9 = minecraft:oak_planks:axis=x\n"
                            + "block.13 = minecraft:tall_grass:half=lower tall_grass:half=upper\n"
                            + "block.14 = leaves:waterlogged=false:distance=1,2\n"
                            + "block.15 = oak_leaves\n"
                            + "block.10 = %minecraft:logs\n"
                            + "block.40000 = bad\n"
                            + "block.11 = minecraft:stone\n"
                            + "block.12 = minecraft:stone\n");
            PackMaterialResolver.ParseResult result = PackMaterialResolver.parse(root);
            assertEquals(-7, result.resolver().resolveName("diorite"), "signed material id");
            assertEquals(8, result.resolver().resolveName("minecraft:dirt"), "duplicate material id");
            assertEquals(-1, result.resolver().resolveName("minecraft:oak_planks"), "selector mapping");
            assertEquals(-1, result.resolver().resolveName("minecraft:stone"), "conflicting mapping");
            // State selectors, as Iris reads them: BSL and Complementary map
            // tall grass by half and leaves by waterlogged.
            PackMaterialResolver resolver = result.resolver();
            assertEquals(13, resolver.resolveState("minecraft:tall_grass",
                    Map.of("half", "lower")::get), "namespaced selector");
            assertEquals(13, resolver.resolveState("tall_grass",
                    Map.of("half", "upper")::get), "unqualified selector");
            assertEquals(-1, resolver.resolveState("minecraft:tall_grass", Map.<String, String>of()::get),
                    "selector matched a state without the property");
            assertEquals(14, resolver.resolveState("minecraft:leaves",
                    Map.of("waterlogged", "false", "distance", "2")::get), "multi-property selector");
            assertEquals(-1, resolver.resolveState("minecraft:leaves",
                    Map.of("waterlogged", "false", "distance", "3")::get), "selector value list");
            assertEquals(15, resolver.resolveState("minecraft:oak_leaves",
                    Map.of("waterlogged", "true")::get), "plain mapping under a selector-free name");
            assertEquals(-1, resolver.resolveName("minecraft:oak_planks"),
                    "a selector became a plain mapping");
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

    static ConformanceReport probe(Path pack) {
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

    /** A fixture's report must match its entry in the recorded baseline, field by field. */
    private static void verifyFixtureBaseline(ConformanceReport report, Path baselinePath, String fixture)
            throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "baseline is missing: " + baselinePath);
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject baseline = root.getAsJsonObject("fixtures").getAsJsonObject(fixture);
        String label = baselinePath.getFileName() + " " + fixture;
        assertTrue(baseline != null, label + " is missing");
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(), label + " report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject().get("passInventory").toString(),
                label + " pass inventory");
        assertEquals(baseline.get("expectedStages").toString(), expectedStages(report).toString(),
                label + " stages");
        assertEquals(baseline.get("expectedDeviations").toString(), expectedDeviations(report).toString(),
                label + " deviations");
        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedHashes, sourceHashes(report), label + " source hashes");
        assertStable(report, label);
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
}
