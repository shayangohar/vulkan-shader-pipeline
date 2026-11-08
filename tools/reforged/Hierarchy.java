package reforged;

import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A type and the supertypes a class tree holds, breadth first, and the last supertype the tree lacks. */
record Hierarchy(List<ClassNode> classes, String lastMissing) {
    static Hierarchy of(ClassLookup lookup, String type) {
        List<ClassNode> classes = new ArrayList<>();
        String lastMissing = null;
        Deque<String> queue = new ArrayDeque<>(List.of(type));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!seen.add(name)) continue;
            ClassNode node = lookup.find(name);
            if (node == null) {
                lastMissing = name;
                continue;
            }
            classes.add(node);
            if (node.superName != null) queue.add(node.superName);
            queue.addAll(node.interfaces);
        }
        return new Hierarchy(classes, lastMissing);
    }
}
