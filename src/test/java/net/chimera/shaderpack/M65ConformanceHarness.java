package net.chimera.shaderpack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Strict M6.5 qualification checks for the exact Complementary and BSL packs. */
public final class M65ConformanceHarness {
    private static final List<String> STATIC_KEYS = List.of(
            "logicalName", "version", "fullPackFingerprint", "reportSha256",
            "sourceHashes", "executablePrograms", "fallbackPrograms", "unsupportedPrograms",
            "deviationCodes");
    private static final List<String> EVIDENCE_KEYS = List.of(
            "expectedChimeraCaptures", "expectedIrisReferenceCaptures", "runtimeEvidence",
            "comparisonClaims");

    private M65ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path baseline = Path.of(System.getProperty(
                "chimera.m65.baseline", fixtureRoot.resolve("baselines/m6_5.json").toString()));
        JsonObject baselineRoot = readObject(baseline, "M6.5 baseline");
        verifyBaselineHeader(baselineRoot);
        verifyM61Fixture(fixtureRoot);

        List<PackSpec> packs = List.of(
                new PackSpec("complementary", required("chimera.m65.complementary"),
                        required("chimera.m65.complementaryVersion")),
                new PackSpec("bsl", required("chimera.m65.bsl"),
                        required("chimera.m65.bslVersion")));

