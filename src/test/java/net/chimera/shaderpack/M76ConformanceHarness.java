package net.chimera.shaderpack;

import net.chimera.render.vertex.ChimeraVertexFormats;
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

/** Deterministic M7.6 checks for the core family adapter registry. */
public final class M76ConformanceHarness {
    private M76ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path supported = fixtureRoot.resolve("m7_6/families");
        Path unsupported = fixtureRoot.resolve("m7_6/unsupported_families");
        Path baseline = Path.of(System.getProperty(
                "chimera.m76.baseline", fixtureRoot.resolve("baselines/m7_6.json").toString()));

        verifyRegistry();
        verifySupported(supported, baseline);
        verifyUnsupported(unsupported, baseline);
        System.out.println("[chimera] M7.6 family adapter conformance: PASS");
    }

    private static void verifyRegistry() {
        assertTrue(FamilyAdapterRegistry.isExecutableFamily("gbuffers_block"),
                "M7.6 block family is not executable");
        assertTrue(FamilyAdapterRegistry.isExecutableFamily("gbuffers_hand"),
                "M7.6 hand family is not executable");
        assertTrue(FamilyAdapterRegistry.isExecutableFamily("gbuffers_particles"),
                "M7.6 particle family is not executable");
        // M8.5b intentionally promotes the sky family from host fallback to
        // its bounded adapter. Keep the historical harness aware of that
        // deliberate capability addition instead of treating it as a regression.
        assertEquals(UniformRegistry.Stage.SKY,
                FamilyAdapterRegistry.stageFor("gbuffers_skybasic"),
                "M8.5b sky stage");
        assertEquals(UniformRegistry.Stage.BLOCK,
                FamilyAdapterRegistry.stageFor("gbuffers_block"), "M7.6 block stage");
        assertEquals(UniformRegistry.Stage.HAND,
                FamilyAdapterRegistry.stageFor("gbuffers_hand"), "M7.6 hand stage");
        assertEquals(UniformRegistry.Stage.PARTICLE,
                FamilyAdapterRegistry.stageFor("gbuffers_particles"), "M7.6 particle stage");
        assertEquals(FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY,
                FamilyAdapterRegistry.forProgram("gbuffers_hand").vertexContract(),
                "M7.6 hand contract");
        assertEquals(FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY,
                FamilyAdapterRegistry.forProgram("gbuffers_block").vertexContract(),
                "M7.6 block contract");
        assertEquals(FamilyAdapterPlan.VertexContract.HOST_PARTICLE,
                FamilyAdapterRegistry.forProgram("gbuffers_particles").vertexContract(),
                "M7.6 particle contract");
        assertEquals(48, ChimeraVertexFormats.EXTENDED_PARTICLE.getVertexSize(),
                "M7.6 hand format stride");
        assertEquals(56, ChimeraVertexFormats.EXTENDED_ENTITY.getVertexSize(),
                "M7.6 entity format stride");
    }

    private static void verifySupported(Path fixture, Path baseline) throws IOException {
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "M7.6 supported report stability");
        for (String name : List.of("gbuffers_entities", "gbuffers_block", "gbuffers_hand")) {
            PackProgramPlan plan = first.plan().program(name);
            assertTrue(plan != null && plan.executable(),
                    "M7.6 entity-like plan was not executable: " + name
                            + " deviations=" + (plan == null ? "missing" : plan.deviations())
                            + " vertex=" + (plan == null ? "missing" : plan.convertedVertex() != null)
                            + " fragment=" + (plan == null ? "missing" : plan.convertedFragment() != null)
                            + " interface=" + (plan == null ? "missing" : plan.interfacePlan().deviations())
                            + " resolution=" + (plan == null || first.resolution() == null
                            ? "missing" : first.resolution().resolution(name))
                            + " stage=" + (plan == null ? "missing" : plan.interfacePlan().effective(
                            FamilyAdapterRegistry.stageFor(name)).stage()));
            FamilyAdapterPlan.VertexContract expectedContract = FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY;
            assertEquals(expectedContract, plan.familyAdapter().vertexContract(),
                    "M7.6 entity-like contract: " + name);
            assertTrue(plan.convertedVertex() != null && plan.convertedVertex().contains("#version 460"),
                    "M7.6 entity-like vertex was not converted: " + name);
            assertTrue(plan.convertedFragment() != null && plan.convertedFragment().contains("#version 460"),
                    "M7.6 entity-like fragment was not converted: " + name);
            if (name.equals("gbuffers_hand")) {
                assertTrue(plan.convertedVertex().contains("layout(location = 2) in vec2 UV0"),
                        "hand primary vertex must use the actual arm entity UV layout");
                assertTrue(plan.convertedVertex().contains("layout(location = 4) in ivec2 UV2"),
                        "hand primary vertex omitted the arm lightmap input");
            }
            assertTrue(first.report().shouldAttempt(name),
                    "M7.6 entity-like report rejected: " + name);
        }
        PackProgramPlan entityPlan = first.plan().program("gbuffers_entities");
        PackProgramPlan blockPlan = first.plan().program("gbuffers_block");
        assertTrue(entityPlan.convertedVertex() != null
                        && !entityPlan.convertedVertex().contains("Position + ModelOffset"),
                "M7.6 world entity bridge inherited the block ModelOffset");
        assertTrue(blockPlan.convertedVertex() != null
                        && blockPlan.convertedVertex().contains("Position + ModelOffset"),
                "M7.6 block bridge omitted the host ModelOffset");

        PackProgramPlan particles = first.plan().program("gbuffers_particles");
        assertTrue(particles != null && particles.executable(),
                "M7.6 particle plan was not executable: "
                        + (particles == null ? List.of() : particles.deviations()));
        assertEquals(UniformRegistry.Stage.PARTICLE,
                particles.interfacePlan().effective(UniformRegistry.Stage.PARTICLE).stage(),
                "M7.6 particle interface stage");
        assertTrue(particles.convertedVertex().contains("layout(location = 0) in vec3 Position"),
                "M7.6 particle position input");
        assertTrue(particles.convertedFragment().contains("layout(location = 0) in vec4 particleColor"),
                "M7.6 particle varying location");
        assertTrue(first.report().shouldAttempt("gbuffers_particles"),
                "M7.6 particle report rejected");

        PackProgramPlan finalPlan = first.plan().program("final");
        assertTrue(finalPlan != null && finalPlan.executable(),
                "M7.6 supported final pass was not executable");
        assertTrue(finalPlan.convertedFragment() != null
                        && finalPlan.convertedFragment().contains("texcoord"),
                "M7.6 supported final pass was not converted with the fixed varying");

        ConformanceReport.ProgramReport sky = first.report().program("gbuffers_skybasic");
        assertTrue(sky != null, "M7.6 sky family was not inventoried");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED, sky.support(),
                "M7.6 fragment-only sky support status");
        assertTrue(!first.report().shouldAttempt("gbuffers_skybasic"),
                "M7.6 fragment-only sky family remained eligible");
        verifyBaseline(first.report(), baseline, "families");
        if (Boolean.getBoolean("chimera.m76.printBaseline")) {
            printBaseline("families", first.report());
        }
        assertNoAbsolutePaths(first.report(), fixture);
    }

    private static void verifyUnsupported(Path fixture, Path baseline) throws IOException {
        PackProbe.Analysis analysis = PackProbe.analyze(fixture);
        assertTrue(analysis.report().shouldAttempt("final"),
                "M7.6 unrelated final pass was rejected");
        PackProgramPlan finalPlan = analysis.plan().program("final");
        assertTrue(finalPlan != null && finalPlan.executable(),
                "M7.6 unsupported fixture identity final was not executable");
        for (String name : List.of("gbuffers_skybasic", "gbuffers_entities_glowing")) {
            ConformanceReport.ProgramReport program = analysis.report().program(name);
            assertTrue(program != null, "M7.6 unsupported family was not inventoried: " + name);
            assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED, program.support(),
                    "M7.6 unsupported family status: " + name);
            assertTrue(!analysis.report().shouldAttempt(name),
                    "M7.6 unsupported family remained eligible: " + name);
        }
        verifyBaseline(analysis.report(), baseline, "unsupported_families");
        if (Boolean.getBoolean("chimera.m76.printBaseline")) {
            printBaseline("unsupported_families", analysis.report());
        }
        assertNoAbsolutePaths(analysis.report(), fixture);
    }

    private static void verifyBaseline(
            ConformanceReport report, Path baselinePath, String fixtureName) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject entry = root.getAsJsonObject("fixtures").getAsJsonObject(fixtureName);
        assertTrue(entry != null, "M7.6 baseline entry is missing: " + fixtureName);
        String expectedHash = entry.has("reportSha256")
                ? entry.get("reportSha256").getAsString() : "TO_BE_FILLED";
        if (!"TO_BE_FILLED".equals(expectedHash)) {
            assertEquals(expectedHash, report.sha256(), "M7.6 report hash: " + fixtureName);
        }
        if (entry.has("sourceHashes")) {
            Map<String, String> expected = new TreeMap<>();
            for (Map.Entry<String, JsonElement> value
                    : entry.getAsJsonObject("sourceHashes").entrySet()) {
                expected.put(value.getKey(), value.getValue().getAsString());
            }
            assertEquals(expected, sourceHashes(report), "M7.6 source hashes: " + fixtureName);
        }
        if (entry.has("expectedExecutablePrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedExecutablePrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.SUPPORTED
                                    || value.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "M7.6 executable baseline: " + fixtureName);
        }
        if (entry.has("expectedFallbackPrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedFallbackPrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "M7.6 fallback baseline: " + fixtureName);
        }
        if (entry.has("expectedUnsupportedPrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedUnsupportedPrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.UNSUPPORTED)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "M7.6 unsupported baseline: " + fixtureName);
        }
        if (entry.has("expectedDeviations")) {
            assertEquals(strings(entry.getAsJsonArray("expectedDeviations")),
                    allDeviations(report), "M7.6 deviations: " + fixtureName);
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
                "M7.6 report contains an absolute fixture path");
        assertTrue(!json.matches("(?s).*\\b(?:timestamp|generatedAt)\\b.*"),
                "M7.6 report contains a timestamp field");
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
