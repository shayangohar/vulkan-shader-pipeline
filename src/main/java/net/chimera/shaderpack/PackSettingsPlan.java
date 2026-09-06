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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        Map<String, PackResourceDeclaration> resourceDeclarations
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
    private static final Pattern PROGRAM_ENABLED = Pattern.compile(
            "^program\\.(.+)\\.enabled\\s*=\\s*(.*)$");
    private static final Pattern FEATURES = Pattern.compile(
            "^iris\\.features\\.(required|optional)\\s*=\\s*(.*)$");
    private static final Pattern CUSTOM_VALUE = Pattern.compile(
            "^\\s*(uniform|variable)\\.(float|int|bool)\\.([A-Za-z_]\\w*)\\s*=\\s*(.*)$");
    private static final Pattern CONDITION = Pattern.compile(
            "^#\\s*(if|ifdef|ifndef|elif|else|endif)\\b(.*)$");
    private static final Pattern OPTION_VALUES = Pattern.compile("\\[([^]]*)]");
    private static final Set<String> SUPPORTED_FEATURES = Set.of(
            "LEGACY_GLSL", "COMPOSITE", "GBUFFERS", "SHADOWS", "WATER", "ENTITIES");

    PackSettingsPlan {
        options = immutableOptions(options);
        defaults = immutableMap(defaults);
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
    }

    /** Compatibility constructor for the M7.1 settings shape. */
    PackSettingsPlan(
            Map<String, Option> options,
            Map<String, String> defaults,
            Map<String, Profile> profiles,
            Map<String, String> propertyValues,
            Map<String, Boolean> programEnabled,
            Set<String> requiredFeatures,
            Set<String> optionalFeatures,
            Set<String> unsupportedRequiredFeatures,
            List<String> deviations,
            String activeProfile
    ) {
        this(options, defaults, profiles, propertyValues, programEnabled,
                requiredFeatures, optionalFeatures, unsupportedRequiredFeatures,
                deviations, activeProfile, PackRuntimeSettings.empty(), Map.of());
    }

    /** Compatibility constructor for callers using the M7.2 settings shape. */
    PackSettingsPlan(
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
            PackRuntimeSettings runtimeSettings
    ) {
        this(options, defaults, profiles, propertyValues, programEnabled,
                requiredFeatures, optionalFeatures, unsupportedRequiredFeatures,
                deviations, activeProfile, runtimeSettings, Map.of());
    }

    static PackSettingsPlan empty() {
        return new PackSettingsPlan(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), Set.of(), List.of(), "default", PackRuntimeSettings.empty(), Map.of());
    }

    static PackSettingsPlan parse(List<PackProgram> programs, Path shadersDir) {
        Map<String, Option> options = new TreeMap<>();
        Map<String, String> defaults = new TreeMap<>();
        List<String> deviations = new ArrayList<>();
        Map<String, String> sourceFiles = new TreeMap<>();
        if (programs != null) {
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
        }
        // Only executable entry files can author global pack defaults. Helper
        // libraries contain implementation macros that must stay local to
        // the source that includes them.
        for (Map.Entry<String, String> file : sourceFiles.entrySet()) {
            collectSourceOptions(file.getValue(), file.getKey(), options, defaults, deviations);
        }

        Map<String, Profile> profiles = new TreeMap<>();
        Map<String, String> properties = new TreeMap<>();
        Map<String, Boolean> programEnabled = new TreeMap<>();
        Set<String> required = new TreeSet<>();
        Set<String> optional = new TreeSet<>();
        List<PackRuntimeSettings.Declaration> customValues = new ArrayList<>();
        Map<String, PackResourceDeclaration> resourceDeclarations = new TreeMap<>();
        Path propertyFile = shadersDir == null ? null : shadersDir.resolve("shaders.properties");
        if (propertyFile != null && Files.isRegularFile(propertyFile)) {
            parseProperties(propertyFile, options, defaults, profiles, properties, programEnabled,
                    required, optional, customValues, resourceDeclarations, deviations);
        }
        Set<String> unsupportedRequired = new TreeSet<>();
        for (String feature : required) {
            if (!SUPPORTED_FEATURES.contains(feature.toUpperCase())) {
                unsupportedRequired.add(feature);
                deviations.add("REQUIRED_FEATURE_UNSUPPORTED:" + feature);
            }
        }
        PackRuntimeSettings runtimeSettings = PackRuntimeSettings.build(customValues, defaults, deviations);
        return new PackSettingsPlan(options, defaults, profiles, properties, programEnabled,
                required, optional, unsupportedRequired, runtimeSettings.deviations(), "default",
                runtimeSettings, resourceDeclarations);
    }

    /** Active defaults seed the shared shader preprocessor. */
    Map<String, String> preprocessorDefines() {
        return defaults;
    }

    boolean enabled(String name) {
        return programEnabled.getOrDefault(name, true);
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

    Map<String, Integer> customSamplerSlots() {
        Map<String, Integer> slots = new TreeMap<>();
        int next = PackResourcePlan.PACK_SLOT_FIRST;
        for (PackResourceDeclaration declaration : resourceDeclarations.values().stream()
                .sorted(Comparator.comparing(PackResourceDeclaration::key)).toList()) {
            if (declaration.sampler().isBlank() || slots.containsKey(declaration.sampler())) {
                continue;
            }
            if (next <= PackResourcePlan.PACK_SLOT_LAST) {
                slots.put(declaration.sampler(), next++);
            }
        }
        return Collections.unmodifiableMap(slots);
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

    private static void parseProperties(
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
            List<String> deviations
    ) {
        PackConditionals.State conditions = new PackConditionals.State(defaults);
        boolean invalidCondition = false;
        try {
            for (String line : Files.readAllLines(propertyFile, StandardCharsets.UTF_8)) {
                Matcher directive = CONDITION.matcher(line);
                if (directive.matches()) {
                    if (!invalidCondition) {
                        try {
                            conditions.apply(directive.group(1), directive.group(2).trim());
                        } catch (RuntimeException failure) {
                            deviations.add("PREPROCESSOR_CONDITION_UNSUPPORTED");
                            conditions = new PackConditionals.State(defaults);
                            invalidCondition = true;
                        }
                    }
                    continue;
                }
                if (invalidCondition) {
                    continue;
                }
                if (!conditions.active()) {
                    continue;
                }
                Matcher define = DEFINE.matcher(line);
                if (define.matches()) {
                    String name = define.group(1);
                    String value = define.group(2) == null || define.group(2).isBlank()
                            ? "1" : stripComment(define.group(2)).trim();
                    options.putIfAbsent(name, new Option(name, "define", value, valuesFrom(line),
                            propertyFile.getFileName().toString()));
                    defaults.put(name, value);
                    conditions.define(name, value);
                    continue;
                }
                Matcher commentedDefine = COMMENTED_DEFINE.matcher(line);
                if (commentedDefine.matches()) {
                    String name = commentedDefine.group(1);
                    options.putIfAbsent(name, new Option(name, "define", "0", valuesFrom(line),
                            propertyFile.getFileName().toString()));
                    continue;
                }
                if (line.trim().startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals < 1) {
                    continue;
                }
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                if (key.isBlank()) {
                    continue;
                }
                values.put(key, value);
                PackResourceDeclaration resource = resourceDeclaration(key, value);
                if (resource != null) {
                    resourceDeclarations.put(resource.key(), resource);
                    continue;
                }
                Matcher profile = PROFILE.matcher(line);
                if (profile.matches()) {
                    profiles.put(profile.group(1), new Profile(profile.group(1), value));
                    continue;
                }
                Matcher enabled = PROGRAM_ENABLED.matcher(line);
                if (enabled.matches()) {
                    try {
                        programEnabled.put(enabled.group(1), PackConditionals.evaluate(
                                enabled.group(2).trim(), conditions.macros(), true));
                    } catch (RuntimeException failure) {
                        programEnabled.put(enabled.group(1), true);
                        deviations.add("PROGRAM_ENABLE_EXPRESSION_UNSUPPORTED:" + enabled.group(1));
                    }
                    continue;
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
                    continue;
                }
                Matcher custom = CUSTOM_VALUE.matcher(line);
                if (custom.matches()) {
                    customValues.add(PackRuntimeSettings.declaration(
                            custom.group(1).equals("uniform"), custom.group(2),
                            custom.group(3), custom.group(4).trim()));
                    continue;
                }
                if (key.startsWith("image.") || key.startsWith("customImage.")) {
                    deviations.add("CUSTOM_IMAGE_UNSUPPORTED:" + key);
                    continue;
                }
                if (key.startsWith("bufferObject.") || key.startsWith("ssbo.")
                        || key.startsWith("storage.")) {
                    deviations.add("STORAGE_RESOURCE_UNSUPPORTED:" + key);
                    continue;
                }
                if (!isSupportedProperty(key)) {
                    deviations.add("SETTING_UNSUPPORTED:" + key);
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

    private static boolean isSupportedProperty(String key) {
        return key.startsWith("profile.") || key.startsWith("program.")
                || key.startsWith("iris.features.") || key.matches("colortex\\d+Format")
                || key.matches("colortex\\d+Clear") || key.matches("colortex\\d+ClearColor")
                || key.matches("colortex\\d+MipmapEnabled")
                || key.startsWith("size.buffer.colortex") || key.startsWith("flip.")
                || key.startsWith("customTexture.") || key.startsWith("texture.")
                || key.startsWith("shadow") || key.equals("sunPathRotation")
                || key.equals("sunPathOffset") || key.equals("shadowMapResolution")
                || key.equals("shadowDistance");
    }

    private static PackResourceDeclaration resourceDeclaration(String key, String value) {
        if (key.startsWith("customTexture.")) {
            String sampler = key.substring("customTexture.".length()).trim();
            return sampler.isBlank() ? null
                    : new PackResourceDeclaration(key, "*", sampler, value, PackResourceKind.PACK_TEXTURE);
        }
        if (key.equals("texture.noise")) {
            return new PackResourceDeclaration(key, "*", "noisetex", value, PackResourceKind.NOISE);
        }
        if (key.startsWith("texture.")) {
            String[] parts = key.split("\\.", 3);
            if (parts.length == 3 && !parts[1].isBlank() && !parts[2].isBlank()) {
                return new PackResourceDeclaration(key, parts[1], parts[2], value,
                        PackResourceKind.PACK_TEXTURE);
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

    private static boolean isStageContextMacro(String name) {
        return name.equals("FSH") || name.equals("VSH") || name.equals("GSH")
                || name.equals("CSH") || name.equals("TCS") || name.equals("TES")
                || name.equals("VERTEX_SHADER") || name.equals("FRAGMENT_SHADER")
                || name.equals("GEOMETRY_SHADER") || name.equals("COMPUTE_SHADER");
    }

    private static boolean isDimensionContextMacro(String name) {
        return name.equals("OVERWORLD") || name.equals("NETHER") || name.equals("END");
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
