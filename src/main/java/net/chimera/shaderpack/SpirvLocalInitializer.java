package net.chimera.shaderpack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives every uninitialized function-scope variable of a plain data type an
 * {@code OpConstantNull} initializer.
 *
 * <p>GLSL leaves such locals undefined. The GL drivers packs are authored
 * against start them at zero, and packs read them before writing them
 * (Complementary's {@code parallaxTexDepth} feeds its first POM test). A
 * Vulkan driver keeps whatever the register held, so the pack's branch
 * changes with unrelated GPU state. Zero is the value those packs were
 * authored and tested with. Opaque types (images, samplers) are left alone;
 * they cannot be null-initialized and are never read uninitialized.</p>
 */
public final class SpirvLocalInitializer {
    private static final int MAGIC = 0x07230203;
    private static final int HEADER_WORDS = 5;
    private static final int OP_TYPE_BOOL = 20;
    private static final int OP_TYPE_INT = 21;
    private static final int OP_TYPE_FLOAT = 22;
    private static final int OP_TYPE_VECTOR = 23;
    private static final int OP_TYPE_MATRIX = 24;
    private static final int OP_TYPE_ARRAY = 28;
    private static final int OP_TYPE_STRUCT = 30;
    private static final int OP_TYPE_POINTER = 32;
    private static final int OP_CONSTANT_NULL = 46;
    private static final int OP_FUNCTION = 54;
    private static final int OP_VARIABLE = 59;
    private static final int STORAGE_FUNCTION = 7;

    // Set only while a pack pipeline compiles; host shaders keep their bytes.
    private static final ThreadLocal<Boolean> PACK_COMPILE = ThreadLocal.withInitial(() -> false);

    private SpirvLocalInitializer() {}

    /** Runs a pack pipeline creation with pack-module initialization enabled. */
    static <T> T forPackCompile(java.util.function.Supplier<T> creation) {
        boolean outer = PACK_COMPILE.get();
        PACK_COMPILE.set(true);
        try {
            return creation.get();
        } finally {
            PACK_COMPILE.set(outer);
        }
    }

    /** True while a pack pipeline's shaders compile on this thread. */
    public static boolean packCompileActive() {
        return PACK_COMPILE.get();
    }

    /** Returns the transformed module, or the input when nothing needs an initializer. */
    public static ByteBuffer apply(ByteBuffer spirv) {
        int[] words = toWords(spirv);
        int[] result = apply(words);
        return result == words ? spirv : toBuffer(result);
    }

    static int[] apply(int[] words) {
        if (words.length < HEADER_WORDS || words[0] != MAGIC) return words;
        Map<Integer, int[]> types = new HashMap<>();
        Map<Integer, Integer> functionPointee = new HashMap<>();
        Map<Integer, Integer> nulls = new HashMap<>();
        int firstFunction = -1;
        boolean needed = false;
        for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
            int opcode = words[at] & 0xFFFF;
            int count = words[at] >>> 16;
            if (count == 0) return words;
            switch (opcode) {
                case OP_TYPE_BOOL, OP_TYPE_INT, OP_TYPE_FLOAT, OP_TYPE_VECTOR, OP_TYPE_MATRIX,
                        OP_TYPE_ARRAY, OP_TYPE_STRUCT ->
                        types.put(words[at + 1], Arrays.copyOfRange(words, at, at + count));
                case OP_TYPE_POINTER -> {
                    if (words[at + 2] == STORAGE_FUNCTION) functionPointee.put(words[at + 1], words[at + 3]);
                }
                case OP_CONSTANT_NULL -> nulls.putIfAbsent(words[at + 1], words[at + 2]);
                case OP_FUNCTION -> { if (firstFunction < 0) firstFunction = at; }
                case OP_VARIABLE -> {
                    if (count == 4 && words[at + 3] == STORAGE_FUNCTION
                            && plainData(types, functionPointee.get(words[at + 1]))) needed = true;
                }
                default -> { }
            }
        }
        if (!needed || firstFunction < 0) return words;

        int bound = words[3];
        List<Integer> inserted = new ArrayList<>();
        List<Integer> body = new ArrayList<>(words.length + 16);
        for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
            int opcode = words[at] & 0xFFFF;
            int count = words[at] >>> 16;
            if (opcode == OP_VARIABLE && count == 4 && words[at + 3] == STORAGE_FUNCTION) {
                Integer pointee = functionPointee.get(words[at + 1]);
                if (plainData(types, pointee)) {
                    Integer nullId = nulls.get(pointee);
                    if (nullId == null) {
                        nullId = bound++;
                        nulls.put(pointee, nullId);
                        inserted.add((3 << 16) | OP_CONSTANT_NULL);
                        inserted.add(pointee);
                        inserted.add(nullId);
                    }
                    body.add((5 << 16) | OP_VARIABLE);
                    body.add(words[at + 1]);
                    body.add(words[at + 2]);
                    body.add(STORAGE_FUNCTION);
                    body.add(nullId);
                    continue;
                }
            }
            for (int i = 0; i < count; i++) body.add(words[at + i]);
        }
        // Null constants are module-level: place them before the first function.
        // Function-storage variables only occur inside functions, so every
        // word before it is unchanged.
        body.addAll(firstFunction - HEADER_WORDS, inserted);

        int[] result = new int[HEADER_WORDS + body.size()];
        System.arraycopy(words, 0, result, 0, HEADER_WORDS);
        result[3] = bound;
        for (int i = 0; i < body.size(); i++) result[HEADER_WORDS + i] = body.get(i);
        return result;
    }

    private static boolean plainData(Map<Integer, int[]> types, Integer id) {
        int[] type = id == null ? null : types.get(id);
        if (type == null) return false;
        return switch (type[0] & 0xFFFF) {
            case OP_TYPE_BOOL, OP_TYPE_INT, OP_TYPE_FLOAT -> true;
            case OP_TYPE_VECTOR, OP_TYPE_MATRIX, OP_TYPE_ARRAY -> plainData(types, type[2]);
            case OP_TYPE_STRUCT -> {
                for (int i = 2; i < type.length; i++) if (!plainData(types, type[i])) yield false;
                yield true;
            }
            default -> false;
        };
    }

    private static int[] toWords(ByteBuffer spirv) {
        ByteBuffer view = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[view.remaining() / 4];
        view.asIntBuffer().get(words);
        return words;
    }

    private static ByteBuffer toBuffer(int[] words) {
        ByteBuffer out = ByteBuffer.allocateDirect(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        out.asIntBuffer().put(words);
        return out;
    }
}
