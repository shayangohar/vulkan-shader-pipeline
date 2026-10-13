package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Token-safe rewrites for the small legacy-to-Vulkan GLSL bridge. */
public final class GlslTokenRewriter {
    private GlslTokenRewriter() {}

    /**
     * Blanks complete unreachable function definitions while preserving line
     * breaks. Source positions remain stable for compiler diagnostics and the
     * later declaration rewrites cannot leave dead resource references behind.
     */
    static String removeUnreachableFunctions(
            String source, GlslResourceUsage.Analysis usage
    ) {
        if (source == null || usage == null || !usage.successful()
                || usage.unreachableFunctions().isEmpty()) {
            return source;
        }
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        for (GlslResourceUsage.FunctionDefinition function : usage.unreachableFunctions()) {
            int start = Math.max(0, function.definitionStart());
            int end = Math.min(tokens.size() - 1, function.definitionEnd());
            for (int index = start; index <= end; index++) {
                String text = tokens.get(index).text();
                tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.TRIVIA,
                        lineBreaks(text)));
            }
        }
        return GlslLexer.render(tokens);
    }

    static String lineBreaks(String text) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\r' || value == '\n') {
                result.append(value);
            }
        }
        return result.toString();
    }

    static String replaceIdentifiers(String source, Map<String, String> replacements) {
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER) {
                continue;
            }
            String replacement = replacements.get(token.text());
            if (replacement != null) {
                tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, replacement));
            }
        }
        return GlslLexer.render(tokens);
    }

    /**
     * Renames a sampler identifier without renaming a function with the same
     * spelling.  The OptiFine atlas sampler is commonly named {@code texture},
     * which collides with the GLSL texture lookup function.
     */
    static String renameSamplerIdentifier(String source, String sampler, String replacement) {
        if (source == null || sampler == null || replacement == null || sampler.equals(replacement)) {
            return source;
        }
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (!token.identifier(sampler)) {
                continue;
            }
            int next = GlslLexer.nextSignificant(tokens, index);
            if (next >= 0 && tokens.get(next).symbol("(")) {
                continue;
            }
            tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, replacement));
        }
        return GlslLexer.render(tokens);
    }

    public static String rewriteTextureCalls(String source) {
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            String replacement = switch (token.text()) {
                case "texture2DLod", "texture3DLod" -> "textureLod";
                case "texture2D", "texture3D" -> "texture";
                case "texture2DProj" -> "textureProj";
                case "texture2DGradARB" -> "textureGrad";
                case "texelFetch2D", "texelFetch3D" -> "texelFetch";
                default -> null;
            };
            if (token.kind() != GlslLexer.Kind.IDENTIFIER || replacement == null) {
                continue;
            }
            int open = GlslLexer.nextSignificant(tokens, index);
            if (open < 0 || !tokens.get(open).symbol("(")) {
                continue;
            }
            if (GlslLexer.matching(tokens, open, "(", ")") < 0) {
                throw new IllegalArgumentException("unbalanced texture call");
            }
            tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, replacement));
        }
        return GlslLexer.render(tokens);
    }

    /** Vulkan GLSL reserves 'sampler'; legacy GL permits it as a function parameter name. */
    static String renameReservedSamplerParameters(String source) {
        if (source == null || !source.contains("sampler")) return source;
        var usage = GlslResourceUsage.analyze(source);
        if (!usage.successful()) return source;
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        Set<String> names = new HashSet<>();
        for (var token : tokens) if (token.kind() == GlslLexer.Kind.IDENTIFIER) names.add(token.text());
        String replacement = "chimeraSamplerParameter";
        while (names.contains(replacement)) replacement += "_";
        List<GlslResourceUsage.FunctionDefinition> functions = new ArrayList<>(usage.reachableFunctions());
        functions.addAll(usage.unreachableFunctions());
        for (var function : functions) {
            boolean reservedParameter = false;
            for (int i = function.definitionStart(); i < function.bodyStart(); i++) {
                if (tokens.get(i).identifier("sampler")) reservedParameter = true;
            }
            if (!reservedParameter) continue;
            for (int i = function.definitionStart(); i <= function.definitionEnd(); i++) {
                if (tokens.get(i).identifier("sampler")) tokens.set(i, identifier(replacement));
            }
        }
        return GlslLexer.render(tokens);
    }

    /**
     * Keeps legacy 3D texture reads inside the declared image volume. Iris
     * packs commonly use camera-relative coordinates for these reads. Vulkan
     * does not guarantee a safe result for an out-of-range texelFetch, so the
     * bounded compute bridge makes the access explicit before shaderc sees it.
     */
    public static String rewriteTexelFetchBounds(String source, Set<String> samplerNames) {
        if (source == null || source.isBlank() || samplerNames == null || samplerNames.isEmpty()) {
            return source;
        }
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        for (int index = tokens.size() - 1; index >= 0; index--) {
            GlslLexer.Token token = tokens.get(index);
            if (!token.identifier("texelFetch")) {
                continue;
            }
            int open = GlslLexer.nextSignificant(tokens, index);
            int close = open < 0 ? -1 : GlslLexer.matching(tokens, open, "(", ")");
            if (open < 0 || close < 0) {
                throw new IllegalArgumentException("unbalanced texelFetch call");
            }
            List<int[]> arguments = argumentRanges(tokens, open + 1, close);
            if (arguments.size() != 3) {
                continue;
            }
            String sampler = singleIdentifier(tokens, arguments.get(0));
            if (!samplerNames.contains(sampler)) {
                continue;
            }
            String coordinate = argumentText(tokens, arguments.get(1));
            String lod = argumentText(tokens, arguments.get(2));
            String expression = "texelFetch(" + sampler + ", clamp(" + coordinate
                    + ", ivec3(0), textureSize(" + sampler + ", 0) - ivec3(1)), " + lod + ")";
            tokens.set(index, raw(expression));
            for (int clear = index + 1; clear <= close; clear++) {
                tokens.set(clear, raw(""));
            }
        }
        return GlslLexer.render(tokens);
    }

    /**
     * Converts legacy shadow lookups to expressions using the declared sampler.
     * GLSL opaque sampler types cannot be function parameters, so this rewrite
     * deliberately emits an inline expression instead of a generated helper.
     */
    static String rewriteShadowCalls(String source, Map<String, String> samplerTypes) {
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        // A program may define a function under one of these names (Solas's own
        // texture2DShadow); its calls and its definition are the program's, not legacy lookups.
        Set<String> userDefined = userDefinedFunctions(tokens, LEGACY_SHADOW_CALLS);
        // Work from the innermost calls outward. This keeps nested legacy
        // lookups valid when the outer coordinate expression is rendered.
        for (int index = tokens.size() - 1; index >= 0; index--) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER || !LEGACY_SHADOW_CALLS.contains(token.text())
                    || userDefined.contains(token.text())) {
                continue;
            }
            int open = GlslLexer.nextSignificant(tokens, index);
            int close = open < 0 ? -1 : GlslLexer.matching(tokens, open, "(", ")");
            if (open < 0 || !tokens.get(open).symbol("(") || close < 0) {
                throw new IllegalArgumentException("unbalanced legacy shadow call");
            }
            tokens.set(index, raw(legacyShadowExpression(tokens, index, close, samplerTypes)));
            for (int clear = index + 1; clear <= close; clear++) {
                tokens.set(clear, raw(""));
            }
        }
        return GlslLexer.render(tokens);
    }

    private static final Set<String> LEGACY_SHADOW_CALLS = Set.of("shadow2D", "shadow2DLod", "texture2DShadow");

    /** The inline expression replacing the legacy shadow call at {@code call}, whose arguments end at {@code close}. */
    private static String legacyShadowExpression(List<GlslLexer.Token> tokens, int call, int close,
                                                 Map<String, String> samplerTypes) {
        String function = tokens.get(call).text();
        int open = GlslLexer.nextSignificant(tokens, call);
        List<int[]> arguments = argumentRanges(tokens, open + 1, close);
        boolean lodCall = function.equals("shadow2DLod");
        int expectedArguments = lodCall ? 3 : 2;
        if (arguments.size() != expectedArguments) {
            throw new IllegalArgumentException("invalid legacy shadow argument count");
        }
        String sampler = singleIdentifier(tokens, arguments.get(0));
        String samplerType = samplerTypes == null ? null : samplerTypes.get(sampler);
        if (samplerType == null) {
            throw new IllegalArgumentException("legacy shadow sampler is not declared: " + sampler);
        }
        String coordinate = argumentText(tokens, arguments.get(1));
        String lod = lodCall ? argumentText(tokens, arguments.get(2)) : null;
        String test = shadowTest(samplerType, sampler, coordinate, lod);
        return function.equals("texture2DShadow") ? test : "vec4(" + test + ")";
    }

    /**
     * The scalar shadow test: a compare sampler's own lookup, or a step against a depth
     * texture's red channel. {@code lod} is null for a lookup without one.
     */
    private static String shadowTest(String samplerType, String sampler, String coordinate, String lod) {
        String lookup = lod == null ? "texture(" : "textureLod(";
        String lodArgument = lod == null ? "" : ", " + lod;
        if (samplerType.equals("sampler2DShadow")) {
            return lookup + sampler + ", " + coordinate + lodArgument + ")";
        }
        if (samplerType.equals("sampler2D")) {
            return "step(" + coordinate + ".z, " + lookup + sampler + ", " + coordinate + ".xy" + lodArgument
                    + ").r)";
        }
        throw new IllegalArgumentException("unsupported legacy shadow sampler type: " + samplerType);
    }

    /**
     * Wraps the one real GLSL main function and appends statements after the
     * authored body. The lexer and resource analysis make comments, strings,
     * helper functions, and identifiers containing "main" irrelevant.
     */
    static String appendMainEpilogue(String source, String epilogue) {
        if (source == null || epilogue == null || epilogue.isBlank()) return source;
        MainFunction main = locateMain(source);
        List<GlslLexer.Token> tokens = main.tokens();
        String authored = uniqueIdentifier(source, "chimeraAuthoredMain");
        String wrapper = uniqueIdentifier(source, "chimeraGeneratedMain");
        tokens.set(main.nameIndex(), identifier(authored));
        String indented = indent(epilogue);
        String generated = "\nvoid " + wrapper + "() {\n    " + authored + "();\n"
                + indented + "\n}\nvoid main() {\n    " + wrapper + "();\n}\n";
        tokens.add(main.bodyEnd() + 1, raw(generated));
        return GlslLexer.render(tokens);
    }

    /**
     * Splits {@code [layout(...)] [writeonly] uniform T a, b[2], c = x;} into
     * one declaration per name. GLSL gives each declarator the same qualifiers
     * and type, so this changes nothing a shader can observe; it lets every
     * per-name rewriter (bindings, advanced images, the shadowcomp translator)
     * find each declaration. Solas declares its two floodfill images in one
     * statement. Interface blocks are left alone.
     */
    public static String splitUniformDeclarators(String source) {
        if (source == null || !source.contains("uniform") || !source.contains(",")) return source;
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        boolean changed = false;
        int depth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("{")) depth++;
            if (token.symbol("}")) depth--;
            if (depth != 0 || !token.identifier("uniform") || inDirective(tokens, index)) continue;
            int end = splitUniformStatement(tokens, index);
            if (end < 0) continue;
            index = end;
            changed = true;
        }
        return changed ? GlslLexer.render(tokens) : source;
    }

    /**
     * Rewrites the statement around {@code uniform} as one declaration per name and returns the
     * index of its semicolon. An interface block, a single name or an unterminated statement is
     * left alone and gives -1.
     */
    private static int splitUniformStatement(List<GlslLexer.Token> tokens, int uniform) {
        int start = qualifierStart(tokens, uniform);
        int type = GlslLexer.nextSignificant(tokens, uniform);
        while (type >= 0 && (PRECISION_QUALIFIERS.contains(tokens.get(type).text())
                || MEMORY_QUALIFIERS.contains(tokens.get(type).text()))) {
            type = GlslLexer.nextSignificant(tokens, type);
        }
        int first = type < 0 ? -1 : GlslLexer.nextSignificant(tokens, type);
        if (first < 0 || tokens.get(first).symbol("{")
                || tokens.get(type).kind() != GlslLexer.Kind.IDENTIFIER) return -1;
        List<int[]> declarators = declaratorRanges(tokens, first);
        if (declarators == null || declarators.size() < 2) return -1;
        int end = declarators.get(declarators.size() - 1)[1];
        String prefix = GlslLexer.render(tokens.subList(start, type + 1));
        StringBuilder split = new StringBuilder();
        for (int[] declarator : declarators) {
            if (split.length() > 0) split.append('\n');
            split.append(prefix).append(' ')
                    .append(GlslLexer.render(tokens.subList(declarator[0], declarator[1])).strip())
                    .append(';');
        }
        for (int cursor = start; cursor <= end; cursor++) tokens.set(cursor, raw(""));
        tokens.set(start, raw(split.toString()));
        return end;
    }

    /**
     * The token range of each top-level declarator from {@code first} through the statement's
     * semicolon, which ends the last range; null when no semicolon closes the statement.
     */
    private static List<int[]> declaratorRanges(List<GlslLexer.Token> tokens, int first) {
        List<int[]> declarators = new ArrayList<>();
        int declaratorStart = first;
        int nesting = 0;
        for (int cursor = first; cursor < tokens.size(); cursor++) {
            GlslLexer.Token current = tokens.get(cursor);
            if (current.symbol("(") || current.symbol("[")) nesting++;
            if (current.symbol(")") || current.symbol("]")) nesting--;
            if (nesting != 0 || (!current.symbol(",") && !current.symbol(";"))) continue;
            declarators.add(new int[] {declaratorStart, cursor});
            declaratorStart = cursor + 1;
            if (current.symbol(";")) return declarators;
        }
        return null;
    }

    /** The first token of the memory qualifiers and layout(...) written before {@code uniform}. */
    private static int qualifierStart(List<GlslLexer.Token> tokens, int uniform) {
        int start = uniform;
        while (true) {
            int before = GlslLexer.previousSignificant(tokens, start);
            if (before < 0 || inDirective(tokens, before)) return start;
            GlslLexer.Token previous = tokens.get(before);
            if (previous.kind() == GlslLexer.Kind.IDENTIFIER
                    && MEMORY_QUALIFIERS.contains(previous.text())) {
                start = before;
            } else if (previous.symbol(")")) {
                int open = matchingBackward(tokens, before);
                int keyword = open < 0 ? -1 : GlslLexer.previousSignificant(tokens, open);
                if (keyword < 0 || !tokens.get(keyword).identifier("layout")) return start;
                start = keyword;
            } else {
                return start;
            }
        }
    }

    private static final Set<String> PRECISION_QUALIFIERS = Set.of("lowp", "mediump", "highp");
    private static final Set<String> MEMORY_QUALIFIERS = Set.of(
            "writeonly", "readonly", "coherent", "volatile", "restrict");

    /**
     * Desktop GL drivers accept a global {@code const} whose initializer is
     * not a constant expression (a uniform, a plain global, a user function);
     * Vulkan GLSL rejects it. Drop {@code const} from exactly those
     * declarations, following dependencies until nothing changes, so every
     * truly constant global keeps it (array sizes and other consts need it).
     */
    static String relaxNonConstantGlobals(String source) {
        if (source == null || !source.contains("const")) return source;
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        Set<String> userFunctions = globalFunctionNames(tokens);
        List<ConstDeclaration> declarations = globalConstDeclarations(tokens);
        Set<String> constant = constantNames(tokens, declarations, userFunctions);
        boolean relaxed = false;
        for (ConstDeclaration declaration : declarations) {
            if (constant.containsAll(declaration.names())) continue;
            tokens.set(declaration.keyword(), raw(""));
            relaxed = true;
        }
        return relaxed ? GlslLexer.render(tokens) : source;
    }

    /** Every parsable {@code const} declaration that starts a statement at global scope, in source order. */
    private static List<ConstDeclaration> globalConstDeclarations(List<GlslLexer.Token> tokens) {
        List<ConstDeclaration> declarations = new ArrayList<>();
        int depth = 0;
        int parentheses = 0;
        int previous = -1;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (!token.significant()) continue;
            if (token.symbol("{")) depth++;
            if (token.symbol("}")) depth--;
            if (token.symbol("(")) parentheses++;
            if (token.symbol(")")) parentheses--;
            if (depth == 0 && parentheses == 0 && token.identifier("const")
                    && startsStatement(tokens, previous, index)) {
                ConstDeclaration declaration = parseConstDeclaration(tokens, index);
                if (declaration != null) declarations.add(declaration);
            }
            previous = index;
        }
        return declarations;
    }

    /** True when the token at {@code index}, after the significant token {@code previous}, begins a statement. */
    private static boolean startsStatement(List<GlslLexer.Token> tokens, int previous, int index) {
        return previous < 0 || tokens.get(previous).symbol(";")
                || tokens.get(previous).symbol("}") || firstOnLine(tokens, index);
    }

    /**
     * The declared names that stay constant: a declaration whose initializer reads a non-constant
     * name loses all its names, repeated until nothing changes.
     */
    private static Set<String> constantNames(List<GlslLexer.Token> tokens, List<ConstDeclaration> declarations,
                                             Set<String> userFunctions) {
        Set<String> constant = new HashSet<>();
        declarations.forEach(declaration -> constant.addAll(declaration.names()));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ConstDeclaration declaration : declarations) {
                if (!constant.containsAll(declaration.names())) continue;
                for (int[] range : declaration.initializers()) {
                    if (!isConstantExpression(tokens, range[0], range[1], constant, userFunctions)) {
                        constant.removeAll(declaration.names());
                        changed = true;
                        break;
                    }
                }
            }
        }
        return constant;
    }

    private static final Set<String> CONSTANT_IDENTIFIERS = Set.of("true", "false");

    private static boolean isConstantExpression(List<GlslLexer.Token> tokens, int start, int end,
                                                Set<String> constant, Set<String> userFunctions) {
        for (int index = start; index < end; index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER) continue;
            int before = GlslLexer.previousSignificant(tokens, index);
            if (before >= 0 && tokens.get(before).symbol(".")) continue;
            int after = GlslLexer.nextSignificant(tokens, index);
            boolean call = after >= 0 && tokens.get(after).symbol("(");
            if (after >= 0 && tokens.get(after).symbol("[")) {
                int close = GlslLexer.matching(tokens, after, "[", "]");
                int next = close < 0 ? -1 : GlslLexer.nextSignificant(tokens, close);
                call = next >= 0 && tokens.get(next).symbol("(");
            }
            String name = token.text();
            if (call) {
                // Constructors and built-in functions fold; a user function never does.
                if (userFunctions.contains(name)) return false;
                continue;
            }
            if (CONSTANT_IDENTIFIERS.contains(name) || name.startsWith("gl_") || constant.contains(name)) continue;
            return false;
        }
        return true;
    }

    private record ConstDeclaration(int keyword, List<String> names, List<int[]> initializers) {}

    /** The const keyword's declarators and initializer token ranges, or null for anything else. */
    private static ConstDeclaration parseConstDeclaration(List<GlslLexer.Token> tokens, int keyword) {
        int type = GlslLexer.nextSignificant(tokens, keyword);
        while (type >= 0 && PRECISION_QUALIFIERS.contains(tokens.get(type).text())) {
            type = GlslLexer.nextSignificant(tokens, type);
        }
        if (type < 0 || tokens.get(type).kind() != GlslLexer.Kind.IDENTIFIER) return null;
        int cursor = skipArray(tokens, GlslLexer.nextSignificant(tokens, type));
        List<String> names = new ArrayList<>();
        List<int[]> initializers = new ArrayList<>();
        while (cursor >= 0 && tokens.get(cursor).kind() == GlslLexer.Kind.IDENTIFIER) {
            names.add(tokens.get(cursor).text());
            int next = skipArray(tokens, GlslLexer.nextSignificant(tokens, cursor));
            if (next < 0) return null;
            if (tokens.get(next).symbol("=")) {
                int end = initializerEnd(tokens, next + 1);
                if (end < 0) return null;
                initializers.add(new int[] {next + 1, end});
                next = end;
            }
            if (tokens.get(next).symbol(";")) return new ConstDeclaration(keyword, names, initializers);
            if (!tokens.get(next).symbol(",")) return null;
            cursor = GlslLexer.nextSignificant(tokens, next);
        }
        return null;
    }

    /** The comma or semicolon that ends an initializer starting at {@code start}, outside any brackets; -1 if none. */
    private static int initializerEnd(List<GlslLexer.Token> tokens, int start) {
        int nesting = 0;
        for (int index = start; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("(") || token.symbol("[") || token.symbol("{")) nesting++;
            if (token.symbol(")") || token.symbol("]") || token.symbol("}")) nesting--;
            if (nesting == 0 && (token.symbol(",") || token.symbol(";"))) return index;
        }
        return -1;
    }

    /** Steps over an array suffix such as {@code [3]}; returns the next significant index. */
    private static int skipArray(List<GlslLexer.Token> tokens, int index) {
        if (index < 0 || !tokens.get(index).symbol("[")) return index;
        int close = GlslLexer.matching(tokens, index, "[", "]");
        return close < 0 ? -1 : GlslLexer.nextSignificant(tokens, close);
    }

    private static boolean firstOnLine(List<GlslLexer.Token> tokens, int index) {
        for (int cursor = index - 1; cursor >= 0; cursor--) {
            GlslLexer.Token token = tokens.get(cursor);
            if (token.kind() == GlslLexer.Kind.TRIVIA) {
                if (token.text().contains("\n")) return true;
                continue;
            }
            return false;
        }
        return true;
    }

    /** Names of functions defined or declared at global scope. */
    private static Set<String> globalFunctionNames(List<GlslLexer.Token> tokens) {
        Set<String> names = new HashSet<>();
        int depth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("{")) depth++;
            if (token.symbol("}")) depth--;
            if (depth != 0 || token.kind() != GlslLexer.Kind.IDENTIFIER) continue;
            int before = GlslLexer.previousSignificant(tokens, index);
            int open = GlslLexer.nextSignificant(tokens, index);
            if (before < 0 || open < 0 || !tokens.get(open).symbol("(")
                    || tokens.get(before).kind() != GlslLexer.Kind.IDENTIFIER) continue;
            int close = GlslLexer.matching(tokens, open, "(", ")");
            int body = close < 0 ? -1 : GlslLexer.nextSignificant(tokens, close);
            if (body >= 0 && (tokens.get(body).symbol("{") || tokens.get(body).symbol(";"))) {
                names.add(token.text());
            }
        }
        return names;
    }

    private static int matchingBackward(List<GlslLexer.Token> tokens, int close) {
        int depth = 0;
        for (int index = close; index >= 0; index--) {
            if (tokens.get(index).symbol(")")) depth++;
            if (tokens.get(index).symbol("(") && --depth == 0) return index;
        }
        return -1;
    }

    /** True when the token sits on a preprocessor line, such as a #define body. */
    private static boolean inDirective(List<GlslLexer.Token> tokens, int index) {
        GlslLexer.Token lineStart = tokens.get(index);
        for (int cursor = index - 1; cursor >= 0; cursor--) {
            GlslLexer.Token token = tokens.get(cursor);
            if (token.kind() == GlslLexer.Kind.TRIVIA && token.text().contains("\n")) break;
            if (token.significant()) lineStart = token;
        }
        return lineStart.text().startsWith("#");
    }

    /** Inserts statements at the start of the one real GLSL main body (Iris prependMainFunctionBody). */
    static String prependMainPrologue(String source, String prologue) {
        if (source == null || prologue == null || prologue.isBlank()) return source;
        MainFunction main = locateMain(source);
        List<GlslLexer.Token> tokens = main.tokens();
        tokens.add(main.bodyOpen() + 1, raw("\n" + indent(prologue)));
        return GlslLexer.render(tokens);
    }

    private record MainFunction(List<GlslLexer.Token> tokens, int nameIndex, int bodyOpen, int bodyEnd) {}

    private static MainFunction locateMain(String source) {
        GlslResourceUsage.FunctionDefinition main = mainDefinition(source);
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        int nameIndex = -1;
        for (int index = main.definitionStart(); index < main.bodyStart(); index++) {
            if (tokens.get(index).identifier("main")) {
                nameIndex = index;
                break;
            }
        }
        if (nameIndex < 0) throw new IllegalArgumentException("main function name is missing");
        return new MainFunction(tokens, nameIndex, mainBodyOpen(tokens, nameIndex), main.bodyEnd());
    }

    /** The source's one main definition, found by the resource analysis. */
    private static GlslResourceUsage.FunctionDefinition mainDefinition(String source) {
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(source);
        if (!usage.successful()) throw new IllegalArgumentException("main function is malformed");
        List<GlslResourceUsage.FunctionDefinition> mains = new ArrayList<>();
        usage.reachableFunctions().stream()
                .filter(value -> value.name().equals("main"))
                .forEach(mains::add);
        usage.unreachableFunctions().stream()
                .filter(value -> value.name().equals("main"))
                .forEach(mains::add);
        mains = mains.stream().distinct().toList();
        if (mains.size() != 1) {
            throw new IllegalArgumentException(mains.isEmpty()
                    ? "main function is missing" : "main function is ambiguous");
        }
        return mains.get(0);
    }

    /** The opening brace of main's body; only {@code void main()} is supported. */
    private static int mainBodyOpen(List<GlslLexer.Token> tokens, int nameIndex) {
        int returnType = GlslLexer.previousSignificant(tokens, nameIndex);
        int open = GlslLexer.nextSignificant(tokens, nameIndex);
        int close = open < 0 ? -1 : GlslLexer.matching(tokens, open, "(", ")");
        int bodyOpen = close < 0 ? -1 : GlslLexer.nextSignificant(tokens, close);
        if (returnType < 0 || !tokens.get(returnType).identifier("void")
                || open < 0 || close < 0 || bodyOpen < 0
                || !tokens.get(open).symbol("(") || !tokens.get(close).symbol(")")
                || !tokens.get(bodyOpen).symbol("{")) {
            throw new IllegalArgumentException("main signature is unsupported");
        }
        for (int index = open + 1; index < close; index++) {
            if (tokens.get(index).significant()) {
                throw new IllegalArgumentException("main parameters are unsupported");
            }
        }
        return bodyOpen;
    }

    /** Returns a source-safe identifier that is absent from the token stream. */
    static String uniqueIdentifier(String source, String base) {
        String candidate = base == null || base.isBlank() ? "chimeraGenerated" : base;
        Set<String> identifiers = new HashSet<>();
        for (GlslLexer.Token token : GlslLexer.lex(source == null ? "" : source)) {
            if (token.kind() == GlslLexer.Kind.IDENTIFIER) identifiers.add(token.text());
        }
        while (identifiers.contains(candidate)) candidate += "_";
        return candidate;
    }

    private static String indent(String source) {
        String[] lines = source.split("\\R", -1);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) result.append('\n');
            result.append("    ").append(lines[index]);
        }
        return result.toString();
    }

    static List<int[]> argumentRanges(List<GlslLexer.Token> tokens, int start, int end) {
        List<int[]> ranges = new ArrayList<>();
        int argumentStart = start;
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        for (int index = start; index < end; index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("(")) {
                parentheses++;
            } else if (token.symbol(")")) {
                parentheses--;
            } else if (token.symbol("[")) {
                brackets++;
            } else if (token.symbol("]")) {
                brackets--;
            } else if (token.symbol("{")) {
                braces++;
            } else if (token.symbol("}")) {
                braces--;
            } else if (token.symbol(",") && parentheses == 0 && brackets == 0 && braces == 0) {
                ranges.add(new int[] {argumentStart, index});
                argumentStart = index + 1;
            }
        }
        ranges.add(new int[] {argumentStart, end});
        return ranges;
    }

    /**
     * The names among {@code candidates} the source defines or declares as functions: a return
     * type, the name, a parameter list, then a body or a semicolon.
     */
    static Set<String> userDefinedFunctions(List<GlslLexer.Token> tokens, Set<String> candidates) {
        Set<String> defined = new HashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER || !candidates.contains(token.text())) {
                continue;
            }
            int type = GlslLexer.previousSignificant(tokens, index);
            int open = GlslLexer.nextSignificant(tokens, index);
            if (type < 0 || tokens.get(type).kind() != GlslLexer.Kind.IDENTIFIER
                    || open < 0 || !tokens.get(open).symbol("(")) {
                continue;
            }
            int close = GlslLexer.matching(tokens, open, "(", ")");
            int after = close < 0 ? -1 : GlslLexer.nextSignificant(tokens, close);
            if (after >= 0 && (tokens.get(after).symbol("{") || tokens.get(after).symbol(";"))) {
                defined.add(token.text());
            }
        }
        return defined;
    }

    static String singleIdentifier(List<GlslLexer.Token> tokens, int[] range) {
        int first = -1;
        int last = -1;
        for (int index = range[0]; index < range[1]; index++) {
            if (!tokens.get(index).significant()) {
                continue;
            }
            if (first < 0) {
                first = index;
            }
            last = index;
        }
        if (first < 0 || first != last || tokens.get(first).kind() != GlslLexer.Kind.IDENTIFIER) {
            throw new IllegalArgumentException("legacy shadow sampler must be an identifier");
        }
        return tokens.get(first).text();
    }

    private static String argumentText(List<GlslLexer.Token> tokens, int[] range) {
        return GlslLexer.render(tokens.subList(range[0], range[1])).trim();
    }

    /** The private global a discarded gl_FragData[N] write goes to (PostTargetPlan.discardedOutputs). */
    static final String DISCARDED_OUTPUT = "chimeraDiscardedFragData";

    static String rewritePostOutputs(String source, PostTargetPlan targetPlan) {
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.identifier("gl_FragColor")) {
                tokens.set(index, identifier("chimeraFragColor0"));
                continue;
            }
            if (!token.identifier("gl_FragData")) {
                continue;
            }
            int open = GlslLexer.nextSignificant(tokens, index);
            int number = open < 0 ? -1 : GlslLexer.nextSignificant(tokens, open);
            int close = number < 0 ? -1 : GlslLexer.nextSignificant(tokens, number);
            if (open < 0 || number < 0 || close < 0
                    || !tokens.get(open).symbol("[")
                    || tokens.get(number).kind() != GlslLexer.Kind.NUMBER
                    || !tokens.get(close).symbol("]")) {
                throw new IllegalArgumentException("invalid gl_FragData access");
            }
            tokens.set(index, identifier(postOutputName(targetPlan, fragDataLocation(tokens.get(number)))));
            tokens.set(open, raw(""));
            tokens.set(number, raw(""));
            tokens.set(close, raw(""));
        }
        return GlslLexer.render(tokens);
    }

    private static int fragDataLocation(GlslLexer.Token number) {
        try {
            return Integer.parseInt(number.text());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("non-constant gl_FragData index");
        }
    }

    /** The output a {@code gl_FragData[location]} write goes to under the target plan. */
    private static String postOutputName(PostTargetPlan targetPlan, int location) {
        boolean discarded = targetPlan != null && targetPlan.discardedOutputs().contains(location);
        if (!discarded && (targetPlan == null || !targetPlan.outputLocations().contains(location))) {
            throw new IllegalArgumentException("fragment output is not in the target plan");
        }
        return (discarded ? DISCARDED_OUTPUT : "chimeraFragColor") + location;
    }

    static String rewriteSingleOutput(String source) {
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.identifier("gl_FragColor")) {
                tokens.set(index, identifier("fragColor"));
                continue;
            }
            if (!token.identifier("gl_FragData")) {
                continue;
            }
            int open = GlslLexer.nextSignificant(tokens, index);
            int number = open < 0 ? -1 : GlslLexer.nextSignificant(tokens, open);
            int close = number < 0 ? -1 : GlslLexer.nextSignificant(tokens, number);
            if (open < 0 || number < 0 || close < 0
                    || !tokens.get(open).symbol("[")
                    || !tokens.get(number).kind().equals(GlslLexer.Kind.NUMBER)
                    || !tokens.get(close).symbol("]")) {
                continue;
            }
            if (!tokens.get(number).text().equals("0")) {
                continue;
            }
            tokens.set(index, identifier("fragColor"));
            tokens.set(open, raw(""));
            tokens.set(number, raw(""));
            tokens.set(close, raw(""));
        }
        return GlslLexer.render(tokens);
    }

    public static boolean containsIdentifier(String source, String name) {
        for (GlslLexer.Token token : GlslLexer.lex(source)) {
            if (token.identifier(name)) {
                return true;
            }
        }
        return false;
    }

    public static int identifierCount(String source, String name) {
        int count = 0;
        for (GlslLexer.Token token : GlslLexer.lex(source)) {
            if (token.identifier(name)) count++;
        }
        return count;
    }

    static GlslLexer.Token identifier(String value) {
        return new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, value);
    }

    static GlslLexer.Token raw(String value) {
        return new GlslLexer.Token(GlslLexer.Kind.TRIVIA, value);
    }

}
