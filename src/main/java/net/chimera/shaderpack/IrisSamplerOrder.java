package net.chimera.shaderpack;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The order Iris registers a program's samplers in. Each registration of a name appends a
 * glUniform1i for it, so the last one is what the name reads (ProgramSamplers.Builder
 * addDynamicSampler). A pack's customTexture entry or custom image sampler thus loses its name to
 * a built-in sampler Iris registers after it. A stage texture replaces what a registration made
 * through the sampler interceptor binds, so a customTexture entry, registered around it, beats it.
 */
final class IrisSamplerOrder {
    /** What Iris binds a sampler name to. */
    enum Source { TARGET, CUSTOM_TEXTURE, IMAGE, LEVEL, DEPTH, NOISE, SHADOW }

    /** A post program, and every pass compute: CompositeRenderer and FinalPassRenderer.createComputes. */
    private static final List<Source> POST = List.of(Source.TARGET, Source.CUSTOM_TEXTURE, Source.IMAGE,
            Source.NOISE, Source.DEPTH, Source.SHADOW);
    /** The final program, which registers its customTexture entries after images (FinalPassRenderer). */
    private static final List<Source> FINAL = List.of(Source.TARGET, Source.IMAGE, Source.CUSTOM_TEXTURE,
            Source.NOISE, Source.DEPTH, Source.SHADOW);
    /** A world program, which registers images after its noise and depth (IrisRenderingPipeline.createShader). */
    private static final List<Source> WORLD = List.of(Source.TARGET, Source.CUSTOM_TEXTURE, Source.LEVEL,
            Source.DEPTH, Source.NOISE, Source.IMAGE, Source.SHADOW);
    /** The names IrisSamplers.addShadowSamplers registers. */
    private static final Pattern SHADOW =
            Pattern.compile("shadow|watershadow|shadowtex[01](?:HW|DH)?|shadowcolor[0-7]?");
    /** The world's texture, lightmap, overlay and PBR maps (IrisSamplers.addLevelSamplers). */
    private static final Pattern LEVEL = Pattern.compile("tex|texture|gtexture|lightmap|iris_overlay|normals|specular");

    private IrisSamplerOrder() {}

    /** The order {@code program} registers in, or a compute of that pass ({@code compute}). */
    static List<Source> of(String program, boolean compute) {
        if (program == null || PackResourcePlan.postChain(program) == null) return WORLD;
        return !compute && program.equals("final") ? FINAL : POST;
    }

    /**
     * Of the pack's image sampler and customTexture entry for {@code name} ({@code image},
     * {@code customTexture}) and the built-in sampler Iris registers under it, the one registered
     * last; null when there is none.
     */
    static Source last(List<Source> order, String name, boolean image, boolean customTexture) {
        Source last = builtIn(order, name);
        if (image && (last == null || order.indexOf(Source.IMAGE) > order.indexOf(last))) last = Source.IMAGE;
        if (customTexture && (last == null || order.indexOf(Source.CUSTOM_TEXTURE) > order.indexOf(last))) {
            last = Source.CUSTOM_TEXTURE;
        }
        return last;
    }

    /** The built-in sampler Iris registers under {@code name} in this order, or null for none. */
    private static Source builtIn(List<Source> order, String name) {
        if (name == null) return null;
        if (SHADOW.matcher(name).matches()) return Source.SHADOW;
        if (name.equals("noisetex")) return Source.NOISE;
        if (name.equals("gdepthtex") || name.matches("depthtex[0-2]")) return Source.DEPTH;
        if (order.contains(Source.LEVEL) && LEVEL.matcher(name).matches()) return Source.LEVEL;
        return PackResourcePlan.targetIndex(name) != null ? Source.TARGET : null;
    }
}
