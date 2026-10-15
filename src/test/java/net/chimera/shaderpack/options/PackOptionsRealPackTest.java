package net.chimera.shaderpack.options;

import net.chimera.shaderpack.PackSource;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The option model on the locked packs: what the settings screen will show for each. */
@Tag("packs")
final class PackOptionsRealPackTest {
    @Test
    void bsl() {
        ShaderPackOptions options = load(TestPacks.bsl());
        ShaderOption.Choice clouds = options.options().choices().get("CLOUDS").option();
        assertEquals("2", clouds.defaultValue());
        assertEquals(List.of("0", "1", "2", "3"), clouds.values());
        assertFalse(options.menu().main().elements().isEmpty(), "BSL lays out a main screen");
    }

    @Test
    void complementary() {
        ShaderPackOptions options = load(TestPacks.complementary());
        ShaderOption.Choice style = options.options().choices().get("CLOUD_STYLE_DEFINE").option();
        assertEquals("-1", style.defaultValue());
        assertEquals(List.of("-1", "0", "1", "3", "50"), style.values());
        assertTrue(options.options().isChoice("RP_MODE"), "Complementary's resource pack mode is an option");
    }

    @Test
    void extraPacks() {
        for (Path pack : TestPacks.extra()) {
            ShaderPackOptions options = load(pack);
            assertFalse(options.options().toggles().isEmpty() && options.options().choices().isEmpty(),
                    pack.getFileName() + " declares no options");
            if (pack.getFileName().toString().startsWith("MakeUp")) {
                assertEquals(List.of("0", "1", "2"), options.options().choices().get("V_CLOUDS").option().values());
            }
        }
    }

    private static ShaderPackOptions load(Path pack) {
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack)) {
            ShaderPackOptions options = ShaderPackOptions.load(loaded.shadersDir(), Map.of());
            System.out.println(summary(pack, options));
            return options;
        }
    }

    private static String summary(Path pack, ShaderPackOptions options) {
        List<String> screens = new ArrayList<>();
        screens.add("main=" + options.menu().main().elements().size() + "x" + options.menu().main().columnCount());
        options.menu().subScreens().forEach((id, screen) -> screens.add(id + "=" + screen.elements().size()));
        return "[chimera] options " + pack.getFileName() + ": toggles=" + options.options().toggles().size()
                + " choices=" + options.options().choices().size()
                + " profiles=" + options.profiles().declared().stream().map(PackProfiles.Profile::name).toList()
                + " current=" + options.profile().current().map(PackProfiles.Profile::name).orElse("Custom")
                + " langs=" + options.language().languages().size()
                + " screens=" + screens.size() + " " + screens.subList(0, Math.min(6, screens.size()))
                + " problems=" + options.problems();
    }
}
