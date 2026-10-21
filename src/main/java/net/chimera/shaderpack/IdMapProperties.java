package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The id-map files read as Iris's IdMap.loadProperties reads them: ISO-8859-1 text, {@code #if}
 * blocks following the macros, and {@code \} continuations joined as
 * {@link PackSettingsPlan.PropertyLineJoiner} describes. Iris then loads the text into
 * java.util.Properties and parses item.properties and entity.properties with one rule
 * ({@code IdMap.parseIdMap}); {@link #entries} and {@link #ids} are those two steps.
 */
final class IdMapProperties {
    private static final Pattern CONDITION = Pattern.compile(
            "^\\s*#\\s*(if|ifdef|ifndef|elif|else|endif)\\b(.*)$");

    private IdMapProperties() {}

    /**
     * One file's logical lines in file order. {@code failure} names why there are none when the
     * file exists but could not be read or its conditions could not be evaluated.
     */
    record Lines(boolean present, String sourceHash, List<String> lines, String failure) {}

    /** Reads {@code fileName} in the shaders folder, preprocessed with {@code macros}. */
    static Lines read(Path shadersDir, String fileName, Map<String, String> macros) {
        Path file = shadersDir == null ? null : shadersDir.resolve(fileName);
        if (file == null || !Files.isRegularFile(file)) {
            return new Lines(false, null, List.of(), null);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return new Lines(true, null, List.of(), "READ_FAILED");
        }
        List<String> lines = new ArrayList<>();
        PackConditionals.State conditions = new PackConditionals.State(macros);
        PackSettingsPlan.PropertyLineJoiner joiner = new PackSettingsPlan.PropertyLineJoiner();
        for (String raw : new String(bytes, StandardCharsets.ISO_8859_1).split("\\R", -1)) {
            if (PackSettingsPlan.PropertyLineJoiner.isDirective(raw) && joiner.pending()) {
                // A directive line ends the open continuation.
                lines.add(joiner.finish());
            }
            Matcher directive = CONDITION.matcher(raw);
            if (directive.matches()) {
                try {
                    conditions.apply(directive.group(1), directive.group(2).trim());
                } catch (RuntimeException failure) {
                    return new Lines(true, ConformanceReport.textSha256(bytes), List.of(), "CONDITION_UNSUPPORTED");
                }
                continue;
            }
            String line = conditions.active() ? joiner.accept(raw) : null;
            if (line != null) lines.add(line);
        }
        String last = joiner.finish();
        if (last != null) lines.add(last);
        return new Lines(true, ConformanceReport.textSha256(bytes), List.copyOf(lines), null);
    }

    /**
     * The {@code key=value} entries of logical lines as java.util.Properties splits them: the key
     * ends at the first {@code =}, {@code :} or whitespace. Iris's OrderBackedProperties keeps one
     * value per key, the last, at the position where the key first appeared.
     */
    static Map<String, String> entries(List<String> lines) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : lines) {
            String content = line.strip();
            if (content.isEmpty() || content.startsWith("#") || content.startsWith("!")) continue;
            int end = 0;
            while (end < content.length() && "=: \t\f".indexOf(content.charAt(end)) < 0) end++;
            int value = end;
            while (value < content.length() && Character.isWhitespace(content.charAt(value))) value++;
            if (value < content.length() && "=:".indexOf(content.charAt(value)) >= 0) value++;
            entries.put(content.substring(0, end), content.substring(value).strip());
        }
        return entries;
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
