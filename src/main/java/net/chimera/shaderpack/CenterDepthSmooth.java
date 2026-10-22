package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Iris's smoothed centre depth (CompositeDepthTransformer, CenterDepthSampler). A fullscreen pass
 * that declares {@code uniform float centerDepthSmooth} reads {@code iris_centerDepthSmooth}, a 1x1
 * texture the renderer smooths each frame, through {@code texture(iris_centerDepthSmooth,
 * vec2(0.5)).r}. Iris registers no uniform of that name, so in any other program it is an unset
 * uniform.
 */
final class CenterDepthSmooth {
    static final String UNIFORM = "centerDepthSmooth";
    static final String SAMPLER = "iris_centerDepthSmooth";

    private CenterDepthSmooth() {}

    /**
     * The source with its {@code uniform float centerDepthSmooth} replaced by the sampler and every
     * reference by the sampler's read, in a program whose renderer registers the sampler
     * (IrisSamplerOrder: composite-style passes and final); the source as it is otherwise.
     */
    static String apply(String source, String program) {
        if (source == null || !IrisSamplerOrder.of(program, false).registers(IrisSamplerOrder.Source.CENTER_DEPTH)) {
            return source;
        }
        GlslTree tree = GlslDeclarations.parseOrNull(source);
        GlslDeclarations.Variable uniform = tree == null ? null : GlslDeclarations.variables(tree).stream()
                .filter(CenterDepthSmooth::declaresTheUniform).findFirst().orElse(null);
        if (uniform == null) return source;
        GlslTree.Declaration declaration = uniform.declaration();
        String others = declaration.declarators().stream()
                .filter(declarator -> !tree.text(declarator.name()).equals(UNIFORM))
                .map(declarator -> source.substring(tree.offset(declarator.range().start()),
                        tree.offset(declarator.range().end())))
                .collect(Collectors.joining(", "));
        String replacement = "uniform sampler2D " + SAMPLER + ";" + (others.isEmpty() ? "" : " uniform float "
                + others + ";");
        String rewritten = GlslDeclarations.apply(source, List.of(new GlslDeclarations.TextEdit(
                tree.offset(declaration.range().start()), tree.offset(declaration.range().end()), replacement)));
        return GlslTokenRewriter.replaceIdentifiers(rewritten,
                Map.of(UNIFORM, "texture(" + SAMPLER + ", vec2(0.5)).r"));
    }

    /** {@code uniform float centerDepthSmooth}, alone or beside other names, as Iris's matcher reads it. */
    private static boolean declaresTheUniform(GlslDeclarations.Variable variable) {
        return variable.name().equals(UNIFORM) && variable.qualifiers().equals(List.of("uniform"))
                && variable.type().equals("float") && variable.unarrayed();
    }
}
