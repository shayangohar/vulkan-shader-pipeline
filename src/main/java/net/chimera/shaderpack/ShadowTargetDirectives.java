package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris PackShadowDirectives' sampling settings. Each shadowcolor target has a format (RGBA by
 * default), a per-frame clear (on), a clear colour (1, 1, 1, 1), mipmaps (off) and nearest
 * filtering (off; always on for an integer format). Each shadow depth, shadowtex0 and shadowtex1,
 * has hardware filtering, mipmaps and nearest filtering, all off. The const directives apply in
 * the order the sources declare them; a base directive (shadowHardwareFiltering,
 * generateShadowMipmap, generateShadowColorMipmap) sets every target it covers, and a numbered one
 * sets one. Iris logs and ignores an unknown format or a malformed clear colour.
 */
public record ShadowTargetDirectives(List<ColorTarget> colors, List<DepthSampling> depths, List<String> deviations) {
    /** Iris MAX_SHADOW_COLOR_BUFFERS_IRIS: the shadowcolor targets with HIGHER_SHADOWCOLOR. */
    public static final int MAX_COLOR_TARGETS = 8;
    /** Iris MAX_SHADOW_COLOR_BUFFERS_OF: the shadowcolor targets without it. */
    public static final int LEGACY_COLOR_TARGETS = 2;

    /** One shadowcolor target's settings. */
    public record ColorTarget(IrisColorFormats.Format format, boolean clear, float[] clearColor, boolean mipmap,
                              boolean nearest) {
        /** Iris SamplingSettings.getNearest: an integer format never filters linearly. */
        public boolean nearestFiltering() {
            return nearest || format.kind() != IrisColorFormats.Kind.FLOAT;
        }
    }

    /** One shadow depth's settings: shadowtex0 then shadowtex1. */
    public record DepthSampling(boolean hardwareFiltering, boolean mipmap, boolean nearest) {}

    private static final Pattern NUMBERED = Pattern.compile(
            "(shadowcolor|shadowColor|shadowtex|shadow)(\\d)(Format|ClearColor|Clear|MinMagNearest|Nearest|Mipmap)");

    public ShadowTargetDirectives {
        colors = List.copyOf(colors);
        depths = List.copyOf(depths);
        deviations = deviations.stream().distinct().sorted().toList();
    }

    /** Iris's defaults: no directive declared. */
    public static ShadowTargetDirectives defaults() {
        return new Builder().build();
    }

    /** The settings the const directives in {@code sources}, in order, declare. */
    public static ShadowTargetDirectives parse(List<String> sources) {
        return read(sources.stream().flatMap(source -> IrisConstDirectives.find(source).stream()).toList());
    }

    /** The settings {@code directives}, in order, declare. */
    static ShadowTargetDirectives read(List<IrisConstDirectives.Directive> directives) {
        Builder builder = new Builder();
        for (IrisConstDirectives.Directive directive : directives) {
            builder.accept(directive.type(), directive.name(), directive.value());
        }
        return builder.build();
    }

    public ColorTarget color(int index) {
        return colors.get(index);
    }

    /** A refusal for each sampler the program reads that names a shadow resource Iris leaves unbound for the pack. */
    static List<String> unboundShadowSamplers(List<UniformRegistry.SamplerBinding> samplers,
                                              PackConfig.PackConfigData config, Predicate<String> read) {
        return samplers.stream().map(UniformRegistry.SamplerBinding::name)
                .filter(name -> !bound(name, config) && read.test(name))
                .map(name -> "SAMPLER_NOT_MAPPED:" + name).toList();
    }

    /**
     * Whether Iris binds the shadow resource {@code name} for the pack (IrisSamplers.addShadowSamplers):
     * a shadowcolor target among the pass's targets (shadowcolor2-7 only with HIGHER_SHADOWCOLOR), and
     * shadowtexNHW with SEPARATE_HARDWARE_SAMPLERS and that depth's shadowHardwareFiltering. Any other
     * name is not one of these and counts as bound here.
     */
    static boolean bound(String name, PackConfig.PackConfigData config) {
        int color = PackResourcePlan.shadowColorIndex(name);
        if (color >= 0) {
            return color < (config == null ? LEGACY_COLOR_TARGETS : config.shadowSettings().colorTargetCount());
        }
        if (!name.matches("shadowtex[01]HW")) return true;
        return config != null && config.settings().hasFeature(PackFeatureFlags.SEPARATE_HARDWARE_SAMPLERS)
                && config.shadowSettings().targets().depth(name.charAt("shadowtex".length()) - '0')
                .hardwareFiltering();
    }

    /** The Vulkan formats of shadowcolor0 to shadowcolor{@code count - 1}. */
    public List<Integer> attachmentFormats(int count) {
        return colors.subList(0, count).stream().map(target -> target.format().vkFormat()).toList();
    }

    public DepthSampling depth(int index) {
        return depths.get(index);
    }

    /** Mutable settings while the directives apply. */
    private static final class Builder {
        private final String[] formats = new String[MAX_COLOR_TARGETS];
        private final boolean[] clears = new boolean[MAX_COLOR_TARGETS];
        private final float[][] clearColors = new float[MAX_COLOR_TARGETS][];
        private final boolean[] colorMipmaps = new boolean[MAX_COLOR_TARGETS];
        private final boolean[] colorNearest = new boolean[MAX_COLOR_TARGETS];
        private final boolean[] hardwareFiltering = new boolean[2];
        private final boolean[] depthMipmaps = new boolean[2];
        private final boolean[] depthNearest = new boolean[2];
        private final List<String> deviations = new ArrayList<>();

