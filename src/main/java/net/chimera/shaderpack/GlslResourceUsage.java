package net.chimera.shaderpack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final Set<String> FUNCTION_QUALIFIERS = Set.of(
            "const", "inline", "static", "highp", "mediump", "lowp", "precise",
            "invariant");

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
        try {
            List<GlslLexer.Token> tokens = GlslLexer.lex(source == null ? "" : source);
            Set<String> declaredSamplers = new TreeSet<>();
            if (injectedSamplers != null) {
                injectedSamplers.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .forEach(declaredSamplers::add);
            }
            Set<String> declaredImages = new TreeSet<>();
            Set<String> declaredStorage = new TreeSet<>();
            Set<Integer> declarationTokens = new HashSet<>();
            collectDeclarations(tokens, declaredSamplers, declaredImages, declaredStorage,
                    declarationTokens);

            List<FunctionDefinition> functions = collectFunctions(tokens);
            Map<String, List<FunctionDefinition>> functionsByName = new HashMap<>();
            for (FunctionDefinition function : functions) {
                functionsByName.computeIfAbsent(function.name(), ignored -> new ArrayList<>())
                        .add(function);
            }
            FunctionDefinition main = functionsByName.getOrDefault("main", List.of()).stream()
                    .findFirst().orElse(null);
            Map<String, List<String>> macroCalls = collectFunctionMacros(
                    source == null ? "" : source);

            Set<String> referencedSamplers = new TreeSet<>();
            Set<String> referencedImages = new TreeSet<>();
            Set<String> referencedStorage = new TreeSet<>();
            for (FunctionDefinition function : functions) {
                collectResourceReferences(tokens, function.bodyStart(), function.bodyEnd(),
                        declaredSamplers, declaredImages, declaredStorage, declarationTokens,
                        referencedSamplers, referencedImages, referencedStorage);
            }
            collectTopLevelReferences(tokens, functions,
                    declaredSamplers, declaredImages, declaredStorage, declarationTokens,
                    referencedSamplers, referencedImages, referencedStorage);

            Set<FunctionDefinition> reachable = new HashSet<>();
            Set<String> liveSamplers = new TreeSet<>();
            Set<String> liveImages = new TreeSet<>();
            Set<String> liveStorage = new TreeSet<>();
            if (main == null) {
                // Interface-only snippets and compute wrappers without a
                // conventional main are retained conservatively.
                reachable.addAll(functions);
                liveSamplers.addAll(referencedSamplers);
                liveImages.addAll(referencedImages);
                liveStorage.addAll(referencedStorage);
            } else {
                ArrayDeque<FunctionDefinition> pending = new ArrayDeque<>();
                pending.add(main);
                seedTopLevelCalls(tokens, functions, functionsByName, macroCalls, pending);
                while (!pending.isEmpty()) {
                    FunctionDefinition function = pending.removeFirst();
                    if (!reachable.add(function)) {
                        continue;
                    }
                    Set<String> shadowed = localResourceDeclarations(tokens, function,
                            declaredSamplers, declaredImages, declaredStorage);
                    for (int index = function.bodyStart(); index < function.bodyEnd(); index++) {
                        GlslLexer.Token token = tokens.get(index);
                        if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER
                                || declarationTokens.contains(index)) {
                            continue;
                        }
                        String name = token.text();
                        if (!shadowed.contains(name) && declaredSamplers.contains(name)) {
                            liveSamplers.add(name);
                        }
                        if (!shadowed.contains(name) && declaredImages.contains(name)) {
                            liveImages.add(name);
                        }
                        if (!shadowed.contains(name) && declaredStorage.contains(name)) {
                            liveStorage.add(name);
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

            List<FunctionDefinition> reachableFunctions = functions.stream()
                    .filter(reachable::contains).toList();
            List<FunctionDefinition> unreachableFunctions = main == null
                    ? List.of()
                    : functions.stream().filter(value -> !reachable.contains(value)).toList();
            return new Analysis(declaredSamplers, referencedSamplers, liveSamplers,
                    declaredImages, liveImages, declaredStorage,
                    liveStorage, reachableFunctions, unreachableFunctions, true, List.of());
        } catch (RuntimeException failure) {
            return new Analysis(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Set.of(), List.of(), List.of(), false,
                    List.of("RESOURCE_USAGE_UNCLASSIFIABLE"));
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

    private static void collectResourceReferences(
            List<GlslLexer.Token> tokens,
            int start,
            int end,
            Set<String> samplers,
            Set<String> images,
            Set<String> storage,
            Set<Integer> declarationTokens,
            Set<String> referencedSamplers,
            Set<String> referencedImages,
            Set<String> referencedStorage
    ) {
        Set<String> shadowed = localResourceDeclarations(tokens, start, end,
                samplers, images, storage);
        for (int index = start; index < end; index++) {
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER
                    || declarationTokens.contains(index) || shadowed.contains(token.text())) {
                continue;
            }
            if (samplers.contains(token.text())) referencedSamplers.add(token.text());
            if (images.contains(token.text())) referencedImages.add(token.text());
            if (storage.contains(token.text())) referencedStorage.add(token.text());
        }
    }

    private static void collectTopLevelReferences(
            List<GlslLexer.Token> tokens,
            List<FunctionDefinition> functions,
            Set<String> samplers,
            Set<String> images,
            Set<String> storage,
            Set<Integer> declarationTokens,
            Set<String> referencedSamplers,
            Set<String> referencedImages,
            Set<String> referencedStorage
    ) {
        Set<Integer> functionTokens = new HashSet<>();
        for (FunctionDefinition function : functions) {
            for (int index = function.definitionStart(); index <= function.definitionEnd(); index++) {
                functionTokens.add(index);
            }
        }
        for (int index = 0; index < tokens.size(); index++) {
            if (functionTokens.contains(index)) continue;
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER
                    || declarationTokens.contains(index)) continue;
            if (samplers.contains(token.text())) referencedSamplers.add(token.text());
            if (images.contains(token.text())) referencedImages.add(token.text());
            if (storage.contains(token.text())) referencedStorage.add(token.text());
        }
    }

    private static void seedTopLevelCalls(
            List<GlslLexer.Token> tokens,
            List<FunctionDefinition> functions,
            Map<String, List<FunctionDefinition>> functionsByName,
            Map<String, List<String>> macroCalls,
            ArrayDeque<FunctionDefinition> pending
    ) {
        Set<Integer> functionTokens = new HashSet<>();
        for (FunctionDefinition function : functions) {
            for (int index = function.definitionStart(); index <= function.definitionEnd(); index++) {
                functionTokens.add(index);
            }
        }
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
        Matcher objectLike = OBJECT_MACRO.matcher(source == null ? "" : source);
        while (objectLike.find()) {
            Set<String> named = new TreeSet<>();
            for (GlslLexer.Token token : GlslLexer.lex(objectLike.group(2))) {
                if (token.kind() == GlslLexer.Kind.IDENTIFIER) named.add(token.text());
            }
            result.put(objectLike.group(1), List.copyOf(named));
        }
        Matcher matcher = FUNCTION_MACRO.matcher(source == null ? "" : source);
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

    private static void collectDeclarations(
            List<GlslLexer.Token> tokens,
            Set<String> samplers,
            Set<String> images,
            Set<String> storage,
            Set<Integer> declarationTokens
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER) continue;
            if (token.identifier("uniform")) {
                int type = GlslLexer.nextSignificant(tokens, index);
                if (type < 0 || tokens.get(type).kind() != GlslLexer.Kind.IDENTIFIER) continue;
                String typeName = tokens.get(type).text();
                if (isSampler(typeName)) {
                    collectUniformNames(tokens, type, samplers, declarationTokens);
                } else if (isImageType(typeName)) {
                    int name = GlslLexer.nextSignificant(tokens, type);
                    if (name >= 0 && tokens.get(name).kind() == GlslLexer.Kind.IDENTIFIER) {
                        images.add(tokens.get(name).text());
                        declarationTokens.add(index);
                        declarationTokens.add(type);
                        declarationTokens.add(name);
                    }
                }
            } else if (isImageType(token.text())) {
                int name = GlslLexer.nextSignificant(tokens, index);
                if (name >= 0 && tokens.get(name).kind() == GlslLexer.Kind.IDENTIFIER) {
                    images.add(tokens.get(name).text());
                    declarationTokens.add(index);
                    declarationTokens.add(name);
                }
            } else if (token.identifier("buffer")) {
                int block = GlslLexer.nextSignificant(tokens, index);
                if (block >= 0 && tokens.get(block).kind() == GlslLexer.Kind.IDENTIFIER) {
                    storage.add(tokens.get(block).text());
                    declarationTokens.add(index);
                    declarationTokens.add(block);
                    int open = GlslLexer.nextSignificant(tokens, block);
                    int close = open < 0 ? -1 : GlslLexer.matching(tokens, open, "{", "}");
                    if (close >= 0) {
                        int instance = GlslLexer.nextSignificant(tokens, close);
                        if (instance >= 0 && tokens.get(instance).kind() == GlslLexer.Kind.IDENTIFIER) {
                            storage.add(tokens.get(instance).text());
                            declarationTokens.add(instance);
                        }
                    }
                }
            }
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
            int open = GlslLexer.nextSignificant(tokens, index);
            if (open < 0 || !tokens.get(open).symbol("(")) continue;
            int close = GlslLexer.matching(tokens, open, "(", ")");
            int body = close < 0 ? -1 : GlslLexer.nextSignificant(tokens, close);
            if (body < 0 || !tokens.get(body).symbol("{")) continue;
            int end = GlslLexer.matching(tokens, body, "{", "}");
            if (end < 0) throw new IllegalArgumentException("unbalanced GLSL function body");
            int definitionStart = functionDefinitionStart(tokens, index);
            result.add(new FunctionDefinition(token.text(), definitionStart, body + 1, end, end));
        }
        if (braceDepth != 0) throw new IllegalArgumentException("unbalanced GLSL braces");
        return result;
    }

    private static int functionDefinitionStart(List<GlslLexer.Token> tokens, int nameIndex) {
        int returnType = GlslLexer.previousSignificant(tokens, nameIndex);
        if (returnType < 0) {
            return nameIndex;
        }
        int start = returnType;
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
        if (previousToken.kind() == GlslLexer.Kind.IDENTIFIER
                && (VALUE_TYPES.contains(previousToken.text())
                || previousToken.text().equals("uniform")
                || previousToken.text().equals("attribute")
                || previousToken.text().equals("varying")
                || previousToken.text().equals("in")
                || previousToken.text().equals("out"))) {
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
            if (token.kind() == GlslLexer.Kind.IDENTIFIER
                    && (VALUE_TYPES.contains(token.text())
                    || token.text().equals("uniform")
                    || token.text().equals("attribute")
                    || token.text().equals("varying")
                    || token.text().equals("in")
                    || token.text().equals("out"))) {
                return true;
            }
        }
        return false;
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

    private static Set<String> localResourceDeclarations(
            List<GlslLexer.Token> tokens,
            FunctionDefinition function,
            Set<String> samplers,
            Set<String> images,
            Set<String> storage
    ) {
        return localResourceDeclarations(tokens, function.bodyStart(), function.bodyEnd(),
                samplers, images, storage);
    }

    private static Set<String> localResourceDeclarations(
            List<GlslLexer.Token> tokens,
            int start,
            int end,
            Set<String> samplers,
            Set<String> images,
            Set<String> storage
    ) {
        Set<String> resources = new HashSet<>();
        resources.addAll(samplers);
        resources.addAll(images);
        resources.addAll(storage);
        Set<String> result = new HashSet<>();
        for (int index = start; index < end; index++) {
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant() || token.kind() != GlslLexer.Kind.IDENTIFIER
                    || !resources.contains(token.text())) continue;
            int previous = GlslLexer.previousSignificant(tokens, index);
            if (previous >= start
                    && tokens.get(previous).kind() == GlslLexer.Kind.IDENTIFIER
                    && (VALUE_TYPES.contains(tokens.get(previous).text())
                    || isSampler(tokens.get(previous).text())
                    || isImageType(tokens.get(previous).text()))) {
                result.add(token.text());
            }
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
        return values.stream().distinct().sorted(java.util.Comparator
                .comparing(FunctionDefinition::name)
                .thenComparingInt(FunctionDefinition::definitionStart)
                .thenComparingInt(FunctionDefinition::definitionEnd)).toList();
    }
}
