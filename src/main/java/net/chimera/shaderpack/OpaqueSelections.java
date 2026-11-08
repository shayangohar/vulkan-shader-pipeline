package net.chimera.shaderpack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A sampler or image chosen by a conditional as a call's first argument:
 * {@code textureLod(altFrame ? texDiffuseRT : texDiffuseRT_alt, uv, 0)}. NVIDIA's GL compiler
 * takes an opaque value as a {@code ?:} operand; GLSL does not, and glslang refuses it ("no
 * operation ':' exists that takes a left-hand operand of type ... sampler2D"). Shrimple's traced
 * lighting (composite11.csh, composite12) reads its ping-pong history this way, so its RTX profile
 * fell back. The choice moves out of the call, which then reads the texel GL's would:
 * {@code ((altFrame) ? textureLod(texDiffuseRT, uv, 0) : textureLod(texDiffuseRT_alt, uv, 0))}.
 * Only the branch taken runs, so the other arguments still run once.
 */
final class OpaqueSelections {
    private OpaqueSelections() {}

    /** A declaration of an opaque variable or parameter: its type, then its name. */
    private static final Pattern OPAQUE_DECLARATION = Pattern.compile(
            "\\b[iu]?(?:sampler|image)[0-9A-Za-z]*\\s+([A-Za-z_]\\w*)");
    /** Rewrites inside a rewritten call's other arguments wait for the next round; this bounds the rounds. */
    private static final int MAX_ROUNDS = 8;

    /** {@code source} with every call's conditional opaque first argument moved out of the call. */
    static String hoist(String source) {
        if (source == null || source.indexOf('?') < 0) return source;
        Set<String> opaque = opaqueNames(source);
        if (opaque.isEmpty()) return source;
        String result = source;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            String next = hoistOnce(result, opaque);
            if (next.equals(result)) return result;
            result = next;
        }
        return result;
    }

    private static Set<String> opaqueNames(String source) {
        Set<String> names = new HashSet<>();
        Matcher declaration = OPAQUE_DECLARATION.matcher(GlslLexer.stripComments(source));
        while (declaration.find()) names.add(declaration.group(1));
        return names;
    }

    /** One left-to-right pass: each call found rewritten, its arguments left for the next pass. */
    private static String hoistOnce(String source, Set<String> opaque) {
        List<GlslLexer.Token> tokens = GlslLexer.lex(source);
        StringBuilder result = new StringBuilder();
        int copied = 0;
        for (int index = 0; index < tokens.size(); index++) {
            Selection selection = selectionAt(tokens, index, opaque);
            if (selection == null) continue;
            result.append(GlslLexer.render(tokens.subList(copied, selection.name())));
            result.append(selection.rewrite(tokens));
            copied = selection.close() + 1;
            index = selection.close();
        }
        if (copied == 0) return source;
        return result.append(GlslLexer.render(tokens.subList(copied, tokens.size()))).toString();
    }

    /**
     * A call at {@code open}, its opening parenthesis, whose first argument is {@code c ? a : b}
     * with {@code a} and {@code b} opaque names: the tokens of the call's name, the {@code ?},
     * {@code a}, {@code b}, the end of the first argument and the closing parenthesis.
     */
    private record Selection(int name, int open, int question, int first, int second, int argumentEnd,
                             int close) {
        String rewrite(List<GlslLexer.Token> tokens) {
            String callee = GlslLexer.render(tokens.subList(name, open + 1));
            String condition = GlslLexer.render(tokens.subList(open + 1, question)).trim();
            String rest = GlslLexer.render(tokens.subList(argumentEnd, close + 1));
            return "((" + condition + ") ? " + callee + tokens.get(first).text() + rest + " : " + callee
                    + tokens.get(second).text() + rest + ")";
        }
    }

    private static Selection selectionAt(List<GlslLexer.Token> tokens, int open, Set<String> opaque) {
        if (!tokens.get(open).symbol("(")) return null;
        int name = GlslLexer.previousSignificant(tokens, open);
        if (name < 0 || tokens.get(name).kind() != GlslLexer.Kind.IDENTIFIER) return null;
        int close = GlslLexer.matching(tokens, open, "(", ")");
        Argument argument = close < 0 ? null : firstArgument(tokens, open, close);
        if (argument == null) return null;
        int first = GlslLexer.nextSignificant(tokens, argument.question());
        int second = GlslLexer.nextSignificant(tokens, argument.colon());
        boolean names = first == GlslLexer.previousSignificant(tokens, argument.colon())
                && second == GlslLexer.previousSignificant(tokens, argument.end());
        if (!names || !opaque.contains(tokens.get(first).text()) || !opaque.contains(tokens.get(second).text())) {
            return null;
        }
        return new Selection(name, open, argument.question(), first, second, argument.end(), close);
    }

    /** A call's first argument: the token it ends at, and its one top-level {@code ?} and {@code :}. */
    private record Argument(int end, int question, int colon) {}

    /** The first argument of the call opened at {@code open}, when it is one top-level conditional; else null. */
    private static Argument firstArgument(List<GlslLexer.Token> tokens, int open, int close) {
        int depth = 0;
        int question = -1;
        int colon = -1;
        for (int index = open + 1; index < close; index++) {
            GlslLexer.Token token = tokens.get(index);
            int nesting = nesting(token);
            depth += nesting;
            if (depth != 0 || nesting != 0) continue;
            if (token.symbol(",")) return conditional(index, question, colon);
            if (token.symbol("?")) {
                if (question >= 0) return null;
                question = index;
            } else if (token.symbol(":")) {
                if (colon >= 0) return null;
                colon = index;
            }
        }
        return conditional(close, question, colon);
    }

    private static Argument conditional(int end, int question, int colon) {
        return question < 0 || colon < question ? null : new Argument(end, question, colon);
    }

    /** +1 for a token that opens a bracket, -1 for one that closes it, else 0. */
    private static int nesting(GlslLexer.Token token) {
        if (token.symbol("(") || token.symbol("[") || token.symbol("{")) return 1;
        return token.symbol(")") || token.symbol("]") || token.symbol("}") ? -1 : 0;
    }
}
