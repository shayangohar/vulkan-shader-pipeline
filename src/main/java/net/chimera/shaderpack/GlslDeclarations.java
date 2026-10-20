package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The file-scope variable declarations of a {@link GlslTree}, and text edits on them. The
 * converters' declaration rewrites read and edit declarations through this instead of matching
 * declaration text, so a comment, a declarator list or a qualifier order no longer decides
 * whether a declaration is seen (KNOW-714).
 */
final class GlslDeclarations {
    private GlslDeclarations() {}

    /** One name a file-scope declaration declares. */
    record Variable(GlslTree tree, GlslTree.Declaration declaration, GlslTree.Declarator declarator) {
        String name() {
            return tree.text(declarator.name());
        }

        /** The type's name ({@code struct} for an inline struct); empty for a qualifier-only line. */
        String type() {
            GlslTree.TypeSpec type = declaration.type();
            if (type == null) return "";
            return type.name() >= 0 ? tree.text(type.name()) : "struct";
        }

        /** Whether a qualifier keyword is {@code keyword}. */
        boolean qualified(String keyword) {
            return declaration.qualifiers().stream().anyMatch(value -> tree.text(value.keyword()).equals(keyword));
        }

        /** The qualifier keywords in source order, {@code layout} for a layout list. */
        List<String> qualifiers() {
            return declaration.qualifiers().stream().map(value -> tree.text(value.keyword())).toList();
        }

        /** Whether this is the declaration's only name, without array sizes or an initializer. */
        boolean plain() {
            return declaration.declarators().size() == 1 && declarator.arrays().isEmpty()
                    && declarator.initializer() == null
                    && (declaration.type() == null || declaration.type().arrays().isEmpty());
        }
    }

    /** Replaces source characters {@code [from, to)} with {@code text}. */
    record TextEdit(int from, int to, String text) {}

    /**
     * The tree of {@code source}, or null when it does not parse. A reader that only gathers
     * declarations (target directives, outputs) treats such a source as declaring none: it
     * compiles nowhere, and the converters refuse it with the parse failure.
     */
    static GlslTree parseOrNull(String source) {
        try {
            return GlslTree.parse(source);
        } catch (IllegalArgumentException unparsed) {
            return null;
        }
    }

    /**
     * Where a {@code (?m)^[ \t]*} match that ends at {@code offset} begins: the start of the
     * indentation before it, or -1 when other text shares its line.
     */
    static int indentationStart(String source, int offset) {
        int start = offset;
        while (start > 0 && (source.charAt(start - 1) == ' ' || source.charAt(start - 1) == '\t')) start--;
        return start == 0 || source.charAt(start - 1) == '\n' || source.charAt(start - 1) == '\r' ? start : -1;
    }

