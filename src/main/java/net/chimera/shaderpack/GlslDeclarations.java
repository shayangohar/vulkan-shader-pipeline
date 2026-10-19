package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

        /** Whether this is the declaration's only name, without array sizes or an initializer. */
        boolean plain() {
            return declaration.declarators().size() == 1 && declarator.arrays().isEmpty()
                    && declarator.initializer() == null
                    && (declaration.type() == null || declaration.type().arrays().isEmpty());
        }
    }

    /** Replaces source characters {@code [from, to)} with {@code text}. */
    record TextEdit(int from, int to, String text) {}

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
