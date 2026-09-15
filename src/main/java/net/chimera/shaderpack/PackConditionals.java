package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/** Shared bounded expression evaluator for shader and pack-property conditions. */
final class PackConditionals {
    static final int MAX_EXPRESSION_DEPTH = 64;

    private PackConditionals() {}

    /** Shared conditional state for shader and pack-property preparation. */
    static final class State {
        private final Map<String, String> macros = new TreeMap<>();
        private final Deque<Branch> branches = new ArrayDeque<>();

        State(Map<String, String> initialMacros) {
            if (initialMacros != null) {
                macros.putAll(initialMacros);
            }
        }

        boolean active() {
            return branches.isEmpty() || branches.peek().active();
        }

        Map<String, String> macros() {
            return macros;
        }

        String define(String name, String value) {
            return macros.put(name, value == null || value.isBlank() ? "1" : value.trim());
        }

        String undefine(String name) {
            return macros.remove(name);
        }

        void apply(String directive, String expression) {
            String argument = expression == null ? "" : expression.trim();
            switch (directive.toLowerCase()) {
                case "if", "ifdef", "ifndef" -> {
                    boolean parent = active();
                    String test = directive.equalsIgnoreCase("ifdef")
                            ? "defined(" + argument + ")"
                            : directive.equalsIgnoreCase("ifndef")
                            ? "!defined(" + argument + ")" : argument;
                    boolean result = parent && evaluate(test, macros);
                    branches.push(new Branch(parent, parent && result, result, false));
                }
                case "elif" -> {
                    if (branches.isEmpty()) {
                        throw new IllegalArgumentException("missing conditional");
                    }
                    Branch previous = branches.pop();
                    if (previous.elseSeen()) {
                        throw new IllegalArgumentException("conditional after else");
                    }
                    boolean result = previous.parentActive() && !previous.taken()
                            && evaluate(argument, macros);
                    branches.push(new Branch(previous.parentActive(),
                            previous.parentActive() && result,
                            previous.taken() || result, false));
                }
                case "else" -> {
                    if (branches.isEmpty()) {
                        throw new IllegalArgumentException("missing conditional");
                    }
                    Branch previous = branches.pop();
                    if (previous.elseSeen()) {
                        throw new IllegalArgumentException("duplicate else");
                    }
                    branches.push(new Branch(previous.parentActive(),
                            previous.parentActive() && !previous.taken(), true, true));
                }
                case "endif" -> {
                    if (branches.isEmpty()) {
                        throw new IllegalArgumentException("missing conditional");
                    }
                    branches.pop();
                }
                default -> throw new IllegalArgumentException("unsupported conditional");
            }
        }

        void finish() {
            if (!branches.isEmpty()) {
                throw new IllegalArgumentException("unterminated conditional");
            }
        }

        private record Branch(boolean parentActive, boolean active, boolean taken, boolean elseSeen) {}
    }

    static boolean evaluate(String expression, Map<String, String> macros) {
        return evaluate(expression, macros, false);
    }

    /**
     * Evaluates the small boolean expression language used by pack conditions.
     * Unknown names normally mean zero. Property program toggles may opt into
     * the Iris behavior where an unknown option name remains enabled.
     */
    static boolean evaluate(
            String expression,
            Map<String, String> macros,
            boolean unknownNamesAreTrue
    ) {
        return new ExpressionParser(expression, macros, unknownNamesAreTrue).parse();
    }

    private static final class ExpressionParser {
        private final String source;
        private final Map<String, String> macros;
        private final boolean unknownNamesAreTrue;
        private int index;
        private int recursionDepth;

        private ExpressionParser(
                String source,
                Map<String, String> macros,
                boolean unknownNamesAreTrue
        ) {
            this.source = source == null ? "" : source;
            this.macros = macros == null ? Map.of() : macros;
            this.unknownNamesAreTrue = unknownNamesAreTrue;
        }

        private boolean parse() {
            boolean result = parseOr();
            skipSpace();
            if (index != source.length()) {
                throw new IllegalArgumentException("unsupported expression");
            }
            return result;
        }

        private boolean parseOr() {
            boolean value = parseAnd();
            while (take("||")) {
                boolean right = parseAnd();
                value = value || right;
            }
            return value;
        }

        private boolean parseAnd() {
            boolean value = parseUnary();
            while (take("&&")) {
                boolean right = parseUnary();
                value = value && right;
            }
            return value;
        }

