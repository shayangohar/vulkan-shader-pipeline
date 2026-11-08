package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A fragment stage that reads gl_FragDepth and never writes it reads gl_FragCoord.z instead. GL
 * compiles such a read: its value is undefined until the shader writes one, and the fragment keeps
 * its rasterized depth, gl_FragCoord.z. Vulkan's validator rejects any FragDepth access in a module
 * without the DepthReplacing mode, which glslang declares only for a write, so the program did not
 * build. Noble passes gl_FragDepth to its shadow lookup in every forward program; its water,
 * portals, particles and translucent entities fell back to vanilla. A shader that may write it, by
 * assignment, increment or an out or inout argument, keeps every read; a redeclaration
 * ({@code layout(depth_greater) out float gl_FragDepth;}) is neither.
 */
final class UnwrittenFragDepth {
    private static final String NAME = "gl_FragDepth";
    private static final String RASTER_DEPTH = "gl_FragCoord.z";
    private static final Set<String> WRITES = Set.of("=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", "++", "--");

    private UnwrittenFragDepth() {}

    /**
     * Rewrites each read into {@code tokens}, which are {@code tree}'s tokens as earlier passes
     * replaced them, index for index. Returns whether it rewrote any.
     */
    static boolean readRasterDepth(GlslTree tree, List<GlslLexer.Token> tokens) {
        List<GlslLexer.Token> source = tree.tokens();
        List<Integer> reads = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            if (!source.get(index).identifier(NAME)) continue;
            int previous = GlslLexer.previousSignificant(source, index);
            if (previous >= 0 && source.get(previous).identifier("float")) continue;
            if (mayWrite(tree, index)) return false;
            reads.add(index);
        }
        reads.forEach(index -> tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, RASTER_DEPTH)));
        return !reads.isEmpty();
    }

    /** Whether the use at {@code index} may write: in place, or as a lone argument the callee takes out. */
    private static boolean mayWrite(GlslTree tree, int index) {
        List<GlslLexer.Token> tokens = tree.tokens();
        int previous = GlslLexer.previousSignificant(tokens, index);
        int next = GlslLexer.nextSignificant(tokens, index);
        GlslLexer.Token before = previous < 0 ? null : tokens.get(previous);
        GlslLexer.Token after = next < 0 ? null : tokens.get(next);
        if (after == null || writesInPlace(before, after)) return true;
        boolean loneArgument = before != null && (before.symbol("(") || before.symbol(","))
                && (after.symbol(")") || after.symbol(","));
        return loneArgument && outArgument(tree, index);
    }

    /** An assignment or increment, or a swizzle or subscript of the scalar, which could be either. */
    private static boolean writesInPlace(GlslLexer.Token before, GlslLexer.Token after) {
        if (before != null && (before.symbol("++") || before.symbol("--"))) return true;
        return after.kind() == GlslLexer.Kind.SYMBOL
                && (WRITES.contains(after.text()) || after.symbol(".") || after.symbol("["));
    }

    /** Whether the call holding the lone argument at {@code index} may take it out or inout. */
    private static boolean outArgument(GlslTree tree, int index) {
        List<GlslLexer.Token> tokens = tree.tokens();
        int position = 0;
        int depth = 0;
        for (int current = index - 1; current >= 0; current--) {
            GlslLexer.Token token = tokens.get(current);
            if (token.symbol(")") || token.symbol("]")) {
                depth++;
            } else if (token.symbol("(") || token.symbol("[")) {
                if (depth == 0) {
                    return token.symbol("(") && outParameter(tree, GlslLexer.previousSignificant(tokens, current),
                            position);
                }
                depth--;
            } else if (depth == 0 && token.symbol(",")) {
                position++;
            } else if (token.symbol(";") || token.symbol("{") || token.symbol("}")) {
                return false;
            }
        }
        return false;
    }

    /**
     * Whether the function named at {@code name} may take parameter {@code position} out: modf's
     * second, the one float out parameter among the built-ins, or any overload the source declares
     * with that parameter out or inout.
     */
    private static boolean outParameter(GlslTree tree, int name, int position) {
        if (name < 0 || tree.tokens().get(name).kind() != GlslLexer.Kind.IDENTIFIER) return false;
        String callee = tree.text(name);
        if (callee.equals("modf")) return position == 1;
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Function function) || !tree.text(function.name()).equals(callee)
                    || position >= function.parameters().size()) {
                continue;
            }
            for (GlslTree.Qualifier qualifier : function.parameters().get(position).qualifiers()) {
                String keyword = tree.text(qualifier.keyword());
                if (keyword.equals("out") || keyword.equals("inout")) return true;
            }
        }
        return false;
    }
}
