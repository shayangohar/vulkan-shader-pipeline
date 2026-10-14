package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Test;

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

/** The common world-family adapters. */
final class WorldFamilyAdapterTest {
    @Test
    void worldFamilyAdapters() throws Exception {
        Path fixtureRoot = TestPacks.fixtures();
        Path supported = fixtureRoot.resolve("m8_4/families");
        Path unsupported = fixtureRoot.resolve("m8_4/unsupported_families");
        Path baseline = TestPacks.baseline("m8_4");

        verifySupported(supported, baseline);
        verifyUnsupported(unsupported, baseline);
        if (TestPacks.printBaselines()) {
            printBaseline("families", PackProbe.analyze(supported).report());
            printBaseline("unsupported_families", PackProbe.analyze(unsupported).report());
        }
    }

    @Test
    void registry() {
        List<String> executable = List.of(
                "gbuffers_entities_translucent",
                "gbuffers_spidereyes",
                "gbuffers_damagedblock",
                "gbuffers_hand_water",
                "gbuffers_particles_translucent",
                "gbuffers_weather");
        for (String name : executable) {
            assertTrue(FamilyAdapterRegistry.isExecutableFamily(name),
                    "family is not executable: " + name);
        }
        assertEquals(UniformRegistry.Stage.ENTITY,
                FamilyAdapterRegistry.stageFor("gbuffers_entities_translucent"),
                "translucent entity stage");
        assertEquals(UniformRegistry.Stage.ENTITY,
                FamilyAdapterRegistry.stageFor("gbuffers_spidereyes"),
                "spider-eyes entity stage");
        assertEquals(UniformRegistry.Stage.BLOCK,
                FamilyAdapterRegistry.stageFor("gbuffers_damagedblock"),
                "damaged block stage");
        assertEquals(UniformRegistry.Stage.HAND,
                FamilyAdapterRegistry.stageFor("gbuffers_hand_water"),
                "water hand stage");
        assertEquals(UniformRegistry.Stage.PARTICLE,
                FamilyAdapterRegistry.stageFor("gbuffers_particles_translucent"),
                "translucent particle stage");
        assertEquals(UniformRegistry.Stage.PARTICLE,
                FamilyAdapterRegistry.stageFor("gbuffers_weather"),
                "weather stage");
        assertEquals(FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY,
                FamilyAdapterRegistry.forProgram("gbuffers_entities_translucent").vertexContract(),
                "translucent entity contract");
        assertEquals(FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY,
                FamilyAdapterRegistry.forProgram("gbuffers_hand_water").vertexContract(),
                "water hand contract");
        assertEquals(FamilyAdapterPlan.VertexContract.HOST_PARTICLE,
                FamilyAdapterRegistry.forProgram("gbuffers_weather").vertexContract(),
                "weather contract");
        assertEquals(56, ChimeraVertexFormats.EXTENDED_ENTITY.getVertexSize(),
                "entity stride");
        assertEquals(48, ChimeraVertexFormats.EXTENDED_PARTICLE.getVertexSize(),
                "particle stride");
    }

    @Test
    void hostShadowDepthContract() throws IOException {
        Path shader = Path.of("src/main/resources/assets/chimera/shaders/chimera_terrain/chimera_terrain.fsh");
        String source = Files.readString(shader, StandardCharsets.UTF_8);
        assertTrue(source.contains("projCoords.xy = projCoords.xy * 0.5 + 0.5"),
                "host shadow must keep Vulkan zero-to-one Z");
        assertTrue(source.contains("float storedDepth = 1.0 - texture(ShadowMap"),
                "host shadow must convert reversed depth");
        assertTrue(!source.contains("projCoords = projCoords * 0.5 + 0.5"),
                "host shadow must not remap Z as OpenGL depth");
    }

    private static void verifySupported(Path fixture, Path baseline) throws IOException {
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "supported report stability");

