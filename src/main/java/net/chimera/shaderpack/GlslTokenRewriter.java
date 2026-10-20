package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Rewrites for the legacy-to-Vulkan GLSL bridge. The function and declaration rewrites read and
 * edit the parse tree (KNOW-737): unreachable functions, main's prologue and epilogue, reserved
 * parameter names, uniform declarator lists and global consts. The rest stay token rewrites,
 * because the tree holds expressions as token ranges:
 * <ul>
 *   <li>renaming a name everywhere it is spelled, which built-ins and the authored entry point
 *       need whatever scope they appear in ({@link #replaceIdentifiers}), and a sampler named like
 *       a lookup function, which only its calls tell apart ({@link #renameSamplerIdentifier});</li>
 *   <li>rewriting calls and their arguments: texture lookups, bounded texelFetch and legacy
 *       shadow lookups, innermost first;</li>
 *   <li>replacing {@code gl_FragData[N]} subscripts with the outputs they write;</li>
 *   <li>deciding whether a const initializer is a constant expression;</li>
 *   <li>asking whether, or how often, a name is spelled ({@link #containsIdentifier}).</li>
 * </ul>
 */
public final class GlslTokenRewriter {
    private GlslTokenRewriter() {}

    /**
     * Blanks complete unreachable function definitions while preserving line
     * breaks. Source positions remain stable for compiler diagnostics and the
     * later declaration rewrites cannot leave dead resource references behind.
     * Directive lines inside a blanked function stay: a #define there holds for
     * the rest of the file (Complementary defines WATER_FOG_MULT_M in an unused
     * GetWaterFog and uses it later, where the identical redefinition is folded).
     */
    static String removeUnreachableFunctions(
            String source, GlslResourceUsage.Analysis usage
    ) {
        if (source == null || usage == null || !usage.successful()
                || usage.unreachableFunctions().isEmpty()) {
            return source;
        }
        GlslTree tree = GlslTree.parse(source);
        boolean[] directives = tree.directiveTokens();
        List<GlslTree.Edit> edits = new ArrayList<>();
        for (GlslResourceUsage.FunctionDefinition function : usage.unreachableFunctions()) {
            int run = -1;
            for (int index = function.definitionStart(); index <= function.definitionEnd() + 1; index++) {
                boolean blank = index <= function.definitionEnd() && !directives[index];
                if (blank && run < 0) run = index;
                if (blank || run < 0) continue;
                edits.add(new GlslTree.Edit(new GlslTree.Range(run, index),
                        lineBreaks(tree.text(new GlslTree.Range(run, index)))));
                run = -1;
            }
        }
        return tree.print(edits);
    }

    private static String lineBreaks(String text) {
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
        if (!GlslResourceUsage.analyze(source).successful()) return source;
        GlslTree tree = GlslTree.parse(source);
        String replacement = uniqueIdentifier(source, "chimeraSamplerParameter");
        List<GlslTree.Edit> edits = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Function function) || function.body() == null
                    || function.parameters().stream().noneMatch(value -> tree.text(value.name()).equals("sampler"))) {
                continue;
            }
            for (int index = function.range().start(); index < function.range().end(); index++) {
                if (tree.tokens().get(index).identifier("sampler")) {
                    edits.add(new GlslTree.Edit(new GlslTree.Range(index, index + 1), replacement));
                }
            }
        }
        return edits.isEmpty() ? source : tree.print(edits);
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
        Set<String> userDefined = userDefinedFunctions(GlslTree.parse(source), LEGACY_SHADOW_CALLS);
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
        GlslTree tree = GlslTree.parse(source);
        GlslTree.Function main = mainDefinition(tree);
        String authored = uniqueIdentifier(source, "chimeraAuthoredMain");
        String wrapper = uniqueIdentifier(source, "chimeraGeneratedMain");
        String generated = "\nvoid " + wrapper + "() {\n    " + authored + "();\n"
                + indent(epilogue) + "\n}\nvoid main() {\n    " + wrapper + "();\n}\n";
        int close = main.body().range().end() - 1;
        return tree.print(List.of(new GlslTree.Edit(new GlslTree.Range(main.name(), main.name() + 1), authored),
                new GlslTree.Edit(new GlslTree.Range(close + 1, close + 1), generated)));
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
        GlslTree tree = GlslTree.parse(source);
        List<GlslTree.Edit> edits = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Declaration declaration) || declaration.declarators().size() < 2
                    || declaration.type() == null || declaration.type().struct() != null
                    || declaration.qualifiers().stream()
                    .noneMatch(value -> tree.text(value.keyword()).equals("uniform"))) {
                continue;
            }
            // The qualifiers and type, as written, start every declaration of the list.
            String prefix = tree.text(new GlslTree.Range(declaration.range().start(),
                    declaration.type().range().end()));
            StringBuilder split = new StringBuilder();
            for (GlslTree.Declarator declarator : declaration.declarators()) {
                if (split.length() > 0) split.append('\n');
                split.append(prefix).append(' ').append(tree.text(declarator.range())).append(';');
            }
            edits.add(new GlslTree.Edit(declaration.range(), split.toString()));
        }
        return edits.isEmpty() ? source : tree.print(edits);
    }

    /**
     * Desktop GL drivers accept a global {@code const} whose initializer is
     * not a constant expression (a uniform, a plain global, a user function);
     * Vulkan GLSL rejects it. Drop {@code const} from exactly those
     * declarations, following dependencies until nothing changes, so every
     * truly constant global keeps it (array sizes and other consts need it).
     */
    static String relaxNonConstantGlobals(String source) {
        if (source == null || !source.contains("const")) return source;
        GlslTree tree = GlslTree.parse(source);
        Set<String> userFunctions = userDefinedFunctions(tree, null);
        List<ConstDeclaration> declarations = globalConstDeclarations(tree);
        Set<String> constant = constantNames(tree.tokens(), declarations, userFunctions);
        List<GlslTree.Edit> edits = new ArrayList<>();
        for (ConstDeclaration declaration : declarations) {
            if (constant.containsAll(declaration.names())) continue;
            edits.add(new GlslTree.Edit(new GlslTree.Range(declaration.keyword(), declaration.keyword() + 1), ""));
        }
        return edits.isEmpty() ? source : tree.print(edits);
    }

    /** Every file-scope {@code const} declaration, with its names and initializer token ranges, in source order. */
    private static List<ConstDeclaration> globalConstDeclarations(GlslTree tree) {
        List<ConstDeclaration> declarations = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Declaration declaration) || declaration.type() == null) continue;
            GlslTree.Qualifier keyword = declaration.qualifiers().stream()
                    .filter(value -> tree.text(value.keyword()).equals("const")).findFirst().orElse(null);
            if (keyword == null || declaration.declarators().isEmpty()) continue;
            List<String> names = new ArrayList<>();
            List<int[]> initializers = new ArrayList<>();
            for (GlslTree.Declarator declarator : declaration.declarators()) {
                names.add(tree.text(declarator.name()));
                if (declarator.initializer() != null) {
                    initializers.add(new int[] {declarator.initializer().start(), declarator.initializer().end()});
                }
            }
            declarations.add(new ConstDeclaration(keyword.keyword(), names, initializers));
        }
        return declarations;
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

    /** Inserts statements at the start of the one real GLSL main body (Iris prependMainFunctionBody). */
    static String prependMainPrologue(String source, String prologue) {
        if (source == null || prologue == null || prologue.isBlank()) return source;
        GlslTree tree = GlslTree.parse(source);
        int open = mainDefinition(tree).body().range().start();
        return tree.print(List.of(new GlslTree.Edit(new GlslTree.Range(open + 1, open + 1),
                "\n" + indent(prologue))));
    }

    /** The source's one main definition; only {@code void main()} is supported. */
    private static GlslTree.Function mainDefinition(GlslTree tree) {
        List<GlslTree.Function> mains = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Function function && function.body() != null
                    && tree.text(function.name()).equals("main")) {
                mains.add(function);
            }
        }
        if (mains.size() != 1) {
            throw new IllegalArgumentException(mains.isEmpty()
                    ? "main function is missing" : "main function is ambiguous");
        }
        GlslTree.Function main = mains.get(0);
        int returnType = GlslLexer.previousSignificant(tree.tokens(), main.name());
        if (returnType < 0 || !tree.tokens().get(returnType).identifier("void")) {
            throw new IllegalArgumentException("main signature is unsupported");
        }
        GlslTree.Range parameters = main.parameterList();
        if (GlslLexer.nextSignificant(tree.tokens(), parameters.start()) != parameters.end() - 1) {
            throw new IllegalArgumentException("main parameters are unsupported");
        }
        return main;
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
     * The names the source defines or declares as functions at file scope, among {@code candidates}
     * when they are given.
     */
    static Set<String> userDefinedFunctions(GlslTree tree, Set<String> candidates) {
        Set<String> defined = new HashSet<>();
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Function function
                    && (candidates == null || candidates.contains(tree.text(function.name())))) {
                defined.add(tree.text(function.name()));
            }
        }
        return defined;
    }

    private static String singleIdentifier(List<GlslLexer.Token> tokens, int[] range) {
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

    /**
     * True when {@code name} appears as an identifier token, outside comments and literals. Most
     * names asked about are absent, and then the text cannot hold the token, so it is not lexed.
     */
    public static boolean containsIdentifier(String source, String name) {
        if (source == null || !source.contains(name)) return false;
        for (GlslLexer.Token token : GlslLexer.lex(source)) {
            if (token.identifier(name)) {
                return true;
            }
        }
        return false;
    }

    /** True when an identifier token, outside comments and literals, is one {@code accepted} takes. */
    public static boolean containsIdentifier(String source, Predicate<String> accepted) {
        if (source == null) return false;
        for (GlslLexer.Token token : GlslLexer.lex(source)) {
            if (token.kind() == GlslLexer.Kind.IDENTIFIER && accepted.test(token.text())) return true;
        }
        return false;
    }

    public static int identifierCount(String source, String name) {
        if (source == null || !source.contains(name)) return 0;
        int count = 0;
        for (GlslLexer.Token token : GlslLexer.lex(source)) {
            if (token.identifier(name)) count++;
        }
        return count;
    }

    private static GlslLexer.Token identifier(String value) {
        return new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, value);
    }

    private static GlslLexer.Token raw(String value) {
        return new GlslLexer.Token(GlslLexer.Kind.TRIVIA, value);
    }

}
