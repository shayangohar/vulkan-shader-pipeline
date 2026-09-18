package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Token-safe rewrites for the small legacy-to-Vulkan GLSL bridge. */
public final class GlslTokenRewriter {
    private GlslTokenRewriter() {}

    /** Load-time bridge for resolved terrain atlas samples; ambiguous scopes stay authored. */
    public static String rewriteTerrainAtlasSamples(String source, Set<String> atlasSamplers) {
        if (source == null || source.isBlank() || atlasSamplers.isEmpty()) return source;
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        GlslResourceUsage.Analysis analysis = GlslResourceUsage.analyze(source);
        if (!analysis.successful()) return source;
        List<GlslResourceUsage.FunctionDefinition> functions = new ArrayList<>(analysis.reachableFunctions());
        functions.addAll(analysis.unreachableFunctions());
        if (functions.isEmpty()) return source;
        Set<String> identifiers = new java.util.HashSet<>();
        for (var token : tokens) if (token.kind() == GlslLexer.Kind.IDENTIFIER) identifiers.add(token.text());
        String prefix = "chimeraAtlas";
        while (atlasPrefixUsed(identifiers, prefix)) prefix += "_";
        String helper = prefix + "Sample";
        boolean[] directive = atlasDirectiveTokens(tokens);
        Set<String> callableShadows = new java.util.HashSet<>();
        for (var function : functions) callableShadows.add(function.name());
        // A macro with one of these names is not a known GLSL sampling operation.
        for (int i = 0; i < tokens.size(); i++) {
            if (directive[i] && tokens.get(i).identifier("define")) {
                int name = GlslLexer.nextSignificant(tokens, i);
                if (name >= 0) callableShadows.add(tokens.get(name).text());
            }
        }
        boolean changed = false;
        for (var function : functions) {
            Set<String> eligible = new java.util.HashSet<>(atlasSamplers);
            for (int i = function.definitionStart(); i < function.bodyEnd(); i++) {
                if (directive[i] || !eligible.contains(tokens.get(i).text())) continue;
                int previous = GlslLexer.previousSignificant(tokens, i);
                if (previous >= 0 && tokens.get(previous).kind() == GlslLexer.Kind.IDENTIFIER
                        && !tokens.get(previous).identifier("return")) eligible.remove(tokens.get(i).text());
                // Multiple declarators and parameter lists: fail closed for the symbol.
                if (previous >= 0 && tokens.get(previous).symbol(",")) eligible.remove(tokens.get(i).text());
            }
            for (int i = function.bodyStart(); i < function.bodyEnd(); i++) {
                var token = tokens.get(i);
                if (directive[i] || token.kind() != GlslLexer.Kind.IDENTIFIER
                        || !Set.of("texture2D", "texture", "chimeraTexture").contains(token.text())
                        || callableShadows.contains(token.text())) continue;
                int open = GlslLexer.nextSignificant(tokens, i);
                if (open < 0 || !tokens.get(open).symbol("(")) continue;
                int close = GlslLexer.matching(tokens, open, "(", ")");
                if (close < 0) continue;
                List<int[]> arguments = argumentRanges(tokens, open + 1, close);
                if (arguments.size() != 2) continue;
                String sampler;
                try { sampler = singleIdentifier(tokens, arguments.get(0)); }
                catch (IllegalArgumentException ignored) { continue; }
                if (!eligible.contains(sampler)) continue;
                tokens.set(i, identifier(helper));
                changed = true;
            }
        }
        if (!changed) return source;
        int insertion = functions.stream().mapToInt(GlslResourceUsage.FunctionDefinition::definitionStart).min().orElseThrow();
        String p = prefix;
        String definition = "vec4 " + helper + "(sampler2D " + p + "Tex, vec2 " + p + "Uv) {\n"
                + "vec2 " + p + "Pixel = 1.0 / vec2(textureSize(" + p + "Tex, 0));\n"
                + "vec2 " + p + "Du = dFdx(" + p + "Uv), " + p + "Dv = dFdy(" + p + "Uv);\n"
                + "vec2 " + p + "Screen = max(sqrt(" + p + "Du * " + p + "Du + " + p + "Dv * " + p + "Dv), vec2(1e-20));\n"
                + "vec2 " + p + "Coord = " + p + "Uv / " + p + "Pixel;\n"
                + "vec2 " + p + "Center = round(" + p + "Coord) - 0.5;\n"
                + "vec2 " + p + "Offset = clamp((" + p + "Coord - " + p + "Center - 0.5) * " + p + "Pixel / " + p + "Screen + 0.5, 0.0, 1.0);\n"
                + "return textureGrad(" + p + "Tex, (" + p + "Center + " + p + "Offset) * " + p + "Pixel, " + p + "Du, " + p + "Dv);\n}\n";
        tokens.add(insertion, raw(definition));
        return GlslLexer.render(tokens);
    }

    private static boolean atlasPrefixUsed(Set<String> identifiers, String prefix) {
        for (String name : identifiers) if (name.startsWith(prefix)) return true;
        return false;
    }

