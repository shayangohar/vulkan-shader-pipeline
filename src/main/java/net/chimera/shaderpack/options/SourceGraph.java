package net.chimera.shaderpack.options;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The pack's program sources and everything they {@code #include}, as the option scan sees them.
 *
 * <p>The scan starts from every file named like a program in the shaders root and in each dimension
 * folder, and follows every {@code #include} line whatever condition surrounds it, since includes
 * are resolved before the preprocessor runs. Paths are pack-relative with a leading slash; a target
 * starting with a slash resolves from the shaders root, any other from the including file's folder.
 * An include that cannot be read is recorded and skipped, so one bad path does not hide every option.
 */
final class SourceGraph {
    private final Map<String, AnnotatedSource> files;
    private final Map<String, Set<String>> includes;
    private final List<String> failures;

    private SourceGraph(Map<String, AnnotatedSource> files, Map<String, Set<String>> includes, List<String> failures) {
        this.files = files;
        this.includes = includes;
        this.failures = List.copyOf(failures);
    }

    static SourceGraph empty() {
        return new SourceGraph(Map.of(), Map.of(), List.of());
    }

    static SourceGraph read(Path shadersRoot) {
        Map<String, AnnotatedSource> files = new TreeMap<>();
        Map<String, Set<String>> includes = new HashMap<>();
        List<String> failures = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>(ProgramSourceNames.starts(shadersRoot));
        Set<String> seen = new LinkedHashSet<>(queue);
        while (!queue.isEmpty()) {
            String path = queue.removeLast();
            List<String> lines = readLines(shadersRoot, path);
            if (lines == null) {
                failures.add("SOURCE_INCLUDE_UNREADABLE:" + path);
                continue;
            }
            files.put(path, AnnotatedSource.parse(lines));
            Set<String> targets = new LinkedHashSet<>();
            for (String line : lines) {
                String target = includeTarget(path, line);
                if (target == null || target.equals(path)) continue;
                targets.add(target);
                if (seen.add(target)) queue.add(target);
            }
            includes.put(path, targets);
        }
        return new SourceGraph(files, includes, failures);
    }

    /** Every readable file, by pack-relative path. */
    Map<String, AnnotatedSource> files() {
        return files;
    }

    List<String> failures() {
        return failures;
    }

    /**
     * The files split into groups joined by includes in either direction. An option and the
     * {@code #ifdef} that confirms it must share a group.
     */
    List<List<String>> components() {
        Map<String, String> parent = new HashMap<>();
        for (String file : files.keySet()) parent.put(file, file);
        includes.forEach((from, targets) -> targets.stream().filter(files::containsKey)
                .forEach(to -> parent.put(root(parent, from), root(parent, to))));
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String file : files.keySet()) {
            groups.computeIfAbsent(root(parent, file), key -> new ArrayList<>()).add(file);
        }
        return List.copyOf(groups.values());
    }

    private static String root(Map<String, String> parent, String file) {
        String current = file;
        while (!parent.get(current).equals(current)) current = parent.get(current);
        parent.put(file, current);
        return current;
    }

    /** The pack path an {@code #include} line names, resolved against {@code from}; null for other lines. */
    static String includeTarget(String from, String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("#include") || trimmed.length() <= "#include ".length()) return null;
        String target = trimmed.substring("#include ".length()).trim();
        if (target.startsWith("\"")) target = target.substring(1);
        if (target.endsWith("\"")) target = target.substring(0, target.length() - 1);
        String base = target.startsWith("/") ? "" : from.substring(0, from.lastIndexOf('/'));
        return normalize(base + "/" + target);
    }

    /** Collapses {@code .} and {@code ..} segments; null when the path climbs above the shaders root. */
    private static String normalize(String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) return null;
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        return "/" + String.join("/", segments);
    }

    private static List<String> readLines(Path shadersRoot, String path) {
        try {
            Path file = shadersRoot.resolve(path.substring(1)).normalize();
            if (!file.startsWith(shadersRoot.normalize()) || !Files.isRegularFile(file)) return null;
            return List.of(Files.readString(file, StandardCharsets.UTF_8).split("\\R"));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Iris's program file names, looked for in the shaders root and each dimension folder. */
    static final class ProgramSourceNames {
        private static final List<String> STAGES = List.of(".vsh", ".tcs", ".tes", ".gsh", ".fsh");
        private static final List<String> CANDIDATES = candidates();

        private ProgramSourceNames() {}

        static List<String> starts(Path shadersRoot) {
            List<String> starts = new ArrayList<>();
            addPresent(shadersRoot, "", starts);
            for (String folder : dimensionFolders(shadersRoot)) addPresent(shadersRoot, folder, starts);
            return starts;
        }

        private static void addPresent(Path shadersRoot, String folder, List<String> starts) {
            Path directory = folder.isEmpty() ? shadersRoot : shadersRoot.resolve(folder);
            if (!Files.isDirectory(directory)) return;
            Set<String> present = new HashSet<>();
            try (Stream<Path> listing = Files.list(directory)) {
                listing.forEach(entry -> present.add(entry.getFileName().toString()));
            } catch (IOException unreadable) {
                return;
            }
            String prefix = folder.isEmpty() ? "/" : "/" + folder + "/";
            for (String candidate : CANDIDATES) {
                if (present.contains(candidate)) starts.add(prefix + candidate);
            }
        }

        /** The folders dimension.properties names, else the world folders that exist. */
        private static List<String> dimensionFolders(Path shadersRoot) {
            List<String> folders = new ArrayList<>();
            Path properties = shadersRoot.resolve("dimension.properties");
            if (Files.isRegularFile(properties)) {
                Properties values = new Properties();
                try (Reader reader = Files.newBufferedReader(properties, StandardCharsets.UTF_8)) {
                    values.load(reader);
                } catch (IOException | IllegalArgumentException unreadable) {
                    values.clear();
                }
                values.stringPropertyNames().stream().filter(key -> key.startsWith("dimension."))
                        .map(key -> key.substring("dimension.".length())).sorted().forEach(folders::add);
            }
            if (folders.isEmpty()) {
                for (String world : List.of("world0", "world-1", "world1")) {
                    if (Files.isDirectory(shadersRoot.resolve(world))) folders.add(world);
                }
            }
            return folders;
        }

        private static List<String> candidates() {
            List<String> names = new ArrayList<>();
            for (String array : List.of("setup", "begin", "shadowcomp", "prepare", "deferred", "composite")) {
                for (int index = 0; index < 100; index++) addComputeStarts(names, array + (index == 0 ? "" : index));
            }
            addComputeStarts(names, "shadow");
            for (String shadow : List.of("solid", "cutout", "water", "entities", "lightning", "block")) {
                addStarts(names, "shadow_" + shadow);
            }
            for (String gbuffer : List.of("basic", "line", "textured", "textured_lit", "skybasic", "skytextured",
                    "clouds", "terrain", "terrain_solid", "terrain_cutout", "damagedblock", "block",
                    "block_translucent", "beaconbeam", "item", "entities", "entities_translucent", "lightning",
                    "particles", "particles_translucent", "entities_glowing", "armor_glint", "spidereyes", "hand",
                    "weather", "water", "hand_water")) {
                addStarts(names, "gbuffers_" + gbuffer);
            }
            for (String dh : List.of("terrain", "water", "generic", "shadow")) addStarts(names, "dh_" + dh);
            addComputeStarts(names, "final");
            return List.copyOf(names);
        }

        private static void addStarts(List<String> names, String base) {
            for (String stage : STAGES) names.add(base + stage);
        }

        /** The stages, the compute shader and its 26 lettered variants. */
        private static void addComputeStarts(List<String> names, String base) {
            addStarts(names, base);
            names.add(base + ".csh");
            for (char letter = 'a'; letter <= 'z'; letter++) names.add(base + "_" + letter + ".csh");
        }
    }
}
