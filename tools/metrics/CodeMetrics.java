import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DefaultCaseLabelTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Measures the Java sources and fails when they regress past the recorded baseline. A regression
 * is a method newly over the complexity or length limit, or worse than recorded; a file newly over
 * the length limit, or longer than recorded; more inline qualified names or lines over 120
 * characters; or more duplicated code. Every method and file the baseline lists must also carry a
 * reason to stay over its limit. {@code record} rewrites the baseline from the sources and keeps
 * each recorded reason.
 *
 * <p>Usage: {@code CodeMetrics check|record <baseline> <source root>...}
 */
public final class CodeMetrics {
    static final int MAX_COMPLEXITY = 15;
    static final int MAX_METHOD_LINES = 80;
    static final int MAX_FILE_LINES = 1000;
    static final int MAX_LINE_LENGTH = 120;
    static final int CLONE_TOKENS = 100;
    static final double MAX_MAIN_DUPLICATION = 3.0;
    static final double DUPLICATION_SLACK = 0.1;
    static final Set<String> PACKAGE_ROOTS = Set.of("net", "java", "javax", "org", "com");

    record Method(String file, String name, int complexity, int lines) {}
    record SourceFile(String path, boolean test, int lines, int longLines, int qualifiedNames, List<Integer> tokens) {}

    private final List<Method> methods = new ArrayList<>();
    private final List<SourceFile> files = new ArrayList<>();
    private final Map<String, Integer> tokenIds = new HashMap<>();

