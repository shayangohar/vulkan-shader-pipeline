package net.chimera.shaderpack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Expands the macros of a prepared source as a C preprocessor does, and as Iris's JCPP does before
 * any analysis (KNOW-714): object-like and function-like macros, arguments expanded before they
 * are substituted, {@code ##} pasting, and rescanning with each macro hidden from its own
 * expansion. The input is {@link ShaderSourcePreprocessor}'s output, whose conditionals are
 * resolved and whose active #define and #undef lines stand in source order. Those lines become
 * empty; comments and every other directive (#version, #extension, #pragma, #line) stay as
 * written; a call that spans lines keeps the line count. A call with the wrong number of
 * arguments, or one that never closes, stays as written for the compiler to report.
 */
final class GlslMacroExpander {
    static final int MAX_EXPANDED_CHARS = 16 * 1024 * 1024;
    private static final Pattern DEFINE_OR_UNDEF = Pattern.compile("(?m)^[ \\t]*#[ \\t]*(?:define|undef)\\b");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    private static final GlslLexer.Kind TRIVIA = GlslLexer.Kind.TRIVIA;

    /** A macro: its parameters, null for an object-like one, and its replacement list. */
    private record Macro(List<String> parameters, List<Tok> body) {}

    /**
     * A token in flight: its kind and text, whether whitespace preceded it in a replacement list,
     * the macros it may no longer expand, whether it is the source's own, and whether it opens a
     * directive line.
     */
    private record Tok(GlslLexer.Kind kind, String text, boolean spaceBefore, Set<String> hidden,
                       boolean original, boolean directive) {
        boolean symbol(String value) {
            return kind == GlslLexer.Kind.SYMBOL && text.equals(value);
        }

        boolean significant() {
            return kind != TRIVIA;
        }

        Tok as(boolean space, Set<String> hide) {
            return new Tok(kind, text, space, hide, false, false);
        }
    }

    /** A call's arguments and the newlines its source spanned. */
    private record Call(List<List<Tok>> arguments, int newlines) {}

    private final Map<String, Macro> macros;
    private final List<Tok> input;
    private final Deque<Tok> pending = new ArrayDeque<>();
    private final List<Tok> output = new ArrayList<>();
    private int position;
    private long produced;

    private GlslMacroExpander(Map<String, Macro> macros, List<Tok> input) {
        this.macros = macros;
        this.input = input;
    }

    /** The source with its macros expanded, or the source itself when it defines none. */
    static String expand(String source) {
        if (source == null || !DEFINE_OR_UNDEF.matcher(source).find()) return source;
        GlslMacroExpander expander = new GlslMacroExpander(new HashMap<>(), sourceTokens(GlslLexer.lex(source)));
        expander.run();
        return render(expander.output);
    }

    private static List<Tok> sourceTokens(List<GlslLexer.Token> tokens) {
        List<Tok> result = new ArrayList<>(tokens.size());
        boolean lineStart = true;
        for (GlslLexer.Token token : tokens) {
            boolean directive = lineStart && token.symbol("#");
            result.add(new Tok(token.kind(), token.text(), false, Set.of(), true, directive));
            if (token.kind() == TRIVIA && !token.text().startsWith("/")) {
                if (token.text().indexOf('\n') >= 0) lineStart = true;
            } else {
                lineStart = false;
            }
        }
        return result;
    }

    private Tok next() {
        if (!pending.isEmpty()) return pending.pop();
        return position < input.size() ? input.get(position++) : null;
    }

    private void pushBack(List<Tok> tokens) {
        for (int index = tokens.size() - 1; index >= 0; index--) pending.push(tokens.get(index));
    }

    private void run() {
        for (Tok token = next(); token != null; token = next()) {
            if (token.directive()) {
                directive(token);
            } else if (!expandsHere(token) || !expand(token)) {
                add(token);
            }
        }
    }

    private void add(Tok token) {
        produced += token.text().length();
        if (produced > MAX_EXPANDED_CHARS) {
            throw new IllegalArgumentException("macro expansion exceeds " + MAX_EXPANDED_CHARS + " characters");
        }
        output.add(token);
    }

    private boolean expandsHere(Tok token) {
        return token.kind() == GlslLexer.Kind.IDENTIFIER && macros.containsKey(token.text())
                && !token.hidden().contains(token.text());
    }

    /**
     * A directive line, from its {@code #} to the newline: #define and #undef change the table and
     * leave nothing, any other directive stays as written. The newline and what follows it stay.
     */
    private void directive(Tok hash) {
        List<Tok> line = new ArrayList<>();
        line.add(hash);
        String rest = null;
        for (Tok token = next(); token != null; token = next()) {
            int newline = token.kind() == TRIVIA && !token.text().startsWith("/") ? token.text().indexOf('\n') : -1;
            if (newline >= 0) {
                rest = token.text().substring(newline);
                break;
            }
            line.add(token);
        }
        List<Tok> words = line.stream().filter(Tok::significant).toList();
        String name = words.size() > 1 ? words.get(1).text() : "";
        if (name.equals("define") && words.size() > 2) {
            define(line);
        } else if (name.equals("undef") && words.size() > 2) {
            macros.remove(words.get(2).text());
        } else {
            line.forEach(this::add);
        }
        if (rest != null) add(new Tok(TRIVIA, rest, false, Set.of(), true, false));
    }

    /** Records {@code #define NAME[(params)] replacement}; a '(' right after the name makes it function-like. */
    private void define(List<Tok> line) {
        int index = indexOfSignificant(line, 0, 2);
        String name = line.get(index).text();
        List<String> parameters = null;
        int body = index + 1;
        if (body < line.size() && line.get(body).symbol("(")) {
            parameters = new ArrayList<>();
            for (body++; body < line.size() && !line.get(body).symbol(")"); body++) {
                Tok token = line.get(body);
                if (token.kind() == GlslLexer.Kind.IDENTIFIER) parameters.add(token.text());
            }
            body++;
        }
        List<Tok> replacement = line.subList(Math.min(body, line.size()), line.size());
        macros.put(name, new Macro(parameters, replacementList(replacement)));
    }

    private static int indexOfSignificant(List<Tok> tokens, int from, int nth) {
        int seen = 0;
        for (int index = from; index < tokens.size(); index++) {
            if (tokens.get(index).significant() && seen++ == nth) return index;
        }
        throw new IllegalArgumentException("malformed #define");
    }

    /** The replacement tokens without trivia, each marking the space before it; {@code ##} is one token. */
    private static List<Tok> replacementList(List<Tok> tokens) {
        List<Tok> result = new ArrayList<>();
        boolean space = false;
        for (Tok token : tokens) {
            if (!token.significant()) {
                space = !result.isEmpty();
                continue;
            }
            Tok previous = result.isEmpty() ? null : result.get(result.size() - 1);
            if (previous != null && token.symbol("#") && previous.symbol("#") && !space) {
                result.set(result.size() - 1, new Tok(GlslLexer.Kind.SYMBOL, "##", previous.spaceBefore(),
                        Set.of(), false, false));
            } else {
                result.add(token.as(space, Set.of()));
            }
            space = false;
        }
        return result;
    }

    /** Expands the macro named by {@code name} in place; false when a function-like name is not a call. */
    private boolean expand(Tok name) {
        Macro macro = macros.get(name.text());
        Set<String> hidden = new HashSet<>(name.hidden());
        hidden.add(name.text());
        Set<String> hide = Set.copyOf(hidden);
        if (macro.parameters() == null) {
            pushExpansion(name, substitute(macro, List.of(), hide), 0);
            return true;
        }
        Call call = call(macro.parameters().size());
        if (call == null) return false;
        pushExpansion(name, substitute(macro, call.arguments(), hide), call.newlines());
        return true;
    }

    /** Queues an expansion for rescanning, with the newlines its call spanned after it. */
    private void pushExpansion(Tok name, List<Tok> expansion, int newlines) {
        List<Tok> queued = new ArrayList<>(expansion);
        if (!queued.isEmpty()) {
            Tok first = queued.get(0);
            queued.set(0, new Tok(first.kind(), first.text(), name.spaceBefore(), first.hidden(), false, false));
        }
        if (newlines > 0) queued.add(new Tok(TRIVIA, "\n".repeat(newlines), false, Set.of(), false, false));
        pushBack(queued);
    }

    /**
     * Reads a call's arguments after its name: null, with everything read put back, when no '('
     * follows, the call does not close, a directive interrupts it, or the count does not match.
     */
    private Call call(int parameterCount) {
        List<Tok> read = new ArrayList<>();
        Tok open = nextSignificant(read);
        Call call = open == null || !open.symbol("(") || open.directive() ? null : arguments(read);
        if (call != null && parameterCount == 0 && call.arguments().size() == 1
                && call.arguments().get(0).isEmpty()) {
            call = new Call(List.of(), call.newlines());
        }
        if (call == null || call.arguments().size() != parameterCount) {
            pushBack(read);
            return null;
        }
        return call;
    }

    /** The next significant token, recording it and the trivia before it in {@code read}. */
    private Tok nextSignificant(List<Tok> read) {
        for (Tok token = next(); token != null; token = next()) {
            read.add(token);
            if (token.significant()) return token;
        }
        return null;
    }

    /** The comma-separated arguments up to the matching ')', recording each token read in {@code read}. */
    private Call arguments(List<Tok> read) {
        List<List<Tok>> arguments = new ArrayList<>();
        List<Tok> current = new ArrayList<>();
        int depth = 0;
        int newlines = 0;
        boolean space = false;
        for (Tok token = next(); token != null; token = next()) {
            read.add(token);
            if (token.directive()) return null;
            if (!token.significant()) {
                newlines += (int) token.text().chars().filter(value -> value == '\n').count();
                space = true;
                continue;
            }
            if (depth == 0 && (token.symbol(",") || token.symbol(")"))) {
                arguments.add(current);
                if (token.symbol(")")) return new Call(arguments, newlines);
                current = new ArrayList<>();
                space = false;
                continue;
            }
            if (token.symbol("(")) depth++;
            if (token.symbol(")")) depth--;
            current.add(token.as(space && !current.isEmpty(), token.hidden()));
            space = false;
        }
        return null;
    }

    /** The replacement list with each parameter replaced by its argument, pasted, and hidden from {@code hide}. */
    private List<Tok> substitute(Macro macro, List<List<Tok>> arguments, Set<String> hide) {
        List<Tok> body = macro.body();
        Map<Integer, List<Tok>> expandedArguments = new HashMap<>();
        List<Tok> result = new ArrayList<>();
        for (int index = 0; index < body.size(); index++) {
            Tok token = body.get(index);
            int parameter = macro.parameters() == null || token.kind() != GlslLexer.Kind.IDENTIFIER
                    ? -1 : macro.parameters().indexOf(token.text());
            if (parameter < 0) {
                result.add(token);
                continue;
            }
            boolean pasted = pasted(body, index);
            List<Tok> argument = pasted ? arguments.get(parameter)
                    : expandedArguments.computeIfAbsent(parameter, ignored -> expandArgument(arguments.get(parameter)));
            if (argument.isEmpty() && pasted) {
                result.add(new Tok(GlslLexer.Kind.SYMBOL, "", token.spaceBefore(), Set.of(), false, false));
            }
            for (int at = 0; at < argument.size(); at++) {
                Tok value = argument.get(at);
                result.add(value.as(at == 0 ? token.spaceBefore() : value.spaceBefore(), value.hidden()));
            }
        }
        return hiddenFrom(paste(result), hide);
    }

    /** Whether the replacement token at {@code index} is an operand of {@code ##}. */
    private static boolean pasted(List<Tok> body, int index) {
        return (index > 0 && body.get(index - 1).symbol("##"))
                || (index + 1 < body.size() && body.get(index + 1).symbol("##"));
    }

    private static List<Tok> hiddenFrom(List<Tok> tokens, Set<String> hide) {
        List<Tok> result = new ArrayList<>(tokens.size());
        for (Tok token : tokens) {
            result.add(token.as(token.spaceBefore(), token.hidden().isEmpty() ? hide : union(token.hidden(), hide)));
        }
        return result;
    }

    /** An argument fully expanded on its own, as C expands it before substitution. */
    private List<Tok> expandArgument(List<Tok> argument) {
        GlslMacroExpander inner = new GlslMacroExpander(macros, argument);
        inner.produced = produced;
        inner.run();
        return inner.output;
    }

    /** Joins the tokens on each side of every {@code ##}, and drops the empty placeholders of empty arguments. */
    private static List<Tok> paste(List<Tok> tokens) {
        List<Tok> result = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            Tok token = tokens.get(index);
            if (token.symbol("##") && !result.isEmpty() && index + 1 < tokens.size()) {
                Tok left = result.remove(result.size() - 1);
                Tok right = tokens.get(++index);
                result.add(joined(left, right));
            } else {
                result.add(token);
            }
        }
        result.removeIf(token -> token.text().isEmpty());
        return result;
    }

    private static Tok joined(Tok left, Tok right) {
        String text = left.text() + right.text();
        GlslLexer.Kind kind = IDENTIFIER.matcher(text).matches() ? GlslLexer.Kind.IDENTIFIER
                : !text.isEmpty() && Character.isDigit(text.charAt(0)) ? GlslLexer.Kind.NUMBER : GlslLexer.Kind.SYMBOL;
        return new Tok(kind, text, left.spaceBefore(), union(left.hidden(), right.hidden()), false, false);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty() || first.containsAll(second)) return first;
        Set<String> result = new HashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    /**
     * The tokens as text. The source's own tokens keep their spacing; an expanded token takes the
     * space its replacement list had, and one more wherever two tokens would otherwise read as one.
     */
    private static String render(List<Tok> tokens) {
        StringBuilder text = new StringBuilder();
        boolean previousExpanded = false;
        for (Tok token : tokens) {
            if (!token.significant()) {
                text.append(token.text());
                previousExpanded = false;
                continue;
            }
            boolean expanded = !token.original();
            if ((expanded || previousExpanded) && !text.isEmpty() && !token.text().isEmpty()) {
                char last = text.charAt(text.length() - 1);
                if (!Character.isWhitespace(last)
                        && ((expanded && token.spaceBefore()) || merges(last, token.text().charAt(0)))) {
                    text.append(' ');
                }
            }
            text.append(token.text());
            previousExpanded = expanded;
        }
        return text.toString();
    }

    /** Whether {@code left} then {@code right}, written together, would lex as one token or a comment. */
    private static boolean merges(char left, char right) {
        if (word(left) && word(right)) return true;
        if ((Character.isDigit(left) && right == '.') || (left == '.' && Character.isDigit(right))) return true;
        return operator(left) && operator(right);
    }

    private static boolean word(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static boolean operator(char value) {
        return "+-*/%<>=!&|^:#".indexOf(value) >= 0;
    }
}
