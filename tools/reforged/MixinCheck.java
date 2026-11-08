package reforged;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks Chimera's mixins and direct references against a VulkanMod Reforged class tree, to tell whether Chimera
 * still loads on a new Reforged jar. chimera.mixins.json sets {@code defaultRequire 1}, so every Chimera mixin into
 * Reforged must apply: one injector that finds no target, or one shadowed, accessed or invoked member that is gone,
 * stops the game at launch. A direct reference to a Reforged member that moved fails later, when the code first
 * runs. The checker reads class files only: Chimera's compiled classes ({@code build/classes/java/main}) and
 * Reforged jars extracted to directories. It models Mixin and MixinExtras closely enough to catch moved targets; it
 * is not Mixin, so an injector it passes can still fail on something it does not model.
 *
 * <p>Run every check against the Reforged jar Chimera builds against too. That old jar is the control and must
 * report no failures; when it does, the checker or Chimera's build is out of date and the new jar's results mean
 * nothing. {@code tools/check-reforged.ps1} extracts both jars and runs inject, merged and refs that way.
 *
 * <p>Usage, from {@code tools}: compile with {@code javac -cp <asm jar>;<asm-tree jar> -d <out> reforged/*.java},
 * then run {@code java -cp <out>;<asm jar>;<asm-tree jar> reforged.MixinCheck <mode> <operands>}, one of:
 * <ul>
 *   <li>{@code inject <chimera classes> <reforged dir> [-v]}: each Chimera mixin into {@code net/vulkanmod}: its
 *       target classes; its {@code @Shadow}, {@code @Overwrite}, {@code @Accessor} and {@code @Invoker} members;
 *       and each injector's selectors, {@code @At} points, slices, {@code require} and {@code allow} counts,
 *       handler signature and {@code @Local} captures. Prints a FAIL line per problem and a SUMMARY; {@code -v}
 *       also prints what passed.</li>
 *   <li>{@code refs <chimera classes> <old dir> <new dir> [<old label> <new label>]}: each class, field and method
 *       constant in Chimera's classes that names a {@code net/vulkanmod} type, resolved in both trees; opcodes
 *       that disagree with a member's static-ness; Reforged types named in descriptors and strings; and the
 *       Reforged types Chimera extends (unimplemented abstract methods, lost, new and final overrides). The labels
 *       name the two trees in the report; they default to old and new.</li>
 *   <li>{@code dump <classes dir> <package prefix>}: one line per injector or overwrite in the mixins under the
 *       prefix, to diff what two versions inject into.</li>
 *   <li>{@code api <reforged dir>}: each {@code net/vulkanmod} class, field and method with its access flags, to
 *       diff two versions.</li>
 *   <li>{@code merged <chimera classes> <reforged dir>}: Chimera injectors into vanilla methods whose body a
 *       Reforged mixin overwrites or merges in, checked against Reforged's body instead of vanilla's: a HIT line
 *       per such body, then a FAIL or ok line per injector. It also fails what Mixin 0.8.7 refuses at launch:
 *       a non-HEAD injector into such a body from a priority not above the Reforged mixin's, and any injector
 *       in an interface mixin. It prints no SUMMARY.</li>
 * </ul>
 */
public final class MixinCheck {
    static final String USAGE = "usage: MixinCheck inject <chimera classes> <reforged dir> [-v]"
            + " | refs <chimera classes> <old dir> <new dir> [<old label> <new label>]"
            + " | dump <classes dir> <package prefix> | api <reforged dir> | merged <chimera classes> <reforged dir>";
    static final String VULKANMOD = "net/vulkanmod/";
    static final String CHIMERA_MIXINS = "net/chimera/mixin/";
    static final String REFORGED_MIXINS = "net/vulkanmod/mixin/";
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    /** The annotations that inject code into a target method. */
    static final Set<String> INJECTORS = Set.of("Inject", "Redirect", "WrapOperation", "WrapMethod", "ModifyArg",
            "ModifyArgs", "ModifyVariable", "ModifyExpressionValue", "ModifyReturnValue", "ModifyConstant",
            "WrapWithCondition", "ModifyReceiver");
    /** The annotations that reach a target's existing member instead of giving it a body. */
    static final Set<String> MEMBER_ANNOTATIONS = Set.of("Shadow", "Accessor", "Invoker");

    private MixinCheck() {}

    public static void main(String[] args) throws IOException {
        String mode = args.length == 0 ? "" : args[0];
        List<String> operands = args.length == 0 ? List.of() : List.of(args).subList(1, args.length);
        if (!validOperands(mode, operands)) throw new IllegalArgumentException(USAGE);
        switch (mode) {
            case "inject" -> inject(operands.get(0), operands.get(1), operands.size() == 3);
            case "refs" -> {
                List<String> labels = operands.size() == 5 ? operands.subList(3, 5) : List.of("old", "new");
                refs(operands.get(0), operands.get(1), operands.get(2), labels.get(0), labels.get(1));
            }
            case "dump" -> dump(operands.get(0), operands.get(1));
            case "api" -> api(operands.get(0));
            case "merged" -> merged(operands.get(0), operands.get(1));
            default -> throw new IllegalStateException(mode);
        }
    }

    private static boolean validOperands(String mode, List<String> operands) {
        return switch (mode) {
            case "inject" -> operands.size() == 2 || (operands.size() == 3 && operands.get(2).equals("-v"));
            case "refs" -> operands.size() == 3 || operands.size() == 5;
            case "dump", "merged" -> operands.size() == 2;
            case "api" -> operands.size() == 1;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- modes

    /** Checks every Chimera mixin into {@code net/vulkanmod} against one Reforged class tree. */
    static void inject(String chimeraDirectory, String reforgedDirectory, boolean verbose) throws IOException {
        ClassDirectory chimera = new ClassDirectory(chimeraDirectory);
        InjectCheck check = new InjectCheck(new ClassDirectory(reforgedDirectory), verbose);
        for (String className : chimera.classNames(CHIMERA_MIXINS)) {
            check.checkMixin(chimera.find(className), simpleName(className));
        }
        System.out.println(check.tally.summary());
    }

    /** Resolves Chimera's references to Reforged in an old (control) and a new class tree. */
    static void refs(String chimeraDirectory, String oldDirectory, String newDirectory, String oldLabel,
                     String newLabel) throws IOException {
        ReferenceCheck check = new ReferenceCheck(new ClassDirectory(chimeraDirectory),
                new ClassDirectory(oldDirectory), new ClassDirectory(newDirectory), oldLabel, newLabel);
        check.scan();
        check.reportReferences();
        check.reportMentionedTypes();
        check.reportSubtypes();
    }

    /** Prints each injector or overwrite under {@code prefix}: target, method, kind, selector, points, handler. */
    static void dump(String directory, String prefix) throws IOException {
        ClassDirectory classes = new ClassDirectory(directory);
        for (String className : classes.classNames(prefix)) {
            ClassNode mixin = classes.find(className);
            String handlerOwner = className.substring(prefix.length());
            for (String target : mixinTargets(mixin)) {
                for (MethodNode handler : mixin.methods) {
                    for (AnnotationNode annotation : annotations(handler.visibleAnnotations,
                            handler.invisibleAnnotations)) {
                        dumpAnnotation(target, handlerOwner + "." + handler.name, handler, annotation);
                    }
                }
            }
        }
    }

    private static void dumpAnnotation(String target, String handlerName, MethodNode handler,
                                       AnnotationNode annotation) {
        String kind = annotationName(annotation);
        if (kind.equals("Overwrite")) {
            System.out.println(target + "\t" + handler.name + "\t" + kind + "\t" + handler.name + handler.desc
                    + "\t-\t" + handlerName);
            return;
        }
        if (!INJECTORS.contains(kind)) return;
        List<String> ats = values(annotation, "at").stream().map(at -> describeAt((AnnotationNode) at)).toList();
        String points = ats.isEmpty() ? "-" : String.join(";", ats);
        for (Object text : values(annotation, "method")) {
            Selector selector = Selector.parse(text.toString());
            String method = selector.name() == null ? "?" : selector.name();
            System.out.println(target + "\t" + method + "\t" + kind + "\t" + text + "\t" + points + "\t" + handlerName);
        }
    }

    /** Prints each {@code net/vulkanmod} class, field and method with its flags; lambdas are left out. */
    static void api(String directory) throws IOException {
        ClassDirectory classes = new ClassDirectory(directory);
        for (String className : classes.classNames(VULKANMOD)) {
            ClassNode type = classes.find(className);
            System.out.println(className + "\tCLASS\t" + flags(type.access, null) + " extends " + type.superName
                    + " implements " + type.interfaces);
            for (FieldNode field : type.fields) {
                System.out.println(className + "\tF " + field.name + ":" + field.desc + "\t"
                        + flags(field.access, null));
            }
            for (MethodNode method : type.methods) {
                if ((method.access & Opcodes.ACC_SYNTHETIC) != 0 && method.name.startsWith("lambda$")) continue;
                System.out.println(className + "\tM " + method.name + method.desc + "\t" + flags(method.access, null));
            }
        }
    }

    /**
     * Checks Chimera injectors into vanilla classes against the methods Reforged's mixins put there: a HIT line
     * per Reforged body an injector selects, then the injector's check against those bodies alone. Two Mixin
     * 0.8.7 rules fail at launch whatever the target: an injector in an interface mixin, and an injector into
     * another mixin's merged body from a priority not above that mixin's, unless it injects at HEAD
     * (MethodHead.checkPriority) or wraps the whole method.
     */
    static void merged(String chimeraDirectory, String reforgedDirectory) throws IOException {
        ClassDirectory reforged = new ClassDirectory(reforgedDirectory);
        Map<String, ClassNode> bodies = reforgedBodies(reforged);
        ClassDirectory chimera = new ClassDirectory(chimeraDirectory);
        for (String className : chimera.classNames(CHIMERA_MIXINS)) {
            ClassNode mixin = chimera.find(className);
            String mixinName = simpleName(className);
            if ((mixin.access & Opcodes.ACC_INTERFACE) != 0) failInterfaceInjectors(mixin, mixinName);
            for (String target : mixinTargets(mixin)) {
                ClassNode body = target.startsWith(VULKANMOD) ? null : bodies.get(target);
                if (body == null) continue;
                InjectCheck check = new InjectCheck(name -> name.equals(target) ? body : null, true);
                MergedTarget merged = new MergedTarget(check, mixinName, mixinPriority(mixin), target, body, reforged);
                for (MethodNode handler : mixin.methods) {
                    for (AnnotationNode annotation : annotations(handler.visibleAnnotations,
                            handler.invisibleAnnotations)) {
                        checkMergedInjector(merged, handler, annotation);
                    }
                }
            }
        }
    }

    /** A Chimera mixin's view of one vanilla class that Reforged's mixins put bodies into. */
    private record MergedTarget(InjectCheck check, String mixinName, int priority, String target, ClassNode body,
                                ClassDirectory reforged) {}

    private static void failInterfaceInjectors(ClassNode mixin, String mixinName) {
        for (MethodNode handler : mixin.methods) {
            for (AnnotationNode annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                if (INJECTORS.contains(annotationName(annotation))) {
                    System.out.println("FAIL\t" + mixinName + "\t" + handler.name
                            + "\t<interface>\tinjector in an interface mixin: Mixin 0.8.7 applies none");
                }
            }
        }
    }

    private static void checkMergedInjector(MergedTarget merged, MethodNode handler, AnnotationNode annotation) {
        String kind = annotationName(annotation);
        if (!INJECTORS.contains(kind)) return;
        boolean hit = false;
        for (Object text : values(annotation, "method")) {
            Selector selector = Selector.parse(text.toString());
            for (MethodNode method : merged.body().methods) {
                if (!selector.matches(merged.target(), method.name, method.desc)) continue;
                hit = true;
                System.out.println("HIT\t" + merged.mixinName() + "." + handler.name + "\t" + merged.target() + "."
                        + method.name + method.desc + "\t" + method.signature.replace(REFORGED_MIXINS, ""));
                checkMergedPriority(merged, handler, annotation, method);
            }
        }
        if (hit) merged.check().checkInjector(merged.target(), merged.mixinName(), handler, annotation, kind);
    }

    /** Fails an injector that Mixin refuses into another mixin's merged body at its priority. */
    private static void checkMergedPriority(MergedTarget merged, MethodNode handler, AnnotationNode annotation,
                                            MethodNode method) {
        if (annotationName(annotation).equals("WrapMethod") || atHeadOnly(annotation)) return;
        String owner = method.signature.substring(method.signature.indexOf(' ') + 1);
        int ownerPriority = mixinPriority(merged.reforged().find(owner));
        if (merged.priority() > ownerPriority) return;
        System.out.println("FAIL\t" + merged.mixinName() + "\t" + handler.name + "\t" + merged.target() + "."
                + method.name + "\tbody merged by " + simpleName(owner) + " at priority " + ownerPriority
                + "; a non-HEAD injector needs a higher priority than " + merged.priority());
    }

    /** True when every {@code @At} of the injector is HEAD; false when it has none (ModifyConstant). */
    private static boolean atHeadOnly(AnnotationNode annotation) {
        List<Object> points = values(annotation, "at");
        if (points.isEmpty()) return false;
        for (Object point : points) {
            if (!"HEAD".equals(stringValue((AnnotationNode) point, "value"))) return false;
        }
        return true;
    }

    /** A mixin's {@code @Mixin(priority)}, 1000 when unset (neither Chimera's nor Reforged's config sets one). */
    static int mixinPriority(ClassNode type) {
        if (type == null) return 1000;
        for (AnnotationNode annotation : annotations(type.visibleAnnotations, type.invisibleAnnotations)) {
            if (annotation.desc.equals(MIXIN)) return intValue(annotation, "priority", 1000);
        }
        return 1000;
    }

    /**
     * The methods Reforged's mixins overwrite or merge into each class they target, retargeted at that class.
     * Each method's {@code signature} field is reused to record its origin: OVERWRITE or MERGED, then the mixin.
     */
    private static Map<String, ClassNode> reforgedBodies(ClassDirectory reforged) throws IOException {
        Map<String, ClassNode> bodies = new HashMap<>();
        for (String className : reforged.classNames(REFORGED_MIXINS)) {
            ClassNode mixin = reforged.find(className);
            for (String target : mixinTargets(mixin)) {
                ClassNode body = bodies.computeIfAbsent(target, name -> {
                    ClassNode type = new ClassNode();
                    type.name = name;
                    return type;
                });
                for (MethodNode method : mixin.methods) {
                    String origin = bodyOrigin(method);
                    if (origin == null) continue;
                    retarget(method, className, target);
                    method.signature = origin + className;
                    body.methods.add(method);
                }
            }
        }
        return bodies;
    }

    /** {@code "OVERWRITE "} or {@code "MERGED "} for a mixin method whose body lands in its target, else null. */
    private static String bodyOrigin(MethodNode method) {
        boolean overwrite = false;
        for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
            String kind = annotationName(annotation);
            if (INJECTORS.contains(kind) || MEMBER_ANNOTATIONS.contains(kind)) return null;
            if (kind.equals("Overwrite")) overwrite = true;
        }
        boolean helper = method.name.equals("<init>") || method.name.equals("<clinit>")
                || method.name.startsWith("lambda$");
        if (helper || (method.access & Opcodes.ACC_ABSTRACT) != 0) return null;
        return overwrite ? "OVERWRITE " : "MERGED ";
    }

    /** Points a mixin method's calls and field accesses on the mixin itself at its target class. */
    private static void retarget(MethodNode method, String mixin, String target) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(mixin)) call.owner = target;
            if (instruction instanceof FieldInsnNode access && access.owner.equals(mixin)) access.owner = target;
        }
    }

    // ---------------------------------------------------------------- shared class-file helpers

    /** The classes a {@code @Mixin} targets, or an empty list when the class is not a mixin. */
    static List<String> mixinTargets(ClassNode type) {
        for (AnnotationNode annotation : annotations(type.visibleAnnotations, type.invisibleAnnotations)) {
            if (!annotation.desc.equals(MIXIN)) continue;
            List<String> targets = new ArrayList<>();
            for (Object value : values(annotation, "value")) targets.add(((Type) value).getInternalName());
            for (Object value : values(annotation, "targets")) targets.add(((String) value).replace('.', '/'));
            return targets;
        }
        return List.of();
    }

    static String simpleName(String className) {
        return className.substring(className.lastIndexOf('/') + 1);
    }

    static boolean isStatic(int access) {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    /** Access flags as text, such as {@code public static final}, and the generic signature when given. */
    static String flags(int access, String signature) {
        return visibility(access) + ((access & Opcodes.ACC_STATIC) != 0 ? " static" : "")
                + ((access & Opcodes.ACC_FINAL) != 0 ? " final" : "")
                + ((access & Opcodes.ACC_ABSTRACT) != 0 ? " abstract" : "")
                + (signature != null ? " sig=" + signature : "");
    }

    private static String visibility(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) return "public";
        if ((access & Opcodes.ACC_PROTECTED) != 0) return "protected";
        if ((access & Opcodes.ACC_PRIVATE) != 0) return "private";
        return "package";
    }

    // ---------------------------------------------------------------- shared annotation helpers

    /** An annotation attribute's value, or null when it is not set. */
    static Object value(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(key)) return annotation.values.get(index + 1);
        }
        return null;
    }

    /** An array attribute's values; a single value as a list of one. */
    @SuppressWarnings("unchecked")
    static List<Object> values(AnnotationNode annotation, String key) {
        Object value = value(annotation, key);
        if (value == null) return List.of();
        if (value instanceof List<?> list) return (List<Object>) list;
        return List.of(value);
    }

    static int intValue(AnnotationNode annotation, String key, int fallback) {
        Object value = value(annotation, key);
        return value == null ? fallback : ((Number) value).intValue();
    }

    static String stringValue(AnnotationNode annotation, String key) {
        return stringValue(annotation, key, null);
    }

    static String stringValue(AnnotationNode annotation, String key, String fallback) {
        Object value = value(annotation, key);
        return value == null ? fallback : value.toString();
    }

    /** An annotation's simple type name, such as {@code Inject}. */
    static String annotationName(AnnotationNode annotation) {
        return annotation.desc.substring(annotation.desc.lastIndexOf('/') + 1, annotation.desc.length() - 1);
    }

    static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (visible != null) annotations.addAll(visible);
        if (invisible != null) annotations.addAll(invisible);
        return annotations;
    }

    static List<AnnotationNode> parameterAnnotations(MethodNode method, int parameter) {
        List<AnnotationNode> annotations = new ArrayList<>();
        addParameterAnnotations(annotations, method.visibleParameterAnnotations, parameter);
        addParameterAnnotations(annotations, method.invisibleParameterAnnotations, parameter);
        return annotations;
    }

    private static void addParameterAnnotations(List<AnnotationNode> annotations,
                                                List<AnnotationNode>[] byParameter, int parameter) {
        if (byParameter != null && parameter < byParameter.length && byParameter[parameter] != null) {
            annotations.addAll(byParameter[parameter]);
        }
    }

    /** An {@code @At} as text, such as {@code @At(INVOKE, Lfoo;bar()V, ordinal=1)}. */
    static String describeAt(AnnotationNode at) {
        StringBuilder text = new StringBuilder("@At(").append(stringValue(at, "value"));
        if (value(at, "target") != null) text.append(", ").append(stringValue(at, "target"));
        if (intValue(at, "ordinal", -1) >= 0) text.append(", ordinal=").append(intValue(at, "ordinal", -1));
        if (intValue(at, "opcode", -1) >= 0) text.append(", opcode=").append(intValue(at, "opcode", -1));
        if (!values(at, "args").isEmpty()) text.append(", args=").append(values(at, "args"));
        if (value(at, "slice") != null) text.append(", slice=").append(stringValue(at, "slice"));
        return text.append(")").toString();
    }
}
