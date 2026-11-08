package net.chimera.shaderpack;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The numbering behind both the {@code biome} input and the {@code BIOME_*} constants.
 *
 * <p>An expression such as {@code in(biome, BIOME_GROVE)} only means anything when the constant
 * and the value it is compared against come from the same table, so this is the one place that
 * hands out either. Iris numbers the vanilla biomes in the order Minecraft's {@code Biomes}
 * registers them (MixinBiomes: the_void 0, plains 1, ... end_barrens 63), and packs write those
 * numbers too: Bliss tests {@code in(biome, 6, 52, 7)} for swamps and 5 for the desert.
 *
 * <p>A biome outside the table -- a modded one, or any other namespace -- reads {@link #UNKNOWN},
 * 0, as Iris's map answers a key it lacks.
 */
public final class BiomeIds {
    private static final String PREFIX = "BIOME_";
    private static final String VANILLA_NAMESPACE = "minecraft";

    /** What a biome Iris's map lacks reads: Object2IntMap.getInt's default, the_void's number. */
    public static final int UNKNOWN = 0;

    /** The vanilla 1.21.1 biome names, in Biomes' registration order, which numbers them. */
    private static final List<String> VANILLA = List.of(
            "the_void", "plains", "sunflower_plains", "snowy_plains", "ice_spikes", "desert", "swamp",
            "mangrove_swamp", "forest", "flower_forest", "birch_forest", "dark_forest",
            "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga", "taiga",
            "snowy_taiga", "savanna", "savanna_plateau", "windswept_hills", "windswept_gravelly_hills",
            "windswept_forest", "windswept_savanna", "jungle", "sparse_jungle", "bamboo_jungle",
            "badlands", "eroded_badlands", "wooded_badlands", "meadow", "cherry_grove", "grove",
            "snowy_slopes", "frozen_peaks", "jagged_peaks", "stony_peaks", "river", "frozen_river",
            "beach", "snowy_beach", "stony_shore", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean",
            "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean",
            "mushroom_fields", "dripstone_caves", "lush_caves", "deep_dark", "nether_wastes",
            "warped_forest", "crimson_forest", "soul_sand_valley", "basalt_deltas", "the_end",
            "end_highlands", "end_midlands", "small_end_islands", "end_barrens");
    /**
     * Biomes of later versions that packs name: Complementary, Escheridia and Rethinking Voxels test
     * BIOME_PALE_GARDEN. Iris 1.21.1 has no such constant and drops the uniform, which reads 0; a
     * number past the vanilla ones, which no 1.21.1 biome reads, gives the same 0 without refusing
     * the program for an unknown name (KNOW-790).
     */
    private static final List<String> LATER = List.of("pale_garden");

    private static final Map<String, Integer> IDS = ids();
    private static final PackExpression.Constants CONSTANTS = buildConstants();

    private BiomeIds() {}

    /** The {@code BIOME_*}, {@code CAT_*} and {@code PPT_*} symbols the packs write. */
    public static PackExpression.Constants constants() {
        return CONSTANTS;
    }

    /** The number for a biome key, or {@link #UNKNOWN} when no constant names it. */
    public static int id(ResourceLocation key) {
        if (key == null || !VANILLA_NAMESPACE.equals(key.getNamespace())) {
            return UNKNOWN;
        }
        Integer id = IDS.get(key.getPath());
        return id == null ? UNKNOWN : id;
    }

    private static Map<String, Integer> ids() {
        Map<String, Integer> result = new TreeMap<>();
        int id = 0;
        for (String name : VANILLA) {
            result.put(name, id++);
        }
        for (String name : LATER) {
            result.put(name, id++);
        }
        return Map.copyOf(result);
    }

    /**
     * Iris's biome categories, in its {@code BiomeCategories} order: the {@code CAT_*} constants
     * are these ordinals and {@code biome_category} answers one of them.
     */
    public enum Category {
        NONE, TAIGA, EXTREME_HILLS, JUNGLE, MESA, PLAINS, SAVANNA, ICY, THE_END, BEACH, FOREST,
        OCEAN, DESERT, RIVER, SWAMP, MUSHROOM, NETHER, MOUNTAIN, UNDERGROUND
    }

    /**
     * Iris 1.21.1's {@code BiomeUniforms.getBiomeCategory}, line for line: the first tag a biome
     * has picks its category. Iris never answers {@code SAVANNA} on this version.
     */
    private static final List<Map.Entry<TagKey<Biome>, Category>> CATEGORY_TAGS = List.of(
            Map.entry(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS, Category.NONE),
            Map.entry(BiomeTags.HAS_VILLAGE_SNOWY, Category.ICY),
            Map.entry(BiomeTags.IS_HILL, Category.EXTREME_HILLS),
            Map.entry(BiomeTags.IS_TAIGA, Category.TAIGA),
            Map.entry(BiomeTags.IS_OCEAN, Category.OCEAN),
            Map.entry(BiomeTags.IS_JUNGLE, Category.JUNGLE),
            Map.entry(BiomeTags.IS_FOREST, Category.FOREST),
            Map.entry(BiomeTags.IS_BADLANDS, Category.MESA),
            Map.entry(BiomeTags.IS_NETHER, Category.NETHER),
            Map.entry(BiomeTags.IS_END, Category.THE_END),
            Map.entry(BiomeTags.IS_BEACH, Category.BEACH),
            Map.entry(BiomeTags.HAS_DESERT_PYRAMID, Category.DESERT),
            Map.entry(BiomeTags.IS_RIVER, Category.RIVER),
            Map.entry(BiomeTags.HAS_CLOSER_WATER_FOG, Category.SWAMP),
            Map.entry(BiomeTags.PLAYS_UNDERWATER_MUSIC, Category.UNDERGROUND),
            Map.entry(BiomeTags.WITHOUT_ZOMBIE_SIEGES, Category.MUSHROOM),
            Map.entry(BiomeTags.IS_MOUNTAIN, Category.MOUNTAIN));

    /** A biome's category, by {@link #CATEGORY_TAGS}, else {@code PLAINS}. */
    public static Category category(Holder<Biome> biome) {
        if (biome == null) return Category.NONE;
        for (Map.Entry<TagKey<Biome>, Category> tag : CATEGORY_TAGS) {
            if (biome.is(tag.getKey())) return tag.getValue();
        }
        return Category.PLAINS;
    }

    private static PackExpression.Constants buildConstants() {
        Map<String, Double> values = new TreeMap<>();
        IDS.forEach((name, id) ->
                values.put(PREFIX + name.toUpperCase(Locale.ROOT), (double) id));
        // Iris's IrisDefines: the category ordinals and the precipitation codes it serves.
        for (Category category : Category.values()) {
            values.put("CAT_" + category.name(), (double) category.ordinal());
        }
        values.put("PPT_NONE", 0.0);
        values.put("PPT_RAIN", 1.0);
        values.put("PPT_SNOW", 2.0);
        return name -> {
            Double value = values.get(name);
            return value == null ? Double.NaN : value;
        };
    }
}
