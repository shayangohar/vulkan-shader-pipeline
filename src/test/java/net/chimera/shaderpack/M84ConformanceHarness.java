package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.render.vertex.ChimeraVertexFormats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic M8.4 checks for the common world-family adapters. */
public final class M84ConformanceHarness {
    private M84ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path supported = fixtureRoot.resolve("m8_4/families");
        Path unsupported = fixtureRoot.resolve("m8_4/unsupported_families");
        Path baseline = Path.of(System.getProperty(
                "chimera.m84.baseline", fixtureRoot.resolve("baselines/m8_4.json").toString()));

        verifyRegistry();
        verifyHostShadowDepthContract();
        verifySupported(supported, baseline);
        verifyUnsupported(unsupported, baseline);
        if (Boolean.getBoolean("chimera.m84.printBaseline")) {
            printBaseline("families", PackProbe.analyze(supported).report());
            printBaseline("unsupported_families", PackProbe.analyze(unsupported).report());
        }
        System.out.println("[chimera] M8.4 common world-family conformance: PASS");
    }

    private static void verifyRegistry() {
        List<String> executable = List.of(
                "gbuffers_entities_translucent",
                "gbuffers_entities_glowing",
                "gbuffers_damagedblock",
                "gbuffers_hand_water",
                "gbuffers_particles_translucent",
                "gbuffers_weather");
        for (String name : executable) {
            assertTrue(FamilyAdapterRegistry.isExecutableFamily(name),
                    "M8.4 family is not executable: " + name);
        }
        assertEquals(UniformRegistry.Stage.ENTITY,
                FamilyAdapterRegistry.stageFor("gbuffers_entities_translucent"),
                "M8.4 translucent entity stage");
        assertEquals(UniformRegistry.Stage.ENTITY,
                FamilyAdapterRegistry.stageFor("gbuffers_entities_glowing"),
                "M8.4 glowing entity stage");
        assertEquals(UniformRegistry.Stage.BLOCK,
                FamilyAdapterRegistry.stageFor("gbuffers_damagedblock"),
                "M8.4 damaged block stage");
        assertEquals(UniformRegistry.Stage.HAND,
                FamilyAdapterRegistry.stageFor("gbuffers_hand_water"),
                "M8.4 water hand stage");
        assertEquals(UniformRegistry.Stage.PARTICLE,
                FamilyAdapterRegistry.stageFor("gbuffers_particles_translucent"),
                "M8.4 translucent particle stage");
        assertEquals(UniformRegistry.Stage.PARTICLE,
                FamilyAdapterRegistry.stageFor("gbuffers_weather"),
                "M8.4 weather stage");
        assertEquals(FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY,
                FamilyAdapterRegistry.forProgram("gbuffers_entities_translucent").vertexContract(),
                "M8.4 translucent entity contract");
        assertEquals(FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY,
                FamilyAdapterRegistry.forProgram("gbuffers_hand_water").vertexContract(),
                "M8.4 water hand contract");
        assertEquals(FamilyAdapterPlan.VertexContract.HOST_PARTICLE,
                FamilyAdapterRegistry.forProgram("gbuffers_weather").vertexContract(),
                "M8.4 weather contract");
        assertEquals(56, ChimeraVertexFormats.EXTENDED_ENTITY.getVertexSize(),
                "M8.4 entity stride");
        assertEquals(48, ChimeraVertexFormats.EXTENDED_PARTICLE.getVertexSize(),
                "M8.4 particle stride");
    }

    private static void verifyHostShadowDepthContract() throws IOException {
        Path shader = Path.of("src/main/resources/assets/chimera/shaders/chimera_terrain/chimera_terrain.fsh");
        String source = Files.readString(shader, StandardCharsets.UTF_8);
        assertTrue(source.contains("projCoords.xy = projCoords.xy * 0.5 + 0.5"),
                "M8.4 host shadow must keep Vulkan zero-to-one Z");
        assertTrue(source.contains("float storedDepth = 1.0 - texture(ShadowMap"),
                "M8.4 host shadow must convert reversed depth");
        assertTrue(!source.contains("projCoords = projCoords * 0.5 + 0.5"),
                "M8.4 host shadow must not remap Z as OpenGL depth");
    }

    private static void verifySupported(Path fixture, Path baseline) throws IOException {
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "M8.4 supported report stability");

        for (String name : List.of(
                "gbuffers_entities", "gbuffers_entities_translucent", "gbuffers_entities_glowing",
                "gbuffers_block", "gbuffers_hand", "gbuffers_hand_water",
                "gbuffers_particles", "gbuffers_particles_translucent", "gbuffers_weather")) {
            PackProgramPlan plan = first.plan().program(name);
            assertTrue(plan != null && plan.executable(),
                    "M8.4 family plan was not executable: " + name
                            + " deviations=" + (plan == null ? "missing" : plan.deviations()));
            assertTrue(plan.convertedVertex() != null
                            && plan.convertedVertex().contains("#version 460"),
                    "M8.4 vertex was not converted: " + name);
            assertTrue(plan.convertedFragment() != null
                            && plan.convertedFragment().contains("#version 460"),
                    "M8.4 fragment was not converted: " + name);
            assertTrue(first.report().shouldAttempt(name),
                    "M8.4 family report rejected: " + name);
        }

        PackProgramPlan finalPlan = first.plan().program("final");
        assertTrue(finalPlan != null && finalPlan.executable(),
                "M8.4 final pass was not executable");
        assertTrue(first.report().shouldAttempt("final"),
                "M8.4 final report rejected");

        ConformanceReport.ProgramReport damaged = first.report().program("gbuffers_damagedblock");
        assertTrue(damaged != null, "M8.4 damaged-block program was not inventoried");
        assertTrue(damaged.deviations().contains("DAMAGED_BLOCK_HOST_FORMAT_INSTALLED"),
                "M8.4 damaged-block host adapter was not named");
        assertTrue(first.report().shouldAttempt("gbuffers_damagedblock"),
                "M8.4 damaged-block BLOCK adapter was not eligible");

        ConformanceReport.ProgramReport sky = first.report().program("gbuffers_skybasic");
        assertTrue(sky != null, "M8.4 unsupported sky was not inventoried");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED, sky.support(),
                "M8.4 sky support status");
        assertTrue(!first.report().shouldAttempt("gbuffers_skybasic"),
                "M8.4 sky lane remained eligible");
        verifyBaseline(first.report(), baseline, "families");
        assertNoAbsolutePaths(first.report(), fixture);
    }

    private static void verifyUnsupported(Path fixture, Path baseline) throws IOException {
        PackProbe.Analysis analysis = PackProbe.analyze(fixture);
        assertTrue(analysis.report().shouldAttempt("final"),
                "M8.4 unrelated final pass was rejected");
        for (String name : List.of("gbuffers_entities")) {
            ConformanceReport.ProgramReport program = analysis.report().program(name);
            assertTrue(program != null, "M8.4 fallback family was not inventoried: " + name);
            assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK, program.support(),
                    "M8.4 fallback family status: " + name);
            assertTrue(!analysis.report().shouldAttempt(name),
                    "M8.4 fallback family remained eligible: " + name);
        }
        ConformanceReport.ProgramReport sky = analysis.report().program("gbuffers_skybasic");
        assertTrue(analysis.report().shouldAttempt("gbuffers_damagedblock"),
                "unused entity attributes must not reject the host BLOCK damage shader");
        assertTrue(sky != null, "M8.4 unsupported sky was not inventoried");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED, sky.support(),
                "M8.4 unsupported sky status");
        verifyBaseline(analysis.report(), baseline, "unsupported_families");
        assertNoAbsolutePaths(analysis.report(), fixture);
    }

    private static void verifyBaseline(
            ConformanceReport report, Path baselinePath, String fixtureName) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject fixtures = root.getAsJsonObject("fixtures");
        JsonObject entry = fixtures == null ? null : fixtures.getAsJsonObject(fixtureName);
        assertTrue(entry != null, "M8.4 baseline entry is missing: " + fixtureName);
        String expectedHash = entry.has("reportSha256")
                ? entry.get("reportSha256").getAsString() : "TO_BE_FILLED";
        if (!"TO_BE_FILLED".equals(expectedHash)) {
            assertEquals(expectedHash, report.sha256(), "M8.4 report hash: " + fixtureName);
        }
        if (entry.has("sourceHashes")
                && !entry.getAsJsonObject("sourceHashes").entrySet().isEmpty()) {
            Map<String, String> expected = new TreeMap<>();
            for (Map.Entry<String, JsonElement> value
                    : entry.getAsJsonObject("sourceHashes").entrySet()) {
                expected.put(value.getKey(), value.getValue().getAsString());
            }
            assertEquals(expected, sourceHashes(report),
                    "M8.4 source hashes: " + fixtureName);
        }
        assertPrograms(entry, report, "expectedExecutablePrograms",
                ConformanceReport.SupportStatus.SUPPORTED,
                ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION);
        assertPrograms(entry, report, "expectedFallbackPrograms",
                ConformanceReport.SupportStatus.IDENTITY_FALLBACK);
        assertPrograms(entry, report, "expectedUnsupportedPrograms",
                ConformanceReport.SupportStatus.UNSUPPORTED);
        if (entry.has("expectedDeviations")) {
            assertEquals(strings(entry.getAsJsonArray("expectedDeviations")),
                    allDeviations(report), "M8.4 deviations: " + fixtureName);
        }
    }

    private static void assertPrograms(
            JsonObject entry,
            ConformanceReport report,
            String key,
            ConformanceReport.SupportStatus... statuses
    ) {
        if (!entry.has(key)) {
            return;
        }
        List<String> actual = report.programs().stream()
                .filter(value -> contains(statuses, value.support()))
                .map(ConformanceReport.ProgramReport::name)
                .sorted()
                .toList();
        assertEquals(strings(entry.getAsJsonArray(key)), actual, "M8.4 " + key);
    }

    private static boolean contains(ConformanceReport.SupportStatus[] values,
                                    ConformanceReport.SupportStatus actual) {
        for (ConformanceReport.SupportStatus value : values) {
            if (value == actual) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> result = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            program.sourceHashes().forEach(result::putIfAbsent);
        }
        return result;
    }

    private static List<String> allDeviations(ConformanceReport report) {
        List<String> result = new ArrayList<>(report.deviations());
        for (ConformanceReport.ProgramReport program : report.programs()) {
            result.addAll(program.deviations());
        }
        return result.stream().distinct().sorted().toList();
    }

    private static void printBaseline(String name, ConformanceReport report) {
        System.out.println("[chimera] M8.4 baseline " + name
                + " reportSha256=" + report.sha256());
        sourceHashes(report).forEach((path, hash) ->
                System.out.println("[chimera] M8.4 baseline " + name
                        + " source " + path + "=" + hash));
        System.out.println("[chimera] M8.4 baseline " + name
                + " deviations=" + allDeviations(report));
    }

    private static List<String> strings(JsonArray values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static void assertNoAbsolutePaths(ConformanceReport report, Path fixture) {
        String json = report.toJson();
        assertTrue(!json.contains(fixture.toAbsolutePath().toString()),
                "M8.4 report contains an absolute fixture path");
        assertTrue(!json.matches("(?s).*\\b(?:timestamp|generatedAt)\\b.*"),
                "M8.4 report contains a timestamp field");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
