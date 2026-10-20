package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;

/**
 * The preprocessor directive lines of a GLSL source, read from {@link GlslLexer} tokens: a
 * {@code #} with only spaces or tabs before it on its line, through that line's end. Comments are
 * trivia, so a directive written inside a comment is not one. This reads any source, parsed or
 * not: {@link GlslVersion} also reads unprepared sources, whose conditional blocks the tree may
 * not parse.
 */
final class GlslDirectives {
    private GlslDirectives() {}

    /**
     * One directive line. Offsets are characters: {@code hash} is its {@code #}, {@code end} the
     * end of its line before the line break, {@code wordsEnd} the end of its last word. The
     * keyword is the word after {@code #} ({@code version}, {@code extension}); {@code adjacent}
     * when nothing separates them. {@code words} follow the keyword, {@code rest} is the line's
     * text after the last word, and {@code plain} is true when only spaces or tabs separate the
     * words and only a line comment follows them.
     */
    record Line(int hash, int end, int wordsEnd, String keyword, boolean adjacent, List<String> words,
                String rest, boolean plain) {
        boolean is(String name) {
            return keyword.equals(name);
        }
    }

    /** Every directive line, in source order. */
    static List<Line> lines(String source) {
        String text = source == null ? "" : source;
        List<GlslLexer.Token> tokens = GlslLexer.lex(text);
        List<Line> lines = new ArrayList<>();
        int offset = 0;
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).symbol("#") && startsLine(text, offset)) {
                lines.add(line(text, tokens, index, offset));
            }
            offset += tokens.get(index).text().length();
        }
        return lines;
    }

    /** The first {@code #version} line, or null without one. */
    static Line version(String source) {
        for (Line line : lines(source)) {
            if (line.is("version")) return line;
        }
        return null;
    }

    private static boolean startsLine(String text, int hash) {
        int index = hash;
        while (index > 0 && (text.charAt(index - 1) == ' ' || text.charAt(index - 1) == '\t')) index--;
        return index == 0 || text.charAt(index - 1) == '\n' || text.charAt(index - 1) == '\r';
    }

    /** The directive whose {@code #} is token {@code hashToken}, starting at character {@code hash}. */
    private static Line line(String text, List<GlslLexer.Token> tokens, int hashToken, int hash) {
        String keyword = "";
        boolean adjacent = false;
        boolean plain = true;
        List<String> words = new ArrayList<>();
        int offset = hash + 1;
        int wordsEnd = offset;
        for (int index = hashToken + 1; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            String value = token.text();
            // A line comment runs to the line's end and may close the directive plainly.
            if (value.startsWith("//")) break;
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                // A block comment that runs onto the next line ends the directive, but not plainly.
                plain &= value.isBlank();
                break;
            }
            if (token.significant()) {
                if (keyword.isEmpty()) {
                    keyword = value;
                    adjacent = offset == hash + 1;
                } else {
                    words.add(value);
                }
                wordsEnd = offset + value.length();
            } else if (!value.isBlank()) {
                plain = false;
            }
            offset += value.length();
        }
        int end = wordsEnd;
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
        return new Line(hash, end, wordsEnd, keyword, adjacent, List.copyOf(words), text.substring(wordsEnd, end),
                plain);
    }
}
