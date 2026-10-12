package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;

/** Small lossless lexer for the token rewrites used by the pack bridge. */
final class GlslLexer {
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
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < input.length()) {
            char current = input.charAt(index);
            if (Character.isWhitespace(current)) {
                int start = index++;
                while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                    index++;
                }
                tokens.add(new Token(Kind.TRIVIA, input.substring(start, index)));
                continue;
            }
            if (current == '/' && index + 1 < input.length()
                    && input.charAt(index + 1) == '/') {
                int start = index;
                index += 2;
                while (index < input.length() && input.charAt(index) != '\n') {
                    index++;
                }
                tokens.add(new Token(Kind.TRIVIA, input.substring(start, index)));
                continue;
            }
            if (current == '/' && index + 1 < input.length()
                    && input.charAt(index + 1) == '*') {
                int start = index;
                index += 2;
                int end = input.indexOf("*/", index);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated GLSL comment");
                }
                index = end + 2;
                tokens.add(new Token(Kind.TRIVIA, input.substring(start, index)));
                continue;
            }
            if (current == '"' || current == '\'') {
                int start = index++;
                boolean escaped = false;
                while (index < input.length()) {
                    char value = input.charAt(index++);
                    if (escaped) {
                        escaped = false;
                    } else if (value == '\\') {
                        escaped = true;
                    } else if (value == current) {
                        break;
                    }
                }
                if (index > input.length() || input.charAt(index - 1) != current) {
                    throw new IllegalArgumentException("unterminated GLSL literal");
                }
                tokens.add(new Token(Kind.LITERAL, input.substring(start, index)));
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                int start = index++;
                while (index < input.length()) {
                    char value = input.charAt(index);
                    if (!Character.isLetterOrDigit(value) && value != '_') {
                        break;
                    }
                    index++;
                }
                tokens.add(new Token(Kind.IDENTIFIER, input.substring(start, index)));
                continue;
            }
            if (Character.isDigit(current)
                    || (current == '.' && index + 1 < input.length()
                    && Character.isDigit(input.charAt(index + 1)))) {
                int start = index++;
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
                tokens.add(new Token(Kind.NUMBER, input.substring(start, index)));
                continue;
            }
            String operator = operatorAt(input, index);
            tokens.add(new Token(Kind.SYMBOL, operator));
            index += operator.length();
        }
        return List.copyOf(tokens);
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

    private static String operatorAt(String source, int index) {
        for (String operator : List.of("<<=", ">>=", "==", "!=", "<=", ">=", "&&", "||",
                "++", "--", "+=", "-=", "*=", "/=", "%=", "<<", ">>", "::")) {
            if (source.startsWith(operator, index)) {
                return operator;
            }
        }
        return source.substring(index, index + 1);
    }
}
