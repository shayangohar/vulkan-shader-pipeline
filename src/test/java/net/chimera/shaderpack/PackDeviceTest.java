package net.chimera.shaderpack;

import net.chimera.render.shader.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a module needs that the device was created without. RenderPearl 2.8's NVIDIA path keeps a
 * float16_t in a storage buffer and runs f16 subgroup arithmetic; a GPU without the features must
 * refuse the program, as a GL driver would, instead of handing the driver a module it cannot run.
 */
final class PackDeviceTest {
    /** A device with every feature, every subgroup stage and operation, and the pack extensions. */
    private static final PackDevice EVERYTHING = new PackDevice("test", PackDevice.VENDOR_NVIDIA,
            PackDevice.DRIVER_NVIDIA_PROPRIETARY, "test", allFeatures(), 0x3F, 0x1FF,
            new TreeSet<>(PackDevice.PACK_EXTENSIONS));

    private static final String HALF_BUFFER = """
            #version 460
            #extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
            #extension GL_EXT_shader_16bit_storage : require
            layout(local_size_x = 32) in;
            layout(std430, binding = 0) buffer Exposure { float16_t exposure; vec4 color; };
            void main() { color *= float(exposure * float16_t(2.0)); }
            """;
    private static final String HALF_SUBGROUP_MIN = """
            #version 460
            #extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
            #extension GL_KHR_shader_subgroup_arithmetic : require
            #extension GL_EXT_shader_subgroup_extended_types_float16 : require
            layout(location = 0) in vec3 position;
            layout(location = 0) out vec4 color;
            void main() { color = vec4(vec3(subgroupMin(f16vec3(position))), 1.0); }
            """;

    @Test
    void aHalfFloatInAStorageBufferNeeds16BitStorage() {
        var module = requirements(HALF_BUFFER, Shaderc.shaderc_glsl_compute_shader);
        assertEquals(List.of(), EVERYTHING.missing(module, PackDevice.STAGE_COMPUTE));
        assertEquals(List.of("feature shaderFloat16", "feature storageBuffer16BitAccess"),
                without("shaderFloat16", "storageBuffer16BitAccess").missing(module, PackDevice.STAGE_COMPUTE));
    }

    /** Subgroup operations run in the stages and kinds the device names, on sized types only with the feature. */
    @Test
    void subgroupOperationsNeedTheirStageKindAndTypes() {
        var module = requirements(HALF_SUBGROUP_MIN, Shaderc.shaderc_glsl_fragment_shader);
        assertEquals(List.of(), EVERYTHING.missing(module, PackDevice.STAGE_FRAGMENT));
        PackDevice computeOnly = new PackDevice("test", 0, 0, "test", allFeatures(), PackDevice.STAGE_COMPUTE, 0x1,
                EVERYTHING.extensions());
        assertEquals(List.of("subgroup operations in this stage", "subgroup arithmetic operations"),
                computeOnly.missing(module, PackDevice.STAGE_FRAGMENT));
        assertEquals(List.of("feature shaderSubgroupExtendedTypes"),
                without("shaderSubgroupExtendedTypes").missing(module, PackDevice.STAGE_FRAGMENT));
    }

    /** Alpha Piscium's NVIDIA path: the AMD GPU has neither the extension nor the operation. */
    @Test
    void partitionedSubgroupsNeedNvidiasExtensionAndOperation() {
        var module = requirements("""
                #version 460
                #extension GL_NV_shader_subgroup_partitioned : require
                layout(local_size_x = 32) in;
                layout(std430, binding = 0) buffer Values { uvec4 value; };
                void main() { value = subgroupPartitionNV(value.x); }
                """, Shaderc.shaderc_glsl_compute_shader);
        assertEquals(List.of(), PackDevice.offline("nvidia").missing(module, PackDevice.STAGE_COMPUTE));
        assertEquals(List.of("device extension VK_NV_shader_subgroup_partitioned", "subgroup partitioned operations"),
                PackDevice.offline("amd").missing(module, PackDevice.STAGE_COMPUTE));
    }

    /** The RTX 5080 has no 16-bit stage inputs and outputs: RenderPearl 2.4's f16vec4 varyings need them. */
    @Test
    void halfVaryingsNeedStorageInputOutput16() {
        var module = requirements("""
                #version 460
                #extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
                #extension GL_EXT_shader_16bit_storage : require
                layout(location = 0) in vec4 position;
                layout(location = 0) out f16vec4 tint;
                void main() { tint = f16vec4(position); gl_Position = position; }
                """, Shaderc.shaderc_glsl_vertex_shader);
        assertEquals(List.of("feature storageInputOutput16"),
                PackDevice.offline("nvidia").missing(module, PackDevice.STAGE_VERTEX));
        assertEquals(List.of(), PackDevice.offline("amd").missing(module, PackDevice.STAGE_VERTEX));
    }

    @Test
    void offlineProfilesAreNamed() {
        assertEquals(PackDevice.VENDOR_AMD, PackDevice.offline("AMD").vendorId());
        assertTrue(PackDevice.offline("amd").trinaryMinmax() && !PackDevice.offline("nvidia").trinaryMinmax());
        assertEquals(new GlslVendorExtensions.SizedTypes(true, true, true), PackDevice.offline("intel").sizedTypes());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PackDevice.offline("apple"));
        assertTrue(failure.getMessage().contains("[amd, intel, nvidia]"), failure.getMessage());
    }

    private static TreeSet<String> allFeatures() {
        TreeSet<String> result = new TreeSet<>();
        Stream.of(PackDevice.CORE_FEATURES, PackDevice.VULKAN11_FEATURES, PackDevice.VULKAN12_FEATURES)
                .forEach(result::addAll);
        return result;
    }

    private static PackDevice without(String... features) {
        TreeSet<String> enabled = allFeatures();
        Set.of(features).forEach(enabled::remove);
        return new PackDevice("test", 0, 0, "test", enabled, EVERYTHING.subgroupStages(),
                EVERYTHING.subgroupOperations(), EVERYTHING.extensions());
    }

    /** Compiled on a device with everything, so the compiler itself refuses nothing. */
    private static SpirvDeviceRequirements.Requirements requirements(String source, int kind) {
        PackDevice before = PackDevice.current();
        PackDevice.set(EVERYTHING);
        ByteBuffer module = GlslCompiler.compile("test", source, kind, options -> {});
        try {
            return SpirvDeviceRequirements.of(module);
        } finally {
            MemoryUtil.memFree(module);
            PackDevice.set(before);
        }
    }
}
