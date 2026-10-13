package net.chimera.shaderpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
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

/** Immutable standard-family resolution for one selected pack dimension. */
record PackResolutionPlan(
        String selectedDimension,
        String selectedSourceFolder,
        Map<String, PackProgramResolution> resolutions,
        List<String> aliases,
        List<String> missingPrograms,
        List<String> disabledPrograms,
        List<String> deviations
) {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Map<String, List<String>> FALLBACKS = fallbackTable();

    PackResolutionPlan {
        selectedDimension = selectedDimension == null ? "minecraft:overworld" : selectedDimension;
        selectedSourceFolder = selectedSourceFolder == null ? "" : selectedSourceFolder;
        resolutions = resolutions == null ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(resolutions));
        aliases = PlanJson.sorted(aliases);
        missingPrograms = PlanJson.sorted(missingPrograms);
        disabledPrograms = PlanJson.sorted(disabledPrograms);
        deviations = PlanJson.sorted(deviations);
    }

    static PackResolutionPlan empty() {
        return new PackResolutionPlan("minecraft:overworld", "", Map.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    static PackResolutionPlan build(
            String dimension,
            String folder,
            List<PackProgram> programs,
            PackSettingsPlan settings
    ) {
        Map<String, PackProgram> shipped = shippedPrograms(programs);
        PackSettingsPlan actualSettings = settings == null ? PackSettingsPlan.empty() : settings;
        Map<String, PackProgramResolution> answers = new TreeMap<>();
        Set<String> allNames = new TreeSet<>(FALLBACKS.keySet());
        allNames.addAll(shipped.keySet());
        List<String> aliases = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        List<String> deviations = new ArrayList<>();
        for (String requested : allNames) {
            List<String> chain = FALLBACKS.getOrDefault(requested, List.of(requested));
            int depth = selectedDepth(chain, shipped, actualSettings, folder);
            boolean authoredEnabled = actualSettings.enabled(folder, requested);
            boolean enabled = authoredEnabled || (depth >= 0 && requested.startsWith("gbuffers_"));
            List<String> programDeviations = new ArrayList<>();
            if (!authoredEnabled) {
                disabled.add(requested);
                if (!enabled) programDeviations.add("PROGRAM_DISABLED:" + requested);
            }
            if (depth < 0) {
                missing.add(requested);
                answers.put(requested, new PackProgramResolution(requested, "", "", 0,
                        enabled, false, programDeviations));
                continue;
            }
            String selected = chain.get(depth);
            PackProgram chosen = shipped.get(selected);
            boolean exact = depth == 0 && requested.equals(selected);
            boolean executable = enabled && supportsCurrentAdapter(requested);
            if (!exact) {
                String alias = requested + "->" + selected;
                aliases.add(alias);
                programDeviations.add("PROGRAM_ALIAS_RECORDED:" + alias);
                if (!executable) programDeviations.add("PROGRAM_ALIAS_RUNTIME_FALLBACK:" + requested);
                deviations.add("PROGRAM_ALIAS_RECORDED:" + alias);
            }
            answers.put(requested, new PackProgramResolution(requested, selected,
                    relative(chosen.sourceRoot(), chosen.fragmentPath()), depth, enabled,
                    executable, programDeviations));
        }
        return new PackResolutionPlan(dimension, folder, answers, aliases, missing,
                disabled, deviations);
    }

    /** The shipped programs by name; of two with one name, the first by source path. */
    private static Map<String, PackProgram> shippedPrograms(List<PackProgram> programs) {
        Map<String, PackProgram> shipped = new TreeMap<>();
        if (programs != null) {
            for (PackProgram program : programs.stream()
                    .filter(value -> value != null)
                    .sorted(Comparator.comparing(PackProgram::name)
                            .thenComparing(value -> relative(value.sourceRoot(), value.fragmentPath())))
                    .toList()) {
                shipped.putIfAbsent(program.name(), program);
            }
        }
        return shipped;
    }

    /** How far down its fallback chain a request resolves: the first program shipped and enabled, or -1. */
    private static int selectedDepth(List<String> chain, Map<String, PackProgram> shipped,
                                     PackSettingsPlan settings, String folder) {
        for (int index = 0; index < chain.size(); index++) {
            if (shipped.get(chain.get(index)) != null && settings.enabled(folder, chain.get(index))) {
                return index;
            }
        }
        return -1;
    }

    PackProgramResolution resolution(String name) {
        return resolutions.get(name);
    }

    boolean shouldAttempt(String name) {
        PackProgramResolution resolution = resolutions.get(name);
        return resolution == null || resolution.executable();
    }

    /** Materialize selected prepared sources under the requested adapter's name. */
    List<PackProgram> executablePrograms(List<PackProgram> programs) {
        Map<String, PackProgram> result = new TreeMap<>();
        if (programs != null) {
            programs.stream().filter(program -> !isShaderKey(program.name()))
                    .forEach(program -> result.put(program.name(), program));
        }
        Map<String, PackProgram> shipped = new TreeMap<>(result);
        for (PackProgramResolution resolved : resolutions.values()) {
            if (!resolved.executable() || resolved.requestedProgram().equals(resolved.selectedProgram())) continue;
            PackProgram source = shipped.get(resolved.selectedProgram());
            if (source == null) continue;
            result.put(resolved.requestedProgram(), new PackProgram(resolved.requestedProgram(),
                    source.fragmentSource(), source.fragmentPath(), source.vertexSource(), source.vertexPath(),
                    source.sourceRoot(), source.preparedFragmentSource(), source.preparedVertexSource(),
                    source.preparationDeviations(), source.variantFolder(), source.preparedSources()));
        }
        return List.copyOf(result.values());
    }

    String snapshotJson() {
        JsonObject root = new JsonObject();
        root.addProperty("selectedDimension", selectedDimension);
        root.addProperty("selectedSourceFolder", selectedSourceFolder);
        JsonArray entries = new JsonArray();
        resolutions.values().stream().sorted(Comparator.comparing(PackProgramResolution::requestedProgram))
                .forEach(value -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("requestedProgram", value.requestedProgram());
                    entry.addProperty("selectedProgram", value.selectedProgram());
                    entry.addProperty("sourcePath", value.sourcePath());
                    entry.addProperty("fallbackDepth", value.fallbackDepth());
                    entry.addProperty("enabled", value.enabled());
                    entry.addProperty("executable", value.executable());
                    entry.add("deviations", PlanJson.strings(value.deviations()));
                    entries.add(entry);
                });
        root.add("resolutions", entries);
        root.add("aliases", PlanJson.strings(aliases));
        root.add("missingPrograms", PlanJson.strings(missingPrograms));
        root.add("disabledPrograms", PlanJson.strings(disabledPrograms));
        root.add("deviations", PlanJson.strings(deviations));
        return JSON.toJson(root);
    }

    String fingerprint() {
        return ConformanceReport.sha256(snapshotJson().getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, List<String>> fallbackTable() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        addChain(result, "shadow");
        addChain(result, "shadow_solid", "shadow");
        addChain(result, "shadow_cutout", "shadow");
        addChain(result, "shadow_water", "shadow");
        addChain(result, "shadow_entities", "shadow");
        addChain(result, "shadow_lightning", "shadow_entities", "shadow");
        addChain(result, "shadow_block", "shadow");
        // Iris shadow ShaderKeys drawn on host layouts: each runs the Iris program it names.
        addKeyChain(result, "shadow_lines", "shadow");
        addKeyChain(result, "shadow_leash", "shadow");
        addKeyChain(result, "shadow_basic", "shadow");
        addKeyChain(result, "shadow_glint", "shadow_entities", "shadow");
        addKeyChain(result, "shadow_text", "shadow_entities", "shadow");
        addKeyChain(result, "shadow_text_bg", "shadow_entities", "shadow");
        addChain(result, "gbuffers_basic");
        addChain(result, "gbuffers_line", "gbuffers_basic");
        addChain(result, "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_skybasic", "gbuffers_basic");
        addChain(result, "gbuffers_skytextured", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_clouds", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_terrain_solid", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured",
                "gbuffers_basic");
        addChain(result, "gbuffers_terrain_cutout", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured",
                "gbuffers_basic");
        addChain(result, "gbuffers_damagedblock", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured",
                "gbuffers_basic");
        addChain(result, "gbuffers_block", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured",
                "gbuffers_basic");
        addChain(result, "gbuffers_block_translucent", "gbuffers_block", "gbuffers_terrain", "gbuffers_textured_lit",
                "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_beaconbeam", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_item", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_entities_translucent", "gbuffers_entities", "gbuffers_textured_lit",
                "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_entities_glowing", "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured",
                "gbuffers_basic");
        addChain(result, "gbuffers_lightning", "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured",
                "gbuffers_basic");
        addChain(result, "gbuffers_particles", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_particles_translucent", "gbuffers_particles", "gbuffers_textured_lit",
                "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_armor_glint", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_spidereyes", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_weather", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_water", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured",
                "gbuffers_basic");
        addChain(result, "gbuffers_hand_water", "gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured",
                "gbuffers_basic");
        addChain(result, "dh_terrain");
        addChain(result, "dh_water", "dh_terrain");
        addChain(result, "dh_generic", "dh_terrain");
        addChain(result, "dh_shadow");
        addChain(result, "final");
        return Collections.unmodifiableMap(result);
    }

    private static void addChain(Map<String, List<String>> map, String name, String... parents) {
        List<String> chain = new ArrayList<>();
        chain.add(name);
        if (parents != null) {
            Collections.addAll(chain, parents);
        }
        map.put(name, List.copyOf(chain));
    }

    /**
     * A shader key that is not an Iris program name: it resolves only through the programs
     * Iris uses for it, so a pack file of the same name is never selected.
     */
    private static void addKeyChain(Map<String, List<String>> map, String key, String... programs) {
        map.put(key, List.of(programs));
    }

    /** True for a requested name that is a shader key, never an authorable program. */
    static boolean isShaderKey(String name) {
        List<String> chain = FALLBACKS.get(name);
        return chain != null && !chain.get(0).equals(name);
    }

    private static boolean supportsCurrentAdapter(String name) {
        return FamilyAdapterRegistry.isExecutableFamily(name);
    }

    private static String relative(Path root, Path path) {
        if (path == null) {
            return "";
        }
        if (root == null) {
            return path.getFileName() == null ? "" : path.getFileName().toString();
        }
        try {
            return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (RuntimeException ignored) {
            return path.getFileName() == null ? "" : path.getFileName().toString();
        }
    }
}
