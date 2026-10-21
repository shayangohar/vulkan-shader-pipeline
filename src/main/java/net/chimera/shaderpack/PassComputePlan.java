package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
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
import java.util.stream.Stream;

/**
 * Iris's pass computes (ProgramSet.readComputeArray, CompositeRenderer): {@code <pass>.csh}, then
 * {@code <pass>_a.csh} to {@code _z.csh} up to the first missing or disabled one, in the selected
 * folder. They run right before their prepare, deferred or composite pass draws. {@code colorimgN}
 * and {@code colortexN} both bind the image that pass reads, and a dispatch covers
 * {@code workGroups}, the {@code workGroupsRender} share of the screen, or the screen over the
 * local size. A compute Chimera cannot bind keeps its reasons and does not run.
 */
public final class PassComputePlan {
    private static final Pattern SOURCE_NAME = Pattern.compile(
            "((?:prepare|deferred|composite)(?:[1-9]\\d?)?)(?:_[a-z])?\\.csh");
    private static final Pattern LOCAL_SIZE = Pattern.compile(
            "layout\\s*\\(([^)]*\\blocal_size_[xyz][^)]*)\\)\\s*in\\s*;");
    private static final Pattern LOCAL_SIZE_AXIS = Pattern.compile("local_size_([xyz])\\s*=\\s*(\\d+)");
    private static final Pattern WORK_GROUPS = Pattern.compile("\\bconst\\s+ivec3\\s+workGroups\\s*=\\s*"
            + "ivec3\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern WORK_GROUPS_RENDER = Pattern.compile("\\bconst\\s+vec2\\s+workGroupsRender"
            + "\\s*=\\s*vec2\\s*\\(\\s*([-+0-9.eE]+)\\s*,\\s*([-+0-9.eE]+)\\s*\\)");
    private static final Pattern IMAGE_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?"
                    + "((?:(?:uniform|writeonly|readonly|coherent|volatile|restrict)\\s+)*)"
                    + "(\\w*image\\w+)\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern SAMPLER_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(\\w*sampler\\w+)\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern OTHER_UNIFORM = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+\\w+\\s+([A-Za-z_]\\w*)\\s*(?:\\[[^\\]]*\\])?\\s*;");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^[ \\t]*#extension\\s+(\\w+)[^\\r\\n]*(?:\\r?\\n|$)");
    private static final Pattern COLOR_IMAGE = Pattern.compile("colorimg([0-9]+)");
    /** The pack textures a compute samples by resource key. */
    private static final Set<PackResourceKind> TEXTURE_KINDS = EnumSet.of(PackResourceKind.NOISE,
            PackResourceKind.PACK_TEXTURE, PackResourceKind.RAW_TEXTURE);
    /** The value block a pass compute reads; the pack compile check finds its offsets by this name. */
    public static final String VALUES_BLOCK = "ChimeraPassComputeValues";

    /** What one descriptor binds: a colour target, a pack texture, or a pack image. */
    public enum Kind {
        /** {@code colorimgN}: target N as a storage image, in its format. */
        TARGET_IMAGE,
        /** {@code colortexN}: target N sampled. */
        TARGET_SAMPLER,
        /** A pack texture: a declared file or raw texture, a game resource, or the generated noise. */
        TEXTURE,
        /** {@code depthtexN}: the depth snapshot the pass's program reads under that name. */
        DEPTH,
        /** A pack image's own storage name ({@code image.<name>}), in its format. */
        PACK_IMAGE,
        /** A pack image's sampler, reading the image it names. */
        PACK_IMAGE_SAMPLER,
        /** A storage block: the pack's {@code bufferObject.<target>}. */
        STORAGE_BUFFER
    }

    /**
     * One descriptor: its symbol and binding, what it binds, the target or bufferObject index (-1
     * for none), a storage image's format, and the pack image's name or the pack texture's key.
     */
    public record Binding(String symbol, int binding, Kind kind, int target, String format, String resource) {
        /** Whether the compute writes through it, as a storage image. */
        public boolean storage() {
            return kind == Kind.TARGET_IMAGE || kind == Kind.PACK_IMAGE;
        }
    }

    /**
     * One pass compute: its translated source (null when it cannot run), what dispatch binds, and
     * the pack textures its {@link Kind#TEXTURE} bindings read.
     */
    public record PassCompute(String name, String pass, String source, int[] localSize, int[] workGroups,
                              float[] workGroupsRender, List<Binding> bindings, List<PackResourceBinding> textures,
                              List<ComputeValueBlock.Value> values, List<String> deviations) {
        public boolean supported() { return source != null; }

        /** Iris ComputeProgram.getWorkGroups for a screen of this size. */
        public int[] groups(int width, int height) {
            if (workGroups != null) return workGroups.clone();
            float scaleX = workGroupsRender == null ? 1.0F : workGroupsRender[0];
            float scaleY = workGroupsRender == null ? 1.0F : workGroupsRender[1];
            return new int[] {(int) Math.ceil(Math.ceil(width * scaleX) / localSize[0]),
                    (int) Math.ceil(Math.ceil(height * scaleY) / localSize[1]), 1};
        }
    }

    private final List<PassCompute> computes;

    private PassComputePlan(List<PassCompute> computes) {
        this.computes = List.copyOf(computes);
    }

    public static PassComputePlan empty() { return new PassComputePlan(List.of()); }

    public static PassComputePlan build(PackPlan plan, Path shadersDir) {
        if (plan == null || shadersDir == null) return empty();
        String folder = plan.selectedSourceFolder();
        Path root = folder == null || folder.isBlank() ? shadersDir : shadersDir.resolve(folder).normalize();
        if (!root.startsWith(shadersDir.normalize()) || !Files.isDirectory(root)) return empty();
        Predicate<String> enabled = name -> plan.settings().enabled(folder, name);
        Translator translator = new Translator(plan, shadersDir);
        List<PassCompute> result = new ArrayList<>();
        for (String pass : passes(root)) {
            for (String name : sourceNames(root, pass, enabled)) {
                result.add(translator.translate(name, pass, root.resolve(name + ".csh")));
            }
        }
        return new PassComputePlan(result);
    }

    public List<PassCompute> computes() { return computes; }

    /** Whether {@code <program>.csh} is a pass compute: one this plan runs before the pass, not a stage of it. */
    static boolean isPassComputeProgram(String program) {
        return program != null && SOURCE_NAME.matcher(program + ".csh").matches();
    }

    /** The computes that run before this pass, in Iris's order. */
    public List<PassCompute> before(String pass) {
        return computes.stream().filter(value -> value.supported() && value.pass().equals(pass)).toList();
    }

    /** The compute translated from {@code <name>.csh}, or null. */
    public PassCompute compute(String name) {
        return computes.stream().filter(value -> value.name().equals(name)).findFirst().orElse(null);
    }

    /** The targets a compute writes through {@code colorimgN}, which need storage usage. */
    public Set<Integer> storageTargets() {
        return targets(Kind.TARGET_IMAGE);
    }

    /** The bufferObject indexes a compute binds, which the session must allocate. */
    public Set<Integer> storageBuffers() {
        return targets(Kind.STORAGE_BUFFER);
    }

    private Set<Integer> targets(Kind kind) {
        return computes.stream().filter(PassCompute::supported)
                .flatMap(value -> value.bindings().stream())
                .filter(binding -> binding.kind() == kind)
                .map(Binding::target)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The passes any compute source in the folder names, in pass order. */
    private static List<String> passes(Path root) {
        try (Stream<Path> files = Files.list(root)) {
            return files.map(file -> SOURCE_NAME.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> matcher.group(1))
                    .distinct()
                    .sorted(PostTargetPlan.programComparator())
                    .toList();
        } catch (IOException failure) {
            return List.of();
        }
    }

    /** Iris reads {@code <pass>.csh}, then the suffixes from {@code _a} until one is missing or disabled. */
    static List<String> sourceNames(Path root, String pass, Predicate<String> enabled) {
        List<String> names = new ArrayList<>();
        if (present(root, pass, enabled)) names.add(pass);
        for (char suffix = 'a'; suffix <= 'z'; suffix++) {
            String name = pass + "_" + suffix;
            if (!present(root, name, enabled)) break;
            names.add(name);
        }
        return names;
    }

    private static boolean present(Path root, String name, Predicate<String> enabled) {
        return Files.isRegularFile(root.resolve(name + ".csh")) && enabled.test(name);
    }

    /** The GLSL image format of a target's storage, or null when it has none. */
    static String imageFormat(int vkFormat) {
        String name = PackConfig.formatName(vkFormat);
        return PackConfig.FMT_TO_VK.containsKey(name) ? name.toLowerCase(Locale.ROOT) : null;
    }

    /** Translates one compute source into Chimera's Vulkan module, or records why it cannot run. */
    private static final class Translator {
        private final PackPlan plan;
        private final Path shadersDir;
        private final Map<String, String> macros;
        private final Set<String> lockedMacros;

        Translator(PackPlan plan, Path shadersDir) {
            this.plan = plan;
            this.shadersDir = shadersDir;
            PackAdvancedResourcePlan advanced = plan.advancedResources();
            boolean customImages = advanced.capabilityPossible();
            this.macros = customImages ? PackEngineDefines.forCustomImages(plan.settingsPreprocessorDefines())
                    : PackEngineDefines.forPack(plan.settingsPreprocessorDefines());
            this.lockedMacros = PackEngineDefines.lockedNames(plan.settingsOverriddenNames(), customImages, false);
        }

        /**
         * A pass with computes and no fragment program still runs them at its place in the chain
         * (Iris CompositeRenderer.ComputeOnlyPass), so the program need not exist.
         */
        PassCompute translate(String name, String pass, Path path) {
            Work work = new Work(name, pass);
            String text = prepare(path, work);
            if (text == null) return work.result(null, null, null, null);
            int[] localSize = localSize(text, work);
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(text);
            String body = GlslTokenRewriter.removeUnreachableFunctions(text, usage);
            body = bindImages(body, work);
            body = bindSamplers(body, usage, work);
            List<ComputeValueBlock.Value> values = values(body, work);
            body = removeUnusedUniforms(PackAdvancedResourcePlan.VALUE_UNIFORM_DECLARATION.matcher(body)
                    .replaceAll(""), work);
            body = bindStorageBuffers(body, work);
            String source = work.failed ? null : module(body, work, values);
            return work.result(source, localSize, values, text);
        }

        /** The preprocessed source with one declaration per name; null when it cannot be read or is not 430+. */
        private String prepare(Path path, Work work) {
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepareForRuntime(
                        shadersDir, path, raw, macros, lockedMacros);
                prepared.deviations().stream()
                        .filter(value -> value == null || !ShaderSourcePreprocessor.isWarning(value))
                        .forEach(value -> work.fail("PASS_COMPUTE_PREPROCESSOR:" + value));
                GlslVersion version = prepared.successful() ? GlslVersion.first(prepared.source()) : null;
                if (version == null || !version.supported() || version.number() < 430) {
                    work.fail("PASS_COMPUTE_VERSION_UNSUPPORTED");
                }
                if (work.failed) return null;
                String text = ServedUniforms.computeSource(prepared.source(),
                        name -> UniformRegistry.irisSets(name, plan.runtimeSettings().customDescriptors()));
                if (!GlslResourceUsage.analyze(text).successful()) {
                    work.fail("PASS_COMPUTE_RESOURCE_USAGE_UNCLASSIFIABLE");
                }
                return work.failed ? null : text;
            } catch (IOException | RuntimeException failure) {
                work.fail("PASS_COMPUTE_SOURCE_UNREADABLE");
                return null;
            }
        }

        /**
         * Binds each used {@code colorimgN} (1 to 15, a float image2D) in its target's format, and
         * each used pack image (Iris IrisImages.addCustomImages) in its own. The usage analysis
         * tracks 3D images only; with unreachable functions blanked, any reference left besides the
         * declaration is a use.
         */
        private String bindImages(String body, Work work) {
            Matcher image = IMAGE_DECLARATION.matcher(body);
            StringBuilder result = new StringBuilder();
            while (image.find()) {
                String name = image.group(3);
                image.appendReplacement(result, "");
                if (GlslTokenRewriter.identifierCount(body, name) <= 1) continue;
                PackAdvancedResourcePlan.ImageSpec pack = packImage(PackAdvancedResourcePlan.imageBySymbol(
                        plan.advancedResources().images(), name));
                Matcher color = COLOR_IMAGE.matcher(name);
                int target = color.matches() ? Integer.parseInt(color.group(1)) : -1;
                String format = pack != null ? pack.layoutQualifier()
                        : target >= 1 && target <= PackTargetGraphPlan.MAX_TARGET
                        ? imageFormat(targetFormat(target)) : null;
                String type = pack != null ? AdvancedImageDeclarations.storageImageType(pack) : "image2D";
                if (format == null || !image.group(2).equals(type)) {
                    work.fail("PASS_COMPUTE_IMAGE_UNSUPPORTED:" + name);
                    continue;
                }
                work.declare("layout(" + format + ", binding = " + work.bindings.size() + ") "
                        + image.group(1) + type + " " + name + ";");
                work.bindings.add(pack != null
                        ? new Binding(name, work.bindings.size(), Kind.PACK_IMAGE, -1, format, pack.name())
                        : new Binding(name, work.bindings.size(), Kind.TARGET_IMAGE, target, format, null));
            }
            image.appendTail(result);
            return result.toString();
        }

        /** A pack image this session creates, or null. */
        private static PackAdvancedResourcePlan.ImageSpec packImage(PackAdvancedResourcePlan.ImageSpec spec) {
            return spec != null && spec.supported() ? spec : null;
        }

        /**
         * Binds each live 2D sampler of a target, or of the noise texture, that no pack texture
         * replaces, and each live sampler of a pack image (Iris IrisSamplers.addCustomImages).
         */
        private String bindSamplers(String body, GlslResourceUsage.Analysis usage, Work work) {
            Matcher sampler = SAMPLER_DECLARATION.matcher(body);
            StringBuilder result = new StringBuilder();
            while (sampler.find()) {
                String name = sampler.group(2);
                String type = sampler.group(1);
                sampler.appendReplacement(result, "");
                if (!usage.liveSamplers().contains(name)) {
                    unused(body, name, work);
                    continue;
                }
                Binding binding = samplerBinding(name, type, work);
                if (binding == null) {
                    work.fail("PASS_COMPUTE_SAMPLER_UNSUPPORTED:" + name);
                    continue;
                }
                work.declare("layout(binding = " + work.bindings.size() + ") uniform " + type + " " + name + ";");
                work.bindings.add(binding);
            }
            sampler.appendTail(result);
            return result.toString();
        }

        /**
         * Points each storage block at the pack buffer it names, as for programs
         * (StorageBufferDeclarations.matchingBufferSpecs): Iris binds bufferObject.N at index N for
         * every program and compute (ShaderStorageBufferHolder). A block no supported buffer serves,
         * or one without a std430 layout or a binding to rewrite, stops the compute.
         */
        private String bindStorageBuffers(String body, Work work) {
            List<GlslStorageBufferParser.Block> blocks;
            try {
                blocks = GlslStorageBufferParser.scan(body);
            } catch (RuntimeException failure) {
                work.fail("PASS_COMPUTE_STORAGE_BUFFER_PARSE_UNSUPPORTED");
                return body;
            }
            Map<String, Integer> bindings = new TreeMap<>();
            for (GlslStorageBufferParser.Block block : blocks) {
                List<PackAdvancedResourcePlan.BufferSpec> matches = StorageBufferDeclarations.matchingBufferSpecs(
                        new StorageBufferDeclarations.StorageBlock(block.blockName(), block.instanceName(),
                                block.originalBinding(), block.laidOut(), 0), plan.advancedResources().buffers());
                PackAdvancedResourcePlan.BufferSpec spec = matches.size() == 1 ? matches.get(0) : null;
                if (spec == null || !spec.supported() || !block.laidOut() || block.originalBinding() < 0) {
                    work.fail("PASS_COMPUTE_STORAGE_BUFFER_UNSUPPORTED:" + block.blockName());
                    continue;
                }
                bindings.put(GlslStorageBufferParser.key(block), work.bindings.size());
                work.bindings.add(new Binding(block.blockName(), work.bindings.size(), Kind.STORAGE_BUFFER,
                        spec.logicalIndex(), null, null));
            }
            return GlslStorageBufferParser.rewrite(body, bindings);
        }

        /** What the live sampler {@code name} of {@code type} reads, or null when dispatch cannot bind it. */
        private Binding samplerBinding(String name, String type, Work work) {
            PackAdvancedResourcePlan.ImageSpec pack = packImage(PackAdvancedResourcePlan.imageBySampler(
                    plan.advancedResources().images(), name));
            if (pack != null) {
                if (!type.equals(pack.samplerType())) return null;
                return new Binding(name, work.bindings.size(), Kind.PACK_IMAGE_SAMPLER, -1, null, pack.name());
            }
            PackResourceBinding resource = PackResourcePlan.passComputeBinding(plan, work.pass, name, type, shadersDir);
            Integer target = PackResourcePlan.targetIndex(name);
            if (resource.kind() == PackResourceKind.TARGET && target != null && resource.available()) {
                return type.equals(targetSamplerType(target))
                        ? new Binding(name, work.bindings.size(), Kind.TARGET_SAMPLER, target, null, null) : null;
            }
            if (resource.kind() == PackResourceKind.DEPTH && resource.available()) {
                return type.equals("sampler2D")
                        ? new Binding(name, work.bindings.size(), Kind.DEPTH, -1, null, resource.resourceKey()) : null;
            }
            if (!TEXTURE_KINDS.contains(resource.kind()) || !resource.available()) return null;
            // A PNG and the noise are 2D colour images; a raw texture serves only its own sampler type.
            if (resource.kind() != PackResourceKind.RAW_TEXTURE && !type.equals("sampler2D")) return null;
            work.textures.add(resource);
            return new Binding(name, work.bindings.size(), Kind.TEXTURE, -1, null, resource.resourceKey());
        }

        /** The sampler type a target binds to: usampler2D or isampler2D for an integer format (Iris). */
        private String targetSamplerType(int target) {
            IrisColorFormats.Format format = IrisColorFormats.stored(targetFormat(target));
            IrisColorFormats.Kind kind = format == null ? IrisColorFormats.Kind.FLOAT : format.kind();
            return kind == IrisColorFormats.Kind.UINT ? "usampler2D"
                    : kind == IrisColorFormats.Kind.SINT ? "isampler2D" : "sampler2D";
        }

        /** The live value uniforms the frame serves, in the order the block lays them out. */
        private List<ComputeValueBlock.Value> values(String body, Work work) {
            Map<String, String> live = new TreeMap<>();
            Matcher value = PackAdvancedResourcePlan.VALUE_UNIFORM_DECLARATION.matcher(body);
            while (value.find()) {
                String name = value.group(2);
                if (GlslTokenRewriter.identifierCount(body, name) <= 1) continue;
                if (UniformRegistry.resolveForCompute(name, value.group(1),
                        plan.runtimeSettings().customDescriptors()) == null) {
                    work.fail("PASS_COMPUTE_UNIFORM_UNSUPPORTED:" + name);
                } else {
                    live.put(name, value.group(1));
                }
            }
            return ComputeValueBlock.layout(live);
        }

        /** Drops the remaining uniform declarations nothing reads; one still read cannot be bound. */
        private String removeUnusedUniforms(String body, Work work) {
            Matcher other = OTHER_UNIFORM.matcher(body);
            StringBuilder result = new StringBuilder();
            while (other.find()) {
                other.appendReplacement(result, "");
                unused(body, other.group(1), work);
            }
            other.appendTail(result);
            String remaining = result.toString();
            if (GlslTokenRewriter.containsIdentifier(remaining, "uniform")) {
                work.fail("PASS_COMPUTE_UNIFORM_DECLARATION_UNSUPPORTED");
            }
            return remaining;
        }

        private static void unused(String body, String name, Work work) {
            if (GlslTokenRewriter.identifierCount(body, name) > 1) work.fail("PASS_COMPUTE_RESOURCE_UNBOUND:" + name);
        }

        /** Version 450, the source's own extensions (ARB ones are core), the value block, the bindings, the body. */
        private static String module(String body, Work work, List<ComputeValueBlock.Value> values) {
            StringBuilder extensions = new StringBuilder();
            Matcher extension = EXTENSION.matcher(body);
            while (extension.find()) {
                if (!extension.group(1).startsWith("GL_ARB_")) extensions.append(extension.group().trim()).append('\n');
            }
            String text = EXTENSION.matcher(body).replaceAll("")
                    .replaceAll("(?m)^\\s*#version\\b[^\\r\\n]*(?:\\r?\\n|$)", "");
            return "#version 450\n" + extensions
                    + ComputeValueBlock.declaration(VALUES_BLOCK, values, work.bindings.size())
                    + work.declarations + LegacyGlslConverter.convertTextureCalls(text);
        }

        /** The declared local size, unspecified axes 1; null with a deviation when there is none. */
        private static int[] localSize(String text, Work work) {
            Matcher layout = LOCAL_SIZE.matcher(text);
            if (!layout.find()) {
                work.fail("PASS_COMPUTE_WORKGROUP_UNSUPPORTED");
                return null;
            }
            int[] size = {1, 1, 1};
            Matcher axis = LOCAL_SIZE_AXIS.matcher(layout.group(1));
            while (axis.find()) size[axis.group(1).charAt(0) - 'x'] = Integer.parseInt(axis.group(2));
            return size;
        }

        private int targetFormat(int target) {
            Map<Integer, Integer> formats = plan.config() == null ? Map.of() : plan.config().colortexFormats();
            return formats.getOrDefault(target, PostTargetPlan.DEFAULT_FORMAT);
        }
    }

    /** One compute's translation in progress: its bindings, generated declarations and deviations. */
    private static final class Work {
        final String name;
        final String pass;
        final List<Binding> bindings = new ArrayList<>();
        final List<PackResourceBinding> textures = new ArrayList<>();
        final StringBuilder declarations = new StringBuilder();
        final List<String> deviations = new ArrayList<>();
        boolean failed;

        Work(String name, String pass) {
            this.name = name;
            this.pass = pass;
        }

        void fail(String deviation) {
            deviations.add(deviation);
            failed = true;
        }

        void declare(String declaration) {
            declarations.append(declaration).append('\n');
        }

        PassCompute result(String source, int[] localSize, List<ComputeValueBlock.Value> values, String text) {
            int[] workGroups = null;
            float[] render = null;
            Matcher absolute = text == null ? null : WORK_GROUPS.matcher(text);
            if (absolute != null && absolute.find()) {
                workGroups = new int[] {Integer.parseInt(absolute.group(1)), Integer.parseInt(absolute.group(2)),
                        Integer.parseInt(absolute.group(3))};
            }
            Matcher relative = text == null ? null : WORK_GROUPS_RENDER.matcher(text);
            if (relative != null && relative.find()) {
                render = new float[] {Float.parseFloat(relative.group(1)), Float.parseFloat(relative.group(2))};
            }
            return new PassCompute(name, pass, failed ? null : source, localSize, workGroups, render,
                    List.copyOf(bindings), List.copyOf(textures), values == null ? List.of() : values,
                    List.copyOf(deviations));
        }
    }
}
