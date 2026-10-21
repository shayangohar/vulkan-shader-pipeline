package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The pack's item.properties ids, read as Iris's IdMap.parseItemIdMap reads them
 * ({@link IdMapProperties}): the file goes through the same continuation and {@code #if}
 * handling as block.properties, each {@code item.<n>} key lists whitespace-separated item names
 * (namespace {@code minecraft} when none is given), a later line for the same key replaces an
 * earlier one as java.util.Properties does, tokens with state selectors are skipped, and an
 * unlisted item resolves to -1.
 */
public final class PackItemIdResolver {
    /** Iris's Object2IntMap default return value for an unlisted item. */
    public static final int UNMAPPED = -1;

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
        IdMapProperties.Lines lines = IdMapProperties.read(shadersDir, "item.properties", macros);
        if (!lines.present()) {
            return new ParseResult(empty(), false, List.of());
        }
        if (lines.failure() != null) {
            return new ParseResult(empty(), true, List.of("ITEM_PROPERTIES_" + lines.failure()));
        }
        List<String> invalidKeys = new ArrayList<>();
        Map<String, Integer> ids = IdMapProperties.ids(IdMapProperties.entries(lines.lines()), "item.", invalidKeys);
        return new ParseResult(new PackItemIdResolver(ids), true,
                invalidKeys.stream().map(key -> "ITEM_ID_KEY_INVALID:" + key).toList());
    }

    /** The pack id of an item name such as {@code minecraft:torch}, or {@link #UNMAPPED}. */
    public int resolve(String namespacedId) {
        return namespacedId == null ? UNMAPPED
                : ids.getOrDefault(IdMapProperties.namespaced(namespacedId), UNMAPPED);
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
}
