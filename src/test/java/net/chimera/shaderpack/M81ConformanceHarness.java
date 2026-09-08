package net.chimera.shaderpack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.render.vertex.ChimeraVertexFormats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic checks for the M8.1 terrain and water material bridge. */
public final class M81ConformanceHarness {
    private M81ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path supportedRoot = root.resolve("m8_1/terrain_water");
        PackProbe.Analysis analysis = PackProbe.analyze(supportedRoot);
        PackPlan plan = analysis.plan();
        assertTrue(plan != null, "M8.1 fixture plan is missing");
        assertTrue(plan.terrainMaterial().modern(), "modern terrain material plan was not selected");
        assertTrue(plan.terrainMaterial().separateAo(), "separateAo did not select the AO word");
        assertTrue(plan.terrainMaterial().stride() == 40, "modern AO stride is not 40 bytes");
        assertTrue(ChimeraVertexFormats.terrainFormat(plan.terrainMaterial()).getVertexSize() == 40,
                "modern AO vertex format is not 40 bytes");

        verifyModernProgram(plan, "gbuffers_terrain");
        verifyModernProgram(plan, "gbuffers_water");
        assertTrue(plan.shouldAttempt("gbuffers_terrain"), "modern terrain was rejected by shouldAttempt");
        assertTrue(plan.shouldAttempt("gbuffers_water"), "modern water was rejected by shouldAttempt");

        Path unsupportedRoot = root.resolve("m8_1/unsupported_terrain");
        PackProbe.Analysis unsupported = PackProbe.analyze(unsupportedRoot);
        PackProgramPlan unsupportedPlan = require(unsupported.plan(), "gbuffers_terrain");
        assertTrue(!unsupportedPlan.executable(), "unsupported modern terrain was installed");
        assertTrue(unsupportedPlan.deviations().stream().anyMatch(value ->
                        value.contains("UNSUPPORTED") || value.startsWith("TRANSLATION_UNSUPPORTED:")),
                "unsupported modern terrain has no named fallback");
        assertTrue(!unsupported.plan().shouldAttempt("gbuffers_terrain"),
                "unsupported modern terrain still passes shouldAttempt");

        if (Boolean.getBoolean("chimera.m81.printBaseline")) {
            printBaseline("terrain_water", analysis);
            printBaseline("unsupported_terrain", unsupported);
            return;
        }
        verifyBaseline(analysis, root.resolve("baselines/m8_1.json"), "terrain_water");
        verifyBaseline(unsupported, root.resolve("baselines/m8_1.json"), "unsupported_terrain");

