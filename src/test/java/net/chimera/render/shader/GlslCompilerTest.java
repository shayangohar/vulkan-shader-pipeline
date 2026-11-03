package net.chimera.render.shader;

import net.chimera.render.ChimeraDevice;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

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

    /** NVIDIA's driver crashed building RenderPearl's deferred1, which calls GL_AMD_shader_trinary_minmax's min3. */
    @Test
    void refusesAModuleNeedingAnExtensionTheDeviceWasCreatedWithout() {
        String source = """
                #version 450
                #extension GL_AMD_shader_trinary_minmax : require
                layout(local_size_x = 1) in;
                layout(std430, binding = 0) buffer Values { vec3 value; };
                void main() { value = min3(value, vec3(1.0), vec3(2.0)); }
                """;
        ChimeraDevice.setEnabledExtensions(List.of("VK_KHR_dynamic_rendering"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> GlslCompiler.compile(
                "deferred1.csh", source, Shaderc.shaderc_glsl_compute_shader, options -> {}));
        assertTrue(failure.getMessage().contains("VK_AMD_shader_trinary_minmax"), failure.getMessage());
        ChimeraDevice.setEnabledExtensions(List.of("VK_AMD_shader_trinary_minmax"));
        MemoryUtil.memFree(GlslCompiler.compile("deferred1.csh", source, Shaderc.shaderc_glsl_compute_shader,
                options -> {}));
    }
}
