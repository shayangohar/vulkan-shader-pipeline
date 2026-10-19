package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable load-time resource plan shared by probing, pipelines, and runtime. */
public final class PackResourcePlan {
    private static final Pattern COLOR_TARGET = Pattern.compile("colortex([0-9]+)");
    /** A generated noisetex binding's source: this prefix, then the image's side in texels. */
    public static final String GENERATED_NOISE = "generated-noise:";
    private static final int DEFAULT_NOISE_RESOLUTION = 256;
    private static final int MAX_NOISE_RESOLUTION = 4096;
    private static final Pattern NOISE_RESOLUTION = Pattern.compile(
            "(?m)^\\s*const\\s+int\\s+noiseTextureResolution\\s*=\\s*(\\d{1,5})\\s*;");

    /** Chimera's name for the host entity overlay, which entityColor is made from. */
    public static final String OVERLAY_SAMPLER = "chimeraOverlay";

    /**
     * Host textures a program samples through the host's own selector slot:
     * the draw's atlas, the entity overlay (hurt tint and flash) and the
     * lightmap. Every host-texture decision reads this one table.
     */
    public static final Map<String, Integer> HOST_TEXTURE_SLOTS =
            Map.of("texture", 0, OVERLAY_SAMPLER, 1, "lightmap", 2);

    public static boolean isHostTexture(String resourceKey) {
        return resourceKey != null && HOST_TEXTURE_SLOTS.containsKey(resourceKey);
    }

    private final Map<String, List<PackResourceBinding>> byProgram;
    private final Map<String, PackResourceDeclaration> declarations;
    private final List<String> deviations;
    private final String snapshot;
    private final String fingerprint;

    private PackResourcePlan(
            Map<String, List<PackResourceBinding>> byProgram,
            Map<String, PackResourceDeclaration> declarations,
            List<String> deviations
    ) {
        Map<String, List<String>> resourceUsers = new TreeMap<>();
        byProgram.forEach((program, bindings) -> {
            for (PackResourceBinding binding : bindings == null ? List.<PackResourceBinding>of() : bindings) {
                resourceUsers.computeIfAbsent(binding.resourceKey(), ignored -> new ArrayList<>())
                        .add(program);
            }
        });
        Map<String, List<PackResourceBinding>> programCopy = new TreeMap<>();
        byProgram.forEach((program, bindings) -> {
            List<PackResourceBinding> normalized = bindings == null ? List.of()
                    : bindings.stream()
                    .sorted(Comparator.comparing(PackResourceBinding::sampler))
                    .map(binding -> binding.withDependentPrograms(
                            resourceUsers.getOrDefault(binding.resourceKey(), List.of())))
                    .toList();
            programCopy.put(program, normalized);
        });
        this.byProgram = Collections.unmodifiableMap(programCopy);
        this.declarations = Collections.unmodifiableMap(new TreeMap<>(declarations));
        this.deviations = deviations == null ? List.of()
                : deviations.stream().filter(value -> value != null && !value.isBlank())
                .distinct().sorted().toList();
        this.snapshot = snapshotJson();
        this.fingerprint = ConformanceReport.sha256(snapshot.getBytes(StandardCharsets.UTF_8));
    }

    public static PackResourcePlan empty() {
        return new PackResourcePlan(Map.of(), Map.of(), List.of());
    }

