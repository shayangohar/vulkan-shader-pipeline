package reforged;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static reforged.MixinCheck.VULKANMOD;
import static reforged.MixinCheck.flags;
import static reforged.MixinCheck.isStatic;

/**
 * The refs mode: scans Chimera's classes for what they use of Reforged (constant-pool references, the opcodes that
 * reach each member, Reforged types named in descriptors and strings, and Reforged types Chimera extends), then
 * reports each against an old (control) and a new Reforged class tree.
 */
final class ReferenceCheck {
    /** A VulkanMod type named in a descriptor, signature or string. */
    static final Pattern VULKANMOD_TYPE = Pattern.compile("L(net/vulkanmod/[A-Za-z0-9_/$]+)[;<]");
    static final Pattern ARRAY_PREFIX = Pattern.compile("^\\[+L?");
    static final Pattern DESCRIPTOR_END = Pattern.compile(";$");
    /** Constant-pool tags of the entries the scan reads. */
    static final int CONSTANT_UTF8 = 1;
    static final int CONSTANT_CLASS = 7;
    static final int CONSTANT_FIELDREF = 9;
    static final int CONSTANT_METHODREF = 10;
    static final int CONSTANT_INTERFACE_METHODREF = 11;

    private final ClassDirectory chimera;
    private final ClassLookup oldClasses;
    private final ClassLookup newClasses;
    private final String oldLabel;
    private final String newLabel;
    /** Each reference, and the Chimera classes that make it. */
    private final Map<Reference, Set<String>> references = new TreeMap<>(Reference.ORDER);
    /** Each Reforged type named in a descriptor, signature or string, and the Chimera classes naming it. */
    private final Map<String, Set<String>> mentionedTypes = new TreeMap<>();
    /** The opcodes Chimera reaches each Reforged member with, keyed like {@link Reference#key()}. */
    private final Map<String, Set<String>> opcodes = new TreeMap<>();
    /** Each Chimera class that extends or implements Reforged types, and those types. */
    private final Map<String, List<String>> subtypes = new TreeMap<>();
    private int classCount;

    ReferenceCheck(ClassDirectory chimera, ClassLookup oldClasses, ClassLookup newClasses, String oldLabel,
                   String newLabel) {
        this.chimera = chimera;
        this.oldClasses = oldClasses;
        this.newClasses = newClasses;
        this.oldLabel = oldLabel;
        this.newLabel = newLabel;
    }

    /** A constant-pool reference from Chimera to Reforged: a Class, Field, Method or IMethod (interface method). */
    record Reference(String kind, String owner, String name, String descriptor) {
        static final Comparator<Reference> ORDER = Comparator.comparing(Reference::owner)
                .thenComparing(Reference::name).thenComparing(Reference::descriptor).thenComparing(Reference::kind);

        /** The member as text: {@code owner.name:desc} for a field, {@code owner.namedesc} otherwise. */
        String key() {
            return kind.equals("Field") ? owner + "." + name + ":" + descriptor : owner + "." + name + descriptor;
        }
    }

    // ---------------------------------------------------------------- scan

    void scan() throws IOException {
        for (String className : chimera.classNames("")) {
            classCount++;
            scanConstantPool(className);
            ClassNode type = chimera.find(className);
            scanSupertypes(className, type);
            scanInstructions(type);
        }
    }

    private void scanConstantPool(String className) {
        byte[] bytes = chimera.bytes(className);
        ClassReader reader = new ClassReader(bytes);
        char[] buffer = new char[Math.max(reader.getMaxStringLength(), 16)];
        String user = className.replace("net/chimera/", "");
        for (int item = 1; item < reader.getItemCount(); item++) {
            int offset = reader.getItem(item);
            if (offset == 0) continue;
            int tag = reader.readByte(offset - 1);
            switch (tag) {
                case CONSTANT_CLASS -> {
                    String type = elementType(reader.readUTF8(offset, buffer));
                    if (type.startsWith(VULKANMOD)) addReference(new Reference("Class", type, "", ""), user);
                }
                case CONSTANT_FIELDREF, CONSTANT_METHODREF, CONSTANT_INTERFACE_METHODREF ->
                        scanMemberReference(reader, offset, buffer, tag, user);
                case CONSTANT_UTF8 -> {
                    String text = new String(bytes, offset + 2, reader.readUnsignedShort(offset),
                            StandardCharsets.ISO_8859_1);
                    Matcher matcher = VULKANMOD_TYPE.matcher(text);
                    while (matcher.find()) {
                        mentionedTypes.computeIfAbsent(matcher.group(1), key -> new TreeSet<>()).add(user);
                    }
                }
                default -> { }
            }
        }
    }

