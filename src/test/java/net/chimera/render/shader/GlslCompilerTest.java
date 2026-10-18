package net.chimera.render.shader;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
}