    public static PackResourcePlan build(PackPlan plan, Path shadersDir) {
        if (plan == null) return empty();
        PackSettingsPlan settings = plan.settings();
        Map<String, PackResourceDeclaration> declarations = settings == null
                ? Map.of() : settings.resourceDeclarations();
        Map<String, List<PackResourceBinding>> bindings = new TreeMap<>();
        List<String> deviations = new ArrayList<>();
        deviations.addAll(settings == null ? List.of() : settings.resourceDeviations());

        if (settings != null) {
            addSlotLimitDeviations(plan, settings, deviations);
        }

        Map<String, Set<Integer>> writtenEarlier = targetsWrittenEarlierInChain(plan);
        int noiseResolution = noiseResolution(plan);
        for (PackProgramPlan programPlan : plan.programs()) {
            if (programPlan == null || programPlan.interfacePlan() == null) continue;
            UniformRegistry.Stage stage = FamilyAdapterRegistry.stageFor(programPlan.name());
            List<UniformRegistry.SamplerBinding> samplers = programPlan.interfacePlan()
                    .effective(stage).samplers();
            Set<Integer> deactivatedOverrides = writtenEarlier.getOrDefault(programPlan.name(), Set.of());
            List<PackResourceBinding> programBindings = new ArrayList<>();
            boolean waterShadow = declaresWaterShadow(samplers);
            for (UniformRegistry.SamplerBinding sampler : samplers) {
                PackProgramResolution resolved = plan.resolution().resolution(programPlan.name());
                String sourceProgram = resolved == null || resolved.selectedProgram().isBlank()
                        ? programPlan.name() : resolved.selectedProgram();
                PackResourceBinding binding = resolve(sourceProgram, sampler, waterShadow, declarations,
                        shadersDir, settings == null ? Map.of() : settings.propertyValues(), deactivatedOverrides);
                if (!sourceProgram.equals(programPlan.name())) {
                    binding = new PackResourceBinding(programPlan.name(), binding.sampler(), binding.resourceKey(),
                            binding.kind(), binding.source(), binding.slot(), binding.filter(), binding.wrap(),
                            binding.status(), binding.deviations());
                }
                if (binding.status() == PackResourceStatus.GENERATED) {
                    binding = withNoiseResolution(binding, noiseResolution);
                }
                programBindings.add(binding);
                deviations.addAll(binding.deviations());
            }
            bindings.put(programPlan.name(), List.copyOf(programBindings));
        }
        return new PackResourcePlan(bindings, declarations, deviations);
    }

    /** A pack texture that a live sampler reads, but that no selector slot is left to serve. */
    private static void addSlotLimitDeviations(PackPlan plan, PackSettingsPlan settings, List<String> deviations) {
        Map<String, Integer> customSlots = settings.customSamplerSlots();
        TreeSet<String> activeSamplers = new TreeSet<>();
        for (PackProgramPlan programPlan : plan.programs()) {
            if (programPlan == null || programPlan.interfacePlan() == null) continue;
            UniformRegistry.Stage stage = FamilyAdapterRegistry.stageFor(programPlan.name());
            programPlan.interfacePlan().effective(stage).samplers().stream()
                    .map(UniformRegistry.SamplerBinding::name)
                    .forEach(activeSamplers::add);
        }
        for (PackResourceDeclaration declaration : settings.resourceDeclarations().values()) {
            String sampler = declaration.sampler();
            if (activeSamplers.contains(sampler)
                    && !customSlots.containsKey(sampler)
                    && !UniformRegistry.NAME_TO_SLOT.containsKey(sampler)) {
                deviations.add("PACK_RESOURCE_SLOT_LIMIT:" + sampler);
            }
        }
    }

    /**
     * Resolve the live block atlas and its stitched material companions,
     * honoring pack texture overrides. Terrain normals/specular share the
     * atlas texel topology and UVs, so they take the same magnification
     * reconstruction as the albedo (Iris samples all three nearest).
     */
    static Set<String> terrainAtlasSamplers(String program,
            UniformRegistry.ProgramInterface iface, PackSettingsPlan settings) {
        if (iface == null || (iface.stage() != UniformRegistry.Stage.GEOMETRY
                && iface.stage() != UniformRegistry.Stage.TRANSLUCENT)) return Set.of();
        PackSettingsPlan resources = settings == null ? PackSettingsPlan.empty() : settings;
        Set<String> names = new TreeSet<>();
        for (UniformRegistry.SamplerBinding sampler : iface.samplers()) {
            if (!sampler.glslType().equals("sampler2D")) continue;
            PackResourceBinding binding = resolve(program, sampler, declaresWaterShadow(iface.samplers()),
                    resources.resourceDeclarations(), null, resources.propertyValues(), Set.of());
            boolean albedo = sampler.slot() == 0
                    && binding.kind() == PackResourceKind.TARGET
                    && binding.status() == PackResourceStatus.HOST_ALIAS
                    && binding.resourceKey().equals("texture");
            if (albedo || binding.status() == PackResourceStatus.MATERIAL_MAP) names.add(sampler.name());
        }
        return Set.copyOf(names);
    }

