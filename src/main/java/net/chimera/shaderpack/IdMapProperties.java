package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The id-map files (block.properties, item.properties, entity.properties) read as Iris reads them.
 * PropertiesPreprocessor runs the file through its C preprocessor (JCPP), IdMap.loadProperties
 * rejoins the continuations that preprocessing split and loads the text into OrderBackedProperties,
 * and IdMap.parseIdMap reads the item and entity keys. {@link #read} is the first two steps and
 * {@link #ids} the third.
 */
final class IdMapProperties {
    /** A continuation whose next lines preprocessing emptied goes on past them (IdMap.loadProperties). */
    private static final Pattern CONTINUED_PAST_EMPTY_LINES = Pattern.compile("\\\\\\n\\s*\\n");
    /**
     * IdMap.loadProperties then starts a new line at each {@code block.} that follows other text on
     * its line, and the character before it goes with the match: a continuation that runs into the
     * next block key loses its last character.
     */
    private static final Pattern RUN_ON_BLOCK_KEY = Pattern.compile("\\S *block\\.");

    private IdMapProperties() {}

    /**
     * One file's entries in the order their keys first appear, each with its last value, as
     * OrderBackedProperties keeps them. {@code failure} names why there are none when the file exists
     * but could not be read, its conditions could not be evaluated, or its escapes are malformed.
     */
    record Loaded(boolean present, String sourceHash, Map<String, String> entries, String failure) {}

    /** Reads {@code fileName} in the shaders folder, preprocessed with {@code macros}. */
    static Loaded read(Path shadersDir, String fileName, Map<String, String> macros) {
        Path file = shadersDir == null ? null : shadersDir.resolve(fileName);
        if (file == null || !Files.isRegularFile(file)) {
            return new Loaded(false, null, Map.of(), null);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return new Loaded(true, null, Map.of(), "READ_FAILED");
        }
        String hash = ConformanceReport.textSha256(bytes);
        // Iris reads id maps as ISO-8859-1, which never fails on a stray byte.
        PropertiesPreprocessor.Preprocessed preprocessed =
                PropertiesPreprocessor.preprocess(new String(bytes, StandardCharsets.ISO_8859_1), macros);
        if (preprocessed.failed()) {
            return new Loaded(true, hash, Map.of(), "CONDITION_UNSUPPORTED");
        }
        String text = CONTINUED_PAST_EMPTY_LINES.matcher(preprocessed.text()).replaceAll(" ");
        text = RUN_ON_BLOCK_KEY.matcher(text).replaceAll("\nblock.");
        try {
            return new Loaded(true, hash, PropertiesPreprocessor.load(text), null);
        } catch (IOException | IllegalArgumentException e) {
            return new Loaded(true, hash, Map.of(), "MALFORMED");
        }
    }

    /**
     * Iris's IdMap.parseIdMap over {@code <prefix><n>} keys, in entry order: each whitespace-separated
     * name maps to n, a later key overriding an earlier one. A name with a state selector is skipped,
     * a name without a namespace is in {@code minecraft}, and a key whose n is not an integer is
     * reported in {@code invalidKeys}.
     */
    static Map<String, Integer> ids(Map<String, String> entries, String prefix, List<String> invalidKeys) {
        Map<String, Integer> ids = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            if (!key.startsWith(prefix)) return;
            int id;
            try {
                id = Integer.parseInt(key.substring(prefix.length()));
            } catch (NumberFormatException e) {
                invalidKeys.add(key);
                return;
            }
            for (String name : value.split("\\s+")) {
                if (!name.isEmpty() && !name.contains("=")) ids.put(namespaced(name), id);
            }
        });
        return ids;
    }

    /** Iris's NamespacedId: a name without a namespace is in {@code minecraft}. */
    static String namespaced(String name) {
        return name.indexOf(':') < 0 ? "minecraft:" + name : name;
    }
}