    /** Every name declared by a variable declaration at file scope, in source order. */
    static List<Variable> variables(GlslTree tree) {
        List<Variable> variables = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Declaration declaration) || declaration.type() == null) continue;
            for (GlslTree.Declarator declarator : declaration.declarators()) {
                variables.add(new Variable(tree, declaration, declarator));
            }
        }
        return List.copyOf(variables);
    }

    /**
     * Removes a declaration. One that stands alone on its line takes its indentation and trailing
     * spaces with it and leaves the line terminator, as a {@code (?m)^[ \t]*...;[ \t]*$} match
     * replaced with nothing did.
     */
    static TextEdit remove(GlslTree tree, GlslTree.Node node) {
        String source = tree.source();
        int from = tree.offset(node.range().start());
        int to = tree.offset(node.range().end());
        int lineStart = from;
        while (lineStart > 0 && (source.charAt(lineStart - 1) == ' ' || source.charAt(lineStart - 1) == '\t')) {
            lineStart--;
        }
        int lineEnd = to;
        while (lineEnd < source.length() && (source.charAt(lineEnd) == ' ' || source.charAt(lineEnd) == '\t')) {
            lineEnd++;
        }
        boolean alone = (lineStart == 0 || isLineBreak(source.charAt(lineStart - 1)))
                && (lineEnd == source.length() || isLineBreak(source.charAt(lineEnd)));
        return alone ? new TextEdit(lineStart, lineEnd, "") : new TextEdit(from, to, "");
    }

    /**
     * The arguments of a {@code layout(...)} qualifier, name to value ({@code ""} for a bare
     * name): {@code layout(location = 2, index = 0)} gives location 2 and index 0.
     */
    static Map<String, String> layoutArguments(GlslTree tree, GlslTree.Qualifier qualifier) {
        Map<String, String> arguments = new LinkedHashMap<>();
        String text = tree.text(qualifier.range());
        int open = text.indexOf('(');
        if (!tree.text(qualifier.keyword()).equals("layout") || open < 0) return arguments;
        for (String argument : text.substring(open + 1, text.lastIndexOf(')')).split(",")) {
            int equals = argument.indexOf('=');
            String name = (equals < 0 ? argument : argument.substring(0, equals)).trim();
            if (!name.isEmpty()) arguments.put(name, equals < 0 ? "" : argument.substring(equals + 1).trim());
        }
        return arguments;
    }

    /**
     * Where a line-anchored declaration rewrite's span ends: {@code tail} (its trailing spaces,
     * comment and line breaks, compiled MULTILINE) matched from just after the declaration; -1
     * when the tail does not match there, so the old pattern did not match that declaration.
     */
    static int tailEnd(String source, int from, Pattern tail) {
        Matcher matcher = tail.matcher(source);
        matcher.region(from, source.length());
        matcher.useAnchoringBounds(false);
        matcher.useTransparentBounds(true);
        return matcher.lookingAt() ? matcher.end() : -1;
    }

    /** The {@code location} a declaration's layout gives it; null without one. */
    static Integer location(GlslTree tree, GlslTree.Declaration declaration) {
        for (GlslTree.Qualifier qualifier : declaration.qualifiers()) {
            String value = layoutArguments(tree, qualifier).get("location");
            if (value != null && value.matches("\\d+")) return Integer.parseInt(value);
        }
        return null;
    }

    /**
     * Where a {@code (?m)^\s*} match that ends at {@code offset} begins: the first line start
     * after the text before {@code offset}, so the blank lines and indentation before a
     * declaration belong to its span. -1 when the declaration does not start a line.
     */
    static int lineStartBefore(String source, int offset) {
        return lineStartBefore(source, offset, 0);
    }

    /**
     * {@link #lineStartBefore(String, int)} for a pattern searched repeatedly: the search resumes at
     * the previous match's end, {@code minimum}, so a span never starts before it, however many
     * blank lines that match's tail took.
     */
    static int lineStartBefore(String source, int offset, int minimum) {
        int text = offset;
        while (text > minimum && " \t\n\u000B\f\r".indexOf(source.charAt(text - 1)) >= 0) text--;
        for (int index = text; index <= offset; index++) {
            boolean lineStart = index == 0 || source.charAt(index - 1) == '\n'
                    || source.charAt(index - 1) == '\r' && (index == source.length() || source.charAt(index) != '\n');
            if (lineStart) return index;
        }
        return -1;
    }

    /** The first {@code void main} the source declares, a prototype or a definition; null without one. */
    static GlslTree.Function main(GlslTree tree) {
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Function function && tree.text(function.name()).equals("main")
                    && tree.text(function.returnType().range()).equals("void") && function.qualifiers().isEmpty()) {
                return function;
            }
        }
        return null;
    }

    /** The first {@code void main} with a body; null without one. */
    static GlslTree.Function mainDefinition(GlslTree tree) {
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Function function && function.body() != null
                    && tree.text(function.name()).equals("main")
                    && tree.text(function.returnType().range()).equals("void") && function.qualifiers().isEmpty()) {
                return function;
            }
        }
        return null;
    }

    /** The source with its first main renamed {@code name}, its signature written {@code void name()}. */
    static String renameMain(String source, String name) {
        GlslTree tree = GlslTree.parse(source);
        GlslTree.Function main = main(tree);
        if (main == null) return null;
        return apply(source, List.of(new TextEdit(tree.offset(main.returnType().range().start()),
                tree.offset(main.parameterList().end()), "void " + name + "()")));
    }

    /** {@code source} with each edit applied; edits must not overlap. */
    static String apply(String source, List<TextEdit> edits) {
        List<TextEdit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(TextEdit::from));
        StringBuilder result = new StringBuilder(source.length());
        int at = 0;
        for (TextEdit edit : ordered) {
            if (edit.from() < at) throw new IllegalArgumentException("text edits overlap");
            result.append(source, at, edit.from()).append(edit.text());
            at = edit.to();
        }
        return result.append(source, at, source.length()).toString();
    }

    private static boolean isLineBreak(char value) {
        return value == '\n' || value == '\r';
    }
}
