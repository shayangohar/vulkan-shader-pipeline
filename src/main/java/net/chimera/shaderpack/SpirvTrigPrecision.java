package net.chimera.shaderpack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Evaluates the arithmetic that feeds a pack's {@code sin}, {@code cos} or
 * {@code tan} exactly as written, by decorating it {@code NoContraction}.
 *
 * <p>Packs hash with {@code fract(sin(dot(p, k)) * 43758.5453)} at arguments in
 * the thousands, where a one-ulp change in {@code dot} gives an unrelated hash.
 * Value noise built on it (BSL's foliage sway) stays continuous only when a
 * lattice corner hashes bit-identically from both cells that share it. NVIDIA's
 * Vulkan compiler fused {@code (flr.x + 1.0) * 12.9898} differently at the two
 * call sites, so the sway leapt at every cell crossing; Iris, on the GL
 * compiler, stayed smooth. Only the backward slice of trigonometric arguments
 * is decorated, so every other operation keeps the driver's fused
 * multiply-adds.</p>
 *
 * <p>Unoptimized glslang output carries values through function variables and
 * pointer parameters, so the slice follows a load to every store into the same
 * variable, a parameter to the argument of every call, and a call to the
 * values its function returns.</p>
 */
public final class SpirvTrigPrecision {
    private static final int MAGIC = 0x07230203;
    private static final int HEADER_WORDS = 5;
    private static final int OP_EXT_INST_IMPORT = 11;
    private static final int OP_EXT_INST = 12;
    private static final int OP_FUNCTION = 54;
    private static final int OP_FUNCTION_PARAMETER = 55;
    private static final int OP_FUNCTION_CALL = 57;
    private static final int OP_VARIABLE = 59;
    private static final int OP_LOAD = 61;
    private static final int OP_STORE = 62;
    private static final int OP_ACCESS_CHAIN = 65;
    private static final int OP_IN_BOUNDS_ACCESS_CHAIN = 66;
    private static final int OP_DECORATE = 71;
    private static final int OP_PHI = 245;
    private static final int OP_RETURN_VALUE = 254;
    private static final int DECORATION_NO_CONTRACTION = 42;
    private static final int FIRST_TYPE_OPCODE = 19;
    private static final int LAST_TYPE_OPCODE = 39;
    /** GLSL.std.450 Sin, Cos, Tan. */
    private static final Set<Integer> TRIG = Set.of(13, 14, 15);
    /** FNegate, FAdd, FSub, FMul, FDiv, FRem, FMod, the matrix/vector products and Dot. */
    static final Set<Integer> FLOAT_ARITHMETIC = Set.of(
            127, 129, 131, 133, 136, 140, 141, 142, 143, 144, 145, 146, 148);

    private SpirvTrigPrecision() {}

    public static ByteBuffer apply(ByteBuffer spirv) {
        ByteBuffer view = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[view.remaining() / 4];
        view.asIntBuffer().get(words);
        int[] result = apply(words);
        if (result == words) return spirv;
        ByteBuffer out = ByteBuffer.allocateDirect(result.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        out.asIntBuffer().put(result);
        return out;
    }

    static int[] apply(int[] words) {
        if (words.length < HEADER_WORDS || words[0] != MAGIC) return words;
        Set<Integer> glslStd = new HashSet<>();
        Map<Integer, Integer> definition = new HashMap<>();        // result id -> instruction offset
        Map<Integer, Integer> baseOf = new HashMap<>();            // access chain -> base pointer
        Map<Integer, List<Integer>> stores = new HashMap<>();      // pointer -> stored values
        Map<Integer, List<Integer>> returns = new HashMap<>();     // function -> returned values
        Map<Integer, int[]> parameterSlot = new HashMap<>();       // parameter -> {function, index}
        Map<Integer, Integer> parameterCount = new HashMap<>();
        Map<Integer, List<int[]>> calls = new HashMap<>();         // function -> call instructions
        List<Integer> seeds = new ArrayList<>();
        int firstType = -1;
        int function = -1;
        for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
            int opcode = words[at] & 0xFFFF;
            int count = words[at] >>> 16;
            if (count == 0) return words;
            if (firstType < 0 && opcode >= FIRST_TYPE_OPCODE && opcode <= LAST_TYPE_OPCODE) firstType = at;
            switch (opcode) {
                case OP_EXT_INST_IMPORT -> {
                    if ("GLSL.std.450".equals(literal(words, at + 2, count - 2))) glslStd.add(words[at + 1]);
                }
                case OP_FUNCTION -> function = words[at + 2];
                case OP_FUNCTION_PARAMETER -> {
                    int index = parameterCount.merge(function, 1, Integer::sum) - 1;
                    parameterSlot.put(words[at + 2], new int[] {function, index});
                    definition.put(words[at + 2], at);
                }
                case OP_FUNCTION_CALL -> {
                    calls.computeIfAbsent(words[at + 3], f -> new ArrayList<>())
                            .add(Arrays.copyOfRange(words, at, at + count));
                    definition.put(words[at + 2], at);
                }
                case OP_RETURN_VALUE -> returns.computeIfAbsent(function, f -> new ArrayList<>()).add(words[at + 1]);
                case OP_STORE -> stores.computeIfAbsent(words[at + 1], p -> new ArrayList<>()).add(words[at + 2]);
                case OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN -> {
                    baseOf.put(words[at + 2], words[at + 3]);
                    definition.put(words[at + 2], at);
                }
                case OP_EXT_INST -> {
                    definition.put(words[at + 2], at);
                    if (glslStd.contains(words[at + 3]) && TRIG.contains(words[at + 4]) && count > 5) {
                        seeds.add(words[at + 5]);
                    }
                }
                default -> {
                    if (producesValue(opcode) && count > 2) definition.put(words[at + 2], at);
                }
            }
        }
        if (seeds.isEmpty() || firstType < 0) return words;
        // A store through an access chain is a store into its root variable.
        Map<Integer, List<Integer>> rootStores = new HashMap<>();
        stores.forEach((pointer, values) ->
                rootStores.computeIfAbsent(root(baseOf, pointer), p -> new ArrayList<>()).addAll(values));

        Set<Integer> visited = new HashSet<>();
        Set<Integer> decorate = new TreeSet<>();
        ArrayDeque<Integer> work = new ArrayDeque<>(seeds);
        while (!work.isEmpty()) {
            int id = work.pop();
            if (!visited.add(id)) continue;
            int[] slot = parameterSlot.get(id);
            if (slot != null) {
                for (int[] call : calls.getOrDefault(slot[0], List.of())) {
                    if (4 + slot[1] < call.length) work.add(call[4 + slot[1]]);
                }
            }
            Integer at = definition.get(id);
            if (at == null) continue;
            int opcode = words[at] & 0xFFFF;
            int count = words[at] >>> 16;
            switch (opcode) {
                case OP_LOAD -> {
                    // The root may be a parameter, whose value comes from each caller.
                    int pointer = root(baseOf, words[at + 3]);
                    work.addAll(rootStores.getOrDefault(pointer, List.of()));
                    work.add(pointer);
                    for (int i = at + 3; i < at + count; i++) work.add(words[i]);
                }
                case OP_FUNCTION_CALL -> {
                    work.addAll(returns.getOrDefault(words[at + 3], List.of()));
                    for (int i = at + 4; i < at + count; i++) work.add(words[i]);
                }
                case OP_FUNCTION_PARAMETER, OP_VARIABLE -> work.addAll(rootStores.getOrDefault(id, List.of()));
                default -> {
                    if (FLOAT_ARITHMETIC.contains(opcode)) decorate.add(id);
                    // Operands after the result id; literals that happen to match an id
                    // only widen the slice.
                    for (int i = at + 3; i < at + count; i++) work.add(words[i]);
                }
            }
        }
        if (decorate.isEmpty()) return words;
        // Annotations precede every type declaration.
        int[] out = new int[words.length + decorate.size() * 3];
        System.arraycopy(words, 0, out, 0, firstType);
        int w = firstType;
        for (int id : decorate) {
            out[w++] = (3 << 16) | OP_DECORATE;
            out[w++] = id;
            out[w++] = DECORATION_NO_CONTRACTION;
        }
        System.arraycopy(words, firstType, out, w, words.length - firstType);
        return out;
    }

    private static int root(Map<Integer, Integer> baseOf, int pointer) {
        int current = pointer;
        for (int guard = 0; guard < 64; guard++) {
            Integer base = baseOf.get(current);
            if (base == null) return current;
            current = base;
        }
        return current;
    }

    /** Value-producing instructions that carry (result type, result id). */
    private static boolean producesValue(int opcode) {
        return (opcode >= 77 && opcode <= 205) || opcode == OP_LOAD || opcode == OP_VARIABLE || opcode == OP_PHI;
    }

    private static String literal(int[] words, int from, int length) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < from + length; i++) {
            for (int shift = 0; shift < 32; shift += 8) {
                int c = (words[i] >>> shift) & 0xFF;
                if (c == 0) return text.toString();
                text.append((char) c);
            }
        }
        return text.toString();
    }
}
