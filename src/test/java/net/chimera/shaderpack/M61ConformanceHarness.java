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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministic M6.1 checks for the shared program-plan and translation core. */
public final class M61ConformanceHarness {
    private M61ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path fixture = fixtureRoot.resolve("m6_1/program_plan");
        JsonObject baseline = readObject(Path.of(System.getProperty(
                "chimera.m61.baseline", fixtureRoot.resolve("baselines/m6_1.json").toString())));

        verifyFixture(fixture, baseline);
        verifyTokenBoundaries();
        verifyPreparationBudgets();
        verifyExternalPack("chimera.m61.complementary", baseline);
        verifyExternalPack("chimera.m61.independent", baseline);
        System.out.println("[chimera] M6.1 shared program-plan conformance: PASS");
    }

    private static void verifyFixture(Path fixture, JsonObject baseline) throws IOException {
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        ConformanceReport report = first.report();
        assertEquals(report.toJson(), second.report().toJson(), "M6.1 fixture report stability");
        assertEquals(planFingerprint(first.plan()), planFingerprint(second.plan()),
                "M6.1 fixture plan stability");

        PackProgramPlan composite = requirePlan(first.plan(), "composite");
        PreparedShaderSource compositeSource = composite.stages().get("fragment");
        assertTrue(compositeSource != null, "M6.1 composite prepared source is missing");
        assertTrue(compositeSource.dependencies().contains("lib/common.glsl"),
                "M6.1 root-relative include was not recorded");
        assertTrue(compositeSource.deviations().contains("PREPROCESSOR_MACRO_REDEFINED:M61_FEATURE"),
                "M6.1 macro redefinition was not recorded");
        assertEquals(List.of(new UniformRegistry.UniformDeclaration("frameTimeCounter", "float")),
                composite.interfacePlan().uniforms(), "M6.1 uniform union ordering");
        assertEquals(List.of(new UniformRegistry.SamplerBinding("colortex0", 0)),
                composite.interfacePlan().samplers(), "M6.1 sampler ordering");
        assertEquals(Map.of("texcoord", 0), composite.varyingLocations(),
                "M6.1 post varying location");
        assertConvertedPost(composite.convertedFragment(), "M6.1 composite conversion");

        PackProgramPlan numbered = requirePlan(first.plan(), "composite1");
        assertTrue(numbered.executable(), "M6.1 GLSL 130 pass was rejected");
        assertConvertedPost(numbered.convertedFragment(), "M6.1 GLSL 130 conversion");
        assertTrue(!numbered.convertedFragment().contains("GL_ARB_shader_texture_lod"),
                "M6.1 legacy texture extension was retained");
        assertTrue(!numbered.convertedFragment().contains("texture2D")
                        && !numbered.convertedFragment().contains("texture2DLod"),
                "M6.1 texture calls were not rewritten");

        PackProgramPlan terrain = requirePlan(first.plan(), "gbuffers_terrain");
        assertTrue(terrain.executable(), "M6.1 paired terrain plan was rejected");
        assertEquals(Set.of("vertexColor", "texcoord"), terrain.varyingLocations().keySet(),
                "M6.1 terrain varying union");
        assertEquals(0, terrain.varyingLocations().get("texcoord"),
                "M6.1 deterministic terrain varying location");
        assertTrue(terrain.convertedVertex() != null && terrain.convertedFragment() != null,
                "M6.1 paired terrain conversion is incomplete");

        PackProgramPlan modern = requirePlan(first.plan(), "composite2");
        assertTrue(modern.executable(), "M6.1 modern post compatibility regression");
        assertTrue(modern.deviations().contains("MODERN_GLSL_TRANSLATED"),
                "M6.1 modern post translation deviation is missing");
        assertFallback(first, "composite3", "POST_VARYING_UNSUPPORTED:unsupportedUv");
        assertEquals(List.of("composite", "composite1", "composite2", "final", "gbuffers_terrain"),
                eligibleNames(report), "M6.1 eligible program set");

        JsonElementChecks.verifyBaseline(report, baseline, fixture);
    }

    private static void verifyTokenBoundaries() {
        String source = "// texture2D(gl_FragColor)\n"
                + "const char *name = \"texture2D gl_FragColor\";\n"
                + "vec4 value = texture2D(colortex0, texture2D(colortex0, uv).xy);\n";
        String rewritten = GlslTokenRewriter.rewriteTextureCalls(source);
        assertTrue(rewritten.contains("texture(colortex0, texture(colortex0, uv).xy)"),
                "M6.1 nested texture rewrite failed");
        assertTrue(rewritten.contains("// texture2D(gl_FragColor)"),
                "M6.1 comment was rewritten");
        assertTrue(rewritten.contains("\"texture2D gl_FragColor\""),
                "M6.1 literal was rewritten");
        assertEquals(6L, GlslLexer.lex("1+2 1e-2 3.5 4.0e+1").stream()
                        .filter(GlslLexer.Token::significant).count(),
                "M6.1 numeric token boundary");
    }

    private static void verifyPreparationBudgets() {
        StringBuilder nested = new StringBuilder("#version 120\n#if ");
        for (int i = 0; i < 70; i++) {
            nested.append('(');
        }
        nested.append('1');
        for (int i = 0; i < 70; i++) {
            nested.append(')');
        }
        nested.append("\n#endif\n");
        ShaderSourcePreprocessor.Result result = ShaderSourcePreprocessor.prepare(
                Path.of("."), null, nested.toString());
        assertTrue(result.deviations().contains("PREPROCESSOR_CONDITION_UNSUPPORTED"),
                "M6.1 expression recursion budget was not enforced");
    }

    private static void verifyExternalPack(
            String property,
            JsonObject m57Baseline
    ) throws IOException {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new AssertionError("Missing required M6.1 pack path: -D" + property);
        }
        Path path = Path.of(value);
        String versionProperty = property + "Version";
        String version = System.getProperty(versionProperty);
        assertTrue(version != null && !version.isBlank(),
                "Missing required M6.1 pack version: -D" + versionProperty);
        assertTrue(Files.isDirectory(path) || Files.isRegularFile(path),
                "M6.1 pack path does not exist: " + path);
        PackProbe.Analysis analysis = PackProbe.analyze(path);
        ConformanceReport report = analysis.report();
        ConformanceReport second = PackProbe.probe(path);
        assertEquals(report.sha256(), second.sha256(), property + " report stability");
        List<String> eligible = eligibleNames(report);
        assertTrue(!eligible.isEmpty(), property + " has no executable M6.1 program");
        for (ConformanceReport.ProgramReport program : report.programs()) {
            assertEquals(program.support() == ConformanceReport.SupportStatus.SUPPORTED
                            || program.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                    report.shouldAttempt(program.name()), property + " eligibility: " + program.name());
        }
        assertTrue(analysis.plan().programs().stream()
                        .anyMatch(PackProgramPlan::executable),
                property + " plan has no executable program");
        List<String> planEligible = analysis.plan().programs().stream()
                .filter(PackProgramPlan::executable)
                .map(PackProgramPlan::name)
                .sorted()
                .toList();
        assertEquals(planEligible, eligible, property + " report/plan eligibility");
        assertNoUnstableReportData(report, path);

        JsonObject old = oldBaselineEntry(property, m57Baseline);
        if (old != null && old.has("executablePrograms")) {
            Set<String> previous = new TreeSet<>(
                    JsonElementChecks.jsonArrayStrings(old.getAsJsonArray("executablePrograms")));
            Set<String> current = new TreeSet<>(planEligible);
            Set<String> added = new TreeSet<>(current);
            added.removeAll(previous);
            assertTrue(!added.isEmpty(), property + " did not add an executable program beyond M5.7");
        }
        System.out.println("[chimera] " + property + " executable programs: " + eligible
                + ", planned=" + planEligible + ", version=" + version);
    }

    private static JsonObject oldBaselineEntry(String property, JsonObject baseline) {
        String label = property.endsWith("complementary") ? "complementary" : "independent";
        JsonElement historical = baseline.get("m57BaselineExecutablePrograms");
        if (historical == null || !historical.isJsonObject()
                || !historical.getAsJsonObject().has(label)) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.add("executablePrograms", historical.getAsJsonObject().getAsJsonArray(label));
        return result;
    }

    private static void assertConvertedPost(String source, String label) {
        assertTrue(source != null, label + " returned no source");
        assertEquals(1, count(source, "#version 460"), label + " version count");
        assertTrue(!source.contains("#version 120") && !source.contains("#version 130"),
                label + " retained a legacy version");
        assertTrue(source.contains("layout(location = 0) out"),
                label + " did not generate the fragment output");
    }

    private static void assertFallback(
            PackProbe.Analysis analysis,
            String name,
            String expectedDeviation
    ) {
        ConformanceReport.ProgramReport report = analysis.report().program(name);
        assertTrue(report != null, "M6.1 fallback program is missing: " + name);
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                report.support(), "M6.1 fallback status: " + name);
        assertTrue(report.deviations().contains(expectedDeviation),
                "M6.1 fallback deviation is missing: " + name);
        assertTrue(!analysis.report().shouldAttempt(name),
                "M6.1 fallback was marked executable: " + name);
    }

    private static PackProgramPlan requirePlan(PackPlan plan, String name) {
        PackProgramPlan result = plan.program(name);
        assertTrue(result != null, "M6.1 plan is missing: " + name);
        return result;
    }

    private static List<String> eligibleNames(ConformanceReport report) {
        return report.programs().stream()
                .filter(program -> program.support() == ConformanceReport.SupportStatus.SUPPORTED
                        || program.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)
                .map(ConformanceReport.ProgramReport::name)
                .sorted()
                .toList();
    }

    private static void assertNoUnstableReportData(ConformanceReport report, Path pack) {
        String json = report.toJson();
        assertTrue(!json.contains(pack.toAbsolutePath().normalize().toString()),
                "M6.1 report contains an absolute pack path");
        assertTrue(!json.matches("(?s).*\\b(?:timestamp|createdAt|updatedAt)\\b.*"),
                "M6.1 report contains timestamp data");
    }

    private static int count(String source, String value) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

    private static List<String> planFingerprint(PackPlan plan) {
        return plan.programs().stream().map(value -> {
            PostTargetPlan targets = value.targetPlan();
            String targetFingerprint = targets == null ? ""
                    : targets.targetSlots() + ":" + targets.outputLocations() + ":" + targets.deviations();
            return value.name() + "|" + value.stages().keySet() + "|"
                    + value.interfacePlan() + "|" + value.varyingLocations() + "|"
                    + targetFingerprint + "|" + value.convertedFragment() + "|"
                    + value.convertedVertex() + "|" + value.deviations() + "|" + value.executable();
        }).toList();
    }

    private static JsonObject readObject(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
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

    private static final class JsonElementChecks {
        private JsonElementChecks() {}

        private static void verifyBaseline(
                ConformanceReport report,
                JsonObject baseline,
                Path fixture
        ) throws IOException {
            String expectedHash = baseline.get("reportSha256").getAsString();
            if (expectedHash.equals("TO_BE_FILLED")) {
                System.out.println("[chimera] M6.1 fixture report hash: " + report.sha256());
                System.out.println("[chimera] M6.1 fixture source hashes: " + sourceHashes(report));
                System.out.println("[chimera] M6.1 fixture deviations: " + allDeviations(report));
                return;
            }
            assertEquals(expectedHash, report.sha256(), "M6.1 fixture report hash");
            Map<String, String> expectedSources = new TreeMap<>();
            baseline.getAsJsonObject("sourceHashes").entrySet()
                    .forEach(entry -> expectedSources.put(entry.getKey(), entry.getValue().getAsString()));
            assertEquals(expectedSources, sourceHashes(report), "M6.1 fixture source hashes");
            assertEquals(jsonArrayStrings(baseline.getAsJsonArray("expectedExecutablePrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.SUPPORTED
                                    || value.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "M6.1 executable baseline");
            assertEquals(jsonArrayStrings(baseline.getAsJsonArray("expectedFallbackPrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "M6.1 fallback baseline");
            assertEquals(jsonArrayStrings(baseline.getAsJsonArray("expectedDeviations")),
                    allDeviations(report), "M6.1 deviation baseline");
            assertTrue(Files.isDirectory(fixture), "M6.1 fixture disappeared during baseline check");
        }

        private static Map<String, String> sourceHashes(ConformanceReport report) {
            Map<String, String> result = new TreeMap<>();
            for (ConformanceReport.ProgramReport program : report.programs()) {
                program.sourceHashes().forEach(result::putIfAbsent);
            }
            return result;
        }

        private static List<String> allDeviations(ConformanceReport report) {
            TreeSet<String> result = new TreeSet<>(report.deviations());
            for (ConformanceReport.ProgramReport program : report.programs()) {
                result.addAll(program.deviations());
            }
            return result.stream().toList();
        }

        private static List<String> jsonArrayStrings(JsonArray array) {
            List<String> result = new ArrayList<>();
            array.forEach(value -> result.add(value.getAsString()));
            return result;
        }
    }
}
