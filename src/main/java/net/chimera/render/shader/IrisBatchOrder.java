package net.chimera.render.shader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order Iris draws a frame's buffered batches in (batchedentityrendering's
 * GraphTranslucencyRenderOrderManager behind FullyBufferedMultiBufferSource). Batches draw by
 * transparency kind. Within a kind every buffered type is a vertex, added at its first use, and
 * consecutive uses of the kind inside one group (one entity's render, or one banner's patterns) add
 * an edge from the earlier type to the later. A kind draws in reverse depth-first finishing order
 * over its vertices taken in first-use order: an edge's source draws before its target, and types
 * no edge relates draw in reverse first-use order, so block entities, written after the entities,
 * draw before them.
 *
 * <p>Iris first removes a minimum-weight feedback arc set from a cyclic kind; here the search
 * breaks a cycle where it meets it.</p>
 */
final class IrisBatchOrder<T> {
    private final EnumMap<IrisTransparency, Map<T, Set<T>>> graphs = new EnumMap<>(IrisTransparency.class);
    private final EnumMap<IrisTransparency, T> previous = new EnumMap<>(IrisTransparency.class);
    private boolean inGroup;

    /** Records a use of {@code type}, a batch of {@code kind}. */
    void use(T type, IrisTransparency kind) {
        Map<T, Set<T>> graph = graphs.computeIfAbsent(kind, unused -> new LinkedHashMap<>());
        graph.computeIfAbsent(type, unused -> new LinkedHashSet<>());
        if (!inGroup) return;
        T before = previous.put(kind, type);
        if (before != null && !before.equals(type)) graph.get(before).add(type);
    }

    /** Opens a group unless one is open; true when this call opened it, and must close it. */
    boolean startGroup() {
        if (inGroup) return false;
        previous.clear();
        inGroup = true;
        return true;
    }

    void endGroup() {
        previous.clear();
        inGroup = false;
    }

    /** Forgets the frame's uses, at the head of the next level. */
    void reset() {
        graphs.clear();
        endGroup();
    }

    /** Each recorded type's place in the draw order: by kind, then by the kind's graph. */
    Map<T, Integer> ranks() {
        Map<T, Integer> ranks = new HashMap<>();
        for (IrisTransparency kind : IrisTransparency.values()) {
            Map<T, Set<T>> graph = graphs.get(kind);
            if (graph == null) continue;
            List<T> finished = new ArrayList<>();
            Set<T> discovered = new HashSet<>();
            for (T vertex : graph.keySet()) visit(graph, vertex, discovered, finished);
            for (int index = finished.size() - 1; index >= 0; index--) ranks.put(finished.get(index), ranks.size());
        }
        return ranks;
    }

    private static <T> void visit(Map<T, Set<T>> graph, T vertex, Set<T> discovered, List<T> finished) {
        if (!discovered.add(vertex)) return;
        for (T next : graph.get(vertex)) visit(graph, next, discovered, finished);
        finished.add(vertex);
    }
}
