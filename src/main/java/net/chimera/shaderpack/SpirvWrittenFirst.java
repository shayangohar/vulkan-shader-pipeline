package net.chimera.shaderpack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The variables a module writes whole before anything can read them, so their starting value is
 * never seen: a store of the whole variable comes before every other instruction naming it on
 * every path, which is to say it dominates them. A local is judged in its own function. A private
 * global or output is judged in the module's only entry point, where a call counts as a read when
 * another function names the variable, and a return or an emit reads every output. A geometry
 * stage's outputs are undefined after an emit, which GeometryStageBridge's wrapper covers by
 * storing them back; this only decides which start from zero.
 *
 * <p>A GLSL initializer is such a store. glslang writes it where the variable is declared, a
 * global's at the top of main, and the optimizer may then turn a global into a local and leave its
 * store after a branch: BSL's world programs store their light-colour and offset tables after the
 * short-circuit test of an earlier initializer.</p>
 */
final class SpirvWrittenFirst {
    private static final int OP_FUNCTION = 54;
    private static final int OP_FUNCTION_END = 56;
    private static final int OP_FUNCTION_CALL = 57;
    private static final int OP_VARIABLE = 59;
    private static final int OP_STORE = 62;
    private static final int OP_EMIT_VERTEX = 218;
    private static final int OP_EMIT_STREAM_VERTEX = 220;
    private static final int OP_LABEL = 248;
    private static final int OP_RETURN = 253;
    private static final int OP_RETURN_VALUE = 254;
    private static final int STORAGE_OUTPUT = 3;
    private static final int STORAGE_PRIVATE = 6;
    private static final int STORAGE_FUNCTION = 7;

    private final int[] words;
    /** Each variable's storage class. */
    private final Map<Integer, Integer> storage;
    private final Set<Integer> entryFunctions;
    /** Module-scope variables a function other than the entry point names. */
    private final Set<Integer> namedElsewhere = new HashSet<>();

    /** One function's blocks, each the offsets of its instructions from its label on. */
    private record Function(int id, List<List<Integer>> blocks) {}

    /** An instruction naming a variable: its block, its place there, and whether it stores the whole variable. */
    private record Use(int block, int position, boolean wholeStore) {}

    private SpirvWrittenFirst(int[] words, Map<Integer, Integer> storage, Set<Integer> entryFunctions) {
        this.words = words;
        this.storage = storage;
        this.entryFunctions = entryFunctions;
    }

    /** The variables written whole first, from the functions starting at word {@code firstFunction}. */
    static Set<Integer> find(int[] words, int firstFunction, Map<Integer, Integer> storage,
                             Set<Integer> entryFunctions) {
        if (firstFunction < 0) return Set.of();
        SpirvWrittenFirst finder = new SpirvWrittenFirst(words, storage, entryFunctions);
        List<Function> functions = finder.functions(firstFunction);
        for (Function function : functions) {
            if (!finder.soleEntry(function)) finder.recordModuleNames(function);
        }
        Set<Integer> written = new HashSet<>();
        for (Function function : functions) finder.judge(function, written);
        return written;
    }

    private List<Function> functions(int from) {
        List<Function> functions = new ArrayList<>();
        Function current = null;
        for (int at = from; at < words.length; at += words[at] >>> 16) {
            int opcode = words[at] & 0xFFFF;
            if (opcode == OP_FUNCTION) {
                current = new Function(words[at + 2], new ArrayList<>());
                functions.add(current);
            } else if (opcode == OP_FUNCTION_END) {
                current = null;
            } else if (current != null && opcode == OP_LABEL) {
                current.blocks().add(new ArrayList<>(List.of(at)));
            } else if (current != null && !current.blocks().isEmpty()) {
                current.blocks().get(current.blocks().size() - 1).add(at);
            }
        }
        return functions;
    }

    private boolean soleEntry(Function function) {
        return entryFunctions.size() == 1 && entryFunctions.contains(function.id());
    }

    private void recordModuleNames(Function function) {
        for (List<Integer> block : function.blocks()) {
            for (int at : block) {
                // An emit in a helper reads every output, so a call that reaches it does too.
                int opcode = words[at] & 0xFFFF;
                if (opcode == OP_EMIT_VERTEX || opcode == OP_EMIT_STREAM_VERTEX) namedElsewhere.addAll(outputs());
                for (int i = at + 1; i < at + (words[at] >>> 16); i++) {
                    if (judged(words[i], true) && storage.get(words[i]) != STORAGE_FUNCTION) {
                        namedElsewhere.add(words[i]);
                    }
                }
            }
        }
    }

    /** Whether a function judges {@code id}: a local, or in the sole entry point a private global or output. */
    private boolean judged(int id, boolean entry) {
        Integer kind = storage.get(id);
        if (kind == null) return false;
        return kind == STORAGE_FUNCTION || entry && (kind == STORAGE_PRIVATE || kind == STORAGE_OUTPUT);
    }

    /** Adds each variable {@code function} judges that a whole store of it dominates every other use. */
    private void judge(Function function, Set<Integer> written) {
        if (function.blocks().isEmpty()) return;
        boolean entry = soleEntry(function);
        Map<Integer, List<Use>> uses = new HashMap<>();
        for (int b = 0; b < function.blocks().size(); b++) {
            List<Integer> block = function.blocks().get(b);
            for (int p = 1; p < block.size(); p++) recordUses(block.get(p), b, p, entry, uses);
        }
        int[] dominators = dominators(function.blocks());
        uses.forEach((variable, list) -> {
            if (storedFirst(list, dominators)) written.add(variable);
        });
    }

