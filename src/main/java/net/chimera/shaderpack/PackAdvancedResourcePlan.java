package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
    public static final int MAX_BUFFER_INDEX = 12;
    public static final long MAX_STORAGE_BUFFER_BYTES = 64L * 1024L * 1024L;

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
    private static final Pattern STORAGE_BLOCK_DECLARATION = Pattern.compile(
            "(?s)(?:layout\\s*\\(([^)]*)\\)\\s*)?"
                    + "(?:(?:readonly|writeonly|coherent|volatile|restrict)\\s+)*"
                    + "buffer\\s+([A-Za-z_]\\w*)\\s*\\{");
    private static final Pattern STORAGE_BINDING = Pattern.compile(
            "(?i)\\bbinding\\s*=\\s*(\\d+)");

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
            String widthToken,
            String heightToken,
            String depthToken,
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
            widthToken = widthToken == null ? Integer.toString(width) : widthToken;
            heightToken = heightToken == null ? Integer.toString(height) : heightToken;
            depthToken = depthToken == null ? Integer.toString(depth) : depthToken;
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }

        /** Compatibility constructor for the original literal-dimension shape. */
        public ImageSpec(
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
            this(name, sampler, pixelFormat, internalFormat, pixelType, clear, relative,
                    width, height, depth, Integer.toString(width), Integer.toString(height),
                    Integer.toString(depth), estimatedBytes, status, deviations);
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
            int selectorSlot,
            int stageMask
    ) {
        /** Compatibility constructor for plans created before stage ownership was recorded. */
        public GraphicsImageBinding(
                String program,
                String symbol,
                String imageName,
                String sampler,
                String glslType,
                int selectorSlot
        ) {
            this(program, symbol, imageName, sampler, glslType, selectorSlot,
                    org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS);
        }

        public GraphicsImageBinding {
            program = program == null ? "" : program;
            symbol = symbol == null ? "" : symbol;
            imageName = imageName == null ? "" : imageName;
            sampler = sampler == null ? "" : sampler;
            glslType = glslType == null ? "image3D" : glslType;
            stageMask = stageMask == 0
                    ? org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS : stageMask;
        }
    }

    /** One validated shaders.properties bufferObject.N declaration. */
    public record BufferSpec(
            int logicalIndex,
            long size,
            String requestedName,
            Status status,
            List<String> deviations
    ) {
        public BufferSpec {
            requestedName = requestedName == null ? "" : requestedName.trim();
            status = status == null ? Status.UNAVAILABLE : status;
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }

        public boolean supported() {
            return status == Status.SUPPORTED;
        }
    }

    /** One program's admitted std430 block and its generated descriptor binding. */
    public record StorageBufferBinding(
            String program,
            String blockName,
            String instanceName,
            int originalBinding,
            int logicalIndex,
            int rewrittenBinding,
            int stageMask,
            long size,
            Status status,
            List<String> deviations
    ) {
        public StorageBufferBinding {
            program = program == null ? "" : program;
            blockName = blockName == null ? "" : blockName;
            instanceName = instanceName == null ? "" : instanceName;
            status = status == null ? Status.UNAVAILABLE : status;
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }

        public boolean supported() {
            return status == Status.SUPPORTED;
        }
    }

    /** A descriptor slot, independent of Vulkan object allocation. */
    public record DescriptorBinding(
            String symbol, int set, int binding, int type, int stages, String identity
    ) {
        /** Compatibility constructor for the original descriptor shape. */
        public DescriptorBinding(String symbol, int set, int binding, int type, int stages) {
            this(symbol, set, binding, type, stages, symbol == null ? "" : symbol);
        }

        public DescriptorBinding {
            symbol = symbol == null ? "" : symbol;
            identity = identity == null ? "" : identity;
        }
    }

    public record ProgramBindingLayout(int storageBase, int imageBase, int samplerBase,
            List<StorageBufferBinding> storageBuffers, List<GraphicsImageBinding> storageImages,
            List<GraphicsImageBinding> advancedSamplers,
            List<DescriptorBinding> ordinaryDescriptors,
            List<DescriptorBinding> descriptors) {
        /** Compatibility constructor for layouts created before ordinary descriptors were retained. */
        public ProgramBindingLayout(int storageBase, int imageBase, int samplerBase,
                List<StorageBufferBinding> storageBuffers, List<GraphicsImageBinding> storageImages,
                List<GraphicsImageBinding> advancedSamplers, List<DescriptorBinding> descriptors) {
            this(storageBase, imageBase, samplerBase, storageBuffers, storageImages,
                    advancedSamplers, List.of(), descriptors);
        }

        public ProgramBindingLayout {
            storageBuffers = List.copyOf(storageBuffers);
            storageImages = List.copyOf(storageImages);
            advancedSamplers = List.copyOf(advancedSamplers);
            ordinaryDescriptors = List.copyOf(ordinaryDescriptors);
            descriptors = List.copyOf(descriptors);
        }
    }

    private static ProgramBindingLayout bindingLayout(int base, List<StorageBufferBinding> buffers,
            List<GraphicsImageBinding> images, List<GraphicsImageBinding> samplers,
            List<DescriptorBinding> ordinaryDescriptors) {
        Map<Integer, StorageBufferBinding> uniqueBuffers = new TreeMap<>();
        for (StorageBufferBinding buffer : buffers) {
            if (!buffer.supported()) continue;
            uniqueBuffers.merge(buffer.logicalIndex(), buffer, (a, b) -> new StorageBufferBinding(
                    a.program(), a.blockName(), a.instanceName(), a.originalBinding(), a.logicalIndex(),
                    a.rewrittenBinding(), a.stageMask() | b.stageMask(), a.size(), a.status(), a.deviations()));
        }
        List<DescriptorBinding> slots = new ArrayList<>(ordinaryDescriptors);
        for (StorageBufferBinding buffer : buffers) {
            if (buffer.supported()) slots.add(new DescriptorBinding(buffer.blockName(), 0,
                    buffer.rewrittenBinding(), 9, buffer.stageMask(),
                    "storage:" + buffer.logicalIndex()));
        }
        List<GraphicsImageBinding> uniqueImages = mergeGraphicsBindings(images,
                GraphicsImageBinding::symbol);
        List<GraphicsImageBinding> uniqueSamplers = mergeGraphicsBindings(samplers,
                GraphicsImageBinding::sampler);
        int imageBase = base + uniqueBuffers.size();
        int samplerBase = imageBase + uniqueImages.size();
        for (int i = 0; i < uniqueImages.size(); i++) slots.add(new DescriptorBinding(
                uniqueImages.get(i).symbol(), 0, imageBase + i, 3,
                uniqueImages.get(i).stageMask(),
                "resource:" + uniqueImages.get(i).selectorSlot() + ":3"));
        for (int i = 0; i < uniqueSamplers.size(); i++) slots.add(new DescriptorBinding(
                uniqueSamplers.get(i).sampler(), 0, samplerBase + i, 1,
                uniqueSamplers.get(i).stageMask(),
                "resource:" + uniqueSamplers.get(i).selectorSlot() + ":1"));
        return new ProgramBindingLayout(base, imageBase, samplerBase,
                List.copyOf(uniqueBuffers.values()), uniqueImages, uniqueSamplers,
                ordinaryDescriptors, slots);
    }

    private static List<GraphicsImageBinding> mergeGraphicsBindings(
            List<GraphicsImageBinding> values,
            java.util.function.Function<GraphicsImageBinding, String> keyFunction
    ) {
        Map<String, GraphicsImageBinding> merged = new TreeMap<>();
        if (values != null) {
            for (GraphicsImageBinding value : values) {
                if (value == null) continue;
                String key = keyFunction.apply(value);
                GraphicsImageBinding previous = merged.get(key);
                if (previous == null) {
                    merged.put(key, value);
                } else {
                    merged.put(key, new GraphicsImageBinding(
                            previous.program(), previous.symbol(), previous.imageName(),
                            previous.sampler(), previous.glslType(), previous.selectorSlot(),
                            previous.stageMask() | value.stageMask()));
                }
            }
        }
        return List.copyOf(merged.values());
    }


    /** Reads the emitted shader declarations, not the rewrite's intended offsets. */
    public static List<DescriptorBinding> shaderBindings(String source, int stage) {
        List<DescriptorBinding> result = new ArrayList<>();
        if (source == null) return result;
        List<GlslLexer.Token> tokens = GlslLexer.lex(source).stream()
                .filter(GlslLexer.Token::significant).toList();
        int braceDepth = 0;
        int parenDepth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String word = tokens.get(i).text();
            switch (word) {
                case "{" -> braceDepth++;
                case "}" -> braceDepth--;
                case "(" -> parenDepth++;
                case ")" -> parenDepth--;
            }
            // Opaque function parameters borrow the caller's resource; they
            // do not declare descriptor slots, even in a global prototype.
            if (braceDepth != 0 || parenDepth != 0) continue;
            if (word.equals("uniform") && i + 2 < tokens.size()
                    && tokens.get(i + 1).kind() == GlslLexer.Kind.IDENTIFIER
                    && tokens.get(i + 2).text().equals("{")) {
                int start = i - 1;
                while (start >= 0 && !Set.of(";", "{", "}").contains(tokens.get(start).text())) start--;
                int set = descriptorInteger(tokens, start + 1, i, "set", 0);
                int binding = descriptorInteger(tokens, start + 1, i, "binding", -1);
                if (binding < 0) continue;
                result.add(new DescriptorBinding(tokens.get(i + 1).text(), set, binding, 8, stage,
                        "ubo:" + binding));
                continue;
            }
            int type = word.equals("buffer") ? 9
                    : word.matches("[ui]?image3D") ? 3
                    : word.matches("(?:[ui]?sampler3D|sampler2D(?:Shadow)?)") ? 1 : -1;
            if (type < 0 || i + 1 >= tokens.size()) continue;
            if (tokens.get(i + 1).kind() != GlslLexer.Kind.IDENTIFIER) continue;
            String symbol = tokens.get(i + 1).text();
            int start = i - 1;
            while (start >= 0 && !Set.of(";", "{", "}").contains(tokens.get(start).text())) start--;
            if (type != 9) {
                boolean uniform = false;
                for (int j = start + 1; j < i; j++) {
                    if (tokens.get(j).identifier("uniform")) uniform = true;
                }
                if (!uniform) continue;
            }
            int set = descriptorInteger(tokens, start + 1, i, "set", 0);
            int binding = descriptorInteger(tokens, start + 1, i, "binding", -1);
            result.add(new DescriptorBinding(symbol, set, binding, type, stage, symbol));
        }
        return List.copyOf(result);
    }

    private static int descriptorInteger(
            List<GlslLexer.Token> tokens, int start, int end, String key, int fallback
    ) {
        for (int index = Math.max(0, start); index + 2 < end; index++) {
            if (!tokens.get(index).text().equals(key)
                    || !tokens.get(index + 1).text().equals("=")
                    || !tokens.get(index + 2).text().matches("[0-9]+")) continue;
            return Integer.parseInt(tokens.get(index + 2).text());
        }
        return fallback;
    }

    public static String layoutMismatch(ProgramBindingLayout layout, String vertex, String fragment,
            List<DescriptorBinding> descriptors) {
        if (layout == null) return "DESCRIPTOR_LAYOUT_MISMATCH:missing-plan";
        List<DescriptorBinding> declared = new ArrayList<>(shaderBindings(vertex, 1));
        declared.addAll(shaderBindings(fragment, 16));
        Map<String, Integer> nativeIdentities = new TreeMap<>();
        for (DescriptorBinding descriptor : descriptors) {
            String identity = descriptor.identity();
            if (identity == null || identity.isBlank()) continue;
            String key = identity + ":type=" + descriptor.type();
            if (nativeIdentities.merge(key, 1, Integer::sum) > 1) {
                return "DESCRIPTOR_IDENTITY_DUPLICATE:" + identity;
            }
        }
        Set<String> nativeBindings = new TreeSet<>();
        for (DescriptorBinding descriptor : descriptors) {
            String key = descriptor.set() + ":" + descriptor.binding();
            if (!nativeBindings.add(key)) {
                return "DESCRIPTOR_BINDING_DUPLICATE:" + key;
            }
        }
        for (DescriptorBinding shader : declared) {
            List<DescriptorBinding> expectedMatches = layout.descriptors().stream()
                    .filter(value -> value.set() == shader.set()
                            && value.binding() == shader.binding()
                            && value.type() == shader.type()).toList();
            DescriptorBinding expected = expectedMatches.size() == 1
                    ? expectedMatches.get(0) : null;
            if (expected == null
                    || (expected.stages() & shader.stages()) != shader.stages()) {
                return "DESCRIPTOR_LAYOUT_MISMATCH:" + shader.symbol();
            }
            List<DescriptorBinding> matches = descriptors.stream().filter(value ->
                    value.set() == shader.set() && value.binding() == shader.binding()).toList();
            if (matches.size() != 1 || matches.get(0).type() != shader.type()
                    || (matches.get(0).stages() & shader.stages()) != shader.stages()) {
                return "DESCRIPTOR_LAYOUT_MISMATCH:" + shader.symbol();
            }
        }
        for (DescriptorBinding expected : layout.descriptors()) {
            List<DescriptorBinding> matches = descriptors.stream().filter(value ->
                    value.set() == expected.set() && value.binding() == expected.binding()
                            && value.type() == expected.type()).toList();
            if (matches.size() != 1
                    || (matches.get(0).stages() & expected.stages()) != expected.stages()) {
                return "DESCRIPTOR_LAYOUT_MISMATCH:" + expected.symbol();
            }
        }
        for (DescriptorBinding actual : descriptors) {
            boolean planned = layout.descriptors().stream().anyMatch(expected ->
                    expected.set() == actual.set() && expected.binding() == actual.binding()
                            && expected.type() == actual.type());
            if (!planned) {
                return "DESCRIPTOR_LAYOUT_EXTRA:" + actual.binding();
            }
        }
        return null;
    }
    private final Map<String, ProgramBindingLayout> bindingLayouts;

    private final Map<String, ImageSpec> images;
    private final Map<String, ComputeSpec> computeStages;
    private final Map<String, List<GraphicsImageBinding>> graphicsImages;
    private final Map<String, List<GraphicsImageBinding>> graphicsImageReaders;
    private final Map<Integer, BufferSpec> buffers;
    private final Map<String, List<StorageBufferBinding>> storageBuffers;
    private final List<String> dependentPrograms;
    private final List<String> bufferDependentPrograms;
    private final List<String> storageEligiblePrograms;
    private final List<String> customImageFeaturePrograms;
    private final List<String> deviations;
    private final String snapshot;
    private final String fingerprint;
    private final Map<String, OrdinaryDescriptorContract> ordinaryContracts;

    private PackAdvancedResourcePlan(
            Map<String, ImageSpec> images,
            Map<String, ComputeSpec> computeStages,
            Map<String, List<GraphicsImageBinding>> graphicsImages,
            Map<String, List<GraphicsImageBinding>> graphicsImageReaders,
            Map<Integer, BufferSpec> buffers,
            Map<String, List<StorageBufferBinding>> storageBuffers,
            List<String> dependentPrograms,
            List<String> bufferDependentPrograms,
            List<String> storageEligiblePrograms,
            List<String> customImageFeaturePrograms,
            List<String> deviations,
            Map<String, OrdinaryDescriptorContract> ordinaryContracts,
            Map<String, Integer> bindingBases
    ) {
        this.ordinaryContracts = Map.copyOf(ordinaryContracts);
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
        Map<String, List<GraphicsImageBinding>> readerCopy = new TreeMap<>();
        if (graphicsImageReaders != null) {
            graphicsImageReaders.forEach((program, bindings) -> readerCopy.put(program,
                    bindings == null ? List.of() : bindings.stream()
                            .distinct()
                            .sorted(Comparator.comparing(GraphicsImageBinding::symbol))
                            .toList()));
        }
        this.graphicsImageReaders = Map.copyOf(readerCopy);
        this.buffers = Map.copyOf(new TreeMap<>(buffers == null ? Map.of() : buffers));
        Map<String, List<StorageBufferBinding>> storageCopy = new TreeMap<>();
        if (storageBuffers != null) {
            storageBuffers.forEach((program, bindings) -> storageCopy.put(program,
                    bindings == null ? List.of() : bindings.stream()
                            .distinct()
                            .sorted(Comparator.comparingInt(StorageBufferBinding::logicalIndex)
                                    .thenComparing(StorageBufferBinding::blockName))
                            .toList()));
        }
        this.storageBuffers = Map.copyOf(storageCopy);
        Map<String, ProgramBindingLayout> layouts = new TreeMap<>();
        bindingBases.forEach((program, base) -> layouts.put(program, bindingLayout(base,
                this.storageBuffers.getOrDefault(program, List.of()),
                this.graphicsImages.getOrDefault(program, List.of()),
                this.graphicsImageReaders.getOrDefault(program, List.of()),
                ordinaryContracts.containsKey(program)
                        ? ordinaryContracts.get(program).descriptors() : List.of())));
        this.bindingLayouts = Map.copyOf(layouts);
        this.dependentPrograms = sorted(dependentPrograms);
        this.bufferDependentPrograms = sorted(bufferDependentPrograms);
        this.storageEligiblePrograms = sorted(storageEligiblePrograms);
        this.customImageFeaturePrograms = sorted(customImageFeaturePrograms);
        this.deviations = sorted(deviations);
        this.snapshot = snapshotJson();
        this.fingerprint = ConformanceReport.sha256(snapshot.getBytes(StandardCharsets.UTF_8));
    }

    public static PackAdvancedResourcePlan empty() {
        return new PackAdvancedResourcePlan(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    /**
     * Parses only buffer properties. This small first-pass plan lets the
     * program builder preserve admitted storage declarations while the full
     * source scan constructs the immutable per-program bindings.
     */
    public static PackAdvancedResourcePlan catalog(PackSettingsPlan settings) {
        Map<Integer, BufferSpec> buffers = parseBuffers(settings, new ArrayList<>());
        return new PackAdvancedResourcePlan(Map.of(), Map.of(), Map.of(), Map.of(), buffers, Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    /**
     * Returns live sampler and image symbols from the selected compute wrapper.
     * Compute sources are not ordinary PackProgram entries, so this inventory
     * is performed before selector allocation and uses the same preprocessor
     * boundary as the final compute plan.
     */
    static Set<String> liveComputeResourceNames(
            Path shadersDir,
            String selectedFolder,
            PackSettingsPlan settings,
            boolean customImages
    ) {
        if (shadersDir == null) return Set.of();
        Path root = selectedFolder == null || selectedFolder.isBlank()
                ? shadersDir : shadersDir.resolve(selectedFolder);
        Path source = root.resolve("shadowcomp.csh").normalize();
        if (!source.startsWith(shadersDir.normalize()) || !Files.isRegularFile(source)) {
            return Set.of();
        }
        try {
            String raw = Files.readString(source, StandardCharsets.UTF_8);
            ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepare(
                    shadersDir, source, raw,
                    PackEngineDefines.forCompute(
                            settings == null ? Map.of() : settings.preprocessorDefines(), customImages),
                    PackEngineDefines.lockedNames(
                            settings == null ? Set.of() : settings.overriddenNames(),
                            customImages, true));
            if (!prepared.successful()) return Set.of();
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(prepared.source());
            if (!usage.successful()) return Set.of();
            TreeSet<String> names = new TreeSet<>();
            names.addAll(usage.liveSamplers());
            names.addAll(usage.liveImages());
            return Set.copyOf(names);
        } catch (IOException | RuntimeException failure) {
            return Set.of();
        }
    }

    /** Builds only from the existing immutable pack plan and pack properties. */
    public static PackAdvancedResourcePlan build(PackPlan plan, Path shadersDir) {
        if (plan == null) return empty();
        Map<String, ImageSpec> images = new TreeMap<>();
        List<String> deviations = new ArrayList<>();
        for (Map.Entry<String, String> entry : plan.settings().propertyValues().entrySet()) {
            if (!entry.getKey().startsWith("image.")) continue;
            ImageSpec spec = parseImage(entry.getKey(), entry.getValue(),
                    plan.settings() == null ? Map.of() : plan.settings().preprocessorDefines());
            images.put(spec.name(), spec);
            deviations.addAll(spec.deviations());
            if (spec.supported()) {
                deviations.add("ADVANCED_IMAGE_APPLIED:" + spec.name());
            }
        }
        applyBudget(images, deviations);
        Map<Integer, BufferSpec> buffers = parseBuffers(plan.settings(), deviations);

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
        Map<String, List<GraphicsImageBinding>> graphicsImageReaders = new TreeMap<>();
        Set<String> imageSamplerNames = images.values().stream()
                .map(ImageSpec::sampler)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> graphicsProducedImages = new TreeSet<>();
        for (PackProgramPlan program : plan.programs()) {
            if (program == null || program.name().isBlank()) continue;
            boolean usesAdvanced = program.stages().values().stream().anyMatch(value ->
                    usesAdvancedResource(value.source(), images));
            if (usesAdvanced) {
                dependent.add(program.name());
            }
            for (Map.Entry<String, PreparedShaderSource> stageEntry : program.stages().entrySet()) {
                PreparedShaderSource stage = stageEntry.getValue();
                if (stage == null || stage.source() == null) continue;
                GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(
                        stage.source(), imageSamplerNames);
                deviations.addAll(usage.deviations());
                Matcher image = GRAPHICS_IMAGE_DECLARATION.matcher(stage.source());
                while (image.find()) {
                    if (!usage.liveImages().contains(image.group(2))) {
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
                                    spec.name(), spec.sampler(), image.group(1), selectorSlot,
                                    stageMask(stageEntry.getKey())));
                    graphicsProducedImages.add(spec.name());
                }
                // A GLSL compiler still resolves declarations in helper
                // functions that are not reachable from main().  Inject a
                // descriptor for every referenced, pack-declared image
                // sampler so those helpers compile.  Runtime eligibility
                // remains based on liveSamplers() in usesAdvancedResource;
                // this does not create a fake alias or make an unrelated
                // sampler a dependency.
                for (String sampler : usage.referencedSamplers()) {
                    ImageSpec spec = images.values().stream()
                            .filter(value -> value.sampler().equals(sampler))
                            .findFirst().orElse(null);
                    if (spec == null || !spec.supported()) continue;
                    int selectorSlot = plan.settings().customSamplerSlots()
                            .getOrDefault(spec.sampler(), -1);
                    if (selectorSlot < 0) {
                        deviations.add("PACK_RESOURCE_SLOT_LIMIT:" + spec.sampler());
                        continue;
                    }
                    graphicsImageReaders.computeIfAbsent(program.name(), ignored -> new ArrayList<>())
                            .add(new GraphicsImageBinding(program.name(), spec.name(), spec.name(),
                                    spec.sampler(), storageImageType(spec), selectorSlot,
                                    stageMask(stageEntry.getKey())));
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
                graphicsProducedImages,
                plan.settings().runtimeSettings().customDescriptors());
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
        List<String> bufferDependent = new ArrayList<>();
        List<String> storageEligible = new ArrayList<>();
        Map<String, Set<String>> advancedSamplerNames = new TreeMap<>();
        graphicsImageReaders.forEach((program, bindings) -> advancedSamplerNames.put(program,
                bindings.stream().map(GraphicsImageBinding::sampler)
                        .filter(value -> value != null && !value.isBlank())
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new))));
        Map<String, OrdinaryDescriptorContract> ordinaryContracts = new TreeMap<>();
        Map<String, Integer> bindingBases = new TreeMap<>();
        PackResourcePlan sampledResources = PackResourcePlan.build(plan, shadersDir);
        for (PackProgramPlan program : plan.programs()) {
            var contract = PackPipelines.ordinaryDescriptorContract(program,
                    advancedSamplerNames.getOrDefault(program.name(), Set.of()))
                    .withResources(sampledResources.bindings(program.name()));
            ordinaryContracts.put(program.name(), contract);
            bindingBases.put(program.name(), contract.nextBinding());
        }
        Map<String, List<StorageBufferBinding>> storageBuffers = scanStorageBuffers(
                plan, buffers, bufferDependent, storageEligible, deviations, bindingBases);
        Map<Integer, BufferSpec> budgetedBuffers = applyStorageBudget(
                buffers, storageBuffers, storageEligible, images, deviations);
        if (!budgetedBuffers.equals(buffers)) {
            buffers = budgetedBuffers;
            bufferDependent.clear();
            storageEligible.clear();
            storageBuffers = scanStorageBuffers(
                    plan, buffers, bufferDependent, storageEligible, deviations, bindingBases);
        }
        return new PackAdvancedResourcePlan(images, compute, graphicsImages, graphicsImageReaders, buffers,
                storageBuffers, dependent, bufferDependent, storageEligible,
                customImageFeaturePrograms, deviations, ordinaryContracts, bindingBases);
    }

    private static Map<Integer, BufferSpec> parseBuffers(
            PackSettingsPlan settings,
            List<String> deviations
    ) {
        Map<Integer, BufferSpec> result = new TreeMap<>();
        if (settings == null) {
            return result;
        }
        settings.propertyValues().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String key = entry.getKey();
                    if (key.startsWith("ssbo.") || key.startsWith("storage.")) {
                        deviations.add("STORAGE_BUFFER_UNSUPPORTED_PROPERTY:" + key);
                        deviations.add("STORAGE_BUFFER_DEFERRED:" + key);
                        return;
                    }
                    if (!key.startsWith("bufferObject.")) {
                        return;
                    }
                    String suffix = key.substring("bufferObject.".length());
                    int index;
                    try {
                        index = Integer.parseInt(suffix);
                    } catch (RuntimeException failure) {
                        deviations.add("STORAGE_BUFFER_INDEX_UNSUPPORTED:" + suffix);
                        return;
                    }
                    BufferSpec spec = parseBuffer(index, entry.getValue());
                    result.put(index, spec);
                    deviations.addAll(spec.deviations());
                });
        return result;
    }

    private static BufferSpec parseBuffer(int index, String raw) {
        List<String> deviations = new ArrayList<>();
        if (index < 0 || index > MAX_BUFFER_INDEX) {
            deviations.add("STORAGE_BUFFER_INDEX_UNSUPPORTED:" + index);
            return new BufferSpec(index, 0L, "", Status.UNSUPPORTED, deviations);
        }
        String[] values = raw == null ? new String[0] : raw.trim().split("\\s+");
        if (values.length == 0 || values.length > 2) {
            deviations.add("STORAGE_BUFFER_PROPERTY_MALFORMED:" + index);
            return new BufferSpec(index, 0L, "", Status.UNSUPPORTED, deviations);
        }
        long size;
        try {
            size = Long.parseLong(values[0]);
        } catch (RuntimeException failure) {
            deviations.add("STORAGE_BUFFER_SIZE_UNSUPPORTED:" + index);
            return new BufferSpec(index, 0L, values.length == 2 ? values[1] : "",
                    Status.UNSUPPORTED, deviations);
        }
        if (size <= 0L || size > MAX_STORAGE_BUFFER_BYTES) {
            deviations.add("STORAGE_BUFFER_SIZE_UNSUPPORTED:" + index);
        } else if ((size & 3L) != 0L) {
            deviations.add("STORAGE_BUFFER_ALIGNMENT_UNSUPPORTED:" + index);
        }
        if (raw != null && raw.toLowerCase(java.util.Locale.ROOT).contains("relative")) {
            deviations.add("STORAGE_BUFFER_RELATIVE_UNSUPPORTED:" + index);
        }
        return new BufferSpec(index, size, values.length == 2 ? values[1] : "",
                deviations.isEmpty() ? Status.SUPPORTED : Status.UNSUPPORTED, deviations);
    }

    private static Map<String, List<StorageBufferBinding>> scanStorageBuffers(
            PackPlan plan,
            Map<Integer, BufferSpec> specs,
            List<String> dependentPrograms,
            List<String> eligiblePrograms,
            List<String> deviations,
            Map<String, Integer> bindingBases
    ) {
        Map<String, List<StorageBufferBinding>> result = new TreeMap<>();
        if (plan == null) return result;
        for (PackProgramPlan program : plan.programs()) {
            if (program == null || program.name().isBlank()) continue;
            List<StorageBlock> blocks = new ArrayList<>();
            for (Map.Entry<String, PreparedShaderSource> stage : program.stages().entrySet()) {
                if (stage.getValue() == null || stage.getValue().source() == null) continue;
                int stageMask = stage.getKey().equals("vertex")
                        ? org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT
                        : stage.getKey().equals("fragment")
                        ? org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT : 0;
                if (stageMask == 0) continue;
                try {
                    for (StorageBlock block : scanStorageBlocks(stage.getValue().source())) {
                        blocks.add(new StorageBlock(block.blockName(), block.instanceName(),
                                block.originalBinding(), block.std430(), stageMask));
                    }
                } catch (RuntimeException failure) {
                    // Keep malformed storage declarations local to this
                    // program. They must not become an invented executable
                    // binding, and they must not reject unrelated programs.
                    String marker = "__parse_error__" + stage.getKey();
                    blocks.add(new StorageBlock(marker, "", -1, false, stageMask));
                    deviations.add("STORAGE_BUFFER_PARSE_UNSUPPORTED:" + program.name()
                            + ":" + stage.getKey());
                }
            }
            if (blocks.isEmpty()) continue;
            dependentPrograms.add(program.name());
            // A structurally valid block is a producer/consumer candidate even
            // while the first immutable plan is still waiting for its final
            // storage bindings. Final eligibility is decided after descriptor
            // construction and shader conversion.
            eligiblePrograms.add(program.name());
            Map<String, StorageBlock> merged = new TreeMap<>();
            Set<String> duplicateBlocks = new TreeSet<>();
            for (StorageBlock block : blocks) {
                String key = block.blockName() + "\\u0000" + block.instanceName();
                StorageBlock previous = merged.get(key);
                if (previous == null) {
                    merged.put(key, block);
                } else {
                    if ((previous.stageMask() & block.stageMask()) != 0) {
                        duplicateBlocks.add(key);
                    }
                    merged.put(key, new StorageBlock(previous.blockName(), previous.instanceName(),
                            previous.originalBinding(), previous.std430() && block.std430(),
                            previous.stageMask() | block.stageMask()));
                }
            }
            Map<Integer, Integer> logicalBindings = new TreeMap<>();
            List<StorageBufferBinding> bindings = new ArrayList<>();
            for (StorageBlock block : merged.values()) {
                String blockKey = block.blockName() + "\\u0000" + block.instanceName();
                List<BufferSpec> matches = matchingBufferSpecs(block, specs);
                BufferSpec spec = matches.size() == 1 ? matches.get(0) : null;
                List<String> local = new ArrayList<>();
                if (!block.std430()) {
                    local.add("STORAGE_BUFFER_LAYOUT_UNSUPPORTED:" + block.blockName());
                }
                if (duplicateBlocks.contains(blockKey)) {
                    local.add("STORAGE_BUFFER_BLOCK_DUPLICATE:" + program.name() + ":"
                            + block.blockName());
                }
                if (matches.size() > 1) {
                    local.add("STORAGE_BUFFER_BLOCK_AMBIGUOUS:" + program.name() + ":"
                            + block.blockName());
                } else if (spec == null) {
                    local.add("STORAGE_BUFFER_BLOCK_UNMAPPED:" + program.name() + ":"
                            + block.blockName());
                } else {
                    local.addAll(spec.deviations());
                }
                Status status = spec != null && spec.supported() && local.stream().noneMatch(
                        value -> value.startsWith("STORAGE_BUFFER_LAYOUT_UNSUPPORTED:")
                                || value.startsWith("STORAGE_BUFFER_BLOCK_UNMAPPED:")
                                || value.startsWith("STORAGE_BUFFER_BLOCK_AMBIGUOUS:")
                                || value.startsWith("STORAGE_BUFFER_BLOCK_DUPLICATE:")
                                || value.startsWith("STORAGE_BUFFER_SIZE_")
                                || value.startsWith("STORAGE_BUFFER_ALIGNMENT_")
                                || value.startsWith("STORAGE_BUFFER_RELATIVE_"))
                        ? Status.SUPPORTED : Status.UNSUPPORTED;
                int logicalIndex = spec == null ? -1 : spec.logicalIndex();
                long size = spec == null ? 0L : spec.size();
                int rewritten = status != Status.SUPPORTED ? -1 : logicalBindings.computeIfAbsent(
                        logicalIndex, ignored -> bindingBases.getOrDefault(program.name(), 0)
                                + logicalBindings.size());
                StorageBufferBinding binding = new StorageBufferBinding(
                        program.name(), block.blockName(), block.instanceName(),
                        block.originalBinding(), logicalIndex, rewritten, block.stageMask(),
                        size, status, local);
                bindings.add(binding);
                deviations.addAll(local);
                if (!binding.supported()) {
                    deviations.add("STORAGE_BUFFER_PROGRAM_FALLBACK:" + program.name());
                }
            }
            result.put(program.name(), bindings);
        }
        for (BufferSpec spec : specs.values()) {
            boolean used = result.values().stream().flatMap(List::stream)
                    .filter(value -> eligiblePrograms.contains(value.program()))
                    .anyMatch(value -> value.logicalIndex() == spec.logicalIndex());
            if (!used) {
                deviations.add("STORAGE_BUFFER_DEFERRED:bufferObject." + spec.logicalIndex());
            }
        }
        return result;
    }

    private static List<BufferSpec> matchingBufferSpecs(StorageBlock block,
                                                        Map<Integer, BufferSpec> specs) {
        List<BufferSpec> matches = specs.values().stream()
                .filter(spec -> !spec.requestedName().isBlank()
                        && (spec.requestedName().equals(block.blockName())
                        || spec.requestedName().equals(block.instanceName())))
                .toList();
        if (matches.isEmpty() && block.originalBinding() >= 0) {
            matches = specs.values().stream()
                    .filter(spec -> spec.requestedName().isBlank()
                            && spec.logicalIndex() == block.originalBinding())
                    .toList();
        }
        return matches;
    }

    private static Map<Integer, BufferSpec> applyStorageBudget(
            Map<Integer, BufferSpec> buffers,
            Map<String, List<StorageBufferBinding>> storageBuffers,
            List<String> eligiblePrograms,
            Map<String, ImageSpec> images,
            List<String> deviations
    ) {
        Map<Integer, BufferSpec> result = new TreeMap<>(buffers);
        long imageBytes = images.values().stream()
                .filter(ImageSpec::supported)
                .mapToLong(ImageSpec::estimatedBytes)
                .sum();
        long remaining = Math.max(0L, MAX_TOTAL_BYTES - imageBytes);
        TreeSet<Integer> used = new TreeSet<>();
        storageBuffers.entrySet().stream()
                .filter(entry -> eligiblePrograms.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .filter(StorageBufferBinding::supported)
                .map(StorageBufferBinding::logicalIndex)
                .forEach(used::add);
        boolean rejected = false;
        for (Integer index : used) {
            BufferSpec spec = result.get(index);
            if (spec == null || !spec.supported()) continue;
            if (spec.size() > remaining) {
                List<String> next = new ArrayList<>(spec.deviations());
                next.add("STORAGE_BUFFER_BUDGET_EXCEEDED:" + index);
                result.put(index, new BufferSpec(spec.logicalIndex(), spec.size(),
                        spec.requestedName(), Status.UNSUPPORTED, next));
                deviations.add("STORAGE_BUFFER_BUDGET_EXCEEDED:" + index);
                rejected = true;
            } else {
                remaining -= spec.size();
            }
        }
        if (rejected) deviations.add("STORAGE_BUFFER_BUDGET_EXCEEDED");
        return result;
    }

    private record StorageBlock(
            String blockName,
            String instanceName,
            int originalBinding,
            boolean std430,
            int stageMask
    ) {}

    private static int stageMask(String stage) {
        return switch (stage == null ? "" : stage) {
            case "vertex" -> org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
            case "fragment" -> org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
            default -> 0;
        };
    }

    private static List<StorageBlock> scanStorageBlocks(String source) {
        List<StorageBlock> result = new ArrayList<>();
        for (GlslStorageBufferParser.Block block : GlslStorageBufferParser.scan(source)) {
            result.add(new StorageBlock(block.blockName(), block.instanceName(),
                    block.originalBinding(), block.std430(), 0));
        }
        return result;
    }

    private static ImageSpec parseImage(
            String key,
            String raw,
            Map<String, String> defines
    ) {
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
            int width = resolveImageDimension(name, values[6], defines, deviations);
            int height = resolveImageDimension(name, values[7], defines, deviations);
            int depth = resolveImageDimension(name, values[8], defines, deviations);
            if (width < 1 || height < 1 || depth < 1) {
                deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
                return new ImageSpec(name, sampler, pixelFormat, internalFormat, pixelType,
                        clear, relative, width, height, depth, values[6], values[7], values[8],
                        0, Status.UNSUPPORTED, deviations);
            }
            long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), depth)
                    * bytesPerVoxel(internalFormat);
            if (width < 1 || height < 1 || depth < 1 || width > MAX_DIMENSION
                    || height > MAX_DIMENSION || depth > MAX_DIMENSION) {
                deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
                return new ImageSpec(name, sampler, pixelFormat, internalFormat, pixelType,
                        clear, relative, width, height, depth, values[6], values[7], values[8],
                        bytes, Status.UNSUPPORTED, deviations);
            }
            deviations.add("ADVANCED_IMAGE_DECLARED:" + name);
            return new ImageSpec(name, sampler, pixelFormat, internalFormat, pixelType,
                    clear, relative, width, height, depth, values[6], values[7], values[8],
                    bytes, Status.SUPPORTED, deviations);
        } catch (RuntimeException failure) {
            if (deviations.stream().noneMatch(value -> value.startsWith(
                    "ADVANCED_IMAGE_DIMENSION_UNRESOLVED:" + name + ":"))) {
                deviations.add("ADVANCED_IMAGE_UNSUPPORTED:" + name);
            }
            return new ImageSpec(name, sampler, pixelFormat, internalFormat, pixelType,
                    clear, relative, 0, 0, 0, values[6], values[7], values[8],
                    0, Status.UNSUPPORTED, deviations);
        }
    }

    private static int resolveImageDimension(
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

    private static Map<String, ComputeSpec> discoverCompute(
            Path shadersDir,
            String selectedFolder,
            Map<String, ImageSpec> images,
            List<String> deviations,
            Map<String, String> initialMacros,
            java.util.Set<String> lockedMacros,
            Set<String> graphicsProducedImages,
            Map<String, UniformRegistry.UniformDescriptor> customDescriptors
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
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(text);
            localDeviations.addAll(usage.deviations());
            if (!usage.successful()) {
                localDeviations.add("COMPUTE_RESOURCE_USAGE_UNCLASSIFIABLE");
            }
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
            names.addAll(usage.liveImages());
            TreeSet<String> samplers = new TreeSet<>();
            for (String sampler : usage.liveSamplers()) {
                if (images.values().stream().anyMatch(image -> image.sampler().equals(sampler))) {
                    samplers.add(sampler);
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
                if (UniformRegistry.resolve(name, valueUniform.group(1), customDescriptors) != null) {
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
                        spec.relative(), spec.width(), spec.height(), spec.depth(),
                        spec.widthToken(), spec.heightToken(), spec.depthToken(), spec.estimatedBytes(),
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

    private static boolean usesAdvancedResource(String source, Map<String, ImageSpec> images) {
        if (source == null) return false;
        Set<String> injectedSamplers = images == null ? Set.of() : images.values().stream()
                .map(ImageSpec::sampler)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(source, injectedSamplers);
        if (!usage.successful()) return true;
        if (!usage.liveImages().isEmpty()) return true;
        Matcher sampler3D = Pattern.compile("\\b(?:sampler3D|isampler3D|usampler3D)\\s+([A-Za-z_]\\w*)")
                .matcher(source);
        while (sampler3D.find()) {
            if (usage.liveSamplers().contains(sampler3D.group(1))) return true;
        }
        return usage.liveSamplers().stream().anyMatch(injectedSamplers::contains);
    }

    private static boolean usesImageWrite(String source, String symbol) {
        return GlslResourceUsage.analyze(source).liveImages().contains(symbol);
    }

    private static boolean usesSamplerCall(String source, String sampler) {
        return source != null && sampler != null
                && GlslResourceUsage.liveSamplers(source).contains(sampler);
    }

    private static int bytesPerVoxel(String format) {
        return switch (format) {
            case "r8ui" -> 1;
            case "r16ui" -> 2;
            case "rgba16f" -> 8;
            default -> throw new IllegalArgumentException("unsupported advanced image format: " + format);
        };
    }

    static String storageImageType(ImageSpec spec) {
        if (spec == null) return "image3D";
        return switch (spec.internalFormat().toLowerCase(java.util.Locale.ROOT)) {
            case "r8ui", "r16ui" -> "uimage3D";
            case "rgba16f" -> "image3D";
            default -> "image3D";
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
    public Map<String, List<GraphicsImageBinding>> graphicsImageReaders() {
        return graphicsImageReaders;
    }
    public List<GraphicsImageBinding> graphicsImageReaders(String program) {
        return graphicsImageReaders.getOrDefault(program, List.of());
    }
    /** Returns the union of writer and reader bindings for pipeline setup. */
    public List<GraphicsImageBinding> imageBindings(String program) {
        Map<String, GraphicsImageBinding> result = new TreeMap<>();
        for (GraphicsImageBinding binding : graphicsImageReaders(program)) {
            result.put(binding.symbol(), binding);
        }
        for (GraphicsImageBinding binding : graphicsImages(program)) {
            result.put(binding.symbol(), binding);
        }
        return List.copyOf(result.values());
    }
    /** Returns every logical image name that needs a successful graphics writer. */
    public Set<String> requiredGraphicsImageNames() {
        return graphicsImageNames(graphicsImages.keySet());
    }
    /** Returns logical image names covered by the selected producer programs. */
    public Set<String> graphicsImageNames(Collection<String> programs) {
        TreeSet<String> result = new TreeSet<>();
        if (programs != null) {
            for (String program : programs) {
                for (GraphicsImageBinding binding : graphicsImages(program)) {
                    result.add(binding.imageName());
                }
            }
        }
        return Set.copyOf(result);
    }
    public boolean requiresGraphicsProducer() { return !graphicsImages.isEmpty(); }
    public Map<Integer, BufferSpec> buffers() { return buffers; }
    public Map<String, List<StorageBufferBinding>> storageBuffers() { return storageBuffers; }
    public List<StorageBufferBinding> storageBuffers(String program) {
        return storageBuffers.getOrDefault(program, List.of());
    }
    public List<String> bufferDependentPrograms() { return bufferDependentPrograms; }
    public List<String> storageEligiblePrograms() { return storageEligiblePrograms; }
    public boolean hasStorageBuffers() {
        return storageBuffers.values().stream().anyMatch(value -> !value.isEmpty());
    }
    public boolean storageBufferCapabilityPossible() {
        if (bufferDependentPrograms.isEmpty()) return true;
        boolean hasBinding = storageBuffers.values().stream().flatMap(List::stream)
                .anyMatch(StorageBufferBinding::supported);
        return hasBinding
                && storageBuffers.entrySet().stream()
                .filter(entry -> bufferDependentPrograms.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .allMatch(StorageBufferBinding::supported)
                && buffers.values().stream()
                .filter(spec -> storageBuffers.values().stream().flatMap(List::stream)
                        .anyMatch(binding -> binding.logicalIndex() == spec.logicalIndex()))
                .allMatch(BufferSpec::supported);
    }
    public boolean storageBufferProgramSupported(String program) {
        return storageBuffers(program).stream().allMatch(StorageBufferBinding::supported);
    }

    static boolean containsStorageBlock(String source) {
        return GlslStorageBufferParser.hasBlock(source);
    }

    public ProgramBindingLayout bindingLayout(String program) {
        return bindingLayouts.getOrDefault(program,
                new ProgramBindingLayout(0, 0, 0, List.of(), List.of(), List.of(), List.of()));
    }

    OrdinaryDescriptorContract ordinaryContract(String program) {
        return ordinaryContracts.get(program);
    }
    /** Rewrites admitted storage blocks without changing their std430 members. */
    public String rewriteStorageBuffers(String program, String source) {
        if (source == null) return null;
        List<StorageBufferBinding> bindings = storageBuffers(program);
        if (bindings.isEmpty()) {
            return GlslStorageBufferParser.hasBlock(source) ? null : source;
        }
        Map<String, Integer> replacements = new TreeMap<>();
        for (StorageBufferBinding binding : bindings) {
            if (!binding.supported() || binding.rewrittenBinding() < 0) return null;
            for (GlslStorageBufferParser.Block block : GlslStorageBufferParser.scan(source)) {
                if (block.blockName().equals(binding.blockName())
                        && block.instanceName().equals(binding.instanceName())) {
                    DescriptorBinding descriptor = bindingLayout(program).descriptors().stream()
                            .filter(value -> value.type() == 9 && value.symbol().equals(binding.blockName()))
                            .findFirst().orElse(null);
                    if (descriptor == null) return null;
                    replacements.put(GlslStorageBufferParser.key(block), descriptor.binding());
                }
            }
        }
        return GlslStorageBufferParser.rewrite(source, replacements);
    }
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
        boolean imageCompute = images.isEmpty() && computeStages.isEmpty()
                || shadowCompute != null && shadowCompute.supported()
                && requiredImagesSupported(shadowCompute, images);
        // Storage-buffer eligibility is deliberately per program. An invalid
        // block in one family must not disable unrelated image or compute
        // programs that have a complete advanced-image plan.
        return imageCompute;
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
            item.addProperty("widthToken", value.widthToken());
            item.addProperty("heightToken", value.heightToken());
            item.addProperty("depthToken", value.depthToken());
            item.addProperty("estimatedBytes", value.estimatedBytes());
            item.addProperty("status", value.status().name());
            item.add("deviations", strings(value.deviations()));
            imageArray.add(item);
        });
        root.add("images", imageArray);
        JsonArray computeArray = new JsonArray();
        computeStages.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .forEach(value -> {
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
        graphicsImages.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
            String program = entry.getKey();
            List<GraphicsImageBinding> bindings = entry.getValue();
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
        if (!graphicsImageReaders.isEmpty()) {
            JsonArray readerArray = new JsonArray();
            graphicsImageReaders.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                JsonObject item = new JsonObject();
                item.addProperty("program", entry.getKey());
                JsonArray entries = new JsonArray();
                entry.getValue().forEach(binding -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("symbol", binding.symbol());
                    value.addProperty("image", binding.imageName());
                    value.addProperty("sampler", binding.sampler());
                    value.addProperty("type", binding.glslType());
                    value.addProperty("selectorSlot", binding.selectorSlot());
                    entries.add(value);
                });
                item.add("bindings", entries);
                readerArray.add(item);
            });
            root.add("graphicsImageReaders", readerArray);
        }
        JsonArray bufferArray = new JsonArray();
        buffers.values().stream().sorted(Comparator.comparingInt(BufferSpec::logicalIndex)).forEach(value -> {
            JsonObject item = new JsonObject();
            item.addProperty("index", value.logicalIndex());
            item.addProperty("size", value.size());
            item.addProperty("name", value.requestedName());
            item.addProperty("status", value.status().name());
            item.add("deviations", strings(value.deviations()));
            bufferArray.add(item);
        });
        root.add("buffers", bufferArray);
        JsonArray storageArray = new JsonArray();
        storageBuffers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
            String program = entry.getKey();
            List<StorageBufferBinding> bindings = entry.getValue();
            JsonObject item = new JsonObject();
            item.addProperty("program", program);
            JsonArray entries = new JsonArray();
            bindings.forEach(binding -> {
                JsonObject value = new JsonObject();
                value.addProperty("block", binding.blockName());
                value.addProperty("instance", binding.instanceName());
                value.addProperty("originalBinding", binding.originalBinding());
                value.addProperty("index", binding.logicalIndex());
                value.addProperty("binding", binding.rewrittenBinding());
                value.addProperty("stages", binding.stageMask());
                value.addProperty("size", binding.size());
                value.addProperty("status", binding.status().name());
                value.add("deviations", strings(binding.deviations()));
                entries.add(value);
            });
            item.add("bindings", entries);
            storageArray.add(item);
        });
        root.add("storageBuffers", storageArray);
        root.add("dependentPrograms", strings(dependentPrograms));
        root.add("bufferDependentPrograms", strings(bufferDependentPrograms));
        root.add("storageEligiblePrograms", strings(storageEligiblePrograms));
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
