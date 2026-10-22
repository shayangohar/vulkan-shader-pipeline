package net.chimera.shaderpack;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable resolver for block.properties mappings, read through the same
 * continuation and conditional handling as shaders.properties. A token is a
 * block name, optionally followed by state predicates as Iris writes them
 * ({@code minecraft:tall_grass:half=lower}, {@code leaves:waterlogged=false}),
 * or a block tag ({@code %walls:north=low}).
 *
 * <p>A state takes the first entry, in file order, that matches it, as Iris's
 * BlockMaterialMapping puts each id's states with putIfAbsent: a plain name
 * matches every state, so it wins over a later predicate entry for that block.
 * Tag entries come after every block entry, as Iris adds them last.</p>
 */
public final class PackMaterialResolver {
    private static final Pattern MAPPING = Pattern.compile(
            "^\\s*block\\.(-?\\d+)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern CONDITION = Pattern.compile(
            "^\\s*#\\s*(if|ifdef|ifndef|elif|else|endif)\\b(.*)$");
    private static final Pattern DEFINE = Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_]\\w*)(?:\\s+(.*?))?\\s*$");
    private static final Pattern UNDEFINE = Pattern.compile("^\\s*#\\s*undef\\s+([A-Za-z_]\\w*)\\s*$");

    /**
     * One block entry: its id applies to a state whose value for each predicate equals the
     * predicate's, as Iris's checkState compares them. A predicate on a property the block does
     * not have is ignored, as Iris ignores it; a plain name has none and matches every state.
     */
    record Entry(int id, Map<String, String> predicates) {
        boolean matches(Function<String, String> stateValue) {
            for (Map.Entry<String, String> predicate : predicates.entrySet()) {
                String value = stateValue.apply(predicate.getKey());
                if (value != null && !value.equals(predicate.getValue())) {
                    return false;
                }
            }
            return true;
        }
    }

    /** One {@code %tag} entry: the block tag it names, and the entry its states then match. */
    record TagEntry(TagKey<Block> key, Entry entry) {}

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
    private final Map<String, List<Entry>> entries;
    private final List<TagEntry> tags;
    private final Map<String, Layer> layers;
    private final List<String> deviations;
    private final Map<BlockState, Integer> stateCache = new ConcurrentHashMap<>();
    private final Map<Block, Optional<Layer>> layerCache = new ConcurrentHashMap<>();

    private PackMaterialResolver(Collected collected, Map<String, Layer> layers) {
        this.ids = Collections.unmodifiableMap(new TreeMap<>(collected.ids()));
        Map<String, List<Entry>> copy = new TreeMap<>();
        collected.entries().forEach((name, list) -> copy.put(name, List.copyOf(list)));
        this.entries = Collections.unmodifiableMap(copy);
        this.tags = List.copyOf(collected.tags());
        this.layers = Collections.unmodifiableMap(new TreeMap<>(layers));
        this.deviations = List.copyOf(new TreeSet<>(collected.deviations()));
    }

    /**
     * What one block.properties read collects: each name's plain id, every block's entries and
     * the tag entries in file order, each layer key's value, deviations.
     */
    private record Collected(Map<String, Integer> ids, Map<String, List<Entry>> entries, List<TagEntry> tags,
                             Map<Layer, String> layerLines, Set<String> deviations) {
        Collected() {
            this(new TreeMap<>(), new TreeMap<>(), new ArrayList<>(), new EnumMap<>(Layer.class), new TreeSet<>());
        }

        void clear() {
            ids.clear();
            entries.clear();
            tags.clear();
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
            readLines(Files.readAllLines(file, StandardCharsets.UTF_8), macros, collected);
        } catch (IOException e) {
            collected.deviations().add("BLOCK_PROPERTIES_READ_FAILED");
            sourceHash = null;
        }
        Map<String, Layer> layers = layers(collected.layerLines(), collected.deviations());
        return new ParseResult(new PackMaterialResolver(collected, layers), true, sourceHash,
                List.copyOf(collected.deviations()));
    }

    /**
     * The file's lines as Iris's preprocessor (JCPP) hands them to IdMap: inactive branches drop,
     * and each line reads the file's own #define macros made before it. Photon, Bloop and
     * superDuperVanilla name their tags through such macros ({@code #define tags_leaves %leaves}).
     */
    private static void readLines(List<String> lines, Map<String, String> macros, Collected collected) {
        PackConditionals.State conditions = new PackConditionals.State(macros);
        PackSettingsPlan.PropertyLineJoiner joiner = new PackSettingsPlan.PropertyLineJoiner();
        for (String raw : lines) {
            if (PackSettingsPlan.PropertyLineJoiner.isDirective(raw) && joiner.pending()) {
                // A directive line ends the open continuation.
                parseExpanded(joiner.finish(), conditions, collected);
            }
            Matcher directive = CONDITION.matcher(raw);
            if (directive.matches()) {
                try {
                    conditions.apply(directive.group(1), directive.group(2).trim());
                } catch (RuntimeException failure) {
                    collected.deviations().add("BLOCK_PROPERTIES_CONDITION_UNSUPPORTED");
                    collected.clear();
                    return;
                }
                continue;
            }
            if (!conditions.active() || defineMacro(raw, conditions)) {
                continue;
            }
            String line = joiner.accept(raw);
            if (line != null) {
                parseExpanded(line, conditions, collected);
            }
        }
        String last = joiner.finish();
        if (last != null) {
            parseExpanded(last, conditions, collected);
        }
    }

    /** Applies a #define or #undef line to the macros later lines read; false for any other line. */
    private static boolean defineMacro(String raw, PackConditionals.State conditions) {
        Matcher define = DEFINE.matcher(raw);
        if (define.matches()) {
            conditions.define(define.group(1), define.group(2));
            return true;
        }
        Matcher undefine = UNDEFINE.matcher(raw);
        if (undefine.matches()) {
            conditions.undefine(undefine.group(1));
            return true;
        }
        return false;
    }

    private static void parseExpanded(String line, PackConditionals.State conditions, Collected collected) {
        parseLine(PackConditionals.expandMacros(line, conditions.macros()), collected);
    }

    public static PackMaterialResolver empty() {
        return new PackMaterialResolver(new Collected(), Map.of());
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
            Function<String, String> stateValue = property -> stateValue(state, property);
            int id = resolveState(name, stateValue);
            return id != -1 ? id : resolveTags(state::is, stateValue);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    /**
     * The id of the first tag entry, in file order, whose tag holds the state and whose predicates
     * match it, or -1. Iris adds tag entries after every block entry (createBlockStateIdMap), so a
     * state a block entry maps never reaches its tags; a tag the registries lack holds nothing.
     */
    int resolveTags(Predicate<TagKey<Block>> holds, Function<String, String> stateValue) {
        for (TagEntry tag : tags) {
            if (holds.test(tag.key()) && tag.entry().matches(stateValue)) return tag.entry().id();
        }
        return -1;
    }

    private static String stateValue(BlockState state, String propertyName) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
        return property == null ? null : valueName(state, property);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** A plain-name lookup: the id of the block's first unconditioned mapping. */
    public int resolveName(String name) {
        String normalized = normalizeName(name);
        return normalized == null ? -1 : ids.getOrDefault(normalized, -1);
    }

    /**
     * The id of one block state: the first of the block's entries, in file order, that matches it.
     * {@code stateValue} gives a property's value, or null when the block has no such property.
     */
    public int resolveState(String name, Function<String, String> stateValue) {
        String normalized = normalizeName(name);
        if (normalized == null) {
            return -1;
        }
        for (Entry entry : entries.getOrDefault(normalized, List.of())) {
            if (entry.matches(stateValue)) {
                return entry.id();
            }
        }
        return -1;
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
        Set<String> deviations = collected.deviations();
        Matcher matcher = MAPPING.matcher(line);
        Integer mappedId = matchMappingId(matcher);
        if (mappedId == null) {
            deviations.add("BLOCK_PROPERTIES_INVALID");
            return;
        }
        for (String token : matcher.group(2).split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.startsWith("%")) {
                addTag(token, mappedId, collected);
                continue;
            }
            addEntry(token, mappedId, collected);
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
     * malformed or the id falls outside the short range. A line with no names maps nothing, as in
     * Iris: superDuperVanilla keeps empty ids such as {@code block.10100 =} for user additions.
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
        if (id < Short.MIN_VALUE || id > Short.MAX_VALUE) {
            return null;
        }
        return id;
    }

    /**
     * One {@code [namespace:]block[:prop=value...]} token, split as Iris's BlockEntry.parse splits it
     * on colons: a second segment without '=' is the path under the first as namespace, and each
     * later segment is a predicate. A predicate not of the form key=value is skipped, and a key's
     * last value wins. The first plain mapping of a name is its resolveName id.
     */
    private static void addEntry(String token, int id, Collected collected) {
        Token parsed = parseToken(token, collected.deviations());
        if (parsed == null) return;
        collected.entries().computeIfAbsent(parsed.name(), key -> new ArrayList<>())
                .add(new Entry(id, parsed.predicates()));
        if (parsed.hasPredicates()) return;
        Integer previous = collected.ids().putIfAbsent(parsed.name(), id);
        if (previous != null && previous != id) {
            collected.deviations().add("BLOCK_MAPPING_CONFLICT");
        }
    }

    /**
     * One {@code %[namespace:]tag[:prop=value...]} token, read as BlockEntry.parse reads it once its
     * '%' signs are gone, with the tag's name lower-cased as BlockMaterialMapping.addTag compares
     * names ignoring case.
     */
    private static void addTag(String token, int id, Collected collected) {
        Token parsed = parseToken(token.replace("%", "").toLowerCase(Locale.ROOT), collected.deviations());
        if (parsed == null) return;
        TagKey<Block> key = TagKey.create(Registries.BLOCK, ResourceLocation.parse(parsed.name()));
        collected.tags().add(new TagEntry(key, new Entry(id, parsed.predicates())));
    }

    /** A token's normalized name and predicates, and whether it named any predicate segment. */
    private record Token(String name, Map<String, String> predicates, boolean hasPredicates) {}

    /** Splits a token as BlockEntry.parse does; null, with a deviation, when its name is invalid. */
    private static Token parseToken(String token, Set<String> deviations) {
        String[] segments = token.split(":");
        int first = segments.length > 1 && segments[1].indexOf('=') >= 0 ? 1 : Math.min(segments.length, 2);
        String name = first == 1 ? segments[0] : segments[0] + ":" + segments[1];
        String normalized = normalizeName(name);
        if (normalized == null) {
            deviations.add("BLOCK_PROPERTIES_INVALID");
            return null;
        }
        Map<String, String> predicates = new TreeMap<>();
        for (int index = first; index < segments.length; index++) {
            String[] parts = segments[index].split("=");
            if (parts.length != 2) {
                deviations.add("BLOCK_PROPERTIES_INVALID");
                continue;
            }
            predicates.put(parts[0], parts[1]);
        }
        return new Token(normalized, Collections.unmodifiableMap(predicates), first < segments.length);
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
