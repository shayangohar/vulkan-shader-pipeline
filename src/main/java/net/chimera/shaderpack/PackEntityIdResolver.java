package net.chimera.shaderpack;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** The pack's entity.properties ids, as the entity adapter and the entityId uniform read them. */
public final class PackEntityIdResolver {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_./-]+)?");

    private final Map<String, Integer> ids;
    private final List<String> deviations;
    private final boolean present;

    private PackEntityIdResolver(Map<String, Integer> ids, List<String> deviations, boolean present) {
        this.ids = Collections.unmodifiableMap(new TreeMap<>(ids));
        this.deviations = deviations.stream().distinct().sorted().toList();
        this.present = present;
    }

    public record ParseResult(
            PackEntityIdResolver resolver,
            boolean present,
            String sourceHash,
            List<String> deviations
    ) {
        public ParseResult {
            resolver = resolver == null ? empty() : resolver;
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }
    }

    /**
     * Reads entity.properties as Iris's IdMap.parseEntityIdMap does ({@link IdMapProperties}),
     * preprocessed with the id-map {@code macros}: {@code #if} blocks, {@code \} continuations, a
     * key's last value, and a later key's mapping over an earlier one for the same entity.
     * Chimera's vertex attribute holds an id from 0 to 65535; an id outside that is reported.
     */
    public static ParseResult parse(Path shadersDir, Map<String, String> macros) {
        IdMapProperties.Lines lines = IdMapProperties.read(shadersDir, "entity.properties", macros);
        if (!lines.present()) {
            return new ParseResult(empty(), false, null, List.of());
        }
        Set<String> deviations = new TreeSet<>();
        if (lines.failure() != null) {
            deviations.add("ENTITY_PROPERTIES_" + lines.failure());
        }
        List<String> invalidKeys = new ArrayList<>();
        Map<String, Integer> ids = new TreeMap<>();
        IdMapProperties.ids(IdMapProperties.entries(lines.lines()), "entity.", invalidKeys).forEach((name, id) -> {
            if (id < 0 || id > 0xFFFF) {
                deviations.add("ENTITY_ID_UNSUPPORTED:" + id);
            } else {
                ids.put(name, id);
            }
        });
        invalidKeys.forEach(key -> deviations.add("ENTITY_ID_UNSUPPORTED:" + key));
        boolean usable = !ids.isEmpty();
        if (usable) {
            deviations.add("ENTITY_ID_MAP_APPLIED");
        }
        return new ParseResult(new PackEntityIdResolver(ids, List.copyOf(deviations), usable),
                usable, lines.sourceHash(), List.copyOf(deviations));
    }

    public static PackEntityIdResolver empty() {
        return new PackEntityIdResolver(Map.of(), List.of(), false);
    }

    /** Resolves a registry name. Unknown names in a present map use Iris's unsigned sentinel. */
    public int resolveName(String name) {
        String normalized = normalizeName(name);
        if (!present) {
            return 0;
        }
        return normalized == null ? 0xFFFF : ids.getOrDefault(normalized, 0xFFFF);
    }

    /**
     * The id Iris's entityId uniform reads for a registry name: an unknown name in a present map
     * reads the map's -1 there, not the vertex attribute's unsigned sentinel.
     */
    public int uniformId(String name) {
        int id = resolveName(name);
        return id == 0xFFFF ? -1 : id;
    }

    /** Resolves a live entity type without changing the render state. */
    public int resolve(EntityType<?> type) {
        if (type == null) {
            return resolveName(null);
        }
        try {
            return resolveName(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        } catch (RuntimeException ignored) {
            return resolveName(null);
        }
    }

    public boolean present() {
        return present;
    }

    public Map<String, Integer> mappings() {
        return ids;
    }

    public List<String> deviations() {
        return deviations;
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank() || !NAME.matcher(value).matches()) {
            return null;
        }
        return value.indexOf(':') < 0 ? "minecraft:" + value : value;
    }
}
