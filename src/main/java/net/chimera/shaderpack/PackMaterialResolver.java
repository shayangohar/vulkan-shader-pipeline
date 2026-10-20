package net.chimera.shaderpack;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable resolver for block.properties mappings, read through the same
 * continuation and conditional handling as shaders.properties. A token is a
 * block name, optionally followed by state selectors as Iris writes them
 * ({@code minecraft:tall_grass:half=lower}, {@code leaves:waterlogged=false},
 * {@code block:prop=a,b}). Tags are rejected instead of being guessed.
 */
public final class PackMaterialResolver {
    private static final Pattern MAPPING = Pattern.compile(
            "^\\s*block\\.(-?\\d+)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern PROPERTY = Pattern.compile("[a-z0-9_]+=[a-z0-9_,]+");
    private static final Pattern CONDITION = Pattern.compile(
            "^\\s*#\\s*(if|ifdef|ifndef|elif|else|endif)\\b(.*)$");

    /** One state-selector entry: the id applies when every listed property has a listed value. */
    record Selector(int id, Map<String, Set<String>> properties) {
        boolean matches(Function<String, String> stateValue) {
            for (Map.Entry<String, Set<String>> property : properties.entrySet()) {
                String value = stateValue.apply(property.getKey());
                if (value == null || !property.getValue().contains(value)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * The chunk render layer a {@code layer.<name>} line puts its blocks in, as OptiFine names them: the
     * pack draws them through that layer's program. Complementary and Bliss put glass, panes and beacons
     * in {@code layer.translucent}, so the water program draws them.
     */
    public enum Layer {
        SOLID, CUTOUT, CUTOUT_MIPPED, TRANSLUCENT;

        /** The layer a {@code layer.<name>} key names, or null for another name. */
        static Layer named(String name) {
            return switch (name) {
                case "solid" -> SOLID;
                case "cutout" -> CUTOUT;
                case "cutout_mipped" -> CUTOUT_MIPPED;
                case "translucent" -> TRANSLUCENT;
                default -> null;
            };
        }
    }

    private final Map<String, Integer> ids;
    private final Map<String, List<Selector>> selectors;
    private final Map<String, Layer> layers;
    private final List<String> deviations;
    private final Map<BlockState, Integer> stateCache = new ConcurrentHashMap<>();
    private final Map<Block, Optional<Layer>> layerCache = new ConcurrentHashMap<>();

    private PackMaterialResolver(
            Map<String, Integer> ids,
            Map<String, List<Selector>> selectors,
            Map<String, Layer> layers,
            Collection<String> deviations
    ) {
        this.ids = Collections.unmodifiableMap(new TreeMap<>(ids));
        Map<String, List<Selector>> copy = new TreeMap<>();
        selectors.forEach((name, list) -> copy.put(name, List.copyOf(list)));
        this.selectors = Collections.unmodifiableMap(copy);
        this.layers = Collections.unmodifiableMap(new TreeMap<>(layers));
        this.deviations = List.copyOf(new TreeSet<>(deviations));
    }

    /** What one block.properties read collects: ids, state selectors, each layer key's value, deviations. */
    private record Collected(Map<String, Integer> ids, Map<String, List<Selector>> selectors,
                             Map<Layer, String> layerLines, Set<String> deviations) {
        Collected() {
            this(new TreeMap<>(), new TreeMap<>(), new EnumMap<>(Layer.class), new TreeSet<>());
        }

        void clear() {
            ids.clear();
            selectors.clear();
            layerLines.clear();
        }
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
        return parse(shadersDir, PackEngineDefines.standard());
    }

    /**
     * Reads block.properties as Iris does: backslash continuations join into one
     * logical line, and {@code #if}/{@code #ifdef} branches are evaluated against
     * the engine's standard macros plus the pack's own defines. BSL puts every
     * id's block list on a continuation line and gates whole tables on
     * {@code MC_VERSION}.
     */
    public static ParseResult parse(Path shadersDir, Map<String, String> macros) {
        Path file = shadersDir == null ? null : shadersDir.resolve("block.properties");
        if (file == null || !Files.isRegularFile(file)) {
            return new ParseResult(empty(), false, null, List.of("BLOCK_PROPERTIES_MISSING"));
        }

        Collected collected = new Collected();
        String sourceHash;
        try {
            byte[] bytes = Files.readAllBytes(file);
            sourceHash = ConformanceReport.textSha256(bytes);
            PackConditionals.State conditions = new PackConditionals.State(macros);
            PackSettingsPlan.PropertyLineJoiner joiner = new PackSettingsPlan.PropertyLineJoiner();
            boolean failed = false;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (PackSettingsPlan.PropertyLineJoiner.isDirective(raw) && joiner.pending()) {
                    // A directive line ends the open continuation.
                    parseLine(joiner.finish(), collected);
                }
                Matcher directive = CONDITION.matcher(raw);
                if (directive.matches()) {
                    try {
                        conditions.apply(directive.group(1), directive.group(2).trim());
                    } catch (RuntimeException failure) {
                        collected.deviations().add("BLOCK_PROPERTIES_CONDITION_UNSUPPORTED");
                        collected.clear();
                        failed = true;
                        break;
                    }
                    continue;
                }
                if (!conditions.active()) {
                    continue;
                }
                String line = joiner.accept(raw);
                if (line != null) {
                    parseLine(line, collected);
                }
            }
            String last = failed ? null : joiner.finish();
            if (last != null) {
                parseLine(last, collected);
            }
        } catch (IOException e) {
            collected.deviations().add("BLOCK_PROPERTIES_READ_FAILED");
            sourceHash = null;
        }
        Map<String, Layer> layers = layers(collected.layerLines(), collected.deviations());
        return new ParseResult(new PackMaterialResolver(collected.ids(), collected.selectors(), layers,
                collected.deviations()), true, sourceHash, List.copyOf(collected.deviations()));
    }

    public static PackMaterialResolver empty() {
        return new PackMaterialResolver(Map.of(), Map.of(), Map.of(), List.of());
    }

    /**
     * The blocks each {@code layer.<name>} line names, by normalized block name. A properties file
     * keeps a key's last value, as Iris reads it; a block two layers name takes the later layer in
     * {@link Layer} order. A tag or a state selector names no block here, as in Iris.
     */
    private static Map<String, Layer> layers(Map<Layer, String> layerLines, Set<String> deviations) {
        Map<String, Layer> layers = new TreeMap<>();
        layerLines.forEach((layer, value) -> {
            for (String token : value.trim().split("\\s+")) {
                if (token.isBlank()) continue;
                String normalized = token.startsWith("%") || token.indexOf('=') >= 0 ? null : normalizeName(token);
                if (normalized == null) {
                    deviations.add(token.startsWith("%") ? "BLOCK_TAG_UNSUPPORTED" : "BLOCK_LAYER_INVALID");
                    continue;
                }
                layers.put(normalized, layer);
            }
        });
        return layers;
    }

    /** The layer the pack's {@code layer.*} lines put this block state's block in; null when none does. */
    public Layer layer(BlockState state) {
        if (state == null || layers.isEmpty()) {
            return null;
        }
        return layerCache.computeIfAbsent(state.getBlock(), block -> {
            try {
                return Optional.ofNullable(layers.get(BuiltInRegistries.BLOCK.getKey(block).toString()));
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** The {@code layer.*} overrides, by normalized block name. */
    public Map<String, Layer> layers() {
        return layers;
    }

    public int resolve(BlockState state) {
        if (state == null) {
            return -1;
        }
        return stateCache.computeIfAbsent(state, this::resolveUncached);
    }

    private int resolveUncached(BlockState state) {
        try {
            String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return resolveState(name, property -> stateValue(state, property));
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String stateValue(BlockState state, String propertyName) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
        return property == null ? null : valueName(state, property);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** A plain-name lookup: the id of the block's unconditioned mapping. */
    public int resolveName(String name) {
        String normalized = normalizeName(name);
        return normalized == null ? -1 : ids.getOrDefault(normalized, -1);
    }

    /**
     * The id of one block state. A matching selector is more specific than the
     * block's plain mapping, so the first one that matches, in file order, wins.
     */
    public int resolveState(String name, Function<String, String> stateValue) {
        String normalized = normalizeName(name);
        if (normalized == null) {
            return -1;
        }
        for (Selector selector : selectors.getOrDefault(normalized, List.of())) {
            if (selector.matches(stateValue)) {
                return selector.id();
            }
        }
        return ids.getOrDefault(normalized, -1);
    }

    public Map<String, Integer> mappings() {
        return ids;
    }

    public List<String> deviations() {
        return deviations;
    }

    private static void parseLine(String rawLine, Collected collected) {
        String line = rawLine;
        int comment = line.indexOf('#');
        if (comment >= 0) {
            line = line.substring(0, comment);
        }
        line = line.trim();
        if (line.startsWith("layer.")) {
            parseLayerLine(line, collected);
            return;
        }
        if (line.isEmpty() || !line.startsWith("block.")) {
            return;
        }
        Map<String, Integer> ids = collected.ids();
        Map<String, List<Selector>> selectors = collected.selectors();
        Set<String> deviations = collected.deviations();

        Matcher matcher = MAPPING.matcher(line);
        Integer mappedId = matchMappingId(matcher);
        if (mappedId == null) {
            deviations.add("BLOCK_PROPERTIES_INVALID");
            return;
        }
        int id = mappedId;

        for (String token : matcher.group(2).split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.startsWith("%")) {
                deviations.add("BLOCK_TAG_UNSUPPORTED");
                continue;
            }
            if (token.indexOf('=') >= 0) {
                addSelector(token, id, selectors, deviations);
                continue;
            }
            String normalized = normalizeName(token);
            if (normalized == null) {
                deviations.add("BLOCK_PROPERTIES_INVALID");
                continue;
            }
            // Iris BlockMaterialMapping (putIfAbsent): the first mapping in file order wins.
            Integer previous = ids.putIfAbsent(normalized, id);
            if (previous != null && previous != id) {
                deviations.add("BLOCK_MAPPING_CONFLICT");
            }
        }
    }

    /** Keeps a {@code layer.<name> = <blocks>} line's value under its layer; the key's last value wins. */
    private static void parseLayerLine(String line, Collected collected) {
        int equals = line.indexOf('=');
        Layer layer = equals < 0 ? null : Layer.named(line.substring("layer.".length(), equals).trim());
        if (layer == null) {
            collected.deviations().add("BLOCK_LAYER_INVALID");
            return;
        }
        collected.layerLines().put(layer, line.substring(equals + 1));
    }

    /**
     * Matches a {@code block.<id> = <names>} line and returns its id, or null when the line is
     * malformed, the id falls outside the short range, or no names follow it.
     */
    private static Integer matchMappingId(Matcher matcher) {
        if (!matcher.matches()) {
            return null;
        }
        int id;
        try {
            id = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (id < Short.MIN_VALUE || id > Short.MAX_VALUE || matcher.group(2).isBlank()) {
            return null;
        }
        return id;
    }

    /**
     * {@code [namespace:]block:prop=value[,value...][:prop=value...]}. The name
     * is every segment before the first property; a second name segment is the
     * path under the first as namespace.
     */
    private static void addSelector(
            String token,
            int id,
            Map<String, List<Selector>> selectors,
            Set<String> deviations
    ) {
        String[] segments = token.split(":", -1);
        int first = 0;
        while (first < segments.length && segments[first].indexOf('=') < 0) {
            first++;
        }
        String name = first == 1 ? segments[0]
                : first == 2 ? segments[0] + ":" + segments[1] : null;
        String normalized = normalizeName(name);
        if (normalized == null) {
            deviations.add("BLOCK_PROPERTIES_INVALID");
            return;
        }
        Map<String, Set<String>> properties = new TreeMap<>();
        for (int index = first; index < segments.length; index++) {
            String property = segments[index];
            if (!PROPERTY.matcher(property).matches()) {
                deviations.add("BLOCK_PROPERTIES_INVALID");
                return;
            }
            int equals = property.indexOf('=');
            Set<String> values = new TreeSet<>();
            for (String option : property.substring(equals + 1).split(",")) {
                if (!option.isEmpty()) values.add(option);
            }
            properties.merge(property.substring(0, equals), values,
                    (left, right) -> { left.retainAll(right); return left; });
        }
        selectors.computeIfAbsent(normalized, key -> new ArrayList<>())
                .add(new Selector(id, Collections.unmodifiableMap(properties)));
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
