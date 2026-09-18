package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.Map;
import org.lwjgl.util.shaderc.Shaderc;

/** Throwaway runtime compiler smoke; no Vulkan device or game launch. */
public final class AtlasPackSmoke {
    public static void main(String[] args) throws Exception {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                    Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
            for (String path : args) {
                Map<String, String> overrides = path.contains("Complementary")
                        ? Map.of("COLORED_LIGHTING", "128", "WORLD_SPACE_REFLECTIONS", "1")
                        : Map.of("MULTICOLORED_BLOCKLIGHT", "1", "MCBL_DISTANCE", "128", "MCBL_HALF_HEIGHT", "1");
                overrides.forEach((key, value) -> System.setProperty("chimera.option." + key, value));
                try {
                    PackPlan plan = PackProbe.analyze(Path.of(path)).plan();
                    PackProgramPlan terrain = plan.program("gbuffers_terrain");
                    if (terrain == null || !terrain.executable()) throw new AssertionError("Terrain rejected: " + path);
                    String fragment = terrain.convertedFragment();
                    if (!fragment.contains("chimeraAtlas")) throw new AssertionError("Atlas bridge missing: " + path);
                    java.nio.file.Files.writeString(Path.of("scratch/dump-" + (path.contains("Complementary") ? "comp" : "bsl") + "-terrain.frag"), fragment);
                    for (String stage : new String[]{"vertex", "fragment"}) {
                        String source = stage.equals("vertex") ? terrain.convertedVertex() : fragment;
                        java.nio.ByteBuffer code = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
                        java.nio.ByteBuffer file = org.lwjgl.system.MemoryUtil.memUTF8("gbuffers_terrain." + stage);
                        java.nio.ByteBuffer entry = org.lwjgl.system.MemoryUtil.memUTF8("main");
                        long result;
                        try {
                            result = Shaderc.shaderc_compile_into_spv(compiler, code,
                                    stage.equals("vertex") ? Shaderc.shaderc_glsl_vertex_shader : Shaderc.shaderc_glsl_fragment_shader,
                                    file, entry, options);
                        } finally {
                            org.lwjgl.system.MemoryUtil.memFree(code);
                            org.lwjgl.system.MemoryUtil.memFree(file);
                            org.lwjgl.system.MemoryUtil.memFree(entry);
                        }
                        try {
                            if (result == 0 || Shaderc.shaderc_result_get_compilation_status(result)
                                    != Shaderc.shaderc_compilation_status_success) {
                                throw new AssertionError(path + " " + stage + ": "
                                        + (result == 0 ? "no compiler result" : Shaderc.shaderc_result_get_error_message(result)));
                            }
                            System.out.println("ATLAS PACK COMPILE PASS " + path + " " + stage);
                        } finally { if (result != 0) Shaderc.shaderc_result_release(result); }
                    }
                } finally { overrides.keySet().forEach(key -> System.clearProperty("chimera.option." + key)); }
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
