package net.chimera.shaderpack;

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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable load-time resource plan shared by probing, pipelines, and runtime. */
public final class PackResourcePlan {
    /** Slot 14 is reserved by the runtime for pack coverage. */
    public static final int COVERAGE_SLOT = 14;
    /** Slot 15 is the distinct post shadowtex1 selector. */
    public static final int SHADOW_TEX1_SLOT = 15;
    /** Pack-owned sampled resources must not overlap reserved selectors. */
    public static final int PACK_SLOT_FIRST = 16;
    public static final int PACK_SLOT_LAST = 21;

    private static final Pattern COLOR_TARGET = Pattern.compile("colortex([0-7])");

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
            Map<String, Integer> customSlots = settings.customSamplerSlots();
            TreeSet<String> activeSamplers = new TreeSet<>();
            for (PackProgramPlan programPlan : plan.programs()) {
                if (programPlan == null || programPlan.interfacePlan() == null) continue;
                UniformRegistry.Stage stage = stageFor(programPlan.name());
                programPlan.interfacePlan().effective(stage).samplers().stream()
                        .map(UniformRegistry.SamplerBinding::name)
                        .forEach(activeSamplers::add);
            }
            for (PackResourceDeclaration declaration : declarations.values()) {
                String sampler = declaration.sampler();
                if (activeSamplers.contains(sampler)
                        && !customSlots.containsKey(sampler)
                        && !UniformRegistry.NAME_TO_SLOT.containsKey(sampler)) {
                    deviations.add("PACK_RESOURCE_SLOT_LIMIT:" + sampler);
                }
            }
        }

        for (PackProgramPlan programPlan : plan.programs()) {
            if (programPlan == null || programPlan.interfacePlan() == null) continue;
            UniformRegistry.Stage stage = stageFor(programPlan.name());
            List<UniformRegistry.SamplerBinding> samplers = programPlan.interfacePlan()
                    .effective(stage).samplers();
            List<PackResourceBinding> programBindings = new ArrayList<>();
            for (UniformRegistry.SamplerBinding sampler : samplers) {
                PackResourceBinding binding = resolve(programPlan.name(), sampler, declarations, shadersDir,
                        settings == null ? Map.of() : settings.propertyValues());
                programBindings.add(binding);
                deviations.addAll(binding.deviations());
            }
            bindings.put(programPlan.name(), List.copyOf(programBindings));
        }
        return new PackResourcePlan(bindings, declarations, deviations);
    }

    private static PackResourceBinding resolve(
            String program,
            UniformRegistry.SamplerBinding sampler,
            Map<String, PackResourceDeclaration> declarations,
            Path shadersDir,
            Map<String, String> propertyValues
    ) {
        String name = sampler.name();
        String canonical = canonicalResource(name);
        int slot = sampler.slot();
        List<String> deviations = new ArrayList<>();

        PackResourceDeclaration declaration = declarations.get("texture." + program + "." + name);
        if (declaration == null) declaration = declarations.get("texture.*." + name);
        if (declaration == null) declaration = declarations.get("customTexture." + name);
        if (declaration == null && name.equals("noisetex")) {
            declaration = declarations.get("texture.noise");
        }

        // Pack declarations take precedence over canonical aliases. This is
        // important for a stage that deliberately replaces a logical target
        // with a sampled pack texture.
        if (declaration != null) {
            boolean game = isNamespacedSource(declaration.source());
            String key = name.equals("noisetex") ? "noisetex" : declaration.key();
            PackResourceKind kind = name.equals("noisetex")
                    ? PackResourceKind.NOISE : PackResourceKind.PACK_TEXTURE;
            String filter = name.equals("noisetex") ? "linear" : "nearest";
            String wrap = "repeat";
            int resolvedSlot = name.equals("noisetex") ? 7 : slot;
            if (game) {
                deviations.add("PACK_TEXTURE_DECLARED:" + key);
                return new PackResourceBinding(program, name, key, kind,
                        declaration.source(), resolvedSlot, filter, wrap,
                        PackResourceStatus.GAME_RESOURCE, deviations);
            }
            if (resolvePath(shadersDir, declaration.source()) != null) {
                deviations.add("PACK_TEXTURE_APPLIED:" + key);
                return new PackResourceBinding(program, name, key, kind,
                        declaration.source(), resolvedSlot, filter, wrap,
                        PackResourceStatus.PACK_FILE, deviations);
            }
            deviations.add(isUnsafePath(declaration.source())
                    ? "PACK_TEXTURE_PATH_UNSAFE:" + key
                    : "PACK_TEXTURE_MISSING:" + key);
            return new PackResourceBinding(program, name, key, kind,
                    declaration.source(), resolvedSlot, filter, wrap,
                    PackResourceStatus.UNAVAILABLE, deviations);
        }

        if (name.equals("texture") || name.equals("tex") || name.equals("lightmap")) {
            String hostResource = name.equals("tex") ? "texture" : name;
            deviations.add("STANDARD_RESOURCE_ALIAS:" + name + ":" + hostResource);
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

        if (canonical.startsWith("colortex") || canonical.startsWith("depthtex")) {
            deviations.add("STANDARD_RESOURCE_ALIAS:" + name + ":" + canonical);
            return new PackResourceBinding(program, name, canonical,
                    canonical.startsWith("depthtex") ? PackResourceKind.DEPTH : PackResourceKind.TARGET,
                    "", slot, "linear", "clamp", PackResourceStatus.HOST_ALIAS, deviations);
        }
        if (canonical.startsWith("shadowtex")) {
            if (!canonical.equals("shadowtex0")) {
                if (canonical.equals("shadowtex1")
                        && UniformRegistry.GEOMETRY_NAME_TO_SLOT.containsKey(name)) {
                    deviations.add("SHADOW_RESOURCE_ALIAS:" + name);
                    return new PackResourceBinding(program, name, "shadowtex0",
                            PackResourceKind.SHADOW_DEPTH, "", slot, "linear", "clamp",
                            PackResourceStatus.HOST_ALIAS, deviations);
                }
                deviations.add("STANDARD_RESOURCE_UNAVAILABLE:" + name);
                return new PackResourceBinding(program, name, canonical,
                        PackResourceKind.SHADOW_DEPTH, "", slot, "linear", "clamp",
                        PackResourceStatus.UNAVAILABLE, deviations);
            }
            deviations.add("SHADOW_RESOURCE_ALIAS:" + name);
            return new PackResourceBinding(program, name, canonical,
                    PackResourceKind.SHADOW_DEPTH, "", slot, "linear", "clamp",
                    PackResourceStatus.HOST_ALIAS, deviations);
        }
        if (canonical.equals("shadowcolor0")) {
            deviations.add("SHADOW_RESOURCE_ALIAS:" + name);
            return new PackResourceBinding(program, name, canonical,
                    PackResourceKind.SHADOW_COLOR, "", slot, "linear", "clamp",
                    PackResourceStatus.HOST_ALIAS, deviations);
        }
        if (canonical.equals("shadowcolor1")) {
            deviations.add("STANDARD_RESOURCE_UNAVAILABLE:" + name);
            return new PackResourceBinding(program, name, canonical,
                    PackResourceKind.SHADOW_COLOR, "", slot, "linear", "clamp",
                    PackResourceStatus.UNAVAILABLE, deviations);
        }
        if (canonical.equals("noisetex")) {
            String source = "tex/noise.png";
            boolean exists = resolvePath(shadersDir, source) != null;
            if (exists) {
                deviations.add("PACK_TEXTURE_APPLIED:noisetex");
                return new PackResourceBinding(program, name, "noisetex",
                        PackResourceKind.NOISE, source, 7, "linear", "repeat",
                        PackResourceStatus.PACK_FILE, deviations);
            }
            deviations.add(isUnsafePath(source)
                    ? "PACK_TEXTURE_PATH_UNSAFE:noisetex"
                    : "PACK_TEXTURE_MISSING:noisetex");
            return new PackResourceBinding(program, name, "noisetex",
                    PackResourceKind.NOISE, source, 7, "linear", "repeat",
                    PackResourceStatus.UNAVAILABLE, deviations);
        }
        if (name.equals("normals") || name.equals("specular")) {
            deviations.add("MATERIAL_MAP_DEFERRED:" + name);
            return new PackResourceBinding(program, name, name,
                    PackResourceKind.MATERIAL_ATLAS, "", slot, "nearest", "repeat",
                    PackResourceStatus.MATERIAL_ATLAS, deviations);
        }
        deviations.add("SAMPLER_NOT_MAPPED:" + name);
        return new PackResourceBinding(program, name, name,
                PackResourceKind.UNSERVED, "", slot, "nearest", "repeat",
                PackResourceStatus.UNSUPPORTED, deviations);
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
        if (sampler.equals("tex")) return "texture";
        if (sampler.equals("gcolor")) return "colortex0";
        if (sampler.equals("gdepth")) return "colortex1";
        if (sampler.equals("gnormal")) return "colortex2";
        if (sampler.equals("composite")) return "colortex3";
        if (sampler.matches("gaux[1-4]")) {
            return "colortex" + (3 + Integer.parseInt(sampler.substring(4)));
        }
        if (sampler.matches("colortex[0-7]")) return sampler;
        if (sampler.matches("depthtex[0-2]")) return sampler;
        if (sampler.matches("shadowtex[0-1]")) return sampler;
        if (sampler.matches("shadowcolor[0-1]")) return sampler;
        return sampler.equals("noisetex") ? "noisetex" : sampler;
    }

    public static Integer targetIndex(String sampler) {
        Matcher matcher = COLOR_TARGET.matcher(canonicalResource(sampler));
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static UniformRegistry.Stage stageFor(String name) {
        return FamilyAdapterRegistry.stageFor(name);
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
        String normalized = source.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("[A-Za-z]:/.*")) return null;
        Path root = shadersDir.toAbsolutePath().normalize();
        Path path = root.resolve(normalized).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) return null;
        return path;
    }

    private static boolean isUnsafePath(String source) {
        if (source == null || source.isBlank()) return true;
        String normalized = source.replace('\\', '/');
        return normalized.startsWith("/") || normalized.matches("[A-Za-z]:/.*")
                || normalized.equals("..") || normalized.startsWith("../")
                || normalized.contains("/../") || normalized.contains("/./")
                || normalized.equals(".") || normalized.contains("\0");
    }

    private static boolean isNamespacedSource(String source) {
        return source != null && source.contains(":") && !isUnsafePath(source)
                && !source.startsWith("./") && !source.startsWith("../");
    }

    private static String advancedImageName(Map<String, String> propertyValues, String sampler) {
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
