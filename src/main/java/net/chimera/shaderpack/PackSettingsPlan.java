package net.chimera.shaderpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Immutable defaults-only interpretation of shaders.properties (ShadersPropertiesReader) and pack options. */
record PackSettingsPlan(
        Map<String, Option> options,
        Map<String, String> defaults,
        Map<String, Profile> profiles,
        Map<String, String> propertyValues,
        Map<String, Boolean> programEnabled,
        Set<String> requiredFeatures,
        Set<String> optionalFeatures,
        Set<String> unsupportedRequiredFeatures,
        List<String> deviations,
        String activeProfile,
        PackRuntimeSettings runtimeSettings,
        Map<String, PackResourceDeclaration> resourceDeclarations,
        Map<String, Integer> samplerSlots,
        boolean samplerSlotsFinalized,
        // The macros in force at the end of shaders.properties, option values included: Iris
        // expands them throughout the file, so an image may be COLORED_LIGHTING texels wide.
        Map<String, String> propertyMacros,
        // Resolved during the parse: propertyValues is sorted, and Iris applies these keys in file order.
        ParticleDirectives particleDirectives
) {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    // A directive's name follows its '#' directly: PropertiesPreprocessor reads "# define" as a comment.
    static final Pattern DEFINE = Pattern.compile(
            "^\\s*#define\\s+([A-Za-z_]\\w*)(?:\\s+(.*?))?\\s*$");
    static final Pattern COMMENTED_DEFINE = Pattern.compile(
            "^\\s*//\\s*#\\s*define\\s+([A-Za-z_]\\w*)(?:\\s+(.*?))?\\s*$");
    private static final Pattern CONST = Pattern.compile(
            "^\\s*const\\s+(int|float|bool)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+);\\s*(.*)$");
    private static final Pattern OPTION_VALUES = Pattern.compile("\\[([^]]*)]");
    private static final Pattern SAFE_OVERRIDE_VALUE = Pattern.compile(
            "[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?[fF]?|true|false",
            Pattern.CASE_INSENSITIVE);

    PackSettingsPlan {
        options = immutableOptions(options);
        defaults = immutableMap(defaults);
        propertyMacros = immutableMap(propertyMacros);
        profiles = immutableProfiles(profiles);
        propertyValues = immutableMap(propertyValues);
        programEnabled = programEnabled == null
                ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(programEnabled));
        requiredFeatures = immutableSet(requiredFeatures);
        optionalFeatures = immutableSet(optionalFeatures);
        unsupportedRequiredFeatures = immutableSet(unsupportedRequiredFeatures);
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        activeProfile = activeProfile == null || activeProfile.isBlank() ? "default" : activeProfile;
        runtimeSettings = runtimeSettings == null ? PackRuntimeSettings.empty() : runtimeSettings;
        resourceDeclarations = resourceDeclarations == null
                ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(resourceDeclarations));
        samplerSlots = samplerSlots == null
                ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(samplerSlots));
        particleDirectives = particleDirectives == null ? ParticleDirectives.DEFAULTS : particleDirectives;
    }

    static PackSettingsPlan empty() {
        return new PackSettingsPlan(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), Set.of(), List.of(), "default", PackRuntimeSettings.empty(),
                Map.of(), Map.of(), false, Map.of(), null);
    }

    static PackSettingsPlan parse(List<PackProgram> programs, Path shadersDir) {
        return parse(programs, shadersDir, systemOptionOverrides(), PackOptionEnvironment.defaults(shadersDir));
    }

    /**
     * Parses settings with load-time overrides for names the sources do not declare (declared
     * options are already edited into the sources) and what the pack's options bring to
     * shaders.properties: their values as macros and the programs the matching profile switches off.
     */
    static PackSettingsPlan parse(
            List<PackProgram> programs,
            Path shadersDir,
            Map<String, String> explicitOverrides,
            PackOptionEnvironment environment
    ) {
        Map<String, Option> options = new TreeMap<>();
        Map<String, String> defaults = new TreeMap<>();
        List<String> deviations = new ArrayList<>();
        // Only executable entry files can author global pack defaults. Helper
        // libraries contain implementation macros that must stay local to
        // the source that includes them.
        for (Map.Entry<String, String> file : entrySourceFiles(programs, shadersDir).entrySet()) {
            collectSourceOptions(file.getValue(), file.getKey(), options, defaults, deviations);
        }

        Map<String, String> overrides = normalizeOverrides(explicitOverrides, deviations);
        // Iris writes toggle options as true/false. On is the plain define,
        // which Chimera writes as "1" (an active bare source define is
        // empty); off means the macro is undefined, so it leaves the defaults
        // and is locked out of the source below (overriddenNames without a value).
        Set<String> disabled = applyToggleOverrides(overrides);
        for (Map.Entry<String, String> override : overrides.entrySet()) {
            options.put(override.getKey(), new Option(override.getKey(), "chimera.option",
                    override.getValue(), List.of(), "chimera.option"));
            deviations.add("PACK_OPTION_OVERRIDE:" + override.getKey());
        }
        overrides.keySet().removeAll(disabled);
        defaults.putAll(overrides);
        defaults.keySet().removeAll(disabled);

        Map<String, Profile> profiles = new TreeMap<>();
        // In file order until the plan sorts it: the particle directives depend on that order.
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, Boolean> programEnabled = new TreeMap<>();
        Set<String> required = new TreeSet<>();
        Set<String> optional = new TreeSet<>();
        List<PackRuntimeSettings.Declaration> customValues = new ArrayList<>();
        Map<String, PackResourceDeclaration> resourceDeclarations = new TreeMap<>();
        Path propertyFile = shadersDir == null ? null : shadersDir.resolve("shaders.properties");
        Map<String, String> macros = mergedConditionMacros(environment.macros(), overrides, disabled);
        if (propertyFile != null && Files.isRegularFile(propertyFile)) {
            Set<String> toggles = new TreeSet<>(environment.toggles());
            toggles.addAll(disabled);
            macros = ShadersPropertiesReader.read(propertyFile, options, defaults, profiles, properties, programEnabled,
                    required, optional, customValues, resourceDeclarations, deviations, macros, toggles);
            AdvancedImageDeclarations.dropImagesPastIrisLimit(properties, deviations);
            dropMissingTextures(resourceDeclarations, shadersDir, deviations);
        }
        // Iris drops a !program.NAME program of the matching profile whatever its own switch says.
        environment.disabledPrograms().forEach(program -> programEnabled.put(program, false));
        Set<String> unsupportedRequired = unsupportedFeatures(required, deviations);
        PackRuntimeSettings runtimeSettings = PackRuntimeSettings.build(
                customValues, deviations, BiomeIds.constants());
        return new PackSettingsPlan(options, defaults, profiles, properties, programEnabled,
                required, optional, unsupportedRequired, runtimeSettings.deviations(), "default",
                runtimeSettings, resourceDeclarations, Map.of(), false, macros,
                ParticleDirectives.fromOrderedProperties(properties));
    }

    /** The programs' fragment and vertex sources by relative path; the first program by name wins a path. */
    private static Map<String, String> entrySourceFiles(List<PackProgram> programs, Path shadersDir) {
        Map<String, String> sourceFiles = new TreeMap<>();
        if (programs == null) {
            return sourceFiles;
        }
        for (PackProgram program : programs.stream()
                .filter(value -> value != null)
                .sorted(Comparator.comparing(PackProgram::name))
                .toList()) {
            Path sourceRoot = program.sourceRoot() == null ? shadersDir : program.sourceRoot();
            if (program.fragmentSource() != null) {
                sourceFiles.putIfAbsent(relative(sourceRoot, program.fragmentPath()),
                        program.fragmentSource());
            }
            if (program.vertexSource() != null) {
                sourceFiles.putIfAbsent(relative(sourceRoot, program.vertexPath()),
                        program.vertexSource());
            }
        }
        return sourceFiles;
    }

    /** Turns a true override into the plain define and returns the names a false one turns off. */
    private static Set<String> applyToggleOverrides(Map<String, String> overrides) {
        Set<String> disabled = new TreeSet<>();
        for (Map.Entry<String, String> override : overrides.entrySet()) {
            if (override.getValue().equalsIgnoreCase("true")) override.setValue("1");
            else if (override.getValue().equalsIgnoreCase("false")) disabled.add(override.getKey());
        }
        return disabled;
    }

    /** The required features Chimera does not provide, each recorded as a deviation; Iris refuses the pack. */
    private static Set<String> unsupportedFeatures(Set<String> required, List<String> deviations) {
        Set<String> unsupported = PackFeatureFlags.unprovided(required);
        unsupported.forEach(feature -> deviations.add("REQUIRED_FEATURE_UNSUPPORTED:" + feature));
        return unsupported;
    }

    /**
     * The macros shaders.properties is read with, as Iris preprocesses it: the pack's option values,
     * Iris's standard environment with {@code IS_IRIS} and the features Chimera provides, and the JVM
     * overrides the pack does not declare (a false one leaves its name undefined).
     */
    private static Map<String, String> mergedConditionMacros(
            Map<String, String> optionMacros,
            Map<String, String> overrides,
            Set<String> disabled
    ) {
        Map<String, String> result = new TreeMap<>(optionMacros);
        result.putAll(overrides);
        result.keySet().removeAll(disabled);
        result.putAll(PackEngineDefines.standard());
        result.put(PackEngineDefines.IS_IRIS, "1");
        result.putAll(PackFeatureFlags.propertyMacros());
        return result;
    }

    private static Map<String, String> normalizeOverrides(
            Map<String, String> source,
            List<String> deviations
    ) {
        Map<String, String> result = new TreeMap<>();
        if (source == null) return result;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!name.matches("[A-Za-z_]\\w*") || !SAFE_OVERRIDE_VALUE.matcher(value).matches()) {
                if (!name.isBlank()) deviations.add("PACK_OPTION_OVERRIDE_UNSUPPORTED:" + name);
                continue;
            }
            result.put(name, value);
        }
        return result;
    }

    private static Map<String, String> systemOptionOverrides() {
        return PackOptionSources.systemOverrides();
    }

    /**
     * The launcher's option choices seed the shared shader preprocessor, with Iris's defines for the
     * optional features the pack lists and Chimera provides. Defines authored in an executable stage
     * are local to that variant. Those in shaders.properties stay inside that file: Iris preprocesses
     * it on its own (PropertiesPreprocessor), and nothing it defines reaches a program or an id map.
     */
    Map<String, String> preprocessorDefines() {
        Map<String, String> result = new TreeMap<>();
        defaults.forEach((name, value) -> {
            Option option = options.get(name);
            if (option != null && "chimera.option".equals(option.source())) {
                result.put(name, value);
            }
        });
        result.putAll(PackFeatureFlags.sourceDefines(optionalFeatures));
        // Iris adds the colour spaces, in ColorSpace's order, to the shader and id-map defines of a
        // pack that sets supportsColorCorrection (ShaderPack); currentColorSpace serves SRGB.
        if (supportsColorCorrection()) {
            result.putAll(COLOR_SPACES);
        }
        return stageIndependentDefaults(result);
    }

    private static final Map<String, String> COLOR_SPACES = Map.of("COLOR_SPACE_SRGB", "0",
            "COLOR_SPACE_DCI_P3", "1", "COLOR_SPACE_DISPLAY_P3", "2", "COLOR_SPACE_REC2020", "3",
            "COLOR_SPACE_ADOBE_RGB", "4");

    /**
     * Iris skips a custom texture whose file it cannot read ("Unable to read the custom texture",
     * ShaderPack.readTexture), as if it were never declared: a stage texture then leaves its
     * sampler to the program's own binding, a customTexture name reads unit 0, and a missing
     * texture.noise leaves the generated noise. A raw texture stays declared and refuses its
     * program, unlike Iris, which renames its sampler to customtexN and reads unit 0 there: a 1D or
     * 3D sampler on unit 0's 2D image is a type mismatch. An unsafe path stays declared too. Mellow
     * and Reverie name LUT files that only some LUT_FILE options ship.
     */
    private static void dropMissingTextures(Map<String, PackResourceDeclaration> declarations, Path shadersDir,
                                            List<String> deviations) {
        if (shadersDir == null) return;
        declarations.values().removeIf(declaration -> {
            if (declaration.kind() != PackResourceKind.PACK_TEXTURE && declaration.kind() != PackResourceKind.NOISE) {
                return false;
            }
            String source = declaration.source();
            if (PackResourcePlan.isUnsafePath(source) || PackResourcePlan.isNamespacedSource(source)
                    || PackResourcePlan.isHostBlockAtlasSource(source)
                    || PackResourcePlan.resolvePath(shadersDir, source) != null) {
                return false;
            }
            deviations.add("PACK_TEXTURE_DROPPED:" + declaration.key());
            return true;
        });
    }

    /** shaders.properties' supportsColorCorrection, which Iris reads as true or 1. */
    boolean supportsColorCorrection() {
        String value = propertyValues.get("supportsColorCorrection");
        return value != null && (value.trim().equals("true") || value.trim().equals("1"));
    }

    /**
     * The defines a prepared source keeps for the compiler as {@code #define} lines: the launcher's
     * option values, and the colour spaces, which Kappa reads as values ({@code case
     * COLOR_SPACE_SRGB:}) as well as in conditions.
     */
    Set<String> lockedDefineNames() {
        Set<String> names = new TreeSet<>(overriddenNames());
        if (supportsColorCorrection()) {
            names.addAll(COLOR_SPACES.keySet());
        }
        return Collections.unmodifiableSet(names);
    }

    /** Names whose values were explicitly supplied by the Chimera launcher. */
    Set<String> overriddenNames() {
        return options.entrySet().stream()
                .filter(entry -> "chimera.option".equals(entry.getValue().source()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean enabled(String name) {
        return programEnabled.getOrDefault(name, true);
    }

    /** Whether Iris reads the pack's tessellation stages: it lists TESSELLATION_SHADERS (ProgramSet). */
    boolean readsTessellation() {
        return hasFeature(PackFeatureFlags.TESSELLATION_SHADERS);
    }

    /** Iris ShaderPack.hasFeature: the pack lists the flag as required or optional. */
    boolean hasFeature(String flag) {
        return PackFeatureFlags.active(requiredFeatures, optionalFeatures, flag);
    }

    /**
     * Iris matches a switch to the program's path exactly: {@code program.world0/composite2.enabled}
     * (Photon's form) names the dimension folder's program, and a bare {@code program.composite2}
     * only the root one. Bliss switches a bare {@code composite5} that only its folders have.
     */
    boolean enabled(String folder, String name) {
        return enabled(folder == null || folder.isBlank() ? name : folder + "/" + name);
    }

    String snapshotJson() {
        JsonObject root = new JsonObject();
        root.add("options", optionsJson());
        root.add("defaults", stringMap(defaults));
        JsonObject profileJson = new JsonObject();
        profiles.forEach((name, profile) -> profileJson.addProperty(name, profile.expression()));
        root.add("profiles", profileJson);
        root.add("propertyValues", stringMap(propertyValues));
        JsonObject enabledJson = new JsonObject();
        programEnabled.forEach(enabledJson::addProperty);
        root.add("programEnabled", enabledJson);
        root.add("requiredFeatures", strings(requiredFeatures));
        root.add("optionalFeatures", strings(optionalFeatures));
        root.add("unsupportedRequiredFeatures", strings(unsupportedRequiredFeatures));
        root.add("deviations", strings(deviations));
        root.addProperty("activeProfile", activeProfile);
        root.addProperty("runtimeSettings", runtimeSettingsFingerprint());
        if (!resourceDeclarations.isEmpty()) {
            root.addProperty("resourceFingerprint", resourceFingerprint());
        }
        return JSON.toJson(root);
    }

    public PackRuntimeSettings runtimeSettings() {
        return runtimeSettings;
    }

    private String runtimeSettingsFingerprint() {
        StringBuilder value = new StringBuilder();
        runtimeSettings.customDescriptors().keySet().stream().sorted()
                .forEach(name -> value.append(name).append(';'));
        return ConformanceReport.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    String fingerprint() {
        return ConformanceReport.sha256(snapshotJson().getBytes(StandardCharsets.UTF_8));
    }

    List<String> resourceDeviations() {
        return resourceDeclarations.values().stream()
                .map(value -> "PACK_TEXTURE_DECLARED:" + value.key())
                .sorted().toList();
    }

    /** Returns the resolved live sampler slots, or the compatibility default before resolution. */
    Map<String, Integer> customSamplerSlots() {
        if (samplerSlotsFinalized) {
            return samplerSlots;
        }
        return defaultSamplerSlots();
    }

    /** Resolves only samplers proven live in the prepared source snapshot. */
    Map<String, Integer> customSamplerSlots(Set<String> liveSamplers) {
        Map<String, Integer> slots = new TreeMap<>();
        Set<Integer> reserved = reservedTargetSlots(liveSamplers);
        List<Integer> available = SelectorNamespace.PACK_SELECTOR_SLOTS.stream()
                .filter(slot -> !reserved.contains(slot)).toList();
        int next = 0;
        // Advanced image samplers are allocated first. They participate in
        // compute and graphics producer/consumer schedules, so a decorative
        // texture declaration must not starve a required image such as the
        // WSR or voxel sampler. The ordering remains deterministic by the
        // property key.
        for (Map.Entry<String, String> entry : propertyValues.entrySet()) {
            String sampler = imageSlotKey(entry);
            if (sampler == null) {
                continue;
            }
            String image = entry.getKey().substring("image.".length());
            if (slots.containsKey(sampler) || UniformRegistry.NAME_TO_SLOT.containsKey(sampler)
                    || liveSamplers == null
                    || (!liveSamplers.contains(sampler) && !liveSamplers.contains(image))) {
                continue;
            }
            if (next < available.size()) {
                slots.put(sampler, available.get(next++));
            }
        }
        for (PackResourceDeclaration declaration : resourceDeclarations.values().stream()
                .sorted(Comparator.comparing(PackResourceDeclaration::key)).toList()) {
            if (declaration.sampler().isBlank() || slots.containsKey(declaration.sampler())
                    || liveSamplers == null || !liveSamplers.contains(declaration.sampler())) {
                continue;
            }
            if (next < available.size()) {
                slots.put(declaration.sampler(), available.get(next++));
            }
        }
        return Collections.unmodifiableMap(slots);
    }

    /** The selector slots of the live colortex samplers, which no custom sampler may take. */
    private static Set<Integer> reservedTargetSlots(Set<String> liveSamplers) {
        Set<Integer> reserved = new TreeSet<>();
        if (liveSamplers == null) {
            return reserved;
        }
        for (String sampler : liveSamplers) {
            Integer target = PackResourcePlan.targetIndex(sampler);
            if (target != null && target <= PostTargetPlan.MAX_TARGET) {
                int slot = SelectorNamespace.colorTargetSlot(target);
                if (slot >= 0) reserved.add(slot);
            }
        }
        return reserved;
    }

    /** The slot key of an {@code image.<name>} entry (AdvancedImageDeclarations.slotKey), or null for any other. */
    private static String imageSlotKey(Map.Entry<String, String> entry) {
        if (!entry.getKey().startsWith("image.")) {
            return null;
        }
        String[] values = AdvancedImageDeclarations.values(entry.getValue());
        if (values.length == 0 || !values[0].matches("[A-Za-z_]\\w*")) {
            return null;
        }
        return AdvancedImageDeclarations.slotKey(entry.getKey().substring("image.".length()), values[0]);
    }

    private Map<String, Integer> defaultSamplerSlots() {
        Map<String, Integer> slots = new TreeMap<>();
        int next = 0;
        for (PackResourceDeclaration declaration : resourceDeclarations.values().stream()
                .sorted(Comparator.comparing(PackResourceDeclaration::key)).toList()) {
            if (declaration.sampler().isBlank() || slots.containsKey(declaration.sampler())) continue;
            if (next < SelectorNamespace.PACK_SELECTOR_SLOTS.size()) {
                slots.put(declaration.sampler(), SelectorNamespace.PACK_SELECTOR_SLOTS.get(next++));
            }
        }
        for (Map.Entry<String, String> entry : propertyValues.entrySet()) {
            String sampler = imageSlotKey(entry);
            if (sampler == null) continue;
            if (slots.containsKey(sampler) || UniformRegistry.NAME_TO_SLOT.containsKey(sampler)) continue;
            if (next < SelectorNamespace.PACK_SELECTOR_SLOTS.size()) {
                slots.put(sampler, SelectorNamespace.PACK_SELECTOR_SLOTS.get(next++));
            }
        }
        return Collections.unmodifiableMap(slots);
    }

    PackSettingsPlan withSamplerSlots(Map<String, Integer> resolved) {
        return new PackSettingsPlan(options, defaults, profiles, propertyValues, programEnabled,
                requiredFeatures, optionalFeatures, unsupportedRequiredFeatures, deviations,
                activeProfile, runtimeSettings, resourceDeclarations,
                resolved == null ? Map.of() : resolved, true, propertyMacros, particleDirectives);
    }

    private String resourceFingerprint() {
        StringBuilder value = new StringBuilder();
        resourceDeclarations.values().stream()
                .sorted(Comparator.comparing(PackResourceDeclaration::key))
                .forEach(declaration -> value.append(declaration.key()).append('|')
                        .append(declaration.stage()).append('|')
                        .append(declaration.sampler()).append('|')
                        .append(declaration.source()).append('\n'));
        return ConformanceReport.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void collectSourceOptions(
            String source,
            String file,
            Map<String, Option> options,
            Map<String, String> defaults,
            List<String> deviations
    ) {
        if (source == null) {
            return;
        }
        for (String line : source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            Matcher active = DEFINE.matcher(line);
            Matcher commented = COMMENTED_DEFINE.matcher(line);
            if (active.matches() || commented.matches()) {
                Matcher match = active.matches() ? active : commented;
                String name = match.group(1);
                if (name.matches("DRAWBUFFERS\\d+") || name.matches("RENDERTARGETS\\d+")) {
                    continue;
                }
                if (isStageContextMacro(name) || isDimensionContextMacro(name)) {
                    continue;
                }
                String value = match.group(2) == null || match.group(2).isBlank()
                        ? "1" : stripComment(match.group(2)).trim();
                List<String> values = valuesFrom(line);
                options.putIfAbsent(name, new Option(name, "define",
                        active.matches() ? value : "0", values, file));
                if (active.matches()) {
                    putDefault(name, value, defaults, deviations);
                }
                continue;
            }
            Matcher constant = CONST.matcher(line);
            if (constant.matches()) {
                String name = constant.group(2);
                String value = stripComment(constant.group(3)).trim();
                options.putIfAbsent(name, new Option(name, constant.group(1), value,
                        valuesFrom(constant.group(4)), file));
                putDefault(name, value, defaults, deviations);
            }
        }
    }

    private static void putDefault(
            String name,
            String value,
            Map<String, String> defaults,
            List<String> deviations
    ) {
        String previous = defaults.putIfAbsent(name, value);
        if (previous != null && !previous.equals(value)) {
            deviations.add("OPTION_CONFLICT:" + name);
        }
    }

    static List<String> valuesFrom(String line) {
        Matcher matcher = OPTION_VALUES.matcher(line == null ? "" : line);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : matcher.group(1).split("[\\s,]+")) {
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return result.stream().distinct().sorted().toList();
    }

    private static String stripComment(String value) {
        int comment = value.indexOf("//");
        return comment < 0 ? value : value.substring(0, comment);
    }

    /** The fullscreen and compute passes among Iris's texture stages, each covering its numbered programs. */
    static final List<String> PASS_TEXTURE_STAGES = List.of(
            "setup", "begin", "shadowcomp", "prepare", "deferred", "composite");

    /** Macros that name the stage or pass being compiled, never a pack-wide option. */
    private static final Set<String> STAGE_CONTEXT_MACROS = Set.of(
            "FSH", "VSH", "GSH", "CSH", "TCS", "TES", "VERTEX_SHADER", "FRAGMENT_SHADER", "GEOMETRY_SHADER",
            "COMPUTE_SHADER", "SHADOW", "COMPOSITE", "DEFERRED", "FINAL", "SETUP");
    private static final Pattern STAGE_CONTEXT_MACRO_FAMILIES = Pattern.compile(
            "COMPOSITE\\d+|DEFERRED\\d+|GBUFFERS_[A-Za-z0-9_]+|DH_[A-Za-z0-9_]+");

    static boolean isStageContextMacro(String name) {
        return STAGE_CONTEXT_MACROS.contains(name) || STAGE_CONTEXT_MACRO_FAMILIES.matcher(name).matches();
    }

    /**
     * The dimension a world folder's programs name for themselves (world0 defines OVERWORLD). Like the
     * stage macros, its define holds for those files only. Chimera applies option defaults to every
     * program and to shaders.properties, so it must not become a pack-wide default. Iris edits an option's
     * line in place and injects no defaults, so it needs no such rule (OptionAnnotatedSource).
     */
    static boolean isDimensionContextMacro(String name) {
        return name.equals("OVERWORLD") || name.equals("NETHER") || name.equals("END");
    }

    static Map<String, String> stageIndependentDefaults(Map<String, String> source) {
        Map<String, String> result = new TreeMap<>();
        if (source != null) {
            source.forEach((name, value) -> {
                if (!isStageContextMacro(name) && !isDimensionContextMacro(name)) {
                    result.put(name, value);
                }
            });
        }
        return Collections.unmodifiableMap(result);
    }

    private static String relative(Path root, Path path) {
        if (path == null) {
            return "";
        }
        if (root == null) {
            return path.getFileName() == null ? "" : path.getFileName().toString().replace('\\', '/');
        }
        try {
            return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (RuntimeException ignored) {
            return path.getFileName() == null ? "" : path.getFileName().toString();
        }
    }

    private JsonArray optionsJson() {
        JsonArray result = new JsonArray();
        options.values().stream().sorted(Comparator.comparing(Option::name)).forEach(option -> {
            JsonObject value = new JsonObject();
            value.addProperty("name", option.name());
            value.addProperty("type", option.type());
            value.addProperty("defaultValue", option.defaultValue());
            value.add("values", strings(option.values()));
            value.addProperty("source", option.source());
            result.add(value);
        });
        return result;
    }

    private static JsonObject stringMap(Map<String, String> values) {
        JsonObject result = new JsonObject();
        new TreeMap<>(values).forEach(result::addProperty);
        return result;
    }

    private static JsonArray strings(Iterable<String> values) {
        JsonArray result = new JsonArray();
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            if (value != null) {
                sorted.add(value);
            }
        }
        sorted.forEach(result::add);
        return result;
    }

    private static Map<String, Option> immutableOptions(Map<String, Option> values) {
        return values == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(values));
    }

    private static Map<String, Profile> immutableProfiles(Map<String, Profile> values) {
        return values == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(values));
    }

    private static Map<String, String> immutableMap(Map<String, String> values) {
        return values == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(values));
    }

    private static Set<String> immutableSet(Set<String> values) {
        return values == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(values));
    }

    record Option(String name, String type, String defaultValue, List<String> values, String source) {
        Option {
            values = values == null ? List.of() : values.stream().distinct().sorted().toList();
            source = source == null ? "" : source;
        }
    }

    record Profile(String name, String expression) {
        Profile {
            expression = expression == null ? "" : expression.trim();
        }
    }

}
