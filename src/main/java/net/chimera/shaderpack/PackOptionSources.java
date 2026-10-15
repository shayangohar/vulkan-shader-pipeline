package net.chimera.shaderpack;

import net.chimera.shaderpack.options.OptionFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where a pack's option values come from. Iris keeps them in
 * {@code <pack>.txt} beside the pack zip or folder
 * ({@code ComplementaryReimagined_r5.9.3.zip.txt}); Chimera reads the same
 * file, so one file drives both renderers. {@code chimera.option.*} JVM
 * properties apply on top for automated runs ({@link PackOptionStaging}).
 */
public final class PackOptionSources {
    private static final String OPTION_OVERRIDE_PREFIX = "chimera.option.";

    private PackOptionSources() {}

    /** Iris's per-pack option file for this pack, or null when there is none. */
    public static Path optionFile(Path packPath) {
        if (packPath == null || packPath.getFileName() == null) return null;
        Path file = OptionFile.of(packPath);
        return Files.isRegularFile(file) ? file : null;
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
