package net.chimera.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pack path resolution for the shader commands. */
final class HotSwapCommandTest {
    @Test
    void pathResolution(@TempDir Path root) throws IOException {
        Path shaderpacks = root.resolve("shaderpacks");
        Path gameDirectory = root.resolve("game with spaces");
        Files.createDirectories(shaderpacks);
        Files.createDirectories(gameDirectory);

        Path simplex = Files.createDirectories(shaderpacks.resolve("Simplex"));
        Files.createDirectories(simplex.resolve("shaders"));
        Path bsl = shaderpacks.resolve("BSL.zip");
        Files.writeString(bsl, "zip placeholder", StandardCharsets.UTF_8);
        Files.createDirectories(shaderpacks.resolve("Other").resolve("shaders"));
        // Iris rule: a folder without a shaders folder is not a pack.
        Files.createDirectories(shaderpacks.resolve("NotAPack"));
        Files.writeString(shaderpacks.resolve("ignored.txt"), "not a pack", StandardCharsets.UTF_8);

        assertEquals(List.of("BSL.zip", "Other", "Simplex"),
                ChimeraCommands.listPackNames(shaderpacks), "sorted shaderpack list");
        assertPath(simplex, ChimeraCommands.resolvePackPath(shaderpacks, gameDirectory, "Simplex"),
                "named directory");
        assertPath(bsl, ChimeraCommands.resolvePackPath(shaderpacks, gameDirectory, "BSL"),
                "named ZIP");
        assertPath(bsl, ChimeraCommands.resolvePackPath(shaderpacks, gameDirectory, "\"BSL.zip\""),
                "quoted named ZIP");

        Path explicit = gameDirectory.resolve("local pack");
        Files.createDirectories(explicit.resolve("shaders"));
        assertPath(explicit, ChimeraCommands.resolvePackPath(
                        shaderpacks, gameDirectory, "\".\\local pack\""),
                "quoted relative path");
        assertTrue(!ChimeraCommands.resolvePackPath(shaderpacks, gameDirectory, "missing").valid(),
                "missing pack must fail");

        Files.createDirectories(shaderpacks.resolve("Duplicate").resolve("shaders"));
        Files.writeString(shaderpacks.resolve("Duplicate.zip"), "zip placeholder", StandardCharsets.UTF_8);
        ChimeraCommands.Resolution ambiguous = ChimeraCommands.resolvePackPath(
                shaderpacks, gameDirectory, "Duplicate");
        assertTrue(!ambiguous.valid() && ambiguous.error().contains("ambiguous"),
                "duplicate name must be rejected");
        assertTrue(!ChimeraCommands.resolvePackPath(shaderpacks, gameDirectory, "ignored.txt").valid(),
                "non-ZIP file must fail");
    }

    private static void assertPath(Path expected, ChimeraCommands.Resolution actual, String message) {
        assertTrue(actual.valid(), message + " should resolve: " + actual.error());
        assertEquals(expected.toAbsolutePath().normalize(), actual.path(), message);
    }
}
