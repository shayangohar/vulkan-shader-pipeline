package net.chimera.shaderpack;

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

/** The core family adapter registry. */
final class CoreFamilyAdapterTest {
    @Test
    void coreFamilyAdapters() throws Exception {
        Path fixtureRoot = TestPacks.fixtures();
        Path supported = fixtureRoot.resolve("m7_6/families");
        Path unsupported = fixtureRoot.resolve("m7_6/unsupported_families");
        Path baseline = TestPacks.baseline("m7_6");

        verifySupported(supported, baseline);
        verifyUnsupported(unsupported, baseline);
    }

    @Test
    void registry() {
        assertTrue(FamilyAdapterRegistry.isExecutableFamily("gbuffers_block"),
                "block family is not executable");
        assertTrue(FamilyAdapterRegistry.isExecutableFamily("gbuffers_hand"),
                "hand family is not executable");
        assertTrue(FamilyAdapterRegistry.isExecutableFamily("gbuffers_particles"),
                "particle family is not executable");
        // The sky family has its own bounded adapter rather than host fallback.
        assertEquals(UniformRegistry.Stage.SKY,
                FamilyAdapterRegistry.stageFor("gbuffers_skybasic"),
                "sky stage");
        assertEquals(UniformRegistry.Stage.BLOCK,
                FamilyAdapterRegistry.stageFor("gbuffers_block"), "block stage");
        assertEquals(UniformRegistry.Stage.HAND,
                FamilyAdapterRegistry.stageFor("gbuffers_hand"), "hand stage");
        assertEquals(UniformRegistry.Stage.PARTICLE,
                FamilyAdapterRegistry.stageFor("gbuffers_particles"), "particle stage");
        assertEquals(FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY,
                FamilyAdapterRegistry.forProgram("gbuffers_hand").vertexContract(),
                "hand contract");
        assertEquals(FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY,
                FamilyAdapterRegistry.forProgram("gbuffers_block").vertexContract(),
                "block contract");
        assertEquals(FamilyAdapterPlan.VertexContract.HOST_PARTICLE,
                FamilyAdapterRegistry.forProgram("gbuffers_particles").vertexContract(),
                "particle contract");
        assertEquals(48, ChimeraVertexFormats.EXTENDED_PARTICLE.getVertexSize(),
                "hand format stride");
        assertEquals(56, ChimeraVertexFormats.EXTENDED_ENTITY.getVertexSize(),
                "entity format stride");
    }

    private static void verifySupported(Path fixture, Path baseline) throws IOException {
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "supported report stability");
        for (String name : List.of("gbuffers_entities", "gbuffers_block", "gbuffers_hand")) {
            assertEntityLikePlan(first, name);
        }
        PackProgramPlan entityPlan = first.plan().program("gbuffers_entities");
        PackProgramPlan blockPlan = first.plan().program("gbuffers_block");
        assertTrue(entityPlan.convertedVertex() != null
                        && !entityPlan.convertedVertex().contains("Position + ModelOffset"),
                "world entity bridge inherited the block ModelOffset");
        assertTrue(blockPlan.convertedVertex() != null
                        && blockPlan.convertedVertex().contains("Position + ModelOffset"),
                "block bridge omitted the host ModelOffset");

        PackProgramPlan particles = first.plan().program("gbuffers_particles");
        assertTrue(particles != null && particles.executable(),
                "particle plan was not executable: "
                        + (particles == null ? List.of() : particles.deviations()));
        assertEquals(UniformRegistry.Stage.PARTICLE,
                particles.interfacePlan().effective(UniformRegistry.Stage.PARTICLE).stage(),
                "particle interface stage");
        assertTrue(particles.convertedVertex().contains("layout(location = 0) in vec3 Position"),
                "particle position input");
        assertTrue(particles.convertedFragment().contains("layout(location = 0) in vec4 particleColor"),
                "particle varying location");
        assertTrue(first.report().shouldAttempt("gbuffers_particles"),
                "particle report rejected");

        PackProgramPlan finalPlan = first.plan().program("final");
        assertTrue(finalPlan != null && finalPlan.executable(),
                "supported final pass was not executable");
        assertTrue(finalPlan.convertedFragment() != null
                        && finalPlan.convertedFragment().contains("texcoord"),
                "supported final pass was not converted with the fixed varying");

