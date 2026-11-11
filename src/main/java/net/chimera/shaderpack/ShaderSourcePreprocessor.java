package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, source-agnostic preprocessor for the wrapper patterns used by
 * OptiFine and Iris packs. It expands includes, removes inactive branches and
 * then expands every macro ({@link GlslMacroExpander}) before the pack
 * interface is inspected or converted, as Iris preprocesses before it reads a
 * program.
 */
public final class ShaderSourcePreprocessor {
    private static final int MAX_INCLUDE_DEPTH = 32;
    private static final int MAX_EXPANDED_FILES = 256;
    private static final int MAX_EMITTED_LINES = 200_000;
    private static final int MAX_EXPANDED_CHARS = 4 * 1024 * 1024;
    // A directive name ends at the first non-word character, as JCPP tokenizes it: Escheridia closes
    // its include guard with "#endif//CLOUDS_GLSL".
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(\\w+)(.*?)\\s*$");
    private static final Pattern INCLUDE = Pattern.compile("^[<\"]([^>\"]+)[>\"]$");
    private static final Pattern DEFINE = Pattern.compile("^([A-Za-z_]\\w*)(?:\\s+(.*))?$");
    // A macro is function-like only when "(" follows its name directly, as in C: Noble's
    // "#define ANIMATIONS_ENABLED (WAVING_PLANTS == 1 || ...)" is an object-like macro.
    private static final Pattern FUNCTION_DEFINE = Pattern.compile(
            "^([A-Za-z_]\\w*)\\([^)]*\\)(?:\\s*(.*))?$");

    /** Deviations that only record a fact the preprocessor handled; every other one fails the source. */
    private static final List<String> WARNINGS = List.of(
            "PREPROCESSOR_MACRO_REDEFINED:", "PREPROCESSOR_UNMATCHED_CONDITIONAL:", "PREPROCESSOR_DIRECTIVE_REPORTED:");

    private ShaderSourcePreprocessor() {}

    /** True when a deviation of a prepared source only records a fact (a redefinition, a stray #endif). */
    public static boolean isWarning(String deviation) {
        return deviation != null && WARNINGS.stream().anyMatch(deviation::startsWith);
    }

    static Result prepare(Path shadersRoot, Path sourceFile, String source) {
        return prepare(shadersRoot, sourceFile, source, Map.of());
    }

    static Result prepare(
            Path shadersRoot,
            Path sourceFile,
            String source,
            Map<String, String> initialMacros
    ) {
        return prepare(shadersRoot, sourceFile, source, initialMacros, Set.of());
    }

    static Result prepare(
            Path shadersRoot,
            Path sourceFile,
            String source,
            Map<String, String> initialMacros,
            Set<String> lockedMacros
    ) {
        if (source == null) {
            return new Result(null, List.of("SOURCE_PREPARATION_FAILED"), List.of());
        }
        Context context = new Context(shadersRoot, initialMacros, lockedMacros);
        try {
            context.process(source, sourceFile);
            // Iris's preprocessor (JCPP) ends the source with a conditional still open and keeps what
            // it emitted: Bloop's TAA program never closes its #ifdef TAA.
            if (context.conditionState.open()) {
                context.deviations.add("PREPROCESSOR_UNMATCHED_CONDITIONAL:unterminated");
            }
            String expanded;
            try {
                expanded = GlslMacroExpander.expand(context.output.toString());
            } catch (IllegalArgumentException failure) {
                throw new PreparationFailure("PREPROCESSOR_MACRO_EXPANSION_FAILED");
            }
            return new Result(expanded,
                    List.copyOf(new TreeSet<>(context.deviations)),
                    List.copyOf(new TreeSet<>(context.dependencies)));
        } catch (PreparationFailure e) {
            return new Result(null, List.of(e.deviation), List.of());
        } catch (IOException e) {
            return new Result(null, List.of("SOURCE_INCLUDE_UNRESOLVED"), List.of());
        }
    }