    private static boolean[] atlasDirectiveTokens(List<GlslLexer.Token> tokens) {
        boolean[] result = new boolean[tokens.size()];
        boolean lineStart = true, inDirective = false, escaped = false;
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (lineStart && token.symbol("#")) inDirective = true;
            result[i] = inDirective;
            if (token.text().contains("\n") || token.text().contains("\r")) {
                if (!escaped) inDirective = false;
                lineStart = true;
                escaped = false;
            } else if (token.significant()) {
                lineStart = false;
                escaped = token.symbol("\\");
            }
        }
        return result;
    }

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
        // Work from the innermost calls outward. This keeps nested legacy
        // lookups valid when the outer coordinate expression is rendered.
        for (int index = tokens.size() - 1; index >= 0; index--) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER
                    || (!token.text().equals("shadow2D")
                    && !token.text().equals("shadow2DLod")
                    && !token.text().equals("texture2DShadow"))) {
                continue;
            }
            int open = GlslLexer.nextSignificant(tokens, index);
            int close = open < 0 ? -1 : GlslLexer.matching(tokens, open, "(", ")");
            if (open < 0 || !tokens.get(open).symbol("(") || close < 0) {
                throw new IllegalArgumentException("unbalanced legacy shadow call");
            }
            List<int[]> arguments = argumentRanges(tokens, open + 1, close);
            boolean lodCall = token.text().equals("shadow2DLod");
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
            boolean scalarCall = token.text().equals("texture2DShadow");
            String expression;
            if (samplerType.equals("sampler2DShadow")) {
                expression = (scalarCall ? "texture(" : "vec4(texture(")
                        + sampler + ", " + coordinate;
                if (lodCall) {
                    expression = (scalarCall ? "textureLod(" : "vec4(textureLod(")
                            + sampler + ", " + coordinate
                            + ", " + argumentText(tokens, arguments.get(2))
                            + (scalarCall ? ")" : "))");
                } else {
                    expression += scalarCall ? ")" : "))";
                }
            } else if (samplerType.equals("sampler2D")) {
                String depthSample = lodCall
                        ? "textureLod(" + sampler + ", " + coordinate + ".xy, "
                        + argumentText(tokens, arguments.get(2)) + ").r"
                        : "texture(" + sampler + ", " + coordinate + ".xy).r";
                expression = scalarCall
                        ? "step(" + coordinate + ".z, " + depthSample + ")"
                        : "vec4(step(" + coordinate + ".z, " + depthSample + "))";
            } else {
                throw new IllegalArgumentException("unsupported legacy shadow sampler type: "
                        + samplerType);
            }
            tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, expression));
            for (int clear = index + 1; clear <= close; clear++) {
                tokens.set(clear, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, ""));
            }
        }
        return GlslLexer.render(tokens);
    }

    /**
     * Wraps the one real GLSL main function and appends statements after the
     * authored body. The lexer and resource analysis make comments, strings,
     * helper functions, and identifiers containing "main" irrelevant.
     */
    static String appendMainEpilogue(String source, String epilogue) {
        if (source == null || epilogue == null || epilogue.isBlank()) return source;
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
        GlslResourceUsage.FunctionDefinition main = mains.get(0);
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        int nameIndex = -1;
        for (int index = main.definitionStart(); index < main.bodyStart(); index++) {
            if (tokens.get(index).identifier("main")) {
                nameIndex = index;
                break;
            }
        }
        if (nameIndex < 0) throw new IllegalArgumentException("main function name is missing");
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
        String authored = uniqueIdentifier(source, "chimeraAuthoredMain");
        String wrapper = uniqueIdentifier(source, "chimeraGeneratedMain");
        tokens.set(nameIndex, identifier(authored));
        String indented = indent(epilogue);
        String generated = "\nvoid " + wrapper + "() {\n    " + authored + "();\n"
                + indented + "\n}\nvoid main() {\n    " + wrapper + "();\n}\n";
        tokens.add(main.bodyEnd() + 1, raw(generated));
        return GlslLexer.render(tokens);
    }

    /** Returns a source-safe identifier that is absent from the token stream. */
    static String uniqueIdentifier(String source, String base) {
        String candidate = base == null || base.isBlank() ? "chimeraGenerated" : base;
        Set<String> identifiers = new java.util.HashSet<>();
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

    private static List<int[]> argumentRanges(List<GlslLexer.Token> tokens, int start, int end) {
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
            int location;
            try {
                location = Integer.parseInt(tokens.get(number).text());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("non-constant gl_FragData index");
            }
            if (targetPlan == null || !targetPlan.outputLocations().contains(location)) {
                throw new IllegalArgumentException("fragment output is not in the target plan");
            }
            tokens.set(index, identifier("chimeraFragColor" + location));
            tokens.set(open, raw(""));
            tokens.set(number, raw(""));
            tokens.set(close, raw(""));
        }
        return GlslLexer.render(tokens);
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

    private static GlslLexer.Token identifier(String value) {
        return new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, value);
    }

    private static GlslLexer.Token raw(String value) {
        return new GlslLexer.Token(GlslLexer.Kind.TRIVIA, value);
    }

}
