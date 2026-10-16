package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small lossless lexer for the token rewrites used by the pack bridge. */
final class GlslLexer {
    /** One shared string per ASCII symbol, so single-character tokens allocate nothing. */
    private static final String[] SINGLE_CHARACTERS = singleCharacters();

    enum Kind {
        IDENTIFIER,
        NUMBER,
        TRIVIA,
        LITERAL,
        SYMBOL
    }

    record Token(Kind kind, String text) {
        boolean identifier(String value) {
            return kind == Kind.IDENTIFIER && text.equals(value);
        }

        boolean symbol(String value) {
            return kind == Kind.SYMBOL && text.equals(value);
        }

        boolean significant() {
            return kind != Kind.TRIVIA;
        }
    }

    private GlslLexer() {}

    static List<Token> lex(String source) {
        String input = source == null ? "" : source;
        synchronized (RECENT) {
            List<Token> known = RECENT.get(input);
            if (known != null) return known;
        }
        List<Token> tokens = lexUncached(input);
        synchronized (RECENT) {
            RECENT.put(input, tokens);
        }
        return tokens;
    }

    /**
     * The latest token lists. A program's planning passes each lex the source they are handed, and
     * many hand on the same text unchanged.
     */
    private static final Map<String, List<Token>> RECENT = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Token>> eldest) {
            return size() > 8;
        }
    };

    private static List<Token> lexUncached(String input) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < input.length()) {
            Token token = tokenAt(input, index);
            tokens.add(token);
            index += token.text().length();
        }
        return List.copyOf(tokens);
    }

    /** The one token that starts at {@code index}, classified by its first characters. */
    private static Token tokenAt(String input, int index) {
        char current = input.charAt(index);
        if (Character.isWhitespace(current)) {
            return new Token(Kind.TRIVIA, input.substring(index, whitespaceEnd(input, index)));
        }
        if (input.startsWith("//", index)) {
            return new Token(Kind.TRIVIA, input.substring(index, lineCommentEnd(input, index)));
        }
        if (input.startsWith("/*", index)) {
            return new Token(Kind.TRIVIA, input.substring(index, blockCommentEnd(input, index)));
        }
        if (current == '"' || current == '\'') {
            return new Token(Kind.LITERAL, input.substring(index, literalEnd(input, index)));
        }
        if (Character.isLetter(current) || current == '_') {
            return new Token(Kind.IDENTIFIER, input.substring(index, identifierEnd(input, index)));
        }
        if (Character.isDigit(current)
                || (current == '.' && index + 1 < input.length()
                && Character.isDigit(input.charAt(index + 1)))) {
            return new Token(Kind.NUMBER, input.substring(index, numberEnd(input, index)));
        }
        return new Token(Kind.SYMBOL, operatorAt(input, index));
    }

    private static int whitespaceEnd(String input, int start) {
        int index = start + 1;
        while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int lineCommentEnd(String input, int start) {
        int index = start + 2;
        while (index < input.length() && input.charAt(index) != '\n') {
            index++;
        }
        return index;
    }

    private static int blockCommentEnd(String input, int start) {
        int end = input.indexOf("*/", start + 2);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated GLSL comment");
        }
        return end + 2;
    }

    /** The end of a quoted literal, past its closing quote; a backslash escapes the next character. */
    private static int literalEnd(String input, int start) {
        char quote = input.charAt(start);
        int index = start + 1;
        boolean escaped = false;
        while (index < input.length()) {
            char value = input.charAt(index++);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == quote) {
                break;
            }
        }
        if (index > input.length() || input.charAt(index - 1) != quote) {
            throw new IllegalArgumentException("unterminated GLSL literal");
        }
        return index;
    }

    private static int identifierEnd(String input, int start) {
        int index = start + 1;
        while (index < input.length()) {
            char value = input.charAt(index);
            if (!Character.isLetterOrDigit(value) && value != '_') {
                break;
            }
            index++;
        }
        return index;
    }

    /** The end of a number: digits, letters and dots, and a sign right after an exponent letter. */
    private static int numberEnd(String input, int start) {
        int index = start + 1;
        boolean exponent = false;
        while (index < input.length()) {
            char value = input.charAt(index);
            if (Character.isLetterOrDigit(value) || value == '.') {
                exponent = value == 'e' || value == 'E';
                index++;
                continue;
            }
            if ((value == '+' || value == '-') && index > start && exponent) {
                index++;
                exponent = false;
                continue;
            }
            break;
        }
        return index;
    }

    /**
     * Replaces each comment with one space, scanning left to right so whichever comment starts
     * first wins. Removing block comments before line comments misreads Bliss's
     * {@code x = viewPos.z;//*viewPos.z*...} as a block that swallows kilobytes of live code.
     * An unterminated block comment runs to the end, as GLSL treats it.
     */
    static String stripComments(String source) {
        if (source == null) return "";
        StringBuilder result = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                int end = source.indexOf('\n', index);
                index = end < 0 ? source.length() : end;
                result.append(' ');
            } else if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
                result.append(' ');
            } else {
                result.append(current);
                index++;
            }
        }
        return result.toString();
    }

    static String render(List<Token> tokens) {
        StringBuilder result = new StringBuilder();
        for (Token token : tokens) {
            result.append(token.text());
        }
        return result.toString();
    }

    static int nextSignificant(List<Token> tokens, int index) {
        for (int current = index + 1; current < tokens.size(); current++) {
            if (tokens.get(current).significant()) {
                return current;
            }
        }
        return -1;
    }

    static int previousSignificant(List<Token> tokens, int index) {
        for (int current = index - 1; current >= 0; current--) {
            if (tokens.get(current).significant()) {
                return current;
            }
        }
        return -1;
    }

    static int matching(List<Token> tokens, int open, String left, String right) {
        int depth = 0;
        for (int index = open; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.symbol(left)) {
                depth++;
            } else if (token.symbol(right) && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The operator at {@code index}: the longest of {@code <<= >>= == != <= >= && || ++ -- += -= *=
     * /= %= << >> ::}, else the single character. Every symbol of every pack source passes through
     * here, so it is decided from the next characters instead of trying each operator in turn.
     */
    private static String operatorAt(String source, int index) {
        char first = source.charAt(index);
        char second = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
        String operator = switch (first) {
            case '<', '>' -> shiftOrCompare(source, index, first, second);
            case '=', '!', '*', '/', '%' -> second == '=' ? source.substring(index, index + 2) : null;
            case '&', '|', ':' -> second == first ? source.substring(index, index + 2) : null;
            case '+', '-' -> second == first || second == '=' ? source.substring(index, index + 2) : null;
            default -> null;
        };
        if (operator != null) return operator;
        return first < SINGLE_CHARACTERS.length ? SINGLE_CHARACTERS[first] : String.valueOf(first);
    }

    /** {@code <<=}, {@code <<}, {@code <=} or their {@code >} forms; null for a lone angle bracket. */
    private static String shiftOrCompare(String source, int index, char first, char second) {
        if (second == first) {
            boolean assign = index + 2 < source.length() && source.charAt(index + 2) == '=';
            return source.substring(index, index + (assign ? 3 : 2));
        }
        return second == '=' ? source.substring(index, index + 2) : null;
    }

    private static String[] singleCharacters() {
        String[] strings = new String[128];
        for (char value = 0; value < strings.length; value++) strings[value] = String.valueOf(value);
        return strings;
    }
}