    /** Runtime bridge for load-time stages discovered outside PackProgram. */
    public static Result prepareForRuntime(
            Path shadersRoot,
            Path sourceFile,
            String source,
            Map<String, String> initialMacros
    ) {
        return prepare(shadersRoot, sourceFile, source, initialMacros);
    }

    public static Result prepareForRuntime(
            Path shadersRoot,
            Path sourceFile,
            String source,
            Map<String, String> initialMacros,
            Set<String> lockedMacros
    ) {
        return prepare(shadersRoot, sourceFile, source, initialMacros, lockedMacros);
    }

    public record Result(String source, List<String> deviations, List<String> dependencies) {
        Result(String source, List<String> deviations) {
            this(source, deviations, List.of());
        }

        public Result {
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
            dependencies = dependencies == null ? List.of() : dependencies.stream().distinct().sorted().toList();
        }

        boolean successful() {
            return source != null;
        }
    }

    private static final class Context {
        private final Path shadersRoot;
        private final StringBuilder output = new StringBuilder();
        private final PackConditionals.State conditionState;
        private final Map<String, String> lockedMacros;
        private final Set<String> lockedUndefined = new TreeSet<>();
        private final Set<Path> includeStack = new HashSet<>();
        private final LineReader reader = new LineReader();
        private final Set<String> deviations = new TreeSet<>();
        private final Set<String> dependencies = new TreeSet<>();
        private int expandedFiles;
        private int emittedLines;
        private int emittedChars;
        private boolean lockedDefinitionsEmitted;

        private Context(
                Path shadersRoot,
                Map<String, String> initialMacros,
                Set<String> lockedMacroNames
        ) {
            this.shadersRoot = shadersRoot == null ? Path.of(".").toAbsolutePath().normalize()
                    : shadersRoot.toAbsolutePath().normalize();
            this.conditionState = new PackConditionals.State(initialMacros);
            this.lockedMacros = new TreeMap<>();
            if (lockedMacroNames != null) {
                for (String name : lockedMacroNames) {
                    if (name != null && name.matches("[A-Za-z_]\\w*")) {
                        String value = initialMacros == null ? null : initialMacros.get(name);
                        if (value != null && !value.isBlank()) {
                            this.lockedMacros.put(name, value.trim());
                        } else {
                            // A locked name without a value is an option
                            // switched off: the source may not define it.
                            this.lockedUndefined.add(name);
                        }
                    }
                }
            }
        }

        /** Reads {@code source} and each include it reaches, an include where its directive stood. */
        private void process(String source, Path sourceFile) throws IOException {
            enter(source, sourceFile, 0);
            for (LogicalLine line = reader.next(); line != null; line = reader.next()) {
                processLine(line.text(), line.file(), line.depth(), line.inComment());
            }
        }

        /**
         * Reads {@code source} next, within the include depth, file and cycle limits. Its file
         * leaves the include stack once its last line is read.
         */
        private void enter(String source, Path sourceFile, int depth) {
            if (depth > MAX_INCLUDE_DEPTH) {
                throw new PreparationFailure("SOURCE_INCLUDE_DEPTH_EXCEEDED");
            }
            if (++expandedFiles > MAX_EXPANDED_FILES) {
                throw new PreparationFailure("SOURCE_EXPANSION_BUDGET_EXCEEDED");
            }
            Path normalized = sourceFile == null ? null : sourceFile.toAbsolutePath().normalize();
            if (normalized != null && !includeStack.add(normalized)) {
                throw new PreparationFailure("SOURCE_INCLUDE_CYCLE");
            }
            reader.push(source, sourceFile, depth, () -> {
                if (normalized != null) includeStack.remove(normalized);
            });
        }

