package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renames references to global names the way GLSL resolves them. A function parameter or local
 * variable that shares a global's name hides it until its scope closes, so its declaration and
 * uses keep their spelling; a field access ({@code value.name}) is never a global. Kappa names a
 * parameter after its {@code lightDir} uniform ({@code projectedCaustic(..., vec3 lightDir)}).
 */
final class GlslScopedReferences {
    private GlslScopedReferences() {}

    /** {@code source} with each reference to a global named in {@code replacements} renamed. */
    static String replace(String source, Map<String, String> replacements) {
        if (replacements.isEmpty()) return source;
        GlslTree tree = GlslTree.parse(source);
        List<GlslLexer.Token> tokens = tree.tokens();
        Set<Integer> members = memberNames(tree);
        List<GlslTree.Edit> edits = new ArrayList<>();
        for (int token = 0; token < tokens.size(); token++) {
            String replacement = tokens.get(token).kind() == GlslLexer.Kind.IDENTIFIER
                    ? replacements.get(tokens.get(token).text()) : null;
            if (replacement != null && !members.contains(token) && global(tree, token)) {
                edits.add(new GlslTree.Edit(new GlslTree.Range(token, token + 1), replacement));
            }
        }
        return edits.isEmpty() ? source : tree.print(edits);
    }

    /**
     * Whether the name at {@code token} is a global's: neither a call (a sampler named texture
     * never renames texture(...)) nor a field, and not hidden by a parameter or local.
     */
    private static boolean global(GlslTree tree, int token) {
        List<GlslLexer.Token> tokens = tree.tokens();
        int next = GlslLexer.nextSignificant(tokens, token);
        if (next >= 0 && tokens.get(next).symbol("(")) return false;
        int previous = GlslLexer.previousSignificant(tokens, token);
        if (previous >= 0 && tokens.get(previous).symbol(".")) return false;
        GlslTree.Binding binding = tree.resolve(token);
        return binding == null || !insideFunction(tree, binding);
    }

    /** Whether a binding is a parameter or a declaration inside a function body. */
    static boolean insideFunction(GlslTree tree, GlslTree.Binding binding) {
        if (binding.node() instanceof GlslTree.Parameter) return true;
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Function function && function.body() != null
                    && function.body().range().contains(binding.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The names that file scope declares but never sees as globals: struct and interface-block
     * members, and a prototype's parameters.
     */
    static Set<Integer> memberNames(GlslTree tree) {
        Set<Integer> names = new HashSet<>();
        for (GlslTree.External external : tree.externals()) {
            switch (external) {
                case GlslTree.Declaration declaration when declaration.type() != null
                        && declaration.type().struct() != null ->
                        declaration.type().struct().members().forEach(member -> addNames(names, member));
                case GlslTree.InterfaceBlock block -> block.members().forEach(member -> addNames(names, member));
                case GlslTree.Function function when function.body() == null ->
                        function.parameters().forEach(parameter -> names.add(parameter.name()));
                default -> { }
            }
        }
        return names;
    }

    private static void addNames(Set<Integer> names, GlslTree.Declaration declaration) {
        declaration.declarators().forEach(declarator -> names.add(declarator.name()));
    }
}
