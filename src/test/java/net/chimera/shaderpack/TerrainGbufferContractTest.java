package net.chimera.shaderpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic M8.5a terrain and G-buffer contract checks. */
final class TerrainGbufferContractTest {
    @Test
    void fixture() throws Exception {
        Path fixture = TestPacks.fixture("m8_5/terrain_gbuffers");
        Path baseline = TestPacks.baseline("m8_5");

        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "M8.5 report is not deterministic");

        PackProgramPlan terrain = require(first.plan(), "gbuffers_terrain");
        assertTrue(terrain.executable(), "M8.5 terrain plan is not executable: " + terrain.deviations());
        assertTrue(first.report().shouldAttempt("gbuffers_terrain"),
                "M8.5 terrain report rejected the executable plan");
        assertTrue(terrain.geometryOutputPlan() != null,
                "M8.5 geometry output plan is missing");
        assertEquals(List.of(0, 6), terrain.geometryOutputPlan().targetSlots(),
                "M8.5 terrain target route");
        assertTrue(terrain.convertedVertex() != null
                        && countVersions(terrain.convertedVertex()) == 1,
                "M8.5 terrain vertex conversion is not a single generated version");
        assertTrue(terrain.convertedFragment() != null
                        && countVersions(terrain.convertedFragment()) == 1
                        && terrain.convertedFragment().contains("chimeraFragColor0")
                        && terrain.convertedFragment().contains("chimeraFragColor1"),
                "M8.5 terrain MRT output conversion is incomplete");
        assertTrue(terrain.convertedVertex().contains("layout(location = ")
                        && terrain.convertedFragment().contains("layout(location = "),
                "M8.5 shared varying locations are missing");

        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                first.plan().programs(), first.config(), first.plan().resources(),
                2560, 1440, 8, 16384);
        assertTrue(graph.targets().stream().anyMatch(value -> value.index() == 6),
                "M8.5 target graph did not allocate colortex6 for geometry output");
        assertTrue(graph.steps().stream().noneMatch(value -> value.programName().equals("gbuffers_terrain")),
                "M8.5 geometry was incorrectly added to post ordering");
        assertNoUnstableFields(graph.snapshot(), fixture);
        verifyBaseline(first, baseline);
    }

    @Test
    @Tag("packs")
    void complementary() throws IOException {
        verifyExternalPack("Complementary", TestPacks.complementary());
    }

    @Test
    @Tag("packs")
    void bsl() throws IOException {
        verifyExternalPack("BSL", TestPacks.bsl());
    }

    private static void verifyExternalPack(String label, Path path) throws IOException {
        PackProbe.Analysis analysis = PackProbe.analyze(path);
        PackProgramPlan terrain = analysis.plan().program("gbuffers_terrain");
        assertTrue(terrain != null, label + " terrain program was not discovered");
        assertTrue(terrain.executable() && analysis.report().shouldAttempt("gbuffers_terrain"),
                label + " terrain is not conversion-eligible: " + terrain.deviations());
        assertTrue(terrain.convertedVertex() != null && terrain.convertedFragment() != null,
                label + " terrain translation is missing");
        assertTrue(!GlslTokenRewriter.containsIdentifier(terrain.convertedVertex(), "texture2DLod"),
                label + " terrain vertex retained texture2DLod");
        assertTrue(!containsUnboundSampler(terrain.convertedVertex()),
                label + " terrain vertex retained an unbound sampler");
        if (label.equals("BSL")) {
            assertTrue(!terrain.convertedFragment().matches(
                            "(?s).*float\\s+shadow0\\s*=\\s*vec4\\(texture\\(shadowtex.*"),
                    label + " terrain shadow comparison retained a vector shadow sample");
        }
        for (String constant : List.of("shadowMapResolution", "shadowDistance", "sunPathRotation")) {
            assertTrue(countDeclarations(terrain.convertedFragment(), constant) <= 1,
                    label + " terrain duplicated pack constant: " + constant);
        }
        System.out.println("[chimera] M8.5a " + label + " terrain: installed-eligible outputs="
                + terrain.geometryOutputPlan().targetSlots());
    }

    private static void verifyBaseline(PackProbe.Analysis analysis, Path baseline) throws IOException {
        assertTrue(Files.isRegularFile(baseline), "M8.5 baseline is missing");
        JsonObject root = JsonParser.parseString(
                Files.readString(baseline, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject entry = root.getAsJsonObject("fixtures").getAsJsonObject("terrain_gbuffers");
        assertTrue(entry != null, "M8.5 terrain baseline entry is missing");
        String expectedReport = entry.get("reportSha256").getAsString();
        assertEquals(expectedReport, analysis.report().sha256(), "M8.5 report hash");
        Map<String, String> expectedSources = new TreeMap<>();
        for (Map.Entry<String, JsonElement> value
                : entry.getAsJsonObject("sourceHashes").entrySet()) {
            expectedSources.put(value.getKey(), value.getValue().getAsString());
        }
        assertEquals(expectedSources, sourceHashes(analysis.report()), "M8.5 source hashes");
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> result = new TreeMap<>();
        report.programs().forEach(program -> program.sourceHashes().forEach(result::putIfAbsent));
        return result;
    }

    private static void assertNoUnstableFields(String text, Path fixture) {
        assertTrue(!text.contains(fixture.toAbsolutePath().toString()),
                "M8.5 graph snapshot contains an absolute path");
        assertTrue(!text.matches("(?s).*\\b(?:timestamp|generatedAt)\\b.*"),
                "M8.5 graph snapshot contains a timestamp");
    }

    private static int countVersions(String source) {
        return (int) Pattern.compile("(?im)^\\s*#version\\b")
                .matcher(source == null ? "" : source).results().count();
    }

    private static boolean containsUnboundSampler(String source) {
        String withoutComments = (source == null ? "" : source)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
        return Pattern.compile("(?m)^\\s*uniform\\s+sampler")
                .matcher(withoutComments).find();
    }

    private static int countDeclarations(String source, String name) {
        String withoutComments = (source == null ? "" : source)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
        return (int) Pattern.compile(
                        "(?m)^\\s*(?:const\\s+)?(?:int|float)\\s+"
                                + Pattern.quote(name) + "\\s*=")
                .matcher(withoutComments).results().count();
    }

    private static PackProgramPlan require(PackPlan plan, String name) {
        PackProgramPlan result = plan == null ? null : plan.program(name);
        if (result == null) throw new AssertionError("M8.5 missing program: " + name);
        return result;
    }
}
