package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * block.properties as Iris 1.21.1's IdMap and BlockMaterialMapping read it: a state takes the first
 * entry, in file order, that matches it; predicates compare exactly and skip a property the block
 * lacks; tag entries come after every block entry.
 */
final class BlockPropertiesResolverTest {
    private static final String PROPERTIES = "block.-7 = diorite\n"
            + "block.8 = minecraft:dirt\n"
            + "block.8 = minecraft:dirt\n"
            + "block.9 = minecraft:oak_planks:axis=x\n"
            + "block.13 = minecraft:tall_grass:half=lower tall_grass:half=upper\n"
            + "block.14 = leaves:waterlogged=false:distance=2\n"
            + "block.15 = oak_leaves\n"
            + "block.10 = %minecraft:logs\n"
            + "block.40000 = bad\n"
            + "block.11 = minecraft:stone\n"
            + "block.12 = minecraft:stone\n"
            + "block.16 = minecraft:poppy\n"
            + "block.17 = poppy:age=1 birch_leaves:distance=1,2 spruce_leaves:persistent=true:bad\n"
            + "block.18 = %WALLS:north=low %minecraft:logs\n";

    @Test
    void namesAndPredicates(@TempDir Path root) throws IOException {
        PackMaterialResolver.ParseResult result = parse(root);
        PackMaterialResolver resolver = result.resolver();
        assertEquals(-7, resolver.resolveName("diorite"), "signed material id");
        assertEquals(8, resolver.resolveName("minecraft:dirt"), "duplicate material id");
        assertEquals(-1, resolver.resolveName("minecraft:oak_planks"), "a predicate entry is no plain mapping");
        assertEquals(11, resolver.resolveName("minecraft:stone"),
                "conflicting mapping: the first wins, as in Iris BlockMaterialMapping");
        // BSL and Complementary map tall grass by half and leaves by waterlogged.
        assertEquals(13, resolver.resolveState("minecraft:tall_grass", Map.of("half", "lower")::get),
                "namespaced predicate");
        assertEquals(13, resolver.resolveState("tall_grass", Map.of("half", "upper")::get), "unqualified predicate");
        // Iris ignores a predicate on a property the block does not have (null here).
        assertEquals(13, resolver.resolveState("minecraft:tall_grass", Map.<String, String>of()::get),
                "a predicate on a missing property");
        assertEquals(-1, resolver.resolveState("minecraft:tall_grass", Map.of("half", "middle")::get),
                "a state no predicate matches");
        assertEquals(14, resolver.resolveState("minecraft:leaves",
                Map.of("waterlogged", "false", "distance", "2")::get), "multi-property predicate");
        assertEquals(-1, resolver.resolveState("minecraft:leaves",
                Map.of("waterlogged", "false", "distance", "3")::get), "predicate value");
        assertEquals(15, resolver.resolveState("minecraft:oak_leaves", Map.of("waterlogged", "true")::get),
                "plain mapping under a predicate-free name");
        assertTrue(result.deviations().contains("BLOCK_PROPERTIES_INVALID")
                        && result.deviations().contains("BLOCK_MAPPING_CONFLICT"), result.deviations().toString());
    }

    @Test
    void theFirstEntryInFileOrderWins(@TempDir Path root) throws IOException {
        PackMaterialResolver resolver = parse(root).resolver();
        // Iris putIfAbsent: poppy's plain mapping precedes its age predicate.
        assertEquals(16, resolver.resolveState("minecraft:poppy", Map.of("age", "1")::get),
                "an earlier plain mapping wins over a later predicate");
        // Iris compares a value exactly: "1,2" is no list, so it matches no state.
        assertEquals(-1, resolver.resolveState("minecraft:birch_leaves", Map.of("distance", "1")::get),
                "a comma value is one literal value");
        // A segment that is no key=value is skipped; the rest still applies.
        assertEquals(17, resolver.resolveState("minecraft:spruce_leaves", Map.of("persistent", "true")::get),
                "a malformed predicate is skipped");
    }

    /** Tags (Iris BlockMaterialMapping.addTag): file order among tags, names ignoring case, block predicates. */
    @Test
    void tagsMapAfterBlocks(@TempDir Path root) throws IOException {
        PackMaterialResolver.ParseResult result = parse(root);
        PackMaterialResolver resolver = result.resolver();
        assertEquals(10, resolver.resolveTags(key -> key.location().getPath().equals("logs"),
                Map.<String, String>of()::get), "the first tag entry in file order");
        assertEquals(18, resolver.resolveTags(key -> key.location().toString().equals("minecraft:walls"),
                Map.of("north", "low")::get), "a tag with a predicate, its name lower-cased");
        assertEquals(-1, resolver.resolveTags(key -> key.location().getPath().equals("walls"),
                Map.of("north", "none")::get), "a tag predicate no state matches");
        assertEquals(-1, resolver.resolveTags(key -> false, Map.<String, String>of()::get), "an unknown tag");
        assertTrue(!result.deviations().contains("BLOCK_TAG_UNSUPPORTED"), result.deviations().toString());
        assertEquals("2", PackEngineDefines.standard().get("IRIS_TAG_SUPPORT"), "Iris's StandardMacros promise tags");
    }

    /**
     * Iris preprocesses block.properties (IdMap, through JCPP): #ifdef names one identifier, and the
     * file's own #define macros expand in the lines after them. Photon, Bloop and superDuperVanilla
     * name their tags through such macros.
     */
    @Test
    void definesExpandAndIfdefNamesOneIdentifier(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), """
                #ifdef IRIS_TAG_SUPPORT > 1
                #define tags_leaves %leaves
                #define stone_blocks stone granite
                #endif
                block.5 = stone_blocks
                block.6 = tags_leaves oak_leaves
                #undef stone_blocks
                block.7 = stone_blocks
                """);
        PackMaterialResolver.ParseResult result = PackMaterialResolver.parse(root);
        PackMaterialResolver resolver = result.resolver();
        assertTrue(result.deviations().isEmpty(), result.deviations().toString());
        assertEquals(5, resolver.resolveName("minecraft:granite"), "a macro expands to its block list");
        assertEquals(6, resolver.resolveName("minecraft:oak_leaves"), "the line after a tag macro");
        assertEquals(6, resolver.resolveTags(key -> key.location().getPath().equals("leaves"),
                Map.<String, String>of()::get), "a tag named through a macro");
        assertEquals(7, resolver.resolveName("minecraft:stone_blocks"), "#undef ends the macro");
    }

    /** Without a block.properties, Iris's IdMap maps the legacy numeric ids (LegacyIdMap). */
    @Test
    void aPackWithoutBlockPropertiesTakesTheLegacyIds(@TempDir Path root) {
        PackMaterialResolver.ParseResult result = PackMaterialResolver.parse(root);
        PackMaterialResolver resolver = result.resolver();
        assertTrue(!result.present() && result.deviations().contains("BLOCK_PROPERTIES_MISSING"));
        assertEquals(1, resolver.resolveName("granite"));
        assertEquals(-123, resolver.resolveName("emerald_block"));
        assertEquals(35, resolver.resolveName("light_blue_wool"));
        assertEquals(160, resolver.resolveName("black_stained_glass_pane"));
        assertEquals(18, resolver.resolveName("dark_oak_leaves"));
        assertEquals(-1, resolver.resolveName("short_grass"), "1.21.1's short grass is not the legacy grass");
        assertTrue(resolver.layers().isEmpty());
    }

    private static PackMaterialResolver.ParseResult parse(Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), PROPERTIES);
        return PackMaterialResolver.parse(root);
    }
}
