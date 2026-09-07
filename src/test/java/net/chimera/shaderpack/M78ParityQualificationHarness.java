package net.chimera.shaderpack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M7.8 qualification for exact real packs. Static eligibility, runtime
 * installation, RenderDoc execution, and visual parity remain separate.
 */
public final class M78ParityQualificationHarness {
    private static final Pattern INSTALLED = Pattern.compile(
            "\\[chimera\\] pack ([^:]+): ok\\b");
    private static final Pattern FALLBACK = Pattern.compile(
            "\\[chimera\\] pack ([^:]+): fallback=IDENTITY\\b");
    private static final Pattern SUMMARY = Pattern.compile(
            "conformance summary: reportSha256=([0-9a-f]{64}) installed=(\\d+), fallback=(\\d+)");
    private static final Pattern DIMENSION = Pattern.compile(
            "pack dimension variant installed: ([^ ]+) \\(([^)]+)\\)");

    private M78ParityQualificationHarness() {}

    public static void main(String[] args) throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path baseline = Path.of(System.getProperty(
                "chimera.m78.baseline", fixtureRoot.resolve("baselines/m7_8.json").toString()));
        JsonObject expected = readObject(baseline, "M7.8 baseline");
        verifyHeader(expected);

        List<PackSpec> packs = List.of(
                new PackSpec("complementary", requiredPath("chimera.m78.complementary"),
                        requiredString("chimera.m78.complementaryVersion"),
                        requiredEvidence("chimera.m78.complementaryLog"),
                        requiredEvidence("chimera.m78.complementaryCapture"),
                        optionalEvidence("chimera.m78.irisComplementaryCapture"),
                        optionalEvidence("chimera.m78.irisComplementaryLog")),
                new PackSpec("bsl", requiredPath("chimera.m78.bsl"),
                        requiredString("chimera.m78.bslVersion"),
                        requiredEvidence("chimera.m78.bslLog"),
                        requiredEvidence("chimera.m78.bslCapture"),
                        optionalEvidence("chimera.m78.irisBslCapture"),
                        optionalEvidence("chimera.m78.irisBslLog")));

        JsonObject expectedPacks = childObject(expected, "packs", "M7.8 packs");
        JsonObject actualRoot = new JsonObject();
        actualRoot.addProperty("formatVersion", 1);
        actualRoot.addProperty("milestone", "M7.8");
        actualRoot.addProperty("referenceBackend", "Sodium + Iris");
        actualRoot.add("packs", new JsonObject());
        JsonObject actualPacks = actualRoot.getAsJsonObject("packs");

        for (PackSpec pack : packs) {
            PackSnapshot snapshot = snapshot(pack, fixtureRoot, expectedPacks.getAsJsonObject(pack.label()));
            actualPacks.add(pack.label(), snapshot.json());
        }

        String output = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(actualRoot);
        if (Boolean.getBoolean("chimera.m78.emitBaseline")) {
            System.out.println(output);
            return;
        }

