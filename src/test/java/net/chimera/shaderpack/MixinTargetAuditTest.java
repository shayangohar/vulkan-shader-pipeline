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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every registered Chimera mixin against the bytecode it targets (Minecraft as NeoForge patches it,
 * and VulkanMod Reforged), as Mixin resolves it when the game loads: each target class exists, each
 * injector's target method exists, each INVOKE, FIELD and NEW injection point (slices included)
 * occurs in that method, each @Inject and @WrapMethod handler's descriptor fits its target, and each
 * shadow, accessor and invoker names a member of the target with a matching descriptor.
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
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
    private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String WRAP_WITH_CONDITION = "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;";
    private static final String MODIFY_EXPRESSION_VALUE = "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
    private static final String WRAP_METHOD = "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";
    private static final String CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String CALLBACK_INFO_RETURNABLE =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
    private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
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
                if (!handlerFits(annotation.desc, handler, methods)) {
                    problems.add(mixinName + "." + handler.name + ": handler " + handler.desc + " fits no "
                            + selector + " " + methods.stream().map(method -> method.desc).toList());
                }
                List<AnnotationNode> at = annotations(MixinBytecode.annotationValue(annotation, "at"));
                Site site = at.isEmpty() ? null : site(at.get(0), methods);
                if (site != null && !siteFits(annotation, handler, site)) {
                    problems.add(mixinName + "." + handler.name + ": handler " + handler.desc
                            + " does not fit its call or field (" + Arrays.toString(site.values()) + " -> "
                            + site.result() + ")");
                }
            }
        }

        /**
         * Whether a call or field injector's handler fits the instruction it targets: @Redirect and
         * @WrapOperation take the receiver and arguments (a WrapOperation then its Operation) and
         * return the result, @WrapWithCondition takes them and returns boolean,
         * @ModifyExpressionValue takes and returns the result, and @ModifyArg returns an argument's
         * type.
         */
        private static boolean siteFits(AnnotationNode annotation, MethodNode handler, Site site) {
            Type[] params = Type.getArgumentTypes(handler.desc);
            Type result = Type.getReturnType(handler.desc);
            Type[] values = site.values();
            return switch (annotation.desc) {
                case REDIRECT -> startsWith(params, values) && result.equals(site.result());
                case WRAP_OPERATION -> params.length > values.length && startsWith(params, values)
                        && params[values.length].getDescriptor().equals(OPERATION) && result.equals(site.result());
                case WRAP_WITH_CONDITION -> startsWith(params, values) && result == Type.BOOLEAN_TYPE;
                case MODIFY_EXPRESSION_VALUE -> params.length > 0 && params[0].equals(site.result())
                        && result.equals(site.result());
                case MODIFY_ARG -> modifyArgFits(annotation, params, result, site.arguments());
                default -> true;
            };
        }

        private static boolean modifyArgFits(AnnotationNode annotation, Type[] params, Type result, Type[] arguments) {
            boolean typed = MixinBytecode.annotationValue(annotation, "index") instanceof Integer index
                    ? index < arguments.length && arguments[index].equals(result)
                    : Arrays.asList(arguments).contains(result);
            return typed && params.length > 0 && (params[0].equals(result) || startsWith(params, arguments));
        }

        /** The call or field an injection point names, from the first instruction it matches. */
        private static Site site(AnnotationNode point, List<MethodNode> methods) {
            String kind = String.valueOf(MixinBytecode.annotationValue(point, "value"));
            if (!(MixinBytecode.annotationValue(point, "target") instanceof String reference)) return null;
            for (MethodNode method : methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    Site site = matches(kind, reference, instruction) ? Site.of(instruction) : null;
                    if (site != null) return site;
                }
            }
            return null;
        }

        /** What a call or field access hands a handler: its receiver (null when static), arguments and result. */
        private record Site(Type receiver, Type[] arguments, Type result) {
            static Site of(AbstractInsnNode instruction) {
                if (instruction instanceof MethodInsnNode call) {
                    return new Site(call.getOpcode() == Opcodes.INVOKESTATIC ? null : Type.getObjectType(call.owner),
                            Type.getArgumentTypes(call.desc), Type.getReturnType(call.desc));
                }
                if (instruction instanceof FieldInsnNode field) {
                    int opcode = field.getOpcode();
                    boolean isStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
                    boolean read = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;
                    Type type = Type.getType(field.desc);
                    return new Site(isStatic ? null : Type.getObjectType(field.owner),
                            read ? new Type[0] : new Type[] {type}, read ? type : Type.VOID_TYPE);
                }
                return null;
            }

            Type[] values() {
                if (receiver == null) return arguments;
                Type[] values = new Type[arguments.length + 1];
                values[0] = receiver;
                System.arraycopy(arguments, 0, values, 1, arguments.length);
                return values;
            }
        }

        /**
         * Whether an @Inject or @WrapMethod handler's descriptor fits one candidate, as Mixin checks it
         * when it applies the mixin: an @Inject takes the target's arguments (or none), then its
         * CallbackInfo, then any captured locals; a @WrapMethod takes the arguments and an Operation and
         * returns the target's type. Other injectors are not checked here.
         */
        private static boolean handlerFits(String injector, MethodNode handler, List<MethodNode> methods) {
            boolean inject = injector.equals(INJECT);
            if (!inject && !injector.equals(WRAP_METHOD)) return true;
            for (MethodNode method : methods) {
                if (isStatic(handler) == isStatic(method)
                        && (inject ? injectFits(handler.desc, method.desc) : wrapFits(handler.desc, method.desc))) {
                    return true;
                }
            }
            return false;
        }

        private static boolean injectFits(String handler, String target) {
            Type[] params = Type.getArgumentTypes(handler);
            Type[] arguments = Type.getArgumentTypes(target);
            String callback = Type.getReturnType(target) == Type.VOID_TYPE ? CALLBACK_INFO : CALLBACK_INFO_RETURNABLE;
            int index = params.length > arguments.length && startsWith(params, arguments) ? arguments.length : 0;
            return Type.getReturnType(handler) == Type.VOID_TYPE && params.length > index
                    && params[index].getDescriptor().equals(callback);
        }

        private static boolean wrapFits(String handler, String target) {
            Type[] params = Type.getArgumentTypes(handler);
            Type[] arguments = Type.getArgumentTypes(target);
            return params.length == arguments.length + 1 && startsWith(params, arguments)
                    && params[arguments.length].getDescriptor().equals(OPERATION)
                    && Type.getReturnType(handler).equals(Type.getReturnType(target));
        }

        private static boolean startsWith(Type[] params, Type[] prefix) {
            if (params.length < prefix.length) return false;
            for (int index = 0; index < prefix.length; index++) {
                if (!params[index].equals(prefix[index])) return false;
            }
            return true;
        }

        private static boolean isStatic(MethodNode method) {
            return (method.access & Opcodes.ACC_STATIC) != 0;
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

        /**
         * An accessor or invoker resolves by name and descriptor: a getter returns the field's type, a
         * setter takes it, and an invoker has its method's descriptor.
         */
        private void member(MethodNode handler, AnnotationNode annotation, boolean field) {
            Object value = MixinBytecode.annotationValue(annotation, "value");
            String name = value instanceof String explicit && !explicit.isEmpty()
                    ? explicit : inferredName(handler.name);
            boolean present = field ? fieldDescriptors(name).anyMatch(desc -> handler.desc.equals("()" + desc)
                    || handler.desc.equals("(" + desc + ")V"))
                    : findMethods(name).stream().anyMatch(method -> method.desc.equals(handler.desc));
            if (!present) {
                problems.add(mixinName + "." + handler.name + ": " + (field ? "field " : "method ") + name
                        + " matching " + handler.desc + " is not in " + target.name);
            }
        }

        private Stream<String> fieldDescriptors(String name) {
            return Stream.concat(target.fields.stream(),
                            hostMixins.stream().flatMap(host -> host.fields.stream()))
                    .filter(field -> field.name.equals(name)).map(field -> field.desc);
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
