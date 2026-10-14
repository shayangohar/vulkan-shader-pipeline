package net.chimera.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shader selector's persisted state and Iris's rules for what a pack is. */
final class SelectorConfigTest {
    @Test
    void configRoundTrip(@TempDir Path root) {
        Path file = root.resolve("config").resolve("chimera.properties");
        ChimeraConfig fresh = ChimeraConfig.load(file);
        assertTrue(fresh.shaderPack().isEmpty() && !fresh.shadersEnabled(), "a missing file is no pack, shaders off");
        fresh.setSelection("BSL_v10.1.8.zip", true);
        ChimeraConfig reread = ChimeraConfig.load(file);
        assertTrue(reread.shaderPack().orElse("").equals("BSL_v10.1.8.zip") && reread.shadersEnabled(),
                "selection survives a reload");
        reread.setSelection("BSL_v10.1.8.zip", false);
        assertTrue(!ChimeraConfig.load(file).shadersEnabled()
                        && ChimeraConfig.load(file).shaderPack().isPresent(),
                "turning shaders off keeps the pack name, as Iris does");
    }

    @Test
    void packRules(@TempDir Path root) throws IOException {
        Path packs = Files.createDirectories(root.resolve("shaderpacks"));
        Files.createDirectories(packs.resolve("Folder Pack").resolve("shaders"));
        Files.createDirectories(packs.resolve("NotAPack"));
        Files.writeString(packs.resolve("Zip Pack.zip"), "zip", StandardCharsets.UTF_8);
        Files.writeString(packs.resolve("notes.txt"), "text", StandardCharsets.UTF_8);
        assertTrue(ShaderpackDirectory.list(packs).equals(List.of("Folder Pack", "Zip Pack.zip")),
                "list: " + ShaderpackDirectory.list(packs));
        assertTrue(ShaderpackDirectory.resolve(packs, "Zip Pack.zip").isPresent(), "a zip resolves by name");
        assertTrue(ShaderpackDirectory.resolve(packs, "NotAPack").isEmpty(), "a folder without shaders/ is no pack");
        assertTrue(ShaderpackDirectory.resolve(packs, "../shaderpacks/Zip Pack.zip").isEmpty(),
                "a name may not leave the folder");

        Path dropped = Files.createDirectories(root.resolve("Dropped").resolve("shaders"));
        Files.writeString(dropped.resolve("final.fsh"), "void main() {}", StandardCharsets.UTF_8);
        ShaderpackDirectory.copyInto(packs, dropped.getParent());
        assertTrue(Files.isRegularFile(packs.resolve("Dropped").resolve("shaders").resolve("final.fsh")),
                "a dropped folder pack is copied whole");
        // Iris refuses to overwrite a pack already in the folder.
        assertThrows(FileAlreadyExistsException.class, () -> ShaderpackDirectory.copyInto(packs, dropped.getParent()),
                "a second copy overwrote the pack");
    }
}
