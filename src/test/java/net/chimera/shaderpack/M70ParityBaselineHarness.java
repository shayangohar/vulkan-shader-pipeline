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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M7.0 evidence baseline for exact real packs. Static eligibility, observed
 * installation, RenderDoc execution, and visual parity remain separate.
 */
public final class M70ParityBaselineHarness {
    private static final Pattern INSTALLED = Pattern.compile(
            "\\[chimera\\] pack ([^:]+): ok\\b");
    private static final Pattern FALLBACK = Pattern.compile(
            "\\[chimera\\] pack ([^:]+): fallback=IDENTITY\\b");
    private static final Pattern SUMMARY = Pattern.compile(
            "conformance summary: reportSha256=([0-9a-f]{64}) installed=(\\d+), fallback=(\\d+)");
    private static final Pattern DIMENSION = Pattern.compile(
            "pack dimension variant installed: ([^ ]+) \\(([^)]+)\\)");
    private static final List<String> M65_STATIC_KEYS = List.of(
            "logicalName", "version", "fullPackFingerprint", "reportSha256",
            "sourceHashes", "executablePrograms", "fallbackPrograms", "unsupportedPrograms");

    private M70ParityBaselineHarness() {}

    public static void main(String[] args) throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path baseline = Path.of(System.getProperty(
                "chimera.m70.baseline", fixtureRoot.resolve("baselines/m7_0.json").toString()));
        JsonObject baselineRoot = readObject(baseline, "M7.0 baseline");
        verifyHeader(baselineRoot);
        assertStableBaseline(baselineRoot, baseline);

        List<PackSpec> packs = List.of(
                new PackSpec("complementary", required("chimera.m70.complementary"),
                        required("chimera.m70.complementaryVersion"),
                        optional("chimera.m70.complementaryLog"),
                        optional("chimera.m70.complementaryCapture")),
                new PackSpec("bsl", required("chimera.m70.bsl"),
                        required("chimera.m70.bslVersion"),
                        optional("chimera.m70.bslLog"),
                        optional("chimera.m70.bslCapture")));

        verifyM65RegressionBaseline(fixtureRoot, packs);
        if (Boolean.getBoolean("chimera.m70.emitBaseline")) {
            emitBaseline(fixtureRoot, packs);
            return;
        }

