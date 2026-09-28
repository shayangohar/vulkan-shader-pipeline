package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Where a pack's option values come from. Iris keeps them in
 * {@code <pack>.txt} beside the pack zip or folder
 * ({@code ComplementaryReimagined_r5.9.3.zip.txt}); Chimera reads the same
 * file, so one file drives both renderers. {@code chimera.option.*} JVM
 * properties apply on top for automated runs.
 */
public final class PackOptionSources {
    private static final String OPTION_OVERRIDE_PREFIX = "chimera.option.";

    private PackOptionSources() {}

    /** The option file's values with the JVM overrides on top. */
    public static Map<String, String> overrides(Path packPath) {
        Map<String, String> result = new TreeMap<>(fileValues(packPath));
        result.putAll(systemOverrides());
        return result;
    }

    /** Iris's per-pack option file for this pack, or null when there is none. */
    public static Path optionFile(Path packPath) {
        if (packPath == null || packPath.getFileName() == null) return null;
        Path file = packPath.toAbsolutePath().resolveSibling(packPath.getFileName().toString() + ".txt");
        return Files.isRegularFile(file) ? file : null;
    }

    /** The option file's values; empty when there is no readable file. */
    public static Map<String, String> fileValues(Path packPath) {
        Path file = optionFile(packPath);
        if (file == null) return Map.of();
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader);
        } catch (IOException | IllegalArgumentException unreadable) {
            return Map.of();
        }
        Map<String, String> result = new TreeMap<>();
        for (String name : values.stringPropertyNames()) result.put(name, values.getProperty(name));
        return result;
    }

    /** {@code chimera.option.NAME=value} JVM properties. */
    static Map<String, String> systemOverrides() {
        Map<String, String> result = new TreeMap<>();
        for (String property : System.getProperties().stringPropertyNames()) {
            if (!property.startsWith(OPTION_OVERRIDE_PREFIX)) continue;
            String name = property.substring(OPTION_OVERRIDE_PREFIX.length());
            result.put(name, System.getProperty(property, ""));
        }
        return result;
    }
}
