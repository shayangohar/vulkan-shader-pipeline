package net.chimera.shaderpack;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable resolver for the plain block.properties subset used by M5.2.
 * Selectors and tags are deliberately rejected instead of being guessed.
 */
public final class PackMaterialResolver {
    private static final Pattern MAPPING = Pattern.compile(
            "^\\s*block\\.(-?\\d+)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    private final Map<String, Integer> ids;
    private final List<String> deviations;

    private PackMaterialResolver(Map<String, Integer> ids, Collection<String> deviations) {
        this.ids = Collections.unmodifiableMap(new TreeMap<>(ids));
        this.deviations = List.copyOf(new TreeSet<>(deviations));
    }

    public record ParseResult(
            PackMaterialResolver resolver,
            boolean present,
            String sourceHash,
            List<String> deviations
    ) {
        public ParseResult {
            resolver = resolver == null ? empty() : resolver;
            deviations = List.copyOf(new TreeSet<>(deviations));
        }
    }

    public static ParseResult parse(Path shadersDir) {
        Path file = shadersDir == null ? null : shadersDir.resolve("block.properties");
        if (file == null || !Files.isRegularFile(file)) {
            return new ParseResult(new PackMaterialResolver(Map.of(), List.of()), false, null,
                    List.of("BLOCK_PROPERTIES_MISSING"));
        }

        Map<String, Integer> ids = new TreeMap<>();
        Set<String> conflicts = new TreeSet<>();
        Set<String> deviations = new TreeSet<>();
        String sourceHash;
        try {
            byte[] bytes = Files.readAllBytes(file);
            sourceHash = ConformanceReport.textSha256(bytes);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                parseLine(line, ids, conflicts, deviations);
            }
        } catch (IOException e) {
            deviations.add("BLOCK_PROPERTIES_READ_FAILED");
            sourceHash = null;
        }
        return new ParseResult(new PackMaterialResolver(ids, deviations), true, sourceHash,
                List.copyOf(deviations));
    }

    public static PackMaterialResolver empty() {
        return new PackMaterialResolver(Map.of(), List.of());
    }

    public int resolve(BlockState state) {
        if (state == null) {
            return -1;
        }
        try {
            return resolveName(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    public int resolveName(String name) {
        String normalized = normalizeName(name);
        return normalized == null ? -1 : ids.getOrDefault(normalized, -1);
    }

    public Map<String, Integer> mappings() {
        return ids;
    }

    public List<String> deviations() {
        return deviations;
    }

    private static void parseLine(
            String rawLine,
            Map<String, Integer> ids,
            Set<String> conflicts,
            Set<String> deviations
    ) {
        String line = rawLine;
        int comment = line.indexOf('#');
        if (comment >= 0) {
            line = line.substring(0, comment);
        }
        line = line.trim();
        if (line.isEmpty() || !line.startsWith("block.")) {
            return;
        }

        Matcher matcher = MAPPING.matcher(line);
        if (!matcher.matches()) {
            deviations.add("BLOCK_PROPERTIES_INVALID");
            return;
        }

        int id;
        try {
            id = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            deviations.add("BLOCK_PROPERTIES_INVALID");
            return;
        }
        if (id < Short.MIN_VALUE || id > Short.MAX_VALUE) {
            deviations.add("BLOCK_PROPERTIES_INVALID");
            return;
        }

        String value = matcher.group(2);
        if (value.isBlank()) {
            deviations.add("BLOCK_PROPERTIES_INVALID");
            return;
        }

        for (String token : value.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.startsWith("%")) {
                deviations.add("BLOCK_TAG_UNSUPPORTED");
                continue;
            }
            if (token.indexOf('=') >= 0 || token.indexOf(',') >= 0) {
                deviations.add("BLOCK_SELECTOR_UNSUPPORTED");
                continue;
            }
            String normalized = normalizeName(token);
            if (normalized == null) {
                deviations.add("BLOCK_PROPERTIES_INVALID");
                continue;
            }
            if (conflicts.contains(normalized)) {
                continue;
            }
            Integer previous = ids.putIfAbsent(normalized, id);
            if (previous != null && previous != id) {
                ids.remove(normalized);
                conflicts.add(normalized);
                deviations.add("BLOCK_MAPPING_CONFLICT");
            }
        }
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        int colon = name.indexOf(':');
        if (colon < 0) {
            if (!PATH.matcher(name).matches()) {
                return null;
            }
            return "minecraft:" + name;
        }
        if (colon != name.lastIndexOf(':')) {
            return null;
        }
        String namespace = name.substring(0, colon);
        String path = name.substring(colon + 1);
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            return null;
        }
        return namespace + ":" + path;
    }
}
