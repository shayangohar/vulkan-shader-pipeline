package reforged;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static reforged.MixinCheck.INJECTORS;
import static reforged.MixinCheck.VULKANMOD;
import static reforged.MixinCheck.annotationName;
import static reforged.MixinCheck.annotations;
import static reforged.MixinCheck.describeAt;
import static reforged.MixinCheck.intValue;
import static reforged.MixinCheck.isStatic;
import static reforged.MixinCheck.mixinTargets;
import static reforged.MixinCheck.parameterAnnotations;
import static reforged.MixinCheck.stringValue;
import static reforged.MixinCheck.value;
import static reforged.MixinCheck.values;

/**
 * The inject mode: checks Chimera mixins against one Reforged class tree. For each mixin it checks the target
 * classes; the {@code @Shadow}, {@code @Overwrite}, {@code @Accessor} and {@code @Invoker} members; and each
 * injector's selectors, {@code @At} points, slices, {@code require} and {@code allow} counts, handler signature and
 * {@code @Local} captures. It prints a FAIL line per problem, and an ok line per pass when verbose.
 */
final class InjectCheck {
    static final String CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    static final String LOCAL_REF = "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;";
    static final String SHADOW_PREFIX = "shadow$";
    /** Handler parameter annotations that Mixin fills itself: such a parameter is not a target argument. */
    static final Set<String> SUGAR = Set.of("Local", "Share", "Cancellable");
    /** The local type each MixinExtras primitive {@code Local*Ref} captures. */
    static final Map<String, String> LOCAL_REF_TYPES = Map.of(
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalBooleanRef;", "Z",
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalByteRef;", "B",
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalCharRef;", "C",
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalDoubleRef;", "D",
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalFloatRef;", "F",
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalIntRef;", "I",
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalLongRef;", "J",
            "Lcom/llamalad7/mixinextras/sugar/ref/LocalShortRef;", "S");
    /** An accessor or invoker name: a verb, then the member it reaches, as in {@code getFoo} or {@code callBar}. */
    static final Pattern ACCESSOR_NAME =
            Pattern.compile("^(get|is|set|call|invoke|new|create)(([A-Z])(.*?))(_\\$md.*)?$");
    /** What {@link #constantOf} returns for an instruction that pushes no constant. */
    static final Object NO_CONSTANT = new Object();

    private final ClassLookup reforged;
    private final boolean verbose;
    final Tally tally = new Tally();

    InjectCheck(ClassLookup reforged, boolean verbose) {
        this.reforged = reforged;
        this.verbose = verbose;
    }

    /** Counts of what the inject mode checked and of what failed. */
    static final class Tally {
        int injectorsChecked;
        int injectorsFailing;
        int membersChecked;
        int membersFailing;

        String summary() {
            return "SUMMARY\tinjectors checked=" + injectorsChecked + " failing=" + injectorsFailing
                    + "; members (shadow/accessor/invoker/overwrite) checked=" + membersChecked
                    + " failing=" + membersFailing;
        }
    }

    // ---------------------------------------------------------------- members and injectors

    /** Checks one mixin's members and injectors in each Reforged class it targets. */
    void checkMixin(ClassNode mixin, String mixinName) {
        List<String> targets = mixinTargets(mixin).stream().filter(target -> target.startsWith(VULKANMOD))
                .toList();
        for (String target : targets) {
            if (reforged.find(target) == null) {
                System.out.println("FAIL\t" + mixinName + "\t<class>\ttarget class missing: " + target);
            }
        }
        for (FieldNode field : mixin.fields) {
            for (AnnotationNode annotation : annotations(field.visibleAnnotations, field.invisibleAnnotations)) {
                if (!annotationName(annotation).equals("Shadow")) continue;
                for (String target : targets) checkShadowField(mixinName, field, annotation, target);
            }
        }
        for (MethodNode handler : mixin.methods) {
            for (AnnotationNode annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                for (String target : targets) checkMethodAnnotation(mixinName, handler, annotation, target);
            }
        }
    }

    private void checkShadowField(String mixinName, FieldNode shadow, AnnotationNode annotation, String target) {
        tally.membersChecked++;
        reportMember(mixinName, "@Shadow field " + shadow.name + " " + shadow.desc, target,
                shadowFieldProblem(reforged.find(target), shadow, shadowNames(annotation, shadow.name)));
    }

