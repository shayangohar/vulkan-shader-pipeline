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

/**
 * The id maps read as Iris 1.21.1 reads them: PropertiesPreprocessor's JCPP pass, IdMap.loadProperties'
 * rejoined continuations, and OrderBackedProperties' key order.
 */
final class IdMapPropertiesTest {
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
}
