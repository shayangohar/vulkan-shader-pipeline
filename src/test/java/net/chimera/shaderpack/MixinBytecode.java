package net.chimera.shaderpack;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reads compiled classes and their mixin annotations, to pin the hooks a feature relies on. */
final class MixinBytecode {
    private MixinBytecode() {}

    static ClassNode nativeClass(String name) throws IOException {
        try (var input = MixinBytecode.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new IOException("missing native class " + name);
            var result = new ClassNode();
            new ClassReader(input).accept(result, 0);
            return result;
        }
    }

    /**
     * The instructions of {@code method} in bytecode order, with each call to another method of the
     * same class followed by that method's own instructions. A check on what a method calls then
     * still holds when the method is split into helpers of its class.
     */
    static List<AbstractInsnNode> instructionsThrough(ClassNode owner, String method) {
        var root = owner.methods.stream().filter(candidate -> candidate.name.equals(method)).findFirst()
                .orElseThrow(() -> new AssertionError(owner.name + "." + method + " is missing"));
        List<AbstractInsnNode> instructions = new ArrayList<>();
        appendThrough(owner, root, new HashSet<>(), instructions);
        return instructions;
    }

    private static void appendThrough(ClassNode owner, MethodNode method, Set<MethodNode> path,
                                      List<AbstractInsnNode> instructions) {
        if (!path.add(method)) return;
        for (var instruction : method.instructions) {
            instructions.add(instruction);
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner.name)) {
                owner.methods.stream()
                        .filter(candidate -> candidate.name.equals(call.name) && candidate.desc.equals(call.desc))
                        .findFirst().ifPresent(callee -> appendThrough(owner, callee, path, instructions));
            }
        }
        path.remove(method);
    }

    static boolean mixinTargets(ClassNode mixin, String target) {
        for (var annotation : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")
                    && annotationValue(annotation, "value") instanceof List<?> list && list.stream().anyMatch(type ->
                    ((Type) type).getInternalName().equals(target))) {
                return true;
            }
        }
        return false;
    }

    /** True when an injector of {@code desc} names {@code method} and, if given, an @At {@code target}. */
    static boolean injects(ClassNode mixin, String desc, String method, String target) {
        for (var handler : mixin.methods) {
            for (var annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                if (!annotation.desc.equals(desc)
                        || !(annotationValue(annotation, "method") instanceof List<?> names
                                && names.contains(method))) {
                    continue;
                }
                Object at = annotationValue(annotation, "at");
                List<?> ats = at instanceof List<?> list ? list : at == null ? List.of() : List.of(at);
                if (target == null || ats.stream().anyMatch(value ->
                        value instanceof AnnotationNode node && target.equals(annotationValue(node, "target")))) {
                    return true;
                }
            }
        }
        return false;
    }

    static String resourceText(String path) throws IOException {
        try (var input = MixinBytecode.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The mixin targets {@code target} and cancels {@code method} at its head. */
    static void assertCancellingHeadInject(String mixinName, String target, String method, boolean isStatic)
            throws IOException {
        var mixin = nativeClass(mixinName);
        boolean targets = false;
        for (var annotation : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            targets = annotationValue(annotation, "value") instanceof List<?> list && list.stream().anyMatch(type ->
                    ((Type) type).getInternalName().equals(target));
        }
        boolean cancels = false;
        for (var handler : mixin.methods) {
            for (var annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                boolean head = annotationValue(annotation, "at") instanceof List<?> ats && ats.stream().anyMatch(at ->
                        at instanceof AnnotationNode node && "HEAD".equals(annotationValue(node, "value")));
                cancels |= annotationValue(annotation, "method") instanceof List<?> names && names.contains(method)
                        && Boolean.TRUE.equals(annotationValue(annotation, "cancellable")) && head
                        && ((handler.access & Opcodes.ACC_STATIC) != 0) == isStatic;
            }
        }
        assertTrue(targets && cancels, mixinName + " cancels " + target + "." + method + " at its head");
    }

    static void assertMixinRegistered(String mixinName) throws IOException {
        try (var config = MixinBytecode.class.getClassLoader().getResourceAsStream("chimera.mixins.json")) {
            assertTrue(config != null && new String(config.readAllBytes(), StandardCharsets.UTF_8)
                    .contains("\"" + mixinName + "\""), mixinName + " is registered");
        }
    }

    static List<AnnotationNode> annotations(
            List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(key)) return annotation.values.get(index + 1);
        }
        return null;
    }
}
