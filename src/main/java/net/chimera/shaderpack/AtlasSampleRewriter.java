package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rewrites terrain atlas lookups so each samples the texel centre its gradients select: a mipmapped
 * tile then never bleeds into its neighbour. Ambiguous scopes keep the authored call.
 */
final class AtlasSampleRewriter {
    private AtlasSampleRewriter() {}

    /**
     * The explicit-gradient atlas helper: it samples the texel center the gradients select, so a
     * tile edge never bleeds into its neighbour. {@code {grad}} is its name and {@code {p}} the
     * prefix that keeps its locals clear of the pack's names.
     */
    static final String ATLAS_GRADIENT_HELPER = """
            vec4 {grad}(sampler2D {p}Tex, vec2 {p}Uv, vec2 {p}Du, vec2 {p}Dv) {
            vec2 {p}Pixel = 1.0 / vec2(textureSize({p}Tex, 0));
            vec2 {p}Screen = max(sqrt({p}Du * {p}Du + {p}Dv * {p}Dv), vec2(1e-20));
            vec2 {p}Coord = {p}Uv / {p}Pixel;
            vec2 {p}Center = round({p}Coord) - 0.5;
            vec2 {p}Offset = clamp(({p}Coord - {p}Center - 0.5) * {p}Pixel / {p}Screen + 0.5, 0.0, 1.0);
            return textureGrad({p}Tex, ({p}Center + {p}Offset) * {p}Pixel, {p}Du, {p}Dv);
            }
            """;

    /** The plain atlas helper, {@code {plain}}, which takes its gradients from the derivatives. */
    static final String ATLAS_PLAIN_HELPER = """
            vec4 {plain}(sampler2D {p}Tex, vec2 {p}Uv) {
            return {grad}({p}Tex, {p}Uv, dFdx({p}Uv), dFdy({p}Uv));
            }
            """;

    /** Load-time bridge for resolved terrain atlas samples; ambiguous scopes stay authored. */
    public static String rewriteTerrainAtlasSamples(String source, Set<String> atlasSamplers) {
        if (source == null || source.isBlank() || atlasSamplers.isEmpty()) return source;
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        GlslResourceUsage.Analysis analysis = GlslResourceUsage.analyze(source);
        if (!analysis.successful()) return source;
        List<GlslResourceUsage.FunctionDefinition> functions = new ArrayList<>(analysis.reachableFunctions());
        functions.addAll(analysis.unreachableFunctions());
        if (functions.isEmpty()) return source;
        String prefix = atlasPrefix(tokens);
        AtlasCalls calls = new AtlasCalls(tokens, prefix, functions);
        for (var function : functions) calls.rewrite(function, atlasSamplers);
        if (!calls.plainRewritten && !calls.gradRewritten) return source;
        int insertion = functions.stream().mapToInt(
                GlslResourceUsage.FunctionDefinition::definitionStart).min().orElseThrow();
        // The plain helper delegates so both call shapes share one
        // pixel-center correction; it is always emitted with its target.
        String helpers = calls.plainRewritten ? ATLAS_GRADIENT_HELPER + ATLAS_PLAIN_HELPER : ATLAS_GRADIENT_HELPER;
        String definitions = helpers.replace("{grad}", calls.gradHelper).replace("{plain}", calls.helper)
                .replace("{p}", prefix);
        tokens.add(insertion, GlslTokenRewriter.raw(definitions));
        return GlslLexer.render(tokens);
    }

    /** {@code chimeraAtlas}, with underscores added until no identifier in the source starts with it. */
    private static String atlasPrefix(List<GlslLexer.Token> tokens) {
        Set<String> identifiers = new HashSet<>();
        for (var token : tokens) if (token.kind() == GlslLexer.Kind.IDENTIFIER) identifiers.add(token.text());
        String prefix = "chimeraAtlas";
        while (atlasPrefixUsed(identifiers, prefix)) prefix += "_";
        return prefix;
    }

    private static final Set<String> ATLAS_LOOKUPS = Set.of("texture2D", "texture", "chimeraTexture", "textureGrad");

