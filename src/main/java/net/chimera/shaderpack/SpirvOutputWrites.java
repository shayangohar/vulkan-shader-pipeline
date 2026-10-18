package net.chimera.shaderpack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The colour output locations a fragment module writes.
 *
 * <p>A pack program can declare an output and, under the options in use, never write it: its
 * RENDERTARGETS names the buffer while the write sits in an inactive branch, or a shadow program
 * writes shadowcolor0 alone. A Vulkan attachment the shader leaves unwritten receives undefined
 * values. GL drivers leave such a draw buffer as it was, and packs rely on that, so Chimera masks
 * the colour writes of every attachment whose location the module never stores to
 * (GraphicsPipelineMrtMixin). An output array counts as written at every location it spans.</p>
 */
public final class SpirvOutputWrites {
    /** The answer when a module cannot be read: every location counts as written. */
    public static final int ALL = -1;

    private static final int MAGIC = 0x07230203;
    private static final int HEADER_WORDS = 5;
    private static final int OP_EXT_INST = 12;
    private static final int OP_TYPE_ARRAY = 28;
    private static final int OP_TYPE_POINTER = 32;
    private static final int OP_CONSTANT = 43;
    private static final int OP_FUNCTION_CALL = 57;
    private static final int OP_VARIABLE = 59;
    private static final int OP_STORE = 62;
    private static final int OP_COPY_MEMORY = 63;
    private static final int OP_ACCESS_CHAIN = 65;
    private static final int OP_IN_BOUNDS_ACCESS_CHAIN = 66;
    private static final int OP_DECORATE = 71;
    private static final int DECORATION_LOCATION = 30;
    private static final int STORAGE_OUTPUT = 3;

    private SpirvOutputWrites() {}

    /** A bit per output location the module writes, or {@link #ALL} when it cannot be read. */
    public static int writtenLocations(ByteBuffer spirv) {
        ByteBuffer view = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[view.remaining() / 4];
        view.asIntBuffer().get(words);
        return writtenLocations(words);
    }

    static int writtenLocations(int[] words) {
        if (words.length < HEADER_WORDS || words[0] != MAGIC) return ALL;
        Module module = new Module();
        for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
            int count = words[at] >>> 16;
            if (count == 0 || at + count > words.length) return ALL;
            module.record(words, at, count);
        }
        return module.writtenMask();
    }

    /** What one pass over a module learns: locations, output variables, the pointers into them, their writes. */
    private static final class Module {
        private final Map<Integer, Integer> locations = new HashMap<>();
        private final Map<Integer, Integer> pointees = new HashMap<>();
        private final Map<Integer, Integer> arrayLengths = new HashMap<>();
        private final Map<Integer, Integer> constants = new HashMap<>();
        private final Map<Integer, Integer> variableTypes = new HashMap<>();
        // Each pointer into an output variable, mapped to that variable. Definitions precede
        // their uses in SPIR-V's block order, so one pass sees every chain before its stores.
        private final Map<Integer, Integer> outputs = new HashMap<>();
        private final Set<Integer> written = new HashSet<>();

        private void record(int[] words, int at, int count) {
            switch (words[at] & 0xFFFF) {
                case OP_DECORATE -> {
                    if (count >= 4 && words[at + 2] == DECORATION_LOCATION) locations.put(words[at + 1], words[at + 3]);
                }
                case OP_TYPE_POINTER -> pointees.put(words[at + 1], words[at + 3]);
                case OP_TYPE_ARRAY -> arrayLengths.put(words[at + 1], words[at + 3]);
                case OP_CONSTANT -> constants.put(words[at + 2], words[at + 3]);
                case OP_VARIABLE -> {
                    if (words[at + 3] == STORAGE_OUTPUT) {
                        outputs.put(words[at + 2], words[at + 2]);
                        variableTypes.put(words[at + 2], words[at + 1]);
                    }
                }
                case OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN -> {
                    Integer output = outputs.get(words[at + 3]);
                    if (output != null) outputs.put(words[at + 2], output);
                }
                case OP_STORE, OP_COPY_MEMORY -> markWritten(words[at + 1]);
                // A pointer handed to a function or an extended instruction may be written there.
                case OP_FUNCTION_CALL -> markOperands(words, at + 4, at + count);
                case OP_EXT_INST -> markOperands(words, at + 5, at + count);
                default -> { }
            }
        }

        private void markOperands(int[] words, int from, int to) {
            for (int at = from; at < to; at++) markWritten(words[at]);
        }

        private void markWritten(int pointer) {
            Integer output = outputs.get(pointer);
            if (output != null) written.add(output);
        }

        private int writtenMask() {
            int mask = 0;
            for (int variable : written) {
                Integer location = locations.get(variable);
                if (location == null) continue;
                int span = span(variable);
                for (int slot = location; slot < location + span && slot < Integer.SIZE; slot++) {
                    if (slot >= 0) mask |= 1 << slot;
                }
            }
            return mask;
        }

        /** How many locations an output variable covers: an array's length, else one. */
        private int span(int variable) {
            Integer pointee = pointees.get(variableTypes.get(variable));
            Integer lengthId = pointee == null ? null : arrayLengths.get(pointee);
            Integer length = lengthId == null ? null : constants.get(lengthId);
            return length == null || length < 1 ? 1 : length;
        }
    }
}
