package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** block.properties {@code layer.*} lines: the chunk layer a pack draws each named block in (Iris BlockRenderType). */
final class BlockLayerTest {
    @TempDir
    Path shadersDir;

    /**
     * Complementary names glass, panes and beacons translucent; a key's last value wins, as a
     * properties file keeps it.
     */
    @Test
    void layerLinesNameEachBlocksLayer() throws IOException {
        Files.writeString(shadersDir.resolve("block.properties"), """
                block.32012=glass_pane
                layer.translucent=stone
                layer.translucent=glass glass_pane \\
                    beacon
                layer.cutout_mipped=minecraft:oak_leaves # leaves
                layer.cutout=%minecraft:leaves dirt:snowy=true
                layer.glowing=stone
                """);
        PackMaterialResolver.ParseResult result = PackMaterialResolver.parse(shadersDir, Map.of());
        assertEquals(Map.of(
                        "minecraft:glass", PackMaterialResolver.Layer.TRANSLUCENT,
                        "minecraft:glass_pane", PackMaterialResolver.Layer.TRANSLUCENT,
                        "minecraft:beacon", PackMaterialResolver.Layer.TRANSLUCENT,
                        "minecraft:oak_leaves", PackMaterialResolver.Layer.CUTOUT_MIPPED),
                result.resolver().layers());
        assertEquals(32012, result.resolver().resolveName("glass_pane"), "ids still read");
        assertTrue(result.deviations().containsAll(List.of(
                "BLOCK_TAG_UNSUPPORTED", "BLOCK_LAYER_INVALID")), result.deviations().toString());
    }

    /** Bliss gates its glass line on an option; the line holds only while the macro is defined. */
    @Test
    void layerLinesFollowConditions() throws IOException {
        Files.writeString(shadersDir.resolve("block.properties"), """
                #ifdef FORCE_TRANSLUCENT_GLASS
                    layer.translucent = minecraft:glass minecraft:glass_pane
                #endif
                """);
        assertTrue(PackMaterialResolver.parse(shadersDir, Map.of()).resolver().layers().isEmpty());
        assertEquals(2, PackMaterialResolver.parse(shadersDir, Map.of("FORCE_TRANSLUCENT_GLASS", "1"))
                .resolver().layers().size());
    }
}
