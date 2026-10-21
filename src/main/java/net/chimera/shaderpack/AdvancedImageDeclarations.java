package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
        // Sizes resolve against the macros shaders.properties was read with, option values included.
        Map<String, String> macros = new TreeMap<>(settings.propertyMacros());
        macros.putAll(settings.preprocessorDefines());
        for (Map.Entry<String, String> entry : settings.propertyValues().entrySet()) {
            if (!entry.getKey().startsWith("image.")) continue;
            PackAdvancedResourcePlan.ImageSpec spec = parseImage(entry.getKey(), entry.getValue(), macros);
            images.put(spec.name(), spec);
            deviations.addAll(spec.deviations());
            if (spec.supported()) {
                deviations.add("ADVANCED_IMAGE_APPLIED:" + spec.name());
            }
        }
        applyBudget(images, deviations);
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
        String[] values = raw == null ? new String[0] : raw.trim().split("\s+");
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

    /** A 1D, 2D or 3D image of fixed size, inside the per-axis limit. */
    private static PackAdvancedResourcePlan.ImageSpec fixedImage(
            String name, String[] values, Map<String, String> defines, List<String> deviations) {
        int[] size = new int[3];
        for (int axis = 0; axis < values.length - 6; axis++) {
            size[axis] = resolveImageDimension(name, values[6 + axis], defines, deviations);
        }
        boolean unsized = size[0] < 1 || values.length > 7 && size[1] < 1 || values.length > 8 && size[2] < 1;
        long bytes = unsized ? 0 : Math.multiplyExact(Math.multiplyExact((long) size[0], Math.max(1, size[1])),
                Math.max(1, size[2])) * bytesPerVoxel(values[2].toLowerCase());
        if (unsized || Math.max(size[0], Math.max(size[1], size[2])) > PackAdvancedResourcePlan.MAX_DIMENSION) {
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

    static Map<String, PackAdvancedResourcePlan.ComputeSpec> discoverCompute(
            Path shadersDir,
            String selectedFolder,
            Map<String, PackAdvancedResourcePlan.ImageSpec> images,
            List<String> deviations,
            Map<String, String> initialMacros,
            Set<String> lockedMacros,
            Set<String> graphicsProducedImages,
            Map<String, UniformRegistry.UniformDescriptor> customDescriptors
    ) {
        if (shadersDir == null) return Map.of();
        Map<String, PackAdvancedResourcePlan.ComputeSpec> result = new TreeMap<>();
        Path source = shadowComputeSource(shadersDir, selectedFolder);
        if (source == null) return result;
        try {
            String raw = Files.readString(source, StandardCharsets.UTF_8);
            ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepare(
                    shadersDir, source, raw,
                    initialMacros, lockedMacros);
            if (!prepared.successful()) {
                deviations.add("COMPUTE_STAGE_UNSUPPORTED:shadowcomp");
                return result;
            }
            // The plan reads what PackShadowCompute translates (ServedUniforms.computeSource).
            String text = ServedUniforms.computeSource(prepared.source(),
                    name -> UniformRegistry.irisSets(name, customDescriptors));
            List<String> localDeviations = new ArrayList<>(prepared.deviations());
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(text);
            localDeviations.addAll(usage.deviations());
            if (!usage.successful()) {
                localDeviations.add("COMPUTE_RESOURCE_USAGE_UNCLASSIFIABLE");
            }
            WorkgroupSize size = workgroupSize(text, localDeviations);
            // Compute stages exist from GLSL 430; any profile converts to Chimera's 450 module.
            GlslVersion version = GlslVersion.first(text);
            if (version == null || !version.supported() || version.number() < 430) {
                localDeviations.add("COMPUTE_STAGE_UNSUPPORTED:shadowcomp");
            }
            TreeSet<String> names = new TreeSet<>();
            names.addAll(usage.liveImages());
            TreeSet<String> samplers = usage.liveSamplers().stream()
                    .filter(sampler -> PackAdvancedResourcePlan.imageBySampler(images, sampler) != null)
                    .collect(Collectors.toCollection(TreeSet::new));
            addImageDependencyDeviations(names, images, localDeviations);
            addSamplerDependencyDeviations(samplers, names, images, graphicsProducedImages, localDeviations);
            if (text.matches("(?s).*\\btexelFetch\\s*\\(.*")) {
                localDeviations.add("COMPUTE_TEXEL_FETCH_CLAMPED");
            }
            addValueUniformDeviations(text, customDescriptors, localDeviations);
            PackAdvancedResourcePlan.Status status = !hasBlockingComputeDeviation(localDeviations) && !names.isEmpty()
                    ? PackAdvancedResourcePlan.Status.SUPPORTED : PackAdvancedResourcePlan.Status.UNSUPPORTED;
            if (status == PackAdvancedResourcePlan.Status.SUPPORTED) deviations.add(
                    "COMPUTE_STAGE_SUPPORTED:shadowcomp");
            else deviations.add("COMPUTE_STAGE_UNSUPPORTED:shadowcomp");
            String relative = shadersDir.normalize().relativize(source).toString().replace('\\', '/');
            result.put("shadowcomp", new PackAdvancedResourcePlan.ComputeSpec("shadowcomp", relative,
                    size.x(), size.y(), size.z(),
                    new ArrayList<>(names), new ArrayList<>(samplers), status, localDeviations));
        } catch (IOException | RuntimeException failure) {
            deviations.add("COMPUTE_STAGE_UNSUPPORTED:shadowcomp");
        }
        return result;
    }

    /** The selected folder's shadowcomp.csh; null when it is missing or resolves outside the shaders. */
    private static Path shadowComputeSource(Path shadersDir, String selectedFolder) {
        Path root = selectedFolder == null || selectedFolder.isBlank()
                ? shadersDir : shadersDir.resolve(selectedFolder);
        Path source = root.resolve("shadowcomp.csh").normalize();
        if (!source.startsWith(shadersDir.normalize()) || !Files.isRegularFile(source)) return null;
        return source;
    }

    private record WorkgroupSize(int x, int y, int z) {}

    /** The declared local workgroup size, or zero with a deviation when the stage declares none. */
    private static WorkgroupSize workgroupSize(String text, List<String> localDeviations) {
        Matcher size = PackAdvancedResourcePlan.LOCAL_SIZE.matcher(text);
        if (!size.find()) {
            localDeviations.add("COMPUTE_WORKGROUP_UNSUPPORTED:shadowcomp");
            return new WorkgroupSize(0, 0, 0);
        }
        return new WorkgroupSize(Integer.parseInt(size.group(1)), Integer.parseInt(size.group(2)),
                Integer.parseInt(size.group(3)));
    }

    /** Each image the compute stage writes needs a supported declaration, by image or sampler name. */
    private static void addImageDependencyDeviations(Set<String> names,
                                                     Map<String, PackAdvancedResourcePlan.ImageSpec> images,
                                                     List<String> localDeviations) {
        for (String name : names) {
            PackAdvancedResourcePlan.ImageSpec dependency = PackAdvancedResourcePlan.imageBySymbol(images, name);
            boolean declared = dependency != null && dependency.supported();
            if (!declared) {
                localDeviations.add("COMPUTE_IMAGE_UNAVAILABLE:" + name);
            }
        }
    }

    /** Each image the compute stage samples needs a supported declaration and something that fills it. */
    private static void addSamplerDependencyDeviations(Set<String> samplers, Set<String> names,
                                                       Map<String, PackAdvancedResourcePlan.ImageSpec> images,
                                                       Set<String> graphicsProducedImages,
                                                       List<String> localDeviations) {
        for (String name : samplers) {
            PackAdvancedResourcePlan.ImageSpec dependency = PackAdvancedResourcePlan.imageBySampler(images, name);
            if (dependency == null || !dependency.supported()) {
                localDeviations.add("COMPUTE_SAMPLER_UNAVAILABLE:" + name);
            } else if (dependency.clear()
                    && !names.contains(dependency.name())
                    && !names.contains(name)
                    && (graphicsProducedImages == null
                    || !graphicsProducedImages.contains(dependency.name()))) {
                // A clear-only image is not a producer. BSL uses its
                // voxel image as compute input, but Chimera does not yet
                // have the shadow-geometry voxel writer that populates it.
                // Do not expose undefined image contents to dependent
                // graphics programs.
                localDeviations.add("COMPUTE_INPUT_UNAVAILABLE:" + name);
            }
        }
    }

    /** Each value uniform the compute stage declares: live or unused when resolvable, else unsupported if read. */
    private static void addValueUniformDeviations(String text,
                                                  Map<String, UniformRegistry.UniformDescriptor> customDescriptors,
                                                  List<String> localDeviations) {
        Matcher valueUniform = PackAdvancedResourcePlan.VALUE_UNIFORM_DECLARATION.matcher(text);
        while (valueUniform.find()) {
            String name = valueUniform.group(2);
            if (UniformRegistry.resolveForCompute(name, valueUniform.group(1), customDescriptors) != null) {
                if (GlslTokenRewriter.identifierCount(text, name) > 1) {
                    localDeviations.add("COMPUTE_UNIFORM_LIVE:" + name);
                } else {
                    localDeviations.add("COMPUTE_UNIFORM_UNUSED:" + name);
                }
            } else if (GlslTokenRewriter.identifierCount(text, name) > 1) {
                localDeviations.add("COMPUTE_UNIFORM_UNSUPPORTED:" + name);
            }
        }
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

    static boolean hasBlockingComputeDeviation(List<String> deviations) {
        return deviations.stream().anyMatch(value ->
                value == null
                        || (!ShaderSourcePreprocessor.isWarning(value)
                        && !value.startsWith("COMPUTE_UNIFORM_DEFAULTED:")
                        && !value.startsWith("COMPUTE_UNIFORM_LIVE:")
                        && !value.startsWith("COMPUTE_UNIFORM_UNUSED:")
                        && !value.equals("COMPUTE_TEXEL_FETCH_CLAMPED")));
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

    static String token(String[] values, int index) {
        return values.length > index ? values[index] : "";
    }
}
