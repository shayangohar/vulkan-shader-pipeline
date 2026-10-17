package net.chimera.shaderpack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small token-aware resource use analysis for prepared legacy GLSL.
 *
 * <p>The analysis distinguishes source inventory from entry-point use. It is
 * intentionally conservative for overloaded calls, recursive calls, and
 * source snippets without a main function.</p>
 */
final class GlslResourceUsage {
    private static final Pattern FUNCTION_MACRO = Pattern.compile(
            "(?m)^[\\t ]*#[\\t ]*define[\\t ]+([A-Za-z_]\\w*)"
                    + "[\\t ]*\\([^\\r\\n)]*\\)[\\t ]*([^\\r\\n]*)$");
    private static final Pattern OBJECT_MACRO = Pattern.compile(
            "(?m)^[\\t ]*#[\\t ]*define[\\t ]+([A-Za-z_]\\w*)(?![\\w(])(?![\\t ]*\\()[\\t ]*([^\\r\\n]*)$");
    private static final Set<String> FUNCTION_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "sizeof");
    private static final Set<String> VALUE_TYPES = Set.of(
            "bool", "int", "uint", "float", "double", "vec2", "vec3", "vec4",
            "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "bvec2",
            "bvec3", "bvec4", "mat2", "mat3", "mat4");
    private static final Set<String> STORAGE_QUALIFIERS = Set.of(
            "uniform", "attribute", "varying", "in", "out");
    private static final Set<String> FUNCTION_QUALIFIERS = Set.of(
            "const", "inline", "static", "highp", "mediump", "lowp", "precise",
            "invariant");
    /** Qualifiers GLSL allows between {@code uniform} and an opaque type. */
    private static final Set<String> TYPE_QUALIFIERS = Set.of(
            "highp", "mediump", "lowp", "writeonly", "readonly", "coherent", "volatile",
            "restrict");

    /** Complete token range for one top-level function definition. */
    record FunctionDefinition(
            String name,
            int definitionStart,
            int bodyStart,
            int bodyEnd,
            int definitionEnd
    ) {}

    record Analysis(
            Set<String> declaredSamplers,
            Set<String> referencedSamplers,
            Set<String> liveSamplers,
            Set<String> declaredImages,
            Set<String> liveImages,
            Set<String> declaredStorageBlocks,
            Set<String> liveStorageBlocks,
            List<FunctionDefinition> reachableFunctions,
            List<FunctionDefinition> unreachableFunctions,
            boolean successful,
            List<String> deviations
    ) {
        Analysis {
            declaredSamplers = sorted(declaredSamplers);
            referencedSamplers = sorted(referencedSamplers);
            liveSamplers = sorted(liveSamplers);
            declaredImages = sorted(declaredImages);
            liveImages = sorted(liveImages);
            declaredStorageBlocks = sorted(declaredStorageBlocks);
            liveStorageBlocks = sorted(liveStorageBlocks);
            reachableFunctions = sortedFunctions(reachableFunctions);
            unreachableFunctions = sortedFunctions(unreachableFunctions);
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }
    }

    private GlslResourceUsage() {}

    static Analysis analyze(String source) {
        return analyze(source, Set.of());
    }

    /**
     * Analyzes the source and also treats the supplied names as injected
     * sampler identifiers. Iris image properties can inject sampler names
     * without adding a GLSL uniform declaration. They still need the same
     * entry-point reachability rules as authored samplers.
     */
    static Analysis analyze(String source, Set<String> injectedSamplers) {
        AnalysisKey key = new AnalysisKey(source == null ? "" : source,
                injectedSamplers == null ? Set.of() : Set.copyOf(injectedSamplers));
        synchronized (RECENT) {
            Analysis known = RECENT.get(key);
            if (known != null) return known;
        }
        Analysis analysis = analyzeUncached(key.source(), key.injectedSamplers());
        synchronized (RECENT) {
            RECENT.put(key, analysis);
        }
        return analysis;
    }

    /** One analysis input: the result depends on nothing else. */
    private record AnalysisKey(String source, Set<String> injectedSamplers) {}

    /**
     * The latest analyses. Planning one program asks about the same prepared source many times, once
     * per sampler among others, and each answer would lex and trace the whole program again.
     */
    private static final Map<AnalysisKey, Analysis> RECENT = new LinkedHashMap<>(32, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<AnalysisKey, Analysis> eldest) {
            return size() > 16;
        }
    };

    private static Analysis analyzeUncached(String text, Set<String> injectedSamplers) {
        try {
            List<GlslLexer.Token> tokens = GlslLexer.lex(text);
            Declarations declared = collectDeclarations(tokens, injectedSamplers);
            List<FunctionDefinition> functions = collectFunctions(tokens);
            Map<String, List<FunctionDefinition>> functionsByName = new HashMap<>();
            for (FunctionDefinition function : functions) {
                functionsByName.computeIfAbsent(function.name(), ignored -> new ArrayList<>())
                        .add(function);
            }
            FunctionDefinition main = functionsByName.getOrDefault("main", List.of()).stream()
                    .findFirst().orElse(null);
            Map<String, List<String>> macroCalls = collectFunctionMacros(text);
            Resources referenced = referencedResources(tokens, functions, declared);

            CallGraph graph = new CallGraph(tokens, declared, functionsByName, macroCalls);
            if (main == null) {
                // Interface-only snippets and compute wrappers without a
                // conventional main are retained conservatively.
                graph.keepEverything(functions, referenced);
            } else {
                graph.traceFrom(main, functions);
            }

            List<FunctionDefinition> reachableFunctions = functions.stream()
                    .filter(graph.reachable::contains).toList();
            List<FunctionDefinition> unreachableFunctions = main == null
                    ? List.of()
                    : functions.stream().filter(value -> !graph.reachable.contains(value)).toList();
            Resources names = declared.names();
            return new Analysis(names.samplers(), referenced.samplers(), graph.live.samplers(),
                    names.images(), graph.live.images(), names.storage(),
                    graph.live.storage(), reachableFunctions, unreachableFunctions, true, List.of());
        } catch (RuntimeException failure) {
            return new Analysis(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Set.of(), List.of(), List.of(), false,
                    List.of("RESOURCE_USAGE_UNCLASSIFIABLE"));
        }
    }

    /** Sampler, 3D image and storage block names. */
    private record Resources(Set<String> samplers, Set<String> images, Set<String> storage) {
        Resources() {
            this(new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        }

        /** Adds {@code name} under every kind {@code declared} lists it as. */
        void addDeclared(String name, Resources declared) {
            if (declared.samplers().contains(name)) samplers.add(name);
            if (declared.images().contains(name)) images.add(name);
            if (declared.storage().contains(name)) storage.add(name);
        }

        void addAll(Resources other) {
            samplers.addAll(other.samplers());
            images.addAll(other.images());
            storage.addAll(other.storage());
        }

        Set<String> all() {
            Set<String> all = new HashSet<>(samplers);
            all.addAll(images);
            all.addAll(storage);
            return all;
        }
    }

    /** The resource names a source declares, and the indices of the tokens that declare them. */
    private record Declarations(Resources names, Set<Integer> tokens) {}

    /**
     * Follows calls from main through function bodies and the macros they use, collecting the
     * functions it reaches and the resources their bodies read.
     */
    private static final class CallGraph {
        private final List<GlslLexer.Token> tokens;
        private final Declarations declared;
        private final Map<String, List<FunctionDefinition>> functionsByName;
        private final Map<String, List<String>> macroCalls;
        private final Set<FunctionDefinition> reachable = new HashSet<>();
        private final Resources live = new Resources();
        private final ArrayDeque<FunctionDefinition> pending = new ArrayDeque<>();

        private CallGraph(
                List<GlslLexer.Token> tokens,
                Declarations declared,
                Map<String, List<FunctionDefinition>> functionsByName,
                Map<String, List<String>> macroCalls
        ) {
            this.tokens = tokens;
            this.declared = declared;
            this.functionsByName = functionsByName;
            this.macroCalls = macroCalls;
        }

        /** Treats every function as reachable and every referenced resource as live. */
        private void keepEverything(List<FunctionDefinition> functions, Resources referenced) {
            reachable.addAll(functions);
            live.addAll(referenced);
        }

        private void traceFrom(FunctionDefinition main, List<FunctionDefinition> functions) {
            pending.add(main);
            seedTopLevelCalls(tokens, functions, functionsByName, macroCalls, pending);
            while (!pending.isEmpty()) {
                FunctionDefinition function = pending.removeFirst();
                if (reachable.add(function)) {
                    visitBody(function);
                }
            }
        }

        /** Records the resources one reachable body reads and queues every function it can call. */
        private void visitBody(FunctionDefinition function) {
            Set<Integer> shadowed = localResourceReferences(tokens, function, declared.names());
            for (int index = function.bodyStart(); index < function.bodyEnd(); index++) {
                GlslLexer.Token token = tokens.get(index);
                if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER
                        || declared.tokens().contains(index)) {
                    continue;
                }
                String name = token.text();
                if (!shadowed.contains(index)) {
                    live.addDeclared(name, declared.names());
                }
                int next = GlslLexer.nextSignificant(tokens, index);
                if (next >= 0 && tokens.get(next).symbol("(")
                        && !FUNCTION_KEYWORDS.contains(name)) {
                    pending.addAll(functionsByName.getOrDefault(name, List.of()));
                }
                if (macroCalls.containsKey(name)) {
                    addMacroDependencies(name, macroCalls, functionsByName,
                            pending, new HashSet<>());
                }
            }
        }
    }

    static Set<String> referencedSamplers(String source) {
        return analyze(source).referencedSamplers();
    }

    static Set<String> liveSamplers(String source) {
        return analyze(source).liveSamplers();
    }

    /**
     * Finds canonical uniform names used as values, not calls or declarations.
     * This deliberately does not use a builtin-name denylist. A name is a
     * value reference only when the token context proves that it is one.
     */
    static Set<String> referencedValueIdentifiers(String source, Set<String> candidates) {
        if (source == null || source.isBlank() || candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        try {
            List<GlslLexer.Token> tokens = GlslLexer.lex(source);
            Set<Integer> preprocessor = preprocessorTokens(tokens);
            Set<String> result = new TreeSet<>();
            for (int index = 0; index < tokens.size(); index++) {
                GlslLexer.Token token = tokens.get(index);
                if (token.kind() != GlslLexer.Kind.IDENTIFIER
                        || !candidates.contains(token.text())
                        || preprocessor.contains(index)) {
                    continue;
                }
                int previous = GlslLexer.previousSignificant(tokens, index);
                int next = GlslLexer.nextSignificant(tokens, index);
                if (previous >= 0 && tokens.get(previous).symbol(".")) {
                    continue;
                }
                if (next >= 0 && tokens.get(next).symbol("(")) {
                    continue;
                }
                if (isDeclarationName(tokens, index)) {
                    continue;
                }
                result.add(token.text());
            }
            return Collections.unmodifiableSet(result);
        } catch (RuntimeException failure) {
            return Set.of();
        }
    }

    /** Every declared resource the source reads: in function bodies, unless shadowed, and at file scope. */
    private static Resources referencedResources(
            List<GlslLexer.Token> tokens,
            List<FunctionDefinition> functions,
            Declarations declared
    ) {
        Resources referenced = new Resources();
        for (FunctionDefinition function : functions) {
            collectResourceReferences(tokens, function, declared, referenced);
        }
        collectTopLevelReferences(tokens, functions, declared, referenced);
        return referenced;
    }

    private static void collectResourceReferences(
            List<GlslLexer.Token> tokens,
            FunctionDefinition function,
            Declarations declared,
            Resources referenced
    ) {
        Set<Integer> shadowed = localResourceReferences(tokens, function, declared.names());
        for (int index = function.bodyStart(); index < function.bodyEnd(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER
                    || declared.tokens().contains(index) || shadowed.contains(index)) {
                continue;
            }
            referenced.addDeclared(token.text(), declared.names());
        }
    }

    private static void collectTopLevelReferences(
            List<GlslLexer.Token> tokens,
            List<FunctionDefinition> functions,
            Declarations declared,
            Resources referenced
    ) {
        Set<Integer> functionTokens = functionTokens(functions);
        for (int index = 0; index < tokens.size(); index++) {
            if (functionTokens.contains(index)) continue;
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER
                    || declared.tokens().contains(index)) continue;
            referenced.addDeclared(token.text(), declared.names());
        }
    }

    /** The indices of every token inside a function definition. */
    private static Set<Integer> functionTokens(List<FunctionDefinition> functions) {
        Set<Integer> tokens = new HashSet<>();
        for (FunctionDefinition function : functions) {
            for (int index = function.definitionStart(); index <= function.definitionEnd(); index++) {
                tokens.add(index);
            }
        }
        return tokens;
    }

    private static void seedTopLevelCalls(
            List<GlslLexer.Token> tokens,
            List<FunctionDefinition> functions,
            Map<String, List<FunctionDefinition>> functionsByName,
            Map<String, List<String>> macroCalls,
            ArrayDeque<FunctionDefinition> pending
    ) {
        Set<Integer> functionTokens = functionTokens(functions);
        for (int index = 0; index < tokens.size(); index++) {
            if (functionTokens.contains(index)) continue;
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER
                    || FUNCTION_KEYWORDS.contains(token.text())) continue;
            int next = GlslLexer.nextSignificant(tokens, index);
            boolean call = next >= 0 && tokens.get(next).symbol("(");
            if (call) {
                pending.addAll(functionsByName.getOrDefault(token.text(), List.of()));
            }
            // An object-like macro is used by any reference outside its own #define line.
            if (macroCalls.containsKey(token.text()) && (call || !onDirectiveLine(tokens, index))) {
                addMacroDependencies(token.text(), macroCalls, functionsByName,
                        pending, new HashSet<>());
            }
        }
    }

    private static boolean onDirectiveLine(List<GlslLexer.Token> tokens, int index) {
        GlslLexer.Token first = tokens.get(index);
        for (int cursor = index - 1; cursor >= 0; cursor--) {
            GlslLexer.Token token = tokens.get(cursor);
            if (token.kind() == GlslLexer.Kind.TRIVIA && token.text().contains("\n")) break;
            if (token.significant()) first = token;
        }
        return first.symbol("#");
    }

    /**
     * The functions each macro can call once expanded. A function-like
     * macro calls what its body calls. An object-like macro can name a
     * function its use then calls ({@code #define tonemap tonemap_lottes},
     * then {@code tonemap(x)}), so every identifier in its body counts. The
     * replacement text is tokenized so names in comments and string literals
     * cannot keep an unrelated GLSL function alive.
     */
    private static Map<String, List<String>> collectFunctionMacros(String source) {
        Map<String, List<String>> result = new HashMap<>();
        String defines = defineLines(source == null ? "" : source);
        Matcher objectLike = OBJECT_MACRO.matcher(defines);
        while (objectLike.find()) {
            Set<String> named = new TreeSet<>();
            for (GlslLexer.Token token : GlslLexer.lex(objectLike.group(2))) {
                if (token.kind() == GlslLexer.Kind.IDENTIFIER) named.add(token.text());
            }
            result.put(objectLike.group(1), List.copyOf(named));
        }
        Matcher matcher = FUNCTION_MACRO.matcher(defines);
        while (matcher.find()) {
            Set<String> called = new TreeSet<>();
            List<GlslLexer.Token> replacement = GlslLexer.lex(matcher.group(2));
            for (int index = 0; index < replacement.size(); index++) {
                GlslLexer.Token token = replacement.get(index);
                if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER
                        || FUNCTION_KEYWORDS.contains(token.text())) {
                    continue;
                }
                int next = GlslLexer.nextSignificant(replacement, index);
                if (next >= 0 && replacement.get(next).symbol("(")) {
                    called.add(token.text());
                }
            }
            result.put(matcher.group(1), List.copyOf(called));
        }
        return result;
    }

    /**
     * The whole lines that contain {@code define}, in order, each ended by a newline: the only lines
     * the macro patterns can match. Scanning these instead of a prepared source of thousands of
     * lines gives the same matches in the same order.
     */
    private static String defineLines(String source) {
        StringBuilder lines = new StringBuilder();
        int at = source.indexOf("define");
        while (at >= 0) {
            int start = at;
            while (start > 0 && source.charAt(start - 1) != '\n' && source.charAt(start - 1) != '\r') start--;
            int end = at;
            while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') end++;
            lines.append(source, start, end).append('\n');
            at = source.indexOf("define", end);
        }
        return lines.toString();
    }

    private static void addMacroDependencies(
            String macro,
            Map<String, List<String>> macroCalls,
            Map<String, List<FunctionDefinition>> functionsByName,
            ArrayDeque<FunctionDefinition> pending,
            Set<String> visited
    ) {
        if (!visited.add(macro)) return;
        for (String dependency : macroCalls.getOrDefault(macro, List.of())) {
            pending.addAll(functionsByName.getOrDefault(dependency, List.of()));
            if (macroCalls.containsKey(dependency)) {
                addMacroDependencies(dependency, macroCalls, functionsByName, pending, visited);
            }
        }
    }

    /** The declared resources, starting from the injected sampler names. */
    private static Declarations collectDeclarations(List<GlslLexer.Token> tokens, Set<String> injectedSamplers) {
        Declarations declared = new Declarations(new Resources(), new HashSet<>());
        if (injectedSamplers != null) {
            injectedSamplers.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(declared.names().samplers()::add);
        }
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER) continue;
            if (token.identifier("uniform")) {
                collectUniformDeclaration(tokens, index, declared);
            } else if (isImageType(token.text())) {
                collectImageDeclaration(tokens, index, declared);
            } else if (token.identifier("buffer")) {
                collectStorageDeclaration(tokens, index, declared);
            }
        }
        return declared;
    }

    /** A {@code uniform} sampler or 3D image declaration, with every name of its list. */
    private static void collectUniformDeclaration(List<GlslLexer.Token> tokens, int uniform, Declarations declared) {
        int type = GlslLexer.nextSignificant(tokens, uniform);
        while (type >= 0 && TYPE_QUALIFIERS.contains(tokens.get(type).text())) {
            type = GlslLexer.nextSignificant(tokens, type);
        }
        if (type < 0 || tokens.get(type).kind() != GlslLexer.Kind.IDENTIFIER) return;
        String typeName = tokens.get(type).text();
        if (isSampler(typeName)) {
            collectUniformNames(tokens, type, declared.names().samplers(), declared.tokens());
        } else if (isImageType(typeName)) {
            // Every name of a list: writeonly uniform image3D a, b;
            declared.tokens().add(uniform);
            collectUniformNames(tokens, type, declared.names().images(), declared.tokens());
        }
    }

    /** A 3D image type followed by its name, with or without {@code uniform}. */
    private static void collectImageDeclaration(List<GlslLexer.Token> tokens, int type, Declarations declared) {
        int name = GlslLexer.nextSignificant(tokens, type);
        if (name >= 0 && tokens.get(name).kind() == GlslLexer.Kind.IDENTIFIER) {
            declared.names().images().add(tokens.get(name).text());
            declared.tokens().add(type);
            declared.tokens().add(name);
        }
    }

    /** A {@code buffer} block's name and, after its closing brace, any instance name. */
    private static void collectStorageDeclaration(List<GlslLexer.Token> tokens, int buffer, Declarations declared) {
        int block = GlslLexer.nextSignificant(tokens, buffer);
        if (block < 0 || tokens.get(block).kind() != GlslLexer.Kind.IDENTIFIER) return;
        declared.names().storage().add(tokens.get(block).text());
        declared.tokens().add(buffer);
        declared.tokens().add(block);
        int open = GlslLexer.nextSignificant(tokens, block);
        int close = open < 0 ? -1 : GlslLexer.matching(tokens, open, "{", "}");
        int instance = close < 0 ? -1 : GlslLexer.nextSignificant(tokens, close);
        if (instance >= 0 && tokens.get(instance).kind() == GlslLexer.Kind.IDENTIFIER) {
            declared.names().storage().add(tokens.get(instance).text());
            declared.tokens().add(instance);
        }
    }

    private static void collectUniformNames(
            List<GlslLexer.Token> tokens,
            int type,
            Set<String> names,
            Set<Integer> declarationTokens
    ) {
        boolean expectName = true;
        declarationTokens.add(type);
        for (int index = type + 1; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant()) continue;
            if (token.symbol(";")) return;
            if (token.symbol(",")) {
                expectName = true;
                continue;
            }
            if (expectName && token.kind() == GlslLexer.Kind.IDENTIFIER) {
                names.add(token.text());
                declarationTokens.add(index);
                expectName = false;
            }
        }
    }

    private static List<FunctionDefinition> collectFunctions(List<GlslLexer.Token> tokens) {
        List<FunctionDefinition> result = new ArrayList<>();
        int braceDepth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant()) continue;
            if (token.symbol("{")) {
                braceDepth++;
                continue;
            }
            if (token.symbol("}")) {
                braceDepth--;
                if (braceDepth < 0) throw new IllegalArgumentException("unbalanced GLSL braces");
                continue;
            }
            if (braceDepth != 0 || token.kind() != GlslLexer.Kind.IDENTIFIER
                    || FUNCTION_KEYWORDS.contains(token.text())) continue;
            FunctionDefinition function = functionDefinitionAt(tokens, index);
            if (function != null) result.add(function);
        }
        if (braceDepth != 0) throw new IllegalArgumentException("unbalanced GLSL braces");
        return result;
    }

    /** The function named at {@code nameIndex} when a parameter list and a body follow it, else null. */
    private static FunctionDefinition functionDefinitionAt(List<GlslLexer.Token> tokens, int nameIndex) {
        int open = GlslLexer.nextSignificant(tokens, nameIndex);
        if (open < 0 || !tokens.get(open).symbol("(")) return null;
        int close = GlslLexer.matching(tokens, open, "(", ")");
        int body = close < 0 ? -1 : GlslLexer.nextSignificant(tokens, close);
        if (body < 0 || !tokens.get(body).symbol("{")) return null;
        int end = GlslLexer.matching(tokens, body, "{", "}");
        if (end < 0) throw new IllegalArgumentException("unbalanced GLSL function body");
        int definitionStart = functionDefinitionStart(tokens, nameIndex);
        return new FunctionDefinition(tokens.get(nameIndex).text(), definitionStart, body + 1, end, end);
    }

    private static int functionDefinitionStart(List<GlslLexer.Token> tokens, int nameIndex) {
        int returnType = GlslLexer.previousSignificant(tokens, nameIndex);
        if (returnType < 0) {
            return nameIndex;
        }
        int start = returnType;
        // An array return type (Photon: float[9] sh_coeff_order_2(...)) reaches back to its element type.
        while (tokens.get(start).symbol("]")) {
            int depth = 0;
            int cursor = start;
            for (; cursor >= 0; cursor = GlslLexer.previousSignificant(tokens, cursor)) {
                if (tokens.get(cursor).symbol("]")) depth++;
                else if (tokens.get(cursor).symbol("[") && --depth == 0) break;
            }
            int element = cursor < 0 ? -1 : GlslLexer.previousSignificant(tokens, cursor);
            if (element < 0) break;
            start = element;
        }
        int previous = GlslLexer.previousSignificant(tokens, start);
        while (previous >= 0
                && tokens.get(previous).kind() == GlslLexer.Kind.IDENTIFIER
                && FUNCTION_QUALIFIERS.contains(tokens.get(previous).text())) {
            start = previous;
            previous = GlslLexer.previousSignificant(tokens, start);
        }
        return start;
    }

    private static boolean isDeclarationName(List<GlslLexer.Token> tokens, int index) {
        int previous = GlslLexer.previousSignificant(tokens, index);
        if (previous < 0) {
            return false;
        }
        GlslLexer.Token previousToken = tokens.get(previous);
        if (startsDeclaration(previousToken)) {
            return true;
        }
        if (!previousToken.symbol(",")) {
            return false;
        }
        for (int cursor = previous; cursor >= 0; cursor = GlslLexer.previousSignificant(tokens, cursor)) {
            GlslLexer.Token token = tokens.get(cursor);
            if (token.symbol(";") || token.symbol("{") || token.symbol("}")) {
                return false;
            }
            if (startsDeclaration(token)) {
                return true;
            }
        }
        return false;
    }

    /** A value type or a storage qualifier, which a declared name may follow. */
    private static boolean startsDeclaration(GlslLexer.Token token) {
        return token.kind() == GlslLexer.Kind.IDENTIFIER
                && (VALUE_TYPES.contains(token.text()) || STORAGE_QUALIFIERS.contains(token.text()));
    }

    private static Set<Integer> preprocessorTokens(List<GlslLexer.Token> tokens) {
        Set<Integer> result = new HashSet<>();
        boolean lineStart = true;
        boolean directive = false;
        int previousSignificant = -1;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant()) {
                if (containsLineBreak(token.text())) {
                    String suffix = token.text().substring(Math.max(
                            token.text().lastIndexOf('\n'), token.text().lastIndexOf('\r')) + 1);
                    lineStart = suffix.isBlank();
                    boolean escaped = previousSignificant >= 0
                            && tokens.get(previousSignificant).symbol("\\");
                    if (directive && !continuesDirective(token.text(), escaped)) {
                        directive = false;
                    }
                }
                continue;
            }
            if (directive) {
                result.add(index);
                lineStart = false;
                previousSignificant = index;
                continue;
            }
            if (lineStart && token.symbol("#")) {
                result.add(index);
                directive = true;
                lineStart = false;
                previousSignificant = index;
                continue;
            }
            lineStart = false;
            previousSignificant = index;
        }
        return result;
    }

    private static boolean containsLineBreak(String text) {
        return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }

    private static boolean continuesDirective(String trivia, boolean escapedToken) {
        if (escapedToken) {
            return true;
        }
        int lineBreak = Math.max(trivia.lastIndexOf('\n'), trivia.lastIndexOf('\r'));
        if (lineBreak < 0) return true;
        int cursor = lineBreak - 1;
        while (cursor >= 0 && (trivia.charAt(cursor) == ' ' || trivia.charAt(cursor) == '\t')) {
            cursor--;
        }
        return cursor >= 0 && trivia.charAt(cursor) == '\\';
    }

    /**
     * The token positions in a function body where a resource's name means a local the body
     * declares: from that declaration to the end of its block, as GLSL scopes it. Bliss's
     * GetVolumetricFog reads the global {@code shadow} sampler, then declares a {@code float shadow}
     * in a later block; the earlier read is still the sampler's.
     */
    private static Set<Integer> localResourceReferences(
            List<GlslLexer.Token> tokens,
            FunctionDefinition function,
            Resources declared
    ) {
        Set<String> resources = declared.all();
        Set<Integer> result = new HashSet<>();
        Deque<Set<String>> scopes = new ArrayDeque<>();
        scopes.push(new HashSet<>());
        int start = function.bodyStart();
        for (int index = start; index < function.bodyEnd(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("{")) {
                scopes.push(new HashSet<>());
            } else if (token.symbol("}") && scopes.size() > 1) {
                scopes.pop();
            }
            if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER
                    || !resources.contains(token.text())) continue;
            int previous = GlslLexer.previousSignificant(tokens, index);
            boolean declaration = previous >= start
                    && tokens.get(previous).kind() == GlslLexer.Kind.IDENTIFIER
                    && (VALUE_TYPES.contains(tokens.get(previous).text())
                    || isSampler(tokens.get(previous).text())
                    || isImageType(tokens.get(previous).text()));
            if (declaration) scopes.peek().add(token.text());
            if (declaration || scopes.stream().anyMatch(scope -> scope.contains(token.text()))) result.add(index);
        }
        return result;
    }

    private static boolean isSampler(String type) {
        return type.startsWith("sampler") || type.startsWith("isampler") || type.startsWith("usampler");
    }

    private static boolean isImageType(String type) {
        return type.equals("image3D") || type.equals("iimage3D") || type.equals("uimage3D");
    }

    private static Set<String> sorted(Set<String> values) {
        return Collections.unmodifiableSet(new TreeSet<>(values == null ? Set.of() : values));
    }

    private static List<FunctionDefinition> sortedFunctions(List<FunctionDefinition> values) {
        if (values == null) return List.of();
        return values.stream().distinct().sorted(Comparator
                .comparing(FunctionDefinition::name)
                .thenComparingInt(FunctionDefinition::definitionStart)
                .thenComparingInt(FunctionDefinition::definitionEnd)).toList();
    }
}
