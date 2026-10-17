package net.chimera.shaderpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every registered Chimera mixin against the bytecode it targets (Minecraft as NeoForge patches it,
 * and VulkanMod Reforged), as Mixin resolves it when the game loads: each target class exists, each
 * injector's target method exists, each INVOKE, FIELD and NEW injection point (slices included)
 * occurs in that method, and each shadow, accessor and invoker names a member of the target.
 * Members VulkanMod's own mixins merge into a target count as the target's.
 */
final class MixinTargetAuditTest {
    private static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;");
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    @Test
    void everyMixinResolvesAgainstItsTarget() throws IOException {
        Map<String, List<ClassNode>> merged = hostMixinsByTarget();
        List<String> problems = new ArrayList<>();
        List<String> mixins = registeredMixins("chimera.mixins.json");
        assertTrue(!mixins.isEmpty(), "no Chimera mixins registered");
        for (String mixinName : mixins) {
            ClassNode mixin = MixinBytecode.nativeClass(mixinName);
            List<String> targets = targetsOf(mixin);
            if (targets.isEmpty()) problems.add(mixinName + ": no target");
            for (String targetName : targets) {
                ClassNode target;
                try {
                    target = MixinBytecode.nativeClass(targetName);
                } catch (IOException missing) {
                    problems.add(mixinName + ": missing target class " + targetName);
                    continue;
                }
                new Audit(simpleName(mixinName), mixin, target, merged.getOrDefault(targetName, List.of()), problems)
                        .run();
            }
        }
        assertTrue(problems.isEmpty(), problems.size() + " unresolved mixin targets:\n" + String.join("\n", problems));
    }

    /** One mixin against one target class. */
    private record Audit(String mixinName, ClassNode mixin, ClassNode target, List<ClassNode> hostMixins,
                         List<String> problems) {
        void run() {
            for (MethodNode handler : mixin.methods) {
                for (AnnotationNode annotation : MixinBytecode.annotations(handler.visibleAnnotations,
                        handler.invisibleAnnotations)) {
                    if (INJECTORS.contains(annotation.desc)) injector(handler, annotation);
                    else if (annotation.desc.equals(ACCESSOR)) member(handler, annotation, true);
                    else if (annotation.desc.equals(INVOKER)) member(handler, annotation, false);
                    else if (annotation.desc.equals(SHADOW) && findMethods(handler.name + handler.desc).isEmpty()) {
                        problems.add(mixinName + ": shadow method " + handler.name + handler.desc
                                + " is not in " + target.name);
                    }
                }
            }
            for (FieldNode field : mixin.fields) {
                boolean shadow = MixinBytecode.annotations(field.visibleAnnotations, field.invisibleAnnotations)
                        .stream().anyMatch(annotation -> annotation.desc.equals(SHADOW));
                if (shadow && !hasField(field.name)) {
                    problems.add(mixinName + ": shadow field " + field.name + " is not in " + target.name);
                }
            }
        }

        private void injector(MethodNode handler, AnnotationNode annotation) {
            List<String> selectors = strings(MixinBytecode.annotationValue(annotation, "method"));
            if (selectors.isEmpty()) problems.add(mixinName + "." + handler.name + ": no target method");
            List<AnnotationNode> points = new ArrayList<>(annotations(MixinBytecode.annotationValue(annotation, "at")));
            for (AnnotationNode slice : annotations(MixinBytecode.annotationValue(annotation, "slice"))) {
                points.addAll(annotations(MixinBytecode.annotationValue(slice, "from")));
                points.addAll(annotations(MixinBytecode.annotationValue(slice, "to")));
            }
            for (String selector : selectors) {
                List<MethodNode> methods = findMethods(selector);
                if (methods.isEmpty()) {
                    problems.add(mixinName + "." + handler.name + ": method " + selector + " is not in " + target.name);
                    continue;
                }
                for (AnnotationNode point : points) {
                    String failure = pointFailure(point, methods);
                    if (failure != null) {
                        problems.add(mixinName + "." + handler.name + " in " + selector + ": " + failure);
                    }
                }
            }
        }

        /** Why an injection point is absent from every candidate method, or null when it is present. */
        private String pointFailure(AnnotationNode point, List<MethodNode> methods) {
            Object kind = MixinBytecode.annotationValue(point, "value");
            Object target = MixinBytecode.annotationValue(point, "target");
            if (!(target instanceof String reference) || reference.isEmpty()) return null;
            int ordinal = MixinBytecode.annotationValue(point, "ordinal") instanceof Integer value ? value : -1;
            for (MethodNode method : methods) {
                int found = 0;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (matches(String.valueOf(kind), reference, instruction)) found++;
                }
                if (found > Math.max(ordinal, 0)) return null;
            }
            return kind + " " + reference + (ordinal >= 0 ? " ordinal " + ordinal : "") + " not found";
        }

