package net.chimera.shaderpack;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The id-map files (block.properties, item.properties, entity.properties) read as Iris reads them.
 * PropertiesPreprocessor runs the file through its C preprocessor (JCPP), IdMap.loadProperties
 * rejoins the continuations that preprocessing split and loads the text into OrderBackedProperties,
 * and IdMap.parseIdMap reads the item and entity keys. {@link #read} is the first two steps and
 * {@link #ids} the third.
 */
final class IdMapProperties {
    /** A {@code #} line JCPP reads as a directive: {@code #} and a name with nothing between. */
    private static final Pattern DIRECTIVE = Pattern.compile("^#([A-Za-z_]\\w*)(.*)$");
    /** A {@code #define} argument: the name, a {@code (} right after it for a function-like macro, the body. */
    private static final Pattern MACRO = Pattern.compile("^([A-Za-z_]\\w*)(\\()?\\s*(.*?)\\s*$");
    private static final Set<String> CONDITIONALS = Set.of("if", "ifdef", "ifndef", "elif", "else", "endif");
    /** Stands in for a backslash while a line expands, as Iris hides backslashes from JCPP. */
    private static final String BACKSLASH = "IRIS_PASSTHROUGHBACKSLASH";
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
        String text;
        try {
            // Iris reads id maps as ISO-8859-1, which never fails on a stray byte.
            text = preprocess(new String(bytes, StandardCharsets.ISO_8859_1), macros);
        } catch (RuntimeException failure) {
            return new Loaded(true, hash, Map.of(), "CONDITION_UNSUPPORTED");
        }
        text = CONTINUED_PAST_EMPTY_LINES.matcher(text).replaceAll(" ");
        text = RUN_ON_BLOCK_KEY.matcher(text).replaceAll("\nblock.");
        OrderedProperties properties = new OrderedProperties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            return new Loaded(true, hash, Map.of(), "MALFORMED");
        }
        return new Loaded(true, hash, Collections.unmodifiableMap(properties.entries), null);
    }

    /**
     * The file as PropertiesPreprocessor hands it on. Each line is trimmed and blank lines are dropped.
     * A {@code #} line naming a directive acts as JCPP's directive and any other {@code #} line is a
     * comment; both are left empty, as are the lines of an inactive branch. Every other line loses its
     * {@code #} signs and expands the macros defined before it. A conditional still open at the end is
     * kept, as JCPP keeps it.
     */
    private static String preprocess(String text, Map<String, String> macros) {
        PackConditionals.State conditions = new PackConditionals.State(macros);
        Map<String, String> expansions = new HashMap<>(macros == null ? Map.of() : macros);
        StringBuilder out = new StringBuilder(text.length());
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                Matcher directive = DIRECTIVE.matcher(line);
                if (directive.matches()) {
                    apply(directive.group(1), stripComments(directive.group(2)), conditions, expansions);
                }
            } else if (conditions.active()) {
                String hidden = line.replace("#", "").replace("\\", BACKSLASH);
                out.append(PackConditionals.expandMacros(hidden, expansions).replace(BACKSLASH, "\\"));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * One directive. Conditionals nest inside inactive branches; {@code #define} and {@code #undef}
     * apply only in an active one. JCPP reports {@code #error} and {@code #warning}, cannot include a
     * file into a properties text, and skips a name it does not know; none of those changes the map.
     */
    private static void apply(String name, String argument, PackConditionals.State conditions,
                              Map<String, String> expansions) {
        if (CONDITIONALS.contains(name)) {
            conditions.apply(name, argument);
            return;
        }
        if (!conditions.active()) return;
        if (name.equals("define")) {
            Matcher macro = MACRO.matcher(argument);
            // A function-like macro is not expanded here.
            if (!macro.matches() || macro.group(2) != null) return;
            conditions.define(macro.group(1), macro.group(3));
            // A macro defined with no body expands to nothing.
            expansions.put(macro.group(1), macro.group(3));
        } else if (name.equals("undef") && !argument.isEmpty()) {
            String macro = argument.split("\\s+")[0];
            conditions.undefine(macro);
            expansions.remove(macro);
        }
    }

    /** A directive's argument without its C comments, which JCPP skips (PackConditionals.withoutComments). */
    private static String stripComments(String argument) {
        return PackConditionals.withoutComments(argument == null ? "" : argument).trim();
    }

    /** java.util.Properties that also keeps each key where it first appeared, as OrderBackedProperties does. */
    private static final class OrderedProperties extends Properties {
        private final transient Map<String, String> entries = new LinkedHashMap<>();

        @Override
        public synchronized Object put(Object key, Object value) {
            entries.put((String) key, (String) value);
            return super.put(key, value);
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
