package net.chimera.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** The shader selector's persisted state and Iris's rules for what a pack is. */
public final class SelectorConfigHarness {
    private SelectorConfigHarness() {}

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("chimera-selector");
        try {
            verifyConfigRoundTrip(root);
            verifyPackRules(root);
            System.out.println("[chimera] shader selector config: PASS");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static void verifyConfigRoundTrip(Path root) {
        Path file = root.resolve("config").resolve("chimera.properties");
        ChimeraConfig fresh = ChimeraConfig.load(file);
        check(fresh.shaderPack().isEmpty() && !fresh.shadersEnabled(), "a missing file is no pack, shaders off");
        fresh.setSelection("BSL_v10.1.8.zip", true);
        ChimeraConfig reread = ChimeraConfig.load(file);
        check(reread.shaderPack().orElse("").equals("BSL_v10.1.8.zip") && reread.shadersEnabled(),
                "selection survives a reload");
        reread.setSelection("BSL_v10.1.8.zip", false);
        check(!ChimeraConfig.load(file).shadersEnabled()
                        && ChimeraConfig.load(file).shaderPack().isPresent(),
                "turning shaders off keeps the pack name, as Iris does");
    }

    private static void verifyPackRules(Path root) throws IOException {
        Path packs = Files.createDirectories(root.resolve("shaderpacks"));
        Files.createDirectories(packs.resolve("Folder Pack").resolve("shaders"));
        Files.createDirectories(packs.resolve("NotAPack"));
        Files.writeString(packs.resolve("Zip Pack.zip"), "zip", StandardCharsets.UTF_8);
        Files.writeString(packs.resolve("notes.txt"), "text", StandardCharsets.UTF_8);
        check(ShaderpackDirectory.list(packs).equals(List.of("Folder Pack", "Zip Pack.zip")),
                "list: " + ShaderpackDirectory.list(packs));
        check(ShaderpackDirectory.resolve(packs, "Zip Pack.zip").isPresent(), "a zip resolves by name");
        check(ShaderpackDirectory.resolve(packs, "NotAPack").isEmpty(), "a folder without shaders/ is no pack");
        check(ShaderpackDirectory.resolve(packs, "../shaderpacks/Zip Pack.zip").isEmpty(),
                "a name may not leave the folder");

        Path dropped = Files.createDirectories(root.resolve("Dropped").resolve("shaders"));
        Files.writeString(dropped.resolve("final.fsh"), "void main() {}", StandardCharsets.UTF_8);
        ShaderpackDirectory.copyInto(packs, dropped.getParent());
        check(Files.isRegularFile(packs.resolve("Dropped").resolve("shaders").resolve("final.fsh")),
                "a dropped folder pack is copied whole");
        try {
            ShaderpackDirectory.copyInto(packs, dropped.getParent());
            throw new AssertionError("a second copy overwrote the pack");
        } catch (FileAlreadyExistsException expected) {
            // Iris refuses to overwrite a pack already in the folder.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
