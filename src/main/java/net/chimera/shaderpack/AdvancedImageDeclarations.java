package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads a pack's advanced images and compute stages: each image.<name> declaration and its
 * dimensions, the compute programs that write them, and the memory budget they must fit.
 */
final class AdvancedImageDeclarations {
    /** The sampler name ShaderProperties reads as no sampler. */
    private static final String NO_SAMPLER = "none";

    private AdvancedImageDeclarations() {}

    /** Parses every image.<name> property, then admits the supported images the memory budget fits. */
    static Map<String, PackAdvancedResourcePlan.ImageSpec> parseImages(
            PackSettingsPlan settings, List<String> deviations) {
        Map<String, PackAdvancedResourcePlan.ImageSpec> images = new TreeMap<>();
        // Sizes resolve against the macros in effect at the end of shaders.properties: option values and
        // the file's own defines.
        Map<String, String> macros = new TreeMap<>(settings.propertyMacros());
        macros.putAll(settings.preprocessorDefines());
        for (Map.Entry<String, String> entry : settings.propertyValues().entrySet()) {
            if (!entry.getKey().startsWith("image.")) continue;
            PackAdvancedResourcePlan.ImageSpec spec = parseImage(entry.getKey(), entry.getValue(), macros);
            images.put(spec.name(), spec);
            deviations.addAll(spec.deviations());
        }
        applyBudget(images, deviations);
        images.values().stream().filter(PackAdvancedResourcePlan.ImageSpec::supported)
                .forEach(spec -> deviations.add("ADVANCED_IMAGE_APPLIED:" + spec.name()));
        return images;
    }

    /**
     * One image.<name> declaration as Iris's ShaderProperties reads it: its sampler (or none), pixel
     * format, internal format, pixel type, clear and relative flags, then a relative image's share
     * of the screen, or a fixed image's width, height and depth, as many as it has dimensions (7, 8
     * or 9 values).
     */
    static PackAdvancedResourcePlan.ImageSpec parseImage(
            String key,
            String raw,
            Map<String, String> defines
    ) {
        String name = key.substring("image.".length());
        List<String> deviations = new ArrayList<>();
        String[] values = raw == null ? new String[0] : raw.trim().split("\\s+");
        boolean relative = values.length > 5 && Boolean.parseBoolean(values[5]);
        if (relative ? values.length < 8 : values.length < 7 || values.length > 9) {
            deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
            return image(name, values, new int[3], 0, PackAdvancedResourcePlan.Status.UNSUPPORTED, deviations);
        }
        try {
            return relative ? relativeImage(name, values, defines, deviations)
                    : fixedImage(name, values, defines, deviations);
        } catch (RuntimeException failure) {
            if (deviations.stream().noneMatch(value -> value.startsWith(
                    "ADVANCED_IMAGE_DIMENSION_UNRESOLVED:" + name + ":"))) {
                deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
            }
            return image(name, values, new int[3], 0, PackAdvancedResourcePlan.Status.UNSUPPORTED, deviations);
        }
    }

    /**
     * A 1D, 2D or 3D image of fixed size. Iris creates any size the driver takes; here the byte
     * budget (applyBudget) and, at creation, the device's per-axis limit bound it.
     */
    private static PackAdvancedResourcePlan.ImageSpec fixedImage(
            String name, String[] values, Map<String, String> defines, List<String> deviations) {
        int[] size = new int[3];
        for (int axis = 0; axis < values.length - 6; axis++) {
            size[axis] = resolveImageDimension(name, values[6 + axis], defines, deviations);
        }
        boolean unsized = size[0] < 1 || values.length > 7 && size[1] < 1 || values.length > 8 && size[2] < 1;
        long bytes = unsized ? 0 : Math.multiplyExact(Math.multiplyExact((long) size[0], Math.max(1, size[1])),
                Math.max(1, size[2])) * bytesPerVoxel(values[2].toLowerCase());
        if (unsized) {
            deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
            return image(name, values, size, bytes, PackAdvancedResourcePlan.Status.UNSUPPORTED, deviations);
        }
        deviations.add("ADVANCED_IMAGE_DECLARED:" + name);
        return image(name, values, size, bytes, PackAdvancedResourcePlan.Status.SUPPORTED, deviations);
    }

