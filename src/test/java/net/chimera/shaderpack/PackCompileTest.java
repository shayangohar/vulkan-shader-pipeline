package net.chimera.shaderpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.shader.GlslCompiler;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Compiles every program a pack plan would install, through the runtime preparation seam
 * ({@link PackPipelines#prepare}) and the runtime's compiler ({@link GlslCompiler}), so a
 * converter defect shows up offline instead of as a failed pipeline in game. Failing sources are
 * written to build/pack-report/ for inspection. Used by the pack report and the compile gate.
 */
@Tag("packs")
final class PackCompileTest {
    /** The file extension each stage between the vertex and fragment stages records under. */
    private static final Map<String, String> BETWEEN_EXTENSIONS = Map.of("tess_control", ".tcs",
            "tess_evaluation", ".tes", "geometry", ".gsh");
    /** The shaderc kind each stage between the vertex and fragment stages compiles as. */
    private static final Map<String, Integer> BETWEEN_KINDS = Map.of(
            "tess_control", Shaderc.shaderc_glsl_tess_control_shader,
            "tess_evaluation", Shaderc.shaderc_glsl_tess_evaluation_shader,
            "geometry", Shaderc.shaderc_glsl_geometry_shader);

    record Result(int compiled, List<String> failures, List<String> passComputes) {
        Result(int compiled, List<String> failures) {
            this(compiled, failures, List.of());
        }
    }

    static Result run(Path pack, PackProbe.Analysis analysis) {
        return run(pack, analysis, null);
    }

    /** As {@link #run(Path, PackProbe.Analysis)}, recording every compiled shader in {@code snapshot} when given. */
    static Result run(Path pack, PackProbe.Analysis analysis, ConvertedOutputSnapshot snapshot) {
        if (analysis.plan() == null) return new Result(0, List.of());
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack)) {
            return run(pack, loaded.shadersDir(), analysis, snapshot);
        }
    }

    /** As {@link #run(Path, PackProbe.Analysis)}, with computes read from {@code shadersDir}: a load with options. */
    static Result run(Path pack, Path shadersDir, PackProbe.Analysis analysis) {
        return run(pack, shadersDir, analysis, null);
    }

    static Result run(Path pack, Path shadersDir, PackProbe.Analysis analysis, ConvertedOutputSnapshot snapshot) {
        if (analysis.plan() == null) return new Result(0, List.of());
        int compiled = 0;
        List<String> failures = new ArrayList<>();
        var compiler = new ProgramCompiler(pack, analysis.plan().advancedResources(), failures, snapshot);
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
            if (compiler.compiles(program)) compiled++;
        }
        // The catch-all fallbacks keep one program's Chimera defect from failing the pack; the gate
        // keeps that defect loud (PackPlanBuilder.planningFailed, ProgramReportRules.reportRow).
        for (ConformanceReport.ProgramReport row : analysis.report().programs()) {
            row.deviations().stream()
                    .filter(deviation -> deviation.startsWith(PackPlanBuilder.PLANNING_FAILED)
                            || deviation.startsWith(ProgramReportRules.REPORT_FAILED))
                    .forEach(deviation -> failures.add(row.name() + ": " + deviation));
        }
        // Two textures under one resource key would load as one image (PackResourcePlan.keyConflicts).
        analysis.report().deviations().stream()
                .filter(deviation -> deviation.startsWith(PackResourcePlan.KEY_CONFLICT))
                .forEach(failures::add);
        List<String> passComputes = new ArrayList<>();
        compiled += compilePassComputes(pack, shadersDir, analysis.plan(), failures, passComputes, snapshot);
        return new Result(compiled, List.copyOf(failures), List.copyOf(passComputes));
    }

    /**
     * Translates every pass compute the plan runs exactly as the runtime does, compiles it, and
     * checks its value block; returns how many compiled. Photon's deferred4_a writes the sky
     * light the deferred pass reads.
     */
    static int compilePassComputes(Path pack, Path shadersDir, PackPlan plan, List<String> failures,
                                   List<String> compiledNames, ConvertedOutputSnapshot snapshot) {
        int compiled = 0;
        try {
            for (var compute : PassComputePlan.build(plan, shadersDir).computes()) {
                if (!compute.supported()) continue;
                Map<String, Integer> written = new LinkedHashMap<>();
                for (var value : compute.values()) written.put(value.name(), value.offset());
                String error = compileComputeValues(compute.source(), PassComputePlan.VALUES_BLOCK, written,
                        snapshot, compute.name() + ".csh");
                if (compute.name().equals(System.getProperty("chimera.packCompile.dumpProgram"))) {
                    dump(pack, compute.name() + ".csh", compute.source());
                }
                if (error == null) {
                    compiled++;
                    compiledNames.add(compute.name());
                } else {
                    failures.add(compute.name() + ": " + error + " ["
                            + dump(pack, compute.name() + ".csh", compute.source()) + "]");
                }
            }
        } catch (RuntimeException failure) {
            failures.add("pass computes: " + failure);
        }
        return compiled;
    }

    /** Compiles programs as the runtime prepares them, with each input variant it builds, recording failures. */
    private static final class ProgramCompiler {
        private final Path pack;
        private final PackAdvancedResourcePlan resources;
        private final List<String> failures;
        private final ConvertedOutputSnapshot snapshot;
        private final String fixedPostVertex = ChimeraShaderLoader.loadSource(
                "chimera_composite/chimera_composite.vsh");
        private final String fixedTerrainVertex = ChimeraShaderLoader.loadSource(
                "chimera_terrain/chimera_terrain.vsh");
        /** The current program's vertex error: its own stage's, or the last failing input variant's. */
        private String vertexError;

        ProgramCompiler(Path pack, PackAdvancedResourcePlan resources, List<String> failures,
                        ConvertedOutputSnapshot snapshot) {
            this.pack = pack;
            this.resources = resources;
            this.failures = failures;
            this.snapshot = snapshot;
        }

        /** Compiles one prepared stage and records it under {@code key}; returns the error, or null. */
        private String check(String key, String source, boolean vertex) {
            return vertex ? check(key, source, Shaderc.shaderc_glsl_vertex_shader, "pack.vsh")
                    : check(key, source, Shaderc.shaderc_glsl_fragment_shader, "pack.fsh");
        }

        /** Compiles one prepared stage of shaderc {@code kind} and records it under {@code key}. */
        private String check(String key, String source, int kind, String file) {
            Outcome outcome = outcome(source, kind, file);
            if (snapshot != null) snapshot.record(key, source, outcome.spirvHash());
            return outcome.error();
        }

        /** Compiles the program's prepared stages and input variants; true when every one compiles. */
        boolean compiles(PackProgramPlan program) {
            UniformRegistry.Stage stage = FamilyAdapterRegistry.stageFor(program.name());
            String vertex = pairedVertex(program, stage);
            PackPipelines.PreparedPipeline prepared;
            try {
                prepared = PackPipelines.prepare(program, resources, stage, vertex);
                // The runtime checks a post or shadow program's whole descriptor set before creating it
                // (PackPipelines.buildPost, buildShadow, buildGeometry); a mismatch there stops the game.
                // So it does for a program with stages between vertex and fragment: Shrimple's hand
                // evaluation stage declared the terrain lanes' uniform block and stopped it at launch.
                if (stage == UniformRegistry.Stage.POST || FamilyAdapterRegistry.isShadowFamily(program.name())
                        || !prepared.between().present().isEmpty()) {
                    PackPipelines.prepareLayout(prepared, program.name(), resources);
                }
            } catch (RuntimeException failure) {
                failures.add(program.name() + " preparation: " + failure);
                return false;
            }
            vertexError = program.convertedVertex() == null ? null
                    : check(program.name() + ".vsh", prepared.vertex(), true);
            String fragmentError = check(program.name() + ".fsh", prepared.fragment(), false);
            boolean betweenCompiles = prepared.between().present().stream()
                    .map(between -> checkBetween(program.name(), between)).toList().stream().allMatch(ok -> ok);
            compileInputVariants(program, stage);
            // -Pchimera.packCompile.dumpProgram=<name> writes that program's prepared stages for review.
            if (program.name().equals(System.getProperty("chimera.packCompile.dumpProgram"))) {
                dump(pack, program.name() + ".vsh", prepared.vertex());
                prepared.between().present().forEach(between -> dump(pack,
                        program.name() + BETWEEN_EXTENSIONS.get(between.name()), between.source()));
                dump(pack, program.name() + ".fsh", prepared.fragment());
            }
            if (vertexError != null) failures.add(program.name() + " vertex: " + vertexError
                    + " [" + dump(pack, program.name() + ".vsh", prepared.vertex()) + "]");
            if (fragmentError != null) failures.add(program.name() + " fragment: " + fragmentError
                    + " [" + dump(pack, program.name() + ".fsh", prepared.fragment()) + "]");
            return vertexError == null && betweenCompiles && fragmentError == null;
        }

        /** Compiles one stage the program runs between its vertex and fragment stages; false when it fails. */
        private boolean checkBetween(String program, BetweenStages.Stage between) {
            String extension = BETWEEN_EXTENSIONS.get(between.name());
            String error = check(program + extension, between.source(), BETWEEN_KINDS.get(between.name()),
                    "pack" + extension);
            if (error != null) failures.add(program + " " + between.name() + ": " + error
                    + " [" + dump(pack, program + extension, between.source()) + "]");
            return error == null;
        }

        /**
         * The vertex the runtime draws the program with (a family's own inputs, PackPipelines),
         * or Chimera's fixed vertex where the pack has none.
         */
        private String pairedVertex(PackProgramPlan program, UniformRegistry.Stage stage) {
            if (program.convertedVertex() != null) return PackPipelines.familyVertex(program);
            if (stage == UniformRegistry.Stage.POST) return fixedPostVertex;
            if (stage == UniformRegistry.Stage.GEOMETRY || stage == UniformRegistry.Stage.TRANSLUCENT) {
                return fixedTerrainVertex;
            }
            return program.convertedFragment();
        }

        /** The host-layout input variants the runtime builds for the program's family. */
        private void compileInputVariants(PackProgramPlan program, UniformRegistry.Stage stage) {
            String name = program.name();
            String vertex = program.convertedVertex();
            if (FamilyAdapterRegistry.isHandFamily(name)) {
                for (var host : ChimeraVertexFormats.handFormats().keySet()) {
                    compileLayoutVariant(program, stage, "hand input variant", host);
                }
            }
            if (name.equals("gbuffers_entities_translucent") || name.equals("gbuffers_block_translucent")) {
                // Iris GLYPH text variant (TEXT and TEXT_BE).
                compileLayoutVariant(program, stage, "text input variant",
                        DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
            }
            if (name.equals("gbuffers_block") || name.equals("shadow_entities")) {
                // Falling blocks and moving pistons on the block layout (Iris MOVING_BLOCK).
                compileLayoutVariant(program, stage, "moving-block input variant", DefaultVertexFormat.BLOCK);
            }
            if (FamilyAdapterRegistry.isGlintFamily(name)) {
                // First-person glint: the same program under the hand projection.
                compileVariant(program, stage, "hand glint variant", EntityVertexBridge.handGlintVertex(vertex));
            }
            if (FamilyAdapterRegistry.isSkyFamily(name)) {
                for (var contract : HostLayoutVertexBridge.SKY_CONTRACTS) {
                    compileVariant(program, stage, "sky input variant " + contract,
                            HostLayoutVertexBridge.skyVertexForContract(vertex, contract));
                }
            }
        }

        private void compileVariant(PackProgramPlan program, UniformRegistry.Stage stage, String label,
                                    String variant) {
            String error = check(variantKey(program, label, ".vsh"),
                    PackPipelines.prepare(program, resources, stage, variant).vertex(), true);
            if (error != null) {
                failures.add(program.name() + " " + label + ": " + error);
                vertexError = error;
            }
        }

        /** A native host layout's variant: its own inputs, and its own fragment where the layout has no overlay. */
        private void compileLayoutVariant(PackProgramPlan program, UniformRegistry.Stage stage, String label,
                                          VertexFormat host) {
            String fragment = EntityVertexBridge.fragmentForFormat(program.convertedFragment(), host);
            var prepared = PackPipelines.prepare(program, resources, stage,
                    EntityVertexBridge.layoutVertexForFormat(program.convertedVertex(), host), fragment);
            String layout = label + " " + String.join(",", host.getElementAttributeNames());
            String error = check(variantKey(program, layout, ".vsh"), prepared.vertex(), true);
            if (error != null) {
                failures.add(program.name() + " " + label + ": " + error);
                vertexError = error;
            }
            String fragmentError = fragment.equals(program.convertedFragment())
                    ? null : check(variantKey(program, layout, ".fsh"), prepared.fragment(), false);
            if (fragmentError != null) failures.add(program.name() + " " + label + " fragment: " + fragmentError);
        }

        private static String variantKey(PackProgramPlan program, String label, String extension) {
            return program.name() + " [" + label + "]" + extension;
        }
    }

    /**
     * Compiles a compute source and checks that its value block reads each value at the byte
     * offset dispatch writes it. Returns the first error or disagreement, or null. Solas's
     * frameCounter, declared after a vec3, read the vec3's padding word under implicit std140
     * packing and stayed 0, so its floodfill wrote one image only and flickered.
     */
    static String compileComputeValues(String source, String block, Map<String, Integer> written) {
        return compileComputeValues(source, block, written, null, null);
    }

    private static String compileComputeValues(String source, String block, Map<String, Integer> written,
                                               ConvertedOutputSnapshot snapshot, String key) {
        Compiled compiled = spirv(source, Shaderc.shaderc_glsl_compute_shader, "pack.csh");
        if (snapshot != null) {
            snapshot.record(key, source, compiled.words() == null ? null : spirvHash(compiled.words()));
        }
        if (compiled.error() != null) return compiled.error();
        Map<String, Integer> read = blockMemberOffsets(compiled.words(), block);
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
    /** The compile error of {@code source} as the stage between vertex and fragment named {@code stage}, or null. */
    static String compileBetween(String source, String stage) {
        return outcome(source, BETWEEN_KINDS.get(stage), "pack" + BETWEEN_EXTENSIONS.get(stage)).error();
    }

    static String compile(String source, boolean vertex) {
        return vertex ? outcome(source, Shaderc.shaderc_glsl_vertex_shader, "pack.vsh").error()
                : outcome(source, Shaderc.shaderc_glsl_fragment_shader, "pack.fsh").error();
    }

    /** A shaderc result: the SPIR-V words on success, otherwise the first error line. */
    private record Compiled(int[] words, String error) {}

    /** A cached compile: the first error line or null, and the SPIR-V's short hash when it compiled. */
    private record Outcome(String error, String spirvHash) {}

    /**
     * Each distinct source compiles once per run: the option sweep prepares the same program
     * source under most of its variants. The device's sized types are part of the key, since a
     * vendor extension compiles as its equivalent only with them (GlslVendorExtensions).
     */
    private static final Map<String, Outcome> COMPILED = new ConcurrentHashMap<>();

    private static Outcome outcome(String source, int kind, String fileName) {
        String key = kind + ":" + PackDevice.current() + ":"
                + ConformanceReport.sha256(source.getBytes(StandardCharsets.UTF_8));
        return COMPILED.computeIfAbsent(key, ignored -> {
            Compiled compiled = spirv(source, kind, fileName);
            return new Outcome(compiled.error(), compiled.words() == null ? null : spirvHash(compiled.words()));
        });
    }

    /** The short hash of a SPIR-V module's words, as {@link ConvertedOutputSnapshot} records it. */
    private static String spirvHash(int[] words) {
        ByteBuffer bytes = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asIntBuffer().put(words);
        return ConformanceReport.sha256(bytes.array()).substring(0, 16);
    }

    /**
     * Compiled as the runtime compiles each kind (GlslCompiler). A graphics stage takes the
     * optimizer HostPipelineLayout runs; shaderc validates the module before it optimizes, so a
     * stage the Vulkan validator rejects fails here as it does in game (Noble's unwritten
     * gl_FragDepth reads passed a plain compile). A compute takes none, as in ComputeKernel.
     */
    private static Compiled spirv(String source, int kind, String fileName) {
        LongConsumer configure = kind == Shaderc.shaderc_glsl_compute_shader ? options -> {} : GlslCompiler::optimize;
        ByteBuffer bytes;
        try {
            bytes = GlslCompiler.compile(fileName, source, kind, configure);
        } catch (IllegalStateException failure) {
            String message = failure.getMessage().strip();
            // Warnings precede errors in shaderc's report; name the first error.
            return new Compiled(null, message.lines().filter(line -> line.contains("error")).findFirst()
                    .orElse(message.lines().findFirst().orElse("unknown")));
        }
        try {
            int[] words = new int[bytes.remaining() / 4];
            bytes.order(ByteOrder.nativeOrder()).asIntBuffer().get(words);
            return new Compiled(words, null);
        } finally {
            MemoryUtil.memFree(bytes);
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

    /** Programs and pass computes of the selected dimension the plan will not run, sorted. */
    static SortedSet<String> rejectedPrograms(Path pack, PackProbe.Analysis analysis) {
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack)) {
            return rejectedProgramsIn(loaded.shadersDir(), analysis);
        }
    }

    /** As {@link #rejectedPrograms}, with pass computes read from {@code shadersDir}: a load with options. */
    static SortedSet<String> rejectedProgramsIn(Path shadersDir, PackProbe.Analysis analysis) {
        SortedSet<String> rejected = new TreeSet<>();
        if (analysis.plan() == null) return rejected;
        for (PackProgramPlan program : analysis.plan().activePrograms()) {
            if (!analysis.plan().shouldAttempt(program.name())) rejected.add(program.name());
        }
        PassComputePlan.build(analysis.plan(), shadersDir).computes().stream()
                .filter(compute -> !compute.supported())
                .forEach(compute -> rejected.add(compute.name()));
        return rejected;
    }

    /**
     * The rejections a load tells in chat (SkippedPrograms), each with its reason, for review; the
     * runtime adds those it finds itself, such as a build failure.
     */
    static List<String> toldSkips(PackProbe.Analysis analysis, Collection<String> rejected) {
        return rejected.stream()
                .map(program -> analysis.report().program(program))
                .filter(row -> row != null && !SkippedPrograms.expected(row))
                .map(row -> row.name() + "=" + SkippedPrograms.reason(row.deviations()))
                .toList();
    }

    /** The report deviations that say why a program was rejected, for gate messages and the option sweep. */
    static List<String> rejectionReasons(PackProbe.Analysis analysis, String program) {
        var report = analysis.report().program(program);
        if (report == null) return List.of();
        return report.deviations().stream().filter(deviation -> deviation.contains("UNSUPPORTED")
                || deviation.startsWith("PLAN_INELIGIBLE") || deviation.contains("FAILED")
                || deviation.contains("MALFORMED")).toList();
    }

    /**
     * The compile gate's check of one pack (CorpusChecks runs it): every program the pack installs
     * must compile, and the set its plan rejects must match the recorded admission baseline.
     * Compiling only admitted programs cannot see a program the plan newly rejects (MakeUp 9.5g
     * terrain fell back in game while every admitted program compiled).
     * {@code -Pchimera.packCompile.updateAdmission=true} records the rejected set in {@code recorded}
     * instead, for {@link #writeBaseline}. Every compiled shader must also match its converted-output
     * baseline ({@link ConvertedOutputSnapshot}); {@code -Pchimera.snapshot.update=true} rewrites
     * that, and {@code -Pchimera.snapshot.spirvOnly=true} compares SPIR-V alone.
     * {@code -Pchimera.device=amd} or {@code intel} compiles for that GPU (PackDevice), whose vendor
     * paths differ, against its own admission baseline and without converted output.
     */
    static void checkPack(Path pack, PackProbe.Analysis analysis, Map<String, TreeSet<String>> baseline,
                          Map<String, TreeSet<String>> recorded, List<String> failures) {
        String key = pack.getFileName().toString();
        boolean defaultProfile = PackDevice.current().profile().equals(PackDevice.DEFAULT_PROFILE);
        ConvertedOutputSnapshot snapshot = defaultProfile ? new ConvertedOutputSnapshot(key) : null;
        Result result = run(pack, analysis, snapshot);
        if (snapshot != null) {
            failures.addAll(snapshot.compareWithBaseline(Boolean.getBoolean("chimera.snapshot.update"),
                    Boolean.getBoolean("chimera.snapshot.spirvOnly")));
        }
        SortedSet<String> rejected = rejectedPrograms(pack, analysis);
        System.out.printf("[chimera] pack compile %s: %d programs compile, %d failures, %d rejected,"
                + " pass computes %s%n", key, result.compiled(), result.failures().size(), rejected.size(),
                result.passComputes());
        System.out.printf("[chimera] pack skips told %s: %s%n", key, toldSkips(analysis, rejected));
        if (result.compiled() == 0) failures.add(key + ": no program compiled");
        result.failures().forEach(failure -> failures.add(key + " " + failure));
        if (Boolean.getBoolean("chimera.packCompile.updateAdmission")) {
            recorded.put(key, new TreeSet<>(rejected));
        } else {
            failures.addAll(admissionChanges(key, rejected, baseline.get(key), analysis));
        }
    }

    /** The admission baseline of the current device profile: each pack's rejected programs. */
    static Path baselinePath() {
        String profile = PackDevice.current().profile();
        return TestPacks.baseline(profile.equals(PackDevice.DEFAULT_PROFILE)
                ? "pack_admission" : "pack_admission-" + profile);
    }

    private static List<String> admissionChanges(String key, Set<String> rejected, Set<String> expected,
                                                 PackProbe.Analysis analysis) {
        List<String> failures = new ArrayList<>();
        if (expected == null) {
            failures.add(key + ": no admission baseline; rejected=" + rejected
                    + " (run with -Pchimera.packCompile.updateAdmission=true after reviewing)");
            return failures;
        }
        for (String program : rejected) {
            if (!expected.contains(program)) {
                failures.add(key + " " + program + " newly rejected: " + rejectionReasons(analysis, program));
            }
        }
        for (String program : expected) {
            if (!rejected.contains(program)) {
                failures.add(key + " " + program
                        + " is now admitted; record it with -Pchimera.packCompile.updateAdmission=true");
            }
        }
        return failures;
    }

    private static final Type BASELINE_TYPE = new TypeToken<TreeMap<String, TreeSet<String>>>() {}.getType();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static TreeMap<String, TreeSet<String>> readBaseline(Path path) throws IOException {
        return Files.isRegularFile(path) ? GSON.fromJson(Files.readString(path), BASELINE_TYPE) : new TreeMap<>();
    }

    /** Merges one shard's packs into the baseline; the shards write one at a time, under a file lock. */
    static void writeBaseline(Path path, Map<String, TreeSet<String>> recorded) throws IOException {
        // In build/, kept: a shard waiting on the lock holds the file open, so it is never deleted.
        Path lock = Files.createDirectories(Path.of("build")).resolve(path.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.lock();
            try {
                TreeMap<String, TreeSet<String>> baseline = readBaseline(path);
                baseline.putAll(recorded);
                Files.writeString(path, GSON.toJson(baseline) + System.lineSeparator());
            } finally {
                held.release();
            }
        }
        System.out.println("[chimera] pack admission baseline written: " + path);
    }
}