    private void checkMethodAnnotation(String mixinName, MethodNode handler, AnnotationNode annotation,
                                       String target) {
        String kind = annotationName(annotation);
        switch (kind) {
            case "Shadow", "Overwrite" -> {
                tally.membersChecked++;
                reportMember(mixinName, "@" + kind + " method " + handler.name + handler.desc, target,
                        findMethodInHierarchy(reforged, target, shadowNames(annotation, handler.name),
                                handler.desc, handler.access));
            }
            case "Accessor", "Invoker" -> {
                tally.membersChecked++;
                ClassNode targetClass = reforged.find(target);
                String problem = kind.equals("Accessor") ? accessorProblem(targetClass, handler, annotation)
                        : invokerProblem(targetClass, handler, annotation);
                String named = value(annotation, "value") == null ? ""
                        : " (\"" + stringValue(annotation, "value") + "\")";
                reportMember(mixinName, "@" + kind + " " + handler.name + handler.desc + named, target, problem);
            }
            default -> {
                if (INJECTORS.contains(kind)) checkInjector(target, mixinName, handler, annotation, kind);
            }
        }
    }

    private void reportMember(String mixinName, String member, String target, String problem) {
        if (problem != null) {
            tally.membersFailing++;
            System.out.println("FAIL\t" + mixinName + "\t" + member + "\t" + target + "\t" + problem);
        } else if (verbose) {
            System.out.println("ok\t" + mixinName + "\t" + member + "\t" + target);
        }
    }

    /**
     * Checks one injector in one target class: its selectors match methods, its {@code @At}s and slices find
     * instructions, the count meets {@code require} and {@code allow}, and the handler fits each target.
     */
    void checkInjector(String target, String mixinName, MethodNode handler, AnnotationNode injector, String kind) {
        tally.injectorsChecked++;
        String handlerName = "@" + kind + " " + handler.name;
        ClassNode targetClass = reforged.find(target);
        if (targetClass == null) {
            tally.injectorsFailing++;
            System.out.println("FAIL\t" + mixinName + "\t" + handlerName + "\t" + target + "\ttarget class missing");
            return;
        }
        List<String> problems = new ArrayList<>();
        List<String> info = new ArrayList<>();
        List<Object> selectors = values(injector, "method");
        List<AnnotationNode> ats = values(injector, "at").stream().map(AnnotationNode.class::cast).toList();
        Map<String, AnnotationNode> slices = new HashMap<>();
        for (Object slice : values(injector, "slice")) {
            slices.put(stringValue((AnnotationNode) slice, "id", ""), (AnnotationNode) slice);
        }
        int total = 0;
        StringJoiner counts = new StringJoiner(" ");
        for (MethodNode method : targetMethods(targetClass, target, selectors, problems, info)) {
            if (isStatic(method.access) && !isStatic(handler.access)) {
                problems.add("non-static handler on static target " + method.name);
            }
            List<AbstractInsnNode> points = injectionPoints(kind, method, ats, slices, problems);
            counts.add(method.name + method.desc + "=" + points.size());
            total += points.size();
            if (!points.isEmpty()) problems.addAll(checkHandler(kind, handler, method, points));
        }
        int require = intValue(injector, "require", 1);
        int allow = intValue(injector, "allow", -1);
        if (total < require) problems.add("injection count " + total + " < require " + require);
        if (allow > 0 && total > allow) problems.add("injection count " + total + " > allow " + allow);
        String injectorText = handlerName + " " + selectors + " " + ats.stream().map(MixinCheck::describeAt).toList();
        reportInjector(mixinName + "\t" + injectorText + "\t" + target, problems, "[" + counts + "]", info);
    }

    private void reportInjector(String subject, List<String> problems, String counts, List<String> info) {
        String infoText = info.isEmpty() ? "" : "\t" + info;
        if (!problems.isEmpty()) {
            tally.injectorsFailing++;
            System.out.println("FAIL\t" + subject + "\t" + String.join(" | ", problems) + "\t" + counts + infoText);
        } else if (verbose) {
            System.out.println("ok\t" + subject + "\t" + counts + infoText);
        } else if (!info.isEmpty()) {
            System.out.println("INFO\t" + subject + "\t" + info);
        }
    }

