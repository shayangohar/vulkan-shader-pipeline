package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * block.properties as Iris 1.21.1's IdMap and BlockMaterialMapping read it: a state takes the first
 * entry, in file order, that matches it; predicates compare exactly and skip a property the block
 * lacks; tag entries come after every block entry. The id maps are read through
 * PropertiesPreprocessor's JCPP pass, IdMap.loadProperties' rejoined continuations, and
 * OrderBackedProperties' key order.
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

    /**
     * ChimeraTerrainPipelines tells material maps apart by identity. A new empty map on each reset
     * counted as a change, so each reset to vanilla rebuilt the live level in extended mode.
     */
    @Test
    void theEmptyMapIsOneMap() {
        assertTrue(PackMaterialResolver.empty() == PackMaterialResolver.empty(), "one empty map");
        assertTrue(PackMaterialResolver.empty().mappings().isEmpty());
    }

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

    /**
     * IdMap rejoins a continuation across the lines preprocessing empties. Bliss and Pegasus put
     * #if/#else/#endif and comment lines inside a block list; the names after #endif still belong to it.
     */
    @Test
    void aContinuationGoesOnPastDirectivesAndComments(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), """
                block.56 = oak_leaves \\
                #ifdef MISSING
                  never_named \\
                #else
                  birch_leaves \\
                #endif
                ## a comment inside the list
                  spruce_leaves
                block.57 = stone
                """);
        Map<String, String> entries = IdMapProperties.read(root, "block.properties", Map.of()).entries();
        assertEquals(List.of("block.56", "block.57"), List.copyOf(entries.keySet()));
        assertEquals(List.of("oak_leaves", "birch_leaves", "spruce_leaves"),
                List.of(entries.get("block.56").split("\\s+")));
        assertEquals(56, PackMaterialResolver.parse(root, Map.of()).resolver().resolveName("spruce_leaves"));
    }

    /** OrderBackedProperties keeps a repeated key's last value where the key first appeared. */
    @Test
    void aRepeatedKeyKeepsItsLastValueWhereItFirstAppeared(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), """
                block.1 = stone
                block.2 = dirt
                block.1 = granite
                """);
        IdMapProperties.Loaded file = IdMapProperties.read(root, "block.properties", Map.of());
        assertEquals(List.of("block.1", "block.2"), List.copyOf(file.entries().keySet()));
        assertEquals("granite", file.entries().get("block.1"));
        PackMaterialResolver resolver = PackMaterialResolver.parse(root, Map.of()).resolver();
        assertEquals(-1, resolver.resolveName("stone"), "the replaced value maps nothing");
        assertEquals(1, resolver.resolveName("granite"));
    }

    /**
     * PropertiesPreprocessor drops the {@code #} signs of a text line and reads a {@code #} line with a
     * space after the sign as a comment. JCPP expands a bodiless macro to nothing, applies an #undef
     * followed by a comment, and leaves a function-like macro's call alone here. The file is
     * ISO-8859-1. A continuation that runs into the next block key loses its last character to
     * IdMap's {@code \S *block\.} split.
     */
    @Test
    void linesReadAsIrisPreprocessesThem(@TempDir Path root) throws IOException {
        String text = """
                #define EMPTY
                #define LIST stone EMPTY dirt
                # if 0
                block.1 = LIST #trailing
                #undef LIST // gone
                #define F(x) x
                block.2 = LIST F(oak_log) café
                block.3 = sand \\
                #if 0
                #endif
                block.4 = gravel
                """;
        Files.write(root.resolve("block.properties"), text.getBytes(StandardCharsets.ISO_8859_1));
        IdMapProperties.Loaded file = IdMapProperties.read(root, "block.properties", Map.of());
        assertNull(file.failure());
        Map<String, String> entries = file.entries();
        assertEquals(List.of("stone", "dirt", "trailing"), List.of(entries.get("block.1").split("\\s+")));
        assertEquals(List.of("LIST", "F(oak_log)", "café"), List.of(entries.get("block.2").split("\\s+")));
        assertEquals("san", entries.get("block.3"));
        assertEquals("gravel", entries.get("block.4"));
    }

    /**
     * JCPP lexes the whole file, so a block comment runs across lines (KNOW-1148): a directive line
     * that leaves one open takes the lines up to its close and goes on after it (CUSTOM is 1 + 1);
     * a directive inside a comment is comment text, in an inactive branch too; an active comment's
     * text is kept as written, macros unexpanded (KEEPCOMMENTS), and Properties.load reads it. A
     * {@code #} line naming no JCPP directive is emptied first, comment marks and all. Each value
     * here is what Iris's PropertiesPreprocessor and Properties.load give for this file.
     */
    @Test
    void aBlockCommentRunsAcrossLinesAsJcppLexesIt(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), """
                #define STONE granite
                #define CUSTOM 1 /* open
                block.1 = swallowed
                */ + 1
                #if CUSTOM == 2
                block.2 = STONE
                #endif
                /*
                #if 0
                */
                block.3 = STONE /* STONE */ dirt // STONE
                #ifdef MISSING
                /*
                #endif
                */
                block.4 = inactive
                #endif
                #note /* not a comment
                block.5 = andesite
                block.6 = STONE /* open
                #if 0
                block.7 = STONE
                */ STONE
                #endif
                block.8 = after
                """);
        IdMapProperties.Loaded file = IdMapProperties.read(root, "block.properties", Map.of());
        assertNull(file.failure());
        Map<String, String> entries = file.entries();
        assertNull(entries.get("block.1"), "the #define took the lines up to its comment's close");
        assertEquals("granite", entries.get("block.2"), "the #define went on after the close");
        assertEquals("granite /* STONE */ dirt // STONE", entries.get("block.3"), "comments kept, unexpanded");
        assertNull(entries.get("block.4"), "#endif in a comment ends no branch");
        assertEquals("andesite", entries.get("block.5"), "a # line naming no directive opens no comment");
        assertEquals("granite /* open", entries.get("block.6"));
        assertEquals("STONE", entries.get("block.7"), "#if 0 in a comment is text; the comment is kept unexpanded");
        assertEquals("granite", entries.get("*/"), "the text after the close expands");
        assertEquals("after", entries.get("block.8"));
    }

    /**
     * JCPP lexes a quote as a string or character literal, so a comment mark inside one is text and
     * a name inside one is not expanded. A broken character literal ({@code o'lantern}) runs to the
     * next {@code '} or the end of the line, and Iris hides each backslash behind an identifier
     * first, so {@code '\''} reads as two literals. Each value is JCPP's for this file.
     */
    @Test
    void aQuoteHidesACommentMarkAsJcppLexesIt(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), """
                #define STONE granite
                block.1 = jack_o'lantern /* x
                #if 0
                */
                block.2 = dirt
                #endif
                block.3 = "/*" STONE
                #if 0
                block.4 = hidden
                #endif
                block.5 = 'a' STONE '/*' STONE
                block.6 = "open /* STONE
                block.7 = after
                block.8 = '\\'' /* c */ STONE
                """);
        Map<String, String> entries = IdMapProperties.read(root, "block.properties", Map.of()).entries();
        assertEquals("jack_o'lantern /* x", entries.get("block.1"));
        assertNull(entries.get("block.2"), "the quote kept the /* from opening a comment, so #if 0 applies");
        assertEquals("\"/*\" granite", entries.get("block.3"));
        assertNull(entries.get("block.4"));
        assertEquals("'a' granite '/*' granite", entries.get("block.5"));
        assertEquals("\"open /* STONE", entries.get("block.6"), "an open string runs to the end of its line");
        assertEquals("after", entries.get("block.7"));
        assertEquals("''' /* c */ STONE", entries.get("block.8"));
    }

    /**
     * JCPP reports a conditional it cannot read and reads on (KNOW-1148): an #if or #elif Chimera
     * cannot evaluate, and an empty #if, read false; an empty #ifdef takes block.8, the line after
     * it (anIfdefWithoutANameLeavesItsBranchOn); a second #else and an #elif after #else are
     * ignored; a stray #endif is ignored and a stray #else turns the rest off. The file
     * used to read nothing (CONDITION_UNSUPPORTED). The entries are JCPP's for this file.
     */
    @Test
    void aConditionalJcppCannotReadIsReportedAndSkipped(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), """
                #if F(1)
                block.1=a
                #endif
                block.2=b
                #if 1
                block.3=c
                #else
                block.4=d
                #else
                block.5=e
                #endif
                #if 0
                #else
                block.6=f
                #elif 1
                block.7=g
                #endif
                #ifdef
                block.8=h
                #endif
                #if
                block.9=i
                #endif
                #if 0
                block.10=j
                #elif G(2)
                block.11=k
                #else
                block.12=l
                #endif
                #endif
                block.13=m
                #else
                block.14=n
                #if 1
                block.15=o
                #endif
                #endif
                block.16=p
                """);
        IdMapProperties.Loaded file = IdMapProperties.read(root, "block.properties", Map.of());
        assertEquals("CONDITION_UNSUPPORTED", file.failure());
        assertEquals(List.of("block.2", "block.3", "block.6", "block.7", "block.12", "block.13"),
                List.copyOf(file.entries().keySet()));

        Files.writeString(root.resolve("item.properties"), "#if F(1)\nitem.1=stick\n#endif\nitem.2=torch\n");
        PackItemIdResolver.ParseResult items = PackItemIdResolver.parse(root, Map.of());
        assertEquals(2, items.resolver().resolve("minecraft:torch"), "the item map reads on too");
        assertTrue(items.deviations().contains("ITEM_PROPERTIES_CONDITION_UNSUPPORTED"), items.deviations().toString());
    }

    /**
     * A {@code #} right after a comment's close on its line starts a directive in JCPP; only a
     * {@code #} line survives Iris's line pass with its sign, so it takes a directive name's line
     * inside a comment. The comment text before it stays. The entries are JCPP's for this file.
     */
    @Test
    void aDirectiveMayFollowACommentsClose(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), """
                /*
                #if 0 */ #define STONE granite
                block.1 = STONE
                /* c */ #define DIRT dirt
                block.2 = DIRT
                """);
        Map<String, String> entries = IdMapProperties.read(root, "block.properties", Map.of()).entries();
        assertEquals("granite", entries.get("block.1"));
        assertEquals("DIRT", entries.get("block.2"), "a line that does not start with # loses its sign first");
        assertEquals("c */ define DIRT dirt", entries.get("/*"));

        // A comment that opened after other text keeps the line it closes on from starting a directive.
        Files.writeString(root.resolve("block.properties"), """
                #if 0
                block.0=a /* c
                #define A */ #else
                block.1=b
                #endif
                block.2=c
                """);
        assertEquals(List.of("block.2"),
                List.copyOf(IdMapProperties.read(root, "block.properties", Map.of()).entries().keySet()));
    }

    /**
     * JCPP reads an #ifdef or #ifndef whose argument starts with no identifier as an error that
     * leaves its branch on; with no argument (a comment aside) it also takes the next line,
     * whatever it is, an #endif included. In a skipped branch it does neither. The entries are
     * JCPP's for this file.
     */
    @Test
    void anIfdefWithoutANameLeavesItsBranchOn(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), """
                #ifdef 123
                block.1=a
                #else
                block.2=b
                #endif
                #ifdef
                block.3=c
                #else
                block.4=d
                #endif
                #ifndef
                block.5=e
                #else
                block.6=f
                #endif
                #if 0
                #ifdef
                block.7=g
                #endif
                #endif
                block.8=h
                #ifdef /* c */
                block.9=i
                #endif
                #ifdef // c
                block.10=j
                #endif
                #ifdef
                #endif
                block.11=k
                #ifndef 7up
                block.12=l
                #endif
                block.13=m
                """);
        IdMapProperties.Loaded file = IdMapProperties.read(root, "block.properties", Map.of());
        assertEquals("CONDITION_UNSUPPORTED", file.failure());
        assertEquals(List.of("block.1", "block.8", "block.11", "block.12", "block.13"),
                List.copyOf(file.entries().keySet()));
    }

    /** IdMap.parseRenderTypeMap puts layer keys in key order: a block two keys name takes the later. */
    @Test
    void aBlockTwoLayerKeysNameTakesTheLaterKey(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), """
                layer.translucent = glass
                layer.cutout = glass
                """);
        assertEquals(PackMaterialResolver.Layer.CUTOUT,
                PackMaterialResolver.parse(root, Map.of()).resolver().layers().get("minecraft:glass"));
    }

    /** BlockMaterialMapping.addTag ignores case in the tag's name only; checkState compares values exactly. */
    @Test
    void aTagsPredicatesKeepTheirCase(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), "block.1 = %Leaves:persistent=True\n");
        PackMaterialResolver resolver = PackMaterialResolver.parse(root, Map.of()).resolver();
        assertEquals(1, resolver.resolveTags(key -> key.location().getPath().equals("leaves"),
                Map.of("persistent", "True")::get));
        assertEquals(-1, resolver.resolveTags(key -> key.location().getPath().equals("leaves"),
                Map.of("persistent", "true")::get));
    }

    private static PackMaterialResolver.ParseResult parse(Path root) throws IOException {
        Files.writeString(root.resolve("block.properties"), PROPERTIES);
        return PackMaterialResolver.parse(root);
    }
}
