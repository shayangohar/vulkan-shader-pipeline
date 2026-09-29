package net.chimera.shaderpack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Experimental ({@code -Dchimera.spirvNoContraction=true}): decorates every
 * floating-point arithmetic result of a pack module with {@code NoContraction},
 * so the Vulkan driver evaluates each operation as written instead of fusing
 * or reassociating it.
 *
 * <p>Packs hash with {@code fract(sin(dot(p, k)) * 43758.5453)} at arguments in
 * the thousands; a one-ulp change in {@code dot} gives an unrelated hash. Value
 * noise stays continuous only if a lattice corner hashes identically from both
 * neighbouring cells. BSL's grass sway leapt at cell crossings on NVIDIA's
 * Vulkan compiler while Iris, on the GL compiler, stayed smooth.</p>
 */
public final class SpirvNoContraction {
    public static final boolean ENABLED = Boolean.getBoolean("chimera.spirvNoContraction");

    private static final int MAGIC = 0x07230203;
    private static final int HEADER_WORDS = 5;
    private static final int OP_DECORATE = 71;
    private static final int DECORATION_NO_CONTRACTION = 42;
    private static final int FIRST_TYPE_OPCODE = 19;
    private static final int LAST_TYPE_OPCODE = 39;
    /** FNegate, FAdd, FSub, FMul, FDiv, FRem, FMod, the matrix/vector products and Dot. */
    private static final Set<Integer> FLOAT_ARITHMETIC = Set.of(
            127, 129, 131, 133, 136, 140, 141, 142, 143, 144, 145, 146, 148);

    private SpirvNoContraction() {}

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
        int firstType = -1;
        List<Integer> results = new ArrayList<>();
        for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
            int opcode = words[at] & 0xFFFF;
            int count = words[at] >>> 16;
            if (count == 0) return words;
            if (firstType < 0 && opcode >= FIRST_TYPE_OPCODE && opcode <= LAST_TYPE_OPCODE) firstType = at;
            if (FLOAT_ARITHMETIC.contains(opcode) && count >= 4) results.add(words[at + 2]);
        }
        if (results.isEmpty() || firstType < 0) return words;
        // Annotations precede every type declaration.
        int[] out = new int[words.length + results.size() * 3];
        System.arraycopy(words, 0, out, 0, firstType);
        int w = firstType;
        for (int id : results) {
            out[w++] = (3 << 16) | OP_DECORATE;
            out[w++] = id;
            out[w++] = DECORATION_NO_CONTRACTION;
        }
        System.arraycopy(words, firstType, out, w, words.length - firstType);
        return out;
    }
}
