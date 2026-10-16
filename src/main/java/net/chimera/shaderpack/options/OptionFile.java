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
        if (!Files.isRegularFile(file)) return new TreeMap<>();
        try {
            return readStrict(file);
        } catch (IOException unreadable) {
            return new TreeMap<>();
        }
    }

    /** The values of a settings file the player chose, failing when it cannot be read or parsed. */
    public static Map<String, String> readStrict(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IllegalArgumentException malformed) {
            throw new IOException(malformed.getMessage(), malformed);
        }
        Map<String, String> values = new TreeMap<>();
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
        store(file, changes);
    }

    /** Writes {@code values} through a temporary file, so a failure leaves the previous file whole. */
    private static void store(Path file, Map<String, String> values) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        export(temporary, values);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Writes {@code values} straight to a file the player chose, even when there are none. */
    public static void export(Path file, Map<String, String> values) throws IOException {
        Properties properties = new Properties();
        properties.putAll(values);
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, null);
        }
    }
}
