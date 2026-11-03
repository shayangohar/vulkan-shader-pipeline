package net.chimera.shaderpack;

import net.chimera.render.shader.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The device extensions shaderc's modules need. */
final class SpirvDeviceExtensionsTest {
    private static final String TRINARY = """
            #version 450
            #extension GL_AMD_shader_trinary_minmax : require
            layout(local_size_x = 1) in;
            layout(std430, binding = 0) buffer Values { vec3 value; };
            void main() { value = %s(value, vec3(1.0), vec3(2.0)); }
            """;

    /** RenderPearl's deferred1 calls min3; its setup requires the extension and calls nothing from it. */
    @Test
    void aTrinaryCallNeedsItsVulkanExtensionAndAnUnusedDirectiveNothing() {
        assertEquals(Set.of("VK_AMD_shader_trinary_minmax"), required(TRINARY.formatted("min3")));
        assertEquals(Set.of(), required(TRINARY.formatted("clamp")));
    }

    @Test
    void mapsSpirvNamesToTheVulkanExtensionsThatServeThem() {
        assertNull(SpirvDeviceExtensions.extension("SPV_KHR_16bit_storage"));
        assertEquals("VK_EXT_shader_subgroup_ballot", SpirvDeviceExtensions.extension("SPV_KHR_shader_ballot"));
        assertEquals("VK_NV_shader_subgroup_partitioned",
                SpirvDeviceExtensions.extension("SPV_NV_shader_subgroup_partitioned"));
        assertNull(SpirvDeviceExtensions.instructionSet("GLSL.std.450"));
        assertEquals("VK_KHR_shader_non_semantic_info",
                SpirvDeviceExtensions.instructionSet("NonSemantic.DebugPrintf"));
    }

    private static Set<String> required(String source) {
        ByteBuffer module = GlslCompiler.compile("test.csh", source, Shaderc.shaderc_glsl_compute_shader,
                options -> {});
        try {
            return SpirvDeviceExtensions.required(module);
        } finally {
            MemoryUtil.memFree(module);
        }
    }
}
