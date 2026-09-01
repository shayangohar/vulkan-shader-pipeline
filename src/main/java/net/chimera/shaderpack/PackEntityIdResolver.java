package net.chimera.shaderpack;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable resolver for the basic entity.properties subset used by M6.3. */
public final class PackEntityIdResolver {
    private static final Pattern MAPPING = Pattern.compile(
            "^\\s*entity\\.(-?\\d+)\\s*=\\s*(.*?)\\s*$");
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

    public static ParseResult parse(Path shadersDir) {
        Path file = shadersDir == null ? null : shadersDir.resolve("entity.properties");
        if (file == null || !Files.isRegularFile(file)) {
            return new ParseResult(empty(), false, null, List.of());
        }

        Map<String, Integer> ids = new TreeMap<>();
        Set<String> conflicts = new TreeSet<>();
        Set<String> deviations = new TreeSet<>();
        String sourceHash;
        try {
            byte[] bytes = Files.readAllBytes(file);
            sourceHash = ConformanceReport.sha256(bytes);
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                parseLine(raw, ids, conflicts, deviations);
            }
        } catch (IOException e) {
            sourceHash = null;
            deviations.add("ENTITY_PROPERTIES_READ_FAILED");
        }

        boolean usable = !ids.isEmpty();
        if (usable) {
            deviations.add("ENTITY_ID_MAP_APPLIED");
        }
        return new ParseResult(new PackEntityIdResolver(ids, List.copyOf(deviations), usable),
                usable, sourceHash, List.copyOf(deviations));
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

    private static void parseLine(
            String raw,
            Map<String, Integer> ids,
            Set<String> conflicts,
            Set<String> deviations
    ) {
        String line = raw == null ? "" : raw;
        int comment = line.indexOf('#');
        if (comment >= 0) {
            line = line.substring(0, comment);
        }
        line = line.trim();
        if (line.isEmpty() || !line.startsWith("entity.")) {
            return;
        }
        Matcher matcher = MAPPING.matcher(line);
        if (!matcher.matches()) {
            deviations.add("ENTITY_ID_UNSUPPORTED:property");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            deviations.add("ENTITY_ID_UNSUPPORTED:property");
            return;
        }
        if (id < 0 || id > 0xFFFF) {
            deviations.add("ENTITY_ID_UNSUPPORTED:" + matcher.group(1));
            return;
        }
        String value = matcher.group(2);
        if (value.isBlank()) {
            deviations.add("ENTITY_ID_UNSUPPORTED:property");
            return;
        }
        for (String token : value.split("\\s+")) {
            String normalized = normalizeName(token);
            if (normalized == null) {
                deviations.add("ENTITY_ID_UNSUPPORTED:" + token);
                continue;
            }
            if (conflicts.contains(normalized)) {
                continue;
            }
            Integer previous = ids.putIfAbsent(normalized, id);
            if (previous != null && previous != id) {
                ids.remove(normalized);
                conflicts.add(normalized);
                deviations.add("ENTITY_ID_UNSUPPORTED:" + normalized);
            }
        }
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank() || !NAME.matcher(value).matches()) {
            return null;
        }
        return value.indexOf(':') < 0 ? "minecraft:" + value : value;
    }
}