        private void processLine(String line, Path sourceFile, int depth, boolean inComment) throws IOException {
            Matcher matcher = inComment ? null : directive(line);
            if (matcher == null) {
                if (active()) {
                    emitLockedDefinitions();
                    emit(line);
                }
                return;
            }

            String directive = matcher.group(1).toLowerCase();
            String argument = PackConditionals.withoutComments(
                    matcher.group(2) == null ? "" : matcher.group(2)).trim();
            switch (directive) {
                case "if", "ifdef", "ifndef" -> applyCondition(directive, argument);
                case "elif" -> switchCondition(argument);
                case "else" -> switchElse();
                case "endif" -> popCondition();
                case "define" -> define(argument);
                case "undef" -> undefine(argument);
                case "include" -> include(argument, sourceFile, depth);
                case "version", "extension", "pragma", "line" -> passThrough(directive, line);
                default -> otherDirective(directive);
            }
        }

        /**
         * A directive with no handler. Iris's preprocessor (JCPP) reports #error and #warning and goes
         * on without the line (Photon's prepare stage #errors with cloud shadows off, which no switch
         * disables); any other active one fails the source.
         */
        private void otherDirective(String directive) {
            if (!active()) return;
            if (directive.equals("error") || directive.equals("warning")) {
                deviations.add("PREPROCESSOR_DIRECTIVE_REPORTED:" + directive);
                return;
            }
            throw new PreparationFailure("PREPROCESSOR_DIRECTIVE_UNSUPPORTED:" + directive);
        }

        private void undefine(String argument) {
            if (!active()) {
                return;
            }
            if (!argument.matches("[A-Za-z_]\\w*")) {
                throw new PreparationFailure("PREPROCESSOR_DEFINE_UNSUPPORTED");
            }
            if (lockedMacros.containsKey(argument)) {
                emitLockedDefinitions();
                return;
            }
            conditionState.undefine(argument);
            emit("#undef " + argument);
        }

        /** Keeps a version, extension, pragma or line directive as written; locked macros follow #version. */
        private void passThrough(String directive, String line) {
            if (!active()) {
                return;
            }
            emit(line);
            if (directive.equals("version")) {
                emitLockedDefinitions();
            }
        }

        private void include(String argument, Path sourceFile, int depth) throws IOException {
            if (!active()) {
                return;
            }
            emitLockedDefinitions();
            Matcher matcher = INCLUDE.matcher(argument);
            if (!matcher.matches()) {
                throw new PreparationFailure("SOURCE_INCLUDE_UNRESOLVED");
            }
            Path include = resolveInclude(sourceFile, matcher.group(1));
            if (!Files.isRegularFile(include)) {
                throw new PreparationFailure("SOURCE_INCLUDE_UNRESOLVED");
            }
            dependencies.add(relativePath(include));
            emit("");
            enter(Files.readString(include, StandardCharsets.UTF_8), include, depth + 1);
        }

        private void emit(String line) {
            if (++emittedLines > MAX_EMITTED_LINES
                    || emittedChars + line.length() + 1 > MAX_EXPANDED_CHARS) {
                throw new PreparationFailure("SOURCE_EXPANSION_BUDGET_EXCEEDED");
            }
            output.append(line).append('\n');
            emittedChars += line.length() + 1;
        }

