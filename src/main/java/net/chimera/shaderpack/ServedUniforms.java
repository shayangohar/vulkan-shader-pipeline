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
 * iris_currentAlphaTest); FogMode.OFF's fogMode and fogShape are constants Iris sets once. A
 * declaration of another type is one Iris disables (ProgramUniforms "Wrong uniform type", KNOW-732):
 * it reads its initializer, or zero. Iris sets an int uniform declared bool.
 */
public final class ServedUniforms {
    private ServedUniforms() {}

    static String serve(String source, String name, String type, String value) {
        if (source == null || !GlslTokenRewriter.containsIdentifier(source, name)) return source;
        GlslTree tree = GlslDeclarations.parseOrNull(source);
        GlslDeclarations.Variable uniform = tree == null ? null : GlslDeclarations.variables(tree).stream()
                .filter(variable -> variable.name().equals(name) && variable.qualified("uniform"))
                .findFirst().orElse(null);
        if (uniform == null) return source;
        String served = servedValue(tree, uniform, type, value);
        List<GlslTree.Edit> edits = new ArrayList<>();
        edits.add(new GlslTree.Edit(uniform.declaration().range(), withoutDeclarator(tree, uniform)));
        List<GlslLexer.Token> tokens = tree.tokens();
        for (int index = 0; index < tokens.size(); index++) {
            if (uniform.declaration().range().contains(index) || !tokens.get(index).identifier(name)) continue;
            GlslTree.Binding binding = tree.resolve(index);
            if (binding != null && binding.node() == uniform.declarator()) {
                edits.add(new GlslTree.Edit(new GlslTree.Range(index, index + 1), served));
            }
        }
        return tree.print(edits);
    }

    /** The value a declaration reads: Iris's in its type, or a disabled uniform's initializer or zero. */
    private static String servedValue(GlslTree tree, GlslDeclarations.Variable uniform, String type, String value) {
        String declared = uniform.type();
        if (declared.equals(type)) return value;
        if (declared.equals("bool") && type.equals("int")) return "bool(" + value + ")";
        GlslTree.Range initializer = uniform.declarator().initializer();
        return initializer != null ? "(" + tree.text(initializer) + ")" : declared + "(0)";
    }

    /** The uniforms a fullscreen pass reads as constants ({@link #servePostValues}). */
    static final List<String> POST_VALUES = List.of("fogMode", "fogShape", "blendFunc");

    /**
     * Serves the values Iris fixes for a fullscreen pass: FogMode.OFF's ({@link #serveFogOff}) and
     * blendFunc. Blending is off when a pass's uniforms update (Program.use runs before the pass's
     * blend override applies), so blendFunc reads zero, unless an enabled {@code blend.<pass>}
     * directive's glBlendFuncSeparate then notifies it to the directive's factors. Only
     * CompositeRenderer applies one; final and shadowcomp passes are given none.
     */
    static String servePostValues(String source, PackBlendPlan.Mode passBlend) {
        int[] blend = passBlend == null ? new int[4] : passBlend.glFunc();
        String blendFunc = "ivec4(" + blend[0] + ", " + blend[1] + ", " + blend[2] + ", " + blend[3] + ")";
        return serve(serveFogOff(source), "blendFunc", "ivec4", blendFunc);
    }

    /**
     * FogMode.OFF's constants, fogMode 0 and fogShape -1: Iris builds every fullscreen pass and
     * compute program without fog (CompositeRenderer, FinalPassRenderer, ShadowCompositeRenderer
     * and the setup and shadow computes).
     */
    static String serveFogOff(String source) {
        return serve(serve(source, "fogMode", "int", "0"), "fogShape", "int", "-1");
    }

    /**
     * A compute stage's source as Iris compiles it: one declaration per name, each uniform Iris
     * never sets reading its initializer ({@link #readInitializers}), each one it sets without one
     * ({@link #withoutSetInitializers}), and FogMode.OFF's fog values. Every compute path (pass
     * computes, shadowcomp and its analysis) prepares its source here.
     */
    public static String computeSource(String source, Predicate<String> irisSets) {
        String initialized = readInitializers(GlslTokenRewriter.splitUniformDeclarators(source), irisSets).source();
        return serveFogOff(withoutSetInitializers(initialized, irisSets));
    }

    /**
     * Drops the initializer of each file-scope uniform Iris sets: the value Iris uploads replaces
     * it before any read, and Vulkan GLSL takes no initializer on a uniform. Rethinking Voxels
     * declares {@code uniform ivec3 cameraPositionInt = ivec3(-98257195)}.
     */
    static String withoutSetInitializers(String source, Predicate<String> irisSets) {
        if (source == null || !source.contains("=")) return source;
        GlslTree tree = GlslDeclarations.parseOrNull(source);
        if (tree == null) return source;
        List<GlslTree.Edit> edits = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            GlslTree.Declarator declarator = variable.declarator();
            if (!variable.qualified("uniform") || declarator.initializer() == null
                    || !irisSets.test(variable.name())) {
                continue;
            }
            int end = declarator.arrays().isEmpty() ? declarator.name() + 1
                    : declarator.arrays().get(declarator.arrays().size() - 1).end();
            edits.add(new GlslTree.Edit(new GlslTree.Range(end, declarator.initializer().end()), ""));
        }
        return edits.isEmpty() ? source : tree.print(edits);
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