        private static boolean matches(String kind, String reference, AbstractInsnNode instruction) {
            return switch (kind) {
                case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> instruction instanceof MethodInsnNode call
                        && memberMatches(reference, call.owner, call.name + call.desc);
                case "FIELD" -> instruction instanceof FieldInsnNode field
                        && memberMatches(reference, field.owner, field.name + ":" + field.desc);
                case "NEW" -> instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                        && (reference.equals(type.desc) || reference.equals("L" + type.desc + ";"));
                default -> true;
            };
        }

        /** A Mixin member reference: {@code Lowner;name(desc)} or {@code name(desc)}, fields with {@code :desc}. */
        private static boolean memberMatches(String reference, String owner, String member) {
            if (reference.startsWith("L")) return reference.equals("L" + owner + ";" + member);
            return reference.equals(member);
        }

        private void member(MethodNode handler, AnnotationNode annotation, boolean field) {
            Object value = MixinBytecode.annotationValue(annotation, "value");
            String name = value instanceof String explicit && !explicit.isEmpty()
                    ? explicit : inferredName(handler.name);
            boolean present = field ? hasField(name) : !findMethods(name).isEmpty();
            if (!present) {
                problems.add(mixinName + "." + handler.name + ": " + (field ? "field " : "method ") + name
                        + " is not in " + target.name);
            }
        }

        private static String inferredName(String handler) {
            for (String prefix : List.of("get", "set", "is", "call", "invoke")) {
                if (handler.startsWith(prefix) && handler.length() > prefix.length()) {
                    String rest = handler.substring(prefix.length());
                    return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
                }
            }
            return handler;
        }

        private boolean hasField(String name) {
            if (target.fields.stream().anyMatch(field -> field.name.equals(name))) return true;
            return hostMixins.stream()
                    .anyMatch(host -> host.fields.stream().anyMatch(field -> field.name.equals(name)));
        }

        /** The target's methods a selector names ({@code name}, {@code name(desc)} or {@code Lowner;name(desc)}). */
        private List<MethodNode> findMethods(String selector) {
            String reference = selector.startsWith("L") && selector.indexOf(';') > 0
                    ? selector.substring(selector.indexOf(';') + 1) : selector;
            int descStart = reference.indexOf('(');
            String name = descStart < 0 ? reference : reference.substring(0, descStart);
            String desc = descStart < 0 ? null : reference.substring(descStart);
            List<MethodNode> found = new ArrayList<>();
            for (MethodNode method : target.methods) {
                if (method.name.equals(name) && (desc == null || method.desc.equals(desc))) found.add(method);
            }
            for (ClassNode host : hostMixins) {
                for (MethodNode method : host.methods) {
                    if (method.name.equals(name) && (desc == null || method.desc.equals(desc))) found.add(method);
                }
            }
            return found;
        }
    }

    /** VulkanMod's mixins by the class they merge into: members they add are the target's at load. */
    private static Map<String, List<ClassNode>> hostMixinsByTarget() throws IOException {
        Map<String, List<ClassNode>> byTarget = new HashMap<>();
        for (String name : registeredMixins("vulkanmod.mixins.json")) {
            ClassNode host;
            try {
                host = MixinBytecode.nativeClass(name);
            } catch (IOException missing) {
                continue;
            }
            for (String target : targetsOf(host)) byTarget.computeIfAbsent(target, key -> new ArrayList<>()).add(host);
        }
        return byTarget;
    }

    /** The internal names of a mixin config's mixins, common and client. */
    private static List<String> registeredMixins(String config) throws IOException {
        JsonObject json = JsonParser.parseString(MixinBytecode.resourceText(config)).getAsJsonObject();
        String prefix = json.get("package").getAsString() + ".";
        List<String> names = new ArrayList<>();
        for (String side : List.of("mixins", "client")) {
            if (!json.has(side)) continue;
            json.getAsJsonArray(side).forEach(entry -> names.add((prefix + entry.getAsString()).replace('.', '/')));
        }
        return names;
    }

    private static List<String> targetsOf(ClassNode mixin) {
        List<String> targets = new ArrayList<>();
        for (AnnotationNode annotation
                : MixinBytecode.annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            if (MixinBytecode.annotationValue(annotation, "value") instanceof List<?> types) {
                types.forEach(type -> targets.add(((Type) type).getInternalName()));
            }
            strings(MixinBytecode.annotationValue(annotation, "targets"))
                    .forEach(name -> targets.add(name.replace('.', '/')));
        }
        return targets;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static List<AnnotationNode> annotations(Object value) {
        if (value instanceof AnnotationNode node) return List.of(node);
        if (value instanceof List<?> list) {
            return list.stream().filter(AnnotationNode.class::isInstance).map(AnnotationNode.class::cast).toList();
        }
        return List.of();
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
