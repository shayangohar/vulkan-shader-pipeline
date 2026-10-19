package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongBinaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A conditional directive's expression as Iris's preprocessor, JCPP 1.4.14, evaluates it. Macros
 * expand first, but a {@code defined} operand does not. Every value is a C {@code long}, with C's
 * operators and precedence. A number keeps its integer part ({@code NumericValue.longValue}):
 * {@code 0.5} reads 0 and {@code 1.5e1} reads 10. A name no macro defines reads 0, {@code true} and
 * {@code false} included. Division or modulus by zero reads 0. Shrimple sizes its light bins with
 * {@code (LIGHT_SIZE_XZ*LIGHT_SIZE_XZ*LIGHT_SIZE_Y * LIGHT_BIN_SIZE3) < (2048*2048)}.
 */
final class PreprocessorExpression {
    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(?:([A-Za-z_]\\w*)|(\\.?\\d(?:[eEpP][-+]|[\\w.])*)|(&&|\\|\\||<<|>>|<=|>=|==|!=|[-+*/%<>&^|!~?:()]))");
    private static final Pattern DECIMAL = Pattern.compile("(\\d*)(?:\\.\\d*)?(?:[eE]([-+]?\\d+))?[uUlLfF]*");
    private static final int MAX_TOKENS = 100_000;

    private final List<String> tokens;
    private int index;
    private int depth;

    private PreprocessorExpression(List<String> tokens) {
        this.tokens = tokens;
    }

    /** Whether {@code expression} holds under {@code macros}; throws when it is not one JCPP evaluates. */
    static boolean evaluate(String expression, Map<String, String> macros) {
        List<String> expanded = expand(lex(expression), macros, new HashSet<>(), new int[1]);
        PreprocessorExpression parser = new PreprocessorExpression(expanded);
        long value = parser.conditional();
        if (parser.index != expanded.size()) throw new IllegalArgumentException("unsupported expression");
        return value != 0;
    }

    private static List<String> lex(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text == null ? "" : text);
        int position = 0;
        while (position < matcher.regionEnd()) {
            if (!matcher.find(position) || matcher.start() != position) {
                if (text.substring(position).isBlank()) break;
                throw new IllegalArgumentException("unsupported expression token");
            }
            result.add(matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            position = matcher.end();
        }
        return result;
    }

    /** {@code tokens} with each object-like macro replaced by its expansion; a macro is hidden inside its own. */
    private static List<String> expand(List<String> tokens, Map<String, String> macros, Set<String> hidden,
                                       int[] budget) {
        List<String> result = new ArrayList<>();
        for (int at = 0; at < tokens.size(); at++) {
            if ((budget[0] += 1) > MAX_TOKENS) throw new IllegalArgumentException("expression is too long");
            String token = tokens.get(at);
            if (token.equals("defined")) {
                at = defined(tokens, at, macros, result);
                continue;
            }
            String value = identifier(token) && !hidden.contains(token) ? macros.get(token) : null;
            if (value == null) {
                result.add(token);
                continue;
            }
            // ShaderSourcePreprocessor keeps a function-like macro as "NAME(params) body".
            if (value.startsWith(token + "(")) throw new IllegalArgumentException("function-like macro in condition");
            hidden.add(token);
            result.addAll(expand(lex(value), macros, hidden, budget));
            hidden.remove(token);
        }
        return result;
    }

    /** {@code defined NAME} or {@code defined(NAME)} as 1 or 0; returns the last token it read. */
    private static int defined(List<String> tokens, int at, Map<String, String> macros, List<String> result) {
        int next = at + 1;
        boolean parenthesized = next < tokens.size() && tokens.get(next).equals("(");
        if (parenthesized) next++;
        if (next >= tokens.size() || !identifier(tokens.get(next))) {
            throw new IllegalArgumentException("defined needs a name");
        }
        result.add(macros.containsKey(tokens.get(next)) ? "1" : "0");
        if (parenthesized && (++next >= tokens.size() || !tokens.get(next).equals(")"))) {
            throw new IllegalArgumentException("defined needs a closing parenthesis");
        }
        return next;
    }

    private static boolean identifier(String token) {
        return Character.isLetter(token.charAt(0)) || token.charAt(0) == '_';
    }

