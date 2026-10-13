package net.chimera.shaderpack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
    static <T> T forPackCompile(Supplier<T> creation) {
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
        Module module = new Module(words);
        if (!module.scan() || !module.needed || module.firstFunction < 0) return words;
        List<Integer> body = module.initializedBody();
        int[] result = new int[HEADER_WORDS + body.size()];
        System.arraycopy(words, 0, result, 0, HEADER_WORDS);
        result[3] = module.bound;
        for (int i = 0; i < body.size(); i++) result[HEADER_WORDS + i] = body.get(i);
        return result;
    }

    /** The types, function-storage pointers and null constants of one module, and the rewrite they allow. */
    private static final class Module {
        private final int[] words;
        private final Map<Integer, int[]> types = new HashMap<>();
        private final Map<Integer, Integer> functionPointee = new HashMap<>();
        private final Map<Integer, Integer> nulls = new HashMap<>();
        private int firstFunction = -1;
        private boolean needed;
        private int bound;

        private Module(int[] words) {
            this.words = words;
            this.bound = words[3];
        }

        /** Records every instruction; false when one has a word count of zero. */
        private boolean scan() {
            for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
                int count = words[at] >>> 16;
                if (count == 0) return false;
                record(words[at] & 0xFFFF, at, count);
            }
            return true;
        }

        private void record(int opcode, int at, int count) {
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

        /**
         * Every word after the header, with each plain function-scope variable given a null
         * initializer and any null constant that needed placed before the first function.
         */
        private List<Integer> initializedBody() {
            List<Integer> inserted = new ArrayList<>();
            List<Integer> body = new ArrayList<>(words.length + 16);
            for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
                int opcode = words[at] & 0xFFFF;
                int count = words[at] >>> 16;
                Integer pointee = opcode == OP_VARIABLE && count == 4 && words[at + 3] == STORAGE_FUNCTION
                        ? functionPointee.get(words[at + 1]) : null;
                if (plainData(types, pointee)) {
                    body.add((5 << 16) | OP_VARIABLE);
                    body.add(words[at + 1]);
                    body.add(words[at + 2]);
                    body.add(STORAGE_FUNCTION);
                    body.add(nullConstant(pointee, inserted));
                    continue;
                }
                for (int i = 0; i < count; i++) body.add(words[at + i]);
            }
            // Null constants are module-level: place them before the first function.
            // Function-storage variables only occur inside functions, so every
            // word before it is unchanged.
            body.addAll(firstFunction - HEADER_WORDS, inserted);
            return body;
        }

        /** The null constant of {@code type}, declaring a new one into {@code inserted} when the module has none. */
        private int nullConstant(int type, List<Integer> inserted) {
            Integer nullId = nulls.get(type);
            if (nullId == null) {
                nullId = bound++;
                nulls.put(type, nullId);
                inserted.add((3 << 16) | OP_CONSTANT_NULL);
                inserted.add(type);
                inserted.add(nullId);
            }
            return nullId;
        }
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
