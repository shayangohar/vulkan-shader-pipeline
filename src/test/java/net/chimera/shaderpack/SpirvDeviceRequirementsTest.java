package net.chimera.shaderpack;

import net.chimera.render.shader.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What shaderc's modules need from the device. */
final class SpirvDeviceRequirementsTest {
    private static final String TRINARY = """
            #version 450
            #extension GL_AMD_shader_trinary_minmax : require
            layout(local_size_x = 1) in;
            layout(std430, binding = 0) buffer Values { vec3 value; };
            void main() { value = %s(value, vec3(1.0), vec3(2.0)); }
            """;

    /**
     * RenderPearl's deferred1 calls min3; its setup requires the extension and calls nothing from it.
     * On AMD's device, which serves the extension, the directive stays.
     */
    @Test
    void aTrinaryCallNeedsItsVulkanExtensionAndAnUnusedDirectiveNothing() {
        PackDevice.set(PackDevice.offline("amd"));
        assertEquals(Set.of("VK_AMD_shader_trinary_minmax"), required(TRINARY.formatted("min3")));
        assertEquals(Set.of(), required(TRINARY.formatted("clamp")));
    }

    @Test
    void mapsSpirvNamesToTheVulkanExtensionsThatServeThem() {
        assertNull(SpirvDeviceRequirements.extension("SPV_KHR_16bit_storage"));
        assertEquals("VK_EXT_shader_subgroup_ballot", SpirvDeviceRequirements.extension("SPV_KHR_shader_ballot"));
        assertEquals("VK_NV_shader_subgroup_partitioned",
                SpirvDeviceRequirements.extension("SPV_NV_shader_subgroup_partitioned"));
        assertNull(SpirvDeviceRequirements.instructionSet("GLSL.std.450"));
        assertEquals("VK_KHR_shader_non_semantic_info",
                SpirvDeviceRequirements.instructionSet("NonSemantic.DebugPrintf"));
    }

    /**
     * Audit GD6: these KHR extensions gained a shader_ prefix in Vulkan. Named by the plain rule, a
     * device created with the extension would still refuse the program, and a refusal named an
     * extension that does not exist.
     */
    @Test
    void theShaderPrefixedKhrExtensionsMapToTheirVulkanNames() {
        for (String name : new String[] {"subgroup_uniform_control_flow", "integer_dot_product", "expect_assume",
                "quad_control", "maximal_reconvergence", "float_controls2", "subgroup_rotate",
                "relaxed_extended_instruction"}) {
            assertEquals("VK_KHR_shader_" + name, SpirvDeviceRequirements.extension("SPV_KHR_" + name));
        }
        assertEquals("VK_KHR_shader_untyped_pointers", SpirvDeviceRequirements.extension("SPV_KHR_untyped_pointers"));
        assertEquals("VK_EXT_shader_replicated_composites",
                SpirvDeviceRequirements.extension("SPV_EXT_replicated_composites"));
        // Served by an extension of another name altogether.
        assertEquals("VK_KHR_ray_tracing_pipeline", SpirvDeviceRequirements.extension("SPV_KHR_ray_tracing"));
        assertEquals("VK_NV_shading_rate_image", SpirvDeviceRequirements.extension("SPV_NV_shading_rate"));
        assertEquals("VK_EXT_conservative_rasterization",
                SpirvDeviceRequirements.extension("SPV_EXT_fragment_fully_covered"));
        assertEquals("VK_EXT_fragment_density_map",
                SpirvDeviceRequirements.extension("SPV_EXT_fragment_invocation_density"));
        // The bundled shaderc knows none of their GLSL extensions yet, so the module is written by hand.
        SpirvDeviceRequirements.Requirements module =
                SpirvDeviceRequirements.of(moduleDeclaring("SPV_KHR_subgroup_uniform_control_flow"));
        assertEquals(Set.of("VK_KHR_shader_subgroup_uniform_control_flow"), module.extensions());
        PackDevice device = PackDevice.offline("nvidia");
        assertEquals(List.of("device extension VK_KHR_shader_subgroup_uniform_control_flow"),
                device.missing(module, PackDevice.STAGE_COMPUTE));
        assertEquals(List.of(), device.withExtensions(List.of("VK_KHR_shader_subgroup_uniform_control_flow"))
                .missing(module, PackDevice.STAGE_COMPUTE));
    }

    /** A SPIR-V header and one OpExtension, the words the scan reads before the memory model. */
    private static int[] moduleDeclaring(String extension) {
        byte[] name = (extension + "\0").getBytes(StandardCharsets.UTF_8);
        int nameWords = (name.length + 3) / 4;
        int[] words = new int[5 + 1 + nameWords];
        words[0] = 0x07230203;
        words[1] = 0x00010500;
        words[3] = 1;
        words[5] = (1 + nameWords) << 16 | 10;
        for (int index = 0; index < name.length; index++) {
            words[6 + index / 4] |= (name[index] & 0xFF) << (8 * (index % 4));
        }
        return words;
    }

    private static Set<String> required(String source) {
        ByteBuffer module = GlslCompiler.compile("test.csh", source, Shaderc.shaderc_glsl_compute_shader,
                options -> {});
        try {
            return SpirvDeviceRequirements.of(module).extensions();
        } finally {
            MemoryUtil.memFree(module);
        }
    }
}