    /** Rewrites a source's atlas lookups in place, remembering which helpers the rewritten calls need. */
    private static final class AtlasCalls {
        private final List<GlslLexer.Token> tokens;
        private final boolean[] directive;
        private final Set<String> callableShadows;
        private final String helper;
        private final String gradHelper;
        private boolean plainRewritten;
        private boolean gradRewritten;

        private AtlasCalls(List<GlslLexer.Token> tokens, String prefix,
                           List<GlslResourceUsage.FunctionDefinition> functions) {
            this.tokens = tokens;
            this.helper = prefix + "Sample";
            this.gradHelper = prefix + "SampleGrad";
            this.directive = atlasDirectiveTokens(tokens);
            this.callableShadows = callableShadows(functions);
        }

        /** The source's function names and macro names, which a lookup name may stand for. */
        private Set<String> callableShadows(List<GlslResourceUsage.FunctionDefinition> functions) {
            Set<String> names = new HashSet<>();
            for (var function : functions) names.add(function.name());
            // A macro with one of these names is not a known GLSL sampling operation.
            for (int i = 0; i < tokens.size(); i++) {
                if (directive[i] && tokens.get(i).identifier("define")) {
                    int name = GlslLexer.nextSignificant(tokens, i);
                    if (name >= 0) names.add(tokens.get(name).text());
                }
            }
            return names;
        }

        /** Points one function's lookups of an atlas sampler it does not redeclare at the helpers. */
        private void rewrite(GlslResourceUsage.FunctionDefinition function, Set<String> atlasSamplers) {
            Set<String> eligible = eligibleSamplers(function, atlasSamplers);
            for (int i = function.bodyStart(); i < function.bodyEnd(); i++) {
                var token = tokens.get(i);
                if (directive[i] || token.kind() != GlslLexer.Kind.IDENTIFIER
                        || !ATLAS_LOOKUPS.contains(token.text())
                        || callableShadows.contains(token.text())) continue;
                String sampler = lookupSampler(i);
                if (sampler == null || !eligible.contains(sampler)) continue;
                boolean explicit = token.text().equals("textureGrad");
                tokens.set(i, GlslTokenRewriter.identifier(explicit ? gradHelper : helper));
                if (explicit) gradRewritten = true;
                else plainRewritten = true;
            }
        }

        /** The atlas samplers a function does not redeclare as a parameter or local. */
        private Set<String> eligibleSamplers(GlslResourceUsage.FunctionDefinition function,
                                             Set<String> atlasSamplers) {
            Set<String> eligible = new HashSet<>(atlasSamplers);
            for (int i = function.definitionStart(); i < function.bodyEnd(); i++) {
                if (directive[i] || !eligible.contains(tokens.get(i).text())) continue;
                int previous = GlslLexer.previousSignificant(tokens, i);
                if (previous >= 0 && tokens.get(previous).kind() == GlslLexer.Kind.IDENTIFIER
                        && !tokens.get(previous).identifier("return")) eligible.remove(tokens.get(i).text());
                // Multiple declarators and parameter lists: fail closed for the symbol.
                if (previous >= 0 && tokens.get(previous).symbol(",")) eligible.remove(tokens.get(i).text());
            }
            return eligible;
        }

        /**
         * The sampler identifier the lookup call named at {@code name} reads, or null when the call
         * has an unsupported shape.
         */
        private String lookupSampler(int name) {
            int open = GlslLexer.nextSignificant(tokens, name);
            if (open < 0 || !tokens.get(open).symbol("(")) return null;
            int close = GlslLexer.matching(tokens, open, "(", ")");
            if (close < 0) return null;
            List<int[]> arguments = GlslTokenRewriter.argumentRanges(tokens, open + 1, close);
            // Plain lookups keep their two-argument shape; only authored
            // explicit-gradient textureGrad calls take the gradient path.
            // Every other arity is an unsupported overload: leave it.
            boolean explicit = tokens.get(name).text().equals("textureGrad");
            if (explicit ? arguments.size() != 4 : arguments.size() != 2) return null;
            try {
                return GlslTokenRewriter.singleIdentifier(tokens, arguments.get(0));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    static boolean atlasPrefixUsed(Set<String> identifiers, String prefix) {
        for (String name : identifiers) if (name.startsWith(prefix)) return true;
        return false;
    }

    static boolean[] atlasDirectiveTokens(List<GlslLexer.Token> tokens) {
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
}