    /**
     * A relative image: 2D at a share of the screen (GlImage.Relative), so its size is the frame's
     * and it is created and resized with the screen. Its tokens keep the shares.
     */
    private static PackAdvancedResourcePlan.ImageSpec relativeImage(
            String name, String[] values, Map<String, String> defines, List<String> deviations) {
        float width = relativeShare(name, values[6], defines, deviations);
        float height = relativeShare(name, values[7], defines, deviations);
        bytesPerVoxel(values[2].toLowerCase());
        String[] shares = values.clone();
        shares[6] = Float.toString(width);
        shares[7] = Float.toString(height);
        if (!(width > 0.0F) || !(height > 0.0F)) {
            deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
            return image(name, shares, new int[3], 0, PackAdvancedResourcePlan.Status.UNSUPPORTED, deviations);
        }
        deviations.add("ADVANCED_IMAGE_DECLARED:" + name);
        return image(name, shares, new int[3], 0, PackAdvancedResourcePlan.Status.SUPPORTED, deviations);
    }

    /** A relative image's share of one screen axis: a number, or a macro that names one. */
    private static float relativeShare(String image, String token, Map<String, String> defines,
                                       List<String> deviations) {
        String value = token.trim();
        for (int depth = 0; depth < 8 && defines != null && defines.containsKey(value); depth++) {
            value = defines.get(value).trim();
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException failure) {
            deviations.add("ADVANCED_IMAGE_DIMENSION_UNRESOLVED:" + image + ":" + token);
            throw failure;
        }
    }

    /**
     * The name an image's selector slot is kept under: its sampler's, or for an image whose sampler
     * is {@code none} (ShaderProperties gives it no sampler), the image's own. GLSL gives a sampler
     * and an image uniform different names, so the two never meet.
     */
    static String slotKey(String image, String samplerToken) {
        return samplerToken.equals(NO_SAMPLER) ? image : samplerToken;
    }

    /** The spec of one declaration with this size; its tokens are the declaration's own. */
    private static PackAdvancedResourcePlan.ImageSpec image(String name, String[] values, int[] size, long bytes,
            PackAdvancedResourcePlan.Status status, List<String> deviations) {
        IrisColorFormats.Format format = IrisColorFormats.of(token(values, 2));
        if (status == PackAdvancedResourcePlan.Status.SUPPORTED && format != null && !format.exact()) {
            deviations.add("ADVANCED_IMAGE_FORMAT_APPROXIMATED:" + name + "=" + format.token() + "->"
                    + IrisColorFormats.stored(format.vkFormat()).token());
        }
        String sampler = token(values, 0).equals(NO_SAMPLER) ? "" : token(values, 0);
        return new PackAdvancedResourcePlan.ImageSpec(name, sampler, token(values, 1), token(values, 2).toLowerCase(),
                token(values, 3), Boolean.parseBoolean(token(values, 4)), Boolean.parseBoolean(token(values, 5)),
                size[0], size[1], size[2], sizeToken(values, 6), sizeToken(values, 7), sizeToken(values, 8),
                bytes, status, deviations);
    }

    private static String sizeToken(String[] values, int index) {
        return values.length > index ? values[index] : null;
    }

