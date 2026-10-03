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
        String gradHelper = prefix + "SampleGrad";
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
        boolean changedGrad = false;
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
                        || !Set.of("texture2D", "texture", "chimeraTexture", "textureGrad").contains(token.text())
                        || callableShadows.contains(token.text())) continue;
                int open = GlslLexer.nextSignificant(tokens, i);
                if (open < 0 || !tokens.get(open).symbol("(")) continue;
                int close = GlslLexer.matching(tokens, open, "(", ")");
                if (close < 0) continue;
                List<int[]> arguments = argumentRanges(tokens, open + 1, close);
                // Plain lookups keep their two-argument shape; only authored
                // explicit-gradient textureGrad calls take the gradient path.
                // Every other arity is an unsupported overload: leave it.
                boolean explicit = token.text().equals("textureGrad");
                if (explicit ? arguments.size() != 4 : arguments.size() != 2) continue;
                String sampler;
                try { sampler = singleIdentifier(tokens, arguments.get(0)); }
                catch (IllegalArgumentException ignored) { continue; }
                if (!eligible.contains(sampler)) continue;
                tokens.set(i, identifier(explicit ? gradHelper : helper));
                if (explicit) changedGrad = true;
                else changed = true;
            }
        }
        if (!changed && !changedGrad) return source;
        int insertion = functions.stream().mapToInt(GlslResourceUsage.FunctionDefinition::definitionStart).min().orElseThrow();
        String p = prefix;
        StringBuilder definitions = new StringBuilder();
        definitions.append("vec4 ").append(gradHelper).append("(sampler2D ").append(p).append("Tex, vec2 ").append(p).append("Uv, vec2 ").append(p).append("Du, vec2 ").append(p).append("Dv) {\n")
                .append("vec2 ").append(p).append("Pixel = 1.0 / vec2(textureSize(").append(p).append("Tex, 0));\n")
                .append("vec2 ").append(p).append("Screen = max(sqrt(").append(p).append("Du * ").append(p).append("Du + ").append(p).append("Dv * ").append(p).append("Dv), vec2(1e-20));\n")
                .append("vec2 ").append(p).append("Coord = ").append(p).append("Uv / ").append(p).append("Pixel;\n")
                .append("vec2 ").append(p).append("Center = round(").append(p).append("Coord) - 0.5;\n")
                .append("vec2 ").append(p).append("Offset = clamp((").append(p).append("Coord - ").append(p).append("Center - 0.5) * ").append(p).append("Pixel / ").append(p).append("Screen + 0.5, 0.0, 1.0);\n")
                .append("return textureGrad(").append(p).append("Tex, (").append(p).append("Center + ").append(p).append("Offset) * ").append(p).append("Pixel, ").append(p).append("Du, ").append(p).append("Dv);\n}\n");
        // The plain helper delegates so both call shapes share one
        // pixel-center correction; it is always emitted with its target.
        if (changed) {
            definitions.append("vec4 ").append(helper).append("(sampler2D ").append(p).append("Tex, vec2 ").append(p).append("Uv) {\n")
                    .append("return ").append(gradHelper).append("(").append(p).append("Tex, ").append(p).append("Uv, dFdx(").append(p).append("Uv), dFdy(").append(p).append("Uv));\n}\n");
        }
        tokens.add(insertion, raw(definitions.toString()));
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
        Set<String> names = new java.util.HashSet<>();
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
        Set<String> userDefined = userDefinedFunctions(tokens,
                Set.of("shadow2D", "shadow2DLod", "texture2DShadow"));
        // Work from the innermost calls outward. This keeps nested legacy
        // lookups valid when the outer coordinate expression is rendered.
        for (int index = tokens.size() - 1; index >= 0; index--) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER
                    || (!token.text().equals("shadow2D")
                    && !token.text().equals("shadow2DLod")
                    && !token.text().equals("texture2DShadow"))
                    || userDefined.contains(token.text())) {
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
     * Splits {@code [layout(...)] uniform T a, b[2], c = x;} into one
     * declaration per name. GLSL gives each declarator the same qualifiers and
     * type, so this changes nothing a shader can observe; it lets every
     * per-name rewriter (bindings, advanced images) find each declaration.
     * Interface blocks are left alone.
     */
    static String splitUniformDeclarators(String source) {
        if (source == null || !source.contains("uniform") || !source.contains(",")) return source;
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        boolean changed = false;
        int depth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("{")) depth++;
            if (token.symbol("}")) depth--;
            if (depth != 0 || !token.identifier("uniform") || inDirective(tokens, index)) continue;
            int start = index;
            int before = GlslLexer.previousSignificant(tokens, index);
            if (before >= 0 && tokens.get(before).symbol(")")) {
                int open = matchingBackward(tokens, before);
                int keyword = open < 0 ? -1 : GlslLexer.previousSignificant(tokens, open);
                if (keyword >= 0 && tokens.get(keyword).identifier("layout")) start = keyword;
            }
            int type = GlslLexer.nextSignificant(tokens, index);
            while (type >= 0 && PRECISION_QUALIFIERS.contains(tokens.get(type).text())) {
                type = GlslLexer.nextSignificant(tokens, type);
            }
            int first = type < 0 ? -1 : GlslLexer.nextSignificant(tokens, type);
            if (first < 0 || tokens.get(first).symbol("{")
                    || tokens.get(type).kind() != GlslLexer.Kind.IDENTIFIER) continue;
            List<int[]> declarators = new ArrayList<>();
            int declaratorStart = first;
            int nesting = 0;
            int end = -1;
            for (int cursor = first; cursor < tokens.size(); cursor++) {
                GlslLexer.Token current = tokens.get(cursor);
                if (current.symbol("(") || current.symbol("[")) nesting++;
                if (current.symbol(")") || current.symbol("]")) nesting--;
                if (nesting == 0 && (current.symbol(",") || current.symbol(";"))) {
                    declarators.add(new int[] {declaratorStart, cursor});
                    declaratorStart = cursor + 1;
                    if (current.symbol(";")) {
                        end = cursor;
                        break;
                    }
                }
            }
            if (end < 0 || declarators.size() < 2) continue;
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
            index = end;
            changed = true;
        }
        return changed ? GlslLexer.render(tokens) : source;
    }

    private static final Set<String> PRECISION_QUALIFIERS = Set.of("lowp", "mediump", "highp");

    private static final java.util.regex.Pattern ALIAS_DEFINE = java.util.regex.Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*(?://.*)?$");
    /** Group 1 is the directive through the macro name; group 2 is the name. */
    private static final java.util.regex.Pattern MACRO_DEFINE = java.util.regex.Pattern.compile(
            "(\\s*#\\s*define\\s+([A-Za-z_]\\w*))");
    private static final java.util.regex.Pattern UNDEF = java.util.regex.Pattern.compile(
            "^\\s*#\\s*undef\\s+([A-Za-z_]\\w*)\\s*$");
    private static final java.util.regex.Pattern SAMPLER_DECLARATION = java.util.regex.Pattern.compile(
            "\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?[iu]?sampler\\w*\\s+([A-Za-z_]\\w*)");

    /**
     * Expands object-like macros that name a declared sampler
     * ({@code #define SRC_SAMPLER colortex0}), as Iris's full preprocessing
     * does before any analysis, so sampler use is visible to planning. Only
     * those aliases are touched, in source order: a later {@code #undef} or
     * redefinition ends the alias, and every other macro stays as written.
     */
    static String expandSamplerAliases(String source) {
        if (source == null || !source.contains("#")) return source;
        Set<String> samplers = new java.util.HashSet<>();
        java.util.regex.Matcher declared = SAMPLER_DECLARATION.matcher(source);
        while (declared.find()) samplers.add(declared.group(1));
        if (samplers.isEmpty()) return source;
        // Directives are read line by line; substitution runs over one token
        // stream, so a block comment spanning lines is never lexed in pieces.
        String[] lines = source.split("\n", -1);
        List<Map<String, String>> active = new ArrayList<>(lines.length);
        boolean[] drop = new boolean[lines.length];
        boolean[] macro = new boolean[lines.length];
        Map<String, String> aliases = new java.util.HashMap<>();
        boolean changed = false;
        for (int line = 0; line < lines.length; line++) {
            String body = lines[line].endsWith("\r")
                    ? lines[line].substring(0, lines[line].length() - 1) : lines[line];
            java.util.regex.Matcher define = ALIAS_DEFINE.matcher(body);
            java.util.regex.Matcher undef = UNDEF.matcher(body);
            java.util.regex.Matcher named = MACRO_DEFINE.matcher(body);
            if (define.matches()) {
                String target = aliases.getOrDefault(define.group(2), define.group(2));
                aliases.remove(define.group(1));
                if (samplers.contains(target) && !define.group(1).equals(target)) {
                    aliases.put(define.group(1), target);
                    drop[line] = true;
                    changed = true;
                } else {
                    macro[line] = true;
                }
            } else if (undef.matches() && aliases.remove(undef.group(1)) != null) {
                drop[line] = true;
            } else if (named.lookingAt()) {
                // Any other definition of an alias name ends that alias; other
                // macro bodies may use an alias and expand after it is gone.
                aliases.remove(named.group(2));
                macro[line] = true;
            }
            active.add(Map.copyOf(aliases));
        }
        if (!changed) return source;
        StringBuilder result = new StringBuilder(source.length());
        int line = 0;
        int previous = -1;
        List<GlslLexer.Token> tokens = GlslLexer.lex(source);
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            String text = token.text();
            if (drop[line]) {
                result.append(lineBreaks(text));
            } else if (token.kind() == GlslLexer.Kind.IDENTIFIER
                    && !(previous >= 0 && tokens.get(previous).symbol("."))
                    && !(macro[line] && previous >= 0 && tokens.get(previous).identifier("define"))
                    && active.get(line).containsKey(text)) {
                result.append(active.get(line).get(text));
            } else {
                result.append(text);
            }
            if (token.significant()) previous = index;
            for (int at = 0; at < text.length(); at++) if (text.charAt(at) == '\n') line++;
        }
        return result.toString();
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
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        Set<String> userFunctions = globalFunctionNames(tokens);
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
            boolean statementStart = previous < 0 || tokens.get(previous).symbol(";")
                    || tokens.get(previous).symbol("}") || firstOnLine(tokens, index);
            if (depth == 0 && parentheses == 0 && token.identifier("const") && statementStart) {
                ConstDeclaration declaration = parseConstDeclaration(tokens, index);
                if (declaration != null) declarations.add(declaration);
            }
            previous = index;
        }
        Set<String> constant = new java.util.HashSet<>();
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
        boolean relaxed = false;
        for (ConstDeclaration declaration : declarations) {
            if (constant.containsAll(declaration.names())) continue;
            tokens.set(declaration.keyword(), raw(""));
            relaxed = true;
        }
        return relaxed ? GlslLexer.render(tokens) : source;
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
                int nesting = 0;
                int end = -1;
                for (int index = next + 1; index < tokens.size(); index++) {
                    GlslLexer.Token token = tokens.get(index);
                    if (token.symbol("(") || token.symbol("[") || token.symbol("{")) nesting++;
                    if (token.symbol(")") || token.symbol("]") || token.symbol("}")) nesting--;
                    if (nesting == 0 && (token.symbol(",") || token.symbol(";"))) {
                        end = index;
                        break;
                    }
                }
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
        Set<String> names = new java.util.HashSet<>();
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
        return new MainFunction(tokens, nameIndex, bodyOpen, main.bodyEnd());
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

    /**
     * The names among {@code candidates} the source defines or declares as functions: a return
     * type, the name, a parameter list, then a body or a semicolon.
     */
    static Set<String> userDefinedFunctions(List<GlslLexer.Token> tokens, Set<String> candidates) {
        Set<String> defined = new java.util.HashSet<>();
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