    /** The names a {@code @Shadow} or {@code @Overwrite} member may have in its target: unprefixed, or an alias. */
    static List<String> shadowNames(AnnotationNode annotation, String memberName) {
        String prefix = stringValue(annotation, "prefix", SHADOW_PREFIX);
        List<String> names = new ArrayList<>();
        names.add(memberName.startsWith(prefix) ? memberName.substring(prefix.length()) : memberName);
        for (Object alias : values(annotation, "aliases")) names.add(alias.toString());
        return names;
    }

    /** Why a {@code @Shadow} field has no match in {@code target}, or null when it has one. */
    static String shadowFieldProblem(ClassNode target, FieldNode shadow, List<String> names) {
        if (target == null) return "missing";
        for (FieldNode field : target.fields) {
            if (!names.contains(field.name)) continue;
            if (!field.desc.equals(shadow.desc)) return "type changed: " + field.desc;
            if (isStatic(field.access) != isStatic(shadow.access)) return "static mismatch";
            return null;
        }
        return "missing";
    }

    /**
     * Why a shadowed or overwritten method is missing from {@code target} and its Reforged superclasses, or null
     * when one of them declares it with the handler's static-ness.
     */
    static String findMethodInHierarchy(ClassLookup classes, String target, List<String> names, String descriptor,
                                        int handlerAccess) {
        String current = target;
        boolean inherited = false;
        while (current != null) {
            ClassNode type = classes.find(current);
            if (type == null) return inherited ? "not found up to external super " + current : "target class missing";
            for (MethodNode method : type.methods) {
                if (!names.contains(method.name) || !method.desc.equals(descriptor)) continue;
                return isStatic(method.access) != isStatic(handlerAccess) ? "static mismatch" : null;
            }
            current = type.superName;
            inherited = true;
            if (current != null && !current.startsWith(VULKANMOD)) break;
        }
        ClassNode type = classes.find(target);
        List<String> sameName = new ArrayList<>();
        if (type != null) {
            for (MethodNode method : type.methods) {
                if (names.contains(method.name)) sameName.add(method.name + method.desc);
            }
        }
        return "missing" + (sameName.isEmpty() ? "" : "; same-name methods: " + sameName);
    }

    /** The member an accessor or invoker names: {@code getFoo} gives {@code Foo}; {@code newFoo}, a constructor. */
    static String conventionalName(String methodName, boolean invoker) {
        Matcher matcher = ACCESSOR_NAME.matcher(methodName);
        if (!matcher.matches()) return methodName;
        String verb = matcher.group(1);
        if (invoker && (verb.equals("new") || verb.equals("create"))) return "<init>";
        return matcher.group(2);
    }

