package net.chimera.shaderpack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A compute module's work group size, as the compiler folded it. Iris reads a compute program's
 * size from GL once it links (ComputeProgram: GL_COMPUTE_WORK_GROUP_SIZE), so a pack may size it
 * with a constant expression: RenderPearl 2.8's composite3_b declares
 * {@code local_size_x = min(gl_MaxComputeWorkGroupSize.x, LL_CAPACITY)}.
 */
public final class SpirvLocalSize {
    private static final int MAGIC = 0x07230203;
    private static final int HEADER_WORDS = 5;
    private static final int OP_EXECUTION_MODE = 16;
    private static final int OP_FUNCTION = 54;
    private static final int MODE_LOCAL_SIZE = 17;

    private SpirvLocalSize() {}

    /** The {x, y, z} of the module's LocalSize execution mode; null when it declares none. */
    public static int[] of(ByteBuffer spirv) {
        ByteBuffer view = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[view.remaining() / 4];
        view.asIntBuffer().get(words);
        return of(words);
    }

    static int[] of(int[] words) {
        if (words.length < HEADER_WORDS || words[0] != MAGIC) return null;
        for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
            int count = words[at] >>> 16;
            if (count == 0 || at + count > words.length) break;
            int opcode = words[at] & 0xFFFF;
            // Execution modes precede the first function.
            if (opcode == OP_FUNCTION) break;
            if (opcode == OP_EXECUTION_MODE && count == 6 && words[at + 2] == MODE_LOCAL_SIZE) {
                return new int[] {words[at + 3], words[at + 4], words[at + 5]};
            }
        }
        return null;
    }
}
