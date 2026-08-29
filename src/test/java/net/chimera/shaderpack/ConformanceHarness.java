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

/**
 * Small dependency-free conformance check for the locked M5.1, M5.2, and M5.3 boundary.
 * Gradle runs this class before a normal build.
 */
public final class ConformanceHarness {
    private static final List<String> REQUIRED_PROGRAMS = List.of(
            "gbuffers_terrain", "composite", "final");

    private ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        verifySimplex(fixtureRoot.resolve("simplex"), fixtureRoot.resolve("baselines/simplex.json"));
        verifySupported(fixtureRoot.resolve("m5_1/optifine"), "optifine");
        verifySupported(fixtureRoot.resolve("m5_1/iris"), "iris");
        verifyUnsupported(fixtureRoot.resolve("m5_1/unsupported"));
        verifyM52Material(fixtureRoot.resolve("m5_2/material"), fixtureRoot.resolve("baselines/m5_2.json"));
        verifyM52Unsupported(fixtureRoot.resolve("m5_2/unsupported_vertex"), fixtureRoot.resolve("baselines/m5_2.json"));
        verifyM53LiveUniforms(fixtureRoot.resolve("m5_3/live_uniforms"), fixtureRoot.resolve("baselines/m5_3.json"));
        verifyM53Unsupported(fixtureRoot.resolve("m5_3/unsupported_uniform"), fixtureRoot.resolve("baselines/m5_3.json"));
        System.out.println("[chimera] M5.1, M5.2, and M5.3 conformance harness: PASS");
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
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED,
                numbered.support(), "numbered pass support");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED,
                compute.support(), "compute stage support");
        assertTrue(compute.deviations().contains("UNSUPPORTED_PACK_STAGE"),
                "compute stage deviation");
        assertTrue(report.deviations().contains("SETTING_NOT_APPLIED:customImage0"),
                "custom image deviation");
        assertTrue(report.deviations().contains("SETTING_NOT_APPLIED:iris.features.required"),
                "feature flag deviation");
        assertTrue(!report.shouldAttempt("gbuffers_terrain"), "MRT terrain must not execute");
        assertTrue(!report.shouldAttempt("composite1"), "numbered pass must not execute");
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
