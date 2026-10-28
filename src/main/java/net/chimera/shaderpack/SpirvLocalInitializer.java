package net.chimera.shaderpack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gives every uninitialized function-scope variable of a plain data type an
 * {@code OpConstantNull} initializer, and turns every plain-data
 * {@code OpUndef} into an {@code OpConstantNull}. Module-scope private globals
 * and the vertex, geometry and fragment stages' outputs, built-ins aside, get
 * the same initializer.
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
 *
 * <p>An output a path never writes is zero under NVIDIA's GL compiler too.
 * Escheridia's composite5 returns before writing its reflection history over the
 * sky. Iris leaves those pixels black; a Vulkan driver wrote whatever its
 * registers held there, and the reprojected history spread it as bright
 * single-colour specks along every edge against the sky.</p>
 *
 * <p>A variable its function writes whole before anything can read it keeps no
 * initializer: glslang stores a GLSL initializer at the top of the function, so
 * the zero is never seen, under GL or here. The null still costs: NVIDIA then
 * fills the variable per invocation. BSL's shadowcomp copies two colour tables
 * (75 vec3) into private globals this way, and zeroing them first took its
 * dispatch from 0.2 ms to 33 ms on an RTX 5080.</p>
 */
public final class SpirvLocalInitializer {
    private static final int MAGIC = 0x07230203;
    private static final int HEADER_WORDS = 5;
    private static final int OP_UNDEF = 1;
    private static final int OP_ENTRY_POINT = 15;
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
    private static final int OP_FUNCTION_END = 56;
    private static final int OP_FUNCTION_CALL = 57;
    private static final int OP_VARIABLE = 59;
    private static final int OP_STORE = 62;
    private static final int OP_DECORATE = 71;
    private static final int OP_MEMBER_DECORATE = 72;
    private static final int OP_LABEL = 248;
    private static final int DECORATION_BUILT_IN = 11;
    private static final int STORAGE_OUTPUT = 3;
    private static final int STORAGE_PRIVATE = 6;
    private static final int STORAGE_FUNCTION = 7;
    /** Vertex, Geometry and Fragment: the stages whose outputs a GL driver starts at zero here. */
    private static final Set<Integer> OUTPUT_STAGES = Set.of(0, 3, 4);

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

    /** The types, pointers, built-ins, null constants and undefs of one module, and the rewrite they allow. */
    private static final class Module {
        private final int[] words;
        private final Map<Integer, int[]> types = new HashMap<>();
        /** Each pointer type's storage class and pointee. */
        private final Map<Integer, int[]> pointers = new HashMap<>();
        private final Map<Integer, Integer> nulls = new HashMap<>();
        /** The null constants already written while the module is rewritten, by type. */
        private final Map<Integer, Integer> declaredNulls = new HashMap<>();
        /** Variables and struct types decorated BuiltIn. */
        private final Set<Integer> builtIns = new HashSet<>();
        /** Each variable's storage class. */
        private final Map<Integer, Integer> storage = new HashMap<>();
        private final List<Integer> variables = new ArrayList<>();
        private final Set<Integer> entryFunctions = new HashSet<>();
        /** Variables their function writes whole before anything can read them. */
        private final Set<Integer> writtenFirst = new HashSet<>();
        private boolean outputStages = true;
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
            findWrittenFirst();
            for (int at : variables) needed |= initializable(at) != null;
            return true;
        }

        private void record(int opcode, int at, int count) {
            switch (opcode) {
                case OP_ENTRY_POINT -> {
                    outputStages &= OUTPUT_STAGES.contains(words[at + 1]);
                    entryFunctions.add(words[at + 2]);
                }
                case OP_DECORATE, OP_MEMBER_DECORATE -> recordBuiltIn(opcode, at);
                case OP_TYPE_BOOL, OP_TYPE_INT, OP_TYPE_FLOAT, OP_TYPE_VECTOR, OP_TYPE_MATRIX,
                        OP_TYPE_ARRAY, OP_TYPE_STRUCT ->
                        types.put(words[at + 1], Arrays.copyOfRange(words, at, at + count));
                case OP_TYPE_POINTER -> pointers.put(words[at + 1], new int[] {words[at + 2], words[at + 3]});
                case OP_CONSTANT_NULL -> nulls.putIfAbsent(words[at + 1], words[at + 2]);
                case OP_FUNCTION -> { if (firstFunction < 0) firstFunction = at; }
                case OP_VARIABLE -> {
                    storage.put(words[at + 2], words[at + 3]);
                    variables.add(at);
                }
                case OP_UNDEF -> { if (plainData(types, words[at + 1])) needed = true; }
                default -> { }
            }
        }