        Builder() {
            Arrays.fill(formats, "RGBA");
            Arrays.fill(clears, true);
            for (int i = 0; i < MAX_COLOR_TARGETS; i++) clearColors[i] = new float[] {1.0F, 1.0F, 1.0F, 1.0F};
        }

        /**
         * One directive. Iris reads a boolean from a {@code const bool} of exactly true or false, a
         * format from a {@code const int}, and a clear colour from a {@code const vec4}.
         */
        void accept(String type, String name, String value) {
            boolean bool = type.equals("bool");
            if (bool && !value.equals("true") && !value.equals("false")) return;
            boolean flag = value.equals("true");
            switch (bool ? name : "") {
                case "shadowHardwareFiltering" -> Arrays.fill(hardwareFiltering, flag);
                case "generateShadowMipmap" -> Arrays.fill(depthMipmaps, flag);
                case "generateShadowColorMipmap" -> Arrays.fill(colorMipmaps, flag);
                case "shadowtexMipmap" -> depthMipmaps[0] = flag;
                case "shadowtexNearest" -> depthNearest[0] = flag;
                default -> acceptNumbered(type, name, value, flag);
            }
        }

        private void acceptNumbered(String type, String name, String value, boolean flag) {
            if (type.equals("bool") && name.matches("shadowHardwareFiltering[01]")) {
                hardwareFiltering[name.charAt(name.length() - 1) - '0'] = flag;
                return;
            }
            Matcher numbered = NUMBERED.matcher(name);
            if (!numbered.matches()) return;
            String prefix = numbered.group(1);
            int index = Integer.parseInt(numbered.group(2));
            String setting = numbered.group(3);
            String expected = setting.equals("Format") ? "int" : setting.equals("ClearColor") ? "vec4" : "bool";
            if (!type.equals(expected)) return;
            if (prefix.equals("shadowtex") || prefix.equals("shadow")) {
                acceptDepth(prefix, index, setting, flag);
            } else if (index < MAX_COLOR_TARGETS) {
                acceptColor(prefix, index, setting, value, flag);
            }
        }

        /** shadowtexNMipmap, shadowtexNNearest and shadowNMinMagNearest, for N 0 or 1. */
        private void acceptDepth(String prefix, int index, String setting, boolean flag) {
            if (index > 1) return;
            boolean depthName = prefix.equals("shadowtex");
            if (depthName && setting.equals("Mipmap")) depthMipmaps[index] = flag;
            if (depthName && setting.equals("Nearest") || !depthName && setting.equals("MinMagNearest")) {
                depthNearest[index] = flag;
            }
        }

        /**
         * shadowcolorN's Format, Clear, ClearColor, Mipmap and Nearest; shadowColorN's Mipmap,
         * Nearest and MinMagNearest.
         */
        private void acceptColor(String prefix, int index, String setting, String value, boolean flag) {
            boolean lower = prefix.equals("shadowcolor");
            switch (setting) {
                case "Format" -> {
                    if (lower) acceptFormat(index, value);
                }
                case "Clear" -> {
                    if (lower) clears[index] = flag;
                }
                case "ClearColor" -> {
                    if (lower) acceptClearColor(index, value);
                }
                case "Mipmap" -> colorMipmaps[index] = flag;
                case "Nearest" -> colorNearest[index] = flag;
                default -> {
                    if (!lower) colorNearest[index] = flag;
                }
            }
        }

        /** A format Iris knows; one Vulkan cannot store exactly is stored as IrisColorFormats approximates it. */
        private void acceptFormat(int index, String value) {
            IrisColorFormats.Format format = IrisColorFormats.of(value);
            if (format == null) {
                deviations.add("SHADOW_TARGET_FORMAT_IGNORED:shadowcolor" + index + "=" + value);
                return;
            }
            formats[index] = value;
            if (!format.exact()) {
                deviations.add("SHADOW_TARGET_FORMAT_APPROXIMATED:shadowcolor" + index + "=" + format.token() + "->"
                        + IrisColorFormats.stored(format.vkFormat()).token());
            }
        }

        private void acceptClearColor(int index, String value) {
            float[] color = PackConfig.parseColor(value);
            if (color == null) {
                deviations.add("SHADOW_TARGET_CLEAR_COLOR_IGNORED:shadowcolor" + index);
            } else {
                clearColors[index] = color;
            }
        }

        ShadowTargetDirectives build() {
            List<ColorTarget> colors = new ArrayList<>();
            for (int i = 0; i < MAX_COLOR_TARGETS; i++) {
                colors.add(new ColorTarget(IrisColorFormats.of(formats[i]), clears[i], clearColors[i].clone(),
                        colorMipmaps[i], colorNearest[i]));
            }
            List<DepthSampling> depths = List.of(
                    new DepthSampling(hardwareFiltering[0], depthMipmaps[0], depthNearest[0]),
                    new DepthSampling(hardwareFiltering[1], depthMipmaps[1], depthNearest[1]));
            return new ShadowTargetDirectives(colors, depths, deviations);
        }
    }
}
