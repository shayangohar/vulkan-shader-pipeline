package net.chimera.shaderpack;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The order Iris registers a program's samplers in. Each registration of a name appends a
 * glUniform1i for it, so the last one is what the name reads (ProgramSamplers.Builder
 * addDynamicSampler). A pack's customTexture entry or custom image sampler thus loses its name to
 * a built-in sampler Iris registers after it. A stage texture replaces what a registration made
 * through the sampler interceptor binds; each renderer makes some kinds of registration on the
 * program's own builder instead, which no stage texture replaces.
 */
final class IrisSamplerOrder {
    /** What Iris binds a sampler name to. */
    enum Source { TARGET, CUSTOM_TEXTURE, IMAGE, LEVEL, DEPTH, NOISE, SHADOW }

    /**
     * One renderer's registrations in order, those it makes on the builder ({@code direct}), and
     * the depth sampler names it registers.
     */
    record Order(List<Source> sources, Set<Source> direct, Pattern depth) {
        /** Whether a stage texture replaces what {@code source} binds. */
        boolean intercepted(Source source) {
            return !direct.contains(source);
        }

        /**
         * Of the pack's image sampler and customTexture entry for {@code name} ({@code image},
         * {@code customTexture}) and the built-in sampler registered under it, the one registered
         * last; null when there is none.
         */
        Source last(String name, boolean image, boolean customTexture) {
            Source last = builtIn(name);
            if (image && (last == null || sources.indexOf(Source.IMAGE) > sources.indexOf(last))) {
                last = Source.IMAGE;
            }
            if (customTexture && (last == null || sources.indexOf(Source.CUSTOM_TEXTURE) > sources.indexOf(last))) {
                last = Source.CUSTOM_TEXTURE;
            }
            return last;
        }

        private Source builtIn(String name) {
            if (name == null) return null;
            if (SHADOW.matcher(name).matches()) return Source.SHADOW;
            if (name.equals("noisetex")) return Source.NOISE;
            if (depth.matcher(name).matches()) return Source.DEPTH;
            if (sources.contains(Source.LEVEL) && LEVEL.matcher(name).matches()) return Source.LEVEL;
            return PackResourcePlan.targetIndex(name) != null ? Source.TARGET : null;
        }
    }

    /** The names IrisSamplers.addShadowSamplers registers. */
    private static final Pattern SHADOW = Pattern.compile("shadow|watershadow|shadowtex[01](?:HW)?|shadowcolor[0-7]?");
    /** The world's texture, lightmap, overlay and PBR maps (IrisSamplers.addLevelSamplers). */
    private static final Pattern LEVEL = Pattern.compile("tex|texture|gtexture|lightmap|iris_overlay|normals|specular");

    /**
     * A begin, prepare, deferred or composite program, and the computes of every pass
     * (CompositeRenderer, FinalPassRenderer.createComputes): customTexture entries on the builder.
     */
    static final Order POST = new Order(List.of(Source.TARGET, Source.CUSTOM_TEXTURE, Source.IMAGE, Source.NOISE,
            Source.DEPTH, Source.SHADOW), Set.of(Source.CUSTOM_TEXTURE), Pattern.compile("gdepthtex|depthtex[0-2]"));
    /** The final program, which registers its customTexture entries after images (FinalPassRenderer). */
    static final Order FINAL = new Order(List.of(Source.TARGET, Source.IMAGE, Source.CUSTOM_TEXTURE, Source.NOISE,
            Source.DEPTH, Source.SHADOW), Set.of(Source.CUSTOM_TEXTURE), POST.depth());
    /**
     * A setup compute (IrisRenderingPipeline.createSetupComputes): customTexture entries and the
     * composite depth samplers on the builder, before images.
     */
    static final Order SETUP = new Order(List.of(Source.TARGET, Source.CUSTOM_TEXTURE, Source.DEPTH, Source.IMAGE,
            Source.NOISE, Source.SHADOW), Set.of(Source.CUSTOM_TEXTURE, Source.DEPTH), POST.depth());
    /**
     * A gbuffers or shadow program (IrisRenderingPipeline.addGbufferOrShadowSamplers): customTexture
     * entries through the interceptor, the level samplers on the builder, images after noise and depth.
     */
    static final Order WORLD = new Order(List.of(Source.TARGET, Source.CUSTOM_TEXTURE, Source.LEVEL, Source.DEPTH,
            Source.NOISE, Source.IMAGE, Source.SHADOW), Set.of(Source.LEVEL), Pattern.compile("depthtex[0-2]"));

    private IrisSamplerOrder() {}

    /** The order {@code program} registers in, or a compute of that pass ({@code compute}). */
    static Order of(String program, boolean compute) {
        if (PassComputePlan.isSetup(program)) return SETUP;
        if (program == null || PackResourcePlan.postChain(program) == null) return WORLD;
        return !compute && program.equals("final") ? FINAL : POST;
    }
}