        private String relativePath(Path path) {
            return shadersRoot.relativize(path.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        }

        private Path resolveInclude(Path sourceFile, String name) {
            Path include;
            if (name.startsWith("/")) {
                include = shadersRoot.resolve(name.substring(1));
            } else {
                Path base = sourceFile == null || sourceFile.getParent() == null
                        ? shadersRoot : sourceFile.getParent();
                include = base.resolve(name);
            }
            include = include.normalize().toAbsolutePath();
            if (!include.startsWith(shadersRoot)) {
                throw new PreparationFailure("SOURCE_INCLUDE_UNSAFE");
            }
            return include;
        }

        private void define(String argument) {
            if (!active()) {
                return;
            }
            Matcher function = FUNCTION_DEFINE.matcher(argument);
            if (function.matches()) {
                emitDefinition(function.group(1), argument, normalize(argument));
                return;
            }
            Matcher matcher = DEFINE.matcher(argument);
            if (!matcher.matches()) {
                throw new PreparationFailure("PREPROCESSOR_DEFINE_UNSUPPORTED");
            }
            String name = matcher.group(1);
            String value = matcher.group(2);
            String definition = value == null || value.isBlank() ? "" : normalize(value);
            if (lockedMacros.containsKey(name)) {
                emitLockedDefinitions();
                return;
            }
            if (lockedUndefined.contains(name)) {
                return;
            }
            emitDefinition(name, argument, definition);
        }

        private void emitLockedDefinitions() {
            if (lockedDefinitionsEmitted || lockedMacros.isEmpty()) return;
            lockedDefinitionsEmitted = true;
            lockedMacros.forEach((name, value) -> emit("#define " + name + " " + value));
        }

        private void emitDefinition(String name, String source, String definition) {
            String previous = conditionState.define(name, definition);
            if (previous != null && previous.equals(definition)) {
                return;
            }
            if (previous != null) {
                emit("#undef " + name);
                deviations.add("PREPROCESSOR_MACRO_REDEFINED:" + name);
            }
            emit("#define " + source);
        }

        private static String normalize(String value) {
            return value.trim().replaceAll("\\s+", " ");
        }

        private void switchCondition(String expression) {
            applyCondition("elif", expression);
        }

        private void switchElse() {
            applyCondition("else", "");
        }

        private void popCondition() {
            applyCondition("endif", "");
        }

        private boolean active() {
            return conditionState.active();
        }

        private void applyCondition(String directive, String expression) {
            try {
                if (!conditionState.apply(directive, expression)) {
                    deviations.add("PREPROCESSOR_UNMATCHED_CONDITIONAL:" + directive);
                }
            } catch (RuntimeException e) {
                throw new PreparationFailure("PREPROCESSOR_CONDITION_UNSUPPORTED");
            }
        }
    }

    /** One logical line, whether it starts inside a block comment, and the source it starts in. */
    record LogicalLine(String text, boolean inComment, Path file, int depth) {}

    /** {@code source}'s logical lines, read as one source ({@link LineReader}). */
    static List<LogicalLine> logicalLines(String source) {
        LineReader reader = new LineReader();
        reader.push(source, null, 0, () -> { });
        List<LogicalLine> result = new ArrayList<>();
        for (LogicalLine line = reader.next(); line != null; line = reader.next()) result.add(line);
        return result;
    }

    /**
     * Logical lines over a stack of sources. GLSL 3.3 section 3.1: a backslash right before a
     * newline splices the two lines before any directive is parsed. A comment is one space to a C
     * preprocessor, so a block comment a directive line leaves open joins the lines up to its close
     * into that directive, which goes on after it: in JCPP, {@code #if 0 /* note}, then
     * {@code note *}{@code / || 1} on the next line, holds; one that never closes takes the rest of
     * the text. A line that starts inside a block comment is comment text, not a directive: BSL
     * documents its options with "#define FEATURE ..." lines inside comments. A source pushed while
     * another is read is read next, then the rest of the other: Iris pastes an include's text where
     * its directive stood before JCPP reads the program, so a comment, splice or directive an
     * include leaves open goes on into the includer's next lines.
     */
    static final class LineReader {
        private final Deque<Source> sources = new ArrayDeque<>();
        private final StringBuilder logical = new StringBuilder();
        private boolean inComment;
        // A directive whose block comment is still open, and how much of it is scanned.
        private boolean joining;
        private int scanned;
        // The source the pending logical line started in.
        private Source start;

        /** One source's physical lines, and what runs once they are all read. */
        private static final class Source {
            private final String[] lines;
            private final Path file;
            private final int depth;
            private final Runnable exit;
            private int next;

