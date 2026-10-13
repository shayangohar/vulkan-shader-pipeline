package net.chimera.shaderpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.chimera.render.PackShadowCompute;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compiles every program a pack plan would install, through the runtime preparation seam
 * ({@link PackPipelines#prepare}) and VulkanMod's shaderc target (SPIRVUtils: Vulkan 1.2), so a
 * converter defect shows up offline instead of as a failed pipeline in game. Failing sources are
 * written to build/pack-report/ for inspection. Used by the pack report and the compile gate.
 */
@Tag("packs")
final class PackCompileTest {
    record Result(int compiled, List<String> failures) {}

    static Result run(Path pack, PackProbe.Analysis analysis) {
        if (analysis.plan() == null) return new Result(0, List.of());
        String fixedPostVertex = ChimeraShaderLoader.loadSource(
                "chimera_composite/chimera_composite.vsh");
        String fixedTerrainVertex = ChimeraShaderLoader.loadSource(
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
                for (var host : ChimeraVertexFormats.handFormats().keySet()) {
                    String variant = EntityVertexBridge.handVertexForFormat(program.convertedVertex(), host);
                    String error = compile(PackPipelines.prepare(program, analysis.plan().advancedResources(), stage, variant).vertex(), true);
                    if (error != null) {
                        failures.add(program.name() + " hand input variant: " + error);
                        vertexError = error;
                    }
                }
            }
            if (program.name().equals("gbuffers_entities_translucent") || program.name().equals("gbuffers_block")) {
                // Iris GLYPH text variant (TEXT and TEXT_BE).
                String variant = EntityVertexBridge.layoutVertexForFormat(program.convertedVertex(),
                        DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
                String error = compile(PackPipelines.prepare(program, analysis.plan().advancedResources(), stage, variant).vertex(), true);
                if (error != null) {
                    failures.add(program.name() + " text input variant: " + error);
                    vertexError = error;
                }
            }
            if (FamilyAdapterRegistry.isGlintFamily(program.name())) {
                // First-person glint: the same program under the hand projection.
                String variant = EntityVertexBridge.handGlintVertex(program.convertedVertex());
                String error = compile(PackPipelines.prepare(program, analysis.plan().advancedResources(), stage, variant).vertex(), true);
                if (error != null) {
                    failures.add(program.name() + " hand glint variant: " + error);
                    vertexError = error;
                }
            }
            if (FamilyAdapterRegistry.isSkyFamily(program.name())) {
                for (var contract : HostLayoutVertexBridge.SKY_CONTRACTS) {
                    String variant = HostLayoutVertexBridge.skyVertexForContract(program.convertedVertex(), contract);
                    String error = compile(PackPipelines.prepare(program, analysis.plan().advancedResources(), stage, variant).vertex(), true);
                    if (error != null) {
                        failures.add(program.name() + " sky input variant " + contract + ": " + error);
                        vertexError = error;
                    }
                }
            }
            // -Pchimera.packCompile.dumpProgram=<name> writes that program's prepared stages for review.
            if (program.name().equals(System.getProperty("chimera.packCompile.dumpProgram"))) {
                dump(pack, program.name() + ".vsh", prepared.vertex());
                dump(pack, program.name() + ".fsh", prepared.fragment());
            }
            if (vertexError != null) failures.add(program.name() + " vertex: " + vertexError
                    + " [" + dump(pack, program.name() + ".vsh", prepared.vertex()) + "]");
            if (fragmentError != null) failures.add(program.name() + " fragment: " + fragmentError
                    + " [" + dump(pack, program.name() + ".fsh", prepared.fragment()) + "]");
            if (vertexError == null && fragmentError == null) compiled++;
        }
        String computeError = compileShadowCompute(pack, analysis.plan());
        if (computeError != null) {
            failures.add("shadowcomp: " + computeError);
        } else if (hasShadowCompute(analysis.plan())) {
            compiled++;
        }
        return new Result(compiled, List.copyOf(failures));
    }

    private static boolean hasShadowCompute(PackPlan plan) {
        PackAdvancedResourcePlan advanced = plan.advancedResources();
        if (advanced == null || !advanced.capabilityPossible()) return false;
        PackAdvancedResourcePlan.ComputeSpec spec = advanced.computeStages().get("shadowcomp");
        return spec != null && spec.supported();
    }

    /**
     * Translates the plan's supported shadowcomp exactly as the runtime does
     * ({@link net.chimera.render.PackShadowCompute#translatedModule}), compiles it, and checks
     * its uniform block against the offsets dispatch writes. Returns the first error, or null
     * when it passes or the plan schedules no shadowcomp. Solas fell to vanilla in game on a
     * translation failure no offline check ran.
     */
    static String compileShadowCompute(Path pack, PackPlan plan) {
        if (!hasShadowCompute(plan)) return null;
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack)) {
            var module = PackShadowCompute.translatedModule(
                    plan.advancedResources(), loaded.shadersDir(),
                    plan.settingsPreprocessorDefines(), plan.settingsOverriddenNames());
            Map<String, Integer> written = new LinkedHashMap<>();
            for (var uniform : module.uniforms()) written.put(uniform.name(), uniform.offset());
            String error = compileComputeValues(module.source(), written);
            if (error != null) error += " [" + dump(pack, "shadowcomp.csh", module.source()) + "]";
            return error;
        } catch (IOException | RuntimeException failure) {
            return "translation failed: " + failure.getMessage();
        }
    }

    /**
     * Compiles a compute source and checks that its ChimeraShadowComputeValues block reads each
     * value at the byte offset dispatch writes it. Returns the first error or disagreement, or
     * null. Solas's frameCounter, declared after a vec3, read the vec3's padding word under
     * implicit std140 packing and stayed 0, so its floodfill wrote one image only and flickered.
     */
    static String compileComputeValues(String source, Map<String, Integer> written) {
        Compiled compiled = spirv(source, Shaderc.shaderc_glsl_compute_shader, "pack.csh");
        if (compiled.error() != null) return compiled.error();
        Map<String, Integer> read = blockMemberOffsets(compiled.words(), "ChimeraShadowComputeValues");
        for (var value : written.entrySet()) {
            Integer offset = read.get(value.getKey());
            if (!value.getValue().equals(offset)) {
                return "uniform " + value.getKey() + " is written at offset " + value.getValue()
                        + " but the compiled block reads offset " + offset;
            }
        }
        return null;
    }

    /** Byte offset of each member of the named block, by member name, from SPIR-V decorations. */
    private static Map<String, Integer> blockMemberOffsets(int[] words, String block) {
        Map<Integer, String> names = new HashMap<>();
        Map<Long, String> memberNames = new HashMap<>();
        Map<Long, Integer> memberOffsets = new HashMap<>();
        for (int index = 5; index < words.length; ) {
            int count = words[index] >>> 16;
            int opcode = words[index] & 0xFFFF;
            if (count == 0) throw new IllegalStateException("malformed SPIR-V at word " + index);
            if (opcode == 5) { // OpName
                names.put(words[index + 1], spirvString(words, index + 2, index + count));
            } else if (opcode == 6) { // OpMemberName
                memberNames.put(member(words[index + 1], words[index + 2]),
                        spirvString(words, index + 3, index + count));
            } else if (opcode == 72 && words[index + 3] == 35) { // OpMemberDecorate ... Offset
                memberOffsets.put(member(words[index + 1], words[index + 2]), words[index + 4]);
            }
            index += count;
        }
        Map<String, Integer> offsets = new HashMap<>();
        for (var name : names.entrySet()) {
            if (!name.getValue().equals(block)) continue;
            for (var member : memberNames.entrySet()) {
                if ((int) (member.getKey() >>> 32) != name.getKey()) continue;
                offsets.put(member.getValue(), memberOffsets.get(member.getKey()));
            }
        }
        return offsets;
    }

    private static long member(int type, int member) {
        return ((long) type << 32) | (member & 0xFFFFFFFFL);
    }

    private static String spirvString(int[] words, int from, int end) {
        StringBuilder text = new StringBuilder();
        for (int index = from; index < end; index++) {
            for (int shift = 0; shift < 32; shift += 8) {
                int character = (words[index] >>> shift) & 0xFF;
                if (character == 0) return text.toString();
                text.append((char) character);
            }
        }
        return text.toString();
    }

    /** VulkanMod's shaderc settings; returns the first error line, or null on success. */
    static String compile(String source, boolean vertex) {
        return vertex ? compile(source, Shaderc.shaderc_glsl_vertex_shader, "pack.vsh")
                : compile(source, Shaderc.shaderc_glsl_fragment_shader, "pack.fsh");
    }

    /** A shaderc result: the SPIR-V words on success, otherwise the first error line. */
    private record Compiled(int[] words, String error) {}

    private static String compile(String source, int kind, String fileName) {
        return spirv(source, kind, fileName).error();
    }

    private static Compiled spirv(String source, int kind, String fileName) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        // Heap buffers and explicit sizes: large sources overflow LWJGL's stack
        // (EntityRealPackTest.compileStage).
        ByteBuffer text = MemoryUtil.memUTF8(source);
        ByteBuffer name = MemoryUtil.memUTF8(fileName);
        ByteBuffer entry = MemoryUtil.memASCII("main");
        long result = 0;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                    Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.nshaderc_compile_into_spv(compiler,
                    MemoryUtil.memAddress(text), text.remaining() - 1,
                    kind,
                    MemoryUtil.memAddress(name),
                    MemoryUtil.memAddress(entry), options);
            if (result != 0 && Shaderc.shaderc_result_get_compilation_status(result)
                    == Shaderc.shaderc_compilation_status_success) {
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                int[] words = new int[bytes.remaining() / 4];
                bytes.order(ByteOrder.nativeOrder()).asIntBuffer().get(words);
                return new Compiled(words, null);
            }
            String message = result == 0 ? "no result"
                    : Shaderc.shaderc_result_get_error_message(result);
            if (message == null) return new Compiled(null, "unknown");
            // Warnings precede errors in shaderc's report; name the first error.
            return new Compiled(null, message.strip().lines().filter(line -> line.contains("error")).findFirst()
                    .orElse(message.strip().lines().findFirst().orElse("unknown")));
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
            MemoryUtil.memFree(entry);
            MemoryUtil.memFree(name);
            MemoryUtil.memFree(text);
        }
    }

    private static String dump(Path pack, String file, String source) {
        try {
            Path directory = Path.of("build", "pack-report",
                    pack.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_"));
            Files.createDirectories(directory);
            Path target = directory.resolve(file);
            Files.writeString(target, source);
            return target.toString();
        } catch (IOException failure) {
            return "dump failed: " + failure.getMessage();
        }
    }

    /** Programs of the selected dimension the plan will not attempt, sorted. */
    static SortedSet<String> rejectedPrograms(PackProbe.Analysis analysis) {
        SortedSet<String> rejected = new TreeSet<>();
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
     * admitted program compiled). The packs are the
     * locked pair plus {@code chimera.pack.extra}; {@code -Pchimera.packCompile.updateAdmission=true}
     * rewrites the baseline after review.
     */
    @Test
    void compileLockedPacks() throws Exception {
        List<Path> packs = new ArrayList<>(List.of(TestPacks.complementary(), TestPacks.bsl()));
        packs.addAll(TestPacks.extra());
        Path baselinePath = TestPacks.baseline("pack_admission");
        boolean update = Boolean.getBoolean("chimera.packCompile.updateAdmission");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Type baselineType =
                new TypeToken<TreeMap<String, TreeSet<String>>>() {}.getType();
        TreeMap<String, TreeSet<String>> baseline = Files.isRegularFile(baselinePath)
                ? gson.fromJson(Files.readString(baselinePath), baselineType)
                : new TreeMap<>();
        List<String> failures = new ArrayList<>();
        for (Path pack : packs) {
            String key = pack.getFileName().toString();
            PackProbe.Analysis analysis = PackProbe.analyze(pack);
            Result result = run(pack, analysis);
            SortedSet<String> rejected = rejectedPrograms(analysis);
            System.out.printf("[chimera] pack compile %s: %d programs compile, %d failures, %d rejected%n",
                    key, result.compiled(), result.failures().size(), rejected.size());
            if (result.compiled() == 0) failures.add(key + ": no program compiled");
            result.failures().forEach(failure -> failures.add(key + " " + failure));
            if (update) {
                baseline.put(key, new TreeSet<>(rejected));
                continue;
            }
            Set<String> expected = baseline.get(key);
            if (expected == null) {
                failures.add(key + ": no admission baseline; rejected=" + rejected
                        + " (run with -Pchimera.packCompile.updateAdmission=true after reviewing)");
                continue;
            }
            for (String program : rejected) {
                if (!expected.contains(program)) {
                    failures.add(key + " " + program + " newly rejected: " + rejectionReasons(analysis, program));
                }
            }
            for (String program : expected) {
                if (!rejected.contains(program)) {
                    failures.add(key + " " + program + " is now admitted; record it with -Pchimera.packCompile.updateAdmission=true");
                }
            }
        }
        if (update) {
            Files.writeString(baselinePath, gson.toJson(baseline) + System.lineSeparator());
            System.out.println("[chimera] pack admission baseline written: " + baselinePath);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("pack compile gate failed:\n  " + String.join("\n  ", failures));
        }
    }
}
