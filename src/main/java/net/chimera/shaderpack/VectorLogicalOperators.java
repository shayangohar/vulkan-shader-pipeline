package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code ||} between boolean vectors, inside {@code any()}: Shrimple's parallax check (gbuffers_block,
 * POM displacement) reads {@code any(isnan(b[0]) || isinf(b[0]) || isnan(b[1]) || isinf(b[1]))}.
 * NVIDIA's GL compiler takes {@code ||} and {@code &&} between vectors component-wise, so Iris
 * loads the pack; GLSL takes them on scalars only, and glslang refuses ("no operation '||' exists
 * that takes a left-hand operand of type ... 2-component vector of bool"). {@code any} of a
 * component-wise or is the or of each operand's {@code any}, and {@code all} of a component-wise
 * and is the and of each one's {@code all}, so the call moves onto each operand:
 * {@code (any(isnan(b[0])) || any(isinf(b[0])) || ...)}. Each operand is a call to a built-in that
 * returns a boolean vector for a vector argument, so the source never compiled as written. The
 * operands have no side effects, so stopping at the first true one changes nothing.
 */
final class VectorLogicalOperators {
    private VectorLogicalOperators() {}

    /** The built-ins that return a boolean vector for a vector argument. */
    private static final Set<String> VECTOR_RELATIONS = Set.of("lessThan", "lessThanEqual", "greaterThan",
            "greaterThanEqual", "equal", "notEqual", "not", "isnan", "isinf");
    /** A rewritten call's operands are rewritten in the next round; this bounds the rounds. */
    private static final int MAX_ROUNDS = 4;

    /** {@code source} with each {@code any} of an or, and {@code all} of an and, of boolean vectors distributed. */
    static String distribute(String source) {
        if (source == null || !source.contains("||") && !source.contains("&&")) return source;
        String result = source;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            String next = distributeOnce(result);
            if (next.equals(result)) return result;
            result = next;
        }
        return result;
    }

    private static String distributeOnce(String source) {
        List<GlslLexer.Token> tokens = GlslLexer.lex(source);
        StringBuilder result = new StringBuilder();
        int copied = 0;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            String operator = token.identifier("any") ? "||" : token.identifier("all") ? "&&" : null;
            if (operator == null || memberAccess(tokens, index)) continue;
            int open = GlslLexer.nextSignificant(tokens, index);
            if (open < 0 || !tokens.get(open).symbol("(")) continue;
            int close = GlslLexer.matching(tokens, open, "(", ")");
            List<String> operands = close < 0 ? null : operands(tokens, open, close, operator);
            if (operands == null) continue;
            result.append(GlslLexer.render(tokens.subList(copied, index)));
            List<String> calls = operands.stream().map(operand -> token.text() + "(" + operand + ")").toList();
            result.append('(').append(String.join(" " + operator + " ", calls)).append(')');
            copied = close + 1;
            index = close;
        }
        if (copied == 0) return source;
        return result.append(GlslLexer.render(tokens.subList(copied, tokens.size()))).toString();
    }

    private static boolean memberAccess(List<GlslLexer.Token> tokens, int index) {
        int previous = GlslLexer.previousSignificant(tokens, index);
        return previous >= 0 && tokens.get(previous).symbol(".");
    }

    /**
     * The argument between {@code open} and {@code close}, split at its top-level {@code operator}s,
     * when there are two operands or more and each is one call to a vector relation; else null.
     */
    private static List<String> operands(List<GlslLexer.Token> tokens, int open, int close, String operator) {
        List<String> operands = new ArrayList<>();
        int start = open + 1;
        int depth = 0;
        for (int index = open + 1; index <= close; index++) {
            GlslLexer.Token token = tokens.get(index);
            if (index < close && token.kind() == GlslLexer.Kind.SYMBOL) {
                if (token.symbol("(") || token.symbol("[")) depth++;
                else if (token.symbol(")") || token.symbol("]")) depth--;
                if (depth != 0 || !token.symbol(operator)) continue;
            } else if (index < close) {
                continue;
            }
            String operand = vectorRelation(tokens, start, index);
            if (operand == null) return null;
            operands.add(operand);
            start = index + 1;
        }
        return operands.size() < 2 ? null : operands;
    }

    /** The text of tokens {@code [start, end)} when they are exactly one call to a vector relation; else null. */
    private static String vectorRelation(List<GlslLexer.Token> tokens, int start, int end) {
        int name = GlslLexer.nextSignificant(tokens, start - 1);
        if (name < 0 || name >= end || tokens.get(name).kind() != GlslLexer.Kind.IDENTIFIER
                || !VECTOR_RELATIONS.contains(tokens.get(name).text())) {
            return null;
        }
        int open = GlslLexer.nextSignificant(tokens, name);
        if (open < 0 || open >= end || !tokens.get(open).symbol("(")) return null;
        int close = GlslLexer.matching(tokens, open, "(", ")");
        if (close < 0 || close >= end) return null;
        int after = GlslLexer.nextSignificant(tokens, close);
        if (after >= 0 && after < end) return null;
        return GlslLexer.render(tokens.subList(name, close + 1));
    }
}
