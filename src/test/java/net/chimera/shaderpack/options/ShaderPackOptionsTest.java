package net.chimera.shaderpack.options;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The menu, profiles, option file and translations built over discovered options. */
final class ShaderPackOptionsTest {
    private static final String SOURCE = """
            #define SHADOWS
            #define BLOOM
            //#define FOG
            #define QUALITY 2 // [1 2 3]
            #define SAMPLES 4 // [2 4 8 16]
            #ifdef SHADOWS
            #endif
            #ifdef BLOOM
            #endif
            #ifdef FOG
            #endif
            """;

    private static Path pack(Path root, String properties) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), SOURCE);
        if (properties != null) Files.writeString(shaders.resolve("shaders.properties"), properties);
        return shaders;
    }

    @Test
    void menuFollowsTheScreenEntries(@TempDir Path root) throws IOException {
        Path shaders = pack(root, """
                screen=<profile> <empty> [LIGHT] SHADOWS MISSING *
                screen.LIGHT=BLOOM SAMPLES
                screen.LIGHT.columns=1
                sliders=SAMPLES
                profile.LOW=!SHADOWS QUALITY=1
                profile.HIGH=profile.LOW SHADOWS QUALITY:3 !program.composite1
                """);
        ShaderPackOptions options = ShaderPackOptions.load(shaders, Map.of());
        List<OptionMenu.Element> main = options.menu().main().elements();
        assertTrue(main.get(0) instanceof OptionMenu.ProfileButton);
        assertTrue(main.get(1) instanceof OptionMenu.Empty);
        assertEquals(new OptionMenu.Link("LIGHT"), main.get(2));
        assertTrue(main.get(4) instanceof OptionMenu.Empty, "an undeclared name is a blank cell");
        List<String> filled = main.subList(5, main.size()).stream()
                .map(element -> ((OptionMenu.OptionEntry) element).option().name()).sorted().toList();
        assertEquals(List.of("FOG", "QUALITY", "SHADOWS"), filled,
                "* places every option no sub-screen used, the main screen's own again, as Iris does");
        assertEquals(2, options.menu().main().columnCount());
        OptionMenu.Screen light = options.menu().subScreen("LIGHT").orElseThrow();
        assertEquals(1, light.columnCount());
        assertTrue(((OptionMenu.OptionEntry) light.elements().get(1)).slider());
        assertTrue(options.problems().contains("MENU_ELEMENT_UNKNOWN:MISSING"));
    }

    @Test
    void profilesMatchFromTheMostConstrained(@TempDir Path root) throws IOException {
        Path shaders = pack(root, """
                profile.LOW=!SHADOWS QUALITY=1
                profile.HIGH=profile.LOW SHADOWS QUALITY:3 !program.composite1
                profile.LOOP=profile.LOOP
                """);
        ShaderPackOptions defaults = ShaderPackOptions.load(shaders, Map.of());
        assertEquals(List.of("LOW", "HIGH"), defaults.profiles().declared().stream()
                .map(PackProfiles.Profile::name).toList());
        assertTrue(defaults.problems().stream().anyMatch(problem -> problem.startsWith("PROFILE_INVALID:LOOP")));
        assertTrue(defaults.profile().current().isEmpty(), "defaults match no profile: Custom");
        ShaderPackOptions high = ShaderPackOptions.load(shaders, Map.of("QUALITY", "3"));
        assertEquals("HIGH", high.profile().current().orElseThrow().name());
        assertEquals(List.of("composite1"), high.profileDisabledPrograms(high.values()));
        ShaderPackOptions low = ShaderPackOptions.load(shaders, Map.of("SHADOWS", "false", "QUALITY", "1"));
        assertEquals("LOW", low.profile().current().orElseThrow().name());
    }

    @Test
    void optionFileHoldsOnlyChanges(@TempDir Path root) throws IOException {
        Path shaders = pack(root, null);
        Path file = root.resolve("pack.zip.txt");
        assertEquals(file, OptionFile.of(root.resolve("pack.zip")));
        ShaderPackOptions options = ShaderPackOptions.load(shaders, Map.of());
        OptionValues edited = options.values().copy();
        edited.apply(Map.of("FOG", "true", "QUALITY", "3", "SAMPLES", "4"));
        OptionFile.write(file, edited);
        String text = Files.readString(file, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("#"), "properties files start with the date comment");
        assertEquals(Map.of("FOG", "true", "QUALITY", "3"), OptionFile.read(file));
        edited.apply(Map.of("FOG", "false", "QUALITY", "2"));
        OptionFile.write(file, edited);
        assertFalse(Files.exists(file), "a file with no changes is deleted");
        assertTrue(options.values().changes().isEmpty(), "editing a copy leaves the applied values");
    }

    @Test
    void translationsFallBackToEnglish(@TempDir Path root) throws IOException {
        Path shaders = pack(root, null);
        Files.createDirectories(shaders.resolve("lang"));
        Files.writeString(shaders.resolve("lang/en_US.lang"), "option.SHADOWS=Shadows\nvalue.QUALITY.1=Low\n");
        Files.writeString(shaders.resolve("lang/de_DE.lang"), "option.SHADOWS=Schatten\n");
        PackLanguage language = ShaderPackOptions.load(shaders, Map.of()).language();
        assertEquals(Optional.of("Schatten"), language.translate("de_de", "option.SHADOWS"));
        assertEquals(Optional.of("Low"), language.translate("de_de", "value.QUALITY.1"));
        assertEquals(Optional.empty(), language.translate("en_us", "option.MISSING"));
    }

    @Test
    void lastPropertyDefinitionWinsAcrossBranches() {
        MenuProperties properties = MenuProperties.parse("""
                #ifdef VARIANT
                screen=A B
                #else
                screen=C  D
                #endif
                screen.columns=x
                """);
        assertEquals(Optional.of(List.of("C", "D")), properties.mainScreen(), "#if lines are comments");
        assertTrue(properties.mainColumns().isEmpty());
        assertEquals(List.of("MENU_COLUMNS_MALFORMED:screen.columns=x"), properties.problems());
    }
}
