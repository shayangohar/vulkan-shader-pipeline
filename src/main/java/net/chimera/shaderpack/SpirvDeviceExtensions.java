package net.chimera.shaderpack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The device extensions a SPIR-V module needs. Each OpExtension it declares and each extended
 * instruction set it imports needs its Vulkan extension enabled on the device, unless Vulkan 1.2
 * made it core. A GL driver refuses an {@code #extension name : require} it lacks when it compiles
 * the stage; a Vulkan driver handed such a module can crash instead. RenderPearl requires
 * GL_AMD_shader_trinary_minmax on a GPU it does not know to be NVIDIA's, and NVIDIA's driver
 * crashed in vkCreateComputePipelines on its deferred1 (2026-11-02).
 */
public final class SpirvDeviceExtensions {
    private static final int MAGIC = 0x07230203;
    private static final int HEADER_WORDS = 5;
    private static final int OP_EXTENSION = 10;
    private static final int OP_EXT_INST_IMPORT = 11;
    private static final int OP_MEMORY_MODEL = 14;

    /** SPIR-V extensions Vulkan 1.1 and 1.2 made core; the features they use are the device's to enable. */
    private static final Set<String> CORE = Set.of("SPV_KHR_16bit_storage", "SPV_KHR_device_group",
            "SPV_KHR_multiview", "SPV_KHR_shader_draw_parameters", "SPV_KHR_storage_buffer_storage_class",
            "SPV_KHR_variable_pointers", "SPV_KHR_8bit_storage", "SPV_KHR_float_controls",
            "SPV_KHR_physical_storage_buffer", "SPV_KHR_vulkan_memory_model", "SPV_EXT_descriptor_indexing",
            "SPV_EXT_shader_viewport_index_layer");

    /** The SPIR-V extensions whose Vulkan extension is not named after them. */
    private static final Map<String, String> RENAMED = Map.of(
            "SPV_KHR_shader_ballot", "VK_EXT_shader_subgroup_ballot",
            "SPV_KHR_subgroup_vote", "VK_EXT_shader_subgroup_vote",
            "SPV_KHR_non_semantic_info", "VK_KHR_shader_non_semantic_info",
            "SPV_KHR_terminate_invocation", "VK_KHR_shader_terminate_invocation",
            "SPV_KHR_post_depth_coverage", "VK_EXT_post_depth_coverage",
            "SPV_EXT_demote_to_helper_invocation", "VK_EXT_shader_demote_to_helper_invocation",
            "SPV_EXT_shader_atomic_float_add", "VK_EXT_shader_atomic_float",
            "SPV_EXT_shader_atomic_float_min_max", "VK_EXT_shader_atomic_float2",
            "SPV_EXT_shader_atomic_float16_add", "VK_EXT_shader_atomic_float2",
            "SPV_EXT_shader_image_int64", "VK_EXT_shader_image_atomic_int64");

    private SpirvDeviceExtensions() {}

    /** The device extensions {@code spirv} needs, sorted; none when it cannot be read. */
    public static SortedSet<String> required(ByteBuffer spirv) {
        ByteBuffer view = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[view.remaining() / 4];
        view.asIntBuffer().get(words);
        return required(words);
    }

    static SortedSet<String> required(int[] words) {
        SortedSet<String> result = new TreeSet<>();
        if (words.length < HEADER_WORDS || words[0] != MAGIC) return result;
        for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
            int count = words[at] >>> 16;
            if (count == 0 || at + count > words.length) break;
            int opcode = words[at] & 0xFFFF;
            // Extensions and imports precede the memory model, and every instruction after it.
            if (opcode == OP_MEMORY_MODEL) break;
            String needed = switch (opcode) {
                case OP_EXTENSION -> extension(string(words, at + 1, at + count));
                case OP_EXT_INST_IMPORT -> instructionSet(string(words, at + 2, at + count));
                default -> null;
            };
            if (needed != null) result.add(needed);
        }
        return result;
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
