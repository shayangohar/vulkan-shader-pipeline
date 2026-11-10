package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * GLSL defines {@code any} and {@code all} over boolean vectors only, and glslang refuses a call
 * on a scalar. Escheridia's SSAO tests {@code any(isnan(occlusion))} on a float, which the GL
 * compiler the pack was written against accepts as the value itself. A source that makes such a
 * call gets the scalar overloads, which return their argument; any other source is unchanged.
 */
final class GlslScalarReductions {
    private static final Set<String> REDUCTIONS = Set.of("any", "all");
    /** The per-component tests whose result is scalar for a scalar argument. */
    private static final Set<String> COMPONENT_TESTS = Set.of("isnan", "isinf");
    private static final Set<String> SCALAR_FLOATS = Set.of("float", "double");
    private static final String SWIZZLE_COMPONENT = "[xyzwrgbastpq]";

    private GlslScalarReductions() {}

    /** {@code bool any(bool)} and {@code bool all(bool)} for each reduction the source calls on a scalar. */
    static List<String> overloads(GlslTree tree) {
        Set<String> defined = new TreeSet<>();
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Function function) defined.add(tree.text(function.name()));
        }
        Set<String> needed = new TreeSet<>();
        List<GlslLexer.Token> tokens = tree.tokens();
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() == GlslLexer.Kind.IDENTIFIER && REDUCTIONS.contains(token.text())
                    && !defined.contains(token.text()) && scalarCall(tree, index)) {
                needed.add(token.text());
            }
        }
        List<String> overloads = new ArrayList<>();
        needed.forEach(name -> overloads.add("bool " + name + "(bool chimeraValue) { return chimeraValue; }"));
        return overloads;
    }

    /**
     * Whether the call at {@code name} passes a bool variable, or isnan/isinf of a scalar, or such
     * tests joined by {@code ||} or {@code &&}: {@code any(isnan(f) || isinf(f))} on a float, which
     * VectorLogicalOperators later turns into {@code (any(isnan(f)) || any(isinf(f)))}, so each part
     * needs the scalar overload (audit GD9).
     */
    private static boolean scalarCall(GlslTree tree, int name) {
        List<List<Integer>> operands = operands(tree.tokens(), argument(tree.tokens(), name));
        return !operands.isEmpty() && operands.stream().allMatch(operand -> scalarOperand(tree, operand));
    }

    /** The argument's tokens split at its top-level {@code ||} and {@code &&}; none when a part is empty. */
    private static List<List<Integer>> operands(List<GlslLexer.Token> tokens, List<Integer> argument) {
        List<List<Integer>> operands = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int depth = 0;
        for (int index : argument) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("(") || token.symbol("[")) depth++;
            if (token.symbol(")") || token.symbol("]")) depth--;
            if (depth == 0 && (token.symbol("||") || token.symbol("&&"))) {
                if (current.isEmpty()) return List.of();
                operands.add(current);
                current = new ArrayList<>();
            } else {
                current.add(index);
            }
        }
        if (current.isEmpty()) return List.of();
        operands.add(current);
        return operands;
    }

    /** Whether one operand is a bool variable, or isnan/isinf of a scalar or of one vector component. */
    private static boolean scalarOperand(GlslTree tree, List<Integer> argument) {
        List<GlslLexer.Token> tokens = tree.tokens();
        if (argument.size() == 1) {
            return declaredType(tree, argument.get(0), "bool");
        }
        if (argument.size() < 4 || !COMPONENT_TESTS.contains(tokens.get(argument.get(0)).text())
                || !tokens.get(argument.get(1)).symbol("(") || !tokens.get(argument.getLast()).symbol(")")) {
            return false;
        }
        List<Integer> inner = argument.subList(2, argument.size() - 1);
        if (inner.size() == 1) {
            return SCALAR_FLOATS.stream().anyMatch(type -> declaredType(tree, inner.get(0), type));
        }
        // A single component of any vector: occlusion.r
        return inner.size() == 3 && tokens.get(inner.get(0)).kind() == GlslLexer.Kind.IDENTIFIER
                && tokens.get(inner.get(1)).symbol(".")
                && tokens.get(inner.get(2)).text().matches(SWIZZLE_COMPONENT);
    }

    /** The significant tokens between the call's parentheses, or none when it is not a call. */
    private static List<Integer> argument(List<GlslLexer.Token> tokens, int name) {
        int open = GlslLexer.nextSignificant(tokens, name);
        if (open < 0 || !tokens.get(open).symbol("(")) return List.of();
        int close = GlslLexer.matching(tokens, open, "(", ")");
        List<Integer> argument = new ArrayList<>();
        for (int index = open + 1; index < close; index++) {
            if (tokens.get(index).significant()) argument.add(index);
        }
        return argument;
    }

    /** Whether the identifier at {@code token} names a variable of the scalar {@code type}. */
    private static boolean declaredType(GlslTree tree, int token, String type) {
        GlslTree.Binding binding = tree.resolve(token);
        if (binding == null) return false;
        return switch (binding.node()) {
            case GlslTree.Parameter parameter -> parameter.arrays().isEmpty()
                    && named(tree, parameter.type(), type);
            case GlslTree.Declarator declarator -> declarator.arrays().isEmpty() && binding.declaration() != null
                    && named(tree, binding.declaration().type(), type);
            default -> false;
        };
    }

    private static boolean named(GlslTree tree, GlslTree.TypeSpec spec, String type) {
        return spec != null && spec.struct() == null && spec.arrays().isEmpty() && tree.text(spec.name()).equals(type);
    }
}