        verifyFormatWidths();
        verifyLegacyWidth();
        System.out.println("[chimera] M8.1 terrain and water conformance: PASS");
    }

    private static void verifyBaseline(
            PackProbe.Analysis analysis, Path baselinePath, String fixtureName) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "M8.1 baseline is missing");
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject fixtures = root.getAsJsonObject("fixtures");
        assertTrue(fixtures != null && fixtures.has(fixtureName),
                "M8.1 baseline entry is missing: " + fixtureName);
        JsonObject entry = fixtures.getAsJsonObject(fixtureName);
        String reportHash = entry.get("reportSha256").getAsString();
        assertEquals(reportHash, analysis.report().sha256(),
                "M8.1 report hash: " + fixtureName);
        Map<String, String> expectedSources = new TreeMap<>();
        for (Map.Entry<String, JsonElement> value
                : entry.getAsJsonObject("sourceHashes").entrySet()) {
            expectedSources.put(value.getKey(), value.getValue().getAsString());
        }
        assertEquals(expectedSources, sourceHashes(analysis.report()),
                "M8.1 source hashes: " + fixtureName);
        assertEquals(strings(entry.getAsJsonArray("expectedExecutablePrograms")),
                programsWithStatus(analysis.report(), ConformanceReport.SupportStatus.SUPPORTED,
                        ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION),
                "M8.1 executable programs: " + fixtureName);
        assertEquals(strings(entry.getAsJsonArray("expectedFallbackPrograms")),
                programsWithStatus(analysis.report(), ConformanceReport.SupportStatus.IDENTITY_FALLBACK),
                "M8.1 fallback programs: " + fixtureName);
        assertEquals(strings(entry.getAsJsonArray("expectedUnsupportedPrograms")),
                programsWithStatus(analysis.report(), ConformanceReport.SupportStatus.UNSUPPORTED),
                "M8.1 unsupported programs: " + fixtureName);
        assertEquals(strings(entry.getAsJsonArray("expectedDeviations")),
                allDeviations(analysis.report()),
                "M8.1 deviations: " + fixtureName);
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> result = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            program.sourceHashes().forEach(result::putIfAbsent);
        }
        return result;
    }

    private static java.util.List<String> programsWithStatus(
            ConformanceReport report, ConformanceReport.SupportStatus... statuses) {
        java.util.Set<ConformanceReport.SupportStatus> expected = java.util.Set.of(statuses);
        return report.programs().stream()
                .filter(value -> expected.contains(value.support()))
                .map(ConformanceReport.ProgramReport::name)
                .sorted().toList();
    }

    private static java.util.List<String> allDeviations(ConformanceReport report) {
        return report.programs().stream()
                .flatMap(value -> value.deviations().stream())
                .distinct().sorted().toList();
    }

    private static void printBaseline(String fixtureName, PackProbe.Analysis analysis) {
        JsonObject entry = new JsonObject();
        entry.addProperty("reportSha256", analysis.report().sha256());
        JsonObject hashes = new JsonObject();
        sourceHashes(analysis.report()).forEach(hashes::addProperty);
        entry.add("sourceHashes", hashes);
        entry.add("expectedExecutablePrograms", jsonStrings(programsWithStatus(analysis.report(),
                ConformanceReport.SupportStatus.SUPPORTED,
                ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)));
        entry.add("expectedFallbackPrograms", jsonStrings(programsWithStatus(analysis.report(),
                ConformanceReport.SupportStatus.IDENTITY_FALLBACK)));
        entry.add("expectedUnsupportedPrograms", jsonStrings(programsWithStatus(analysis.report(),
                ConformanceReport.SupportStatus.UNSUPPORTED)));
        entry.add("expectedDeviations", jsonStrings(allDeviations(analysis.report())));
        System.out.println("[chimera] M8.1 baseline " + fixtureName + "="
                + new GsonBuilder().setPrettyPrinting().create().toJson(entry));
    }

    private static JsonArray jsonStrings(java.util.List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static java.util.List<String> strings(JsonArray values) {
        java.util.List<String> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static void verifyModernProgram(PackPlan plan, String name) {
        PackProgramPlan program = require(plan, name);
        assertTrue(program.executable(), name + " modern program was rejected: " + program.deviations()
                + " interface=" + program.interfacePlan().deviations());
        assertTrue(program.terrainMaterial().modern(), name + " did not retain its modern material plan");
        assertTrue(program.convertedVertex() != null
                        && program.convertedVertex().startsWith("#version 460"),
                name + " modern vertex was not normalized");
        assertEquals(1, countVersionDirectives(program.convertedVertex()),
                name + " modern vertex contains duplicate version directives");
        assertTrue(program.convertedVertex().contains("inMidTexCoord")
                        && program.convertedVertex().contains("inMidBlock")
                        && program.convertedVertex().contains("inTangent"),
                name + " material inputs are missing from the vertex bridge");
        assertTrue(program.convertedVertex().contains("layout(location = 0) out"),
                name + " shared varying locations were not emitted");
        assertTrue(program.convertedFragment() != null
                        && program.convertedFragment().contains("layout(location = 0) out vec4 fragColor"),
                name + " modern fragment output was not generated");
        assertEquals(1, countVersionDirectives(program.convertedFragment()),
                name + " modern fragment contains duplicate or misplaced version directives");
        assertTrue(program.convertedFragment().contains("layout(location = 0) in"),
                name + " shared fragment varying location was not emitted");
        assertTrue(program.deviations().contains(name.equals("gbuffers_water")
                        ? "MODERN_WATER_VERTEX_BRIDGE" : "MODERN_TERRAIN_VERTEX_BRIDGE"),
                name + " modern bridge deviation is missing");
    }

    private static void verifyFormatWidths() {
        TerrainMaterialPlan modern = new TerrainMaterialPlan(true, true, true, true, false, java.util.List.of());
        TerrainMaterialPlan ao = new TerrainMaterialPlan(true, true, true, true, true, java.util.List.of());
        assertTrue(ChimeraVertexFormats.terrainFormat(modern).getVertexSize() == 36,
                "modern terrain format is not 36 bytes");
        assertTrue(ChimeraVertexFormats.terrainFormat(ao).getVertexSize() == 40,
                "modern AO format is not 40 bytes");
        assertTrue(ChimeraVertexFormats.terrainFormat(modern)
                        .getElementAttributeNames().contains("MidBlock"),
                "modern format omitted MidBlock");
        var modernElements = ChimeraVertexFormats.terrainFormat(modern).getElements();
        assertEquals(com.mojang.blaze3d.vertex.VertexFormatElement.Type.INT,
                modernElements.get(6).type(), "modern midpoint is not a VulkanMod scalar INT");
        assertEquals(com.mojang.blaze3d.vertex.VertexFormatElement.Type.BYTE,
                modernElements.get(7).type(), "modern normal frame is not a packed BYTE word");
    }

    private static int countVersionDirectives(String source) {
        return java.util.regex.Pattern.compile("(?im)^\\s*#version\\b")
                .matcher(source == null ? "" : source)
                .results()
                .mapToInt(ignored -> 1)
                .sum();
    }

    private static void verifyLegacyWidth() {
        assertTrue(ChimeraVertexFormats.terrainFormat(TerrainMaterialPlan.legacy()).getVertexSize() == 24,
                "legacy terrain format changed from 24 bytes");
    }

    private static PackProgramPlan require(PackPlan plan, String name) {
        PackProgramPlan value = plan == null ? null : plan.program(name);
        if (value == null) {
            throw new AssertionError("M8.1 missing program: " + name);
        }
        return value;
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