    private static PackResourceBinding resolve(
            String program,
            UniformRegistry.SamplerBinding sampler,
            boolean waterShadow,
            Map<String, PackResourceDeclaration> declarations,
            Path shadersDir,
            Map<String, String> propertyValues,
            Set<Integer> deactivatedOverrides
    ) {
        String name = sampler.name();
        int slot = sampler.slot();
        List<String> deviations = new ArrayList<>();
        PackResourceDeclaration declaration =
                declarationFor(program, sampler, declarations, deactivatedOverrides, deviations);
        // Pack declarations take precedence over canonical aliases. This is
        // important for a stage that deliberately replaces a logical target
        // with a sampled pack texture.
        if (declaration != null) return declaredBinding(program, name, slot, declaration, shadersDir, deviations);
        if (name.equals("tex") || name.equals("gtexture") || isHostTexture(name)) {
            String hostResource = name.equals("tex") || name.equals("gtexture") ? "texture" : name;
            // The overlay is Chimera's own declaration, not a pack alias.
            if (!name.equals(OVERLAY_SAMPLER)) {
                deviations.add("STANDARD_RESOURCE_ALIAS:" + name + ":" + hostResource);
            }
            return new PackResourceBinding(program, name, hostResource,
                    PackResourceKind.TARGET, "", slot, "linear", "repeat",
                    PackResourceStatus.HOST_ALIAS, deviations);
        }

        String advancedImage = advancedImageName(propertyValues, name);
        if (advancedImage != null) {
            deviations.add("ADVANCED_RESOURCE_ALIAS:" + name + ":" + advancedImage);
            return new PackResourceBinding(program, name, advancedImage,
                    PackResourceKind.ADVANCED_IMAGE, "", slot, "linear", "repeat",
                    PackResourceStatus.HOST_ALIAS, deviations);
        }
        return standardBinding(program, name, programResource(name, waterShadow), slot, deviations);
    }

    /** Whether a pack raw texture serves this sampler, of this GLSL type, in this program. */
    static boolean rawTextureServes(String program, String sampler, String samplerType,
                                    Map<String, PackResourceDeclaration> declarations) {
        PackResourceDeclaration declaration = declaration(program, sampler, samplerType, declarations);
        return declaration != null && declaration.kind() == PackResourceKind.RAW_TEXTURE;
    }

    /** Whether any pack declaration replaces this sampler, of this GLSL type, in this program. */
    static boolean declaresTexture(String program, String sampler, String samplerType,
                                   Map<String, PackResourceDeclaration> declarations) {
        return declaration(program, sampler, samplerType, declarations) != null;
    }

    private static PackResourceDeclaration declaration(String program, String sampler, String samplerType,
                                                       Map<String, PackResourceDeclaration> declarations) {
        return declarationFor(program, new UniformRegistry.SamplerBinding(sampler, 0, samplerType), declarations,
                Set.of(), new ArrayList<>());
    }

    /** The pack declaration that serves this sampler, if any; an override a pass deactivated is none. */
    private static PackResourceDeclaration declarationFor(String program, UniformRegistry.SamplerBinding sampler,
                                                          Map<String, PackResourceDeclaration> declarations,
                                                          Set<Integer> deactivatedOverrides, List<String> deviations) {
        String name = sampler.name();
        String samplerType = sampler.glslType();
        PackResourceDeclaration declaration = textureDeclaration(declarations, program, name, samplerType);
        String textureStage = textureStage(program);
        if (declaration == null && textureStage != null) {
            declaration = textureDeclaration(declarations, textureStage, name, samplerType);
        }
        if (declaration == null) declaration = textureDeclaration(declarations, "*", name, samplerType);
        // A 1D or 3D raw texture is its own uniform under Iris (TextureTransformer renames it by
        // type), so a pass writing the same-named target never takes it back.
        Integer overriddenTarget = declaration == null || !appliesTo(declaration, "sampler2D") ? null
                : targetIndex(name);
        if (overriddenTarget != null && deactivatedOverrides.contains(overriddenTarget)) {
            // An earlier pass of this chain wrote the target: it now samples the target.
            deviations.add("PACK_TEXTURE_OVERRIDE_DEACTIVATED:" + program + ":" + name);
            declaration = null;
        }
        if (declaration == null && appliesTo(declarations.get("customTexture." + name), samplerType)) {
            declaration = declarations.get("customTexture." + name);
        }
        if (declaration == null && name.equals("tex")
                && FamilyAdapterRegistry.stageFor(program) == UniformRegistry.Stage.POST) {
            declaration = declarations.get("customTexture.textureAtlas");
        }
        if (declaration == null && name.equals("noisetex")) {
            declaration = declarations.get("texture.noise");
        }
        return declaration;
    }

