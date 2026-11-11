package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * dimension.properties read as Iris 1.21.1's ShaderPack reads it: PropertiesPreprocessor with the
 * standard environment defines and no pack options, OrderBackedProperties, then parseDimensionMap,
 * where each world a {@code dimension.<folder>} key lists maps to that folder and a later key
 * overrides an earlier one. superDuperVanilla continues its world-1 list onto the next line.
 */
final class DimensionProperties {
    private static final String PREFIX = "dimension.";

    private DimensionProperties() {}

    /**
     * Each world the file maps to its folder; {@code present} when the file loads any property,
     * as Iris tests it. Without one Iris maps world0, world-1 and world1 as if there were no file.
     */
    record Folders(Map<String, String> worlds, boolean present) {}

    static Folders read(Path shadersDir, Map<String, String> macros, List<String> deviations) {
        Path file = shadersDir.resolve("dimension.properties");
        if (!Files.isRegularFile(file)) return new Folders(Map.of(), false);
        PropertiesPreprocessor.Preprocessed text;
        try {
            // Iris reads it as ISO-8859-1, which never fails on a stray byte.
            text = PropertiesPreprocessor.preprocess(Files.readString(file, StandardCharsets.ISO_8859_1), macros);
        } catch (IOException e) {
            return new Folders(Map.of(), false);
        }
        if (text.failed() || text.open()) deviations.add("PREPROCESSOR_CONDITION_UNSUPPORTED");
        Map<String, String> entries;
        try {
            entries = PropertiesPreprocessor.load(text.text());
        } catch (IOException | IllegalArgumentException e) {
            deviations.add("DIMENSION_PROPERTIES_MALFORMED");
            return new Folders(Map.of(), false);
        }
        Map<String, String> worlds = new TreeMap<>();
        entries.forEach((key, value) -> {
            if (!key.startsWith(PREFIX)) return;
            String folder = key.substring(PREFIX.length());
            // The folder is a path under the pack: only a plain name is read.
            if (!folder.matches("[A-Za-z0-9_-]+")) return;
            for (String world : value.trim().split("\\s+")) {
                if (world.isEmpty()) continue;
                worlds.put(world.equals("*") ? "*" : PackSource.normalizeDimension(world), folder);
            }
        });
        return new Folders(Map.copyOf(worlds), !entries.isEmpty());
    }
}
