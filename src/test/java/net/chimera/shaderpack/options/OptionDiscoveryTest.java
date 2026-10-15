package net.chimera.shaderpack.options;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Option discovery and editing, with the line rules OptiFine and Iris read packs by. */
final class OptionDiscoveryTest {
    private static final String SETTINGS = """
            #define SHADOWS
            //#define BLOOM // Bloom on bright light
            #define UNTESTED
            #define QUALITY 2 // Shadow quality [1 2 3]
            #define SPEED 0.5 // [0.25 1.0]
            #define PLAIN 2
            //#define COMMENTED_VALUE 2 // [1 2]
            #define SUFFIXED 1.0f // [1.0f 2.0f]
            const int shadowMapResolution = 2048; // [1024 2048 4096]
            const float notConfigurable = 1.0; // [1.0 2.0]
            const float sunPathRotation = -40.0; // [-40.0 0.0 40.0]
            """;
    private static final String FRAGMENT = """
            #version 120
            #include "/lib/settings.glsl"
            #ifdef SHADOWS
            #endif
            #ifndef BLOOM
            #endif
            #ifdef UNTESTED // a trailing comment hides the reference
            #endif
            #if defined UNTESTED
            #endif
            void main() {}
            """;

    private static OptionDiscovery discover(Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.createDirectories(shaders.resolve("lib"));
        Files.writeString(shaders.resolve("lib/settings.glsl"), SETTINGS);
        Files.writeString(shaders.resolve("composite.fsh"), FRAGMENT);
        return OptionDiscovery.discover(shaders);
    }

    @Test
    void togglesNeedAnIfdefInTheirIncludeGroup(@TempDir Path root) throws IOException {
        OptionSet options = discover(root).options();
        assertTrue(options.toggles().get("SHADOWS").option().defaultValue(), "SHADOWS on by default");
        var bloom = options.toggles().get("BLOOM").option();
        assertFalse(bloom.defaultValue(), "a commented-out define is off");
        assertEquals(Optional.of("Bloom on bright light"), bloom.comment());
        assertFalse(options.isToggle("UNTESTED"), "only a bare #ifdef/#ifndef line confirms a toggle");
    }

    @Test
    void choicesNeedAValueListAndKeepItsOrder(@TempDir Path root) throws IOException {
        OptionSet options = discover(root).options();
        var quality = options.choices().get("QUALITY").option();
        assertEquals(List.of("1", "2", "3"), quality.values());
        assertEquals(Optional.of("Shadow quality"), quality.comment());
        assertEquals(List.of("0.25", "1.0", "0.5"), options.choices().get("SPEED").option().values(),
                "a default missing from the list is appended");
        for (String rejected : List.of("PLAIN", "COMMENTED_VALUE", "notConfigurable")) {
            assertFalse(options.isChoice(rejected) || options.isToggle(rejected), rejected + " is not an option");
        }
        assertEquals(List.of("1.0f", "2.0f"), options.choices().get("SUFFIXED").option().values(),
                "a float suffix with text after it stays on the value");
        var resolution = options.choices().get("shadowMapResolution").option();
        assertEquals(ShaderOption.Kind.CONST, resolution.kind());
        assertEquals("2048", resolution.defaultValue());
        assertEquals("-40.0", options.choices().get("sunPathRotation").option().defaultValue());
    }

    @Test
    void valuesEditOnlyTheDeclaringLines(@TempDir Path root) throws IOException {
        OptionDiscovery discovery = discover(root);
        OptionValues values = OptionValues.of(discovery.options(), Map.of("SHADOWS", "false", "BLOOM", "true",
                "QUALITY", "3", "shadowMapResolution", "4096", "sunPathRotation", "0.0", "UNKNOWN", "1"));
        Map<String, String> edited = discovery.editedFiles(values);
        assertEquals(List.of("/lib/settings.glsl"), List.copyOf(edited.keySet()), "only the declaring file changes");
        List<String> lines = List.of(edited.get("/lib/settings.glsl").split("\n"));
        assertEquals("//#define SHADOWS", lines.get(0));
        assertEquals("#define BLOOM // Bloom on bright light", lines.get(1));
        assertTrue(lines.get(3).startsWith("#define QUALITY 3 //"), lines.get(3));
        assertEquals("const int shadowMapResolution = 4096; // [1024 2048 4096]", lines.get(8));
        assertEquals("const float sunPathRotation = 0.0; // [-40.0 0.0 40.0]", lines.get(10));
        assertEquals(Map.of(), discovery.editedFiles(OptionValues.of(discovery.options(), Map.of())),
                "defaults change nothing");
    }

    @Test
    void conflictingDefaultsDropTheOption(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#define LEVEL 1 // [1 2]\n");
        Files.writeString(shaders.resolve("final.fsh"), "#define LEVEL 2 // [1 2]\n#define SHARED 1 // [1 2]\n");
        Files.writeString(shaders.resolve("deferred.fsh"), "#define SHARED 1 // Kept [1 2]\n");
        OptionDiscovery discovery = OptionDiscovery.discover(shaders);
        assertFalse(discovery.options().isChoice("LEVEL"));
        assertEquals(Set.of("LEVEL"), discovery.ambiguous());
        var shared = discovery.options().choices().get("SHARED");
        assertEquals(2, shared.locations().size(), "agreeing declarations merge");
        assertEquals(Optional.of("Kept"), shared.option().comment(), "the commented declaration wins");
    }

    @Test
    void anIfdefInAnotherIncludeGroupDoesNotConfirm(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.createDirectories(shaders.resolve("world0"));
        Files.writeString(shaders.resolve("composite.fsh"), "#define LONELY\n");
        Files.writeString(shaders.resolve("world0/final.fsh"), "#ifdef LONELY\n#endif\n");
        assertFalse(OptionDiscovery.discover(shaders).options().isToggle("LONELY"));
        Files.writeString(shaders.resolve("world0/final.fsh"),
                "#include \"../composite.fsh\"\n#ifdef LONELY\n#endif\n");
        assertTrue(OptionDiscovery.discover(shaders).options().isToggle("LONELY"),
                "a relative include joins the group");
    }

    @Test
    void storedValuesFollowTheOptionFileRules(@TempDir Path root) throws IOException {
        OptionSet options = discover(root).options();
        OptionValues values = OptionValues.of(options,
                Map.of("SHADOWS", "maybe", "BLOOM", "false", "QUALITY", "7", "SPEED", "0.5", "UNKNOWN", "1"));
        assertEquals(Map.of("QUALITY", "7"), values.changes(),
                "a bad toggle resets, defaults drop, unknown names drop, an unlisted choice is kept");
        values.apply(Map.of("QUALITY", "2", "BLOOM", "true"));
        assertEquals(Map.of("BLOOM", "true"), values.changes());
        assertTrue(values.toggleOrDefault("1") && !values.toggleOrDefault("0") && values.toggleOrDefault("UNDECLARED"));
    }
}
