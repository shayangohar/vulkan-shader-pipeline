package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, source-agnostic preprocessor for the wrapper patterns used by
 * OptiFine and Iris packs. It expands includes and removes inactive branches
 * before the pack interface is inspected or converted.
 */
public final class ShaderSourcePreprocessor {
    private static final int MAX_INCLUDE_DEPTH = 32;
    private static final int MAX_EXPANDED_FILES = 256;
    private static final int MAX_EMITTED_LINES = 200_000;
    private static final int MAX_EXPANDED_CHARS = 4 * 1024 * 1024;
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#(\\w+)(?:\\s+(.*?))?\\s*$");
    private static final Pattern INCLUDE = Pattern.compile("^[<\"]([^>\"]+)[>\"]$");
    private static final Pattern DEFINE = Pattern.compile("^([A-Za-z_]\\w*)(?:\\s+(.*))?$");
    private static final Pattern FUNCTION_DEFINE = Pattern.compile(
            "^([A-Za-z_]\\w*)\\s*\\([^)]*\\)(?:\\s+(.*))?$");

    private ShaderSourcePreprocessor() {}

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
            context.process(source, sourceFile, 0);
            try {
                context.conditionState.finish();
            } catch (RuntimeException failure) {
                throw new PreparationFailure("PREPROCESSOR_CONDITION_UNSUPPORTED");
            }
            return new Result(context.output.toString(),
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
        private final Set<String> lockedUndefined = new java.util.TreeSet<>();
        private final Set<Path> includeStack = new HashSet<>();
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
            this.lockedMacros = new java.util.TreeMap<>();
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

        private void process(String source, Path sourceFile, int depth) throws IOException {
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
            try {
                String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
                for (String line : lines) {
                    processLine(line, sourceFile, depth);
                }
            } finally {
                if (normalized != null) {
                    includeStack.remove(normalized);
                }
            }
        }

        private void processLine(String line, Path sourceFile, int depth) throws IOException {
            Matcher matcher = DIRECTIVE.matcher(line);
            if (!matcher.matches()) {
                if (active()) {
                    emitLockedDefinitions();
                    emit(line);
                }
                return;
            }

            String directive = matcher.group(1).toLowerCase();
            String argument = stripDirectiveComment(
                    matcher.group(2) == null ? "" : matcher.group(2)).trim();
            switch (directive) {
                case "if" -> pushCondition(argument);
                case "ifdef" -> pushCondition("defined(" + argument + ")");
                case "ifndef" -> pushCondition("!defined(" + argument + ")");
                case "elif" -> switchCondition(argument);
                case "else" -> switchElse();
                case "endif" -> popCondition();
                case "define" -> define(argument);
                case "undef" -> {
                    if (active()) {
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
                }
                case "include" -> include(argument, sourceFile, depth);
                case "version", "extension", "pragma", "line" -> {
                    if (active()) {
                        emit(line);
                        if (directive.equals("version")) {
                            emitLockedDefinitions();
                        }
                    }
                }
                default -> {
                    if (active()) {
                        throw new PreparationFailure("PREPROCESSOR_DIRECTIVE_UNSUPPORTED:" + directive);
                    }
                }
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
            process(Files.readString(include, StandardCharsets.UTF_8), include, depth + 1);
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
            String definition = value == null || value.isBlank() ? "1" : normalize(value);
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

        private void pushCondition(String expression) {
            applyCondition("if", expression);
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
                conditionState.apply(directive, expression);
            } catch (RuntimeException e) {
                throw new PreparationFailure("PREPROCESSOR_CONDITION_UNSUPPORTED");
            }
        }

        private static String stripDirectiveComment(String argument) {
            int lineComment = argument.indexOf("//");
            return lineComment >= 0 ? argument.substring(0, lineComment) : argument;
        }
    }

    private static final class PreparationFailure extends RuntimeException {
        private final String deviation;

        private PreparationFailure(String deviation) {
            this.deviation = deviation;
        }
    }

}