    /** Records the variables the instruction at {@code at} names, and in the entry point the reads it implies. */
    private void recordUses(int at, int block, int position, boolean entry, Map<Integer, List<Use>> uses) {
        int opcode = words[at] & 0xFFFF;
        if (opcode == OP_VARIABLE) return;
        if (entry) impliedReads(opcode).forEach(variable -> use(uses, variable, block, position, false));
        for (int i = at + 1; i < at + (words[at] >>> 16); i++) {
            if (judged(words[i], entry)) use(uses, words[i], block, position, opcode == OP_STORE && i == at + 1);
        }
    }

    /**
     * What an entry point's instruction reads unnamed: a call, globals other functions name; a
     * return or a geometry stage's emit, every output, which the emit sends as its vertex.
     */
    private Set<Integer> impliedReads(int opcode) {
        if (opcode == OP_FUNCTION_CALL) return namedElsewhere;
        return readsOutputs(opcode) ? outputs() : Set.of();
    }

    private static boolean readsOutputs(int opcode) {
        return opcode == OP_RETURN || opcode == OP_RETURN_VALUE
                || opcode == OP_EMIT_VERTEX || opcode == OP_EMIT_STREAM_VERTEX;
    }

    private Set<Integer> outputs() {
        Set<Integer> outputs = new HashSet<>();
        storage.forEach((variable, kind) -> {
            if (kind == STORAGE_OUTPUT) outputs.add(variable);
        });
        return outputs;
    }

    private static void use(Map<Integer, List<Use>> uses, int variable, int block, int position, boolean store) {
        uses.computeIfAbsent(variable, key -> new ArrayList<>()).add(new Use(block, position, store));
    }

    private static boolean storedFirst(List<Use> uses, int[] dominators) {
        for (Use store : uses) {
            if (!store.wholeStore()) continue;
            boolean first = true;
            for (Use use : uses) {
                if (use != store && !before(store, use, dominators)) {
                    first = false;
                    break;
                }
            }
            if (first) return true;
        }
        return false;
    }

    private static boolean before(Use store, Use use, int[] dominators) {
        if (store.block() == use.block()) return store.position() < use.position();
        for (int block = use.block(); block >= 0; block = dominators[block]) {
            if (block == store.block()) return true;
            if (block == 0) return false;
        }
        return false;
    }

    /**
     * Each block's immediate dominator, by index; -1 where the first block cannot reach it
     * (Cooper, Harvey and Kennedy's iteration over reverse postorder).
     */
    private int[] dominators(List<List<Integer>> blocks) {
        List<int[]> successors = successors(blocks);
        List<List<Integer>> predecessors = new ArrayList<>();
        for (int b = 0; b < blocks.size(); b++) predecessors.add(new ArrayList<>());
        for (int b = 0; b < blocks.size(); b++) {
            for (int next : successors.get(b)) predecessors.get(next).add(b);
        }
        int[] order = reversePostorder(successors);
        int[] rank = new int[blocks.size()];
        for (int i = 0; i < order.length; i++) rank[order[i]] = i;
        int[] dominators = new int[blocks.size()];
        Arrays.fill(dominators, -1);
        dominators[0] = 0;
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int i = 1; i < order.length; i++) {
                int dominator = commonDominator(predecessors.get(order[i]), dominators, rank);
                if (dominator != dominators[order[i]]) {
                    dominators[order[i]] = dominator;
                    changed = true;
                }
            }
        }
        return dominators;
    }

    private static int commonDominator(List<Integer> predecessors, int[] dominators, int[] rank) {
        int common = -1;
        for (int predecessor : predecessors) {
            if (dominators[predecessor] < 0) continue;
            int other = predecessor;
            while (common >= 0 && other != common) {
                while (rank[other] > rank[common]) other = dominators[other];
                while (rank[common] > rank[other]) common = dominators[common];
            }
            common = other;
        }
        return common;
    }

    /**
     * Each block's successors: the labels its last instruction names. A literal that happens to
     * equal a label only adds an edge, which can only weaken dominance.
     */
    private List<int[]> successors(List<List<Integer>> blocks) {
        Map<Integer, Integer> index = new HashMap<>();
        for (int b = 0; b < blocks.size(); b++) index.put(words[blocks.get(b).get(0) + 1], b);
        List<int[]> successors = new ArrayList<>();
        for (List<Integer> block : blocks) {
            int at = block.get(block.size() - 1);
            successors.add(Arrays.stream(words, at + 1, at + (words[at] >>> 16))
                    .filter(index::containsKey).map(index::get).distinct().toArray());
        }
        return successors;
    }

    /** The blocks the first one reaches, in reverse postorder. */
    private static int[] reversePostorder(List<int[]> successors) {
        boolean[] seen = new boolean[successors.size()];
        int[] next = new int[successors.size()];
        Deque<Integer> stack = new ArrayDeque<>();
        List<Integer> postorder = new ArrayList<>();
        stack.push(0);
        seen[0] = true;
        while (!stack.isEmpty()) {
            int block = stack.peek();
            int[] targets = successors.get(block);
            if (next[block] < targets.length) {
                int target = targets[next[block]++];
                if (!seen[target]) {
                    seen[target] = true;
                    stack.push(target);
                }
            } else {
                postorder.add(stack.pop());
            }
        }
        int[] order = new int[postorder.size()];
        for (int i = 0; i < order.length; i++) order[i] = postorder.get(order.length - 1 - i);
        return order;
    }
}
