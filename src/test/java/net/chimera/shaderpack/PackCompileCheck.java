package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles every program a pack plan would install, through the runtime preparation seam
 * ({@link PackPipelines#prepare}) and VulkanMod's shaderc target (SPIRVUtils: Vulkan 1.2), so a
 * converter defect shows up offline instead of as a failed pipeline in game. Failing sources are
 * written to build/pack-report/ for inspection. Used by the pack report and the compile gate.
 */
final class PackCompileCheck {
    record Result(int compiled, List<String> failures) {}

    private PackCompileCheck() {}

    static Result run(Path pack, PackProbe.Analysis analysis) {
        if (analysis.plan() == null) return new Result(0, List.of());
        String fixedPostVertex = net.chimera.render.shader.ChimeraShaderLoader.loadSource(
                "chimera_composite/chimera_composite.vsh");
        String fixedTerrainVertex = net.chimera.render.shader.ChimeraShaderLoader.loadSource(
                "chimera_terrain/chimera_terrain.vsh");
        int compiled = 0;
        List<String> failures = new ArrayList<>();
        for (PackProgramPlan program : analysis.plan().activePrograms()) {
            // Runtime also gates on the report. Compiling alone cannot prove admission.
            if (analysis.plan().shouldAttempt(program.name())
                    && !analysis.report().shouldAttempt(program.name())) {
                failures.add(program.name() + " report/plan admission disagreement: "
                        + analysis.report().program(program.name()).deviations());
            }
            if (!analysis.plan().shouldAttempt(program.name()) || program.convertedFragment() == null
                    || program.interfacePlan() == null) {
                continue;
            }
            UniformRegistry.Stage stage = FamilyAdapterRegistry.stageFor(program.name());
            // The runtime pairs a pack fragment with Chimera's fixed vertex where the pack has none.
            String vertex = program.convertedVertex() != null ? program.convertedVertex()
                    : stage == UniformRegistry.Stage.POST ? fixedPostVertex
                    : stage == UniformRegistry.Stage.GEOMETRY || stage == UniformRegistry.Stage.TRANSLUCENT
                    ? fixedTerrainVertex : program.convertedFragment();
            PackPipelines.PreparedPipeline prepared;
            try {
                prepared = PackPipelines.prepare(program, analysis.plan().advancedResources(), stage, vertex);
            } catch (RuntimeException failure) {
                failures.add(program.name() + " preparation: " + failure);
                continue;
            }
            String vertexError = program.convertedVertex() == null ? null : compile(prepared.vertex(), true);
            String fragmentError = compile(prepared.fragment(), false);
            if (FamilyAdapterRegistry.isHandFamily(program.name())) {
                for (var host : net.chimera.render.vertex.ChimeraVertexFormats.handFormats().keySet()) {
                    String variant = LegacyGlslConverter.handVertexForFormat(program.convertedVertex(), host);
                    String error = compile(PackPipelines.prepare(program, analysis.plan().advancedResources(), stage, variant).vertex(), true);
                    if (error != null) {
                        failures.add(program.name() + " hand input variant: " + error);
                        vertexError = error;
                    }
                }
            }
            if (FamilyAdapterRegistry.isSkyFamily(program.name())) {
                for (var contract : LegacyGlslConverter.SKY_CONTRACTS) {
                    String variant = LegacyGlslConverter.skyVertexForContract(program.convertedVertex(), contract);
                    String error = compile(PackPipelines.prepare(program, analysis.plan().advancedResources(), stage, variant).vertex(), true);
                    if (error != null) {
                        failures.add(program.name() + " sky input variant " + contract + ": " + error);
                        vertexError = error;
                    }
                }
            }
            if (vertexError != null) failures.add(program.name() + " vertex: " + vertexError
                    + " [" + dump(pack, program.name() + ".vsh", prepared.vertex()) + "]");
            if (fragmentError != null) failures.add(program.name() + " fragment: " + fragmentError
                    + " [" + dump(pack, program.name() + ".fsh", prepared.fragment()) + "]");
            if (vertexError == null && fragmentError == null) compiled++;
        }
        return new Result(compiled, List.copyOf(failures));
    }

    /** VulkanMod's shaderc settings; returns the first error line, or null on success. */
    static String compile(String source, boolean vertex) {
        long compiler = org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize();
        long options = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize();
        // Heap buffers and explicit sizes: large sources overflow LWJGL's stack
        // (M87EntityRealPackHarness.compileStage).
        java.nio.ByteBuffer text = org.lwjgl.system.MemoryUtil.memUTF8(source);
        java.nio.ByteBuffer name = org.lwjgl.system.MemoryUtil.memUTF8(vertex ? "pack.vsh" : "pack.fsh");
        java.nio.ByteBuffer entry = org.lwjgl.system.MemoryUtil.memASCII("main");
        long result = 0;
        try {
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env(options,
                    org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_vulkan,
                    org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_2);
            result = org.lwjgl.util.shaderc.Shaderc.nshaderc_compile_into_spv(compiler,
                    org.lwjgl.system.MemoryUtil.memAddress(text), text.remaining() - 1,
                    vertex ? org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader
                            : org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader,
                    org.lwjgl.system.MemoryUtil.memAddress(name),
                    org.lwjgl.system.MemoryUtil.memAddress(entry), options);
            if (result != 0 && org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(result)
                    == org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success) {
                return null;
            }
            String message = result == 0 ? "no result"
                    : org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(result);
            if (message == null) return "unknown";
            // Warnings precede errors in shaderc's report; name the first error.
            return message.strip().lines().filter(line -> line.contains("error")).findFirst()
                    .orElse(message.strip().lines().findFirst().orElse("unknown"));
        } finally {
            if (result != 0) org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release(options);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler);
            org.lwjgl.system.MemoryUtil.memFree(entry);
            org.lwjgl.system.MemoryUtil.memFree(name);
            org.lwjgl.system.MemoryUtil.memFree(text);
        }
    }

    private static String dump(Path pack, String file, String source) {
        try {
            Path directory = Path.of("build", "pack-report",
                    pack.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_"));
            java.nio.file.Files.createDirectories(directory);
            Path target = directory.resolve(file);
            java.nio.file.Files.writeString(target, source);
            return target.toString();
        } catch (java.io.IOException failure) {
            return "dump failed: " + failure.getMessage();
        }
    }

    /** Programs of the selected dimension the plan will not attempt, sorted. */
    static java.util.SortedSet<String> rejectedPrograms(PackProbe.Analysis analysis) {
        java.util.SortedSet<String> rejected = new java.util.TreeSet<>();
        if (analysis.plan() == null) return rejected;
        for (PackProgramPlan program : analysis.plan().activePrograms()) {
            if (!analysis.plan().shouldAttempt(program.name())) rejected.add(program.name());
        }
        return rejected;
    }

    /** The report deviations that say why a program was rejected, for gate messages. */
    private static List<String> rejectionReasons(PackProbe.Analysis analysis, String program) {
        var report = analysis.report().program(program);
        if (report == null) return List.of();
        return report.deviations().stream().filter(deviation -> deviation.contains("UNSUPPORTED")
                || deviation.startsWith("PLAN_INELIGIBLE") || deviation.contains("FAILED")
                || deviation.contains("MALFORMED")).toList();
    }

    /**
     * Gate: every program the named packs install must compile, and the set each pack's plan
     * rejects must match the recorded admission baseline. Compiling only admitted programs cannot
     * see a program the plan newly rejects (MakeUp 9.5g terrain fell back in game while every
     * admitted program compiled). Packs come from {@code -Dchimera.packCompile.packs}
     * (path-separator list); {@code -Dchimera.packCompile.admissionBaseline} names the baseline,
     * and {@code -Dchimera.packCompile.updateAdmission=true} rewrites it.
     */
    public static void main(String[] args) throws java.io.IOException {
        String packs = System.getProperty("chimera.packCompile.packs", "");
        if (packs.isBlank()) {
            throw new AssertionError("chimera.packCompile.packs is not set");
        }
        Path baselinePath = Path.of(System.getProperty("chimera.packCompile.admissionBaseline",
                "testpacks/baselines/pack_admission.json"));
        boolean update = Boolean.getBoolean("chimera.packCompile.updateAdmission");
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        java.lang.reflect.Type baselineType =
                new com.google.gson.reflect.TypeToken<java.util.TreeMap<String, java.util.TreeSet<String>>>() {}.getType();
        java.util.TreeMap<String, java.util.TreeSet<String>> baseline = java.nio.file.Files.isRegularFile(baselinePath)
                ? gson.fromJson(java.nio.file.Files.readString(baselinePath), baselineType)
                : new java.util.TreeMap<>();
        List<String> failures = new ArrayList<>();
        for (String entry : packs.split(java.io.File.pathSeparator)) {
            Path pack = Path.of(entry.trim());
            String key = pack.getFileName().toString();
            PackProbe.Analysis analysis = PackProbe.analyze(pack);
            Result result = run(pack, analysis);
            java.util.SortedSet<String> rejected = rejectedPrograms(analysis);
            System.out.printf("[chimera] pack compile %s: %d programs compile, %d failures, %d rejected%n",
                    key, result.compiled(), result.failures().size(), rejected.size());
            if (result.compiled() == 0) failures.add(key + ": no program compiled");
            result.failures().forEach(failure -> failures.add(key + " " + failure));
            if (update) {
                baseline.put(key, new java.util.TreeSet<>(rejected));
                continue;
            }
            java.util.Set<String> expected = baseline.get(key);
            if (expected == null) {
                failures.add(key + ": no admission baseline; rejected=" + rejected
                        + " (run with -PpackAdmissionUpdate=true after reviewing)");
                continue;
            }
            for (String program : rejected) {
                if (!expected.contains(program)) {
                    failures.add(key + " " + program + " newly rejected: " + rejectionReasons(analysis, program));
                }
            }
            for (String program : expected) {
                if (!rejected.contains(program)) {
                    failures.add(key + " " + program + " is now admitted; record it with -PpackAdmissionUpdate=true");
                }
            }
        }
        if (update) {
            java.nio.file.Files.writeString(baselinePath, gson.toJson(baseline) + System.lineSeparator());
            System.out.println("[chimera] pack admission baseline written: " + baselinePath);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("pack compile gate failed:\n  " + String.join("\n  ", failures));
        }
        System.out.println("[chimera] pack compile gate: PASS");
    }
}
