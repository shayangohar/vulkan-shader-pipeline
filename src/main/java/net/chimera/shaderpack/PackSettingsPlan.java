package net.chimera.shaderpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Immutable defaults-only interpretation of shaders.properties and pack options. */
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
        Map<String, String> propertyMacros
) {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern DEFINE = Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_]\\w*)(?:\\s+(.*?))?\\s*$");
    private static final Pattern COMMENTED_DEFINE = Pattern.compile(
            "^\\s*//\\s*#\\s*define\\s+([A-Za-z_]\\w*)(?:\\s+(.*?))?\\s*$");
    private static final Pattern CONST = Pattern.compile(
            "^\\s*const\\s+(int|float|bool)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+);\\s*(.*)$");
    private static final Pattern PROFILE = Pattern.compile(
            "^profile\\.([A-Za-z_]\\w*)\\s*=\\s*(.*)$");
    private static final String PROGRAM_PREFIX = "program.";
    private static final Pattern FEATURES = Pattern.compile(
            "^iris\\.features\\.(required|optional)\\s*=\\s*(.*)$");
    private static final Pattern CUSTOM_VALUE = Pattern.compile(
            "^\\s*(uniform|variable)\\.(float|int|bool|vec2|vec3|vec4)\\.([A-Za-z_]\\w*)\\s*=\\s*(.*)$");
    private static final Pattern CONDITION = Pattern.compile(
            "^\\s*#\\s*(if|ifdef|ifndef|elif|else|endif)\\b(.*)$");
    private static final Pattern OPTION_VALUES = Pattern.compile("\\[([^]]*)]");
    private static final Set<String> SUPPORTED_FEATURES = Set.of(
            "LEGACY_GLSL", "COMPOSITE", "GBUFFERS", "SHADOWS", "WATER", "ENTITIES");
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
    }

    static PackSettingsPlan empty() {
        return new PackSettingsPlan(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), Set.of(), List.of(), "default", PackRuntimeSettings.empty(),
                Map.of(), Map.of(), false, Map.of());
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
        // Iris writes toggle options as true/false. On is the plain define
        // (Chimera's "1", as for an active source define); off means the
        // macro is undefined, so it leaves the defaults and is locked out of
        // the source below (overriddenNames without a value).
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
        Map<String, String> properties = new TreeMap<>();
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
            macros = parseProperties(propertyFile, options, defaults, profiles, properties, programEnabled,
                    required, optional, customValues, resourceDeclarations, deviations, macros, toggles);
        }
        // Iris drops a !program.NAME program of the matching profile whatever its own switch says.
        environment.disabledPrograms().forEach(program -> programEnabled.put(program, false));
        Set<String> unsupportedRequired = unsupportedFeatures(required, deviations);
        PackRuntimeSettings runtimeSettings = PackRuntimeSettings.build(
                customValues, defaults, deviations, BiomeIds.constants());
        return new PackSettingsPlan(options, defaults, profiles, properties, programEnabled,
                required, optional, unsupportedRequired, runtimeSettings.deviations(), "default",
                runtimeSettings, resourceDeclarations, Map.of(), false, macros);
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

    /** The required features Chimera does not support, each recorded as a deviation. */
    private static Set<String> unsupportedFeatures(Set<String> required, List<String> deviations) {
        Set<String> unsupported = new TreeSet<>();
        for (String feature : required) {
            if (!SUPPORTED_FEATURES.contains(feature.toUpperCase())) {
                unsupported.add(feature);
                deviations.add("REQUIRED_FEATURE_UNSUPPORTED:" + feature);
            }
        }
        return unsupported;
    }

    /**
     * The macros shaders.properties is read with, as Iris preprocesses it: the pack's option values,
     * Iris's standard environment with {@code IS_IRIS}, and the JVM overrides the pack does not
     * declare (a false one leaves its name undefined).
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

    /** Active defaults seed the shared shader preprocessor. */
    Map<String, String> preprocessorDefines() {
        Map<String, String> result = new TreeMap<>();
        defaults.forEach((name, value) -> {
            Option option = options.get(name);
            // Defines authored in an executable stage are local to that
            // variant. Only explicit property defines are pack-wide inputs.
            if (option != null && ("shaders.properties".equals(option.source())
                    || "chimera.option".equals(option.source()))) {
                result.put(name, value);
            }
        });
        return stageIndependentDefaults(result);
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
        value.append(runtimeSettings.wetnessRiseHalfLife()).append('|')
                .append(runtimeSettings.wetnessFallHalfLife()).append('|')
                .append(runtimeSettings.eyeBrightnessHalfLife()).append('|');
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
            String sampler = imageSampler(entry);
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

    /** The sampler an {@code image.<name>} entry names first, or null for any other or malformed entry. */
    private static String imageSampler(Map.Entry<String, String> entry) {
        if (!entry.getKey().startsWith("image.")) {
            return null;
        }
        String[] values = entry.getValue() == null
                ? new String[0] : entry.getValue().trim().split("\\s+");
        if (values.length == 0 || !values[0].matches("[A-Za-z_]\\w*")) {
            return null;
        }
        return values[0];
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
            String sampler = imageSampler(entry);
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
                resolved == null ? Map.of() : resolved, true, propertyMacros);
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

    /** Parses shaders.properties into the given maps and returns the macros in force at its end. */
    private static Map<String, String> parseProperties(
            Path propertyFile,
            Map<String, Option> options,
            Map<String, String> defaults,
            Map<String, Profile> profiles,
            Map<String, String> values,
            Map<String, Boolean> programEnabled,
            Set<String> required,
            Set<String> optional,
            List<PackRuntimeSettings.Declaration> customValues,
            Map<String, PackResourceDeclaration> resourceDeclarations,
            List<String> deviations,
            Map<String, String> initialMacros,
            Set<String> toggleOptions
    ) {
        PropertiesParse parse = new PropertiesParse(propertyFile, options, defaults, profiles, values,
                programEnabled, required, optional, customValues, resourceDeclarations, deviations, toggleOptions);
        // Iris's macro set is taken whole: SHADOW or DH_TERRAIN may be the pack's own options.
        parse.run(initialMacros == null ? stageIndependentDefaults(defaults) : initialMacros);
        return parse.conditions.macros();
    }

    /** One pass over shaders.properties: its conditional blocks, defines and key=value entries. */
    private static final class PropertiesParse {
        private final Path propertyFile;
        private final Map<String, Option> options;
        private final Map<String, String> defaults;
        private final Map<String, Profile> profiles;
        private final Map<String, String> values;
        private final Map<String, Boolean> programEnabled;
        private final Set<String> required;
        private final Set<String> optional;
        private final List<PackRuntimeSettings.Declaration> customValues;
        private final Map<String, PackResourceDeclaration> resourceDeclarations;
        private final List<String> deviations;
        private final Set<String> toggleOptions;
        private PackConditionals.State conditions;
        private boolean invalidCondition;

        PropertiesParse(Path propertyFile, Map<String, Option> options, Map<String, String> defaults,
                        Map<String, Profile> profiles, Map<String, String> values,
                        Map<String, Boolean> programEnabled, Set<String> required, Set<String> optional,
                        List<PackRuntimeSettings.Declaration> customValues,
                        Map<String, PackResourceDeclaration> resourceDeclarations, List<String> deviations,
                        Set<String> toggleOptions) {
            this.propertyFile = propertyFile;
            this.options = options;
            this.defaults = defaults;
            this.profiles = profiles;
            this.values = values;
            this.programEnabled = programEnabled;
            this.required = required;
            this.optional = optional;
            this.customValues = customValues;
            this.resourceDeclarations = resourceDeclarations;
            this.deviations = deviations;
            this.toggleOptions = toggleOptions;
        }

        void run(Map<String, String> initialMacros) {
            conditions = new PackConditionals.State(initialMacros);
            try {
                // Iris reads shaders.properties as ISO-8859-1, which never fails on a stray byte.
                List<String> physical = Files.readAllLines(propertyFile, StandardCharsets.ISO_8859_1);
                PropertyLineJoiner joiner = new PropertyLineJoiner();
                for (int index = 0; index <= physical.size(); index++) {
                    String raw = index < physical.size() ? physical.get(index) : null;
                    String line;
                    if (raw != null && PropertyLineJoiner.isDirective(raw) && joiner.pending()) {
                        // The directive ends the open continuation: handle that line first,
                        // then revisit the directive.
                        line = joiner.finish();
                        index--;
                    } else if (raw == null) {
                        line = joiner.finish();
                    } else if (applyCondition(raw) || invalidCondition || !conditions.active()) {
                        continue;
                    } else {
                        line = PropertyLineJoiner.isDirective(raw) ? raw : joiner.accept(raw);
                    }
                    if (line != null) {
                        accept(line);
                    }
                }
                if (!invalidCondition) {
                    try {
                        conditions.finish();
                    } catch (RuntimeException failure) {
                        deviations.add("PREPROCESSOR_CONDITION_UNSUPPORTED");
                    }
                }
            } catch (IOException e) {
                deviations.add("SHADERS_PROPERTIES_READ_FAILED");
            }
        }

        /** Applies an #if, #ifdef, #else or #endif line; false when the line is not one. */
        private boolean applyCondition(String raw) {
            Matcher directive = CONDITION.matcher(raw);
            if (!directive.matches()) {
                return false;
            }
            if (!invalidCondition) {
                try {
                    conditions.apply(directive.group(1), directive.group(2).trim());
                } catch (RuntimeException failure) {
                    deviations.add("PREPROCESSOR_CONDITION_UNSUPPORTED");
                    conditions = new PackConditionals.State(stageIndependentDefaults(defaults));
                    invalidCondition = true;
                }
            }
            return true;
        }

        /** One logical line: a define, a commented-out define, a comment, or a key=value entry. */
        private void accept(String line) {
            if (acceptDefine(line) || line.trim().startsWith("#")) {
                return;
            }
            int equals = line.indexOf('=');
            if (equals < 1) {
                return;
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (key.isBlank()) {
                return;
            }
            values.put(key, value);
            acceptEntry(line, key, value);
        }

        /** A #define declares an option and its default; a commented-out one declares it off. */
        private boolean acceptDefine(String line) {
            Matcher define = DEFINE.matcher(line);
            if (define.matches()) {
                String name = define.group(1);
                if (isStageContextMacro(name) || isDimensionContextMacro(name)) {
                    return true;
                }
                String value = define.group(2) == null || define.group(2).isBlank()
                        ? "1" : stripComment(define.group(2)).trim();
                options.putIfAbsent(name, new Option(name, "define", value, valuesFrom(line),
                        propertyFile.getFileName().toString()));
                defaults.put(name, value);
                conditions.define(name, value);
                return true;
            }
            Matcher commentedDefine = COMMENTED_DEFINE.matcher(line);
            if (!commentedDefine.matches()) {
                return false;
            }
            String name = commentedDefine.group(1);
            options.putIfAbsent(name, new Option(name, "define", "0", valuesFrom(line),
                    propertyFile.getFileName().toString()));
            return true;
        }

        /** A key=value entry: a resource, profile, program switch, feature list, custom value, or unsupported. */
        private void acceptEntry(String line, String key, String value) {
            PackResourceDeclaration resource = resourceDeclaration(key, value);
            if (resource != null) {
                resourceDeclarations.put(resource.key(), resource);
                return;
            }
            Matcher profile = PROFILE.matcher(line);
            if (profile.matches()) {
                profiles.put(profile.group(1), new Profile(profile.group(1), value));
                return;
            }
            String program = switchedProgram(key);
            if (program != null) {
                acceptProgramSwitch(program, value.trim());
                return;
            }
            Matcher feature = FEATURES.matcher(line);
            if (feature.matches()) {
                Set<String> destination = feature.group(1).equals("required") ? required : optional;
                destination.clear();
                for (String token : feature.group(2).split("[\\s,]+")) {
                    if (!token.isBlank()) {
                        destination.add(token);
                    }
                }
                return;
            }
            Matcher custom = CUSTOM_VALUE.matcher(line);
            if (custom.matches()) {
                customValues.add(PackRuntimeSettings.declaration(
                        custom.group(1).equals("uniform"), custom.group(2),
                        custom.group(3), custom.group(4).trim()));
                return;
            }
            if (key.startsWith("image.") || key.startsWith("customImage.")) {
                deviations.add("CUSTOM_IMAGE_UNSUPPORTED:" + key);
            } else if (key.startsWith("bufferObject.")) {
                // Bounded bufferObject.N declarations are admitted. The
                // source plan remains responsible for proving that a
                // matching std430 block and supported schedule exist.
                deviations.add("STORAGE_BUFFER_DECLARED:" + key);
            } else if (key.startsWith("ssbo.") || key.startsWith("storage.")) {
                deviations.add("STORAGE_RESOURCE_UNSUPPORTED:" + key);
            } else if (!isSupportedProperty(key)) {
                deviations.add("SETTING_UNSUPPORTED:" + key);
            }
        }

        /**
         * The program a {@code program.<name>.<anything>} key switches: Iris takes the name up to the
         * first dot after {@code program.} and any suffix (Solas writes
         * {@code program.world0/deferred..enabled}). Null for any other key.
         */
        private static String switchedProgram(String key) {
            int end = key.indexOf('.', PROGRAM_PREFIX.length());
            return key.startsWith(PROGRAM_PREFIX) && end > PROGRAM_PREFIX.length()
                    ? key.substring(PROGRAM_PREFIX.length(), end) : null;
        }

        /** A {@code program.<name>.enabled} switch, evaluated against the pack's option values. */
        private void acceptProgramSwitch(String program, String expression) {
            try {
                // Iris evaluates the switch against the pack's option values: a toggle
                // the pack declares but leaves off (`//#define DOF`) is false. Only a
                // name that is no option at all keeps the program enabled.
                Map<String, String> switches = new HashMap<>(conditions.macros());
                for (String toggle : toggleOptions) {
                    switches.putIfAbsent(toggle, "0");
                }
                programEnabled.put(program, PackConditionals.evaluate(expression, switches, true));
            } catch (RuntimeException failure) {
                programEnabled.put(program, true);
                deviations.add("PROGRAM_ENABLE_EXPRESSION_UNSUPPORTED:" + program);
            }
        }
    }

    private static final Pattern PREPROCESSOR_LINE = Pattern.compile(
            "^#\\s*(?:define|undef|include|line|pragma|error|warning|if|ifdef|ifndef|elif|else|endif)\\b");

    /**
     * Joins backslash continuations the way Iris's PropertiesPreprocessor and Properties
     * loader do. Iris drops blank and plain {@code #} comment lines before preprocessing,
     * so neither ends a continuation. Preprocessing then turns every directive line into
     * an empty line, which does end one: Photon closes a multi-line
     * {@code moon_phase_brightness} with {@code ") \"} right before its {@code #else}, and
     * Bliss's conditioned {@code block.56} ends at its {@code #endif}. Callers evaluate
     * conditions on physical lines, end an open continuation at each directive line, and
     * feed only active non-directive lines to {@link #accept}.
     */
    static final class PropertyLineJoiner {
        private final StringBuilder pending = new StringBuilder();

        /** A preprocessor directive line, which ends an open continuation. */
        static boolean isDirective(String raw) {
            return PREPROCESSOR_LINE.matcher(raw.strip()).find();
        }

        boolean pending() {
            return pending.length() > 0;
        }

        /**
         * The completed logical line, or null while a continuation is open or the line is dropped.
         * Leading whitespace goes, as Properties.load skips it: Photon indents its
         * {@code program.<world>/composite17.enabled} switches inside {@code #ifdef}.
         */
        String accept(String raw) {
            String trimmed = raw.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null;
            if (trimmed.endsWith("\\")) {
                pending.append(trimmed, 0, trimmed.length() - 1).append(' ');
                return null;
            }
            if (pending.length() == 0) return trimmed;
            String line = pending + trimmed;
            pending.setLength(0);
            return line;
        }

        /** The open continuation as one logical line, at a directive line or the end of the file. */
        String finish() {
            if (pending.length() == 0) return null;
            String line = pending.toString();
            pending.setLength(0);
            return line;
        }
    }

    /** Property key prefixes Chimera reads; "shadow" covers every shadow setting. */
    private static final List<String> SUPPORTED_PROPERTY_PREFIXES = List.of(
            "profile.", "program.", "iris.features.", "size.buffer.colortex", "flip.", "customTexture.",
            "texture.", "alphaTest.", "shadow");
    private static final Set<String> SUPPORTED_PROPERTY_NAMES = Set.of("sunPathRotation", "sunPathOffset");
    private static final Pattern SUPPORTED_TARGET_PROPERTY = Pattern.compile(
            "colortex\\d+(?:Format|Clear|ClearColor|MipmapEnabled)");

    private static boolean isSupportedProperty(String key) {
        return SUPPORTED_PROPERTY_PREFIXES.stream().anyMatch(key::startsWith)
                || SUPPORTED_PROPERTY_NAMES.contains(key)
                || SUPPORTED_TARGET_PROPERTY.matcher(key).matches();
    }

    private static PackResourceDeclaration resourceDeclaration(String key, String value) {
        // Iris: a value with more than one word is a raw texture (file, type, format, size).
        PackResourceKind textureKind = value.trim().split("\\s+").length > 1
                ? PackResourceKind.RAW_TEXTURE : PackResourceKind.PACK_TEXTURE;
        if (key.startsWith("customTexture.")) {
            String sampler = key.substring("customTexture.".length()).trim();
            return sampler.isBlank() ? null
                    : new PackResourceDeclaration(key, "*", sampler, value, textureKind);
        }
        if (key.equals("texture.noise")) {
            return new PackResourceDeclaration(key, "*", "noisetex", value, PackResourceKind.NOISE);
        }
        if (key.startsWith("texture.")) {
            String[] parts = key.split("\\.", 3);
            if (parts.length == 3 && !parts[1].isBlank() && !parts[2].isBlank()) {
                // Iris keeps only the sampler name: `texture.deferred.colortex6.1` declares a
                // second texture for colortex6, told apart from the first by its type.
                String sampler = parts[2].split("\\.")[0];
                return sampler.isBlank() ? null
                        : new PackResourceDeclaration(key, parts[1], sampler, value, textureKind);
            }
        }
        return null;
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

    private static List<String> valuesFrom(String line) {
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

    /** Macros that name the stage or pass being compiled, never a pack-wide option. */
    private static final Set<String> STAGE_CONTEXT_MACROS = Set.of(
            "FSH", "VSH", "GSH", "CSH", "TCS", "TES", "VERTEX_SHADER", "FRAGMENT_SHADER", "GEOMETRY_SHADER",
            "COMPUTE_SHADER", "SHADOW", "COMPOSITE", "DEFERRED", "FINAL", "SETUP");
    private static final Pattern STAGE_CONTEXT_MACRO_FAMILIES = Pattern.compile(
            "COMPOSITE\\d+|DEFERRED\\d+|GBUFFERS_[A-Za-z0-9_]+|DH_[A-Za-z0-9_]+");

    private static boolean isStageContextMacro(String name) {
        return STAGE_CONTEXT_MACROS.contains(name) || STAGE_CONTEXT_MACRO_FAMILIES.matcher(name).matches();
    }

    private static boolean isDimensionContextMacro(String name) {
        return name.equals("OVERWORLD") || name.equals("NETHER") || name.equals("END");
    }

    private static Map<String, String> stageIndependentDefaults(Map<String, String> source) {
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
