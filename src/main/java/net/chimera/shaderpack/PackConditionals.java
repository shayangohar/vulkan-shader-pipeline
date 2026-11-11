package net.chimera.shaderpack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared bounded expression evaluator for shader and pack-property conditions. */
final class PackConditionals {
    static final int MAX_EXPRESSION_DEPTH = 64;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");

    private PackConditionals() {}

    /**
     * A directive's text as a C preprocessor such as Iris's JCPP reads it: each block comment is
     * one space, and the text ends at a line comment. A block comment still open at the end is cut
     * there; the line reader (ShaderSourcePreprocessor.LineReader) first joins the lines up to its
     * close, as the comment spans them. A comment mark inside a string or character literal is
     * text ({@link #literalEnd}). Read raw, a block comment after {@code #if FOO} became operators,
     * and the condition evaluated 0.
     */
    static String withoutComments(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int mark = nextCommentOrLiteral(text, index);
            if (mark < 0) return result.append(text, index, text.length()).toString();
            result.append(text, index, mark);
            if (text.startsWith("//", mark)) return result.toString();
            if (text.startsWith("/*", mark)) {
                int end = text.indexOf("*/", mark + 2);
                if (end < 0) return result.toString();
                result.append(' ');
                index = end + 2;
            } else {
                index = literalEnd(text, mark);
                result.append(text, mark, index);
            }
        }
        return result.toString();
    }

    /**
     * Where the next comment or literal in {@code text} starts, from {@code from} on: a {@code /*},
     * a {@code //}, a {@code "} or a {@code '}; or -1. JCPP lexes a quote as a string or character
     * literal, and a comment mark inside one is text.
     */
    static int nextCommentOrLiteral(String text, int from) {
        for (int index = from; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '"' || value == '\'') return index;
            if (value == '/' && index + 1 < text.length()
                    && (text.charAt(index + 1) == '*' || text.charAt(index + 1) == '/')) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The end of the literal whose quote is at {@code start}, as JCPP's lexer reads it on one line.
     * A string runs to its closing {@code "}, a backslash escaping the next character. A character
     * literal is one character (or an escape) and its closing {@code '}; without that close it runs
     * to the next {@code '}. Either one still open at the end of the line ends there: Iris reads
     * {@code jack_o'lantern /* x} as a broken character literal, not a comment.
     */
    static int literalEnd(String text, int start) {
        int index = start + 1;
        if (text.charAt(start) == '"') {
            while (index < text.length()) {
                char value = text.charAt(index);
                if (value == '"') return index + 1;
                index += value == '\\' ? 2 : 1;
            }
            return text.length();
        }
        if (index >= text.length()) return text.length();
        if (text.charAt(index) == '\'') return index + 1;
        index += text.charAt(index) == '\\' ? 2 : 1;
        if (index < text.length() && text.charAt(index) == '\'') return index + 1;
        int close = index < text.length() ? text.indexOf('\'', index) : -1;
        return close < 0 ? text.length() : close + 1;
    }

    /**
     * {@code text} with every identifier that names a macro replaced by its value, again inside
     * that value, as the preprocessor expands a shaders.properties value: {@code size.buffer.colortex1
     * = REFLECTION_RES REFLECTION_RES} reads as {@code 0.5 0.5}. A macro is not expanded inside its
     * own expansion.
     */
    static String expandMacros(String text, Map<String, String> macros) {
        return expandMacros(text, macros, new HashSet<>());
    }

    /**
     * {@code text} with its macros expanded ({@link #expandMacros}) outside its comments and
     * literals, as Iris preprocesses a properties file: JCPP keeps a comment as written
     * (KEEPCOMMENTS) and expands no name inside a literal ({@link #literalEnd}). {@code inComment}
     * is whether a block comment is open where the text starts.
     */
    static String expandOutsideComments(String text, boolean inComment, Map<String, String> macros) {
        StringBuilder result = new StringBuilder(text.length());
        int index = 0;
        if (inComment) {
            int close = text.indexOf("*/");
            index = close < 0 ? text.length() : close + 2;
            result.append(text, 0, index);
        }
        while (index < text.length()) {
            int mark = nextCommentOrLiteral(text, index);
            result.append(expandMacros(text.substring(index, mark < 0 ? text.length() : mark), macros));
            if (mark < 0) break;
            int end;
            if (text.startsWith("//", mark)) {
                end = text.length();
            } else if (text.startsWith("/*", mark)) {
                int close = text.indexOf("*/", mark + 2);
                end = close < 0 ? text.length() : close + 2;
            } else {
                end = literalEnd(text, mark);
            }
            result.append(text, mark, end);
            index = end;
        }
        return result.toString();
    }

    private static String expandMacros(String text, Map<String, String> macros, Set<String> expanding) {
        Matcher identifier = IDENTIFIER.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        int last = 0;
        while (identifier.find()) {
            String name = identifier.group();
            String value = macros.get(name);
            if (value == null || expanding.contains(name)) continue;
            expanding.add(name);
            result.append(text, last, identifier.start()).append(expandMacros(value, macros, expanding));
            expanding.remove(name);
            last = identifier.end();
        }
        return result.append(text, last, text.length()).toString();
    }

    /** Shared conditional state for shader and pack-property preparation. */
    static final class State {
        private final Map<String, String> macros = new TreeMap<>();
        private final Deque<Branch> branches = new ArrayDeque<>();
        private boolean ended;

        State(Map<String, String> initialMacros) {
            if (initialMacros != null) {
                macros.putAll(initialMacros);
            }
        }

        boolean active() {
            return !ended && (branches.isEmpty() || branches.peek().active());
        }

        Map<String, String> macros() {
            return macros;
        }

        /** Defines {@code name}; one defined with no body expands to nothing, as in JCPP. */
        String define(String name, String value) {
            return macros.put(name, value == null ? "" : value.trim());
        }

        String undefine(String name) {
            return macros.remove(name);
        }

        /**
         * Applies one conditional directive; false when it was ignored. An #elif, #else or #endif
         * with no open #if is reported and skipped, as Iris's preprocessor (JCPP) does: Photon's
         * TAAU program ends in a stray #endif and still runs under Iris.
         */
        boolean apply(String directive, String expression) {
            String argument = expression == null ? "" : withoutComments(expression).trim();
            boolean closing = directive.equalsIgnoreCase("elif") || directive.equalsIgnoreCase("else")
                    || directive.equalsIgnoreCase("endif");
            if (closing && branches.isEmpty()) return false;
            switch (directive.toLowerCase()) {
                case "if", "ifdef", "ifndef" -> {
                    boolean parent = active();
                    boolean result = parent && PreprocessorExpression.evaluate(condition(directive, argument), macros);
                    branches.push(new Branch(parent, parent && result, result, false));
                }
                case "elif" -> elseIf(argument);
                case "else" -> otherwise();
                case "endif" -> closeBranch();
                default -> throw new IllegalArgumentException("unsupported conditional");
            }
            return true;
        }

        /**
         * The expression an if, ifdef or ifndef directive tests. #ifdef and #ifndef name one
         * identifier, and a C preprocessor such as Iris's JCPP skips the rest of the line: Bloop
         * and others write {@code #ifdef IRIS_TAG_SUPPORT > 1}.
         */
        private static String condition(String directive, String argument) {
            boolean ifdef = directive.equalsIgnoreCase("ifdef");
            if (!ifdef && !directive.equalsIgnoreCase("ifndef")) return argument;
            Matcher name = IDENTIFIER.matcher(argument);
            String tested = name.lookingAt() ? name.group() : argument;
            return (ifdef ? "" : "!") + "defined(" + tested + ")";
        }

        /** Takes the next branch; a failure leaves the open branch as it was. */
        private void elseIf(String argument) {
            Branch previous = branches.peek();
            if (previous.elseSeen()) {
                throw new IllegalArgumentException("conditional after else");
            }
            boolean result = previous.parentActive() && !previous.taken()
                    && PreprocessorExpression.evaluate(argument, macros);
            branches.pop();
            branches.push(new Branch(previous.parentActive(),
                    previous.parentActive() && result,
                    previous.taken() || result, false));
        }

        private void otherwise() {
            Branch previous = branches.peek();
            if (previous.elseSeen()) {
                throw new IllegalArgumentException("duplicate else");
            }
            branches.pop();
            branches.push(new Branch(previous.parentActive(),
                    previous.parentActive() && !previous.taken(), true, true));
        }

        /** Whether the innermost open branch has passed its #else. */
        boolean afterElse() {
            return !branches.isEmpty() && branches.peek().elseSeen();
        }

        /**
         * Turns the rest of the text off, as JCPP does after a stray #else or #elif: branches
         * opened later stay inactive, and no #endif turns it back on.
         */
        void endAll() {
            ended = true;
        }

        /** Pops the innermost open branch; there must be one. */
        private Branch closeBranch() {
            if (branches.isEmpty()) {
                throw new IllegalArgumentException("missing conditional");
            }
            return branches.pop();
        }

        /** Whether a conditional is still open. */
        boolean open() {
            return !branches.isEmpty();
        }

        void finish() {
            if (!branches.isEmpty()) {
                throw new IllegalArgumentException("unterminated conditional");
            }
        }

        private record Branch(boolean parentActive, boolean active, boolean taken, boolean elseSeen) {}
    }

    /** A conditional directive's expression, as JCPP evaluates it ({@link PreprocessorExpression}). */
    static boolean evaluate(String expression, Map<String, String> macros) {
        return PreprocessorExpression.evaluate(expression, macros);
    }

    /**
     * Evaluates the small boolean expression language of shaders.properties program switches
     * ({@code program.X.enabled}). Unknown names mean zero, unless the switch opts into the Iris
     * behavior where an unknown option name remains enabled.
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
