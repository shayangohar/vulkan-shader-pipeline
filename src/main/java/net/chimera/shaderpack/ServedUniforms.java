package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;

/**
 * Serves an Iris uniform whose value a program fixes, or reads under another name, by rewriting
 * the program's source: the file-scope {@code uniform} declaration goes, and every reference that
 * resolves to it reads the given expression. A local or parameter of the same name stays the
 * pack's own. A source without the declaration, or one that does not parse, is left as written.
 * Iris's core transformers rename such names the same way (alphaTestRef to
 * iris_currentAlphaTest); FogMode.OFF's fogMode and fogShape are constants Iris sets once.
 */
final class ServedUniforms {
    private ServedUniforms() {}

    static String serve(String source, String name, String value) {
        if (source == null || !GlslTokenRewriter.containsIdentifier(source, name)) return source;
        GlslTree tree = GlslDeclarations.parseOrNull(source);
        GlslDeclarations.Variable uniform = tree == null ? null : GlslDeclarations.variables(tree).stream()
                .filter(variable -> variable.name().equals(name) && variable.qualified("uniform"))
                .findFirst().orElse(null);
        if (uniform == null) return source;
        List<GlslTree.Edit> edits = new ArrayList<>();
        edits.add(new GlslTree.Edit(uniform.declaration().range(), withoutDeclarator(tree, uniform)));
        List<GlslLexer.Token> tokens = tree.tokens();
        for (int index = 0; index < tokens.size(); index++) {
            if (uniform.declaration().range().contains(index) || !tokens.get(index).identifier(name)) continue;
            GlslTree.Binding binding = tree.resolve(index);
            if (binding != null && binding.node() == uniform.declarator()) {
                edits.add(new GlslTree.Edit(new GlslTree.Range(index, index + 1), value));
            }
        }
        return tree.print(edits);
    }

    /** The declaration without the served declarator; empty when it declares only that. */
    private static String withoutDeclarator(GlslTree tree, GlslDeclarations.Variable uniform) {
        List<String> others = uniform.declaration().declarators().stream()
                .filter(declarator -> declarator != uniform.declarator())
                .map(declarator -> tree.text(declarator.range()))
                .toList();
        if (others.isEmpty()) return "";
        String head = tree.text(new GlslTree.Range(uniform.declaration().range().start(),
                uniform.declaration().type().range().end()));
        return head + " " + String.join(", ", others) + ";";
    }
}