        System.out.println(output);
        System.out.println("[chimera] M7.8 parity qualification: PASS");
    }

    private static PackSnapshot snapshot(
            PackSpec pack, Path fixtureRoot, JsonObject expected) throws IOException {
        assertTrue(Files.isDirectory(pack.path()) || Files.isRegularFile(pack.path()),
                "M7.8 " + pack.label() + " pack path does not exist: " + pack.path());
        String fingerprint = PackFingerprint.sha256(pack.path());
        assertEquals(fingerprint, PackFingerprint.sha256(pack.path()),
                "M7.8 " + pack.label() + " fingerprint stability");

        PackProbe.Analysis analysis = PackProbe.analyze(pack.path());
        ConformanceReport report = analysis.report();
        ConformanceReport second = PackProbe.probe(pack.path());
        assertEquals(report.toJson(), second.toJson(),
                "M7.8 " + pack.label() + " report stability");
        assertEquals(report.sha256(), second.sha256(),
                "M7.8 " + pack.label() + " report hash stability");
        assertNoUnstableData(report, pack.path(), pack.label());
        verifyPlanEligibility(pack, analysis);
        verifyEquivalentDirectoryLoading(pack, fixtureRoot);

        List<String> eligible = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            if (isEligible(program)) {
                eligible.add(program.name());
            } else if (program.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK) {
                fallback.add(program.name());
            } else if (program.support() == ConformanceReport.SupportStatus.UNSUPPORTED) {
                unsupported.add(program.name());
            }
        }
        eligible.sort(String::compareTo);
        fallback.sort(String::compareTo);
        unsupported.sort(String::compareTo);
        assertTrue(!eligible.isEmpty(),
                "M7.8 " + pack.label() + " has no statically eligible program");

        RuntimeEvidence runtime = readRuntime(pack, fixtureRoot, eligible);
        boolean requireReference = Boolean.getBoolean("chimera.m78.requireReferenceEvidence");
        if (requireReference) {
            assertTrue(runtime.irisCaptureRecorded(),
                    "M7.8 " + pack.label() + " Iris reference capture is missing");
        }

        JsonObject json = new JsonObject();
        json.addProperty("logicalName", PackProbe.logicalPackName(pack.path()));
        json.addProperty("version", pack.version());
        json.addProperty("fullPackFingerprint", fingerprint);
        json.addProperty("reportSha256", report.sha256());
        json.add("executablePrograms", strings(eligible));
        json.add("fallbackPrograms", strings(fallback));
        json.add("unsupportedPrograms", strings(unsupported));
        json.add("deviationCodes", strings(allDeviations(report)));
        json.add("runtimeEvidence", runtime.json());
        json.addProperty("visualParityStatus",
                runtime.irisCaptureRecorded() ? "REVIEW_REQUIRED" : "NOT_PROVIDED");
        json.addProperty("performanceStatus", "DEFERRED_TO_TASK-172");

        assertEquals(expected.get("version").getAsString(), pack.version(),
                "M7.8 " + pack.label() + " version baseline");
        assertEquals(expected.get("requireStaticEligibility").getAsBoolean(), true,
                "M7.8 " + pack.label() + " static eligibility contract");
        assertEquals(expected.get("requireChimeraInstallation").getAsBoolean(),
                !runtime.installed().isEmpty(),
                "M7.8 " + pack.label() + " runtime installation");
        assertEquals(expected.get("visualParityClaim").getAsString(), "NOT_CLAIMED",
                "M7.8 " + pack.label() + " visual claim");
        return new PackSnapshot(json);
    }

    private static RuntimeEvidence readRuntime(
            PackSpec pack, Path fixtureRoot, Collection<String> eligible) throws IOException {
        String text = Files.readString(pack.log(), StandardCharsets.UTF_8);
        TreeSet<String> installed = collect(INSTALLED, text);
        TreeSet<String> fallback = collect(FALLBACK, text);
        TreeSet<String> reportHashes = collect(SUMMARY, text, 1);
        TreeSet<String> summaries = new TreeSet<>();
        Matcher summary = SUMMARY.matcher(text);
        while (summary.find()) {
            summaries.add("installed=" + summary.group(2) + ",fallback=" + summary.group(3));
        }
        TreeSet<String> dimensions = collectDimensions(text);
        TreeSet<String> missing = new TreeSet<>(eligible);
        missing.removeAll(installed);
        assertTrue(missing.isEmpty(),
                "M7.8 " + pack.label() + " statically eligible programs were not installed: " + missing);
        assertTrue(!installed.isEmpty(),
                "M7.8 " + pack.label() + " log has no installed program");
        assertTrue(!fallback.isEmpty(),
                "M7.8 " + pack.label() + " log has no fallback program");
        assertTrue(Files.size(pack.capture()) > 0,
                "M7.8 " + pack.label() + " capture is empty");

        JsonObject json = new JsonObject();
        json.addProperty("chimeraLog", evidencePath(fixtureRoot, pack.log()));
        json.addProperty("chimeraCapture", evidencePath(fixtureRoot, pack.capture()));
        json.add("observedInstalledPrograms", strings(installed));
        json.add("observedFallbackPrograms", strings(fallback));
        json.add("staticEligibleNotObserved", strings(missing));
        json.add("runtimeReportHashes", strings(reportHashes));
        json.add("runtimeSummaryCounts", strings(summaries));
        json.add("dimensionVariants", strings(dimensions));
        json.addProperty("executionStatus", "RENDERDOC_REVIEW_REQUIRED");

        Path irisCapture = pack.irisCapture();
        Path irisLog = pack.irisLog();
        boolean irisRecorded = irisCapture != null && Files.isRegularFile(irisCapture);
        json.addProperty("irisReferenceCapture",
                irisRecorded ? evidencePath(fixtureRoot, irisCapture) : "NOT_PROVIDED");
        json.addProperty("irisReferenceLog",
                irisLog != null && Files.isRegularFile(irisLog)
                        ? evidencePath(fixtureRoot, irisLog) : "NOT_PROVIDED");
        json.addProperty("visualComparison", irisRecorded
                ? "MANUAL_REVIEW_REQUIRED" : "REFERENCE_NOT_PROVIDED");
        return new RuntimeEvidence(json, installed, irisRecorded);
    }

    private static void verifyPlanEligibility(PackSpec pack, PackProbe.Analysis analysis) {
        List<String> reportNames = analysis.report().programs().stream()
                .map(ConformanceReport.ProgramReport::name).sorted().toList();
        List<String> planNames = analysis.plan().programs().stream()
                .map(PackProgramPlan::name).sorted().toList();
        assertTrue(reportNames.containsAll(planNames),
                "M7.8 " + pack.label() + " plan contains an unreported program");
        for (ConformanceReport.ProgramReport program : analysis.report().programs()) {
            PackProgramPlan planned = analysis.plan().program(program.name());
            boolean eligible = isEligible(program);
            assertEquals(eligible, analysis.report().shouldAttempt(program.name()),
                    "M7.8 " + pack.label() + " shouldAttempt: " + program.name());
            if (planned == null) {
                assertTrue(!eligible,
                        "M7.8 " + pack.label() + " unplanned program is eligible: " + program.name());
            } else {
                assertEquals(planned.executable(), analysis.report().shouldAttempt(program.name()),
                        "M7.8 " + pack.label() + " plan eligibility: " + program.name());
            }
        }
    }

    private static void verifyEquivalentDirectoryLoading(PackSpec pack, Path fixtureRoot)
            throws IOException {
        if (!Files.isRegularFile(pack.path())) {
            return;
        }
        Path temporary = Files.createTempDirectory("chimera-m78-directory-");
        try {
            Path directory = temporary.resolve(PackProbe.logicalPackName(pack.path()));
            Path destination = directory.resolve("shaders");
            Path extracted;
            try (PackSource.LoadResult loaded = PackSource.loadResult(pack.path())) {
                extracted = loaded.shadersDir();
                copyTree(extracted, destination);
            }
            assertTrue(!Files.exists(extracted), "M7.8 ZIP extraction cleanup");
            ConformanceReport archiveReport = PackProbe.probe(pack.path());
            ConformanceReport directoryReport = PackProbe.probe(directory);
            assertEquals(archiveReport.toJson(), directoryReport.toJson(),
                    "M7.8 " + pack.label() + " ZIP/directory report");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        Files.walk(source).sorted().forEach(path -> {
            try {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                throw new HarnessFailure(exception.getMessage());
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new HarnessFailure(exception.getMessage());
                }
            });
        }
    }

    private static TreeSet<String> collect(Pattern pattern, String text) {
        return collect(pattern, text, 1);
    }

    private static TreeSet<String> collect(Pattern pattern, String text, int group) {
        TreeSet<String> result = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(group));
        }
        return result;
    }

    private static TreeSet<String> collectDimensions(String text) {
        TreeSet<String> result = new TreeSet<>();
        Matcher matcher = DIMENSION.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1) + "|" + matcher.group(2));
        }
        return result;
    }

    private static TreeSet<String> allDeviations(ConformanceReport report) {
        TreeSet<String> result = new TreeSet<>(report.deviations());
        for (ConformanceReport.ProgramReport program : report.programs()) {
            result.addAll(program.deviations());
        }
        return result;
    }

    private static boolean isEligible(ConformanceReport.ProgramReport program) {
        return program.support() == ConformanceReport.SupportStatus.SUPPORTED
                || program.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION;
    }

    private static String evidencePath(Path fixtureRoot, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path root = fixtureRoot.toAbsolutePath().normalize().getParent();
        if (root != null && absolute.startsWith(root)) {
            return root.relativize(absolute).toString().replace('\\', '/');
        }
        return absolute.getFileName().toString();
    }

    private static Path requiredPath(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new HarnessFailure("Missing required property: " + key);
        }
        Path path = Path.of(value);
        if (!Files.isDirectory(path) && !Files.isRegularFile(path)) {
            throw new HarnessFailure("Path does not exist for " + key + ": " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String requiredString(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new HarnessFailure("Missing required property: " + key);
        }
        return value;
    }

    private static Path requiredEvidence(String key) {
        Path path = Path.of(requiredString(key));
        if (!Files.isRegularFile(path)) {
            throw new HarnessFailure("Evidence path does not exist for " + key + ": " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static Path optionalEvidence(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        Path path = Path.of(value);
        if (!Files.isRegularFile(path)) {
            throw new HarnessFailure("Evidence path does not exist for " + key + ": " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static JsonObject readObject(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new HarnessFailure(label + " does not exist: " + path);
        }
        return com.google.gson.JsonParser.parseString(
                Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject childObject(JsonObject object, String name, String label) {
        if (!object.has(name) || !object.get(name).isJsonObject()) {
            throw new HarnessFailure(label + " is missing " + name);
        }
        return object.getAsJsonObject(name);
    }

    private static JsonArray strings(Collection<String> values) {
        JsonArray result = new JsonArray();
        values.stream().sorted().forEach(result::add);
        return result;
    }

    private static void verifyHeader(JsonObject root) {
        assertEquals(root.get("formatVersion").getAsInt(), 1, "M7.8 baseline format");
        assertEquals(root.get("milestone").getAsString(), "M7.8", "M7.8 baseline milestone");
        assertEquals(root.get("referenceBackend").getAsString(), "Sodium + Iris",
                "M7.8 reference backend");
    }

    private static void assertNoUnstableData(
            ConformanceReport report, Path pack, String label) {
        String json = report.toJson();
        assertTrue(!json.contains(pack.toAbsolutePath().normalize().toString()),
                "M7.8 " + label + " report contains an absolute path");
        assertTrue(!json.matches("(?s).*\\b(?:timestamp|createdAt|updatedAt)\\b.*"),
                "M7.8 " + label + " report contains timestamp data");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new HarnessFailure(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new HarnessFailure(message);
        }
    }

    private record PackSpec(
            String label, Path path, String version, Path log, Path capture,
            Path irisCapture, Path irisLog) {}

    private record PackSnapshot(JsonObject json) {}

    private record RuntimeEvidence(
            JsonObject json, Collection<String> installed, boolean irisCaptureRecorded) {}

    private static final class HarnessFailure extends RuntimeException {
        private HarnessFailure(String message) {
            super(message);
        }
    }
}
