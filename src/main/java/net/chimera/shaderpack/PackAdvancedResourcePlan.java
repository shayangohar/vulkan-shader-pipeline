package net.chimera.shaderpack;

import com.google.gson.JsonObject;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Immutable load-time plan for the first advanced-resource contract.
 *
 * <p>This is deliberately separate from {@link PackResourcePlan}. That plan
 * owns sampled two-dimensional textures. This plan describes writable image
 * declarations and the exact compute stages that may use them.</p>
 */
public final class PackAdvancedResourcePlan {
    public static final long MAX_TOTAL_BYTES = 768L * 1024L * 1024L;
    public static final int MAX_BUFFER_INDEX = 12;
    public static final long MAX_STORAGE_BUFFER_BYTES = 64L * 1024L * 1024L;

    static final Pattern LOCAL_SIZE = Pattern.compile(
            "layout\\s*\\(\\s*local_size_x\\s*=\\s*(\\d+)\\s*,\\s*"
                    + "local_size_y\\s*=\\s*(\\d+)\\s*,\\s*"
                    + "local_size_z\\s*=\\s*(\\d+)\\s*\\)", Pattern.MULTILINE);
    static final Pattern VALUE_UNIFORM_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+"
                    + "(bool|int|uint|float|double|vec[234]|ivec[234]|uvec[234]|bvec[234]"
                    + "|mat[234](?:x[234])?)\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern IMAGE_TYPE = Pattern.compile(
            "[iu]?image(?:1D|2D|3D|Cube|2DRect|1DArray|2DArray|CubeArray|Buffer|2DMS|2DMSArray)");

    public enum Status { SUPPORTED, UNSUPPORTED, UNAVAILABLE }

    /** A declaration Chimera admitted or refused. */
    public interface Admission {
        Status status();

        default boolean supported() { return status() == Status.SUPPORTED; }
    }

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
    ) implements Admission {
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

        /** The internal format as Iris names it (InternalTextureFormat); null for one Iris does not know. */
        public IrisColorFormats.Format format() {
            return IrisColorFormats.of(internalFormat);
        }

        /** The GLSL image format qualifier of the format the texels are stored in ({@code rgba8}, {@code r32ui}). */
        public String layoutQualifier() {
            return IrisColorFormats.stored(format().vkFormat()).token().toLowerCase(Locale.ROOT);
        }

        /** The prefix of the image's GLSL types: {@code u} or {@code i} for an integer format, none for a float one. */
        public String typePrefix() {
            IrisColorFormats.Kind kind = format().kind();
            return kind == IrisColorFormats.Kind.UINT ? "u" : kind == IrisColorFormats.Kind.SINT ? "i" : "";
        }

        /**
         * The image's dimensions, as Iris's ShaderProperties reads its declaration: a relative image
         * is 2D, and a fixed one has as many as the sizes it gives.
         */
        public int dimensions() {
            if (relative) return 2;
            return depth > 0 ? 3 : height > 0 ? 2 : 1;
        }

        /** The GLSL type of the image's sampler: {@code usampler2D}, {@code sampler3D} and so on. */
        public String samplerType() {
            return typePrefix() + "sampler" + dimensionSuffix();
        }

        /** The name the image's selector slot is kept under (AdvancedImageDeclarations.slotKey). */
        public String slotKey() {
            return sampler.isEmpty() ? name : sampler;
        }

        /** The suffix of the image's GLSL types: {@code 1D}, {@code 2D} or {@code 3D}. */
        public String dimensionSuffix() {
            return dimensions() + "D";
        }

        /**
         * The image's width, height and depth on a screen of this size: a relative image's share
         * of it (GlImage.Relative truncates), else its declared size.
         */
        public int[] extent(int screenWidth, int screenHeight) {
            if (!relative) return new int[] {width, Math.max(1, height), Math.max(1, depth)};
            return new int[] {Math.max(1, (int) (screenWidth * Float.parseFloat(widthToken))),
                    Math.max(1, (int) (screenHeight * Float.parseFloat(heightToken))), 1};
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
    ) implements Admission {
        public ComputeSpec {
            program = program == null ? "" : program;
            relativeSource = relativeSource == null ? "" : relativeSource.replace('\\', '/');
            imageNames = imageNames == null ? List.of() : imageNames.stream().distinct().sorted().toList();
            samplerNames = samplerNames == null ? List.of() : samplerNames.stream().distinct().sorted().toList();
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }
    }

    /**
     * One storage image used by a graphics-stage imageStore declaration. {@code viewFormat} is the
     * format qualifier the stage reads the image through when that is not the image's own (Alpha
     * Piscium's r32i over an R32F image), else empty.
     */
    public record GraphicsImageBinding(
            String program,
            String symbol,
            String imageName,
            String sampler,
            String glslType,
            String viewFormat,
            int selectorSlot,
            int stageMask
    ) {
        public GraphicsImageBinding {
            program = program == null ? "" : program;
            symbol = symbol == null ? "" : symbol;
            imageName = imageName == null ? "" : imageName;
            sampler = sampler == null ? "" : sampler;
            glslType = glslType == null ? "image3D" : glslType;
            viewFormat = viewFormat == null ? "" : viewFormat;
            stageMask = stageMask == 0
                    ? VK10.VK_SHADER_STAGE_ALL_GRAPHICS : stageMask;
        }

        /** The Vulkan format of the view this binding reads its image through; 0 for the image's own. */
        public int viewVkFormat() {
            return viewFormat.isEmpty() ? 0 : IrisColorFormats.of(viewFormat).vkFormat();
        }
    }

    /**
     * One validated shaders.properties bufferObject.N declaration. A relative one (Iris's
     * {@code size true scaleX scaleY}) holds {@code size} bytes per covered screen pixel.
     */
    public record BufferSpec(
            int logicalIndex,
            long size,
            String requestedName,
            boolean relative,
            float scaleX,
            float scaleY,
            Status status,
            List<String> deviations
    ) implements Admission {
        public BufferSpec {
            requestedName = requestedName == null ? "" : requestedName.trim();
            status = status == null ? Status.UNAVAILABLE : status;
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }

        public BufferSpec(int logicalIndex, long size, String requestedName, Status status, List<String> deviations) {
            this(logicalIndex, size, requestedName, false, 0f, 0f, status, deviations);
        }

        /**
         * The buffer's bytes on a screen of this size: a relative buffer's size for each pixel of
         * its truncated share (ShaderStorageBuffer.resizeIfRelative), at least one per side.
         */
        public long bytes(int screenWidth, int screenHeight) {
            if (!relative) return size;
            return Math.max(1L, (long) (screenWidth * scaleX)) * Math.max(1L, (long) (screenHeight * scaleY)) * size;
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
    ) implements Admission {
        public StorageBufferBinding {
            program = program == null ? "" : program;
            blockName = blockName == null ? "" : blockName;
            instanceName = instanceName == null ? "" : instanceName;
            status = status == null ? Status.UNAVAILABLE : status;
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }
    }

    /** A descriptor slot, independent of Vulkan object allocation. */
    public record DescriptorBinding(
            String symbol, int set, int binding, int type, int stages, String identity
    ) {
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
            Function<GraphicsImageBinding, String> keyFunction
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
                            previous.sampler(), previous.glslType(), previous.viewFormat(), previous.selectorSlot(),
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
            DescriptorBinding binding = uniformBlockAt(tokens, i)
                    ? uniformBlockBinding(tokens, i, stage) : opaqueBinding(tokens, i, stage);
            if (binding != null) result.add(binding);
        }
        return List.copyOf(result);
    }

    /** Whether the token at index i opens a uniform block: uniform, the block name, then an opening brace. */
    private static boolean uniformBlockAt(List<GlslLexer.Token> tokens, int i) {
        return tokens.get(i).text().equals("uniform") && i + 2 < tokens.size()
                && tokens.get(i + 1).kind() == GlslLexer.Kind.IDENTIFIER
                && tokens.get(i + 2).text().equals("{");
    }

    /** The uniform block opened at index i, or null when it has no explicit binding. */
    private static DescriptorBinding uniformBlockBinding(List<GlslLexer.Token> tokens, int i, int stage) {
        int start = declarationStart(tokens, i);
        int set = descriptorInteger(tokens, start, i, "set", 0);
        int binding = descriptorInteger(tokens, start, i, "binding", -1);
        if (binding < 0) return null;
        return new DescriptorBinding(tokens.get(i + 1).text(), set, binding, 8, stage, "ubo:" + binding);
    }

    /** The storage block, storage image or sampler the type at index i declares, or null for none. */
    private static DescriptorBinding opaqueBinding(List<GlslLexer.Token> tokens, int i, int stage) {
        String word = tokens.get(i).text();
        int type = word.equals("buffer") ? 9
                : word.matches("[ui]?image(?:1D|2D|3D)") ? 3
                : word.matches("(?:[ui]?sampler(?:1D|2D|3D)|sampler2DShadow)") ? 1 : -1;
        if (type < 0 || i + 1 >= tokens.size()) return null;
        if (tokens.get(i + 1).kind() != GlslLexer.Kind.IDENTIFIER) return null;
        String symbol = tokens.get(i + 1).text();
        int start = declarationStart(tokens, i);
        if (type != 9 && !declaresUniform(tokens, start, i)) return null;
        int set = descriptorInteger(tokens, start, i, "set", 0);
        int binding = descriptorInteger(tokens, start, i, "binding", -1);
        return new DescriptorBinding(symbol, set, binding, type, stage, symbol);
    }

    /** The first token of the declaration that reaches index end: just past the previous ;, { or }. */
    private static int declarationStart(List<GlslLexer.Token> tokens, int end) {
        int start = end - 1;
        while (start >= 0 && !Set.of(";", "{", "}").contains(tokens.get(start).text())) start--;
        return start + 1;
    }

    private static boolean declaresUniform(List<GlslLexer.Token> tokens, int start, int end) {
        for (int j = start; j < end; j++) {
            if (tokens.get(j).identifier("uniform")) return true;
        }
        return false;
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
        String duplicate = duplicateDescriptor(descriptors);
        if (duplicate != null) return duplicate;
        for (DescriptorBinding shader : declared) {
            if (!uniquelyServed(layout.descriptors(), shader) || !boundAtSlot(descriptors, shader)) {
                return "DESCRIPTOR_LAYOUT_MISMATCH:" + shader.symbol();
            }
        }
        for (DescriptorBinding expected : layout.descriptors()) {
            if (!uniquelyServed(descriptors, expected)) {
                return "DESCRIPTOR_LAYOUT_MISMATCH:" + expected.symbol();
            }
        }
        for (DescriptorBinding actual : descriptors) {
            boolean planned = layout.descriptors().stream().anyMatch(expected -> sameDescriptor(expected, actual));
            if (!planned) {
                return "DESCRIPTOR_LAYOUT_EXTRA:" + actual.binding();
            }
        }
        return null;
    }

    /** The first resource identity or set and binding that two native descriptors share, or null. */
    private static String duplicateDescriptor(List<DescriptorBinding> descriptors) {
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
        return null;
    }

    /** Whether exactly one candidate has the wanted set, binding and type, visible to all its stages. */
    private static boolean uniquelyServed(List<DescriptorBinding> candidates, DescriptorBinding wanted) {
        List<DescriptorBinding> matches = candidates.stream().filter(value -> sameDescriptor(value, wanted)).toList();
        return matches.size() == 1 && coversStages(matches.get(0), wanted);
    }

    /** Whether exactly one native descriptor sits at the declaration's set and binding, with its type and stages. */
    private static boolean boundAtSlot(List<DescriptorBinding> descriptors, DescriptorBinding shader) {
        List<DescriptorBinding> matches = descriptors.stream().filter(value ->
                value.set() == shader.set() && value.binding() == shader.binding()).toList();
        return matches.size() == 1 && matches.get(0).type() == shader.type() && coversStages(matches.get(0), shader);
    }

    private static boolean sameDescriptor(DescriptorBinding a, DescriptorBinding b) {
        return a.set() == b.set() && a.binding() == b.binding() && a.type() == b.type();
    }

    private static boolean coversStages(DescriptorBinding binding, DescriptorBinding wanted) {
        return (binding.stages() & wanted.stages()) == wanted.stages();
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
        this.dependentPrograms = PlanJson.sorted(dependentPrograms);
        this.bufferDependentPrograms = PlanJson.sorted(bufferDependentPrograms);
        this.storageEligiblePrograms = PlanJson.sorted(storageEligiblePrograms);
        this.customImageFeaturePrograms = PlanJson.sorted(customImageFeaturePrograms);
        this.deviations = PlanJson.sorted(deviations);
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
        Map<Integer, BufferSpec> buffers = StorageBufferDeclarations.parseBuffers(settings, new ArrayList<>());
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
        if (shadersDir == null || settings != null && !settings.enabled(selectedFolder, "shadowcomp")) return Set.of();
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
        List<String> deviations = new ArrayList<>();
        Map<String, ImageSpec> images = AdvancedImageDeclarations.parseImages(plan.settings(), deviations);
        Map<Integer, BufferSpec> buffers = StorageBufferDeclarations.parseBuffers(plan.settings(), deviations);

        boolean customImages = images.values().stream().anyMatch(ImageSpec::supported);
        Map<String, String> computeMacros = PackEngineDefines.forCompute(
                plan.settings().preprocessorDefines(), customImages);
        // Iris exposes this macro when the runtime supports custom images.
        // The bounded Chimera bridge supports the admitted image subset, so
        // compute preparation must see the same feature or guarded sampler
        // declarations will be removed while their uses remain active.
        GraphicsImageScan graphics = GraphicsImageScan.scan(plan, images, deviations);
        // A shadowcomp the pack switches off is no source to Iris (ShaderPack's source provider).
        String folder = plan.resolution().selectedSourceFolder();
        Map<String, ComputeSpec> compute = !plan.settings().enabled(folder, "shadowcomp") ? Map.of()
                : AdvancedImageDeclarations.discoverCompute(shadersDir, folder, images, deviations, computeMacros,
                        PackEngineDefines.lockedNames(plan.settings().overriddenNames(), customImages, true),
                        graphics.producedImages, plan.settings().runtimeSettings().customDescriptors());
        addCapabilityDeviations(images, graphics.customImageFeaturePrograms, deviations);
        List<String> bufferDependent = new ArrayList<>();
        List<String> storageEligible = new ArrayList<>();
        Map<String, OrdinaryDescriptorContract> ordinaryContracts =
                contractsByProgram(plan, shadersDir, graphics.readers);
        Map<String, Integer> bindingBases = new TreeMap<>();
        ordinaryContracts.forEach((program, contract) -> bindingBases.put(program, contract.nextBinding()));
        Map<String, List<StorageBufferBinding>> storageBuffers = StorageBufferDeclarations.scanStorageBuffers(
                plan, buffers, bufferDependent, storageEligible, deviations, bindingBases);
        Map<Integer, BufferSpec> budgetedBuffers = StorageBufferDeclarations.applyStorageBudget(
                buffers, storageBuffers, storageEligible, images, deviations);
        if (!budgetedBuffers.equals(buffers)) {
            buffers = budgetedBuffers;
            bufferDependent.clear();
            storageEligible.clear();
            storageBuffers = StorageBufferDeclarations.scanStorageBuffers(
                    plan, buffers, bufferDependent, storageEligible, deviations, bindingBases);
        }
        addUnplannedDeclarations(plan, graphics.dependent, bufferDependent, deviations);
        return new PackAdvancedResourcePlan(images, compute, graphics.writers, graphics.readers, buffers,
                storageBuffers, graphics.dependent, bufferDependent, storageEligible,
                graphics.customImageFeaturePrograms, deviations, ordinaryContracts, bindingBases);
    }

    /** Whether the pack is offered custom images, and the feature-guarded programs that fall back without. */
    private static void addCapabilityDeviations(Map<String, ImageSpec> images,
                                                List<String> customImageFeaturePrograms, List<String> deviations) {
        if (images.isEmpty()) return;
        if (images.values().stream().anyMatch(ImageSpec::supported)) {
            deviations.add("CUSTOM_IMAGES_CAPABILITY_POSSIBLE");
            return;
        }
        deviations.add("IRIS_FEATURE_CUSTOM_IMAGES_DISABLED");
        for (String program : customImageFeaturePrograms) {
            deviations.add("CUSTOM_IMAGE_FEATURE_FALLBACK:" + program);
        }
    }

    /** Each program's ordinary descriptors and sampled resources, leaving out the image samplers it reads. */
    private static Map<String, OrdinaryDescriptorContract> contractsByProgram(
            PackPlan plan, Path shadersDir, Map<String, List<GraphicsImageBinding>> graphicsImageReaders) {
        Map<String, Set<String>> advancedSamplerNames = new TreeMap<>();
        graphicsImageReaders.forEach((program, bindings) -> advancedSamplerNames.put(program,
                bindings.stream().map(GraphicsImageBinding::sampler)
                        .filter(value -> value != null && !value.isBlank())
                        .collect(Collectors.toCollection(TreeSet::new))));
        Map<String, OrdinaryDescriptorContract> ordinaryContracts = new TreeMap<>();
        PackResourcePlan sampledResources = PackResourcePlan.build(plan, shadersDir);
        for (PackProgramPlan program : plan.programs()) {
            var contract = PackDescriptorContracts.ordinaryDescriptorContract(program,
                    advancedSamplerNames.getOrDefault(program.name(), Set.of()))
                    .withResources(sampledResources.bindings(program.name()));
            ordinaryContracts.put(program.name(), contract);
        }
        return ordinaryContracts;
    }

    /** The image whose sampler has this name, or null. */
    static ImageSpec imageBySampler(Map<String, ImageSpec> images, String sampler) {
        return images.values().stream()
                .filter(value -> value.sampler().equals(sampler))
                .findFirst().orElse(null);
    }

    /** The image a shader symbol names: the image itself, or the sampler that reads it. */
    static ImageSpec imageBySymbol(Map<String, ImageSpec> images, String symbol) {
        ImageSpec direct = images.get(symbol);
        return direct != null ? direct : imageBySampler(images, symbol);
    }

    public Map<String, ImageSpec> images() { return images; }

    /** The image a shader symbol names: the image itself, or the sampler that reads it. */
    public ImageSpec imageForSymbol(String symbol) {
        return imageBySymbol(images, symbol);
    }

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
    /** The Vulkan formats programs read {@code image} through besides its own; any one needs a mutable image. */
    public Set<Integer> viewFormats(String image) {
        return graphicsImages.values().stream().flatMap(List::stream)
                .filter(binding -> binding.imageName().equals(image) && !binding.viewFormat().isEmpty())
                .map(GraphicsImageBinding::viewVkFormat)
                .collect(Collectors.toCollection(TreeSet::new));
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
    public Map<Integer, BufferSpec> buffers() { return buffers; }
    public Map<String, List<StorageBufferBinding>> storageBuffers() { return storageBuffers; }
    public List<StorageBufferBinding> storageBuffers(String program) {
        return storageBuffers.getOrDefault(program, List.of());
    }
    public List<String> bufferDependentPrograms() { return bufferDependentPrograms; }
    public List<String> storageEligiblePrograms() { return storageEligiblePrograms; }

    /** The bufferObject indexes programs bind, which the session allocates. */
    public Set<Integer> programBuffers() {
        return StorageBufferDeclarations.programBuffers(storageBuffers, storageEligiblePrograms);
    }

    /**
     * What the session budget leaves for the buffers only pass computes bind, once the supported
     * images and the fixed-size buffers programs bind are charged (applyStorageBudget). A relative
     * buffer follows the screen, and only the device's range bounds it.
     */
    public long computeBufferBudget() {
        long used = StorageBufferDeclarations.imageBytes(images);
        for (Integer index : programBuffers()) {
            BufferSpec spec = buffers.get(index);
            if (spec != null && spec.supported() && !spec.relative()) used += spec.size();
        }
        return Math.max(0L, MAX_TOTAL_BYTES - used);
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

    private static final String UNPLANNED_DECLARATION = "ADVANCED_DECLARATION_UNPLANNED:";
    /** A program reading or writing a pack image Chimera does not create or cannot bind. */
    static final String UNSERVED_IMAGE = "ADVANCED_IMAGE_UNSERVED:";

    /**
     * Whether the program declares an image or storage block that no binding here serves, as the
     * report reads it (ADVANCED_RESOURCE_UNSUPPORTED). Rethinking Voxels' prepare stores to
     * {@code colorimg9}, which Chimera binds only for pass computes.
     */
    public boolean declaresUnplannedResource(String program) {
        return deviations.contains(UNPLANNED_DECLARATION + program);
    }

    /** Records each program that declares an advanced resource but depends on none this plan serves. */
    private static void addUnplannedDeclarations(PackPlan plan, List<String> imagePrograms,
                                                 List<String> bufferPrograms, List<String> deviations) {
        for (PackProgramPlan program : plan.programs()) {
            if (program == null || imagePrograms.contains(program.name())
                    || bufferPrograms.contains(program.name())) continue;
            if (program.stages().values().stream().anyMatch(stage -> stage != null && stage.source() != null
                    && containsAdvancedDeclarations(stage.source()))) {
                deviations.add(UNPLANNED_DECLARATION + program.name());
            }
        }
    }

    static boolean containsStorageBlock(String source) {
        return GlslStorageBufferParser.hasBlock(source);
    }

    /**
     * Inventory real storage blocks, and images of real opaque types that the stage references, not
     * image-prefixed identifiers. An image the stage only declares is inactive, as GL leaves it,
     * and stage preparation drops its declaration (ProgramStageSources.withoutInactiveImages).
     */
    static boolean containsAdvancedDeclarations(String source) {
        if (containsStorageBlock(source)) return true;
        List<GlslLexer.Token> tokens = GlslLexer.lex(source);
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER || !IMAGE_TYPE.matcher(token.text()).matches()) continue;
            int name = GlslLexer.nextSignificant(tokens, index);
            if (name < 0 || tokens.get(name).kind() != GlslLexer.Kind.IDENTIFIER
                    || GlslTokenRewriter.identifierCount(source, tokens.get(name).text()) > 1) {
                return true;
            }
        }
        return false;
    }

    public ProgramBindingLayout bindingLayout(String program) {
        return bindingLayouts.getOrDefault(program,
                new ProgramBindingLayout(0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of()));
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
    public boolean hasSupportedImages() { return !images.isEmpty()
            && images.values().stream().allMatch(ImageSpec::supported); }
    public boolean hasSupportedCompute() {
        ComputeSpec shadowCompute = computeStages.get("shadowcomp");
        return shadowCompute != null && shadowCompute.supported()
                && requiredImagesSupported(shadowCompute, images);
    }
    /**
     * Whether a program that uses pack images can run: every image it uses is one Chimera creates
     * and binds. Iris creates the images whatever programs exist, shadowcomp or none, and runs each
     * program on its own (GraphicsImageScan records an image it cannot bind).
     */
    public boolean imagesServable(String program) {
        return unservedImages(program).isEmpty();
    }

    /** The pack images the program reads that Chimera does not create or cannot bind. */
    public List<String> unservedImages(String program) {
        String prefix = UNSERVED_IMAGE + program + ":";
        return deviations.stream().filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length())).toList();
    }

    /**
     * Whether Chimera offers the pack Iris's custom images (IRIS_FEATURE_CUSTOM_IMAGES): it creates
     * at least one image the pack declares. A program using an image it does not create falls back
     * on its own (imagesServable).
     */
    public boolean capabilityPossible() {
        return images.values().stream().anyMatch(ImageSpec::supported);
    }
    public String snapshot() { return snapshot; }
    public String fingerprint() { return fingerprint; }

    private String snapshotJson() {
        JsonObject root = new JsonObject();
        root.add("images", AdvancedResourceJson.images(images));
        root.add("computeStages", AdvancedResourceJson.computeStages(computeStages));
        root.add("graphicsImages",
                AdvancedResourceJson.programBindings(graphicsImages, AdvancedResourceJson::graphicsBinding));
        if (!graphicsImageReaders.isEmpty()) {
            root.add("graphicsImageReaders",
                    AdvancedResourceJson.programBindings(graphicsImageReaders, AdvancedResourceJson::graphicsBinding));
        }
        root.add("buffers", AdvancedResourceJson.buffers(buffers));
        root.add("storageBuffers",
                AdvancedResourceJson.programBindings(storageBuffers, AdvancedResourceJson::storageBinding));
        root.add("dependentPrograms", PlanJson.strings(dependentPrograms));
        root.add("bufferDependentPrograms", PlanJson.strings(bufferDependentPrograms));
        root.add("storageEligiblePrograms", PlanJson.strings(storageEligiblePrograms));
        root.add("customImageFeaturePrograms", PlanJson.strings(customImageFeaturePrograms));
        root.add("deviations", PlanJson.strings(deviations));
        root.addProperty("capabilityPossible", capabilityPossible());
        return root.toString();
    }

    private static boolean requiredImagesSupported(ComputeSpec compute, Map<String, ImageSpec> images) {
        if (compute == null || !compute.supported() || compute.imageNames().isEmpty()) return false;
        for (String name : compute.imageNames()) {
            ImageSpec image = imageBySymbol(images, name);
            if (image == null || !image.supported()) return false;
        }
        for (String name : compute.samplerNames()) {
            ImageSpec image = imageBySampler(images, name);
            if (image == null || !image.supported()) return false;
        }
        return true;
    }
}
