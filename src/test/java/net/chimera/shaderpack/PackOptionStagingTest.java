package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Option values reach the pack sources as Iris applies them: by editing the declaring lines. */
final class PackOptionStagingTest {
    private static final String SETTINGS = """
            #define WATER_STYLE 1 // [1 2 3]
            #define RP_MODE 1 // [0 1 2 3]
            //#define WAVES
            const int shadowMapResolution = 2048; // [1024 2048 4096]
            """;
    private static final String COMPOSITE = """
            #version 120
            #include "/settings.glsl"
            #ifdef WAVES
            #endif
            void main() {}
            """;
    private static final String PROPERTIES = """
            profile.LOW=RP_MODE=0 !program.composite
            profile.HIGH=RP_MODE=3
            """;

    private static Map<String, String> packFiles() {
        return Map.of("settings.glsl", SETTINGS, "composite.fsh", COMPOSITE,
                "composite.vsh", "#version 120\nvoid main() {}\n", "shaders.properties", PROPERTIES);
    }

    private static Path folderPack(Path dir) throws IOException {
        Path shaders = Files.createDirectories(dir.resolve("Example_r1/shaders"));
        for (Map.Entry<String, String> file : packFiles().entrySet()) {
            Files.writeString(shaders.resolve(file.getKey()), file.getValue());
        }
        return shaders.getParent();
    }

    @Test
    void folderPackIsCopiedThenEdited(@TempDir Path dir) throws IOException {
        Path pack = folderPack(dir);
        Files.writeString(dir.resolve("Example_r1.txt"),
                "#Iris options\nWATER_STYLE=2\nRP_MODE=3\nWAVES=true\nshadowMapResolution=4096\nUNDECLARED=1\n");
        Path stagedRoot;
        String previousWater = System.setProperty("chimera.option.WATER_STYLE", "3");
        String previousExtra = System.setProperty("chimera.option.EXTRA", "1");
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack)) {
            StagedOptions options = loaded.options();
            assertEquals(Map.of("RP_MODE", "3", "WATER_STYLE", "3", "WAVES", "true", "shadowMapResolution", "4096"),
                    options.applied().changes(), "a JVM property wins over the file; undeclared names drop");
            assertEquals(Map.of("EXTRA", "1"), options.undeclared(), "an undeclared JVM property stays a macro");
            assertEquals(Map.of("RP_MODE", "3", "WATER_STYLE", "2", "WAVES", "true", "shadowMapResolution", "4096"),
                    options.pack().values().changes(), "the menu sees the option file alone");
            assertEquals("HIGH", options.profileName());
            assertFalse(loaded.shadersDir().startsWith(pack), "a folder pack is copied before it is edited");
            String staged = Files.readString(loaded.shadersDir().resolve("settings.glsl"));
            assertTrue(staged.contains("#define WATER_STYLE 3 //") && staged.contains("\n#define WAVES\n")
                    && staged.contains("shadowMapResolution = 4096;"), staged);
            assertEquals(SETTINGS, Files.readString(pack.resolve("shaders/settings.glsl")),
                    "the player's own folder is untouched");
            assertTrue(loaded.deviations().contains("PACK_OPTION_OVERRIDE:shadowMapResolution"));
            stagedRoot = loaded.shadersDir().getParent();
        } finally {
            restore("chimera.option.WATER_STYLE", previousWater);
            restore("chimera.option.EXTRA", previousExtra);
        }
        assertFalse(Files.exists(stagedRoot), "closing the pack deletes the staged copy");
    }

    @Test
    void defaultsReadThePackInPlace(@TempDir Path dir) throws IOException {
        Path pack = folderPack(dir);
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack)) {
            assertEquals(pack.resolve("shaders"), loaded.shadersDir());
            assertTrue(loaded.options().applied().changes().isEmpty());
            assertEquals("Custom", loaded.options().profileName(), "RP_MODE=1 matches neither profile");
        }
    }

    @Test
    void zipPackIsEditedWhereItIsExtracted(@TempDir Path dir) throws IOException {
        Path pack = dir.resolve("Example_r1.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            for (Map.Entry<String, String> file : packFiles().entrySet()) {
                zip.putNextEntry(new ZipEntry("shaders/" + file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Files.writeString(dir.resolve("Example_r1.zip.txt"), "RP_MODE=0\n");
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack)) {
            assertTrue(Files.readString(loaded.shadersDir().resolve("settings.glsl")).contains("#define RP_MODE 0 //"));
            assertEquals("LOW", loaded.options().profileName());
            assertEquals(Set.of("composite"), loaded.options().disabledPrograms(), "LOW switches composite off");
            PackSettingsPlan settings = PackProbe.analyze(pack, loaded).settings();
            assertFalse(settings.enabled("composite"), "a profile's !program drops the program");
        }
    }

    private static void restore(String property, String previous) {
        if (previous == null) System.clearProperty(property);
        else System.setProperty(property, previous);
    }
}