        ConformanceReport.ProgramReport sky = first.report().program("gbuffers_skybasic");
        assertTrue(sky != null, "sky family was not inventoried");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED, sky.support(),
                "fragment-only sky support status");
        assertTrue(!first.report().shouldAttempt("gbuffers_skybasic"),
                "fragment-only sky family remained eligible");
        verifyBaseline(first.report(), baseline, "families");
        if (TestPacks.printBaselines()) {
            printBaseline("families", first.report());
        }
        assertNoAbsolutePaths(first.report(), fixture);
    }

    /** An entity-like program converts onto the extended entity layout and stays eligible. */
    private static void assertEntityLikePlan(PackProbe.Analysis analysis, String name) {
        PackProgramPlan plan = analysis.plan().program(name);
        assertTrue(plan != null && plan.executable(),
                "entity-like plan was not executable: " + name
                        + " deviations=" + (plan == null ? "missing" : plan.deviations())
                        + " vertex=" + (plan == null ? "missing" : plan.convertedVertex() != null)
                        + " fragment=" + (plan == null ? "missing" : plan.convertedFragment() != null)
                        + " interface=" + (plan == null ? "missing" : plan.interfacePlan().deviations())
                        + " resolution=" + (plan == null || analysis.resolution() == null
                        ? "missing" : analysis.resolution().resolution(name))
                        + " stage=" + (plan == null ? "missing" : plan.interfacePlan().effective(
                        FamilyAdapterRegistry.stageFor(name)).stage()));
        FamilyAdapterPlan.VertexContract expectedContract = FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY;
        assertEquals(expectedContract, plan.familyAdapter().vertexContract(),
                "entity-like contract: " + name);
        assertTrue(plan.convertedVertex() != null && plan.convertedVertex().contains("#version 460"),
                "entity-like vertex was not converted: " + name);
        assertTrue(plan.convertedFragment() != null && plan.convertedFragment().contains("#version 460"),
                "entity-like fragment was not converted: " + name);
        if (name.equals("gbuffers_hand")) {
            assertTrue(plan.convertedVertex().contains("layout(location = 2) in vec2 UV0"),
                    "hand primary vertex must use the actual arm entity UV layout");
            assertTrue(plan.convertedVertex().contains("layout(location = 4) in ivec2 UV2"),
                    "hand primary vertex omitted the arm lightmap input");
        }
        assertTrue(analysis.report().shouldAttempt(name),
                "entity-like report rejected: " + name);
    }

    private static void verifyUnsupported(Path fixture, Path baseline) throws IOException {
        PackProbe.Analysis analysis = PackProbe.analyze(fixture);
        assertTrue(analysis.report().shouldAttempt("final"),
                "unrelated final pass was rejected");
        PackProgramPlan finalPlan = analysis.plan().program("final");
        assertTrue(finalPlan != null && finalPlan.executable(),
                "unsupported fixture identity final was not executable");
        for (String name : List.of("gbuffers_skybasic", "gbuffers_entities_glowing")) {
            ConformanceReport.ProgramReport program = analysis.report().program(name);
            assertTrue(program != null, "unsupported family was not inventoried: " + name);
            assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED, program.support(),
                    "unsupported family status: " + name);
            assertTrue(!analysis.report().shouldAttempt(name),
                    "unsupported family remained eligible: " + name);
        }
        verifyBaseline(analysis.report(), baseline, "unsupported_families");
        if (TestPacks.printBaselines()) {
            printBaseline("unsupported_families", analysis.report());
        }
        assertNoAbsolutePaths(analysis.report(), fixture);
    }

    private static void verifyBaseline(
            ConformanceReport report, Path baselinePath, String fixtureName) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject entry = root.getAsJsonObject("fixtures").getAsJsonObject(fixtureName);
        assertTrue(entry != null, "baseline entry is missing: " + fixtureName);
        String expectedHash = entry.has("reportSha256")
                ? entry.get("reportSha256").getAsString() : "TO_BE_FILLED";
        if (!"TO_BE_FILLED".equals(expectedHash)) {
            assertEquals(expectedHash, report.sha256(), "report hash: " + fixtureName);
        }
        if (entry.has("sourceHashes")) {
            Map<String, String> expected = new TreeMap<>();
            for (Map.Entry<String, JsonElement> value
                    : entry.getAsJsonObject("sourceHashes").entrySet()) {
                expected.put(value.getKey(), value.getValue().getAsString());
            }
            assertEquals(expected, sourceHashes(report), "source hashes: " + fixtureName);
        }
        if (entry.has("expectedExecutablePrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedExecutablePrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.SUPPORTED
                                    || value.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "executable baseline: " + fixtureName);
        }
        if (entry.has("expectedFallbackPrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedFallbackPrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "fallback baseline: " + fixtureName);
        }
        if (entry.has("expectedUnsupportedPrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedUnsupportedPrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.UNSUPPORTED)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "unsupported baseline: " + fixtureName);
        }
        if (entry.has("expectedDeviations")) {
            assertEquals(strings(entry.getAsJsonArray("expectedDeviations")),
                    allDeviations(report), "deviations: " + fixtureName);
        }
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> result = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            program.sourceHashes().forEach(result::putIfAbsent);
        }
        return result;
    }

    private static void printBaseline(String name, ConformanceReport report) {
        System.out.println("[chimera] M7.6 baseline " + name + " reportSha256=" + report.sha256());
        sourceHashes(report).forEach((path, hash) ->
                System.out.println("[chimera] M7.6 baseline " + name + " source " + path + "=" + hash));
        System.out.println("[chimera] M7.6 baseline " + name + " deviations=" + allDeviations(report));
    }

    private static List<String> allDeviations(ConformanceReport report) {
        List<String> result = new ArrayList<>(report.deviations());
        for (ConformanceReport.ProgramReport program : report.programs()) {
            result.addAll(program.deviations());
        }
        return result.stream().distinct().sorted().toList();
    }

    private static List<String> strings(Iterable<JsonElement> values) {
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