        if (Boolean.getBoolean("chimera.m65.emitBaseline")) {
            emitBaseline(packs);
            return;
        }
        JsonObject baselinePacks = childObject(baselineRoot, "packs", "M6.5 baseline packs");
        for (PackSpec pack : packs) {
            verifyPack(pack, childObject(baselinePacks, pack.label(),
                    "M6.5 baseline entry for " + pack.label()));
        }
        System.out.println("[chimera] M6.5 real-pack parity qualification: PASS");
    }

    private static void verifyPack(PackSpec spec, JsonObject expected) throws IOException {
        JsonObject actual = snapshot(spec, true);
        for (String key : STATIC_KEYS) {
            assertEquals(expected.get(key).toString(), actual.get(key).toString(),
                    "M6.5 " + spec.label() + " " + key);
        }
        for (String key : EVIDENCE_KEYS) {
            assertNonEmptyRelativeArray(expected, key,
                    "M6.5 " + spec.label() + " " + key);
        }
        verifyEquivalentDirectoryLoading(spec, actual);
    }

    private static JsonObject snapshot(PackSpec spec, boolean requireExecutable) throws IOException {
        assertTrue(Files.isDirectory(spec.path()) || Files.isRegularFile(spec.path()),
                "M6.5 " + spec.label() + " path does not exist: " + spec.path());
        String fingerprint = PackFingerprint.sha256(spec.path());
        assertEquals(fingerprint, PackFingerprint.sha256(spec.path()),
                "M6.5 " + spec.label() + " fingerprint stability");

        PackProbe.Analysis analysis;
        Path extracted = null;
        try (PackSource.LoadResult loaded = PackSource.loadResult(spec.path())) {
            List<String> loadedNames = loaded.programs().stream()
                    .map(PackProgram::name).toList();
            assertEquals(loadedNames.stream().distinct().count(), (long) loadedNames.size(),
                    "M6.5 " + spec.label() + " duplicate runtime programs");
            extracted = Files.isRegularFile(spec.path()) ? loaded.shadersDir() : null;
            analysis = PackProbe.analyze(spec.path(), loaded);
            verifyPlanEligibility(spec, analysis);
        }
        if (extracted != null) {
            assertTrue(!Files.exists(extracted),
                    "M6.5 " + spec.label() + " ZIP extraction was not cleaned up");
        }

        ConformanceReport report = analysis.report();
        ConformanceReport second = PackProbe.probe(spec.path());
        assertEquals(report.toJson(), second.toJson(),
                "M6.5 " + spec.label() + " report stability");
        assertEquals(report.sha256(), second.sha256(),
                "M6.5 " + spec.label() + " report hash stability");
        assertNoUnstableReportData(report, spec.path(), spec.label());

        List<String> executable = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        TreeSet<String> deviations = new TreeSet<>(report.deviations());
        for (ConformanceReport.ProgramReport program : report.programs()) {
            boolean eligible = program.support() == ConformanceReport.SupportStatus.SUPPORTED
                    || program.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION;
            assertEquals(eligible, report.shouldAttempt(program.name()),
                    "M6.5 " + spec.label() + " eligibility: " + program.name());
            assertEquals(ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED,
                    program.runtime(), "M6.5 " + spec.label() + " static runtime: " + program.name());
            deviations.addAll(program.deviations());
            if (eligible) {
                executable.add(program.name());
            } else if (program.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK) {
                fallback.add(program.name());
            } else if (program.support() == ConformanceReport.SupportStatus.UNSUPPORTED) {
                unsupported.add(program.name());
            }
        }
        if (requireExecutable) {
            assertTrue(!executable.isEmpty(), "M6.5 " + spec.label()
                    + " has no executable program in the Chimera subset");
        }

        JsonObject result = new JsonObject();
        result.addProperty("logicalName", PackProbe.logicalPackName(spec.path()));
        result.addProperty("version", spec.version());
        result.addProperty("fullPackFingerprint", fingerprint);
        result.addProperty("reportSha256", report.sha256());
        result.add("sourceHashes", stringMap(sourceHashes(report)));
        result.add("executablePrograms", strings(executable));
        result.add("fallbackPrograms", strings(fallback));
        result.add("unsupportedPrograms", strings(unsupported));
        result.add("deviationCodes", strings(deviations));
        result.add("expectedChimeraCaptures", strings(List.of(
                "captures/m6_5_" + spec.label() + "_chimera.rdc")));
        result.add("expectedIrisReferenceCaptures", strings(List.of(
                "captures/m6_5_" + spec.label() + "_iris.rdc")));
        result.add("runtimeEvidence", strings(List.of(
                "logs/m6_5_" + spec.label() + "_latest.log",
                "RenderDoc CLI analysis",
                "performance/m6_5_" + spec.label() + ".json")));
        result.add("comparisonClaims", strings(List.of(
                "STRUCTURAL_POST_ORDER", "RESOURCE_TARGETS", "FINAL_IMAGE_FEATURES",
                "LIFECYCLE_STABILITY", "PERFORMANCE_MEDIANS")));
        return result;
    }

    private static void verifyPlanEligibility(PackSpec spec, PackProbe.Analysis analysis) {
        List<String> reportNames = analysis.report().programs().stream()
                .map(ConformanceReport.ProgramReport::name).sorted().toList();
        List<String> planNames = analysis.plan().programs().stream()
                .map(PackProgramPlan::name).sorted().toList();
        assertTrue(reportNames.containsAll(planNames),
                "M6.5 " + spec.label() + " plan contains an unreported program");
        for (ConformanceReport.ProgramReport program : analysis.report().programs()) {
            PackProgramPlan planned = analysis.plan().program(program.name());
            if (planned == null) {
                assertTrue(!analysis.report().shouldAttempt(program.name()),
                        "M6.5 " + spec.label() + " unplanned program was executable: "
                                + program.name());
            } else {
                assertEquals(planned.executable(), analysis.report().shouldAttempt(program.name()),
                        "M6.5 " + spec.label() + " plan eligibility: " + program.name());
            }
        }
    }

    private static void verifyM61Fixture(Path fixtureRoot) throws IOException {
        Path fixture = fixtureRoot.resolve("m6_1/program_plan");
        Path baselinePath = fixtureRoot.resolve("baselines/m6_1.json");
        JsonObject baseline = readObject(baselinePath, "M6.1 baseline");
        ConformanceReport report = PackProbe.probe(fixture);
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M6.5 M6.1 fixture report hash");
        assertEquals(jsonStrings(baseline.getAsJsonArray("expectedExecutablePrograms")),
                names(report, ConformanceReport.SupportStatus.SUPPORTED,
                        ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION),
                "M6.5 M6.1 executable baseline");
        assertEquals(jsonStrings(baseline.getAsJsonArray("expectedFallbackPrograms")),
                names(report, ConformanceReport.SupportStatus.IDENTITY_FALLBACK),
                "M6.5 M6.1 fallback baseline");
        assertEquals(jsonStrings(baseline.getAsJsonArray("expectedDeviations")),
                allDeviations(report), "M6.5 M6.1 deviation baseline");
        Map<String, String> expectedSources = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedSources.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedSources, sourceHashes(report), "M6.5 M6.1 source hashes");
    }

    private static List<String> names(
            ConformanceReport report, ConformanceReport.SupportStatus... statuses) {
        List<ConformanceReport.SupportStatus> accepted = List.of(statuses);
        return report.programs().stream()
                .filter(program -> accepted.contains(program.support()))
                .map(ConformanceReport.ProgramReport::name)
                .sorted()
                .toList();
    }

    private static List<String> allDeviations(ConformanceReport report) {
        TreeSet<String> result = new TreeSet<>(report.deviations());
        for (ConformanceReport.ProgramReport program : report.programs()) {
            result.addAll(program.deviations());
        }
        return result.stream().toList();
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> result = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            program.sourceHashes().forEach(result::putIfAbsent);
        }
        return result;
    }

    private static void verifyEquivalentDirectoryLoading(PackSpec spec, JsonObject archiveSnapshot)
            throws IOException {
        if (!Files.isRegularFile(spec.path())) {
            return;
        }
        Path root = Files.createTempDirectory("chimera-m65-directory-");
        try {
            Path packDirectory = root.resolve(PackProbe.logicalPackName(spec.path()));
            Path destination = packDirectory.resolve("shaders");
            Path extracted;
            try (PackSource.LoadResult loaded = PackSource.loadResult(spec.path())) {
                extracted = loaded.shadersDir();
                copyTree(extracted, destination);
            }
            assertTrue(!Files.exists(extracted),
                    "M6.5 " + spec.label() + " archive extraction cleanup");
            JsonObject directorySnapshot = snapshotFromReport(
                    PackProbe.probe(packDirectory), spec);
            for (String key : List.of("logicalName", "reportSha256", "executablePrograms",
                    "fallbackPrograms", "unsupportedPrograms", "deviationCodes", "sourceHashes")) {
                assertEquals(archiveSnapshot.get(key).toString(), directorySnapshot.get(key).toString(),
                        "M6.5 " + spec.label() + " ZIP/directory " + key);
            }
        } finally {
            deleteTree(root);
        }
    }

    private static JsonObject snapshotFromReport(ConformanceReport report, PackSpec spec) {
        List<String> executable = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        TreeSet<String> deviations = new TreeSet<>(report.deviations());
        for (ConformanceReport.ProgramReport program : report.programs()) {
            deviations.addAll(program.deviations());
            if (program.support() == ConformanceReport.SupportStatus.SUPPORTED
                    || program.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION) {
                executable.add(program.name());
            } else if (program.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK) {
                fallback.add(program.name());
            } else {
                unsupported.add(program.name());
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("logicalName", PackProbe.logicalPackName(spec.path()));
        result.addProperty("reportSha256", report.sha256());
        result.add("sourceHashes", stringMap(sourceHashes(report)));
        result.add("executablePrograms", strings(executable));
        result.add("fallbackPrograms", strings(fallback));
        result.add("unsupportedPrograms", strings(unsupported));
        result.add("deviationCodes", strings(deviations));
        return result;
    }

    private static void emitBaseline(List<PackSpec> packs) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);
        root.addProperty("fingerprintAlgorithm", "sha256(sorted relative file names, lengths, and bytes)");
        root.addProperty("referenceBackend", "Sodium + Iris");
        root.addProperty("performanceMethod", "FPS_PLUS_RENDERDOC_COUNTS");
        root.addProperty("captureDirectory", "captures");
        JsonObject entries = new JsonObject();
        for (PackSpec pack : packs) {
            entries.add(pack.label(), snapshot(pack, false));
        }
        root.add("packs", entries);
        System.out.println(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root));
    }

    private static void verifyBaselineHeader(JsonObject baseline) {
        assertEquals("1", baseline.get("formatVersion").toString(),
                "M6.5 baseline format version");
        assertEquals("\"Sodium + Iris\"", baseline.get("referenceBackend").toString(),
                "M6.5 baseline reference backend");
        assertEquals("\"FPS_PLUS_RENDERDOC_COUNTS\"", baseline.get("performanceMethod").toString(),
                "M6.5 baseline performance method");
        assertEquals("\"captures\"", baseline.get("captureDirectory").toString(),
                "M6.5 baseline capture directory");
    }

    private static void assertNonEmptyRelativeArray(JsonObject object, String key, String message) {
        JsonElement value = object.get(key);
        assertTrue(value != null && value.isJsonArray() && value.getAsJsonArray().size() > 0,
                message + " is missing");
        for (JsonElement element : value.getAsJsonArray()) {
            String path = element.getAsString();
            assertTrue(!Path.of(path).isAbsolute() && !path.contains(":\\") && !path.contains(":/"),
                    message + " contains an absolute path: " + path);
        }
    }

    private static void assertNoUnstableReportData(
            ConformanceReport report, Path pack, String label) {
        String json = report.toJson();
        assertTrue(!json.contains(pack.toAbsolutePath().normalize().toString()),
                "M6.5 " + label + " report contains an absolute pack path");
        assertTrue(!json.matches("(?s).*\\b(?:timestamp|createdAt|updatedAt)\\b.*"),
                "M6.5 " + label + " report contains timestamp data");
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new AssertionError("Missing required M6.5 property: -D" + property);
        }
        return value;
    }

    private static JsonObject readObject(Path path, String label) throws IOException {
        assertTrue(Files.isRegularFile(path), label + " is missing: " + path);
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject childObject(JsonObject parent, String key, String message) {
        JsonElement value = parent.get(key);
        assertTrue(value != null && value.isJsonObject(), message + " is missing");
        return value.getAsJsonObject();
    }

    private static JsonArray strings(Iterable<String> values) {
        JsonArray result = new JsonArray();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static JsonObject stringMap(Map<String, String> values) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            result.addProperty(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static List<String> jsonStrings(JsonArray array) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : array) {
            result.add(value.getAsString());
        }
        return result;
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record PackSpec(String label, String pathValue, String version) {
        private Path path() {
            return Path.of(pathValue);
        }
    }
}