        private boolean parseUnary() {
            enterRecursion();
            try {
                if (take("!")) {
                    return !parseUnary();
                }
                if (take("(")) {
                    boolean value = parseOr();
                    require(")");
                    return value;
                }
                return parseComparison();
            } finally {
                recursionDepth--;
            }
        }

        private boolean parseComparison() {
            double left = parseValue();
            String operator = nextOperator();
            if (operator == null) {
                return left != 0.0;
            }
            double right = parseValue();
            return switch (operator) {
                case "==" -> left == right;
                case "!=" -> left != right;
                case ">" -> left > right;
                case ">=" -> left >= right;
                case "<" -> left < right;
                case "<=" -> left <= right;
                default -> throw new IllegalArgumentException("unsupported expression operator");
            };
        }

        private double parseValue() {
            enterRecursion();
            try {
                skipSpace();
                if (takeWord("defined")) {
                    skipSpace();
                    boolean parenthesized = take("(");
                    String name = identifier();
                    if (parenthesized) {
                        require(")");
                    }
                    return macros.containsKey(name) ? 1.0 : 0.0;
                }
                if (takeWord("true")) {
                    return 1.0;
                }
                if (takeWord("false")) {
                    return 0.0;
                }
                if (index < source.length()
                        && (source.charAt(index) == '+' || source.charAt(index) == '-')) {
                    char sign = source.charAt(index++);
                    double value = parseValue();
                    return sign == '-' ? -value : value;
                }
                if (index < source.length()
                        && (Character.isDigit(source.charAt(index)) || source.charAt(index) == '.')) {
                    return number();
                }
                String name = identifier();
                return resolveMacroValue(name, new HashSet<>());
            } finally {
                recursionDepth--;
            }
        }

        private double resolveMacroValue(String name, Set<String> visiting) {
            if (!visiting.add(name)) {
                return unknownNamesAreTrue ? 1.0 : 0.0;
            }
            String value = macros.get(name);
            if (value == null) {
                return unknownNamesAreTrue ? 1.0 : 0.0;
            }
            String normalized = value.trim();
            if (normalized.equalsIgnoreCase("true")) return 1.0;
            if (normalized.equalsIgnoreCase("false")) return 0.0;
            try {
                return Double.parseDouble(normalized.replace("f", "").replace("F", ""));
            } catch (NumberFormatException ignored) {
                if (normalized.matches("[A-Za-z_]\\w*")) {
                    return resolveMacroValue(normalized, visiting);
                }
                return unknownNamesAreTrue ? 1.0 : 0.0;
            }
        }

        private double number() {
            int start = index;
            boolean exponent = false;
            while (index < source.length()) {
                char value = source.charAt(index);
                if (Character.isDigit(value) || value == '.') {
                    index++;
                } else if ((value == 'e' || value == 'E') && !exponent) {
                    exponent = true;
                    index++;
                    if (index < source.length()
                            && (source.charAt(index) == '+' || source.charAt(index) == '-')) {
                        index++;
                    }
                } else {
                    break;
                }
            }
            return Double.parseDouble(source.substring(start, index));
        }

        private void enterRecursion() {
            if (++recursionDepth > MAX_EXPRESSION_DEPTH) {
                throw new IllegalArgumentException("expression is too deep");
            }
        }

        private String nextOperator() {
            for (String operator : List.of(">=", "<=", "==", "!=", ">", "<")) {
                if (take(operator)) {
                    return operator;
                }
            }
            return null;
        }

        private String identifier() {
            skipSpace();
            int start = index;
            if (index >= source.length()
                    || !(Character.isLetter(source.charAt(index)) || source.charAt(index) == '_')) {
                throw new IllegalArgumentException("identifier required");
            }
            index++;
            while (index < source.length()
                    && (Character.isLetterOrDigit(source.charAt(index))
                    || source.charAt(index) == '_')) {
                index++;
            }
            return source.substring(start, index);
        }

        private void require(String token) {
            if (!take(token)) {
                throw new IllegalArgumentException("token required");
            }
        }

        private boolean take(String token) {
            skipSpace();
            if (source.startsWith(token, index)) {
                index += token.length();
                return true;
            }
            return false;
        }

        private boolean takeWord(String token) {
            skipSpace();
            if (!source.startsWith(token, index)) {
                return false;
            }
            int end = index + token.length();
            if (end < source.length()
                    && (Character.isLetterOrDigit(source.charAt(end))
                    || source.charAt(end) == '_')) {
                return false;
            }
            index = end;
            return true;
        }

        private void skipSpace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }
    }
}