        JsonObject expectedPacks = childObject(baselineRoot, "packs", "M7.0 baseline packs");
        for (PackSpec pack : packs) {
            verifyPack(fixtureRoot, pack,
                    childObject(expectedPacks, pack.label(),
                            "M7.0 baseline entry for " + pack.label()));
        }
        System.out.println("[chimera] M7.0 parity baseline: PASS");
    }

    private static void verifyPack(Path fixtureRoot, PackSpec spec, JsonObject expected)
            throws IOException {
        Snapshot actual = snapshot(fixtureRoot, spec, true);
        assertEquals(expected.get("logicalName").toString(), actual.logicalName(),
                "M7.0 " + spec.label() + " logical name");
        assertEquals(expected.get("version").toString(), quote(spec.version()),
                "M7.0 " + spec.label() + " version");
        assertEquals(expected.get("fullPackFingerprint").toString(),
                quote(actual.fingerprint()), "M7.0 " + spec.label() + " fingerprint");
        assertEquals(expected.get("static").toString(), actual.staticJson().toString(),
                "M7.0 " + spec.label() + " static baseline");
        assertEquals(expected.get("runtimeEvidence").toString(),
                actual.evidence().json().toString(), "M7.0 " + spec.label() + " runtime evidence");
        assertEquals(expected.get("featureMatrix").toString(),
                actual.featureMatrix().toString(), "M7.0 " + spec.label() + " feature matrix");
        assertTrue(!actual.evidence().installed().isEmpty(),
                "M7.0 " + spec.label() + " has no observed installed program");
        assertTrue(actual.evidence().captureRecorded(),
                "M7.0 " + spec.label() + " has no recorded RenderDoc capture");
    }

    private static Snapshot snapshot(Path fixtureRoot, PackSpec spec, boolean requireEvidence)
            throws IOException {
        Path packPath = spec.path();
        assertTrue(Files.isDirectory(packPath) || Files.isRegularFile(packPath),
                "M7.0 " + spec.label() + " pack path does not exist: " + packPath);
        String fingerprint = PackFingerprint.sha256(packPath);
        assertEquals(fingerprint, PackFingerprint.sha256(packPath),
                "M7.0 " + spec.label() + " fingerprint stability");

        PackProbe.Analysis analysis;
        Path extracted = null;
        try (PackSource.LoadResult loaded = PackSource.loadResult(packPath)) {
            List<String> names = loaded.programs().stream().map(PackProgram::name).toList();
            assertEquals(names.stream().distinct().count(), (long) names.size(),
                    "M7.0 " + spec.label() + " duplicate runtime programs");
            extracted = Files.isRegularFile(packPath) ? loaded.shadersDir() : null;
            analysis = PackProbe.analyze(packPath, loaded);
            verifyPlanEligibility(spec, analysis);
        }
        if (extracted != null) {
            assertTrue(!Files.exists(extracted),
                    "M7.0 " + spec.label() + " ZIP extraction was not cleaned up");
        }

        ConformanceReport report = analysis.report();
        ConformanceReport second = PackProbe.probe(packPath);
        assertEquals(report.toJson(), second.toJson(),
                "M7.0 " + spec.label() + " report stability");
        assertEquals(report.sha256(), second.sha256(),
                "M7.0 " + spec.label() + " report hash stability");
        assertNoUnstableData(report, packPath, spec.label());

        Map<String, String> sources = sourceHashes(report);
        List<String> executable = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            boolean eligible = isEligible(program);
            assertEquals(eligible, report.shouldAttempt(program.name()),
                    "M7.0 " + spec.label() + " eligibility: " + program.name());
            assertEquals(ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED,
                    program.runtime(), "M7.0 " + spec.label() + " static runtime: " + program.name());
            if (eligible) {
                executable.add(program.name());
            } else if (program.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK) {
                fallback.add(program.name());
            } else if (program.support() == ConformanceReport.SupportStatus.UNSUPPORTED) {
                unsupported.add(program.name());
            }
        }
        assertTrue(!executable.isEmpty(),
                "M7.0 " + spec.label() + " has no executable program in the Chimera subset");

        TreeSet<String> allDeviations = allDeviations(report);
        JsonObject staticJson = new JsonObject();
        staticJson.addProperty("reportSha256", report.sha256());
        staticJson.addProperty("sourceHashCount", sources.size());
        staticJson.addProperty("sourceHashDigest", digestSourceHashes(sources));
        staticJson.addProperty("sourceHashBaseline", "testpacks/baselines/m6_5.json");
        staticJson.add("executablePrograms", strings(executable));
        staticJson.add("fallbackPrograms", strings(fallback));
        staticJson.add("unsupportedPrograms", strings(unsupported));
        staticJson.addProperty("deviationDigest", digestStrings(allDeviations));
        staticJson.add("deviationPrefixes", deviationPrefixes(allDeviations));
        staticJson.add("keyDeviationFamilies", strings(keyDeviationFamilies(allDeviations)));

        RuntimeEvidence evidence = runtimeEvidence(
                fixtureRoot, spec, requireEvidence, new TreeSet<>(executable));
        JsonArray featureMatrix = featureMatrix(report, evidence);
        Snapshot result = new Snapshot(
                quote(PackProbe.logicalPackName(packPath)), fingerprint, staticJson,
                evidence, featureMatrix);
        verifyEquivalentDirectoryLoading(fixtureRoot, spec, result);
        return result;
    }

    private static void verifyPlanEligibility(PackSpec spec, PackProbe.Analysis analysis) {
        List<String> reportNames = analysis.report().programs().stream()
                .map(ConformanceReport.ProgramReport::name).sorted().toList();
        List<String> planNames = analysis.plan().programs().stream()
                .map(PackProgramPlan::name).sorted().toList();
        assertTrue(reportNames.containsAll(planNames),
                "M7.0 " + spec.label() + " plan contains an unreported program");
        for (ConformanceReport.ProgramReport program : analysis.report().programs()) {
            PackProgramPlan planned = analysis.plan().program(program.name());
            if (planned == null) {
                assertTrue(!analysis.report().shouldAttempt(program.name()),
                        "M7.0 " + spec.label() + " unplanned program was executable: "
                                + program.name());
            } else {
                assertEquals(planned.executable(), analysis.report().shouldAttempt(program.name()),
                        "M7.0 " + spec.label() + " plan eligibility: " + program.name());
            }
        }
    }

    private static RuntimeEvidence runtimeEvidence(
            Path fixtureRoot, PackSpec spec, boolean requireEvidence,
            Collection<String> staticallyEligible) throws IOException {
        Path log = spec.logPath();
        Path capture = spec.capturePath();
        if (requireEvidence) {
            assertTrue(log != null && Files.isRegularFile(log),
                    "M7.0 " + spec.label() + " runtime log is missing: " + log);
            assertTrue(capture != null && Files.isRegularFile(capture),
                    "M7.0 " + spec.label() + " RenderDoc capture is missing: " + capture);
        }

        TreeSet<String> installed = new TreeSet<>();
        TreeSet<String> fallback = new TreeSet<>();
        TreeSet<String> reportHashes = new TreeSet<>();
        TreeSet<String> summaries = new TreeSet<>();
        TreeSet<String> dimensions = new TreeSet<>();
        if (log != null && Files.isRegularFile(log)) {
            String text = Files.readString(log, StandardCharsets.UTF_8);
            collect(INSTALLED, text, installed, 1);
            collect(FALLBACK, text, fallback, 1);
            Matcher summary = SUMMARY.matcher(text);
            while (summary.find()) {
                reportHashes.add(summary.group(1));
                summaries.add("installed=" + summary.group(2) + ",fallback=" + summary.group(3));
            }
            Matcher dimension = DIMENSION.matcher(text);
            while (dimension.find()) {
                dimensions.add(dimension.group(1) + "|" + dimension.group(2));
            }
        }
        if (requireEvidence) {
            assertTrue(!installed.isEmpty(),
                    "M7.0 " + spec.label() + " log has no installed pack program");
            assertTrue(!fallback.isEmpty(),
                    "M7.0 " + spec.label() + " log has no fallback program");
        }

        Path repoRoot = fixtureRoot.toAbsolutePath().normalize().getParent();
        JsonObject json = new JsonObject();
        json.addProperty("status", log != null && capture != null
                && Files.isRegularFile(log) && Files.isRegularFile(capture)
                ? "RECORDED" : "NOT_PROVIDED");
        json.addProperty("logPath", evidencePath(repoRoot, log));
        json.addProperty("capturePath", evidencePath(repoRoot, capture));
        json.add("observedInstalledPrograms", strings(installed));
        json.add("observedFallbackPrograms", strings(fallback));
        json.add("runtimeReportHashes", strings(reportHashes));
        json.add("runtimeSummaryCounts", strings(summaries));
        json.add("dimensionVariants", strings(dimensions));
        TreeSet<String> runtimeOnly = new TreeSet<>(installed);
        runtimeOnly.removeAll(staticallyEligible);
        TreeSet<String> notObserved = new TreeSet<>(staticallyEligible);
        notObserved.removeAll(installed);
        json.add("installedOutsideStaticEligibility", strings(runtimeOnly));
        json.add("staticEligibleNotObserved", strings(notObserved));
        json.addProperty("executionStatus", "RENDERDOC_REVIEW_REQUIRED");
        json.addProperty("visualParityStatus", "NOT_CLAIMED");
        return new RuntimeEvidence(json, installed, fallback,
                capture != null && Files.isRegularFile(capture));
    }

    private static void collect(
            Pattern pattern, String text, Collection<String> values, int group) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(group));
        }
    }

    private static JsonArray featureMatrix(
            ConformanceReport report, RuntimeEvidence evidence) {
        JsonArray result = new JsonArray();
        addFeature(result, "post_programs", postStatus(report),
                hasObservedPost(evidence.installed()) ? "OBSERVED" : "NOT_OBSERVED",
                "CAPTURE_REVIEW_REQUIRED", "NOT_CLAIMED");
        addFeature(result, "terrain_family", familyStatus(report, "gbuffers_terrain"),
                observed(evidence.installed(), "gbuffers_terrain"), "HOST_OR_PACK_REVIEW", "NOT_CLAIMED");
        addFeature(result, "water_family", familyStatus(report, "gbuffers_water"),
                observed(evidence.installed(), "gbuffers_water"), "HOST_OR_PACK_REVIEW", "NOT_CLAIMED");
        addFeature(result, "shadow_family", familyStatus(report, "shadow"),
                observed(evidence.installed(), "shadow"), "HOST_OR_PACK_REVIEW", "NOT_CLAIMED");
        addFeature(result, "entity_family", familyStatus(report, "gbuffers_entities"),
                observed(evidence.installed(), "gbuffers_entities"), "HOST_OR_PACK_REVIEW", "NOT_CLAIMED");
        addFeature(result, "uniform_catalog", "PARTIAL", "RUNTIME_LOG_REVIEW",
                "CAPTURE_REVIEW_REQUIRED", "NOT_CLAIMED");
        addFeature(result, "target_routing", targetStatus(report), "RUNTIME_LOG_REVIEW",
                "CAPTURE_REVIEW_REQUIRED", "NOT_CLAIMED");
        addFeature(result, "resource_aliases", "PARTIAL", "RUNTIME_LOG_REVIEW",
                "CAPTURE_REVIEW_REQUIRED", "NOT_CLAIMED");
        addFeature(result, "dimension_lifecycle", "OUT_OF_SCOPE", evidence.dimensionVariants().isEmpty()
                        ? "NOT_OBSERVED" : "OBSERVED_IN_LOG", "LOG_ONLY", "NOT_CLAIMED");
        addFeature(result, "performance", "DEFERRED", "DEFERRED", "DEFERRED", "NOT_CLAIMED");
        return result;
    }

    private static void addFeature(
            JsonArray result, String name, String staticStatus, String installed,
            String executed, String visual) {
        JsonObject feature = new JsonObject();
        feature.addProperty("feature", name);
        feature.addProperty("static", staticStatus);
        feature.addProperty("installed", installed);
        feature.addProperty("executed", executed);
        feature.addProperty("visual", visual);
        result.add(feature);
    }

    private static String postStatus(ConformanceReport report) {
        boolean installedSubset = report.programs().stream()
                .anyMatch(value -> PostTargetPlan.isPostProgramName(value.name())
                        && isEligible(value));
        return installedSubset ? "PARTIAL" : "NONE";
    }

    private static String targetStatus(ConformanceReport report) {
        return report.deviations().stream().anyMatch(value ->
                value.startsWith("POST_TARGET_") || value.equals("MRT_NOT_SUPPORTED"))
                ? "PARTIAL" : "HOST_ONLY";
    }

    private static String familyStatus(ConformanceReport report, String name) {
        ConformanceReport.ProgramReport program = report.program(name);
        return program != null && isEligible(program) ? "SUPPORTED" : "FALLBACK";
    }

    private static boolean hasObservedPost(Collection<String> names) {
        return names.stream().anyMatch(PostTargetPlan::isPostProgramName);
    }

    private static String observed(Collection<String> names, String name) {
        return names.contains(name) ? "OBSERVED" : "HOST_OR_IDENTITY";
    }

    private static void verifyM65RegressionBaseline(Path fixtureRoot, List<PackSpec> packs)
            throws IOException {
        JsonObject baseline = readObject(fixtureRoot.resolve("baselines/m6_5.json"),
                "M6.5 regression baseline");
        JsonObject expectedPacks = childObject(baseline, "packs", "M6.5 regression packs");
        for (PackSpec spec : packs) {
            JsonObject expected = childObject(expectedPacks, spec.label(),
                    "M6.5 regression entry for " + spec.label());
            ConformanceReport report = PackProbe.probe(spec.path());
            assertEquals(expected.get("fullPackFingerprint").getAsString(),
                    PackFingerprint.sha256(spec.path()),
                    "M7.0 " + spec.label() + " M6.5 fingerprint");
            assertEquals(expected.get("reportSha256").getAsString(), report.sha256(),
                    "M7.0 " + spec.label() + " M6.5 report hash");
            assertEquals(expected.get("sourceHashes").toString(),
                    stringMap(sourceHashes(report)).toString(),
                    "M7.0 " + spec.label() + " M6.5 source hashes");
            assertEquals(expected.get("executablePrograms").toString(),
                    strings(names(report, true)).toString(),
                    "M7.0 " + spec.label() + " M6.5 executable programs");
            assertEquals(expected.get("fallbackPrograms").toString(),
                    strings(names(report, false, ConformanceReport.SupportStatus.IDENTITY_FALLBACK)).toString(),
                    "M7.0 " + spec.label() + " M6.5 fallback programs");
            assertEquals(expected.get("unsupportedPrograms").toString(),
                    strings(names(report, false, ConformanceReport.SupportStatus.UNSUPPORTED)).toString(),
                    "M7.0 " + spec.label() + " M6.5 unsupported programs");
            assertEquals(expected.get("deviationCodes").toString(),
                    strings(allDeviations(report)).toString(),
                    "M7.0 " + spec.label() + " M6.5 deviation codes");
        }
    }

    private static List<String> names(
            ConformanceReport report, boolean eligible,
            ConformanceReport.SupportStatus... statuses) {
        List<ConformanceReport.SupportStatus> accepted = List.of(statuses);
        return report.programs().stream()
                .filter(value -> eligible ? isEligible(value) : accepted.contains(value.support()))
                .map(ConformanceReport.ProgramReport::name).sorted().toList();
    }

    private static boolean isEligible(ConformanceReport.ProgramReport program) {
        return program.support() == ConformanceReport.SupportStatus.SUPPORTED
                || program.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION;
    }

    private static void emitBaseline(Path fixtureRoot, List<PackSpec> packs) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);
        root.addProperty("milestone", "M7.0");
        root.addProperty("referenceBackend", "Sodium + Iris");
        root.add("evidenceModel", strings(List.of(
                "STATIC_ELIGIBILITY", "RUNTIME_INSTALLATION", "RUNTIME_EXECUTION", "VISUAL_PARITY")));
        root.addProperty("performanceStatus", "DEFERRED_TO_TASK-172");
        root.addProperty("sourceHashBaseline", "testpacks/baselines/m6_5.json");
        root.add("packs", new JsonObject());
        JsonObject entries = root.getAsJsonObject("packs");
        for (PackSpec spec : packs) {
            Snapshot snapshot = snapshot(fixtureRoot, spec, true);
            JsonObject entry = new JsonObject();
            entry.addProperty("logicalName", unquote(snapshot.logicalName()));
            entry.addProperty("version", spec.version());
            entry.addProperty("fullPackFingerprint", snapshot.fingerprint());
            entry.add("static", snapshot.staticJson());
            entry.add("runtimeEvidence", snapshot.evidence().json());
            entry.add("featureMatrix", snapshot.featureMatrix());
            entries.add(spec.label(), entry);
        }
        System.out.println(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(root));
    }

    private static List<String> keyDeviationFamilies(Collection<String> values) {
        TreeSet<String> result = new TreeSet<>();
        for (String value : values) {
            if (value.equals("MODERN_GLSL_UNSUPPORTED")
                    || value.equals("MRT_NOT_SUPPORTED")
                    || value.equals("MRT_POST_BRIDGE")
                    || value.equals("NOISETEX_PACK_RESOURCE")) {
                result.add(value);
            } else if (value.startsWith("POST_TARGET_INDEX_UNSUPPORTED:")) {
                result.add("POST_TARGET_INDEX_UNSUPPORTED");
            } else if (value.startsWith("POST_TARGET_FORMAT_UNSUPPORTED:")) {
                result.add("POST_TARGET_FORMAT_UNSUPPORTED");
            } else if (value.endsWith("VERTEX_BRIDGE_UNSUPPORTED")) {
                result.add("VERTEX_BRIDGE_UNSUPPORTED");
            } else if (value.startsWith("TRANSLUCENT_")) {
                result.add("TRANSLUCENT_LIMITS");
            } else if (value.startsWith("SAMPLER_NOT_MAPPED:")) {
                result.add("SAMPLER_NOT_MAPPED");
            } else if (value.startsWith("UNIFORM_NAME_UNSUPPORTED:")) {
                result.add("UNIFORM_NAME_UNSUPPORTED");
            }
        }
        return List.copyOf(result);
    }

    private static JsonArray deviationPrefixes(Collection<String> values) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (String value : values) {
            String prefix = value;
            int colon = value.indexOf(':');
            if (colon >= 0) {
                prefix = value.substring(0, colon);
            }
            counts.merge(prefix, 1, Integer::sum);
        }
        JsonArray result = new JsonArray();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("prefix", entry.getKey());
            item.addProperty("count", entry.getValue());
            result.add(item);
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

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> result = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            program.sourceHashes().forEach(result::putIfAbsent);
        }
        return result;
    }

    private static String digestSourceHashes(Map<String, String> values) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return ConformanceReport.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String digestStrings(Collection<String> values) {
        String text = String.join("\n", new TreeSet<>(values)) + "\n";
        return ConformanceReport.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String evidencePath(Path repoRoot, Path path) {
        if (path == null) {
            return "NOT_PROVIDED";
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (repoRoot != null && absolute.startsWith(repoRoot)) {
            return repoRoot.relativize(absolute).toString().replace('\\', '/');
        }
        Path fileName = absolute.getFileName();
        return fileName == null ? "EXTERNAL_EVIDENCE" : fileName.toString();
    }

    private static void verifyHeader(JsonObject root) {
        assertEquals("1", root.get("formatVersion").toString(),
                "M7.0 baseline format version");
        assertEquals("\"M7.0\"", root.get("milestone").toString(),
                "M7.0 baseline milestone");
        assertEquals("\"Sodium + Iris\"", root.get("referenceBackend").toString(),
                "M7.0 baseline reference backend");
        assertEquals("\"DEFERRED_TO_TASK-172\"", root.get("performanceStatus").toString(),
                "M7.0 performance status");
    }

    private static void assertStableBaseline(JsonObject root, Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(!json.matches("(?s).*\\b(?:timestamp|createdAt|updatedAt)\\b.*"),
                "M7.0 baseline contains timestamp data");
        assertTrue(!json.matches("(?s).*\\b[A-Za-z]:[\\\\/].*"),
                "M7.0 baseline contains an absolute path");
        assertTrue(root.get("sourceHashBaseline") != null,
                "M7.0 baseline has no source hash baseline reference");
    }

    private static void assertNoUnstableData(
            ConformanceReport report, Path pack, String label) {
        String json = report.toJson();
        assertTrue(!json.contains(pack.toAbsolutePath().normalize().toString()),
                "M7.0 " + label + " report contains an absolute pack path");
        assertTrue(!json.matches("(?s).*\\b(?:timestamp|createdAt|updatedAt)\\b.*"),
                "M7.0 " + label + " report contains timestamp data");
    }

    private static void verifyEquivalentDirectoryLoading(Path fixtureRoot, PackSpec spec,
            Snapshot archive) throws IOException {
        if (!Files.isRegularFile(spec.path())) {
            return;
        }
        Path temporary = Files.createTempDirectory("chimera-m70-directory-");
        try {
            Path directory = temporary.resolve(PackProbe.logicalPackName(spec.path()));
            Path destination = directory.resolve("shaders");
            Path extracted;
            try (PackSource.LoadResult loaded = PackSource.loadResult(spec.path())) {
                extracted = loaded.shadersDir();
                copyTree(extracted, destination);
            }
            assertTrue(!Files.exists(extracted), "M7.0 ZIP extraction cleanup");
            ConformanceReport report = PackProbe.probe(directory);
            Snapshot directorySnapshot = snapshot(fixtureRoot,
                    new PackSpec(spec.label(), directory.toString(), spec.version(), null, null), false);
            assertEquals(archive.staticJson().toString(), directorySnapshot.staticJson().toString(),
                    "M7.0 " + spec.label() + " ZIP/directory static data");
            assertEquals(report.toJson(), PackProbe.probe(directory).toJson(),
                    "M7.0 " + spec.label() + " directory report stability");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
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

    private static JsonArray strings(Collection<String> values) {
        JsonArray result = new JsonArray();
        for (String value : new TreeSet<>(values)) {
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

    private static JsonObject readObject(Path path, String label) throws IOException {
        assertTrue(Files.isRegularFile(path), label + " is missing: " + path);
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject childObject(JsonObject parent, String key, String message) {
        JsonElement value = parent.get(key);
        assertTrue(value != null && value.isJsonObject(), message + " is missing");
        return value.getAsJsonObject();
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new AssertionError("Missing required M7.0 property: -D" + property);
        }
        return value;
    }

    private static Path optional(String property) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String unquote(String value) {
        return value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record PackSpec(
            String label, String pathValue, String version, Path logPath, Path capturePath) {
        private Path path() {
            return Path.of(pathValue);
        }
    }

    private record RuntimeEvidence(
            JsonObject json, TreeSet<String> installed, TreeSet<String> fallback,
            boolean captureRecorded) {
        private TreeSet<String> dimensionVariants() {
            TreeSet<String> result = new TreeSet<>();
            JsonElement value = json.get("dimensionVariants");
            if (value != null && value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    result.add(item.getAsString());
                }
            }
            return result;
        }
    }

    private record Snapshot(
            String logicalName, String fingerprint, JsonObject staticJson,
            RuntimeEvidence evidence, JsonArray featureMatrix) {}
}
