package net.chimera.render.shader;

import net.chimera.render.ChimeraDevice;
import net.chimera.shaderpack.SpirvDeviceExtensions;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chimera's shaderc entry, on LWJGL's default thread stack as in game. */
final class GlslCompilerTest {
    private static final int SPIRV_MAGIC = 0x07230203;

    /** Bliss's composite outgrew the 64 KB stack shaderc's CharSequence overloads encode into. */
    @Test
    void compilesASourceLargerThanTheThreadStack() {
        String source = "#version 450\n" + "// a pack stage's worth of source\n".repeat(4096)
                + "layout(location = 0) out vec4 color;\nvoid main() { color = vec4(1.0); }\n";
        assertTrue(source.length() > 128 * 1024, "the source must outgrow the stack");
        ByteBuffer module = GlslCompiler.compile("large.fsh", source, Shaderc.shaderc_glsl_fragment_shader,
                GlslCompiler::optimize);
        try {
            assertEquals(SPIRV_MAGIC, module.order(ByteOrder.nativeOrder()).getInt(0));
        } finally {
            MemoryUtil.memFree(module);
        }
    }

    @Test
    void reportsWhatShadercRejects() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> GlslCompiler.compile(
                "broken.fsh", "#version 450\nvoid main() { undeclared(); }\n", Shaderc.shaderc_glsl_fragment_shader,
                options -> {}));
        assertTrue(failure.getMessage().contains("broken.fsh") && failure.getMessage().contains("undeclared"),
                failure.getMessage());
    }

    /** NVIDIA's driver crashed building RenderPearl's deferred1, whose SPIR-V needed an extension it lacks. */
    @Test
    void refusesAModuleNeedingAnExtensionTheDeviceWasCreatedWithout() {
        String source = """
                #version 450
                #extension GL_NV_shader_subgroup_partitioned : require
                layout(local_size_x = 32) in;
                layout(std430, binding = 0) buffer Values { uvec4 value; };
                void main() { value = subgroupPartitionNV(value.x); }
                """;
        ChimeraDevice.setEnabledExtensions(List.of("VK_KHR_dynamic_rendering"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> GlslCompiler.compile(
                "composite7.csh", source, Shaderc.shaderc_glsl_compute_shader, options -> {}));
        assertTrue(failure.getMessage().contains("VK_NV_shader_subgroup_partitioned"), failure.getMessage());
        ChimeraDevice.setEnabledExtensions(List.of("VK_NV_shader_subgroup_partitioned"));
        MemoryUtil.memFree(GlslCompiler.compile("composite7.csh", source, Shaderc.shaderc_glsl_compute_shader,
                options -> {}));
    }

    /** RenderPearl's deferred1 calls min3 under GL_AMD_shader_trinary_minmax, which NVIDIA's device lacks. */
    @Test
    void compilesTheAmdTrinaryFunctionsAsMinAndMaxWithoutTheExtension() {
        String source = """
                #version 450
                #extension GL_AMD_shader_trinary_minmax : require
                layout(local_size_x = 1) in;
                layout(std430, binding = 0) buffer Values { vec3 value; ivec2 count; };
                void main() {
                    value = min3(value, vec3(1.0), vec3(2.0)) + max3(value, vec3(0.5), value.zyx)
                            + mid3(vec3(0.0), value, vec3(1.0));
                    count = mid3(count, ivec2(0), ivec2(8));
                }
                """;
        ChimeraDevice.setEnabledExtensions(List.of("VK_KHR_dynamic_rendering"));
        ByteBuffer module = GlslCompiler.compile("deferred1.csh", source, Shaderc.shaderc_glsl_compute_shader,
                options -> {});
        try {
            assertEquals(Set.of(), SpirvDeviceExtensions.required(module));
        } finally {
            MemoryUtil.memFree(module);
        }
    }
}