    private long conditional() {
        long condition = binary(0);
        if (!take("?")) return condition;
        long whenTrue = conditional();
        if (!take(":")) throw new IllegalArgumentException("conditional needs a colon");
        long whenFalse = conditional();
        return condition != 0 ? whenTrue : whenFalse;
    }

    /** Operators bind tighter as the level rises, as in C; each level is left-associative. */
    private static final List<List<String>> LEVELS = List.of(List.of("||"), List.of("&&"), List.of("|"),
            List.of("^"), List.of("&"), List.of("==", "!="), List.of("<", ">", "<=", ">="), List.of("<<", ">>"),
            List.of("+", "-"), List.of("*", "/", "%"));

    /** Each binary operator on two longs; JCPP reports division or modulus by zero and goes on with 0. */
    private static final Map<String, LongBinaryOperator> OPERATORS = Map.ofEntries(
            Map.entry("||", (left, right) -> truth(left != 0 || right != 0)),
            Map.entry("&&", (left, right) -> truth(left != 0 && right != 0)),
            Map.entry("|", (left, right) -> left | right),
            Map.entry("^", (left, right) -> left ^ right),
            Map.entry("&", (left, right) -> left & right),
            Map.entry("==", (left, right) -> truth(left == right)),
            Map.entry("!=", (left, right) -> truth(left != right)),
            Map.entry("<", (left, right) -> truth(left < right)),
            Map.entry(">", (left, right) -> truth(left > right)),
            Map.entry("<=", (left, right) -> truth(left <= right)),
            Map.entry(">=", (left, right) -> truth(left >= right)),
            Map.entry("<<", (left, right) -> left << right),
            Map.entry(">>", (left, right) -> left >> right),
            Map.entry("+", Long::sum),
            Map.entry("-", (left, right) -> left - right),
            Map.entry("*", (left, right) -> left * right),
            Map.entry("/", (left, right) -> right == 0 ? 0 : left / right),
            Map.entry("%", (left, right) -> right == 0 ? 0 : left % right));

    private long binary(int level) {
        if (level == LEVELS.size()) return unary();
        long left = binary(level + 1);
        while (index < tokens.size() && LEVELS.get(level).contains(tokens.get(index))) {
            LongBinaryOperator operator = OPERATORS.get(tokens.get(index++));
            left = operator.applyAsLong(left, binary(level + 1));
        }
        return left;
    }

    private static long truth(boolean value) {
        return value ? 1 : 0;
    }

    private long unary() {
        if (index >= tokens.size()) throw new IllegalArgumentException("expression ends early");
        if (++depth > PackConditionals.MAX_EXPRESSION_DEPTH) {
            throw new IllegalArgumentException("expression is too deep");
        }
        try {
            return operand(tokens.get(index++));
        } finally {
            depth--;
        }
    }

    private long operand(String token) {
        return switch (token) {
            case "!" -> unary() == 0 ? 1 : 0;
            case "~" -> ~unary();
            case "-" -> -unary();
            case "+" -> unary();
            case "(" -> {
                long value = conditional();
                if (!take(")")) throw new IllegalArgumentException("missing closing parenthesis");
                yield value;
            }
            default -> identifier(token) ? 0 : number(token);
        };
    }

    /** JCPP's NumericValue.longValue: the integer part in its base, scaled by a decimal exponent. */
    static long number(String token) {
        String text = token.toLowerCase(Locale.ROOT);
        if (text.startsWith("0x")) {
            return Long.parseLong(text.substring(2).replaceAll("[ul]+$", ""), 16);
        }
        Matcher matcher = DECIMAL.matcher(token);
        if (!Character.isDigit(text.charAt(0)) && text.charAt(0) != '.' || !matcher.matches()) {
            throw new IllegalArgumentException("unsupported number");
        }
        String integer = matcher.group(1);
        int base = integer.length() > 1 && integer.startsWith("0") && integer.matches("[0-7]+") ? 8 : 10;
        long value = integer.isEmpty() ? 0 : Long.parseLong(integer, base);
        return matcher.group(2) == null ? value : (long) (value * Math.pow(10, Integer.parseInt(matcher.group(2))));
    }

    private boolean take(String token) {
        if (index < tokens.size() && tokens.get(index).equals(token)) {
            index++;
            return true;
        }
        return false;
    }
}