        for (String name : List.of(
                "gbuffers_entities", "gbuffers_entities_translucent", "gbuffers_spidereyes",
                "gbuffers_block", "gbuffers_hand", "gbuffers_hand_water",
                "gbuffers_particles", "gbuffers_particles_translucent", "gbuffers_weather")) {
            PackProgramPlan plan = first.plan().program(name);
            assertTrue(plan != null && plan.executable(),
                    "family plan was not executable: " + name
                            + " deviations=" + (plan == null ? "missing" : plan.deviations()));
            assertTrue(plan.convertedVertex() != null
                            && plan.convertedVertex().contains("#version 460"),
                    "vertex was not converted: " + name);
            assertTrue(plan.convertedFragment() != null
                            && plan.convertedFragment().contains("#version 460"),
                    "fragment was not converted: " + name);
            assertTrue(first.report().shouldAttempt(name),
                    "family report rejected: " + name);
        }

        PackProgramPlan finalPlan = first.plan().program("final");
        assertTrue(finalPlan != null && finalPlan.executable(),
                "final pass was not executable");
        assertTrue(first.report().shouldAttempt("final"),
                "final report rejected");

        ConformanceReport.ProgramReport damaged = first.report().program("gbuffers_damagedblock");
        assertTrue(damaged != null, "damaged-block program was not inventoried");
        assertTrue(damaged.deviations().contains("DAMAGED_BLOCK_HOST_FORMAT_INSTALLED"),
                "damaged-block host adapter was not named");
        assertTrue(first.report().shouldAttempt("gbuffers_damagedblock"),
                "damaged-block BLOCK adapter was not eligible");

        ConformanceReport.ProgramReport sky = first.report().program("gbuffers_skybasic");
        assertTrue(sky != null, "unsupported sky was not inventoried");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED, sky.support(),
                "sky support status");
        assertTrue(!first.report().shouldAttempt("gbuffers_skybasic"),
                "sky lane remained eligible");
        verifyBaseline(first.report(), baseline, "families");
        assertNoAbsolutePaths(first.report(), fixture);
    }

    private static void verifyUnsupported(Path fixture, Path baseline) throws IOException {
        PackProbe.Analysis analysis = PackProbe.analyze(fixture);
        assertTrue(analysis.report().shouldAttempt("final"),
                "unrelated final pass was rejected");
        for (String name : List.of("gbuffers_entities")) {
            ConformanceReport.ProgramReport program = analysis.report().program(name);
            assertTrue(program != null, "fallback family was not inventoried: " + name);
            assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK, program.support(),
                    "fallback family status: " + name);
            assertTrue(!analysis.report().shouldAttempt(name),
                    "fallback family remained eligible: " + name);
        }
        ConformanceReport.ProgramReport sky = analysis.report().program("gbuffers_skybasic");
        assertTrue(analysis.report().shouldAttempt("gbuffers_damagedblock"),
                "unused entity attributes must not reject the host BLOCK damage shader");
        assertTrue(sky != null, "unsupported sky was not inventoried");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED, sky.support(),
                "unsupported sky status");
        verifyBaseline(analysis.report(), baseline, "unsupported_families");
        assertNoAbsolutePaths(analysis.report(), fixture);
    }

    private static void verifyBaseline(
            ConformanceReport report, Path baselinePath, String fixtureName) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject fixtures = root.getAsJsonObject("fixtures");
        JsonObject entry = fixtures == null ? null : fixtures.getAsJsonObject(fixtureName);
        assertTrue(entry != null, "baseline entry is missing: " + fixtureName);
        String expectedHash = entry.has("reportSha256")
                ? entry.get("reportSha256").getAsString() : "TO_BE_FILLED";
        if (!"TO_BE_FILLED".equals(expectedHash)) {
            assertEquals(expectedHash, report.sha256(), "report hash: " + fixtureName);
        }
        if (entry.has("sourceHashes")
                && !entry.getAsJsonObject("sourceHashes").entrySet().isEmpty()) {
            Map<String, String> expected = new TreeMap<>();
            for (Map.Entry<String, JsonElement> value
                    : entry.getAsJsonObject("sourceHashes").entrySet()) {
                expected.put(value.getKey(), value.getValue().getAsString());
            }
            assertEquals(expected, sourceHashes(report),
                    "source hashes: " + fixtureName);
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
                    allDeviations(report), "deviations: " + fixtureName);
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
        assertEquals(strings(entry.getAsJsonArray(key)), actual, key);
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
                "report contains an absolute fixture path");
        assertTrue(!json.matches("(?s).*\\b(?:timestamp|generatedAt)\\b.*"),
                "report contains a timestamp field");
    }
}
