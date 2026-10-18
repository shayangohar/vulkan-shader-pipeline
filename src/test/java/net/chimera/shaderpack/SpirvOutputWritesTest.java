package net.chimera.shaderpack;

import net.chimera.render.shader.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SpirvOutputWritesTest {
    /** A shadow program that writes shadowcolor0 alone leaves location 1 unwritten. */
    @Test
    void declaredButUnwrittenOutputs() {
        assertEquals(0b01, written("""
                #version 450
                layout(location = 0) out vec4 chimeraShadowColor0;
                layout(location = 1) out vec4 chimeraShadowColor1;
                void main() { chimeraShadowColor0 = vec4(1.0); }
                """));
    }

    /** Writes through a component, an array element, a branch and an out parameter all count. */
    @Test
    void writesInEveryForm() {
        assertEquals(0b1111, written("""
                #version 450
                layout(location = 0) out vec4 color;
                layout(location = 1) out vec4 normal;
                layout(location = 2) out vec4 extra[2];
                layout(location = 0) in float side;
                void fill(out vec4 value) { value = vec4(side); }
                void main() {
                    color.rgb = vec3(side);
                    if (side > 0.5) fill(normal);
                    extra[1] = vec4(0.0);
                }
                """));
    }

    @Test
    void unreadableModuleWritesEverything() {
        assertEquals(SpirvOutputWrites.ALL, SpirvOutputWrites.writtenLocations(new int[] {1, 2, 3}));
    }

    private static int written(String source) {
        ByteBuffer module = GlslCompiler.compile("outputs.fsh", source, Shaderc.shaderc_glsl_fragment_shader,
                GlslCompiler::optimize);
        try {
            return SpirvOutputWrites.writtenLocations(module);
        } finally {
            MemoryUtil.memFree(module);
        }
    }
}
