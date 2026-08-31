package net.chimera.shaderpack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


/** Strict M5.7 check for two explicitly named external shader packs. */
public final class M57ConformanceHarness {
    private M57ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path baseline = Path.of(System.getProperty(
                "chimera.m57.baseline", fixtureRoot.resolve("baselines/m5_7.json").toString()));
        verifyArchiveEdges();
        verifyPreparedSourceEdges();
        List<PackSpec> packs = List.of(new PackSpec("complementary",
                requiredProperty("chimera.m57.complementary"),
                requiredProperty("chimera.m57.complementaryVersion")), new PackSpec("independent",
                requiredProperty("chimera.m57.independent"),
                requiredProperty("chimera.m57.independentVersion")));
        if (Boolean.getBoolean("chimera.m57.emitBaseline")) {
            emitBaseline(packs);
            return;
        }
        JsonObject baselineRoot = readObject(baseline, "M5.7 baseline");
        for (PackSpec pack : packs) {
            verifyExternalPack(pack, baselineRoot);
        }
        System.out.println("[chimera] M5.7 strict multi-pack conformance: PASS");
    }

    private static void verifyExternalPack(PackSpec spec, JsonObject baselineRoot) throws IOException {
        JsonObject actual = snapshot(spec, true);
        verifyEquivalentDirectoryLoading(spec, actual);
        JsonObject packs = childObject(baselineRoot, "packs", "M5.7 baseline packs");
        JsonObject expected = childObject(packs, spec.label(),
                "M5.7 baseline entry for " + spec.label());
        for (String key : List.of("logicalName", "version", "fullPackFingerprint", "reportSha256",
                "executablePrograms", "fallbackPrograms", "unsupportedPrograms", "deviations",
                "programDeviations")) {
            assertEquals(expected.get(key).toString(), actual.get(key).toString(),
                    "M5.7 " + spec.label() + " " + key);
        }
        assertNonEmptyArray(expected, "expectedCaptures", spec.label() + " capture references");
        assertNonEmptyArray(expected, "runtimeEvidence", spec.label() + " runtime evidence references");
    }

    private static JsonObject snapshot(PackSpec spec, boolean requireExecutable) throws IOException {
        assertTrue(Files.isDirectory(spec.path()) || Files.isRegularFile(spec.path()),
                "M5.7 " + spec.label() + " path does not exist: " + spec.path());
        String fingerprint = PackFingerprint.sha256(spec.path());
        assertEquals(fingerprint, PackFingerprint.sha256(spec.path()),
                "M5.7 " + spec.label() + " fingerprint stability");

        PackProbe.Analysis analysis = PackProbe.analyze(spec.path());
        ConformanceReport report = analysis.report();
        ConformanceReport secondReport = PackProbe.probe(spec.path());
        assertEquals(report.toJson(), secondReport.toJson(),
                "M5.7 " + spec.label() + " report stability");
        assertEquals(report.sha256(), secondReport.sha256(),
                "M5.7 " + spec.label() + " report hash stability");
        assertNoUnstableReportData(report, spec.path());

        try (PackSource.LoadResult loaded = PackSource.loadResult(spec.path())) {
            List<String> loadedNames = loaded.programs().stream()
                    .map(PackProgram::name)
                    .toList();
            assertEquals(loadedNames.stream().distinct().count(), (long) loadedNames.size(),
                    "M5.7 " + spec.label() + " duplicate runtime programs");
            verifyConversionBoundary(spec, report, analysis.plan());
        }

        List<ConformanceReport.ProgramReport> executable = new ArrayList<>();
        List<ConformanceReport.ProgramReport> fallback = new ArrayList<>();
        List<ConformanceReport.ProgramReport> unsupported = new ArrayList<>();
        TreeSet<String> deviations = new TreeSet<>(report.deviations());
        Map<String, List<String>> programDeviations = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            boolean eligible = program.support() == ConformanceReport.SupportStatus.SUPPORTED
                    || program.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION;
            assertEquals(eligible, report.shouldAttempt(program.name()),
                    "M5.7 " + spec.label() + " static eligibility: " + program.name());
            assertEquals(ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED,
                    program.runtime(), "M5.7 " + spec.label() + " static runtime: " + program.name());
            deviations.addAll(program.deviations());
            if (!program.deviations().isEmpty()) {
                programDeviations.put(program.name(), program.deviations());
            }
            if (eligible) {
                executable.add(program);
            } else if (program.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK) {
                fallback.add(program);
            } else if (program.support() == ConformanceReport.SupportStatus.UNSUPPORTED) {
                unsupported.add(program);
            }
        }
        String statusSummary = report.programs().stream()
                .map(program -> program.name() + "=" + program.support())
                .sorted()
                .toList()
                .toString();
        if (requireExecutable) {
            assertTrue(!executable.isEmpty(),
                    "M5.7 " + spec.label() + " has no executable program in the Chimera subset; programs="
                            + statusSummary + ", deviations=" + deviations);
        }

        JsonObject result = new JsonObject();
        result.addProperty("logicalName", PackProbe.logicalPackName(spec.path()));
        result.addProperty("version", spec.version());
        result.addProperty("fullPackFingerprint", fingerprint);
        result.addProperty("reportSha256", report.sha256());
        result.add("executablePrograms", strings(names(executable)));
        result.add("fallbackPrograms", strings(names(fallback)));
        result.add("unsupportedPrograms", strings(names(unsupported)));
        result.add("deviations", strings(deviations));
        result.add("programDeviations", programDeviationJson(programDeviations));
        result.add("expectedCaptures", strings(List.of("captures/m5_7_" + spec.label() + ".rdc")));
        result.add("runtimeEvidence", strings(List.of("latest.log", "RenderDoc CLI capture analysis")));
        return result;
    }

    /** Uses the same source, interface, and converter inputs as PackPipelines. */
    private static void verifyConversionBoundary(
            PackSpec spec,
            ConformanceReport report,
            PackPlan plan
    ) {
        for (ConformanceReport.ProgramReport reportProgram : report.programs()) {
            if (!report.shouldAttempt(reportProgram.name())
                    || !PostTargetPlan.isPostProgramName(reportProgram.name())) {
                continue;
            }
            PackProgramPlan planned = plan.program(reportProgram.name());
            assertTrue(planned != null && planned.executable(), "M5.7 " + spec.label()
                    + " eligible source failed shared planning: " + reportProgram.name());
            String converted = planned.convertedFragment();
            assertTrue(converted != null, "M5.7 " + spec.label()
                    + " eligible source failed conversion: " + reportProgram.name());
            assertTrue(!converted.contains("#version 120") && !converted.contains("#version 130"),
                    "M5.7 " + spec.label() + " converted source retained a legacy version: "
                            + reportProgram.name());
        }
    }

    private static void verifyEquivalentDirectoryLoading(PackSpec spec, JsonObject zipSnapshot)
            throws IOException {
        if (!Files.isRegularFile(spec.path())) {
            return;
        }
        Path root = Files.createTempDirectory("chimera-m57-directory-");
        try {
            Path packDirectory = root.resolve(PackProbe.logicalPackName(spec.path()));
            Path destination = packDirectory.resolve("shaders");
            try (PackSource.LoadResult loaded = PackSource.loadResult(spec.path())) {
                copyTree(loaded.shadersDir(), destination);
            }
            ConformanceReport directoryReport = PackProbe.probe(packDirectory);
            JsonObject directorySnapshot = snapshotFromReport(directoryReport, spec);
            for (String key : List.of("logicalName", "reportSha256", "executablePrograms",
                    "fallbackPrograms", "unsupportedPrograms", "deviations", "programDeviations")) {
                assertEquals(zipSnapshot.get(key).toString(), directorySnapshot.get(key).toString(),
                        "M5.7 " + spec.label() + " ZIP/directory " + key);
            }
        } finally {
            deleteTree(root);
        }
    }

    private static JsonObject snapshotFromReport(ConformanceReport report, PackSpec spec) {
        List<ConformanceReport.ProgramReport> executable = new ArrayList<>();
        List<ConformanceReport.ProgramReport> fallback = new ArrayList<>();
        List<ConformanceReport.ProgramReport> unsupported = new ArrayList<>();
        TreeSet<String> deviations = new TreeSet<>(report.deviations());
        Map<String, List<String>> programDeviations = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            boolean eligible = program.support() == ConformanceReport.SupportStatus.SUPPORTED
                    || program.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION;
            deviations.addAll(program.deviations());
            if (!program.deviations().isEmpty()) {
                programDeviations.put(program.name(), program.deviations());
            }
            if (eligible) {
                executable.add(program);
            } else if (program.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK) {
                fallback.add(program);
            } else if (program.support() == ConformanceReport.SupportStatus.UNSUPPORTED) {
                unsupported.add(program);
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("logicalName", PackProbe.logicalPackName(spec.path()));
        result.addProperty("reportSha256", report.sha256());
        result.add("executablePrograms", strings(names(executable)));
        result.add("fallbackPrograms", strings(names(fallback)));
        result.add("unsupportedPrograms", strings(names(unsupported)));
        result.add("deviations", strings(deviations));
        result.add("programDeviations", programDeviationJson(programDeviations));
        return result;
    }

    private static void emitBaseline(List<PackSpec> packs) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);
        root.addProperty("fingerprintAlgorithm", "sha256(sorted relative file names, lengths, and bytes)");
        root.addProperty("performanceMethod", "FPS_PLUS_RENDERDOC_COUNTS");
        root.addProperty("captureDirectory", "captures");
        JsonObject entries = new JsonObject();
        for (PackSpec pack : packs) {
            entries.add(pack.label(), snapshot(pack, false));
        }
        root.add("packs", entries);
        System.out.println(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root));
    }

    private static void verifyArchiveEdges() throws IOException {
        Path root = Files.createTempDirectory("chimera-m57-archive-");
        try {
            Path validZip = root.resolve("valid.zip");
            writeZip(validZip, Map.of(
                    "Example/shaders/shaders.json", "{\"programs\":[{\"name\":\"composite\",\"fragment\":\"composite.fsh\"}]}\n",
                    "Example/shaders/composite.fsh", "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }\n"));
            Path extracted;
            try (PackSource.LoadResult loaded = PackSource.loadResult(validZip)) {
                extracted = loaded.shadersDir();
                assertTrue(Files.isRegularFile(extracted.resolve("composite.fsh")),
                        "M5.7 ZIP shader source was not extracted");
                assertEquals(1L, loaded.programs().stream().map(PackProgram::name).distinct().count(),
                        "M5.7 ZIP program count");
            }
            assertTrue(!Files.exists(extracted), "M5.7 ZIP extraction was not cleaned up");

            Path duplicateZip = root.resolve("duplicate.zip");
            writeZip(duplicateZip, Map.of(
                    "shaders/shaders.json", "{\"programs\":[{\"name\":\"duplicate\",\"fragment\":\"b.fsh\"},{\"name\":\"duplicate\",\"fragment\":\"a.fsh\"}]}\n",
                    "shaders/a.fsh", "a\n",
                    "shaders/b.fsh", "b\n"));
            try (PackSource.LoadResult loaded = PackSource.loadResult(duplicateZip)) {
                assertEquals(1, loaded.programs().size(), "M5.7 duplicate program selection");
                assertTrue(loaded.deviations().contains("DUPLICATE_PROGRAM:duplicate"),
                        "M5.7 duplicate program deviation");
                assertEquals("a.fsh",
                        loaded.shadersDir().relativize(loaded.programs().get(0).fragmentPath())
                                .toString().replace('\\', '/'),
                        "M5.7 duplicate deterministic source");
            }

            Path unsafeZip = root.resolve("unsafe.zip");
            writeZip(unsafeZip, Map.of(
                    "../outside.fsh", "bad\n",
                    "shaders/composite.fsh", "good\n"));
            try (PackSource.LoadResult loaded = PackSource.loadResult(unsafeZip)) {
                assertTrue(loaded.programs().isEmpty(), "M5.7 unsafe ZIP was accepted");
                assertTrue(loaded.deviations().contains("PACK_ARCHIVE_UNSAFE_PATH"),
                        "M5.7 unsafe ZIP deviation");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyPreparedSourceEdges() throws IOException {
        Path root = Files.createTempDirectory("chimera-m57-source-");
        try {
            Path shaders = root.resolve("shaders");
            Path world0 = shaders.resolve("world0");
            Path program = shaders.resolve("program");
            Files.createDirectories(world0);
            Files.createDirectories(program);
            Files.createDirectories(shaders.resolve("tex"));
            Files.writeString(world0.resolve("final.fsh"), """
                    #version 130
                    #define FRAGMENT_SHADER
                    #define DIMENSION_OVERWORLD
                    #include "/program/final.glsl"
                    """, StandardCharsets.UTF_8);
            Files.writeString(program.resolve("final.glsl"), """
                    #if defined(FRAGMENT_SHADER) && (2 > 1)
                    uniform sampler2D colortex0;
                    uniform sampler2D noisetex;
                    #else
                    uniform sampler2D inactiveSampler;
                    #endif
                    void main() {
                        gl_FragColor = texture2D(colortex0, vec2(0.0)) +
                                texture2D(noisetex, vec2(0.0));
                    }
                    """, StandardCharsets.UTF_8);
            Files.writeString(shaders.resolve("tex/noise.png"), "fixture-noise",
                    StandardCharsets.UTF_8);

            try (PackSource.LoadResult loaded = PackSource.loadResult(root)) {
                assertEquals("world0", loaded.selectedVariantFolder(),
                        "M5.7 prepared directory dimension selection");
                assertEquals("minecraft:overworld", loaded.selectedDimension(),
                        "M5.7 prepared directory dimension name");
                PackProgram finalProgram = loaded.programs().stream()
                        .filter(programEntry -> programEntry.name().equals("final"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("M5.7 prepared final source is missing"));
                String prepared = finalProgram.executableFragmentSource();
                assertTrue(prepared != null && prepared.contains("uniform sampler2D colortex0"),
                        "M5.7 root-relative include was not expanded");
                assertTrue(prepared.contains("uniform sampler2D noisetex"),
                        "M5.7 active conditional declaration was removed");
                assertTrue(!prepared.contains("inactiveSampler"),
                        "M5.7 inactive conditional declaration was retained");
                assertEquals(shaders.resolve("tex/noise.png").toAbsolutePath().normalize(),
                        loaded.findNoiseTexture().toAbsolutePath().normalize(),
                        "M5.7 pack noise resource resolution");

                loaded.selectDimension("custom:moon");
                assertEquals("world0", loaded.selectedVariantFolder(),
                        "M5.7 custom dimension source fallback");
                assertTrue(loaded.deviations().contains(
                                "DIMENSION_SOURCE_FALLBACK_TO_WORLD0:custom:moon"),
                        "M5.7 custom dimension fallback deviation");
            }

            String simple130 = """
                    #version 130
                    noperspective in vec2 texCoord;
                    uniform sampler2D colortex0;
                    void main() {
                        gl_FragColor = texture2D(colortex0, texCoord);
                    }
                    """;
            PostTargetPlan targetPlan = PostTargetPlan.parse("final", simple130).plan();
            UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.planPreparedPost(
                    simple130, targetPlan);
            String converted = LegacyGlslConverter.convertPostFragment(
                    simple130, null, interfacePlan, targetPlan);
            assertTrue(converted != null, "M5.7 GLSL 130 post conversion failed");
            assertTrue(converted.contains("layout(location = 0) out vec4 chimeraFragColor0"),
                    "M5.7 GLSL 130 output declaration is missing");
            assertTrue(converted.contains("layout(location = 0) in vec2 texCoord"),
                    "M5.7 GLSL 130 input varying bridge is missing");
            assertTrue(converted.contains("texture(colortex0"),
                    "M5.7 texture2D conversion is missing");

            Path zip = root.resolve("prepared.zip");
            writeZip(zip, Map.of(
                    "ShaderPack/shaders/world0/final.fsh",
                    Files.readString(world0.resolve("final.fsh"), StandardCharsets.UTF_8),
                    "ShaderPack/shaders/program/final.glsl",
                    Files.readString(program.resolve("final.glsl"), StandardCharsets.UTF_8),
                    "ShaderPack/shaders/tex/noise.png", "fixture-noise\n"));
            Path extracted;
            try (PackSource.LoadResult loaded = PackSource.loadResult(zip)) {
                extracted = loaded.shadersDir();
                assertEquals("world0", loaded.selectedVariantFolder(),
                        "M5.7 prepared ZIP dimension selection");
                assertTrue(loaded.programs().stream().anyMatch(
                                programEntry -> programEntry.name().equals("final")
                                        && programEntry.executableFragmentSource().contains("colortex0")),
                        "M5.7 prepared ZIP source was not expanded");
                assertTrue(loaded.findNoiseTexture() != null,
                        "M5.7 prepared ZIP noise resource is missing");
            }
            assertTrue(!Files.exists(extracted), "M5.7 prepared ZIP extraction was not cleaned up");

            verifyMacroNormalization(shaders);
            verifyConverterEdges(shaders);
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyMacroNormalization(Path shaders) throws IOException {
        Path sourcePath = shaders.resolve("macro.fsh");
        String source = "#version 130\n"
                + "#define MODE 1\n"
                + "#define MODE 1\n"
                + "#define MODE 2\n"
                + "#if MODE == 2\n"
                + "void main() { gl_FragColor = vec4(1.0); }\n"
                + "#endif\n";
        Files.writeString(sourcePath, source, StandardCharsets.UTF_8);
        ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepare(
                shaders, sourcePath, source);
        assertTrue(prepared.successful(), "M5.7 macro normalization failed");
        assertTrue(prepared.source().contains("#undef MODE"),
                "M5.7 changed macro did not emit an undefinition");
        assertEquals(1, count(prepared.source(), "#define MODE 1"),
                "M5.7 identical macro redefinition was not omitted");
        assertEquals(1, count(prepared.source(), "#define MODE 2"),
                "M5.7 changed macro definition was not retained");
        assertTrue(prepared.deviations().contains("PREPROCESSOR_MACRO_REDEFINED:MODE"),
                "M5.7 macro redefinition deviation is missing");
    }

    private static void verifyConverterEdges(Path shaders) {
        String simple130 = """
                #version 130
                #extension GL_ARB_shader_texture_lod : enable
                noperspective in vec2 texCoord;
                uniform sampler2D colortex0;
                const int colortex0Format = R11F_G11F_B10F;
                const int gaux0Format = RGBA16F;
                const float shadowDistance = 192.0;
                void main() {
                    gl_FragColor = texture2DLod(colortex0, texCoord, shadowDistance * 0.0);
                }
                """;
        PostTargetPlan target = PostTargetPlan.parse("final", simple130).plan();
        UniformRegistry.ProgramInterface plan = UniformRegistry.planPreparedPost(simple130, target);
        String converted = LegacyGlslConverter.convertPostFragment(
                simple130, shaders.resolve("final.fsh"), plan, target,
                Map.of("shadowDistance", "192.0"));
        assertTrue(converted != null, "M5.7 metadata and constant conversion failed");
        assertEquals(1, count(converted, "#version 460"),
                "M5.7 converter emitted duplicate version directives");
        assertTrue(!converted.contains("#version 130"),
                "M5.7 converter retained a legacy version directive");
        assertTrue(!converted.contains("GL_ARB_shader_texture_lod"),
                "M5.7 converter retained the legacy texture-lod extension");
        assertTrue(!converted.contains("colortex0Format") && !converted.contains("gaux0Format"),
                "M5.7 converter retained pack metadata declarations");
        assertTrue(converted.contains("const float shadowDistance = 192.0;"),
                "M5.7 converter did not inject the parsed pack constant");
        assertTrue(converted.contains("layout(location = 0) in vec2 texCoord"),
                "M5.7 fixed fullscreen varying was not accepted");

        String unsupported = """
                #version 120
                varying vec3 sunVec, upVec;
                void main() {
                    gl_FragColor = vec4(dot(sunVec, upVec));
                }
                """;
        PostTargetPlan unsupportedTarget = PostTargetPlan.parse("final", unsupported).plan();
        UniformRegistry.ProgramInterface unsupportedPlan = UniformRegistry.planPreparedPost(
                unsupported, unsupportedTarget);
        assertTrue(LegacyGlslConverter.convertPostFragment(
                        unsupported, shaders.resolve("final.fsh"), unsupportedPlan, unsupportedTarget) == null,
                "M5.7 unsupported fullscreen varying was converted");
        assertTrue(LegacyGlslConverter.postVaryingDeviations(unsupported).contains(
                        "POST_VARYING_UNSUPPORTED:sunVec"),
                "M5.7 unsupported varying deviation is missing");
        assertTrue(LegacyGlslConverter.postVaryingDeviations(unsupported).contains(
                        "POST_VARYING_UNSUPPORTED:upVec"),
                "M5.7 second unsupported varying deviation is missing");

        PackConfig.PackConfigData defaulted = PackConfig.parse(List.of(new PackProgram(
                "final", "const float shadowDistance = 9999.0;\n", shaders.resolve("final.fsh"))), shaders);
        assertEquals("128.0", defaulted.shaderConstants().get("shadowDistance"),
                "M5.7 invalid shadow constant did not use the validated default");
    }

    private static int count(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void writeZip(Path target, Map<String, String> files) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static JsonObject readObject(Path path, String label) throws IOException {
        assertTrue(Files.isRegularFile(path), label + " is missing: " + path);
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException(label + " is invalid: " + path, e);
        }
    }

    private static JsonObject childObject(JsonObject parent, String key, String label) {
        JsonElement value = parent.get(key);
        assertTrue(value != null && value.isJsonObject(), label + " is missing");
        return value.getAsJsonObject();
    }

    private static void assertNoUnstableReportData(ConformanceReport report, Path packPath) {
        String json = report.toJson();
        assertTrue(!json.contains(packPath.toAbsolutePath().toString()), "M5.7 report contains an absolute path");
        assertTrue(!json.contains("chimera-pack-"), "M5.7 report contains a temporary path");
        assertTrue(!json.contains("timestamp") && !json.contains("Timestamp"),
                "M5.7 report contains a timestamp field");
    }

    private static List<String> names(List<ConformanceReport.ProgramReport> programs) {
        return programs.stream().map(ConformanceReport.ProgramReport::name).sorted().toList();
    }

    private static JsonObject programDeviationJson(Map<String, List<String>> deviations) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, List<String>> entry : new TreeMap<>(deviations).entrySet()) {
            object.add(entry.getKey(), strings(entry.getValue()));
        }
        return object;
    }

    private static com.google.gson.JsonArray strings(Iterable<String> values) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static void assertNonEmptyArray(JsonObject object, String key, String label) {
        JsonElement value = object.get(key);
        assertTrue(value != null && value.isJsonArray() && !value.getAsJsonArray().isEmpty(),
                "M5.7 " + label + " are missing");
    }

    private static String requiredProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + key);
        }
        return value;
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new ArchiveCleanupFailure(e);
                }
            });
        } catch (ArchiveCleanupFailure e) {
            throw e.exception;
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        assertTrue(Files.isDirectory(source), "M5.7 source tree is missing: " + source);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private record PackSpec(String label, Path path, String version) {
        private PackSpec(String label, String path, String version) {
            this(label, Path.of(path), version);
        }
    }

    private static final class ArchiveCleanupFailure extends RuntimeException {
        private final IOException exception;

        private ArchiveCleanupFailure(IOException exception) {
            this.exception = exception;
        }
    }
}