        /**
         * Finds the variables each function's first block stores whole before any instruction
         * there names them: the function's own locals, and module-scope variables when the
         * function is the module's only entry point. The block is read up to its first call,
         * which may read any global.
         */
        private void findWrittenFirst() {
            Set<Integer> named = new HashSet<>();
            int function = -1;
            boolean started = false;
            boolean firstBlock = false;
            for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
                int opcode = words[at] & 0xFFFF;
                switch (opcode) {
                    case OP_FUNCTION -> {
                        function = words[at + 2];
                        started = false;
                        named.clear();
                    }
                    case OP_LABEL -> {
                        firstBlock = !started;
                        started = true;
                    }
                    case OP_FUNCTION_CALL, OP_FUNCTION_END -> firstBlock = false;
                    case OP_VARIABLE -> { }
                    default -> { if (firstBlock) readFirstBlock(at, opcode, function, named); }
                }
            }
        }

        /** Records a whole store to a variable nothing named before it, then every variable the instruction names. */
        private void readFirstBlock(int at, int opcode, int function, Set<Integer> named) {
            if (opcode == OP_STORE && !named.contains(words[at + 1]) && storedFirstIn(words[at + 1], function)) {
                writtenFirst.add(words[at + 1]);
            }
            for (int i = at + 1; i < at + (words[at] >>> 16); i++) {
                if (storage.containsKey(words[i])) named.add(words[i]);
            }
        }

        /**
         * Whether {@code function}'s first block runs before anything can read {@code variable}:
         * for a local, its own function; for a module-scope variable, the module's only entry point.
         */
        private boolean storedFirstIn(int variable, int function) {
            Integer kind = storage.get(variable);
            if (kind == null) return false;
            return kind == STORAGE_FUNCTION || entryFunctions.size() == 1 && entryFunctions.contains(function);
        }

        /** Records a BuiltIn decoration's target: a variable, or a struct through one of its members. */
        private void recordBuiltIn(int opcode, int at) {
            int decoration = opcode == OP_DECORATE ? words[at + 2] : words[at + 3];
            if (decoration == DECORATION_BUILT_IN) builtIns.add(words[at + 1]);
        }

        /**
         * The pointee of the uninitialized variable at {@code at} when it takes a null initializer:
         * a plain function local or private global, or a plain output of a stage whose outputs
         * start at zero, built-ins aside, that its function does not write whole first. Null otherwise.
         */
        private Integer initializable(int at) {
            if ((words[at] & 0xFFFF) != OP_VARIABLE || words[at] >>> 16 != 4) return null;
            if (writtenFirst.contains(words[at + 2])) return null;
            int[] pointer = pointers.get(words[at + 1]);
            if (pointer == null || !plainData(types, pointer[1])) return null;
            int storage = words[at + 3];
            boolean output = storage == STORAGE_OUTPUT && outputStages
                    && !builtIns.contains(words[at + 2]) && !builtIns.contains(pointer[1]);
            return storage == STORAGE_FUNCTION || storage == STORAGE_PRIVATE || output ? pointer[1] : null;
        }

        /**
         * Every word after the header, with each initializable variable given a null initializer,
         * each plain undef made a null constant, and the null constants function variables need
         * placed before the first function. A module-level variable's constant goes just ahead of
         * it, unless one is already declared there.
         */
        private List<Integer> initializedBody() {
            List<Integer> inserted = new ArrayList<>();
            List<Integer> body = new ArrayList<>(words.length + 16);
            int functionStart = -1;
            for (int at = HEADER_WORDS; at < words.length; at += words[at] >>> 16) {
                if (at == firstFunction) functionStart = body.size();
                if (initializeVariable(at, body, inserted) || nullUndef(at, body, inserted)) continue;
                if ((words[at] & 0xFFFF) == OP_CONSTANT_NULL) declaredNulls.putIfAbsent(words[at + 1], words[at + 2]);
                for (int i = 0; i < words[at] >>> 16; i++) body.add(words[at + i]);
            }
            body.addAll(functionStart, inserted);
            return body;
        }

        /** Writes the instruction at {@code at} with a null initializer when it is an initializable variable. */
        private boolean initializeVariable(int at, List<Integer> body, List<Integer> inserted) {
            Integer pointee = initializable(at);
            if (pointee == null) return false;
            int initializer = words[at + 3] == STORAGE_FUNCTION
                    ? nullConstant(pointee, inserted) : declaredNull(pointee, body);
            body.add((5 << 16) | OP_VARIABLE);
            body.add(words[at + 1]);
            body.add(words[at + 2]);
            body.add(words[at + 3]);
            body.add(initializer);
            return true;
        }

        /** A null constant of {@code type} already written to {@code body}, declaring one there when none is. */
        private int declaredNull(int type, List<Integer> body) {
            Integer nullId = declaredNulls.get(type);
            if (nullId == null) {
                nullId = bound++;
                declaredNulls.put(type, nullId);
                nulls.putIfAbsent(type, nullId);
                body.add((3 << 16) | OP_CONSTANT_NULL);
                body.add(type);
                body.add(nullId);
            }
            return nullId;
        }

        /**
         * Declares the plain undef at {@code at} as a null constant with the same id: in place
         * among the module-level declarations, or before the first function when a function
         * holds it.
         */
        private boolean nullUndef(int at, List<Integer> body, List<Integer> inserted) {
            if ((words[at] & 0xFFFF) != OP_UNDEF || !plainData(types, words[at + 1])) return false;
            boolean moduleLevel = at < firstFunction;
            if (moduleLevel) declaredNulls.putIfAbsent(words[at + 1], words[at + 2]);
            List<Integer> into = moduleLevel ? body : inserted;
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
