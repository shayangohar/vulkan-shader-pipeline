package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The std140 uniform block a translated compute stage reads its value uniforms from. Each member
 * states the offset dispatch writes it at: implicit std140 packs a scalar into a vec3's fourth
 * word, which this layout leaves empty. Solas's frameCounter, declared after cameraPosition, read
 * that empty word and stayed 0.
 */
public final class ComputeValueBlock {
    /** std140 sizes of the matrix value uniforms. */
    private static final Map<String, Integer> MATRIX_SIZES = Map.of("mat2", 32, "mat3", 48, "mat4", 64);
    private static final List<String> FOUR_WORD_VECTORS =
            List.of("vec3", "vec4", "ivec3", "ivec4", "uvec3", "uvec4", "bvec3", "bvec4");
    private static final Set<String> TWO_WORD_VECTORS = Set.of("vec2", "ivec2", "uvec2", "bvec2");

    private ComputeValueBlock() {}

    /** One live value of the block: dispatch writes it at offset, and the block declares it there. */
    public record Value(String name, String type, int offset, int size) {}

    /** Lays the values out in the map's order, each at its std140 alignment. */
    public static List<Value> layout(Map<String, String> typesByName) {
        int offset = 0;
        List<Value> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : typesByName.entrySet()) {
            int size = size(entry.getValue());
            offset = align(offset, alignment(entry.getValue()));
            result.add(new Value(entry.getKey(), entry.getValue(), offset, size));
            offset += size;
        }
        return List.copyOf(result);
    }

    /** The block's byte size, rounded to 16; 0 with no values. */
    public static int bufferSize(List<Value> values) {
        if (values == null || values.isEmpty()) return 0;
        Value last = values.get(values.size() - 1);
        return align(last.offset() + last.size(), 16);
    }

    /** The block's GLSL declaration at this binding; empty with no values. */
    public static String declaration(String blockName, List<Value> values, int binding) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder block = new StringBuilder("layout(std140, binding = ").append(binding)
                .append(") uniform ").append(blockName).append(" {\n");
        for (Value value : values) {
            block.append("    layout(offset = ").append(value.offset()).append(") ")
                    .append(value.type()).append(' ').append(value.name()).append(";\n");
        }
        return block.append("};\n").toString();
    }

    private static int alignment(String type) {
        if (isFourWordVector(type) || type.startsWith("mat")) return 16;
        if (type.endsWith("vec2")) return 8;
        return 4;
    }

    private static int size(String type) {
        Integer matrixSize = MATRIX_SIZES.get(type);
        if (matrixSize != null) return matrixSize;
        if (isFourWordVector(type)) return 16;
        if (TWO_WORD_VECTORS.contains(type)) return 8;
        return 4;
    }

    /** vec3 and vec4 of every component type, which std140 sizes and aligns as four words. */
    private static boolean isFourWordVector(String type) {
        return FOUR_WORD_VECTORS.stream().anyMatch(type::startsWith);
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }
}
