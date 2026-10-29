package reforged;

import org.objectweb.asm.tree.ClassNode;

/** Finds a class by internal name, such as {@code net/vulkanmod/Initializer}; null when there is none. */
@FunctionalInterface
interface ClassLookup {
    ClassNode find(String internalName);
}
