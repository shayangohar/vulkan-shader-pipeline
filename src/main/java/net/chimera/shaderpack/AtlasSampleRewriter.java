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


    /** Load-time bridge for resolved terrain atlas samples; a lookup of a parameter or local stays authored. */
    public static String rewriteTerrainAtlasSamples(String source, Set<String> atlasSamplers) {
        if (source == null || source.isBlank() || atlasSamplers.isEmpty()) return source;
        if (!GlslResourceUsage.analyze(source).successful()) return source;
        GlslTree tree = GlslTree.parse(source);
        List<GlslTree.Function> functions = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Function function && function.body() != null) functions.add(function);
        }
        if (functions.isEmpty()) return source;
        String prefix = atlasPrefix(tree.tokens());
        AtlasCalls calls = new AtlasCalls(tree, prefix, functions);
        for (var function : functions) calls.rewrite(function, atlasSamplers);
        if (!calls.plainRewritten && !calls.gradRewritten) return source;
        // The plain helper delegates so both call shapes share one
        // pixel-center correction; it is always emitted with its target.
        String helpers = calls.plainRewritten ? ATLAS_GRADIENT_HELPER + ATLAS_PLAIN_HELPER : ATLAS_GRADIENT_HELPER;
        String definitions = helpers.replace("{grad}", calls.gradHelper).replace("{plain}", calls.helper)
                .replace("{p}", prefix);
        // The helpers go just before the first function definition.
        int insertion = functions.get(0).range().start();
        calls.edits.add(new GlslTree.Edit(new GlslTree.Range(insertion, insertion + 1),
                definitions + tree.text(insertion)));
        return tree.print(calls.edits);
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

    /** Rewrites a source's atlas lookups as tree edits, remembering which helpers the rewritten calls need. */
    private static final class AtlasCalls {
        private final GlslTree tree;
        private final List<GlslLexer.Token> tokens;
        private final boolean[] directive;
        private final Set<String> callableShadows;
        private final String helper;
        private final String gradHelper;
        private final List<GlslTree.Edit> edits = new ArrayList<>();
        private boolean plainRewritten;
        private boolean gradRewritten;

        private AtlasCalls(GlslTree tree, String prefix, List<GlslTree.Function> functions) {
            this.tree = tree;
            this.tokens = tree.tokens();
            this.helper = prefix + "Sample";
            this.gradHelper = prefix + "SampleGrad";
            this.directive = tree.directiveTokens();
            this.callableShadows = callableShadows(functions);
        }

        /** The source's function names and macro names, which a lookup name may stand for. */
        private Set<String> callableShadows(List<GlslTree.Function> functions) {
            Set<String> names = new HashSet<>();
            for (var function : functions) names.add(tree.text(function.name()));
            // A macro with one of these names is not a known GLSL sampling operation.
            for (GlslTree.Directive value : tree.directives()) {
                int keyword = GlslLexer.nextSignificant(tokens, value.range().start());
                int name = keyword < 0 ? -1 : GlslLexer.nextSignificant(tokens, keyword);
                if (name >= 0 && tokens.get(keyword).identifier("define")) names.add(tokens.get(name).text());
            }
            return names;
        }

        /** Points one function's lookups of a global atlas sampler at the helpers. */
        private void rewrite(GlslTree.Function function, Set<String> atlasSamplers) {
            GlslTree.Range body = function.body().range();
            for (int i = body.start() + 1; i < body.end() - 1; i++) {
                var token = tokens.get(i);
                if (directive[i] || token.kind() != GlslLexer.Kind.IDENTIFIER
                        || !ATLAS_LOOKUPS.contains(token.text())
                        || callableShadows.contains(token.text())) continue;
                int sampler = lookupSampler(i);
                if (sampler < 0 || !atlasSamplers.contains(tokens.get(sampler).text()) || !global(sampler)) continue;
                boolean explicit = token.text().equals("textureGrad");
                edits.add(new GlslTree.Edit(new GlslTree.Range(i, i + 1), explicit ? gradHelper : helper));
                if (explicit) gradRewritten = true;
                else plainRewritten = true;
            }
        }

        /** Whether the sampler named at {@code index} is the global one, not a parameter or local. */
        private boolean global(int index) {
            GlslTree.Binding binding = tree.resolve(index);
            return binding == null || !GlslScopedReferences.insideFunction(tree, binding);
        }

        /**
         * The token of the sampler identifier the lookup call named at {@code name} reads, or -1 when
         * the call has an unsupported shape.
         */
        private int lookupSampler(int name) {
            int open = GlslLexer.nextSignificant(tokens, name);
            if (open < 0 || !tokens.get(open).symbol("(")) return -1;
            int close = GlslLexer.matching(tokens, open, "(", ")");
            if (close < 0) return -1;
            List<int[]> arguments = GlslTokenRewriter.argumentRanges(tokens, open + 1, close);
            // Plain lookups keep their two-argument shape; only authored
            // explicit-gradient textureGrad calls take the gradient path.
            // Every other arity is an unsupported overload: leave it.
            boolean explicit = tokens.get(name).text().equals("textureGrad");
            if (explicit ? arguments.size() != 4 : arguments.size() != 2) return -1;
            int[] range = arguments.get(0);
            int first = GlslLexer.nextSignificant(tokens, range[0] - 1);
            boolean single = first >= 0 && first < range[1] && tokens.get(first).kind() == GlslLexer.Kind.IDENTIFIER
                    && GlslLexer.nextSignificant(tokens, first) >= range[1];
            return single ? first : -1;
        }
    }

    static boolean atlasPrefixUsed(Set<String> identifiers, String prefix) {
        for (String name : identifiers) if (name.startsWith(prefix)) return true;
        return false;
    }
}
