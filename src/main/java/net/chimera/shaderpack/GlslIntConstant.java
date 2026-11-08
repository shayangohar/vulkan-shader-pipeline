package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.LongBinaryOperator;

/**
 * An integral constant expression of literals, read as GL reads an array size: decimal, octal and
 * hexadecimal integer literals with or without {@code u}, parentheses, unary {@code + - ~} and the
 * binary {@code * / % + - << >> & ^ |} with C's precedence. Photon sizes its histogram varying
 * {@code vec4[HISTOGRAM_BINS / 4]}, which reaches translation as {@code vec4[32 / 4]}. A name, a
 * float, a comparison, or a value GL leaves undefined (division by zero, a shift outside 0..31, a
 * result outside {@code int}) is not read.
 */
final class GlslIntConstant {
    /** Binary operators, binding tighter as the level rises; each level is left-associative. */
    private static final List<List<String>> LEVELS = List.of(List.of("|"), List.of("^"), List.of("&"),
            List.of("<<", ">>"), List.of("+", "-"), List.of("*", "/", "%"));

    /** An expression this does not read; it carries no stack trace. */
    private static final class Unreadable extends RuntimeException {
        Unreadable() {
            super(null, null, false, false);
        }
    }

    private final List<GlslLexer.Token> tokens;
    private int index;

    private GlslIntConstant(List<GlslLexer.Token> tokens) {
        this.tokens = tokens;
    }

    /**
     * The value of the significant tokens from {@code from} up to {@code to}, or empty when this
     * does not read them.
     */
    static OptionalInt value(List<GlslLexer.Token> tokens, int from, int to) {
        if (from < 0 || to > tokens.size() || from >= to) return OptionalInt.empty();
        List<GlslLexer.Token> significant = tokens.subList(from, to).stream()
                .filter(GlslLexer.Token::significant).toList();
        GlslIntConstant parser = new GlslIntConstant(significant);
        try {
            long value = parser.binary(0);
            return parser.index == significant.size() ? OptionalInt.of((int) value) : OptionalInt.empty();
        } catch (Unreadable unreadable) {
            return OptionalInt.empty();
        }
    }

    private long binary(int level) {
        if (level == LEVELS.size()) return unary();
        long left = binary(level + 1);
        while (index < tokens.size() && tokens.get(index).kind() == GlslLexer.Kind.SYMBOL
                && LEVELS.get(level).contains(tokens.get(index).text())) {
            String operator = tokens.get(index++).text();
            left = apply(operator, left, binary(level + 1));
        }
        return left;
    }

    /** Each binary operator on two longs; a divisor of zero or a shift outside 0..31 is not read. */
    private static final Map<String, LongBinaryOperator> OPERATORS = Map.of(
            "|", (left, right) -> left | right,
            "^", (left, right) -> left ^ right,
            "&", (left, right) -> left & right,
            "<<", (left, right) -> left << shift(right),
            ">>", (left, right) -> left >> shift(right),
            "+", Long::sum,
            "-", (left, right) -> left - right,
            "*", (left, right) -> left * right,
            "/", (left, right) -> left / divisor(right),
            "%", (left, right) -> left % divisor(right));

    private static long apply(String operator, long left, long right) {
        return checked(OPERATORS.get(operator).applyAsLong(left, right));
    }

    private static long divisor(long value) {
        if (value == 0) throw new Unreadable();
        return value;
    }

    private static long shift(long value) {
        if (value < 0 || value > 31) throw new Unreadable();
        return value;
    }

    private long unary() {
        if (index >= tokens.size()) throw new Unreadable();
        GlslLexer.Token token = tokens.get(index++);
        if (token.kind() == GlslLexer.Kind.NUMBER) return literal(token.text());
        if (token.symbol("-")) return checked(-unary());
        if (token.symbol("+")) return unary();
        if (token.symbol("~")) return ~unary();
        if (token.symbol("(")) {
            long value = binary(0);
            if (index >= tokens.size() || !tokens.get(index++).symbol(")")) throw new Unreadable();
            return value;
        }
        throw new Unreadable();
    }

    /** A GLSL integer literal's value; a float, or one outside {@code int}, is not read. */
    private static long literal(String text) {
        String digits = text.endsWith("u") || text.endsWith("U") ? text.substring(0, text.length() - 1) : text;
        int base = 10;
        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            digits = digits.substring(2);
            base = 16;
        } else if (digits.length() > 1 && digits.startsWith("0")) {
            base = 8;
        }
        if (digits.isEmpty() || digits.length() > 10) throw new Unreadable();
        try {
            return checked(Long.parseLong(digits, base));
        } catch (NumberFormatException notInteger) {
            throw new Unreadable();
        }
    }

    private static long checked(long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new Unreadable();
        return value;
    }
}
