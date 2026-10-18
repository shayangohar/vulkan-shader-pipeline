package net.chimera.shaderpack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renames references to global names the way GLSL resolves them. A function parameter or local
 * variable that shares a global's name hides it until its scope closes, so its declaration and
 * uses keep their spelling; a field access ({@code value.name}) is never a global. Kappa names a
 * parameter after its {@code lightDir} uniform ({@code projectedCaustic(..., vec3 lightDir)}).
 */
final class GlslScopedReferences {
    private static final Pattern BUILT_IN_TYPE =
            Pattern.compile("void|bool|int|uint|float|double|[biud]?vec[234]|d?mat[234](x[234])?");

    private GlslScopedReferences() {}

    /** {@code source} with each reference to a global named in {@code replacements} renamed. */
    static String replace(String source, Map<String, String> replacements) {
        if (replacements.isEmpty()) return source;
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        Set<String> types = structNames(tokens);
        Deque<Set<String>> scopes = new ArrayDeque<>();
        Set<String> parameters = null;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("{")) {
                scopes.push(parameters == null ? new HashSet<>() : parameters);
                parameters = null;
            } else if (token.symbol("}")) {
                if (!scopes.isEmpty()) scopes.pop();
            } else if (token.symbol("(") && scopes.isEmpty() && functionHeader(tokens, index, types)) {
                int close = matching(tokens, index);
                parameters = parameterNames(tokens, index, close);
                index = renameRange(tokens, index + 1, close, replacements, types, scopes);
            } else if (token.symbol(";") && scopes.isEmpty()) {
                parameters = null;
            } else if (token.kind() == GlslLexer.Kind.IDENTIFIER && replacements.containsKey(token.text())) {
                rename(tokens, index, replacements, types, scopes);
            }
        }
        return GlslLexer.render(tokens);
    }

    /** Renames the references in a function header's parameter list; returns its closing parenthesis. */
    private static int renameRange(List<GlslLexer.Token> tokens, int from, int to, Map<String, String> replacements,
                                   Set<String> types, Deque<Set<String>> scopes) {
        for (int index = from; index < to; index++) {
            GlslLexer.Token token = tokens.get(index);
            // A parameter's own name is a declaration; an array size may still name a global constant.
            if (token.kind() == GlslLexer.Kind.IDENTIFIER && replacements.containsKey(token.text())
                    && !declaredHere(tokens, index, types)) {
                rename(tokens, index, replacements, types, scopes);
            }
        }
        return to;
    }

    private static void rename(List<GlslLexer.Token> tokens, int index, Map<String, String> replacements,
                               Set<String> types, Deque<Set<String>> scopes) {
        String name = tokens.get(index).text();
        int previous = GlslLexer.previousSignificant(tokens, index);
        if (previous >= 0 && tokens.get(previous).symbol(".")) return;
        if (!scopes.isEmpty() && declaredHere(tokens, index, types)) {
            scopes.peek().add(name);
            return;
        }
        for (Set<String> scope : scopes) {
            if (scope.contains(name)) return;
        }
        tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, replacements.get(name)));
    }

    /** Whether the identifier at {@code index} is a declaration's name: a type before it, a declarator end after. */
    private static boolean declaredHere(List<GlslLexer.Token> tokens, int index, Set<String> types) {
        int previous = GlslLexer.previousSignificant(tokens, index);
        int next = GlslLexer.nextSignificant(tokens, index);
        if (previous < 0 || next < 0) return false;
        boolean typed = isType(tokens.get(previous), types);
        GlslLexer.Token after = tokens.get(next);
        return typed && (after.symbol("=") || after.symbol(";") || after.symbol(",") || after.symbol("[")
                || after.symbol(")"));
    }

    /** Whether the parenthesis at {@code open} starts a function's parameter list: {@code type name (}. */
    private static boolean functionHeader(List<GlslLexer.Token> tokens, int open, Set<String> types) {
        int name = GlslLexer.previousSignificant(tokens, open);
        if (name < 0 || tokens.get(name).kind() != GlslLexer.Kind.IDENTIFIER) return false;
        int type = GlslLexer.previousSignificant(tokens, name);
        return type >= 0 && isType(tokens.get(type), types);
    }

    /** A built-in type name or one of the source's struct names. */
    private static boolean isType(GlslLexer.Token token, Set<String> types) {
        return token.kind() == GlslLexer.Kind.IDENTIFIER
                && (BUILT_IN_TYPE.matcher(token.text()).matches() || types.contains(token.text()));
    }

    /** The names a parameter list declares: the last identifier of each parameter, before any array size. */
    private static Set<String> parameterNames(List<GlslLexer.Token> tokens, int open, int close) {
        Set<String> names = new HashSet<>();
        String last = null;
        int depth = 0;
        for (int index = open + 1; index <= close; index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("[") || token.symbol("(")) depth++;
            else if (token.symbol("]") || (token.symbol(")") && index < close)) depth--;
            else if (depth == 0 && (token.symbol(",") || index == close)) {
                if (last != null) names.add(last);
                last = null;
            } else if (depth == 0 && token.kind() == GlslLexer.Kind.IDENTIFIER) {
                last = token.text();
            }
        }
        names.remove("void");
        return names;
    }

    private static Set<String> structNames(List<GlslLexer.Token> tokens) {
        Set<String> names = new HashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).identifier("struct")) continue;
            int name = GlslLexer.nextSignificant(tokens, index);
            if (name >= 0 && tokens.get(name).kind() == GlslLexer.Kind.IDENTIFIER) names.add(tokens.get(name).text());
        }
        return names;
    }

    private static int matching(List<GlslLexer.Token> tokens, int open) {
        int depth = 0;
        for (int index = open; index < tokens.size(); index++) {
            if (tokens.get(index).symbol("(")) depth++;
            else if (tokens.get(index).symbol(")") && --depth == 0) return index;
        }
        return tokens.size() - 1;
    }
}
