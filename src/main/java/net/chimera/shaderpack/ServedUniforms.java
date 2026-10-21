package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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

    /** The uniforms a fullscreen pass reads as constants ({@link #servePostValues}). */
    static final List<String> POST_VALUES = List.of("fogMode", "fogShape", "blendFunc");

    /**
     * Serves the values Iris fixes for a fullscreen pass. CompositeRenderer and FinalPassRenderer
     * build their programs with FogMode.OFF, so fogMode is 0 and fogShape -1. Blending is off when
     * a pass's uniforms update (Program.use runs before the pass's blend override applies), so
     * blendFunc reads zero, unless an enabled {@code blend.<pass>} directive's glBlendFuncSeparate
     * then notifies it to the directive's factors.
     */
    static String servePostValues(String source, PackBlendPlan.Mode passBlend) {
        int[] blend = passBlend == null ? new int[4] : passBlend.glFunc();
        String blendFunc = "ivec4(" + blend[0] + ", " + blend[1] + ", " + blend[2] + ", " + blend[3] + ")";
        return serve(serve(serve(source, "fogMode", "0"), "fogShape", "-1"), "blendFunc", blendFunc);
    }

    /** A source whose initialized unset uniforms became constants, and their names. */
    record Initialized(String source, List<String> names) {}

    /**
     * Rewrites each file-scope uniform that Iris never sets and the source initializes as the
     * constant GL reads it as: a uniform no glUniform call reaches keeps its initializer (Alpha
     * Piscium's {@code uniform mat4 textureMatrix = mat4(1.0)} in its compute and compatibility
     * passes, where no Iris transformer names textureMatrix). An uninitialized one reads zero
     * through the planner's unset rule (UniformRegistry.unsetDescriptor). {@code irisSets} says
     * which names Iris gives a value; their initializers are overwritten and stay as written.
     */
    static Initialized readInitializers(String source, Predicate<String> irisSets) {
        if (source == null || !source.contains("=")) return new Initialized(source, List.of());
        GlslTree tree = GlslDeclarations.parseOrNull(source);
        if (tree == null) return new Initialized(source, List.of());
        Map<GlslTree.Declaration, List<GlslDeclarations.Variable>> unset = new LinkedHashMap<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (variable.qualified("uniform") && variable.declarator().initializer() != null
                    && !irisSets.test(variable.name())) {
                unset.computeIfAbsent(variable.declaration(), key -> new ArrayList<>()).add(variable);
            }
        }
        if (unset.isEmpty()) return new Initialized(source, List.of());
        List<GlslTree.Edit> edits = new ArrayList<>();
        List<String> names = new ArrayList<>();
        unset.forEach((declaration, variables) -> {
            List<String> constants = new ArrayList<>();
            for (GlslDeclarations.Variable variable : variables) {
                constants.add("const " + tree.text(declaration.type().range()) + " "
                        + tree.text(variable.declarator().range()) + ";");
                names.add(variable.name());
            }
            String rest = without(tree, declaration, variables);
            if (!rest.isEmpty()) constants.add(rest);
            edits.add(new GlslTree.Edit(declaration.range(), String.join("\n", constants)));
        });
        return new Initialized(tree.print(edits), List.copyOf(names));
    }

    /** The declaration without the served declarator; empty when it declares only that. */
    private static String withoutDeclarator(GlslTree tree, GlslDeclarations.Variable uniform) {
        return without(tree, uniform.declaration(), List.of(uniform));
    }

    /** The declaration without the given declarators; empty when it declares only those. */
    private static String without(GlslTree tree, GlslTree.Declaration declaration,
                                  List<GlslDeclarations.Variable> removed) {
        List<String> others = declaration.declarators().stream()
                .filter(declarator -> removed.stream().noneMatch(variable -> variable.declarator() == declarator))
                .map(declarator -> tree.text(declarator.range()))
                .toList();
        if (others.isEmpty()) return "";
        String head = tree.text(new GlslTree.Range(declaration.range().start(), declaration.type().range().end()));
        return head + " " + String.join(", ", others) + ";";
    }
}