    private CodeMetrics() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3 || !Set.of("check", "record").contains(args[0])) {
            throw new IllegalArgumentException("usage: CodeMetrics check|record <baseline> <source root>...");
        }
        CodeMetrics metrics = new CodeMetrics();
        for (int index = 2; index < args.length; index++) {
            metrics.measure(Path.of(args[index]));
        }
        Baseline current = metrics.baseline();
        Path baselinePath = Path.of(args[1]);
        Baseline recorded = Files.exists(baselinePath) ? Baseline.read(baselinePath) : new Baseline();
        if (args[0].equals("record")) {
            recorded.reasons.forEach((key, reason) -> {
                if (current.fileLines.containsKey(key) || current.methods.containsKey(key)) {
                    current.reasons.put(key, reason);
                }
            });
            current.write(baselinePath);
            System.out.println("Recorded " + baselinePath + ": " + current.summary());
            return;
        }
        List<String> regressions = current.regressionsFrom(recorded);
        System.out.println("Code metrics: " + current.summary());
        if (!regressions.isEmpty()) {
            regressions.forEach(regression -> System.err.println("  " + regression));
            System.err.println(regressions.size() + " metric regressions: fix them, or run metricsRecord after a"
                    + " deliberate change and give each new long method or file a reason in"
                    + " tools/metrics/baseline.txt");
            System.exit(1);
        }
    }

    private void measure(Path root) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, Locale.ROOT,
                StandardCharsets.UTF_8);
        JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostic -> {}, List.of("-proc:none"),
                null, fileManager.getJavaFileObjectsFromPaths(paths));
        SourcePositions positions = Trees.instance(task).getSourcePositions();
        for (CompilationUnitTree unit : task.parse()) {
            String path = Path.of("").toAbsolutePath().relativize(Path.of(unit.getSourceFile().toUri()))
                    .toString().replace('\\', '/');
            String source = unit.getSourceFile().getCharContent(true).toString();
            String[] lines = source.split("\r?\n", -1);
            int longLines = (int) Stream.of(lines).filter(line -> line.length() > MAX_LINE_LENGTH).count();
            int[] qualifiedNames = {0};
            for (Tree type : unit.getTypeDecls()) {
                if (type instanceof ClassTree declaration) {
                    measureType(unit, positions, path, declaration, "", qualifiedNames);
                }
            }
            int body = unit.getTypeDecls().isEmpty() ? source.length()
                    : (int) positions.getStartPosition(unit, unit.getTypeDecls().get(0));
            files.add(new SourceFile(path, path.contains("src/test/"), lines.length, longLines, qualifiedNames[0],
                    tokens(source.substring(body))));
        }
    }

    private void measureType(CompilationUnitTree unit, SourcePositions positions, String path, ClassTree type,
                             String outer, int[] qualifiedNames) {
        String name = outer.isEmpty() ? type.getSimpleName().toString() : outer + "." + type.getSimpleName();
        LineMap lineMap = unit.getLineMap();
        for (Tree member : type.getMembers()) {
            if (member instanceof ClassTree nested) {
                measureType(unit, positions, path, nested, name, qualifiedNames);
                continue;
            }
            qualifiedNames[0] += qualifiedNames(member);
            if (member instanceof MethodTree method && method.getBody() != null) {
                long start = positions.getStartPosition(unit, method);
                long end = positions.getEndPosition(unit, method);
                int lines = (int) (lineMap.getLineNumber(end) - lineMap.getLineNumber(start) + 1);
                String methodName = method.getName().contentEquals("<init>") ? "<init>" : method.getName().toString();
                methods.add(new Method(path, name + "." + methodName + "/" + method.getParameters().size(),
                        complexity(method), lines));
            }
        }
    }

    /** Cyclomatic complexity: one, plus each branch, loop, case, catch, conditional and short-circuit. */
    private static int complexity(MethodTree method) {
        int[] complexity = {1};
        new TreeScanner<Void, Void>() {
            @Override public Void visitClass(ClassTree node, Void unused) {
                return null;
            }
            @Override public Void visitIf(IfTree node, Void unused) {
                complexity[0]++;
                return super.visitIf(node, unused);
            }
            @Override public Void visitForLoop(ForLoopTree node, Void unused) {
                complexity[0]++;
                return super.visitForLoop(node, unused);
            }
            @Override public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
                complexity[0]++;
                return super.visitEnhancedForLoop(node, unused);
            }
            @Override public Void visitWhileLoop(WhileLoopTree node, Void unused) {
                complexity[0]++;
                return super.visitWhileLoop(node, unused);
            }
            @Override public Void visitDoWhileLoop(DoWhileLoopTree node, Void unused) {
                complexity[0]++;
                return super.visitDoWhileLoop(node, unused);
            }
            @Override public Void visitCase(CaseTree node, Void unused) {
                if (!node.getLabels().stream().allMatch(label ->
                        label instanceof DefaultCaseLabelTree)) complexity[0]++;
                return super.visitCase(node, unused);
            }
            @Override public Void visitCatch(CatchTree node, Void unused) {
                complexity[0]++;
                return super.visitCatch(node, unused);
            }
            @Override public Void visitConditionalExpression(ConditionalExpressionTree node, Void unused) {
                complexity[0]++;
                return super.visitConditionalExpression(node, unused);
            }
            @Override public Void visitBinary(BinaryTree node, Void unused) {
                if (node.getKind() == Tree.Kind.CONDITIONAL_AND || node.getKind() == Tree.Kind.CONDITIONAL_OR) {
                    complexity[0]++;
                }
                return super.visitBinary(node, unused);
            }
        }.scan(method.getBody(), null);
        return complexity[0];
    }

    /** Names written out in full, such as {@code java.util.List}, where an import would do. */
    private static int qualifiedNames(Tree tree) {
        int[] count = {0};
        new TreeScanner<Void, Void>() {
            @Override public Void visitClass(ClassTree node, Void unused) {
                return null;
            }
            @Override public Void visitImport(ImportTree node, Void unused) {
                return null;
            }
            @Override public Void visitPackage(PackageTree node, Void unused) {
                return null;
            }
            @Override public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                String text = node.toString();
                int dot = text.indexOf('.');
                if (dot > 0 && PACKAGE_ROOTS.contains(text.substring(0, dot))
                        && text.chars().filter(c -> c == '.').count() >= 2) {
                    count[0]++;
                    return null;
                }
                return super.visitMemberSelect(node, unused);
            }
        }.scan(tree, null);
        return count[0];
    }

    /** Java tokens without comments or whitespace; literals and names keep their text. */
    private List<Integer> tokens(String source) {
        List<Integer> tokens = new ArrayList<>();
        int at = 0;
        while (at < source.length()) {
            int end = skipped(source, at);
            if (end == at) {
                end = tokenEnd(source, at);
                tokens.add(tokenIds.computeIfAbsent(source.substring(at, end), key -> tokenIds.size()));
            }
            at = end;
        }
        return tokens;
    }

    /** The end of the whitespace or comment at {@code at}, or {@code at} when there is none. */
    private static int skipped(String source, int at) {
        if (Character.isWhitespace(source.charAt(at))) return at + 1;
        if (source.startsWith("//", at)) return endOf(source, source.indexOf('\n', at), 0);
        if (source.startsWith("/*", at)) return endOf(source, source.indexOf("*/", at + 2), 2);
        return at;
    }

    /** The end of the token at {@code at}: a text block, string, character, name or number, or one symbol. */
    private static int tokenEnd(String source, int at) {
        char c = source.charAt(at);
        if (source.startsWith("\"\"\"", at)) return endOf(source, source.indexOf("\"\"\"", at + 3), 3);
        int end = at + 1;
        if (c == '"' || c == '\'') {
            while (end < source.length() && source.charAt(end) != c) end += source.charAt(end) == '\\' ? 2 : 1;
            return Math.min(source.length(), end + 1);
        }
        if (Character.isJavaIdentifierStart(c) || Character.isDigit(c)) {
            while (end < source.length() && (Character.isJavaIdentifierPart(source.charAt(end))
                    || source.charAt(end) == '.' && Character.isDigit(c))) end++;
        }
        return end;
    }

    /** Just past a delimiter found at {@code found}, or the end of the source when it was not found. */
    private static int endOf(String source, int found, int length) {
        return found < 0 ? source.length() : found + length;
    }

    /** Percent of tokens inside a run of at least {@link #CLONE_TOKENS} that appears twice. */
    private double duplication(boolean test) {
        List<SourceFile> group = files.stream().filter(file -> file.test() == test).toList();
        Map<Long, List<int[]>> windows = new HashMap<>();
        long base = 1_000_003L;
        long power = 1;
        for (int index = 0; index < CLONE_TOKENS; index++) power *= base;
        for (int file = 0; file < group.size(); file++) {
            List<Integer> tokens = group.get(file).tokens();
            if (tokens.size() < CLONE_TOKENS) continue;
            long hash = 0;
            for (int index = 0; index < CLONE_TOKENS; index++) hash = hash * base + tokens.get(index);
            for (int start = 0; ; start++) {
                windows.computeIfAbsent(hash, key -> new ArrayList<>(2)).add(new int[] {file, start});
                if (start + CLONE_TOKENS >= tokens.size()) break;
                hash = hash * base + tokens.get(start + CLONE_TOKENS) - power * tokens.get(start);
            }
        }
        Map<Integer, BitSet> duplicated = new HashMap<>();
        for (List<int[]> same : windows.values()) {
            if (same.size() < 2) continue;
            for (int[] window : same) {
                List<Integer> tokens = group.get(window[0]).tokens();
                boolean equalElsewhere = same.stream().anyMatch(other -> (other[0] != window[0]
                        || Math.abs(other[1] - window[1]) >= CLONE_TOKENS)
                        && group.get(other[0]).tokens().subList(other[1], other[1] + CLONE_TOKENS)
                        .equals(tokens.subList(window[1], window[1] + CLONE_TOKENS)));
                if (equalElsewhere) {
                    duplicated.computeIfAbsent(window[0], key -> new BitSet()).set(window[1], window[1] + CLONE_TOKENS);
                }
            }
        }
        long total = group.stream().mapToLong(file -> file.tokens().size()).sum();
        long copied = duplicated.values().stream().mapToLong(BitSet::cardinality).sum();
        return total == 0 ? 0 : Math.round(1000.0 * copied / total) / 10.0;
    }

    private Baseline baseline() {
        Baseline baseline = new Baseline();
        baseline.mainDuplication = duplication(false);
        baseline.testDuplication = duplication(true);
        baseline.qualifiedNames = files.stream().mapToInt(SourceFile::qualifiedNames).sum();
        baseline.longLines = files.stream().mapToInt(SourceFile::longLines).sum();
        for (Method method : methods) {
            if (method.complexity() > MAX_COMPLEXITY || method.lines() > MAX_METHOD_LINES) {
                baseline.methods.merge(method.file() + " " + method.name(),
                        new int[] {method.complexity(), method.lines()},
                        (a, b) -> new int[] {Math.max(a[0], b[0]), Math.max(a[1], b[1])});
            }
        }
        for (SourceFile file : files) {
            if (file.lines() > MAX_FILE_LINES) baseline.fileLines.put(file.path(), file.lines());
        }
        return baseline;
    }

    /** The recorded limits: one line each, in a file people read and review. */
    static final class Baseline {
        double mainDuplication;
        double testDuplication;
        int qualifiedNames;
        int longLines;
        final Map<String, int[]> methods = new TreeMap<>();
        final Map<String, Integer> fileLines = new TreeMap<>();
        /** Why each listed method or file stays over its limit, keyed like {@link #methods} and {@link #fileLines}. */
        final Map<String, String> reasons = new TreeMap<>();

        static Baseline read(Path path) throws IOException {
            Baseline baseline = new Baseline();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] words = line.strip().split("\\s+");
                switch (words[0]) {
                    case "main-duplication-percent" -> baseline.mainDuplication = Double.parseDouble(words[1]);
                    case "test-duplication-percent" -> baseline.testDuplication = Double.parseDouble(words[1]);
                    case "qualified-names" -> baseline.qualifiedNames = Integer.parseInt(words[1]);
                    case "lines-over-120" -> baseline.longLines = Integer.parseInt(words[1]);
                    case "method" -> {
                        String key = words[1] + " " + words[2];
                        baseline.methods.put(key, new int[] {Integer.parseInt(words[3]), Integer.parseInt(words[4])});
                        baseline.putReason(key, words, 5);
                    }
                    case "file" -> {
                        baseline.fileLines.put(words[1], Integer.parseInt(words[2]));
                        baseline.putReason(words[1], words, 3);
                    }
                    default -> { }
                }
            }
            return baseline;
        }

        private void putReason(String key, String[] words, int from) {
            if (words.length > from) reasons.put(key, String.join(" ", List.of(words).subList(from, words.length)));
        }

        void write(Path path) throws IOException {
            StringBuilder text = new StringBuilder();
            text.append("# Limits the sources may not regress past; check fails on a regression.\n");
            text.append("# A listed method or file may shrink, not grow. Rewrite with gradlew metricsRecord.\n");
            text.append("# method <file> <Type.method/arity> <complexity> <lines> <reason>: over ")
                    .append(MAX_COMPLEXITY).append(" complexity or ").append(MAX_METHOD_LINES).append(" lines\n");
            text.append("# file <file> <lines> <reason>: over ").append(MAX_FILE_LINES).append(" lines\n");
            text.append("main-duplication-percent ").append(mainDuplication).append('\n');
            text.append("test-duplication-percent ").append(testDuplication).append('\n');
            text.append("qualified-names ").append(qualifiedNames).append('\n');
            text.append("lines-over-120 ").append(longLines).append('\n');
            fileLines.forEach((file, lines) -> text.append("file ").append(file).append(' ').append(lines)
                    .append(' ').append(reasons.getOrDefault(file, "TODO: why this file is long")).append('\n'));
            methods.forEach((method, values) -> text.append("method ").append(method).append(' ')
                    .append(values[0]).append(' ').append(values[1]).append(' ')
                    .append(reasons.getOrDefault(method, "TODO: why this method stays")).append('\n'));
            Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
        }

        List<String> regressionsFrom(Baseline recorded) {
            List<String> regressions = new ArrayList<>();
            if (mainDuplication > MAX_MAIN_DUPLICATION) {
                regressions.add("main duplication " + mainDuplication + "% is over " + MAX_MAIN_DUPLICATION + "%");
            }
            if (mainDuplication > recorded.mainDuplication + DUPLICATION_SLACK) {
                regressions.add("main duplication rose to " + mainDuplication + "% from " + recorded.mainDuplication
                        + "%");
            }
            if (testDuplication > recorded.testDuplication + DUPLICATION_SLACK) {
                regressions.add("test duplication rose to " + testDuplication + "% from " + recorded.testDuplication
                        + "%");
            }
            if (qualifiedNames > recorded.qualifiedNames) {
                regressions.add("inline qualified names rose to " + qualifiedNames + " from "
                        + recorded.qualifiedNames);
            }
            if (longLines > recorded.longLines) {
                regressions.add("lines over 120 characters rose to " + longLines + " from " + recorded.longLines);
            }
            methods.forEach((method, values) -> {
                int[] limit = recorded.methods.get(method);
                if (limit == null) {
                    regressions.add("new long or complex method " + method + ": complexity " + values[0]
                            + ", " + values[1] + " lines (limits " + MAX_COMPLEXITY + ", " + MAX_METHOD_LINES + ")");
                } else if (values[0] > limit[0] || values[1] > limit[1]) {
                    regressions.add(method + " grew to complexity " + values[0] + ", " + values[1]
                            + " lines from " + limit[0] + ", " + limit[1]);
                }
            });
            fileLines.forEach((file, lines) -> {
                Integer limit = recorded.fileLines.get(file);
                if (limit == null) {
                    regressions.add("new file over " + MAX_FILE_LINES + " lines: " + file + " (" + lines + ")");
                } else if (lines > limit) {
                    regressions.add(file + " grew to " + lines + " lines from " + limit);
                }
            });
            Stream.concat(methods.keySet().stream(), fileLines.keySet().stream())
                    .filter(key -> recorded.methods.containsKey(key) || recorded.fileLines.containsKey(key))
                    .filter(key -> recorded.reasons.getOrDefault(key, "TODO").startsWith("TODO"))
                    .forEach(key -> regressions.add(key + " has no recorded reason to stay over its limit"));
            return regressions;
        }

        String summary() {
            return String.format(Locale.ROOT, "%d methods over limits, %d files over %d lines, duplication main %.1f%%"
                            + " test %.1f%%, %d qualified names, %d lines over %d characters",
                    methods.size(), fileLines.size(), MAX_FILE_LINES, mainDuplication, testDuplication,
                    qualifiedNames, longLines, MAX_LINE_LENGTH);
        }
    }
}
