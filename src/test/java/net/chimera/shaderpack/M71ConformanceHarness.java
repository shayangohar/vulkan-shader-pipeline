package net.chimera.shaderpack;

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
import java.util.TreeSet;

/** Deterministic M7.1 settings and program-resolution checks. */
public final class M71ConformanceHarness {
    private M71ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path fixture = fixtureRoot.resolve("m7_1/resolution");
        Snapshot overworld = inspect(fixture, "minecraft:overworld");
        Snapshot nether = inspect(fixture, "minecraft:the_nether");
        Snapshot end = inspect(fixture, "minecraft:the_end");
        Snapshot custom = inspect(fixture, "custom:moon");

        verifyFixtureContract(overworld, nether, end, custom);
        verifyResolutionRules();
        verifyExternalPack("complementary", "chimera.m71.complementary",
                "chimera.m71.complementaryVersion");
        verifyExternalPack("bsl", "chimera.m71.bsl", "chimera.m71.bslVersion");

        Path baseline = fixtureRoot.resolve("baselines/m7_1.json");
        if (Boolean.getBoolean("chimera.m71.printSnapshot")) {
            System.out.println(snapshotJson(overworld, nether, end, custom));
        } else {
            verifyBaseline(baseline, overworld, nether, end, custom);
        }
        System.out.println("[chimera] M7.1 resolution conformance: PASS");
    }

    private static Snapshot inspect(Path fixture, String dimension) throws IOException {
        try (PackSource.LoadResult loaded = PackSource.loadResult(fixture, dimension)) {
            String folder = loaded.selectedVariantFolder();
            List<String> selectedNames = loaded.programs().stream()
                    .map(PackProgram::name).sorted().toList();
            PackProbe.Analysis analysis = PackProbe.analyze(fixture, loaded);
            ConformanceReport report = analysis.report();
            PackPlan plan = analysis.plan();
            PackSettingsPlan settings = analysis.settings();
            assertEquals("2", settings.defaults().get("QUALITY"),
                    "property define default for " + dimension);
            assertTrue(settings.options().containsKey("BLOOM"),
                    "commented option was not inventoried for " + dimension);
            assertEquals("0", settings.options().get("BLOOM").defaultValue(),
                    "commented option default for " + dimension);
            assertTrue(settings.options().containsKey("M71_EXPOSURE"),
                    "scalar option was not inventoried for " + dimension);
            assertTrue(settings.profiles().containsKey("high")
                            && settings.profiles().containsKey("low"),
                    "profiles were not inventoried for " + dimension);
            assertTrue(settings.enabled("composite") && !settings.enabled("final"),
                    "program enable expressions were not evaluated for " + dimension);
            assertTrue(settings.requiredFeatures().contains("LEGACY_GLSL")
                            && settings.optionalFeatures().contains("MRT"),
                    "feature flags were not inventoried for " + dimension);
            try (PackSource.LoadResult secondLoaded = PackSource.loadResult(fixture, dimension)) {
                assertEquals(report.toJson(), PackProbe.analyze(fixture, secondLoaded).report().toJson(),
                        "report must be deterministic for " + dimension);
            }
            assertEquals(selectedNames.stream().distinct().count(), (long) selectedNames.size(),
                    "duplicate selected programs for " + dimension);
            assertEquals(plan.selectedDimension(), dimension, "plan dimension for " + dimension);
            assertEquals(plan.selectedSourceFolder(), folder, "plan folder for " + dimension);
            assertTrue(!analysis.settings().fingerprint().isBlank(),
                    "settings fingerprint is missing for " + dimension);
            assertTrue(!analysis.resolution().fingerprint().isBlank(),
                    "resolution fingerprint is missing for " + dimension);
            return new Snapshot(dimension, folder, selectedNames, report.sha256(),
                    sourceHashDigest(report), analysis.settings().fingerprint(),
                    analysis.resolution().fingerprint(),
                    analysis.resolution().aliases(), analysis.resolution().missingPrograms(),
                    analysis.resolution().disabledPrograms(), report.deviations(),
                    plan.shouldAttempt("gbuffers_terrain"), plan.shouldAttempt("composite"),
                    plan.shouldAttempt("final"),
                    plan.program("composite") == null
                            ? "" : value(plan.program("composite").stageSource("fragment"), ""));
        }
    }

    private static void verifyFixtureContract(
            Snapshot overworld,
            Snapshot nether,
            Snapshot end,
            Snapshot custom
    ) {
        assertEquals("overworld", overworld.folder(), "explicit overworld source folder");
        assertTrue(overworld.selectedNames().contains("gbuffers_terrain"),
                "overworld terrain source is missing");
        assertTrue(!overworld.selectedNames().contains("gbuffers_textured_lit"),
                "overworld incorrectly merged root source");
        assertTrue(overworld.aliases().stream().anyMatch(value ->
                        value.equals("gbuffers_water->gbuffers_terrain")),
                "water fallback alias is missing");
        assertTrue(overworld.aliases().stream().anyMatch(value ->
                        value.equals("gbuffers_terrain_solid->gbuffers_terrain")),
                "terrain solid fallback alias is missing");
        assertTrue(overworld.disabledNames().contains("final"),
                "disabled final program is missing");
        assertTrue(overworld.terrainEligible(), "fixture terrain was rejected: "
                + overworld.reportDeviations());
        assertTrue(overworld.compositeEligible(), "fixture composite was rejected");
        assertTrue(!overworld.finalEligible(), "disabled final remained eligible");
        assertTrue(overworld.compositeSource().contains("m71Marker"),
                "root-relative or relative include was not prepared");
        assertTrue(overworld.reportDeviations().contains("DUPLICATE_PROGRAM:composite"),
                "duplicate program deviation is missing");
        assertTrue(overworld.settingsFingerprint().length() == 64,
                "settings fingerprint is not SHA-256");
        assertTrue(overworld.resolutionFingerprint().length() == 64,
                "resolution fingerprint is not SHA-256");

        assertEquals("nether", nether.folder(), "explicit Nether source folder");
        assertTrue(nether.selectedNames().contains("gbuffers_textured_lit"),
                "Nether source was not selected");
        assertTrue(!nether.selectedNames().contains("gbuffers_terrain"),
                "Nether incorrectly merged overworld source");

        assertEquals("end", end.folder(), "explicit End source folder");
        assertTrue(end.selectedNames().contains("gbuffers_terrain"),
                "End source was not selected");

        assertEquals("overworld", custom.folder(), "explicit custom-dimension source folder");
        assertEquals(overworld.selectedNames(), custom.selectedNames(),
                "custom dimension did not use its explicit source mapping");
        assertEquals(overworld.sourceHashDigest(), custom.sourceHashDigest(),
                "custom dimension source differs from its mapped folder");
    }

    private static void verifyResolutionRules() {
        PackProgram terrain = new PackProgram("gbuffers_terrain", "", Path.of("terrain.fsh"));
        PackProgram numbered = new PackProgram("composite1", "", Path.of("composite1.fsh"));
        PackResolutionPlan resolution = PackResolutionPlan.build(
                "minecraft:overworld", "", List.of(terrain, numbered), PackSettingsPlan.empty());
        PackProgramResolution water = resolution.resolution("gbuffers_water");
        assertTrue(water != null && water.selectedProgram().equals("gbuffers_terrain"),
                "water did not resolve through its standard parent");
        assertTrue(!water.executable(), "incompatible fallback alias became executable");
        PackProgramResolution exactNumbered = resolution.resolution("composite1");
        assertTrue(exactNumbered != null && exactNumbered.executable(),
                "numbered post pass incorrectly received a fallback rule");
        assertTrue(PackResolutionPlan.fallbackTable().get("composite1") == null,
                "numbered composite pass unexpectedly has a fallback");
        assertTrue(PackConditionals.evaluate("(QUALITY >= 2) && !defined(DISABLED)",
                        Map.of("QUALITY", "2")),
                "shared conditional evaluator failed numeric comparison");
        assertTrue(!PackConditionals.evaluate("defined(MISSING) || false", Map.of()),
                "shared conditional evaluator accepted missing macro");
        assertTrue(PackConditionals.evaluate("UNKNOWN_OPTION || false", Map.of(), true),
                "property unknown-name policy failed");
    }

    private static void verifyExternalPack(String label, String pathKey, String versionKey)
            throws IOException {
        String pathValue = System.getProperty(pathKey);
        String version = System.getProperty(versionKey);
        if (pathValue == null && version == null) {
            return;
        }
        assertTrue(pathValue != null && !pathValue.isBlank(),
                label + " path is required when strict M7.1 mode is enabled");
        assertTrue(version != null && !version.isBlank(),
                label + " version is required when strict M7.1 mode is enabled");
        Path pack = Path.of(pathValue);
        assertTrue(Files.isDirectory(pack) || Files.isRegularFile(pack),
                label + " path does not exist: " + pack);
        PackProbe.Analysis analysis = PackProbe.analyze(pack);
        boolean eligible = false;
        for (ConformanceReport.ProgramReport program : analysis.report().programs()) {
            boolean planned = analysis.plan().shouldAttempt(program.name());
            assertEquals(planned, analysis.report().shouldAttempt(program.name()),
                    label + " static eligibility disagreement: " + program.name());
            eligible |= planned;
        }
        assertTrue(eligible, label + " has no program in the current Chimera contract");
        assertTrue(analysis.settings().fingerprint().length() == 64,
                label + " settings fingerprint is invalid");
        assertTrue(analysis.resolution().fingerprint().length() == 64,
                label + " resolution fingerprint is invalid");
    }

    private static void verifyBaseline(
            Path baseline,
            Snapshot overworld,
            Snapshot nether,
            Snapshot end,
            Snapshot custom
    ) throws IOException {
        assertTrue(Files.isRegularFile(baseline), "M7.1 baseline is missing: " + baseline);
        JsonObject root = JsonParser.parseString(Files.readString(baseline, StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("m7_1/resolution", root.get("fixture").getAsString(),
                "M7.1 baseline fixture");
        verifySnapshot(root.getAsJsonObject("overworld"), overworld, "overworld");
        verifySnapshot(root.getAsJsonObject("nether"), nether, "nether");
        verifySnapshot(root.getAsJsonObject("end"), end, "end");
        verifySnapshot(root.getAsJsonObject("custom"), custom, "custom");
    }

    private static void verifySnapshot(JsonObject expected, Snapshot actual, String label) {
        assertEquals(expected.get("dimension").getAsString(), actual.dimension(),
                label + " baseline dimension");
        assertEquals(expected.get("folder").getAsString(), actual.folder(),
                label + " baseline folder");
        assertEquals(expected.get("reportSha256").getAsString(), actual.reportSha256(),
                label + " baseline report");
        assertEquals(expected.get("sourceHashDigest").getAsString(), actual.sourceHashDigest(),
                label + " baseline sources");
        assertEquals(expected.get("settingsFingerprint").getAsString(), actual.settingsFingerprint(),
                label + " baseline settings");
        assertEquals(expected.get("resolutionFingerprint").getAsString(), actual.resolutionFingerprint(),
                label + " baseline resolution");
        assertEquals(expected.get("aliases").toString(), jsonStrings(actual.aliases()),
                label + " baseline aliases");
        assertEquals(expected.get("missingPrograms").toString(), jsonStrings(actual.missingPrograms()),
                label + " baseline missing programs");
        assertEquals(expected.get("disabledPrograms").toString(), jsonStrings(actual.disabledNames()),
                label + " baseline disabled programs");
    }

    private static String snapshotJson(
            Snapshot overworld,
            Snapshot nether,
            Snapshot end,
            Snapshot custom
    ) {
        StringBuilder result = new StringBuilder();
        result.append("{\n  \"formatVersion\": 1,\n  \"fixture\": \"m7_1/resolution\",\n");
        appendSnapshot(result, "overworld", overworld, true);
        appendSnapshot(result, "nether", nether, true);
        appendSnapshot(result, "end", end, true);
        appendSnapshot(result, "custom", custom, false);
        result.append("}\n");
        return result.toString();
    }

    private static void appendSnapshot(StringBuilder result, String key, Snapshot snapshot,
                                       boolean comma) {
        result.append("  \"").append(key).append("\": {\n")
                .append("    \"dimension\": \"").append(snapshot.dimension()).append("\",\n")
                .append("    \"folder\": \"").append(snapshot.folder()).append("\",\n")
                .append("    \"reportSha256\": \"").append(snapshot.reportSha256()).append("\",\n")
                .append("    \"sourceHashDigest\": \"").append(snapshot.sourceHashDigest()).append("\",\n")
                .append("    \"settingsFingerprint\": \"").append(snapshot.settingsFingerprint()).append("\",\n")
                .append("    \"resolutionFingerprint\": \"").append(snapshot.resolutionFingerprint()).append("\",\n")
                .append("    \"aliases\": ").append(jsonStrings(snapshot.aliases())).append(",\n")
                .append("    \"missingPrograms\": ").append(jsonStrings(snapshot.missingPrograms())).append(",\n")
                .append("    \"disabledPrograms\": ").append(jsonStrings(snapshot.disabledNames())).append("\n")
                .append("  }").append(comma ? "," : "").append("\n");
    }

    private static String jsonStrings(Iterable<String> values) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            sorted.add(value);
        }
        StringBuilder result = new StringBuilder("[");
        boolean first = true;
        for (String value : sorted) {
            if (!first) {
                result.append(",");
            }
            first = false;
            result.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return result.append(']').toString();
    }

    private static String sourceHashDigest(ConformanceReport report) {
        Map<String, String> hashes = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            program.sourceHashes().forEach((path, hash) ->
                    hashes.put(program.name() + ":" + path, hash));
        }
        StringBuilder canonical = new StringBuilder();
        hashes.forEach((key, value) -> canonical.append(key).append('=').append(value).append('\n'));
        return ConformanceReport.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String value(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private record Snapshot(
            String dimension,
            String folder,
            List<String> selectedNames,
            String reportSha256,
            String sourceHashDigest,
            String settingsFingerprint,
            String resolutionFingerprint,
            List<String> aliases,
            List<String> missingPrograms,
            List<String> disabledNames,
            List<String> reportDeviations,
            boolean terrainEligible,
            boolean compositeEligible,
            boolean finalEligible,
            String compositeSource
    ) {
        private Snapshot {
            selectedNames = List.copyOf(new ArrayList<>(selectedNames));
            aliases = List.copyOf(new TreeSet<>(aliases));
            missingPrograms = List.copyOf(new TreeSet<>(missingPrograms));
            disabledNames = List.copyOf(new TreeSet<>(disabledNames));
            reportDeviations = List.copyOf(new TreeSet<>(reportDeviations));
        }
    }
}