    /** A sampler the pack declares a texture for. */
    private static PackResourceBinding declaredBinding(String program, String name, int slot,
                                                       PackResourceDeclaration declaration, Path shadersDir,
                                                       List<String> deviations) {
        if (declaration.kind() == PackResourceKind.RAW_TEXTURE) {
            return rawBinding(program, name, slot, declaration, shadersDir, deviations);
        }
        boolean game = isNamespacedSource(declaration.source());
        String key = name.equals("noisetex") ? "noisetex" : declaration.key();
        PackResourceKind kind = name.equals("noisetex")
                ? PackResourceKind.NOISE : PackResourceKind.PACK_TEXTURE;
        String filter = name.equals("noisetex") ? "linear" : "nearest";
        String wrap = "repeat";
        int resolvedSlot = name.equals("noisetex") ? 7 : slot;
        if (isHostBlockAtlasSource(declaration.source())) {
            deviations.add("STANDARD_RESOURCE_ALIAS:" + name + ":texture");
            return new PackResourceBinding(program, name, "texture",
                    PackResourceKind.TARGET, declaration.source(), resolvedSlot,
                    "linear", "repeat", PackResourceStatus.HOST_ALIAS, deviations);
        }
        if (game) {
            deviations.add("PACK_TEXTURE_DECLARED:" + key);
            return new PackResourceBinding(program, name, key, kind,
                    declaration.source(), resolvedSlot, filter, wrap,
                    PackResourceStatus.GAME_RESOURCE, deviations);
        }
        Path packFile = resolvePath(shadersDir, declaration.source());
        if (packFile != null) {
            deviations.add("PACK_TEXTURE_APPLIED:" + key);
            return new PackResourceBinding(program, name, key, kind,
                    declaration.source(), resolvedSlot, mcmetaFilter(packFile, filter),
                    mcmetaWrap(packFile, wrap), PackResourceStatus.PACK_FILE, deviations);
        }
        deviations.add(isUnsafePath(declaration.source())
                ? "PACK_TEXTURE_PATH_UNSAFE:" + key
                : "PACK_TEXTURE_MISSING:" + key);
        return new PackResourceBinding(program, name, key, kind,
                declaration.source(), resolvedSlot, filter, wrap,
                PackResourceStatus.UNAVAILABLE, deviations);
    }

    /**
     * A raw texture (RawTextureSpec): the file's bytes uploaded as a 1D, 2D or 3D image and
     * sampled as its .mcmeta says (Iris GlTexture: nearest and repeat without it). A format
     * Chimera cannot upload unchanged stays unsupported, so the program never samples something
     * else in its place.
     */
    private static PackResourceBinding rawBinding(String program, String name, int slot,
                                                  PackResourceDeclaration declaration, Path shadersDir,
                                                  List<String> deviations) {
        String key = declaration.key();
        RawTextureSpec spec = RawTextureSpec.parse(declaration.source());
        if (spec == null || spec.unsupportedReason() != null) {
            deviations.add("PACK_RAW_TEXTURE_UNSUPPORTED:" + key);
            return new PackResourceBinding(program, name, key, PackResourceKind.RAW_TEXTURE,
                    declaration.source(), slot, "linear", "repeat", PackResourceStatus.UNSUPPORTED, deviations);
        }
        Path file = resolvePath(shadersDir, spec.file());
        String status;
        if (file == null) {
            status = isUnsafePath(spec.file()) ? "PACK_TEXTURE_PATH_UNSAFE:" : "PACK_TEXTURE_MISSING:";
        } else {
            status = rawSize(file) < spec.requiredBytes() ? "PACK_RAW_TEXTURE_SIZE_MISMATCH:"
                    : "PACK_RAW_TEXTURE_APPLIED:";
        }
        deviations.add(status + key);
        boolean applied = status.equals("PACK_RAW_TEXTURE_APPLIED:");
        // Vulkan filters an integer image with nearest only.
        String filter = spec.storage().integer() ? "nearest" : mcmetaFilter(file, "nearest");
        return new PackResourceBinding(program, name, key, PackResourceKind.RAW_TEXTURE, declaration.source(),
                slot, filter, mcmetaWrap(file, "repeat"),
                applied ? PackResourceStatus.PACK_FILE : PackResourceStatus.UNAVAILABLE, deviations);
    }

