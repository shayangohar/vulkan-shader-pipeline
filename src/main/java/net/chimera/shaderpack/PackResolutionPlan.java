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
        aliases = sorted(aliases);
        missingPrograms = sorted(missingPrograms);
        disabledPrograms = sorted(disabledPrograms);
        deviations = sorted(deviations);
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
            String selected = "";
            int depth = 0;
            PackProgram chosen = null;
            for (int index = 0; index < chain.size(); index++) {
                PackProgram candidate = shipped.get(chain.get(index));
                if (candidate != null) {
                    selected = chain.get(index);
                    depth = index;
                    chosen = candidate;
                    break;
                }
            }
            boolean enabled = actualSettings.enabled(requested);
            List<String> programDeviations = new ArrayList<>();
            if (!enabled) {
                disabled.add(requested);
                programDeviations.add("PROGRAM_DISABLED:" + requested);
            }
            if (chosen == null) {
                missing.add(requested);
                answers.put(requested, new PackProgramResolution(requested, "", "", 0,
                        enabled, false, programDeviations));
                continue;
            }
            boolean exact = depth == 0 && requested.equals(selected);
            boolean executable = exact && enabled && supportsCurrentAdapter(requested);
            if (!exact) {
                String alias = requested + "->" + selected;
                aliases.add(alias);
                programDeviations.add("PROGRAM_ALIAS_RECORDED:" + alias);
                programDeviations.add("PROGRAM_ALIAS_RUNTIME_FALLBACK:" + requested);
                deviations.add("PROGRAM_ALIAS_RECORDED:" + alias);
                executable = false;
            }
            answers.put(requested, new PackProgramResolution(requested, selected,
                    relative(chosen.sourceRoot(), chosen.fragmentPath()), depth, enabled,
                    executable, programDeviations));
        }
        return new PackResolutionPlan(dimension, folder, answers, aliases, missing,
                disabled, deviations);
    }

    PackProgramResolution resolution(String name) {
        return resolutions.get(name);
    }

    boolean shouldAttempt(String name) {
        PackProgramResolution resolution = resolutions.get(name);
        return resolution == null || resolution.executable();
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
                    entry.add("deviations", strings(value.deviations()));
                    entries.add(entry);
                });
        root.add("resolutions", entries);
        root.add("aliases", strings(aliases));
        root.add("missingPrograms", strings(missingPrograms));
        root.add("disabledPrograms", strings(disabledPrograms));
        root.add("deviations", strings(deviations));
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
        addChain(result, "gbuffers_basic");
        addChain(result, "gbuffers_line", "gbuffers_basic");
        addChain(result, "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_skybasic", "gbuffers_basic");
        addChain(result, "gbuffers_skytextured", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_clouds", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_terrain_solid", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_terrain_cutout", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_damagedblock", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_block", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_block_translucent", "gbuffers_block", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_beaconbeam", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_item", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_entities_translucent", "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_entities_glowing", "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_lightning", "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_particles", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_particles_translucent", "gbuffers_particles", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_armor_glint", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_spidereyes", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_weather", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_water", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
        addChain(result, "gbuffers_hand_water", "gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic");
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

    private static boolean supportsCurrentAdapter(String name) {
        return name.equals("gbuffers_terrain")
                || name.equals("gbuffers_water")
                || name.equals("gbuffers_entities")
                || name.equals("shadow")
                || PostTargetPlan.isPostProgramName(name);
    }

    private static List<String> sorted(Iterable<String> values) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(value);
                }
            }
        }
        return List.copyOf(result);
    }

    private static JsonArray strings(Iterable<String> values) {
        JsonArray result = new JsonArray();
        sorted(values).forEach(result::add);
        return result;
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
