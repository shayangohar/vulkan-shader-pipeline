package net.chimera.shaderpack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What a SPIR-V module needs from the device. Each OpExtension it declares and each extended
 * instruction set it imports needs its Vulkan extension enabled on the device, unless Vulkan 1.2
 * made it core; each OpCapability may need a feature (PackDevice.missing). A GL driver refuses an
 * {@code #extension name : require} it lacks when it compiles the stage; a Vulkan driver handed
 * such a module can crash instead. RenderPearl requires GL_AMD_shader_trinary_minmax on a GPU it
 * does not know to be NVIDIA's, and NVIDIA's driver crashed in vkCreateComputePipelines on its
 * deferred1 (2026-11-02).
 */
public final class SpirvDeviceRequirements {
    private static final int MAGIC = 0x07230203;
    private static final int HEADER_WORDS = 5;
    private static final int OP_EXTENSION = 10;
    private static final int OP_EXT_INST_IMPORT = 11;
    private static final int OP_MEMORY_MODEL = 14;
    private static final int OP_CAPABILITY = 17;

    /** SPIR-V extensions Vulkan 1.1 and 1.2 made core; the features they use are the device's to enable. */
    private static final Set<String> CORE = Set.of("SPV_KHR_16bit_storage", "SPV_KHR_device_group",
            "SPV_KHR_multiview", "SPV_KHR_shader_draw_parameters", "SPV_KHR_storage_buffer_storage_class",
            "SPV_KHR_variable_pointers", "SPV_KHR_8bit_storage", "SPV_KHR_float_controls",
            "SPV_KHR_physical_storage_buffer", "SPV_KHR_vulkan_memory_model", "SPV_EXT_descriptor_indexing",
            "SPV_EXT_shader_viewport_index_layer");

    /**
     * The SPIR-V extensions whose Vulkan extension is not named after them. Most of the KHR ones
     * gained a shader_ prefix in Vulkan (audit GD6): without their entry a refusal names an
     * extension that does not exist, and a device created with the real one refuses the program.
     */
    private static final Map<String, String> RENAMED = Map.ofEntries(
            Map.entry("SPV_KHR_shader_ballot", "VK_EXT_shader_subgroup_ballot"),
            Map.entry("SPV_KHR_subgroup_vote", "VK_EXT_shader_subgroup_vote"),
            Map.entry("SPV_KHR_non_semantic_info", "VK_KHR_shader_non_semantic_info"),
            Map.entry("SPV_KHR_terminate_invocation", "VK_KHR_shader_terminate_invocation"),
            Map.entry("SPV_KHR_subgroup_uniform_control_flow", "VK_KHR_shader_subgroup_uniform_control_flow"),
            Map.entry("SPV_KHR_integer_dot_product", "VK_KHR_shader_integer_dot_product"),
            Map.entry("SPV_KHR_expect_assume", "VK_KHR_shader_expect_assume"),
            Map.entry("SPV_KHR_quad_control", "VK_KHR_shader_quad_control"),
            Map.entry("SPV_KHR_maximal_reconvergence", "VK_KHR_shader_maximal_reconvergence"),
            Map.entry("SPV_KHR_float_controls2", "VK_KHR_shader_float_controls2"),
            Map.entry("SPV_KHR_subgroup_rotate", "VK_KHR_shader_subgroup_rotate"),
            Map.entry("SPV_KHR_relaxed_extended_instruction", "VK_KHR_shader_relaxed_extended_instruction"),
            Map.entry("SPV_KHR_untyped_pointers", "VK_KHR_shader_untyped_pointers"),
            Map.entry("SPV_EXT_replicated_composites", "VK_EXT_shader_replicated_composites"),
            Map.entry("SPV_KHR_ray_tracing", "VK_KHR_ray_tracing_pipeline"),
            Map.entry("SPV_NV_shading_rate", "VK_NV_shading_rate_image"),
            Map.entry("SPV_EXT_fragment_fully_covered", "VK_EXT_conservative_rasterization"),
            Map.entry("SPV_EXT_fragment_invocation_density", "VK_EXT_fragment_density_map"),
            Map.entry("SPV_KHR_post_depth_coverage", "VK_EXT_post_depth_coverage"),
            Map.entry("SPV_EXT_demote_to_helper_invocation", "VK_EXT_shader_demote_to_helper_invocation"),
            Map.entry("SPV_EXT_shader_atomic_float_add", "VK_EXT_shader_atomic_float"),
            Map.entry("SPV_EXT_shader_atomic_float_min_max", "VK_EXT_shader_atomic_float2"),
            Map.entry("SPV_EXT_shader_atomic_float16_add", "VK_EXT_shader_atomic_float2"),
            Map.entry("SPV_EXT_shader_image_int64", "VK_EXT_shader_image_atomic_int64"));

    /** A module's device extensions and the capabilities it declares, each sorted. */
    public record Requirements(SortedSet<String> extensions, SortedSet<Integer> capabilities) {}

    private SpirvDeviceRequirements() {}

    /** What {@code spirv} needs; nothing when it cannot be read. */
    public static Requirements of(ByteBuffer spirv) {
        ByteBuffer view = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[view.remaining() / 4];
        view.asIntBuffer().get(words);
        return of(words);
    }

    /** The device extensions {@code spirv} needs, sorted; none when it cannot be read. */
    public static SortedSet<String> extensions(ByteBuffer spirv) {
        return of(spirv).extensions();
    }

    static Requirements of(int[] words) {
        SortedSet<String> extensions = new TreeSet<>();
        SortedSet<Integer> capabilities = new TreeSet<>();
        if (words.length < HEADER_WORDS || words[0] != MAGIC) return new Requirements(extensions, capabilities);
        for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
            int count = words[at] >>> 16;
            if (count == 0 || at + count > words.length) break;
            int opcode = words[at] & 0xFFFF;
            // Capabilities, extensions and imports precede the memory model, and every instruction after it.
            if (opcode == OP_MEMORY_MODEL) break;
            if (opcode == OP_CAPABILITY && count > 1) capabilities.add(words[at + 1]);
            String needed = switch (opcode) {
                case OP_EXTENSION -> extension(string(words, at + 1, at + count));
                case OP_EXT_INST_IMPORT -> instructionSet(string(words, at + 2, at + count));
                default -> null;
            };
            if (needed != null) extensions.add(needed);
        }
        return new Requirements(extensions, capabilities);
    }

    /** The Vulkan extension a SPIR-V extension needs, or null when Vulkan 1.2 has it in core. */
    static String extension(String name) {
        if (CORE.contains(name)) return null;
        String renamed = RENAMED.get(name);
        if (renamed != null) return renamed;
        return name.startsWith("SPV_") ? "VK_" + name.substring(4) : name;
    }

    /** The Vulkan extension an extended instruction set needs, or null for GLSL.std.450. */
    static String instructionSet(String name) {
        if (name.equals("GLSL.std.450")) return null;
        if (name.startsWith("NonSemantic.")) return "VK_KHR_shader_non_semantic_info";
        return extension(name);
    }

    /** A literal string: UTF-8, four bytes to a word with the first in the low byte, ending at a nul. */
    private static String string(int[] words, int from, int to) {
        byte[] bytes = new byte[(to - from) * 4];
        int length = 0;
        for (int word = from; word < to; word++) {
            for (int shift = 0; shift < 32; shift += 8) {
                byte value = (byte) (words[word] >>> shift);
                if (value == 0) return new String(bytes, 0, length, StandardCharsets.UTF_8);
                bytes[length++] = value;
            }
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
