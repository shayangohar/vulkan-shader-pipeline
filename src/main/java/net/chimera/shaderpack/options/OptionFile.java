package net.chimera.shaderpack.options;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * A pack's option file, {@code <pack file name>.txt} beside the pack in the shaderpacks folder, in the
 * format OptiFine defined and Iris keeps: an ISO-8859-1 properties file holding only the options that
 * differ from the pack's defaults, deleted once none do. Iris and Chimera read the same file.
 */
public final class OptionFile {
    private OptionFile() {}

    /** The option file of the pack at {@code packPath} (a zip or a folder). */
    public static Path of(Path packPath) {
        return packPath.toAbsolutePath().resolveSibling(packPath.getFileName().toString() + ".txt");
    }

    /** The stored text values; empty when there is no readable file. */
    public static Map<String, String> read(Path file) {
        Map<String, String> values = new TreeMap<>();
        if (!Files.isRegularFile(file)) return values;
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException unreadable) {
            return values;
        }
        properties.stringPropertyNames().forEach(name -> values.put(name, properties.getProperty(name)));
        return values;
    }

    /**
     * Writes {@code values}' changes, or deletes the file when there are none. The write goes through
     * a temporary file, so a failure leaves the previous file whole.
     */
    public static void write(Path file, OptionValues values) throws IOException {
        Map<String, String> changes = values.changes();
        if (changes.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Properties properties = new Properties();
        properties.putAll(changes);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporary)) {
            properties.store(out, null);
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
