package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pack's item.properties ids, read as Iris's IdMap.parseItemIdMap reads them: the file goes
 * through the same continuation and {@code #if} handling as block.properties, each
 * {@code item.<n>} key lists whitespace-separated item names (namespace {@code minecraft} when
 * none is given), a later line for the same key replaces an earlier one as java.util.Properties
 * does, tokens with state selectors are skipped, and an unlisted item resolves to -1.
 */
public final class PackItemIdResolver {
    /** Iris's Object2IntMap default return value for an unlisted item. */
    public static final int UNMAPPED = -1;

    private static final Pattern MAPPING = Pattern.compile("^\\s*item\\.(-?\\d+)\\s*[=:]\\s*(.*?)\\s*$");
    private static final Pattern CONDITION = Pattern.compile(
            "^\\s*#\\s*(if|ifdef|ifndef|elif|else|endif)\\b(.*)$");

    private static final PackItemIdResolver EMPTY = new PackItemIdResolver(Map.of());

    private final Map<String, Integer> ids;

    private PackItemIdResolver(Map<String, Integer> ids) {
        this.ids = Collections.unmodifiableMap(new TreeMap<>(ids));
    }

    public record ParseResult(PackItemIdResolver resolver, boolean present, List<String> deviations) {
        public ParseResult {
            resolver = resolver == null ? empty() : resolver;
            deviations = List.copyOf(new TreeSet<>(deviations));
        }
    }

    public static PackItemIdResolver empty() {
        return EMPTY;
    }

    public static ParseResult parse(Path shadersDir, Map<String, String> macros) {
        Path file = shadersDir == null ? null : shadersDir.resolve("item.properties");
        if (file == null || !Files.isRegularFile(file)) {
            return new ParseResult(empty(), false, List.of());
        }
        Map<String, String> entries = new LinkedHashMap<>();
        Set<String> deviations = new TreeSet<>();
        try {
            PackConditionals.State conditions = new PackConditionals.State(macros);
            PackSettingsPlan.PropertyLineJoiner joiner = new PackSettingsPlan.PropertyLineJoiner();
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (PackSettingsPlan.PropertyLineJoiner.isDirective(raw) && joiner.pending()) {
                    // A directive line ends the open continuation.
                    readEntry(joiner.finish(), entries);
                }
                Matcher directive = CONDITION.matcher(raw);
                if (directive.matches()) {
                    try {
                        conditions.apply(directive.group(1), directive.group(2).trim());
                    } catch (RuntimeException failure) {
                        deviations.add("ITEM_PROPERTIES_CONDITION_UNSUPPORTED");
                        return new ParseResult(empty(), true, List.copyOf(deviations));
                    }
                    continue;
                }
                if (!conditions.active()) {
                    continue;
                }
                String line = joiner.accept(raw);
                if (line != null) {
                    readEntry(line, entries);
                }
            }
            String last = joiner.finish();
            if (last != null) {
                readEntry(last, entries);
            }
        } catch (IOException e) {
            deviations.add("ITEM_PROPERTIES_READ_FAILED");
            return new ParseResult(empty(), true, List.copyOf(deviations));
        }
        return new ParseResult(fromEntries(entries), true, List.copyOf(deviations));
    }

    /** Builds the map from {@code item.<n>} keys and their value lists, in file order. */
    static PackItemIdResolver fromEntries(Map<String, String> entries) {
        Map<String, Integer> ids = new TreeMap<>();
        entries.forEach((id, value) -> {
            for (String token : value.trim().split("\\s+")) {
                if (token.isEmpty() || token.contains("=")) {
                    continue;
                }
                ids.put(namespaced(token), Integer.parseInt(id));
            }
        });
        return new PackItemIdResolver(ids);
    }

    /** The pack id of an item name such as {@code minecraft:torch}, or {@link #UNMAPPED}. */
    public int resolve(String namespacedId) {
        return namespacedId == null ? UNMAPPED : ids.getOrDefault(namespaced(namespacedId), UNMAPPED);
    }

    /**
     * Iris's currentRenderedItemId for an item that is not a block item: its listed id, else
     * {@link #UNMAPPED}, or 0 when the pack has no item.properties (Iris's empty map).
     */
    public int renderedItemId(String namespacedId) {
        return this == EMPTY ? 0 : resolve(namespacedId);
    }

    public Map<String, Integer> mappings() {
        return ids;
    }

    private static void readEntry(String line, Map<String, String> entries) {
        String content = line.strip();
        if (content.startsWith("#") || content.startsWith("!")) {
            return;
        }
        Matcher matcher = MAPPING.matcher(content);
        if (!matcher.matches()) {
            return;
        }
        try {
            Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return;
        }
        entries.remove(matcher.group(1));
        entries.put(matcher.group(1), matcher.group(2));
    }

    /** Iris's NamespacedId: a name without a namespace is in {@code minecraft}. */
    private static String namespaced(String name) {
        return name.indexOf(':') < 0 ? "minecraft:" + name : name;
    }
}
