package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable load-time plan for the first advanced-resource contract.
 *
 * <p>This is deliberately separate from {@link PackResourcePlan}. That plan
 * owns sampled two-dimensional textures. This plan describes writable image
 * declarations and the exact compute stages that may use them.</p>
 */
public final class PackAdvancedResourcePlan {
    public static final int MAX_DIMENSION = 512;
    public static final long MAX_TOTAL_BYTES = 768L * 1024L * 1024L;

    private static final Pattern LOCAL_SIZE = Pattern.compile(
            "layout\\s*\\(\\s*local_size_x\\s*=\\s*(\\d+)\\s*,\\s*"
                    + "local_size_y\\s*=\\s*(\\d+)\\s*,\\s*"
                    + "local_size_z\\s*=\\s*(\\d+)\\s*\\)", Pattern.MULTILINE);
    private static final Pattern IMAGE_DECLARATION = Pattern.compile(
            "(?:layout\\s*\\([^)]*\\)\\s*)?"
            + "(?:(?:uniform|writeonly|readonly|coherent|volatile|restrict)\\s+)*"
            + "\\b(?:u?i?image3D)\\s+([A-Za-z_]\\w*)");
    private static final Pattern GRAPHICS_IMAGE_DECLARATION = Pattern.compile(
            "(?:layout\\s*\\([^)]*\\)\\s*)?"
                    + "(?:(?:uniform|writeonly|readonly|coherent|volatile|restrict)\\s+)*"
                    + "\\b(u?i?image3D)\\s+([A-Za-z_]\\w*)");
    private static final Pattern SAMPLER_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+"
                    + "(u?sampler3D)\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern VALUE_UNIFORM_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+"
                    + "(bool|int|uint|float|double|vec[234]|ivec[234]|uvec[234]|bvec[234]"
                    + "|mat[234](?:x[234])?)\\s+([A-Za-z_]\\w*)\\s*;");

    public enum Status { SUPPORTED, UNSUPPORTED, UNAVAILABLE }

    public record ImageSpec(
            String name,
            String sampler,
            String pixelFormat,
            String internalFormat,
            String pixelType,
            boolean clear,
            boolean relative,
            int width,
            int height,
            int depth,
            long estimatedBytes,
            Status status,
            List<String> deviations
    ) {
        public ImageSpec {
            name = name == null ? "" : name;
            sampler = sampler == null ? "" : sampler;
            pixelFormat = pixelFormat == null ? "" : pixelFormat;
            internalFormat = internalFormat == null ? "" : internalFormat;
            pixelType = pixelType == null ? "" : pixelType;
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }

        public boolean supported() {
            return status == Status.SUPPORTED;
        }
    }

    public record ComputeSpec(
            String program,
            String relativeSource,
            int localSizeX,
            int localSizeY,
            int localSizeZ,
            List<String> imageNames,
            List<String> samplerNames,
            Status status,
            List<String> deviations
    ) {
        public ComputeSpec {
            program = program == null ? "" : program;
            relativeSource = relativeSource == null ? "" : relativeSource.replace('\\', '/');
            imageNames = imageNames == null ? List.of() : imageNames.stream().distinct().sorted().toList();
            samplerNames = samplerNames == null ? List.of() : samplerNames.stream().distinct().sorted().toList();
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }

        public boolean supported() {
            return status == Status.SUPPORTED;
        }
    }

    /** One storage image used by a graphics-stage imageStore declaration. */
    public record GraphicsImageBinding(
            String program,
            String symbol,
            String imageName,
            String sampler,
            String glslType,
            int selectorSlot
    ) {
        public GraphicsImageBinding {
            program = program == null ? "" : program;
            symbol = symbol == null ? "" : symbol;
            imageName = imageName == null ? "" : imageName;
            sampler = sampler == null ? "" : sampler;
            glslType = glslType == null ? "image3D" : glslType;
        }
    }

    private final Map<String, ImageSpec> images;
    private final Map<String, ComputeSpec> computeStages;
    private final Map<String, List<GraphicsImageBinding>> graphicsImages;
    private final List<String> dependentPrograms;
    private final List<String> customImageFeaturePrograms;
    private final List<String> deviations;
    private final String snapshot;
    private final String fingerprint;

    private PackAdvancedResourcePlan(
            Map<String, ImageSpec> images,
            Map<String, ComputeSpec> computeStages,
            Map<String, List<GraphicsImageBinding>> graphicsImages,
            List<String> dependentPrograms,
            List<String> customImageFeaturePrograms,
            List<String> deviations
    ) {
        this.images = Map.copyOf(new TreeMap<>(images == null ? Map.of() : images));
        this.computeStages = Map.copyOf(new TreeMap<>(computeStages == null ? Map.of() : computeStages));
        Map<String, List<GraphicsImageBinding>> graphicsCopy = new TreeMap<>();
        if (graphicsImages != null) {
            graphicsImages.forEach((program, bindings) -> graphicsCopy.put(program,
                    bindings == null ? List.of() : bindings.stream()
                            .distinct()
                            .sorted(Comparator.comparing(GraphicsImageBinding::symbol))
                            .toList()));
        }
        this.graphicsImages = Map.copyOf(graphicsCopy);
        this.dependentPrograms = sorted(dependentPrograms);
        this.customImageFeaturePrograms = sorted(customImageFeaturePrograms);
        this.deviations = sorted(deviations);
        this.snapshot = snapshotJson();
        this.fingerprint = ConformanceReport.sha256(snapshot.getBytes(StandardCharsets.UTF_8));
    }

    public static PackAdvancedResourcePlan empty() {
        return new PackAdvancedResourcePlan(Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of());
    }

    /** Builds only from the existing immutable pack plan and pack properties. */
    public static PackAdvancedResourcePlan build(PackPlan plan, Path shadersDir) {
        if (plan == null) return empty();
        Map<String, ImageSpec> images = new TreeMap<>();
        List<String> deviations = new ArrayList<>();
        for (Map.Entry<String, String> entry : plan.settings().propertyValues().entrySet()) {
            if (!entry.getKey().startsWith("image.")) continue;
            ImageSpec spec = parseImage(entry.getKey(), entry.getValue());
            images.put(spec.name(), spec);
            deviations.addAll(spec.deviations());
            if (spec.supported()) {
                deviations.add("ADVANCED_IMAGE_APPLIED:" + spec.name());
            }
        }
        applyBudget(images, deviations);

        boolean customImages = images.values().stream().anyMatch(ImageSpec::supported);
        Map<String, String> computeMacros = PackEngineDefines.forCompute(
                plan.settings().preprocessorDefines(), customImages);
        // Iris exposes this macro when the runtime supports custom images.
        // The bounded Chimera bridge supports the admitted image subset, so
        // compute preparation must see the same feature or guarded sampler
        // declarations will be removed while their uses remain active.
        List<String> dependent = new ArrayList<>();
        List<String> customImageFeaturePrograms = new ArrayList<>();
        Map<String, List<GraphicsImageBinding>> graphicsImages = new TreeMap<>();
        Set<String> graphicsProducedImages = new TreeSet<>();
        for (PackProgramPlan program : plan.programs()) {
            if (program == null || program.name().isBlank()) continue;
            boolean usesAdvanced = program.stages().values().stream().anyMatch(value ->
                    usesAdvancedResource(value.source(), images));
            if (usesAdvanced) {
                dependent.add(program.name());
            }
            if (!program.executable()) {
                continue;
            }
            for (PreparedShaderSource stage : program.stages().values()) {
                if (stage == null || stage.source() == null) continue;
                Matcher image = GRAPHICS_IMAGE_DECLARATION.matcher(stage.source());
                while (image.find()) {
                    if (!usesImageWrite(stage.source(), image.group(2))) {
                        continue;
                    }
                    ImageSpec spec = images.get(image.group(2));
                    if (spec == null) {
                        spec = images.values().stream()
                                .filter(value -> value.sampler().equals(image.group(2)))
                                .findFirst().orElse(null);
                    }
                    if (spec == null || !spec.supported()) continue;
                    int selectorSlot = plan.settings().customSamplerSlots()
                            .getOrDefault(spec.sampler(), -1);
                    if (selectorSlot < 0) {
                        deviations.add("PACK_RESOURCE_SLOT_LIMIT:" + spec.sampler());
                        continue;
                    }
                    graphicsImages.computeIfAbsent(program.name(), ignored -> new ArrayList<>())
                            .add(new GraphicsImageBinding(program.name(), image.group(2),
                                    spec.name(), spec.sampler(), image.group(1), selectorSlot));
                    graphicsProducedImages.add(spec.name());
                }
                if (stage.source().contains("IRIS_FEATURE_CUSTOM_IMAGES")
                        && stage.source().contains("OPTIFINE_ACT_ERROR")) {
                    customImageFeaturePrograms.add(program.name());
                }
            }
        }
        Map<String, ComputeSpec> compute = discoverCompute(
                shadersDir, plan.resolution().selectedSourceFolder(), images, deviations,
                computeMacros,
                PackEngineDefines.lockedNames(plan.settings().overriddenNames(), customImages, true),
                graphicsProducedImages);
        ComputeSpec shadowCompute = compute.get("shadowcomp");
        boolean shadowComputeSupported = shadowCompute != null
                && shadowCompute.supported()
                && requiredImagesSupported(shadowCompute, images);
        if (shadowComputeSupported) {
            deviations.add("CUSTOM_IMAGES_CAPABILITY_POSSIBLE");
        } else if (!images.isEmpty() || !compute.isEmpty()) {
            deviations.add("IRIS_FEATURE_CUSTOM_IMAGES_DISABLED");
        }
        if (!shadowComputeSupported) {
            for (String program : customImageFeaturePrograms) {
                deviations.add("CUSTOM_IMAGE_FEATURE_FALLBACK:" + program);
            }
        }
        for (String key : plan.settings().propertyValues().keySet()) {
            if (key.startsWith("bufferObject.") || key.startsWith("ssbo.") || key.startsWith("storage.")) {
                deviations.add("STORAGE_BUFFER_DEFERRED:" + key);
            }
        }
        return new PackAdvancedResourcePlan(images, compute, graphicsImages, dependent,
                customImageFeaturePrograms, deviations);
    }

    private static ImageSpec parseImage(String key, String raw) {
        String name = key.substring("image.".length());
        List<String> deviations = new ArrayList<>();
        String[] values = raw == null ? new String[0] : raw.trim().split("\\s+");
        if (values.length < 9) {
            deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
            return new ImageSpec(name, token(values, 0), token(values, 1), token(values, 2),
                    token(values, 3), false, false, 0, 0, 0, 0, Status.UNSUPPORTED, deviations);
        }
        String sampler = values[0];
        String pixelFormat = values[1];
        String internalFormat = values[2].toLowerCase();
        String pixelType = values[3];
        boolean clear = Boolean.parseBoolean(values[4]);
        boolean relative = Boolean.parseBoolean(values[5]);
        try {
            int width = Integer.parseInt(values[6]);
            int height = Integer.parseInt(values[7]);
            int depth = Integer.parseInt(values[8]);
            long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), depth)
                    * bytesPerVoxel(internalFormat);
            if (width < 1 || height < 1 || depth < 1 || width > MAX_DIMENSION
                    || height > MAX_DIMENSION || depth > MAX_DIMENSION) {
                deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
                return new ImageSpec(name, sampler, pixelFormat, internalFormat, pixelType,
                        clear, relative, width, height, depth, bytes, Status.UNSUPPORTED, deviations);
            }
            deviations.add("ADVANCED_IMAGE_DECLARED:" + name);
            return new ImageSpec(name, sampler, pixelFormat, internalFormat, pixelType,
                    clear, relative, width, height, depth, bytes, Status.SUPPORTED, deviations);
        } catch (RuntimeException failure) {
            deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
            return new ImageSpec(name, sampler, pixelFormat, internalFormat, pixelType,
                    clear, relative, 0, 0, 0, 0, Status.UNSUPPORTED, deviations);
        }
    }

    private static Map<String, ComputeSpec> discoverCompute(
            Path shadersDir,
            String selectedFolder,
            Map<String, ImageSpec> images,
            List<String> deviations,
            Map<String, String> initialMacros,
            java.util.Set<String> lockedMacros,
            Set<String> graphicsProducedImages
    ) {
        if (shadersDir == null) return Map.of();
        Path root = selectedFolder == null || selectedFolder.isBlank()
                ? shadersDir : shadersDir.resolve(selectedFolder);
        Map<String, ComputeSpec> result = new TreeMap<>();
        Path source = root.resolve("shadowcomp.csh").normalize();
        if (!source.startsWith(shadersDir.normalize()) || !Files.isRegularFile(source)) return result;
        try {
            String raw = Files.readString(source, StandardCharsets.UTF_8);
            ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepare(
                    shadersDir, source, raw,
                    initialMacros, lockedMacros);
            if (!prepared.successful()) {
                deviations.add("COMPUTE_STAGE_UNSUPPORTED:shadowcomp");
                return result;
            }
            String text = prepared.source();
            List<String> localDeviations = new ArrayList<>(prepared.deviations());
            Matcher size = LOCAL_SIZE.matcher(text);
            int x = 0, y = 0, z = 0;
            if (size.find()) {
                x = Integer.parseInt(size.group(1));
                y = Integer.parseInt(size.group(2));
                z = Integer.parseInt(size.group(3));
            } else {
                localDeviations.add("COMPUTE_WORKGROUP_UNSUPPORTED:shadowcomp");
            }
            if (!text.matches("(?s).*#version\\s+430\\s+compatibility.*")) {
                localDeviations.add("COMPUTE_STAGE_UNSUPPORTED:shadowcomp");
            }
            TreeSet<String> names = new TreeSet<>();
            Matcher declaration = IMAGE_DECLARATION.matcher(text);
            while (declaration.find()) names.add(declaration.group(1));
            TreeSet<String> samplers = new TreeSet<>();
            Matcher samplerDeclaration = SAMPLER_DECLARATION.matcher(text);
            while (samplerDeclaration.find()) samplers.add(samplerDeclaration.group(2));
            for (ImageSpec image : images.values()) {
                if (!image.sampler().isBlank() && usesSamplerCall(text, image.sampler())
                        && !names.contains(image.sampler())) {
                    samplers.add(image.sampler());
                }
            }
            for (String name : names) {
                ImageSpec dependency = images.get(name);
                if (dependency == null) {
                    dependency = images.values().stream()
                            .filter(spec -> spec.sampler().equals(name))
                            .findFirst().orElse(null);
                }
                boolean declared = dependency != null && dependency.supported();
                if (!declared) {
                    localDeviations.add("COMPUTE_IMAGE_UNAVAILABLE:" + name);
                }
            }
            for (String name : samplers) {
                ImageSpec dependency = images.values().stream()
                        .filter(value -> value.sampler().equals(name))
                        .findFirst().orElse(null);
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
            if (text.matches("(?s).*\\btexelFetch\\s*\\(.*")) {
                localDeviations.add("COMPUTE_TEXEL_FETCH_CLAMPED");
            }
            Matcher valueUniform = VALUE_UNIFORM_DECLARATION.matcher(text);
            while (valueUniform.find()) {
                String name = valueUniform.group(2);
                if (UniformRegistry.descriptor(name, valueUniform.group(1)) != null) {
                    if (GlslTokenRewriter.identifierCount(text, name) > 1) {
                        localDeviations.add("COMPUTE_UNIFORM_LIVE:" + name);
                    } else {
                        localDeviations.add("COMPUTE_UNIFORM_UNUSED:" + name);
                    }
                } else if (GlslTokenRewriter.identifierCount(text, name) > 1) {
                    localDeviations.add("COMPUTE_UNIFORM_UNSUPPORTED:" + name);
                }
            }
            Status status = !hasBlockingComputeDeviation(localDeviations) && !names.isEmpty()
                    ? Status.SUPPORTED : Status.UNSUPPORTED;
            if (status == Status.SUPPORTED) deviations.add("COMPUTE_STAGE_SUPPORTED:shadowcomp");
            else deviations.add("COMPUTE_STAGE_UNSUPPORTED:shadowcomp");
            String relative = shadersDir.normalize().relativize(source).toString().replace('\\', '/');
            result.put("shadowcomp", new ComputeSpec("shadowcomp", relative, x, y, z,
                    new ArrayList<>(names), new ArrayList<>(samplers), status, localDeviations));
        } catch (IOException | RuntimeException failure) {
            deviations.add("COMPUTE_STAGE_UNSUPPORTED:shadowcomp");
        }
        return result;
    }

    private static void applyBudget(Map<String, ImageSpec> images, List<String> deviations) {
        List<Map.Entry<String, ImageSpec>> candidates = images.entrySet().stream()
                .filter(entry -> entry.getValue().supported())
                .sorted(Comparator.comparingLong((Map.Entry<String, ImageSpec> entry)
                                -> entry.getValue().estimatedBytes())
                        .thenComparing(Map.Entry::getKey))
                .toList();
        long admitted = 0L;
        boolean rejected = false;
        for (Map.Entry<String, ImageSpec> entry : candidates) {
            ImageSpec spec = entry.getValue();
            if (spec.estimatedBytes() > MAX_TOTAL_BYTES - admitted) {
                List<String> next = new ArrayList<>(spec.deviations());
                next.add("ADVANCED_IMAGE_BUDGET_EXCEEDED:" + spec.name());
                images.put(entry.getKey(), new ImageSpec(spec.name(), spec.sampler(),
                        spec.pixelFormat(), spec.internalFormat(), spec.pixelType(), spec.clear(),
                        spec.relative(), spec.width(), spec.height(), spec.depth(), spec.estimatedBytes(),
                        Status.UNSUPPORTED, next));
                deviations.add("ADVANCED_IMAGE_BUDGET_EXCEEDED:" + spec.name());
                rejected = true;
            } else {
                admitted += spec.estimatedBytes();
            }
        }
        if (rejected) deviations.add("ADVANCED_IMAGE_BUDGET_EXCEEDED");
    }

    private static boolean hasBlockingComputeDeviation(List<String> deviations) {
        return deviations.stream().anyMatch(value ->
                value == null
                        || (!value.startsWith("PREPROCESSOR_MACRO_REDEFINED:")
                        && !value.startsWith("COMPUTE_UNIFORM_DEFAULTED:")
                        && !value.startsWith("COMPUTE_UNIFORM_LIVE:")
                        && !value.startsWith("COMPUTE_UNIFORM_UNUSED:")
                        && !value.equals("COMPUTE_TEXEL_FETCH_CLAMPED")));
    }

    private static boolean usesAdvancedImage(String source) {
        return source != null && source.matches("(?s).*\\b(?:image(?:Store|Load|Atomic[A-Za-z]*)|[ui]?image(?:1D|2D|3D|Cube))\\b.*");
    }

    private static boolean usesAdvancedResource(String source, Map<String, ImageSpec> images) {
        if (usesAdvancedImage(source)) return true;
        if (source == null) return false;
        // Keep a sampler3D declaration dependent even when its property entry
        // is unavailable. This selects identity fallback instead of allowing
        // an unplanned sampler to compile against an unrelated texture.
        if (source.matches("(?s).*\\b(?:sampler3D|isampler3D|usampler3D)\\b.*")) {
            return true;
        }
        if (images == null || images.isEmpty()) return false;
        for (ImageSpec image : images.values()) {
            String sampler = image.sampler();
            if (sampler != null && !sampler.isBlank()
                    && source.matches("(?s).*\\b(?:sampler3D|isampler3D|usampler3D)\\s+"
                    + Pattern.quote(sampler) + "\\b.*")) {
                return true;
            }
            if (sampler != null && !sampler.isBlank()
                    && usesSamplerCall(source, sampler)) {
                return true;
            }
        }
        return false;
    }

    private static boolean usesImageWrite(String source, String symbol) {
        if (source == null || symbol == null || symbol.isBlank()) return false;
        String identifier = Pattern.quote(symbol);
        return source.matches("(?s).*\\b(?:imageStore|imageAtomic[A-Za-z]*)\\s*\\(\\s*"
                + identifier + "\\b.*");
    }

    private static boolean usesSamplerCall(String source, String sampler) {
        if (source == null || sampler == null || sampler.isBlank()) return false;
        return source.matches("(?s).*\\b(?:texture|texture2D|texture3D|textureLod|"
                + "texture2DLod|texture3DLod|texelFetch)\\s*\\(\\s*"
                + Pattern.quote(sampler) + "\\b.*");
    }

    private static int bytesPerVoxel(String format) {
        return switch (format) {
            case "r8ui" -> 1;
            case "r16ui" -> 2;
            case "rgba16f" -> 8;
            default -> throw new IllegalArgumentException("unsupported advanced image format: " + format);
        };
    }

    private static String token(String[] values, int index) {
        return values.length > index ? values[index] : "";
    }

    public Map<String, ImageSpec> images() { return images; }
    public Map<String, ComputeSpec> computeStages() { return computeStages; }
    public Map<String, List<GraphicsImageBinding>> graphicsImages() { return graphicsImages; }
    public List<GraphicsImageBinding> graphicsImages(String program) {
        return graphicsImages.getOrDefault(program, List.of());
    }
    public boolean requiresGraphicsProducer() { return !graphicsImages.isEmpty(); }
    public List<String> customImageFeaturePrograms() { return customImageFeaturePrograms; }
    public List<String> dependentPrograms() { return dependentPrograms; }
    public List<String> deviations() { return deviations; }
    public boolean hasSupportedImages() { return !images.isEmpty() && images.values().stream().allMatch(ImageSpec::supported); }
    public boolean hasSupportedCompute() {
        ComputeSpec shadowCompute = computeStages.get("shadowcomp");
        return shadowCompute != null && shadowCompute.supported()
                && requiredImagesSupported(shadowCompute, images);
    }
    public boolean capabilityPossible() {
        ComputeSpec shadowCompute = computeStages.get("shadowcomp");
        return shadowCompute != null && shadowCompute.supported()
                && requiredImagesSupported(shadowCompute, images);
    }
    public String snapshot() { return snapshot; }
    public String fingerprint() { return fingerprint; }

    private String snapshotJson() {
        JsonObject root = new JsonObject();
        JsonArray imageArray = new JsonArray();
        images.values().stream().sorted(Comparator.comparing(ImageSpec::name)).forEach(value -> {
            JsonObject item = new JsonObject();
            item.addProperty("name", value.name());
            item.addProperty("sampler", value.sampler());
            item.addProperty("pixelFormat", value.pixelFormat());
            item.addProperty("internalFormat", value.internalFormat());
            item.addProperty("pixelType", value.pixelType());
            item.addProperty("clear", value.clear());
            item.addProperty("relative", value.relative());
            item.addProperty("width", value.width());
            item.addProperty("height", value.height());
            item.addProperty("depth", value.depth());
            item.addProperty("estimatedBytes", value.estimatedBytes());
            item.addProperty("status", value.status().name());
            item.add("deviations", strings(value.deviations()));
            imageArray.add(item);
        });
        root.add("images", imageArray);
        JsonArray computeArray = new JsonArray();
        computeStages.values().forEach(value -> {
            JsonObject item = new JsonObject();
            item.addProperty("program", value.program());
            item.addProperty("source", value.relativeSource());
            item.addProperty("localSizeX", value.localSizeX());
            item.addProperty("localSizeY", value.localSizeY());
            item.addProperty("localSizeZ", value.localSizeZ());
            item.add("images", strings(value.imageNames()));
            item.add("samplers", strings(value.samplerNames()));
            item.addProperty("status", value.status().name());
            item.add("deviations", strings(value.deviations()));
            computeArray.add(item);
        });
        root.add("computeStages", computeArray);
        JsonArray graphicsArray = new JsonArray();
        graphicsImages.forEach((program, bindings) -> {
            JsonObject item = new JsonObject();
            item.addProperty("program", program);
            JsonArray entries = new JsonArray();
            bindings.forEach(binding -> {
                JsonObject value = new JsonObject();
                value.addProperty("symbol", binding.symbol());
                value.addProperty("image", binding.imageName());
                value.addProperty("sampler", binding.sampler());
                value.addProperty("type", binding.glslType());
                value.addProperty("selectorSlot", binding.selectorSlot());
                entries.add(value);
            });
            item.add("bindings", entries);
            graphicsArray.add(item);
        });
        root.add("graphicsImages", graphicsArray);
        root.add("dependentPrograms", strings(dependentPrograms));
        root.add("customImageFeaturePrograms", strings(customImageFeaturePrograms));
        root.add("deviations", strings(deviations));
        root.addProperty("capabilityPossible", capabilityPossible());
        return root.toString();
    }

    private static List<String> sorted(Iterable<String> values) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) for (String value : values) if (value != null && !value.isBlank()) result.add(value);
        return List.copyOf(result);
    }

    private static boolean requiredImagesSupported(ComputeSpec compute, Map<String, ImageSpec> images) {
        if (compute == null || !compute.supported() || compute.imageNames().isEmpty()) return false;
        for (String name : compute.imageNames()) {
            ImageSpec image = images.get(name);
            if (image == null) {
                image = images.values().stream()
                        .filter(value -> value.sampler().equals(name))
                        .findFirst().orElse(null);
            }
            if (image == null || !image.supported()) return false;
        }
        for (String name : compute.samplerNames()) {
            ImageSpec image = images.values().stream()
                    .filter(value -> value.sampler().equals(name))
                    .findFirst().orElse(null);
            if (image == null || !image.supported()) return false;
        }
        return true;
    }

    private static JsonArray strings(Iterable<String> values) {
        JsonArray result = new JsonArray();
        sorted(values).forEach(result::add);
        return result;
    }
}
