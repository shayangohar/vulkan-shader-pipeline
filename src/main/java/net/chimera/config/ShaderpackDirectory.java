package net.chimera.config;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The game's {@code shaderpacks} folder and what counts as a pack in it, shared by the selector
 * screen, the {@code /chimera pack} commands and startup. A pack is what Iris accepts: a
 * {@code .zip} file, or a folder that contains a {@code shaders} folder.
 */
public final class ShaderpackDirectory {
    private ShaderpackDirectory() {}

    public static Path root() {
        return root(Minecraft.getInstance().gameDirectory.toPath());
    }

    public static Path root(Path gameDirectory) {
        return gameDirectory.resolve("shaderpacks").normalize();
    }

    /** Iris's {@code isValidShaderpack}: a zip, or a folder holding a shaders folder. */
    public static boolean isValidPack(Path path) {
        if (path == null) return false;
        if (Files.isDirectory(path)) {
            return Files.isDirectory(path.resolve("shaders"));
        }
        return Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /** Pack file and folder names in the folder, sorted case-insensitively. */
    public static List<String> list(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            return entries.filter(ShaderpackDirectory::isValidPack)
                    .map(path -> path.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
    }

    /** The pack with exactly this file or folder name in {@code root}, if it is one. */
    public static Optional<Path> resolve(Path root, String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..")) {
            return Optional.empty();
        }
        Path candidate = root.resolve(name).normalize();
        return candidate.startsWith(root) && isValidPack(candidate)
                ? Optional.of(candidate) : Optional.empty();
    }

    /** Copies a dropped pack into the folder; refuses to overwrite one already there. */
    public static void copyInto(Path root, Path pack) throws IOException {
        Files.createDirectories(root);
        Path target = root.resolve(pack.getFileName().toString());
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (Files.isDirectory(pack)) {
            try (var walk = Files.walk(pack)) {
                for (Path source : (Iterable<Path>) walk::iterator) {
                    Files.copy(source, target.resolve(pack.relativize(source).toString()));
                }
            }
        } else {
            Files.copy(pack, target);
        }
    }
}