            private Source(String text, Path file, int depth, Runnable exit) {
                this.lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
                this.file = file;
                this.depth = depth;
                this.exit = exit;
            }
        }

        /** Reads {@code text} before the rest of the sources pushed earlier, then runs {@code exit}. */
        void push(String text, Path file, int depth, Runnable exit) {
            sources.push(new Source(text, file, depth, exit));
        }

        /** The next logical line, or null once every source is read. */
        LogicalLine next() {
            for (Source source = current(); source != null; source = current()) {
                if (logical.isEmpty() && !joining) start = source;
                LogicalLine line = accept(source.lines[source.next++]);
                if (line != null) return line;
            }
            if (logical.isEmpty()) return null;
            LogicalLine last = line(logical.toString(), !joining && inComment);
            logical.setLength(0);
            joining = false;
            return last;
        }

        /** The source with a line left to read, after running the exit of each one read through. */
        private Source current() {
            while (!sources.isEmpty() && sources.peek().next >= sources.peek().lines.length) {
                sources.pop().exit.run();
            }
            return sources.peek();
        }

        /** Takes one physical line; returns the logical line it completes, or null. */
        private LogicalLine accept(String line) {
            if (line.endsWith("\\")) {
                logical.append(line, 0, line.length() - 1);
                return null;
            }
            if (joining) {
                // Only the text added since the last scan is scanned, from one character back so a
                // close a backslash splice joined still counts; the join stays linear.
                int from = Math.max(scanned - 1, 0);
                logical.append(line);
                if (inCommentAfter(logical.substring(from), true)) {
                    scanned = logical.length();
                    logical.append(' ');
                    return null;
                }
                joining = false;
                inComment = false;
                LogicalLine directive = line(logical.toString(), false);
                logical.setLength(0);
                return directive;
            }
            if (!logical.isEmpty()) {
                line = logical.append(line).toString();
                logical.setLength(0);
            }
            if (!inComment && startsDirective(line) && inCommentAfter(line, false) && directive(line) != null) {
                joining = true;
                logical.append(line);
                scanned = logical.length();
                logical.append(' ');
                return null;
            }
            LogicalLine result = line(line, inComment);
            inComment = inCommentAfter(line, inComment);
            return result;
        }

        private LogicalLine line(String text, boolean comment) {
            return new LogicalLine(text, comment, start.file, start.depth);
        }
    }

    /** Whether a block comment is still open after {@code line}, given whether one was open before it. */
    static boolean inCommentAfter(String line, boolean inComment) {
        boolean open = inComment;
        int index = 0;
        while (index < line.length()) {
            if (open) {
                int end = line.indexOf("*/", index);
                if (end < 0) return true;
                open = false;
                index = end + 2;
            } else {
                int lineComment = line.indexOf("//", index);
                int block = line.indexOf("/*", index);
                if (block < 0 || (lineComment >= 0 && lineComment < block)) return false;
                open = true;
                index = block + 2;
            }
        }
        return open;
    }

    /** The line's directive match, or null for a line that is not a directive. */
    private static Matcher directive(String line) {
        if (!startsDirective(line)) return null;
        Matcher matcher = DIRECTIVE.matcher(line);
        return matcher.matches() ? matcher : null;
    }

    /**
     * Whether the first character past leading whitespace (regex {@code \s}) is {@code #}. No other
     * line can be a directive, and most lines of a pack are not, so line scanners test this before
     * running a directive pattern.
     */
    static boolean startsDirective(String line) {
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '#') return true;
            if (value != ' ' && value != '\t' && value != '\n' && value != '\u000B' && value != '\f'
                    && value != '\r') {
                return false;
            }
        }
        return false;
    }

    private static final class PreparationFailure extends RuntimeException {
        private final String deviation;

        private PreparationFailure(String deviation) {
            this.deviation = deviation;
        }
    }

}
