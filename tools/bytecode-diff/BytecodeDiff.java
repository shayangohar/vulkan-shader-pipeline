import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compares two compiled class trees method by method after mapping moved code, to show that a
 * refactor which only moves code changes no behaviour (Stele KNOW-1176).
 *
 * Usage: BytecodeDiff <beforeDir> <afterDir> [rulesFile|-] [reportFile]
 * From Gradle: on the base commit, gradlew bytecodeBaseline saves build/classes/java/main and test
 * to build/bytecode-diff/before; on the refactor, gradlew bytecodeDiff -Prules=<file> compares
 * both against it and writes build/bytecode-diff/report-main.txt and report-test.txt.
 *
 * Rules (internal names with slashes, one per line, # comments):
 *   class OLD NEW            OLD's type and members now live in NEW (a host or a nested class)
 *   member OWNER NAME NEW    OWNER's members called NAME (and their lambdas) moved to NEW
 *   rename OWNER NAME NEW    OWNER's member NAME is now called NEW (OWNER is the original owner)
 *   normalize REGEX => TEXT  rewrites every printed body on both sides; \n stands for a line
 *                            break (for example, to read pass.targets.x as this.x after a merge)
 *
 * Still checked by hand: the constructors of merged classes, collapsed duplicates, access
 * changes, class literals of moved types, and anything reached by reflection, resources or
 * stack-trace text. Static initializers are compared field by field, so a static may move.
 *
 * The before tree is mapped forward into the after tree's names. Debug info, frames and
 * constant-pool layout are ignored; labels are numbered per method; lambda bodies are compared
 * as a multiset per enclosing method; enum switches are compared by constant name, not by the
 * synthetic switch-map index.
 */
public class BytecodeDiff {
    static final Map<String, String> classRules = new LinkedHashMap<>();
    static final Map<String, String> memberRules = new HashMap<>();
    static final Map<String, String> renameRules = new HashMap<>();
    /** Regex rewrites applied to every method body on both sides, in order: "normalize REGEX => REPLACEMENT". */
    static final List<String[]> normalizeRules = new ArrayList<>();
    static final Pattern OBJECT_TYPE = Pattern.compile("L([^;<>]+);");
    static final String TRIVIAL_INIT =
            "ACCESS-STATIC=false\n  ALOAD 0\n  INVOKESPECIAL java/lang/Object.<init>()V\n  RETURN\n";
    static final Pattern LAMBDA = Pattern.compile("^lambda\\$(.*)\\$\\d+$");

    public static void main(String[] args) throws Exception {
        Path before = Path.of(args[0]);
        Path after = Path.of(args[1]);
        if (args.length > 2 && !args[2].equals("-")) readRules(Path.of(args[2]));
        PrintWriter report = new PrintWriter(Files.newBufferedWriter(
                Path.of(args.length > 3 ? args[3] : "bytecode-diff.txt")));

        Map<String, ClassNode> b = load(before);
        Map<String, ClassNode> a = load(after);
        Map<String, Map<Integer, String>> bSwitch = switchMaps(b);
        Map<String, Map<Integer, String>> aSwitch = switchMaps(a);

        Mapper forward = new Mapper(true);
        Mapper identity = new Mapper(false);

        Map<String, List<String>> bMethods = new TreeMap<>();
        Map<String, Set<String>> bAccess = new TreeMap<>();
        Map<String, String> bFields = new TreeMap<>();
        Map<String, List<String>> aMethods = new TreeMap<>();
        Map<String, Set<String>> aAccess = new TreeMap<>();
        Map<String, String> aFields = new TreeMap<>();
        List<String> classLiterals = new ArrayList<>();

        collect(b, forward, bSwitch, bMethods, bAccess, bFields, null);
        collect(a, identity, aSwitch, aMethods, aAccess, aFields, null);
        // Class literals of remapped types in the before tree change getName()/getSimpleName().
        for (ClassNode c : b.values()) {
            for (MethodNode m : c.methods) {
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type t
                            && t.getSort() == Type.OBJECT
                            && !forward.type(t.getInternalName()).equals(t.getInternalName())) {
                        classLiterals.add(c.name + "." + m.name + " loads " + t.getInternalName());
                    }
                }
            }
        }

        int same = 0;
        List<String> changed = new ArrayList<>();
        List<String> collapsed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> access = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : bMethods.entrySet()) {
            List<String> other = aMethods.get(e.getKey());
            if (other == null) {
                missing.add(e.getKey());
                continue;
            }
            if (other.equals(e.getValue())) same++;
            else if (new TreeSet<>(other).equals(new TreeSet<>(e.getValue()))) collapsed.add(e.getKey());
            else if (e.getKey().contains(".<clinit>()V") && other.size() == 1
                    && concatenations(e.getValue()).contains(other.get(0))) {
                collapsed.add(e.getKey() + " (static initializers concatenated)");
            }
            else changed.add(e.getKey());
            if (!Objects.equals(bAccess.get(e.getKey()), aAccess.get(e.getKey()))) {
                access.add(e.getKey() + " " + bAccess.get(e.getKey()) + " -> " + aAccess.get(e.getKey()));
            }
        }
        for (String key : aMethods.keySet()) if (!bMethods.containsKey(key)) added.add(key);

        // Static initializers: compared field by field, so a static field may move between classes.
        // Each segment is the code up to and including one PUTSTATIC; a trailing bare RETURN is dropped.
        Map<String, List<String>> bStatics = staticSegments(bMethods);
        Map<String, List<String>> aStatics = staticSegments(aMethods);
        List<String> staticDiffs = new ArrayList<>();
        for (String field : new TreeSet<>(union(bStatics.keySet(), aStatics.keySet()))) {
            List<String> x = bStatics.getOrDefault(field, List.of());
            List<String> y = aStatics.getOrDefault(field, List.of());
            if (!x.equals(y)) {
                staticDiffs.add(field + "\n" + diff(String.join("\n----\n", x), String.join("\n----\n", y)));
            }
        }
        changed.removeIf(k -> k.endsWith(".<clinit>()V"));
        missing.removeIf(k -> k.endsWith(".<clinit>()V"));
        added.removeIf(k -> k.endsWith(".<clinit>()V"));
        collapsed.removeIf(k -> k.contains(".<clinit>()V"));

        List<String> fieldDiffs = new ArrayList<>();
        for (Map.Entry<String, String> e : bFields.entrySet()) {
            String other = aFields.get(e.getKey());
            if (other == null) fieldDiffs.add("MISSING FIELD " + e.getKey() + " " + e.getValue());
            else if (!other.equals(e.getValue())) {
                fieldDiffs.add("FIELD " + e.getKey() + " " + e.getValue() + " -> " + other);
            }
        }
        for (String key : aFields.keySet()) {
            if (!bFields.containsKey(key)) fieldDiffs.add("ADDED FIELD " + key + " " + aFields.get(key));
        }

        List<String> classDiffs = new ArrayList<>();
        Set<String> mappedBefore = new TreeSet<>();
        for (ClassNode c : b.values()) {
            if (isSwitchMapClass(c)) continue;
            String target = forward.type(c.name);
            mappedBefore.add(target);
            ClassNode other = a.get(target);
            if (other == null) {
                classDiffs.add("NO TARGET CLASS for " + c.name + " -> " + target);
                continue;
            }
            if (!target.equals(c.name) && !classRules.containsKey(c.name) && !nestedRule(c.name)) continue;
            if (!target.equals(c.name) && target.equals(classRules.get(c.name)) && a.containsKey(target)
                    && b.containsKey(target)) {
                continue; // folded into an existing host: the host's header is compared on its own
            }
            String bHeader = header(c, forward);
            String aHeader = header(other, identity);
            if (!bHeader.equals(aHeader)) classDiffs.add("HEADER " + target + "\n  before " + bHeader
                    + "\n  after  " + aHeader);
        }
        for (ClassNode c : a.values()) {
            if (isSwitchMapClass(c)) continue;
            if (!mappedBefore.contains(c.name)) classDiffs.add("NEW CLASS " + c.name);
        }

        System.out.println("methods identical: " + same + ", changed: " + changed.size()
                + ", duplicates collapsed: " + collapsed.size()
                + ", missing: " + missing.size() + ", added: " + added.size()
                + ", access changes: " + access.size() + ", field diffs: " + fieldDiffs.size()
                + ", class diffs: " + classDiffs.size()
                + ", static-field initializer diffs: " + staticDiffs.size()
                + ", class literals of moved types: " + classLiterals.size());
        section(report, "CHANGED", changed);
        section(report, "MISSING (before only)", missing);
        section(report, "ADDED (after only)", added);
        section(report, "ACCESS", access);
        section(report, "FIELDS", fieldDiffs);
        section(report, "CLASSES", classDiffs);
        section(report, "STATIC INITIALIZERS", staticDiffs);
        section(report, "CLASS LITERALS", classLiterals);
        report.println("==== BODIES OF CHANGED METHODS");
        for (String key : changed) {
            report.println("---- " + key);
            List<String> bb = bMethods.get(key);
            List<String> ab = aMethods.get(key);
            report.println(diff(String.join("\n====\n", bb), String.join("\n====\n", ab)));
        }
        for (String list : List.of("MISSING", "ADDED")) {
            report.println("==== BODIES " + list);
            for (String key : list.equals("MISSING") ? missing : added) {
                report.println("---- " + key);
                report.println(String.join("\n====\n", list.equals("MISSING") ? bMethods.get(key)
                        : aMethods.get(key)));
            }
        }
        report.close();
        for (String line : changed) System.out.println("CHANGED " + line);
        for (String line : collapsed) System.out.println("COLLAPSED " + line);
        for (String line : missing) System.out.println("MISSING " + line);
        for (String line : added) System.out.println("ADDED " + line);
        for (String line : access) System.out.println("ACCESS " + line);
        for (String line : fieldDiffs) System.out.println(line);
        for (String line : classDiffs) System.out.println(line);
        for (String line : classLiterals) System.out.println("CLASSLIT " + line);
        for (String line : staticDiffs) System.out.println("STATIC " + line.split("\n")[0]);
    }

    static Set<String> union(Set<String> x, Set<String> y) {
        Set<String> result = new TreeSet<>(x);
        result.addAll(y);
        return result;
    }

    /** Every static initializer's code, split per PUTSTATIC target; labels renumbered per segment. */
    static Map<String, List<String>> staticSegments(Map<String, List<String>> methods) {
        Map<String, List<String>> result = new TreeMap<>();
        for (Map.Entry<String, List<String>> e : methods.entrySet()) {
            if (!e.getKey().endsWith(".<clinit>()V")) continue;
            String owner = e.getKey().substring(0, e.getKey().length() - ".<clinit>()V".length());
            for (String body : e.getValue()) {
                List<String> lines = new ArrayList<>();
                for (String line : body.split("\n")) {
                    if (line.startsWith("ACCESS-STATIC=")) continue;
                    lines.add(line);
                    if (line.startsWith("  PUTSTATIC ")) {
                        String target = line.substring("  PUTSTATIC ".length());
                        result.computeIfAbsent(target, k -> new ArrayList<>()).add(relabel(lines));
                        lines.clear();
                    }
                }
                if (!(lines.size() == 1 && lines.get(0).equals("  RETURN")) && !lines.isEmpty()) {
                    result.computeIfAbsent("(rest of " + owner + ")", k -> new ArrayList<>()).add(relabel(lines));
                }
            }
        }
        for (List<String> list : result.values()) Collections.sort(list);
        return result;
    }

    static String relabel(List<String> lines) {
        Map<String, String> names = new HashMap<>();
        Pattern label = Pattern.compile("\\bL(\\d+)\\b");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            Matcher m = label.matcher(line);
            StringBuilder renamed = new StringBuilder();
            while (m.find()) {
                String name = names.computeIfAbsent(m.group(1), k -> "S" + names.size());
                m.appendReplacement(renamed, name);
            }
            m.appendTail(renamed);
            out.append(renamed).append('\n');
        }
        return out.toString();
    }

    /** Every order of joining these static initializers into one: each body's RETURN but the last dropped. */
    static Set<String> concatenations(List<String> bodies) {
        Set<String> result = new HashSet<>();
        permute(new ArrayList<>(bodies), 0, result);
        return result;
    }

    static void permute(List<String> bodies, int from, Set<String> out) {
        if (from == bodies.size()) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < bodies.size(); i++) {
                String body = bodies.get(i);
                if (i > 0) body = body.substring(body.indexOf('\n') + 1);
                if (i < bodies.size() - 1) {
                    if (!body.endsWith("  RETURN\n")) return;
                    body = body.substring(0, body.length() - "  RETURN\n".length());
                }
                joined.append(body);
            }
            out.add(joined.toString());
            return;
        }
        for (int i = from; i < bodies.size(); i++) {
            Collections.swap(bodies, from, i);
            permute(bodies, from + 1, out);
            Collections.swap(bodies, from, i);
        }
    }

    static boolean nestedRule(String name) {
        for (String key : classRules.keySet()) if (name.startsWith(key + "$")) return true;
        return false;
    }

    static void section(PrintWriter out, String title, List<String> lines) {
        out.println("==== " + title + " (" + lines.size() + ")");
        for (String line : lines) out.println(line);
    }

    static String header(ClassNode c, Mapper mp) {
        List<String> interfaces = new ArrayList<>();
        for (String i : c.interfaces) interfaces.add(mp.type(i));
        Collections.sort(interfaces);
        int flags = c.access & ~(Opcodes.ACC_SUPER);
        return "access=" + flags + " super=" + mp.type(c.superName) + " interfaces=" + interfaces;
    }

    static void collect(Map<String, ClassNode> classes, Mapper mp, Map<String, Map<Integer, String>> switches,
                        Map<String, List<String>> methods, Map<String, Set<String>> access,
                        Map<String, String> fields, Void unused) {
        for (ClassNode c : classes.values()) {
            if (isSwitchMapClass(c)) continue;
            boolean foldedDonor = mp.active && classRules.containsKey(c.name)
                    && classes.containsKey(classRules.get(c.name));
            for (MethodNode m : c.methods) {
                if (foldedDonor && m.name.equals("<init>") && m.desc.equals("()V")
                        && body(m, mp, switches).equals(TRIVIAL_INIT)) continue;
                String[] target = mp.member(c.name, m.name, m.desc);
                String key = target[0] + "." + normalizeName(target[1]) + mp.desc(m.desc);
                methods.computeIfAbsent(key, k -> new ArrayList<>()).add(body(m, mp, switches));
                access.computeIfAbsent(key, k -> new TreeSet<>()).add(accessString(m.access));
            }
            for (FieldNode f : c.fields) {
                String[] target = mp.member(c.name, f.name, f.desc);
                fields.put(target[0] + "." + target[1] + ":" + mp.desc(f.desc),
                        accessString(f.access) + (f.value == null ? "" : " = " + f.value));
            }
        }
        for (List<String> list : methods.values()) Collections.sort(list);
    }

    static String accessString(int access) {
        StringBuilder s = new StringBuilder();
        if ((access & Opcodes.ACC_PUBLIC) != 0) s.append("public ");
        if ((access & Opcodes.ACC_PRIVATE) != 0) s.append("private ");
        if ((access & Opcodes.ACC_PROTECTED) != 0) s.append("protected ");
        if ((access & Opcodes.ACC_STATIC) != 0) s.append("static ");
        if ((access & Opcodes.ACC_FINAL) != 0) s.append("final ");
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) s.append("synchronized ");
        if ((access & Opcodes.ACC_VOLATILE) != 0) s.append("volatile ");
        if ((access & Opcodes.ACC_ABSTRACT) != 0) s.append("abstract ");
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) s.append("synthetic ");
        if ((access & Opcodes.ACC_VARARGS) != 0) s.append("varargs ");
        return s.toString().trim();
    }

    static String normalizeName(String name) {
        Matcher m = LAMBDA.matcher(name);
        return m.matches() ? "lambda$" + m.group(1) + "$#" : name;
    }

    static boolean isSwitchMapClass(ClassNode c) {
        if (c.fields.isEmpty()) return false;
        for (FieldNode f : c.fields) if (!f.name.startsWith("$SwitchMap$")) return false;
        return true;
    }

    static Map<String, Map<Integer, String>> switchMaps(Map<String, ClassNode> classes) {
        Map<String, Map<Integer, String>> maps = new HashMap<>();
        for (ClassNode c : classes.values()) {
            if (!isSwitchMapClass(c)) continue;
            for (MethodNode m : c.methods) {
                if (!m.name.equals("<clinit>")) continue;
                String field = null;
                String constant = null;
                Integer value = null;
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC) {
                        if (f.name.startsWith("$SwitchMap$")) field = c.name + "." + f.name;
                        else constant = f.owner + "." + f.name;
                    } else if (insn.getOpcode() >= Opcodes.ICONST_M1 && insn.getOpcode() <= Opcodes.ICONST_5) {
                        value = insn.getOpcode() - Opcodes.ICONST_0;
                    } else if (insn instanceof IntInsnNode i
                            && (i.getOpcode() == Opcodes.BIPUSH || i.getOpcode() == Opcodes.SIPUSH)) {
                        value = i.operand;
                    } else if (insn.getOpcode() == Opcodes.IASTORE && field != null && constant != null
                            && value != null) {
                        maps.computeIfAbsent(field, k -> new HashMap<>()).put(value, constant);
                        constant = null;
                        value = null;
                    }
                }
            }
        }
        return maps;
    }

    static String body(MethodNode m, Mapper mp, Map<String, Map<Integer, String>> switches) {
        Map<LabelNode, Integer> labels = new HashMap<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof LabelNode l) labels.put(l, labels.size());
        }
        StringBuilder out = new StringBuilder();
        out.append("ACCESS-STATIC=").append((m.access & Opcodes.ACC_STATIC) != 0).append('\n');
        Map<Integer, String> pendingSwitch = null;
        for (AbstractInsnNode insn : m.instructions) {
            int op = insn.getOpcode();
            String name = op >= 0 ? Printer.OPCODES[op] : "";
            if (insn instanceof LabelNode l) {
                out.append("L").append(labels.get(l)).append(":\n");
                continue;
            }
            if (insn instanceof LineNumberNode || insn instanceof FrameNode) continue;
            out.append("  ").append(name);
            if (insn instanceof IntInsnNode i) out.append(' ').append(i.operand);
            else if (insn instanceof VarInsnNode v) out.append(' ').append(v.var);
            else if (insn instanceof TypeInsnNode t) out.append(' ').append(mp.typeOrArray(t.desc));
            else if (insn instanceof FieldInsnNode f) {
                if (f.name.startsWith("$SwitchMap$")) {
                    pendingSwitch = switches.get(f.owner + "." + f.name);
                    out.append(" SWITCHMAP ").append(f.name);
                } else {
                    String[] t = mp.member(f.owner, f.name, f.desc);
                    out.append(' ').append(t[0]).append('.').append(t[1]).append(':').append(mp.desc(f.desc));
                }
            } else if (insn instanceof MethodInsnNode mi) {
                String[] t = mp.member(mi.owner, mi.name, mi.desc);
                out.append(' ').append(t[0]).append('.').append(normalizeName(t[1])).append(mp.desc(mi.desc))
                        .append(mi.itf ? " itf" : "");
            } else if (insn instanceof InvokeDynamicInsnNode d) {
                out.append(' ').append(d.name).append(mp.desc(d.desc)).append(' ').append(handle(d.bsm, mp));
                for (Object arg : d.bsmArgs) out.append(" [").append(constant(arg, mp)).append(']');
            } else if (insn instanceof JumpInsnNode j) out.append(" L").append(labels.get(j.label));
            else if (insn instanceof LdcInsnNode ldc) out.append(' ').append(constant(ldc.cst, mp));
            else if (insn instanceof IincInsnNode inc) out.append(' ').append(inc.var).append(' ').append(inc.incr);
            else if (insn instanceof TableSwitchInsnNode ts) {
                List<String> cases = new ArrayList<>();
                for (int k = 0; k < ts.labels.size(); k++) {
                    cases.add(switchKey(ts.min + k, pendingSwitch) + "->L" + labels.get(ts.labels.get(k)));
                }
                if (pendingSwitch != null) Collections.sort(cases);
                out.append(' ').append(cases).append(" default->L").append(labels.get(ts.dflt));
                pendingSwitch = null;
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                List<String> cases = new ArrayList<>();
                for (int k = 0; k < ls.keys.size(); k++) {
                    cases.add(switchKey(ls.keys.get(k), pendingSwitch) + "->L" + labels.get(ls.labels.get(k)));
                }
                if (pendingSwitch != null) Collections.sort(cases);
                out.append(' ').append(cases).append(" default->L").append(labels.get(ls.dflt));
                pendingSwitch = null;
            } else if (insn instanceof MultiANewArrayInsnNode ma) out.append(' ').append(mp.desc(ma.desc))
                    .append(' ').append(ma.dims);
            out.append('\n');
        }
        for (TryCatchBlockNode t : m.tryCatchBlocks) {
            out.append("  TRY L").append(labels.get(t.start)).append(" L").append(labels.get(t.end))
                    .append(" L").append(labels.get(t.handler)).append(' ')
                    .append(t.type == null ? "any" : mp.type(t.type)).append('\n');
        }
        String result = out.toString();
        for (String[] rule : normalizeRules) result = result.replaceAll(rule[0], rule[1]);
        return result;
    }

    static String switchKey(int key, Map<Integer, String> map) {
        if (map == null) return Integer.toString(key);
        String constant = map.get(key);
        return constant == null ? "?" + key : constant;
    }

    static String handle(Handle h, Mapper mp) {
        String[] t = mp.member(h.getOwner(), h.getName(), h.getDesc());
        return "H" + h.getTag() + ":" + t[0] + "." + normalizeName(t[1]) + mp.desc(h.getDesc());
    }

    static String constant(Object value, Mapper mp) {
        if (value instanceof Type t) {
            return t.getSort() == Type.METHOD ? "T:" + mp.desc(t.getDescriptor()) : "T:" + mp.desc(t.getDescriptor());
        }
        if (value instanceof Handle h) return handle(h, mp);
        if (value instanceof String s) return "\"" + s.replace("\n", "\\n") + "\"";
        return value.getClass().getSimpleName() + ":" + value;
    }

    static Map<String, ClassNode> load(Path root) throws IOException {
        Map<String, ClassNode> classes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                ClassReader reader = new ClassReader(Files.readAllBytes(p));
                ClassNode node = new ClassNode();
                reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                classes.put(node.name, node);
            }
        }
        return classes;
    }

    static void readRules(Path file) throws IOException {
        for (String raw : Files.readAllLines(file)) {
            String line = raw.replaceAll("#.*", "").trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            switch (p[0]) {
                case "class" -> classRules.put(p[1], p[2]);
                case "member" -> memberRules.put(p[1] + "." + p[2], p[3]);
                case "rename" -> renameRules.put(p[1] + "." + p[2], p[3]);
                case "normalize" -> {
                    String[] parts = raw.trim().substring("normalize".length()).trim().split(" => ", 2);
                    // In a rule, \n stands for a line break in the printed body.
                    normalizeRules.add(new String[] {parts[0].replace("\\n", "\n"),
                            parts.length > 1 ? parts[1].replace("\\n", "\n") : ""});
                }
                default -> throw new IllegalArgumentException("bad rule: " + raw);
            }
        }
    }

    static String diff(String before, String after) {
        String[] x = before.split("\n");
        String[] y = after.split("\n");
        int n = x.length, m = y.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = x[i].equals(y[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        StringBuilder out = new StringBuilder();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (x[i].equals(y[j])) { out.append("  ").append(x[i]).append('\n'); i++; j++; }
            else if (lcs[i + 1][j] >= lcs[i][j + 1]) out.append("- ").append(x[i++]).append('\n');
            else out.append("+ ").append(y[j++]).append('\n');
        }
        while (i < n) out.append("- ").append(x[i++]).append('\n');
        while (j < m) out.append("+ ").append(y[j++]).append('\n');
        return out.toString();
    }

    static final class Mapper {
        final boolean active;

        Mapper(boolean active) {
            this.active = active;
        }

        String type(String internalName) {
            if (!active || internalName == null) return internalName;
            String exact = classRules.get(internalName);
            if (exact != null) return exact;
            String best = null;
            for (String key : classRules.keySet()) {
                if (internalName.startsWith(key + "$") && (best == null || key.length() > best.length())) best = key;
            }
            return best == null ? internalName : classRules.get(best) + internalName.substring(best.length());
        }

        String typeOrArray(String desc) {
            return desc.startsWith("[") ? desc(desc) : type(desc);
        }

        String desc(String desc) {
            if (!active || desc == null) return desc;
            Matcher m = OBJECT_TYPE.matcher(desc);
            StringBuilder out = new StringBuilder();
            while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement("L" + type(m.group(1)) + ";"));
            m.appendTail(out);
            return out.toString();
        }

        String[] member(String owner, String name, String desc) {
            if (!active) return new String[] {owner, name};
            String key = owner + "." + name;
            String newOwner = memberRules.get(key);
            if (newOwner == null) {
                Matcher lambda = LAMBDA.matcher(name);
                if (lambda.matches()) newOwner = memberRules.get(owner + "." + lambda.group(1));
            }
            if (newOwner == null) newOwner = typeOrArray(owner);
            String newName = renameRules.getOrDefault(key, name);
            Matcher lambdaName = LAMBDA.matcher(name);
            if (lambdaName.matches() && renameRules.containsKey(owner + "." + lambdaName.group(1))) {
                newName = "lambda$" + renameRules.get(owner + "." + lambdaName.group(1))
                        + name.substring(name.lastIndexOf('$'));
            }
            return new String[] {newOwner, newName};
        }
    }
}
