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
            // Exactly the programs the runtime would build: shouldAttempt, not executable() alone.
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
            return message == null ? "unknown" : message.strip().lines().findFirst().orElse("unknown");
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

    /**
     * Gate: every program the named packs install must compile. Packs come from
     * {@code -Dchimera.packCompile.packs} (path-separator list).
     */
    public static void main(String[] args) {
        String packs = System.getProperty("chimera.packCompile.packs", "");
        if (packs.isBlank()) {
            throw new AssertionError("chimera.packCompile.packs is not set");
        }
        List<String> failures = new ArrayList<>();
        for (String entry : packs.split(java.io.File.pathSeparator)) {
            Path pack = Path.of(entry.trim());
            Result result = run(pack, PackProbe.analyze(pack));
            System.out.printf("[chimera] pack compile %s: %d programs compile, %d failures%n",
                    pack.getFileName(), result.compiled(), result.failures().size());
            if (result.compiled() == 0) failures.add(pack.getFileName() + ": no program compiled");
            result.failures().forEach(failure -> failures.add(pack.getFileName() + " " + failure));
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("installed programs do not compile:\n  " + String.join("\n  ", failures));
        }
        System.out.println("[chimera] pack compile gate: PASS");
    }
}