    static int resolveImageDimension(
            String image,
            String token,
            Map<String, String> defines,
            List<String> deviations
    ) {
        String value = token == null ? "" : token.trim();
        Set<String> visiting = new TreeSet<>();
        for (int depth = 0; depth < 8; depth++) {
            if (value.matches("[-+]?\\d+")) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException failure) {
                    break;
                }
            }
            if (!value.matches("[A-Za-z_]\\w*") || defines == null || !defines.containsKey(value)
                    || !visiting.add(value)) {
                break;
            }
            value = defines.get(value);
        }
        deviations.add("ADVANCED_IMAGE_DIMENSION_UNRESOLVED:" + image + ":" + token);
        return 0;
    }

    static void applyBudget(Map<String, PackAdvancedResourcePlan.ImageSpec> images, List<String> deviations) {
        List<Map.Entry<String, PackAdvancedResourcePlan.ImageSpec>> candidates = images.entrySet().stream()
                .filter(entry -> entry.getValue().supported())
                .sorted(Comparator.comparingLong((Map.Entry<String, PackAdvancedResourcePlan.ImageSpec> entry)
                                -> entry.getValue().estimatedBytes())
                        .thenComparing(Map.Entry::getKey))
                .toList();
        long admitted = 0L;
        boolean rejected = false;
        for (Map.Entry<String, PackAdvancedResourcePlan.ImageSpec> entry : candidates) {
            PackAdvancedResourcePlan.ImageSpec spec = entry.getValue();
            if (spec.estimatedBytes() > PackAdvancedResourcePlan.MAX_TOTAL_BYTES - admitted) {
                List<String> next = new ArrayList<>(spec.deviations());
                next.add("ADVANCED_IMAGE_BUDGET_EXCEEDED:" + spec.name());
                images.put(entry.getKey(), new PackAdvancedResourcePlan.ImageSpec(spec.name(), spec.sampler(),
                        spec.pixelFormat(), spec.internalFormat(), spec.pixelType(), spec.clear(),
                        spec.relative(), spec.width(), spec.height(), spec.depth(),
                        spec.widthToken(), spec.heightToken(), spec.depthToken(), spec.estimatedBytes(),
                        PackAdvancedResourcePlan.Status.UNSUPPORTED, next));
                deviations.add("ADVANCED_IMAGE_BUDGET_EXCEEDED:" + spec.name());
                rejected = true;
            } else {
                admitted += spec.estimatedBytes();
            }
        }
        if (rejected) deviations.add("ADVANCED_IMAGE_BUDGET_EXCEEDED");
    }

    /**
     * Whether a stage reads or writes a pack image: a live image, an image's sampler, or a live
     * 3D sampler. A 3D sampler a pack raw texture serves ({@code packVolume}) is an ordinary texture.
     */
    static boolean usesAdvancedResource(String source, Map<String, PackAdvancedResourcePlan.ImageSpec> images,
                                        Predicate<String> packVolume) {
        if (source == null) return false;
        Set<String> injectedSamplers = images == null ? Set.of() : images.values().stream()
                .map(PackAdvancedResourcePlan.ImageSpec::sampler)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(source, injectedSamplers);
        if (!usage.successful()) return true;
        if (!usage.liveImages().isEmpty()) return true;
        Matcher sampler3D = Pattern.compile("\\b(?:sampler3D|isampler3D|usampler3D)\\s+([A-Za-z_]\\w*)")
                .matcher(source);
        while (sampler3D.find()) {
            String name = sampler3D.group(1);
            if (usage.liveSamplers().contains(name) && !packVolume.test(name)) return true;
        }
        return usage.liveSamplers().stream().anyMatch(injectedSamplers::contains);
    }

    /** Bytes per texel of an Iris internal format as stored; Iris skips an image whose format it does not know. */
    static int bytesPerVoxel(String format) {
        IrisColorFormats.Format known = IrisColorFormats.of(format);
        if (known == null) throw new IllegalArgumentException("unsupported advanced image format: " + format);
        return known.bytes();
    }

    static String storageImageType(PackAdvancedResourcePlan.ImageSpec spec) {
        return spec == null || spec.format() == null ? "image3D" : spec.typePrefix() + "image" + spec.dimensionSuffix();
    }

    /** The GLSL type of an image read through {@code viewFormat}; its own type for an empty one. */
    static String storageImageType(PackAdvancedResourcePlan.ImageSpec spec, String viewFormat) {
        IrisColorFormats.Format view = viewFormat.isEmpty() ? null : IrisColorFormats.of(viewFormat);
        if (view == null) return storageImageType(spec);
        IrisColorFormats.Kind kind = view.kind();
        String prefix = kind == IrisColorFormats.Kind.UINT ? "u" : kind == IrisColorFormats.Kind.SINT ? "i" : "";
        return prefix + "image" + spec.dimensionSuffix();
    }

    /** The format a {@code layout(...)} list names, lower case; empty when it names none. */
    static String declaredFormat(String layout) {
        if (layout == null) return "";
        for (String part : layout.split(",")) {
            String token = part.trim();
            if (!token.contains("=") && IrisColorFormats.of(token) != null) return token.toLowerCase(Locale.ROOT);
        }
        return "";
    }

    /**
     * The format a declaration reads an image through when its qualifier names another one of the
     * same texel size, as a GL image unit reinterprets the bits (Alpha Piscium declares its R32F
     * uimg_csr32f r32i for integer atomics); empty for the image's own format or no qualifier, and
     * null for a format of another size, which nothing reads the image as.
     */
    static String viewFormat(PackAdvancedResourcePlan.ImageSpec spec, String declared) {
        return viewFormat(spec.format(), declared);
    }

    /** As {@link #viewFormat(PackAdvancedResourcePlan.ImageSpec, String)}, of any image stored in {@code own}. */
    static String viewFormat(IrisColorFormats.Format own, String declared) {
        IrisColorFormats.Format format = IrisColorFormats.of(declared);
        if (format == null || format.vkFormat() == own.vkFormat()) return "";
        return format.bytes() == own.bytes()
                ? IrisColorFormats.stored(format.vkFormat()).token().toLowerCase(Locale.ROOT) : null;
    }

    static String token(String[] values, int index) {
        return values.length > index ? values[index] : "";
    }
}