    private static long rawSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException unreadable) {
            return -1;
        }
    }

    /** A sampler named for a logical target, shadow map, noise texture or material map. */
    private static PackResourceBinding standardBinding(String program, String name, String canonical, int slot,
                                                       List<String> deviations) {
        Integer target = targetIndex(canonical);
        if (target != null && target > PostTargetPlan.MAX_TARGET) {
            deviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + target);
            return new PackResourceBinding(program, name, canonical,
                    PackResourceKind.TARGET, "", slot, "linear", "clamp",
                    PackResourceStatus.UNSUPPORTED, deviations);
        }
        if (canonical.startsWith("colortex") || canonical.startsWith("depthtex")) {
            deviations.add("STANDARD_RESOURCE_ALIAS:" + name + ":" + canonical);
            return new PackResourceBinding(program, name, canonical,
                    canonical.startsWith("depthtex") ? PackResourceKind.DEPTH : PackResourceKind.TARGET,
                    "", slot, "linear", "clamp", PackResourceStatus.HOST_ALIAS, deviations);
        }
        // shadowtex1 is its own image: the casters drawn before translucent terrain.
        if (canonical.equals("shadowtex0") || canonical.equals("shadowtex1")) {
            deviations.add("SHADOW_RESOURCE_ALIAS:" + name);
            return new PackResourceBinding(program, name, canonical,
                    PackResourceKind.SHADOW_DEPTH, "", slot, "linear", "clamp",
                    PackResourceStatus.HOST_ALIAS, deviations);
        }
        if (canonical.startsWith("shadowtex")) {
            deviations.add("STANDARD_RESOURCE_UNAVAILABLE:" + name);
            return new PackResourceBinding(program, name, canonical,
                    PackResourceKind.SHADOW_DEPTH, "", slot, "linear", "clamp",
                    PackResourceStatus.UNAVAILABLE, deviations);
        }
        if (canonical.equals("shadowcolor0") || canonical.equals("shadowcolor1")) {
            deviations.add("SHADOW_RESOURCE_ALIAS:" + name);
            return new PackResourceBinding(program, name, canonical,
                    PackResourceKind.SHADOW_COLOR, "", slot, "linear", "clamp",
                    PackResourceStatus.HOST_ALIAS, deviations);
        }
        if (canonical.equals("noisetex")) {
            return noiseBinding(program, name, deviations);
        }
        if (materialMapAtSlot(name, slot)) {
            return new PackResourceBinding(program, name, name,
                    PackResourceKind.MATERIAL_MAP, "", slot, "linear", "repeat",
                    PackResourceStatus.MATERIAL_MAP, deviations);
        }
        deviations.add("SAMPLER_NOT_MAPPED:" + name);
        return new PackResourceBinding(program, name, name,
                PackResourceKind.UNSERVED, "", slot, "nearest", "repeat",
                PackResourceStatus.UNSUPPORTED, deviations);
    }

    /**
     * Iris's generated noise at the fixed slot. A pack without texture.noise samples it whatever
     * files it ships (CustomTextureManager); Mellow, RedHat and Sildur's read noisetex so.
     */
    private static PackResourceBinding noiseBinding(String program, String name, List<String> deviations) {
        deviations.add("PACK_TEXTURE_GENERATED:noisetex");
        return new PackResourceBinding(program, name, "noisetex", PackResourceKind.NOISE,
                GENERATED_NOISE + DEFAULT_NOISE_RESOLUTION, 7, "linear", "repeat",
                PackResourceStatus.GENERATED, deviations);
    }

    /** The generated noise's binding at the pack's noiseTextureResolution. */
    private static PackResourceBinding withNoiseResolution(PackResourceBinding binding, int resolution) {
        return new PackResourceBinding(binding.program(), binding.sampler(), binding.resourceKey(), binding.kind(),
                GENERATED_NOISE + resolution, binding.slot(), binding.filter(), binding.wrap(), binding.status(),
                binding.deviations());
    }

    /** The side of the generated noise: the pack's const int noiseTextureResolution, else Iris's 256. */
    static int noiseResolution(PackPlan plan) {
        int resolution = DEFAULT_NOISE_RESOLUTION;
        for (PackProgramPlan program : plan.programs()) {
            if (program == null) continue;
            for (PreparedShaderSource stage : program.stages().values()) {
                Matcher matcher = NOISE_RESOLUTION.matcher(stage == null || stage.source() == null
                        ? "" : stage.source());
                if (matcher.find()) resolution = Integer.parseInt(matcher.group(1));
            }
        }
        return Math.max(1, Math.min(MAX_NOISE_RESOLUTION, resolution));
    }

    /** The side a generated noise binding's source names. */
    public static int generatedNoiseSize(String source) {
        if (source == null || !source.startsWith(GENERATED_NOISE)) return DEFAULT_NOISE_RESOLUTION;
        return Integer.parseInt(source.substring(GENERATED_NOISE.length()));
    }

    /** Whether a normals or specular sampler sits at the selector slot reserved for it. */
    private static boolean materialMapAtSlot(String name, int slot) {
        if (name.equals("normals")) return slot == SelectorNamespace.NORMALS_SLOT;
        return name.equals("specular") && slot == SelectorNamespace.SPECULAR_SLOT;
    }

    private static boolean declaresWaterShadow(List<UniformRegistry.SamplerBinding> samplers) {
        for (UniformRegistry.SamplerBinding sampler : samplers) {
            if (sampler.name().equals("watershadow")) return true;
        }
        return false;
    }

    public List<PackResourceBinding> bindings(String program) {
        return byProgram.getOrDefault(program, List.of());
    }

    public Map<String, List<PackResourceBinding>> bindings() {
        return byProgram;
    }

    public PackResourceBinding binding(String program, String sampler) {
        return bindings(program).stream()
                .filter(value -> value.sampler().equals(sampler))
                .findFirst().orElse(null);
    }

    public boolean programAllowed(String program) {
        return bindings(program).stream().allMatch(PackResourceBinding::available);
    }

    /** Reports the binding that failed, never an unrelated successful resource declaration. */
    public String unavailableReason(String program) {
        return bindings(program).stream().filter(binding -> !binding.available())
                .map(binding -> binding.deviations().isEmpty()
                        ? "PACK_RESOURCE_UNAVAILABLE:" + binding.sampler()
                        : binding.deviations().get(0))
                .findFirst().orElse("PACK_RESOURCE_UNAVAILABLE:" + program);
    }

    public List<String> deviationsForProgram(String program) {
        return bindings(program).stream().flatMap(value -> value.deviations().stream())
                .distinct().sorted().toList();
    }

    public Map<String, PackResourceDeclaration> declarations() { return declarations; }
    public List<String> deviations() { return deviations; }
    public String snapshot() { return snapshot; }
    public String fingerprint() { return fingerprint; }

    public static String canonicalResource(String sampler) {
        if (sampler == null) return "";
        if (sampler.equals("tex") || sampler.equals("gtexture")) return "texture";
        if (sampler.equals("gcolor")) return "colortex0";
        if (sampler.equals("gdepth")) return "colortex1";
        if (sampler.equals("gnormal")) return "colortex2";
        if (sampler.equals("composite")) return "colortex3";
        if (sampler.matches("gaux[1-4]")) {
            return "colortex" + (3 + Integer.parseInt(sampler.substring(4)));
        }
        if (COLOR_TARGET.matcher(sampler).matches()) return sampler;
        if (sampler.matches("depthtex[0-2]")) return sampler;
        if (sampler.matches("shadowtex[0-1]")) return sampler;
        if (sampler.matches("shadowcolor[0-1]")) return sampler;
        return sampler.equals("noisetex") ? "noisetex" : sampler;
    }

    /**
     * Iris's per-program shadow aliases ({@code IrisSamplers.addShadowSamplers}): {@code shadow}
     * is shadowtex0, unless the same program declares {@code watershadow}, which then takes
     * shadowtex0 and leaves {@code shadow} the opaque casters, shadowtex1. {@code shadowcolor} is
     * shadowcolor0. Every other name has one meaning everywhere: {@link #canonicalResource}.
     */
    public static String programResource(String sampler, boolean declaresWaterShadow) {
        if (sampler == null) return "";
        return switch (sampler) {
            case "watershadow" -> "shadowtex0";
            case "shadow" -> declaresWaterShadow ? "shadowtex1" : "shadowtex0";
            case "shadowcolor" -> "shadowcolor0";
            default -> canonicalResource(sampler);
        };
    }

    /** The legacy shadow sampler names whose meaning {@link #programResource} decides. */
    public static boolean isProgramShadowAlias(String sampler) {
        return "shadow".equals(sampler) || "watershadow".equals(sampler) || "shadowcolor".equals(sampler);
    }

    /**
     * Iris CompositeRenderer: in one post chain (begin, prepare, deferred, or
     * composite with final) a stage texture override of colortexN applies only
     * until an earlier pass of that chain has written colortexN; later passes
     * sample the target. Draw buffers and explicit flips count as writes, _pre
     * flips do not. Bliss replaces colortex6 with blue noise for its early
     * composites and then blurs bloom tiles in colortex6.
     */
    private static Map<String, Set<Integer>> targetsWrittenEarlierInChain(PackPlan plan) {
        Map<String, List<Integer>> flips = plan.config() == null ? Map.of() : plan.config().flips();
        Map<String, Set<Integer>> writtenByChain = new TreeMap<>();
        Map<String, Set<Integer>> result = new TreeMap<>();
        List<PackProgramPlan> programs = plan.activePrograms().stream()
                .filter(program -> program != null && program.targetPlan() != null
                        && postChain(program.name()) != null)
                .sorted(Comparator.comparing(PackProgramPlan::name, PostTargetPlan.programComparator()))
                .toList();
        for (PackProgramPlan program : programs) {
            Set<Integer> written = writtenByChain.computeIfAbsent(postChain(program.name()), key -> new TreeSet<>());
            result.put(program.name(), Set.copyOf(written));
            written.addAll(program.targetPlan().targetSlots());
            written.addAll(flips.getOrDefault(program.name(), List.of()));
        }
        return result;
    }

    private static String postChain(String program) {
        if (program.equals("final") || program.matches("composite\\d*")) return "composite";
        if (program.matches("deferred\\d*")) return "deferred";
        if (program.matches("prepare\\d*")) return "prepare";
        if (program.matches("begin\\d*")) return "begin";
        return null;
    }

    public static Integer targetIndex(String sampler) {
        Matcher matcher = COLOR_TARGET.matcher(canonicalResource(sampler));
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
    }

    /**
     * {@code texture.<scope>.<sampler>} for this sampler or any alias of the
     * same target: Iris overrides colortex7 and gaux4 together.
     */
    private static PackResourceDeclaration textureDeclaration(
            Map<String, PackResourceDeclaration> declarations, String scope, String name,
            String samplerType) {
        String prefix = "texture." + scope + ".";
        PackResourceDeclaration exact = declarations.get(prefix + name);
        if (exact != null && appliesTo(exact, samplerType)) return exact;
        String canonical = canonicalResource(name);
        for (PackResourceDeclaration declaration : declarations.values()) {
            if (declaration.key().startsWith(prefix)
                    && canonicalResource(declaration.sampler()).equals(canonical)
                    && appliesTo(declaration, samplerType)) {
                return declaration;
            }
        }
        return null;
    }

    /**
     * Whether a declaration replaces a sampler of this GLSL type. Iris patches a raw texture
     * only into a sampler of its own dimensionality, so `sampler2D colortex0` stays the render
     * target while `sampler3D colortex0` takes the declared volume; a PNG is always 2D.
     */
    static boolean appliesTo(PackResourceDeclaration declaration, String samplerType) {
        if (declaration == null || declaration.kind() != PackResourceKind.RAW_TEXTURE) {
            return declaration != null;
        }
        String[] parts = declaration.source().trim().split("\\s+");
        String dimension = switch (parts[1].toUpperCase(Locale.ROOT)) {
            case "TEXTURE_1D" -> "1D";
            case "TEXTURE_2D", "TEXTURE_RECTANGLE" -> "2D";
            case "TEXTURE_3D" -> "3D";
            default -> "";
        };
        String type = samplerType == null ? "" : samplerType.replaceFirst("^[iu]", "");
        return !dimension.isEmpty() && (type.equals("sampler" + dimension)
                || (dimension.equals("2D") && type.equals("sampler2DRect")));
    }

    /**
     * Iris texture stage named by {@code texture.<stage>.<sampler>}. One stage
     * covers its numbered programs (deferred1 reads texture.deferred.*); shadow
     * programs share the gbuffers stage and final shares the composite stage.
     */
    static String textureStage(String program) {
        if (program == null) return null;
        for (String stage : List.of("begin", "shadowcomp", "prepare", "deferred", "composite")) {
            if (program.startsWith(stage) && program.substring(stage.length()).matches("\\d*")) {
                return stage;
            }
        }
        if (program.equals("final")) return "composite";
        if (program.startsWith("gbuffers_") || program.startsWith("dh_")
                || program.equals("shadow") || program.startsWith("shadow_")) return "gbuffers";
        return null;
    }

    private String snapshotJson() {
        JsonObject root = new JsonObject();
        JsonArray declarationArray = new JsonArray();
        declarations.values().stream().sorted(Comparator.comparing(PackResourceDeclaration::key))
                .forEach(value -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("key", value.key());
                    item.addProperty("stage", value.stage());
                    item.addProperty("sampler", value.sampler());
                    item.addProperty("source", value.source());
                    item.addProperty("kind", value.kind().name());
                    declarationArray.add(item);
                });
        root.add("declarations", declarationArray);
        JsonArray bindingArray = new JsonArray();
        byProgram.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                entry.getValue().forEach(value -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("program", value.program());
                    item.addProperty("sampler", value.sampler());
                    item.addProperty("resource", value.resourceKey());
                    item.addProperty("kind", value.kind().name());
                    item.addProperty("source", value.source());
                    item.addProperty("slot", value.slot());
                    item.addProperty("filter", value.filter());
                    item.addProperty("wrap", value.wrap());
                    item.addProperty("status", value.status().name());
                    item.add("dependentPrograms", strings(value.dependentPrograms()));
                    item.add("deviations", strings(value.deviations()));
                    bindingArray.add(item);
                }));
        root.add("bindings", bindingArray);
        root.add("deviations", strings(deviations));
        return root.toString();
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        values.stream().sorted().forEach(result::add);
        return result;
    }

    public static Path resolvePath(Path shadersDir, String source) {
        if (shadersDir == null || source == null || source.isBlank()
                || source.contains("\0")) return null;
        String normalized = packRelative(source);
        if (normalized.startsWith("/") || normalized.matches("[A-Za-z]:/.*")) return null;
        Path root = shadersDir.toAbsolutePath().normalize();
        Path path = root.resolve(normalized).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) return null;
        return path;
    }

    /**
     * A pack texture's sampling, as Iris reads it: {@code <file>.mcmeta}'s
     * {@code texture.blur} selects linear filtering and {@code texture.clamp}
     * clamp-to-edge. Without the file, or without a key, the default stands.
     */
    static String mcmetaFilter(Path file, String fallback) {
        Boolean blur = mcmetaFlag(file, "blur");
        return blur == null ? fallback : blur ? "linear" : "nearest";
    }

    static String mcmetaWrap(Path file, String fallback) {
        Boolean clamp = mcmetaFlag(file, "clamp");
        return clamp == null ? fallback : clamp ? "clamp" : "repeat";
    }

    private static Boolean mcmetaFlag(Path file, String key) {
        Path meta = file == null ? null : file.resolveSibling(file.getFileName() + ".mcmeta");
        if (meta == null || !Files.isRegularFile(meta)) return null;
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject texture = root.has("texture") && root.get("texture").isJsonObject()
                    ? root.getAsJsonObject("texture") : null;
            return texture != null && texture.has(key) && texture.get(key).isJsonPrimitive()
                    ? texture.get(key).getAsBoolean() : null;
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    /**
     * A texture path relative to the shaders folder. Iris drops one leading slash
     * ({@code /img/smaaArea.png} in Mellow, Continuum's {@code /tex/...}); a second one stays.
     */
    private static String packRelative(String source) {
        String normalized = source.replace('\\', '/');
        return normalized.startsWith("/") ? normalized.substring(1) : normalized;
    }

    private static boolean isUnsafePath(String source) {
        if (source == null || source.isBlank()) return true;
        String normalized = packRelative(source);
        return normalized.startsWith("/") || normalized.matches("[A-Za-z]:/.*")
                || normalized.equals("..") || normalized.startsWith("../")
                || normalized.contains("/../") || normalized.contains("/./")
                || normalized.equals(".") || normalized.contains("\0");
    }

    private static boolean isNamespacedSource(String source) {
        return source != null && source.contains(":") && !isUnsafePath(source)
                && !source.startsWith("./") && !source.startsWith("../");
    }

    /** The block atlas is generated by Minecraft and already owned by selector slot 0. */
    private static boolean isHostBlockAtlasSource(String source) {
        return source != null
                && source.trim().equalsIgnoreCase("minecraft:textures/atlas/blocks.png");
    }

    static String advancedImageName(Map<String, String> propertyValues, String sampler) {
        if (propertyValues == null || sampler == null || sampler.isBlank()) return null;
        for (Map.Entry<String, String> entry : new TreeMap<>(propertyValues).entrySet()) {
            if (!entry.getKey().startsWith("image.")) continue;
            String[] values = entry.getValue() == null
                    ? new String[0] : entry.getValue().trim().split("\\s+");
            if (values.length > 0 && sampler.equals(values[0])) {
                return entry.getKey().substring("image.".length());
            }
        }
        return null;
    }
}