    static String lowerFirst(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** An annotation's non-empty {@code value}, or null. */
    private static String explicitName(AnnotationNode annotation) {
        String name = stringValue(annotation, "value");
        return name == null || name.isEmpty() ? null : name;
    }

    /** Why an {@code @Accessor}'s field is missing or different in {@code target}, or null when it matches. */
    static String accessorProblem(ClassNode target, MethodNode accessor, AnnotationNode annotation) {
        if (target == null) return "target class missing";
        Type[] arguments = Type.getArgumentTypes(accessor.desc);
        String fieldDescriptor = arguments.length == 0 ? Type.getReturnType(accessor.desc).getDescriptor()
                : arguments[arguments.length - 1].getDescriptor();
        List<String> names = new ArrayList<>();
        if (explicitName(annotation) != null) {
            names.add(explicitName(annotation));
        } else {
            String member = conventionalName(accessor.name, false);
            names.addAll(List.of(lowerFirst(member), member, member.toUpperCase(Locale.ROOT)));
        }
        for (FieldNode field : target.fields) {
            if (!names.contains(field.name)) continue;
            if (!field.desc.equals(fieldDescriptor)) {
                return "field " + field.name + " type " + field.desc + " != " + fieldDescriptor;
            }
            return isStatic(accessor.access) != isStatic(field.access) ? "static mismatch" : null;
        }
        return "field " + names.get(0) + " missing";
    }

    /** Why an {@code @Invoker}'s method is missing from {@code target}, or null when it is there. */
    static String invokerProblem(ClassNode target, MethodNode invoker, AnnotationNode annotation) {
        if (target == null) return "target class missing";
        String name = explicitName(annotation);
        if (name == null) {
            String member = conventionalName(invoker.name, true);
            name = member.equals("<init>") ? member : lowerFirst(member);
        }
        String descriptor = name.equals("<init>")
                ? invoker.desc.substring(0, invoker.desc.indexOf(')') + 1) + "V" : invoker.desc;
        if (name.contains("(")) {
            descriptor = name.substring(name.indexOf('('));
            name = name.substring(0, name.indexOf('('));
        }
        List<String> sameName = new ArrayList<>();
        for (MethodNode method : target.methods) {
            if (!method.name.equals(name)) continue;
            if (method.desc.equals(descriptor)) return null;
            sameName.add(method.desc);
        }
        return "method " + name + descriptor + " missing" + (sameName.isEmpty() ? "" : "; same-name: " + sameName);
    }

    /** The target methods an injector's selectors match; a problem for a selector that matches none. */
    static Set<MethodNode> targetMethods(ClassNode targetClass, String target, List<Object> selectors,
                                         List<String> problems, List<String> info) {
        Set<MethodNode> methods = new LinkedHashSet<>();
        for (Object text : selectors) {
            Selector selector = Selector.parse(text.toString());
            List<MethodNode> matches = targetClass.methods.stream()
                    .filter(method -> selector.matches(target, method.name, method.desc)).toList();
            if (matches.isEmpty()) {
                List<String> sameName = targetClass.methods.stream()
                        .filter(method -> selector.name() != null && selector.nameMatches(method.name))
                        .map(method -> method.name + method.desc).toList();
                problems.add("selector '" + text + "' matches no method"
                        + (sameName.isEmpty() ? "" : " (same name: " + sameName + ")"));
                continue;
            }
            if (!selector.wildcard() && matches.size() > 1) {
                info.add("selector '" + text + "' ambiguous "
                        + matches.stream().map(method -> method.name + method.desc).toList() + " -> first used");
                matches = matches.subList(0, 1);
            }
            methods.addAll(matches);
        }
        return methods;
    }

    // ---------------------------------------------------------------- injection points

    /** A slice's first and last instruction index, inclusive. */
    record InstructionRange(int start, int end) {}

    /** The instructions an injector's {@code @At}s select in one target method; problems for what they miss. */
    static List<AbstractInsnNode> injectionPoints(String kind, MethodNode method, List<AnnotationNode> ats,
                                                  Map<String, AnnotationNode> slices, List<String> problems) {
        List<AbstractInsnNode> points = new ArrayList<>();
        if (kind.equals("WrapMethod")) {
            points.add(method.instructions.getFirst());
            return points;
        }
        for (AnnotationNode at : ats) {
            List<String> notes = new ArrayList<>();
            InstructionRange range = sliceRange(method, slices.get(stringValue(at, "slice", "")), problems, notes);
            List<AbstractInsnNode> found = findInjectionPoints(method, at, range.start(), range.end(), notes);
            problems.addAll(notes);
            if (found != null) points.addAll(found);
        }
        return points;
    }

    /** The instructions a slice allows: the whole method without one, or from its {@code from} to its {@code to}. */
    static InstructionRange sliceRange(MethodNode method, AnnotationNode slice, List<String> problems,
                                       List<String> notes) {
        int last = method.instructions.size() - 1;
        if (slice == null) return new InstructionRange(0, last);
        int start = 0;
        int end = last;
        AnnotationNode from = (AnnotationNode) value(slice, "from");
        AnnotationNode to = (AnnotationNode) value(slice, "to");
        if (from != null) {
            List<AbstractInsnNode> found = findInjectionPoints(method, from, 0, last, notes);
            if (found == null || found.isEmpty()) {
                problems.add("slice from " + describeAt(from) + " not found in " + method.name
                        + " (slice widens to method start)");
            } else {
                start = method.instructions.indexOf(found.get(0));
            }
        }
        if (to != null) {
            List<AbstractInsnNode> found = findInjectionPoints(method, to, start, last, notes);
            if (found == null || found.isEmpty()) {
                problems.add("slice to " + describeAt(to) + " not found in " + method.name + " (empty slice)");
                end = start;
            } else {
                end = method.instructions.indexOf(found.get(0));
            }
        }
        return new InstructionRange(start, end);
    }

    /**
     * The instructions an {@code @At} selects in {@code method} from {@code start} to {@code end} inclusive, after
     * its {@code :LAST} and {@code ordinal}; null, with a note, for an {@code @At} kind this does not model.
     */
    static List<AbstractInsnNode> findInjectionPoints(MethodNode method, AnnotationNode at, int start, int end,
                                                      List<String> notes) {
        String value = stringValue(at, "value");
        boolean last = value.endsWith(":LAST");
        value = withoutSuffix(withoutSuffix(withoutSuffix(value, ":LAST"), ":FIRST"), ":ONE");
        AbstractInsnNode[] instructions = method.instructions.toArray();
        if (instructions.length == 0) return new ArrayList<>();
        List<AbstractInsnNode> points = pointsOfKind(value, at, instructions, start, end);
        if (points == null) {
            notes.add("UNSUPPORTED @At " + value);
            return null;
        }
        if (last && !points.isEmpty()) points = List.of(points.get(points.size() - 1));
        int ordinal = intValue(at, "ordinal", -1);
        if (ordinal >= 0) points = points.size() > ordinal ? List.of(points.get(ordinal)) : List.of();
        return points;
    }

    private static String withoutSuffix(String text, String suffix) {
        return text.endsWith(suffix) ? text.substring(0, text.length() - suffix.length()) : text;
    }

    /** Every instruction in the range that an {@code @At} of this kind selects; null for an unmodelled kind. */
    private static List<AbstractInsnNode> pointsOfKind(String kind, AnnotationNode at,
                                                       AbstractInsnNode[] instructions, int start, int end) {
        String target = stringValue(at, "target");
        return switch (kind) {
            case "HEAD" -> List.of(instructions[start]);
            case "RETURN" -> matching(instructions, start, end, InjectCheck::isReturn);
            case "TAIL" -> {
                List<AbstractInsnNode> returns = matching(instructions, start, end, InjectCheck::isReturn);
                yield returns.isEmpty() ? returns : List.of(returns.get(returns.size() - 1));
            }
            case "INVOKE", "INVOKE_ASSIGN" -> {
                Selector selector = Selector.parse(target);
                yield matching(instructions, start, end, instruction -> instruction instanceof MethodInsnNode call
                        && selector.matches(call.owner, call.name, call.desc));
            }
            case "FIELD" -> {
                Selector selector = Selector.parse(target);
                int opcode = intValue(at, "opcode", -1);
                yield matching(instructions, start, end, instruction -> instruction instanceof FieldInsnNode access
                        && selector.matches(access.owner, access.name, access.desc)
                        && (opcode < 0 || opcode == access.getOpcode()));
            }
            case "NEW" -> {
                String constructed = constructedType(at, target);
                yield matching(instructions, start, end, instruction -> instruction instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW && type.desc.equals(constructed));
            }
            case "CONSTANT" -> {
                ConstantArgument wanted = ConstantArgument.parse(values(at, "args"));
                yield matching(instructions, start, end, instruction -> wanted.matches(constantOf(instruction)));
            }
            default -> null;
        };
    }

    /** The instructions from {@code start} to {@code end} inclusive that pass {@code test}. */
    private static List<AbstractInsnNode> matching(AbstractInsnNode[] instructions, int start, int end,
                                                   Predicate<AbstractInsnNode> test) {
        List<AbstractInsnNode> matches = new ArrayList<>();
        for (int index = start; index <= end; index++) {
            if (test.test(instructions[index])) matches.add(instructions[index]);
        }
        return matches;
    }

    private static boolean isReturn(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
    }

    /** The internal name a {@code NEW} {@code @At} constructs: its target, else its {@code class=} argument. */
    private static String constructedType(AnnotationNode at, String target) {
        String type = target;
        if (type == null) {
            for (Object argument : values(at, "args")) {
                if (argument.toString().startsWith("class=")) type = argument.toString().substring("class=".length());
            }
        }
        if (type.startsWith("(")) return Type.getReturnType(type).getInternalName();
        if (type.startsWith("L") && type.endsWith(";")) return type.substring(1, type.length() - 1);
        return type.replace('.', '/');
    }

    /** The constant a {@code CONSTANT} {@code @At} wants: the last {@code <kind>Value=} its {@code args} give. */
    record ConstantArgument(String kind, Object value) {
        static ConstantArgument parse(List<Object> arguments) {
            String kind = null;
            Object value = null;
            for (Object argument : arguments) {
                String text = argument.toString();
                int equals = text.indexOf('=');
                if (equals < 0) continue;
                String key = text.substring(0, equals);
                String literal = text.substring(equals + 1);
                Object parsed = switch (key) {
                    case "intValue" -> Integer.valueOf(literal);
                    case "floatValue" -> Float.valueOf(literal);
                    case "doubleValue" -> Double.valueOf(literal);
                    case "longValue" -> Long.valueOf(literal);
                    case "stringValue" -> literal;
                    default -> null;
                };
                if (parsed != null) {
                    kind = key;
                    value = parsed;
                } else if (key.equals("nullValue")) {
                    kind = key;
                }
            }
            return new ConstantArgument(kind, value);
        }

        boolean matches(Object constant) {
            if (constant == NO_CONSTANT) return false;
            return "nullValue".equals(kind) ? constant == null : constant != null && constant.equals(value);
        }
    }

    /** The constant an instruction pushes, or {@link #NO_CONSTANT}. */
    static Object constantOf(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode == Opcodes.ACONST_NULL) return null;
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) return (long) (opcode - Opcodes.LCONST_0);
        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return (float) (opcode - Opcodes.FCONST_0);
        if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) return (double) (opcode - Opcodes.DCONST_0);
        if (instruction instanceof IntInsnNode push && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) {
            return push.operand;
        }
        if (instruction instanceof LdcInsnNode load) return load.cst;
        return NO_CONSTANT;
    }

    // ---------------------------------------------------------------- handler signatures

    /** A handler's parameter types, the descriptors of its plain parameters, and its {@code @Local}s by index. */
    record HandlerParameters(Type[] types, List<String> plain, Map<Integer, AnnotationNode> locals) {
        static HandlerParameters of(MethodNode handler) {
            Type[] types = Type.getArgumentTypes(handler.desc);
            List<String> plain = new ArrayList<>();
            Map<Integer, AnnotationNode> locals = new LinkedHashMap<>();
            for (int index = 0; index < types.length; index++) {
                AnnotationNode sugar = null;
                for (AnnotationNode annotation : parameterAnnotations(handler, index)) {
                    if (SUGAR.contains(annotationName(annotation))) sugar = annotation;
                }
                if (sugar == null) plain.add(types[index].getDescriptor());
                else if (annotationName(sugar).equals("Local")) locals.put(index, sugar);
            }
            return new HandlerParameters(types, plain, locals);
        }
    }

    /** Problems with a handler's signature against one target method and the points it injects at there. */
    static List<String> checkHandler(String kind, MethodNode handler, MethodNode target,
                                     List<AbstractInsnNode> points) {
        List<String> problems = new ArrayList<>();
        HandlerParameters parameters = HandlerParameters.of(handler);
        List<String> targetArguments = Arrays.stream(Type.getArgumentTypes(target.desc)).map(Type::getDescriptor)
                .toList();
        Type targetReturn = Type.getReturnType(target.desc);
        switch (kind) {
            case "Inject" -> checkInjectSignature(parameters.plain(), targetArguments, targetReturn, problems);
            case "WrapMethod" -> {
                List<String> plain = parameters.plain();
                if (plain.size() != targetArguments.size() + 1
                        || !plain.subList(0, targetArguments.size()).equals(targetArguments)) {
                    problems.add("handler args " + plain + " != target args " + targetArguments + " + Operation");
                }
                Type handlerReturn = Type.getReturnType(handler.desc);
                if (!handlerReturn.equals(targetReturn)) {
                    problems.add("return " + handlerReturn + " != target return " + targetReturn);
                }
            }
            case "Redirect", "WrapOperation", "WrapWithCondition", "ModifyArg", "ModifyExpressionValue" ->
                    checkCapturedArguments(kind, handler, points.get(0), parameters.plain(), targetArguments,
                            problems);
            default -> { }
        }
        checkLocals(handler, target, parameters, points, problems);
        return problems;
    }

    /** An {@code @Inject} handler takes the target's arguments or none, then the right CallbackInfo. */
    private static void checkInjectSignature(List<String> plain, List<String> targetArguments, Type targetReturn,
                                             List<String> problems) {
        int callbackInfo = 0;
        while (callbackInfo < plain.size() && !plain.get(callbackInfo).startsWith(CALLBACK_INFO)) callbackInfo++;
        if (callbackInfo == plain.size()) {
            problems.add("no CallbackInfo param");
            return;
        }
        List<String> before = plain.subList(0, callbackInfo);
        if (!before.isEmpty() && !before.equals(targetArguments)) {
            problems.add("handler args " + before + " != target args " + targetArguments);
        }
        boolean returnable = plain.get(callbackInfo).endsWith("CallbackInfoReturnable;");
        if (returnable != (targetReturn.getSort() != Type.VOID)) {
            problems.add("CallbackInfo kind mismatch for return " + targetReturn);
        }
        if (plain.size() > callbackInfo + 1) {
            problems.add("captured locals after CallbackInfo (manual check) "
                    + plain.subList(callbackInfo + 1, plain.size()));
        }
    }

    /** Plain parameters past what the injection point fills must be a prefix of the target's arguments. */
    private static void checkCapturedArguments(String kind, MethodNode handler, AbstractInsnNode point,
                                               List<String> plain, List<String> targetArguments,
                                               List<String> problems) {
        int expected = expectedOperandCount(kind, handler, point);
        if (expected < 0 || plain.size() <= expected) return;
        List<String> captured = plain.subList(expected, plain.size());
        if (captured.size() > targetArguments.size()
                || !targetArguments.subList(0, captured.size()).equals(captured)) {
            problems.add("captured target args " + captured + " not a prefix of " + targetArguments);
        }
    }

    /** How many plain handler parameters the injection point itself fills; -1 when this does not model it. */
    static int expectedOperandCount(String kind, MethodNode handler, AbstractInsnNode point) {
        int operands = operandCount(point);
        if (operands < 0) return -1;
        return switch (kind) {
            case "WrapOperation" -> operands + 1;
            case "ModifyArg" -> modifyArgParameterCount(handler, point);
            case "ModifyExpressionValue" -> 1;
            default -> operands;
        };
    }

    /** The values an instruction consumes: a call's arguments and receiver, or a field access's; -1 for NEW. */
    private static int operandCount(AbstractInsnNode point) {
        if (point instanceof MethodInsnNode call) {
            return Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        }
        if (point instanceof FieldInsnNode access) {
            return switch (access.getOpcode()) {
                case Opcodes.GETSTATIC -> 0;
                case Opcodes.PUTSTATIC, Opcodes.GETFIELD -> 1;
                default -> 2;
            };
        }
        return point instanceof TypeInsnNode ? -1 : 1;
    }

    /** A {@code @ModifyArg} handler takes one argument, or all of a call's when it takes the same types. */
    private static int modifyArgParameterCount(MethodNode handler, AbstractInsnNode point) {
        if (!(point instanceof MethodInsnNode call)) return 1;
        Type[] parameters = Type.getArgumentTypes(handler.desc);
        Type[] arguments = Type.getArgumentTypes(call.desc);
        boolean allArguments = arguments.length > 1 && parameters.length == arguments.length
                && parameters[0].equals(arguments[0]);
        return allArguments ? parameters.length : 1;
    }

    /** Each {@code @Local} parameter must find its local at every injection point; a problem for the first miss. */
    private static void checkLocals(MethodNode handler, MethodNode target, HandlerParameters parameters,
                                    List<AbstractInsnNode> points, List<String> problems) {
        for (Map.Entry<Integer, AnnotationNode> local : parameters.locals().entrySet()) {
            int parameter = local.getKey();
            String type = capturedType(handler, parameter, parameters.types()[parameter].getDescriptor());
            for (AbstractInsnNode point : points) {
                String problem = checkLocal(target, isStatic(target.access), point, local.getValue(), type);
                if (problem != null) {
                    problems.add("param#" + parameter + " " + problem + " in " + target.name);
                    break;
                }
            }
        }
    }

    // ---------------------------------------------------------------- @Local captures

    /** A local a {@code @Local} could capture: its slot and its name, {@code ?} for an argument. */
    record LocalCandidate(int index, String name) {
        @Override public String toString() {
            return index + ":" + name;
        }
    }

    /** The local type a {@code @Local} parameter captures: what a {@code Local*Ref} wraps, else its own type. */
    private static String capturedType(MethodNode handler, int parameter, String descriptor) {
        if (LOCAL_REF_TYPES.containsKey(descriptor)) return LOCAL_REF_TYPES.get(descriptor);
        if (descriptor.equals(LOCAL_REF)) return localRefType(handler, parameter);
        return descriptor;
    }

    /** Why a {@code @Local} cannot capture its local at {@code instruction}, or null when it can. */
    static String checkLocal(MethodNode target, boolean targetStatic, AbstractInsnNode instruction,
                             AnnotationNode local, String type) {
        List<LocalCandidate> candidates = Boolean.TRUE.equals(value(local, "argsOnly"))
                ? argumentCandidates(target, targetStatic, type) : localCandidates(target, instruction, type);
        if (candidates == null) return "no LVT in target";
        int index = intValue(local, "index", -1);
        List<Object> names = values(local, "name");
        int ordinal = intValue(local, "ordinal", -1);
        if (index >= 0) {
            if (candidates.stream().anyMatch(candidate -> candidate.index() == index)) return null;
            return "@Local(index=" + index + ") " + type + " not found; candidates=" + candidates;
        }
        if (!names.isEmpty()) {
            if (candidates.stream().anyMatch(candidate -> names.contains(candidate.name()))) return null;
            return "@Local(name=" + names + ") " + type + " not found; candidates=" + candidates;
        }
        if (ordinal >= 0) {
            if (candidates.size() > ordinal) return null;
            return "@Local(ordinal=" + ordinal + ") " + type + " only " + candidates.size() + " candidates";
        }
        if (candidates.size() == 1) return null;
        return "implicit @Local " + type + " needs exactly 1 candidate, found " + candidates.size() + " "
                + candidates;
    }

    /** The target's arguments of {@code type}, by slot. */
    private static List<LocalCandidate> argumentCandidates(MethodNode target, boolean targetStatic, String type) {
        List<LocalCandidate> candidates = new ArrayList<>();
        int slot = targetStatic ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(target.desc)) {
            if (argument.getDescriptor().equals(type)) candidates.add(new LocalCandidate(slot, "?"));
            slot += argument.getSize();
        }
        return candidates;
    }

    /** The locals of {@code type} in scope at {@code instruction}; null when the target has no local table. */
    private static List<LocalCandidate> localCandidates(MethodNode target, AbstractInsnNode instruction,
                                                        String type) {
        List<LocalVariableNode> locals = localsAt(target, instruction);
        if (locals == null) return null;
        return locals.stream().filter(local -> local.desc.equals(type))
                .map(local -> new LocalCandidate(local.index, local.name)).toList();
    }

    /** The local variables in scope at {@code instruction}, by slot; null when the method has no table. */
    static List<LocalVariableNode> localsAt(MethodNode method, AbstractInsnNode instruction) {
        if (method.localVariables == null) return null;
        int at = method.instructions.indexOf(instruction);
        List<LocalVariableNode> locals = new ArrayList<>();
        for (LocalVariableNode local : method.localVariables) {
            int start = method.instructions.indexOf(local.start);
            int end = method.instructions.indexOf(local.end);
            if (start <= at && at < end) locals.add(local);
        }
        locals.sort(Comparator.comparingInt(local -> local.index));
        return locals;
    }

    /** The erased type a generic {@code LocalRef<T>} parameter captures, or null without a signature. */
    static String localRefType(MethodNode handler, int parameter) {
        List<String> parameters = parameterSignatures(handler.signature);
        if (parameter >= parameters.size()) return null;
        String signature = parameters.get(parameter);
        int open = signature.indexOf('<');
        if (open < 0) return null;
        String captured = signature.substring(open + 1, signature.lastIndexOf('>'));
        if (captured.startsWith("+") || captured.startsWith("-")) captured = captured.substring(1);
        return withoutTypeArguments(captured);
    }

    /** The parameter type signatures of a generic method signature; empty when there is none. */
    static List<String> parameterSignatures(String signature) {
        List<String> parameters = new ArrayList<>();
        if (signature == null) return parameters;
        int at = signature.indexOf('(') + 1;
        while (signature.charAt(at) != ')') {
            int start = at;
            while (signature.charAt(at) == '[') at++;
            if (signature.charAt(at) == 'L' || signature.charAt(at) == 'T') at = referenceEnd(signature, at);
            at++;
            parameters.add(signature.substring(start, at));
        }
        return parameters;
    }

    /** The index of the {@code ;} that ends the class or type-variable signature at {@code at}. */
    private static int referenceEnd(String signature, int at) {
        int depth = 0;
        for (int index = at; ; index++) {
            char c = signature.charAt(index);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ';' && depth == 0) return index;
        }
    }

    /** A type signature without its type arguments, with inner-class dots as {@code $}: its descriptor. */
    static String withoutTypeArguments(String signature) {
        StringBuilder descriptor = new StringBuilder();
        int depth = 0;
        for (char c : signature.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0) descriptor.append(c);
        }
        return descriptor.toString().replace('.', '$');
    }
}