    private void scanMemberReference(ClassReader reader, int offset, char[] buffer, int tag, String user) {
        String owner = reader.readClass(offset, buffer);
        int nameAndType = reader.getItem(reader.readUnsignedShort(offset + 2));
        String name = reader.readUTF8(nameAndType, buffer);
        String descriptor = reader.readUTF8(nameAndType + 2, buffer);
        if (!elementType(owner).startsWith(VULKANMOD)) return;
        String kind = tag == CONSTANT_FIELDREF ? "Field" : tag == CONSTANT_METHODREF ? "Method" : "IMethod";
        addReference(new Reference(kind, owner, name, descriptor), user);
    }

    private void addReference(Reference reference, String user) {
        references.computeIfAbsent(reference, key -> new TreeSet<>()).add(user);
    }

    private void scanSupertypes(String className, ClassNode type) {
        List<String> supertypes = new ArrayList<>();
        if (type.superName != null && type.superName.startsWith(VULKANMOD)) supertypes.add(type.superName);
        for (String implemented : type.interfaces) {
            if (implemented.startsWith(VULKANMOD)) supertypes.add(implemented);
        }
        if (!supertypes.isEmpty()) subtypes.put(className, supertypes);
    }

    private void scanInstructions(ClassNode type) {
        for (MethodNode method : type.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.startsWith(VULKANMOD)) {
                    addOpcode(call.owner + "." + call.name + call.desc,
                            opcodeName(call.getOpcode()) + (call.itf ? "(itf)" : ""));
                }
                if (instruction instanceof FieldInsnNode access && access.owner.startsWith(VULKANMOD)) {
                    addOpcode(access.owner + "." + access.name + ":" + access.desc, opcodeName(access.getOpcode()));
                }
            }
        }
    }

    private void addOpcode(String member, String opcode) {
        opcodes.computeIfAbsent(member, key -> new TreeSet<>()).add(opcode);
    }

    // ---------------------------------------------------------------- report

    /**
     * A REF line per reference, with FAIL when it does not resolve in the new tree, CTRLFAIL when it does not
     * resolve in the old one, and CHANGED (after the summary) when it resolves differently.
     */
    void reportReferences() {
        int failing = 0;
        int controlFailing = 0;
        List<String> changed = new ArrayList<>();
        for (Map.Entry<Reference, Set<String>> entry : references.entrySet()) {
            Reference reference = entry.getKey();
            String head = reference.kind() + "\t" + reference.key();
            String users = "\tused by " + entry.getValue();
            String inOld = resolve(oldClasses, reference);
            String inNew = resolve(newClasses, reference);
            if (!inOld.startsWith("OK")) {
                controlFailing++;
                System.out.println("CTRLFAIL\t" + head + "\tA=" + inOld + "\tB=" + inNew + users);
            }
            if (!inNew.startsWith("OK")) {
                failing++;
                System.out.println("FAIL\t" + head + "\t" + inNew + "\t(" + oldLabel + ": " + inOld + ")" + users);
            } else if (!inOld.equals(inNew)) {
                changed.add("CHANGED\t" + head + "\tA=" + inOld + "\tB=" + inNew + users);
            }
            System.out.println("REF\t" + head + "\t" + inNew + users);
            failing += reportOpcodes(head, reference.key(), inNew, users);
        }
        System.out.println("SUMMARY\tclasses scanned=" + classCount + " vulkanmod refs checked=" + references.size()
                + " failing(" + newLabel + ")=" + failing + " failing(control " + oldLabel + ")=" + controlFailing);
        changed.forEach(System.out::println);
    }

    /** A FAIL line, counted, per opcode whose static-ness disagrees with the member it reaches in the new tree. */
    private int reportOpcodes(String head, String member, String inNew, String users) {
        Set<String> used = opcodes.get(member);
        if (used == null || !inNew.startsWith("OK")) return 0;
        boolean memberStatic = inNew.contains("static");
        int failing = 0;
        for (String opcode : used) {
            boolean staticOpcode = opcode.startsWith("INVOKESTATIC") || opcode.equals("GETSTATIC")
                    || opcode.equals("PUTSTATIC");
            if (staticOpcode != memberStatic) {
                failing++;
                System.out.println("FAIL\t" + head + "\topcode " + opcode + " vs member " + inNew + users);
            }
        }
        return failing;
    }

    /** A TYPEMISSING line per Reforged type Chimera names that the new tree lacks. */
    void reportMentionedTypes() {
        int missing = 0;
        for (Map.Entry<String, Set<String>> entry : mentionedTypes.entrySet()) {
            if (newClasses.find(entry.getKey()) != null) continue;
            missing++;
            String inOld = oldClasses.find(entry.getKey()) != null ? "has" : "lacks";
            System.out.println("TYPEMISSING\t" + entry.getKey() + "\t(" + oldLabel + " " + inOld
                    + ")\tmentioned by " + entry.getValue());
        }
        System.out.println("SUMMARY\ttypes mentioned in descriptors/strings=" + mentionedTypes.size()
                + " missing in " + newLabel + "=" + missing);
    }

    /** For each Reforged type a Chimera class extends: what it leaves abstract, and how its overrides moved. */
    void reportSubtypes() {
        subtypes.forEach((className, supertypes) -> {
            ClassNode type = chimera.find(className);
            for (String supertype : supertypes) reportSubtype(className, type, supertype);
        });
    }

    private void reportSubtype(String className, ClassNode type, String supertype) {
        Set<String> implemented = new HashSet<>();
        for (MethodNode method : type.methods) implemented.add(method.name + method.desc);
        System.out.println("SUBTYPE\t" + className + " -> " + supertype + "\tunimplemented abstract (" + oldLabel
                + ")=" + difference(abstractMethods(oldClasses, supertype), implemented) + "\t(" + newLabel
                + ")=" + difference(abstractMethods(newClasses, supertype), implemented));
        Set<String> inheritedOld = allMethods(oldClasses, supertype);
        Set<String> inheritedNew = allMethods(newClasses, supertype);
        reportOverrides(className, type, supertype, inheritedOld, inheritedNew);
        Set<String> added = difference(inheritedNew, inheritedOld);
        Set<String> removed = difference(inheritedOld, inheritedNew);
        added.removeIf(signature -> signature.startsWith("lambda$"));
        removed.removeIf(signature -> signature.startsWith("lambda$"));
        if (!added.isEmpty() || !removed.isEmpty()) {
            System.out.println("SUPER-DELTA\t" + className + " -> " + supertype + "\tadded=" + added
                    + "\tremoved=" + removed);
        }
        ClassNode oldSuper = oldClasses.find(supertype);
        ClassNode newSuper = newClasses.find(supertype);
        if (newSuper != null && oldSuper != null && newSuper.access != oldSuper.access) {
            System.out.println("SUPER-ACCESS\t" + supertype + " " + flags(oldSuper.access, null) + " -> "
                    + flags(newSuper.access, null));
        }
    }

    private void reportOverrides(String className, ClassNode type, String supertype, Set<String> inheritedOld,
                                 Set<String> inheritedNew) {
        Set<String> finalInNew = finalMethods(newClasses, supertype);
        for (MethodNode method : type.methods) {
            if (method.name.startsWith("<")) continue;
            String signature = method.name + method.desc;
            if (inheritedOld.contains(signature) && !inheritedNew.contains(signature)) {
                System.out.println("OVERRIDE-LOST\t" + className + "\t" + signature + " existed in " + oldLabel
                        + " hierarchy of " + supertype + " but not " + newLabel);
            }
            if (!inheritedOld.contains(signature) && inheritedNew.contains(signature)) {
                System.out.println("OVERRIDE-NEW\t" + className + "\t" + signature + " now overrides a " + newLabel
                        + " member of " + supertype);
            }
            if (finalInNew.contains(signature)) {
                System.out.println("OVERRIDE-FINAL\t" + className + "\t" + signature + " is final in " + newLabel
                        + " hierarchy of " + supertype);
            }
        }
    }

    // ---------------------------------------------------------------- resolution in a class tree

    /** A class or array name's element type: {@code [Lfoo/Bar;} gives {@code foo/Bar}. */
    static String elementType(String name) {
        return DESCRIPTOR_END.matcher(ARRAY_PREFIX.matcher(name).replaceAll("")).replaceAll("");
    }

    /** Where a reference resolves in a class tree: {@code OK} and the member's flags, or why it does not. */
    static String resolve(ClassLookup classes, Reference reference) {
        String owner = reference.owner();
        if (owner.startsWith("[")) return "OK array";
        ClassNode ownerClass = classes.find(owner);
        if (ownerClass == null) return "CLASS MISSING";
        if (reference.kind().equals("Class")) {
            return "OK " + flags(ownerClass.access, null)
                    + ((ownerClass.access & Opcodes.ACC_INTERFACE) != 0 ? " interface" : "");
        }
        boolean field = reference.kind().equals("Field");
        Hierarchy hierarchy = Hierarchy.of(classes, owner);
        for (ClassNode type : hierarchy.classes()) {
            String where = type.name.equals(owner) ? "" : " in " + type.name;
            String resolved = field ? resolveField(type, reference, where) : resolveMethod(type, reference, where);
            if (resolved != null) return resolved;
        }
        String external = hierarchy.lastMissing();
        if (field) return external != null ? "UNRESOLVED (external super " + external + ")" : "FIELD MISSING";
        return unresolvedMethod(ownerClass, reference.name(), external);
    }

    /**
     * A method the Reforged hierarchy does not declare: probably inherited when a Java or Minecraft supertype is
     * outside the tree and the owner has no method of that name; missing otherwise, with its same-name methods.
     */
    private static String unresolvedMethod(ClassNode ownerClass, String name, String external) {
        List<String> sameName = ownerClass.methods.stream().filter(method -> method.name.equals(name))
                .map(method -> method.desc).toList();
        boolean fromLibrary = external != null && (external.startsWith("java/") || external.startsWith("net/minecraft")
                || external.startsWith("com/mojang"));
        if (fromLibrary && sameName.isEmpty()) return "OK? inherited-from-external " + external;
        return "METHOD MISSING" + (sameName.isEmpty() ? "" : " (same name: " + sameName + ")");
    }

    private static String resolveField(ClassNode type, Reference reference, String where) {
        for (FieldNode field : type.fields) {
            if (!field.name.equals(reference.name())) continue;
            if (field.desc.equals(reference.descriptor())) return "OK " + flags(field.access, field.signature) + where;
            return "FIELD TYPE CHANGED to " + field.desc;
        }
        return null;
    }

    private static String resolveMethod(ClassNode type, Reference reference, String where) {
        for (MethodNode method : type.methods) {
            if (method.name.equals(reference.name()) && method.desc.equals(reference.descriptor())) {
                return "OK " + flags(method.access, method.signature) + where;
            }
        }
        return null;
    }

    /** The abstract instance methods in a type's hierarchy that nothing in it implements. */
    static Set<String> abstractMethods(ClassLookup classes, String type) {
        Set<String> abstracts = new TreeSet<>();
        Set<String> concrete = new HashSet<>();
        for (ClassNode node : Hierarchy.of(classes, type).classes()) {
            for (MethodNode method : node.methods) {
                if (isStatic(method.access)) continue;
                boolean isAbstract = (method.access & Opcodes.ACC_ABSTRACT) != 0;
                (isAbstract ? abstracts : concrete).add(method.name + method.desc);
            }
        }
        abstracts.removeAll(concrete);
        return abstracts;
    }

    /** The final, non-private methods in a type's hierarchy. */
    static Set<String> finalMethods(ClassLookup classes, String type) {
        Set<String> finals = new HashSet<>();
        for (ClassNode node : Hierarchy.of(classes, type).classes()) {
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE)) == Opcodes.ACC_FINAL) {
                    finals.add(method.name + method.desc);
                }
            }
        }
        return finals;
    }

    /** Every method in a type's hierarchy, as name and descriptor. */
    static Set<String> allMethods(ClassLookup classes, String type) {
        Set<String> methods = new HashSet<>();
        for (ClassNode node : Hierarchy.of(classes, type).classes()) {
            for (MethodNode method : node.methods) methods.add(method.name + method.desc);
        }
        return methods;
    }

    /** The members of {@code from} that {@code without} lacks, sorted. */
    static Set<String> difference(Set<String> from, Set<String> without) {
        Set<String> difference = new TreeSet<>(from);
        difference.removeAll(without);
        return difference;
    }

    static String opcodeName(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
            case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
            case Opcodes.GETFIELD -> "GETFIELD";
            case Opcodes.PUTFIELD -> "PUTFIELD";
            case Opcodes.GETSTATIC -> "GETSTATIC";
            case Opcodes.PUTSTATIC -> "PUTSTATIC";
            default -> "OP" + opcode;
        };
    }
}
