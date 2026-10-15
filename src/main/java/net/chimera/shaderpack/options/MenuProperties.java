package net.chimera.shaderpack.options;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * The menu entries of shaders.properties: {@code screen}, {@code screen.<id>},
 * {@code screen.columns}, {@code screen.<id>.columns}, {@code sliders} and {@code profile.<name>}.
 *
 * <p>These come from the file as written, not preprocessed: a properties reader takes {@code #if}
 * lines for comments, so when a key appears in several branches the last one wins, as in OptiFine
 * and Iris. Lists split on runs of spaces.
 */
public record MenuProperties(
        Optional<List<String>> mainScreen,
        Optional<Integer> mainColumns,
        Map<String, List<String>> subScreens,
        Map<String, Integer> subColumns,
        Set<String> sliders,
        Map<String, List<String>> profiles,
        List<String> problems
) {
    public MenuProperties {
        subScreens = Collections.unmodifiableMap(subScreens);
        subColumns = Collections.unmodifiableMap(subColumns);
        sliders = Set.copyOf(sliders);
        profiles = Collections.unmodifiableMap(profiles);
        problems = List.copyOf(problems);
    }

    public static MenuProperties empty() {
        return new MenuProperties(Optional.empty(), Optional.empty(), Map.of(), Map.of(), Set.of(), Map.of(),
                List.of());
    }

    /** Reads {@code shaders.properties} under {@code shadersRoot}; empty when there is none. */
    public static MenuProperties read(Path shadersRoot) {
        Path file = shadersRoot.resolve("shaders.properties");
        if (!Files.isRegularFile(file)) return empty();
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return empty();
        }
    }

    static MenuProperties parse(String contents) {
        Map<String, String> entries = orderedEntries(new StringReader(contents));
        Builder builder = new Builder();
        entries.forEach(builder::accept);
        return builder.build();
    }

    /** Properties in first-seen key order, each with its last value. */
    static Map<String, String> orderedEntries(Reader reader) {
        Map<String, String> ordered = new LinkedHashMap<>();
        Properties properties = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                ordered.put((String) key, (String) value);
                return super.put(key, value);
            }
        };
        try {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException malformed) {
            // Whatever loaded before the bad line stays, as the properties reader leaves it.
        }
        return ordered;
    }

    private static final class Builder {
        private List<String> mainScreen;
        private Integer mainColumns;
        private final Map<String, List<String>> subScreens = new HashMap<>();
        private final Map<String, Integer> subColumns = new HashMap<>();
        private List<String> sliders = List.of();
        private final Map<String, List<String>> profiles = new LinkedHashMap<>();
        private final List<String> problems = new ArrayList<>();

        void accept(String key, String value) {
            if (key.equals("sliders")) sliders = list(value);
            if (key.startsWith("profile.")) profiles.put(key.substring("profile.".length()), list(value));
            if (key.equals("screen.columns")) {
                columns(key, value).ifPresent(columns -> mainColumns = columns);
                return;
            }
            if (key.startsWith("screen.") && key.endsWith(".columns") && key.length() > "screen..columns".length()) {
                String screen = key.substring("screen.".length(), key.length() - ".columns".length());
                columns(key, value).ifPresent(columns -> subColumns.put(screen, columns));
                return;
            }
            if (key.equals("screen")) mainScreen = list(value);
            if (key.startsWith("screen.")) subScreens.put(key.substring("screen.".length()), list(value));
        }

        private Optional<Integer> columns(String key, String value) {
            try {
                return Optional.of(Integer.parseInt(value));
            } catch (NumberFormatException malformed) {
                problems.add("MENU_COLUMNS_MALFORMED:" + key + "=" + value);
                return Optional.empty();
            }
        }

        private static List<String> list(String value) {
            return Arrays.asList(value.split(" +"));
        }

        MenuProperties build() {
            return new MenuProperties(Optional.ofNullable(mainScreen), Optional.ofNullable(mainColumns),
                    subScreens, subColumns, Set.copyOf(sliders), profiles, problems);
        }
    }
}
