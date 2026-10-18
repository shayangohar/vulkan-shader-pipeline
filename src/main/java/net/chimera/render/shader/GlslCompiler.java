package net.chimera.render.shader;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

/**
 * Compiles Chimera's GLSL to SPIR-V for Vulkan 1.2, the host's target. The source reaches shaderc
 * from the heap: shaderc's CharSequence overloads encode it on LWJGL's thread stack, which VulkanMod
 * Reforged leaves at its 64 KB default, and a pack's stage outgrows that.
 */
public final class GlslCompiler {
    private GlslCompiler() {}

    /** Optimizes for performance, as the host compiles its own shaders. */
    public static void optimize(long options) {
        Shaderc.shaderc_compile_options_set_optimization_level(options,
                Shaderc.shaderc_optimization_level_performance);
    }

    /**
     * Compiles {@code source}, a {@code kind} shader (a {@code shaderc_glsl_*_shader} constant)
     * named {@code name}, with the options {@code configure} sets. Returns the module in memory the
     * caller frees with {@link MemoryUtil#memFree}.
     *
     * @throws IllegalStateException with shaderc's report when the source does not compile
     */
    public static ByteBuffer compile(String name, String source, int kind, LongConsumer configure) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        ByteBuffer text = MemoryUtil.memUTF8(source, false);
        long result = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Shaderc.shaderc_compile_options_set_target_env(options,
                    Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            configure.accept(options);
            result = Shaderc.shaderc_compile_into_spv(compiler, text, kind, stack.UTF8(name), stack.UTF8("main"),
                    options);
            if (result == 0L) throw new IllegalStateException(name + ": shaderc returned no result");
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                String report = Shaderc.shaderc_result_get_error_message(result);
                throw new IllegalStateException(report == null ? name + ": compilation failed" : report);
            }
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            ByteBuffer module = MemoryUtil.memAlloc(bytes.remaining());
            MemoryUtil.memCopy(bytes, module);
            return module;
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            MemoryUtil.memFree(text);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
