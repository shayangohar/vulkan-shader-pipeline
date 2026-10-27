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
 * {@code OpConstantNull} initializer, and turns every plain-data
 * {@code OpUndef} into an {@code OpConstantNull}.
 *
 * <p>GLSL leaves such locals undefined. The GL drivers packs are authored
 * against start them at zero, and packs read them before writing them
 * (Complementary's {@code parallaxTexDepth} feeds its first POM test). A
 * Vulkan driver keeps whatever the register held, so the pack's branch
 * changes with unrelated GPU state. Zero is the value those packs were
 * authored and tested with. Opaque types (images, samplers) are left alone;
 * they cannot be null-initialized and are never read uninitialized.</p>
 *
 * <p>Pack modules reach this pass already optimized, and the optimizer's SSA
 * rewrite has replaced a read of a local nothing wrote with {@code OpUndef}.
 * The driver may then pick any value: Ebin's held-light {@code falloff}, unset
 * outside the light's radius, became {@code max(torch, undef)}, NVIDIA folded
 * that to the slightly negative torch level, and {@code pow} of it turned the
 * water NaN, then black. The null constant is the zero the variable would have
 * started at.</p>
 */
public final class SpirvLocalInitializer {
    private static final int MAGIC = 0x07230203;
    private static final int HEADER_WORDS = 5;
    private static final int OP_UNDEF = 1;
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

    private SpirvLocalInitializer() {}

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

    /** The types, function-storage pointers, null constants and undefs of one module, and the rewrite they allow. */
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
                case OP_UNDEF -> { if (plainData(types, words[at + 1])) needed = true; }
                default -> { }
            }
        }

        /**
         * Every word after the header, with each plain function-scope variable given a null
         * initializer, each plain undef made a null constant, and any null constant that
         * needed placed before the first function.
         */
        private List<Integer> initializedBody() {
            List<Integer> inserted = new ArrayList<>();
            List<Integer> body = new ArrayList<>(words.length + 16);
            for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
                if (initializeVariable(at, body, inserted) || nullUndef(at, body, inserted)) continue;
                for (int i = 0; i < words[at] >>> 16; i++) body.add(words[at + i]);
            }
            // Null constants are module-level: place them before the first function.
            // Every word before it keeps its length: an undef there becomes a null
            // constant in place, and function variables only occur inside functions.
            body.addAll(firstFunction - HEADER_WORDS, inserted);
            return body;
        }

        /** Writes the instruction at {@code at} with a null initializer when it is a plain function variable. */
        private boolean initializeVariable(int at, List<Integer> body, List<Integer> inserted) {
            boolean variable = (words[at] & 0xFFFF) == OP_VARIABLE && words[at] >>> 16 == 4
                    && words[at + 3] == STORAGE_FUNCTION;
            Integer pointee = variable ? functionPointee.get(words[at + 1]) : null;
            if (!plainData(types, pointee)) return false;
            body.add((5 << 16) | OP_VARIABLE);
            body.add(words[at + 1]);
            body.add(words[at + 2]);
            body.add(STORAGE_FUNCTION);
            body.add(nullConstant(pointee, inserted));
            return true;
        }

        /**
         * Declares the plain undef at {@code at} as a null constant with the same id: in place
         * among the module-level declarations, or before the first function when a function
         * holds it.
         */
        private boolean nullUndef(int at, List<Integer> body, List<Integer> inserted) {
            if ((words[at] & 0xFFFF) != OP_UNDEF || !plainData(types, words[at + 1])) return false;
            List<Integer> into = at < firstFunction ? body : inserted;
            into.add((3 << 16) | OP_CONSTANT_NULL);
            into.add(words[at + 1]);
            into.add(words[at + 2]);
            return true;
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
