package net.chimera.shaderpack;

import net.chimera.shaderpack.options.OptionFile;
import net.chimera.shaderpack.options.OptionSet;
import net.chimera.shaderpack.options.OptionValues;
import net.chimera.shaderpack.options.PendingOptions;
import net.chimera.shaderpack.options.ShaderPackOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Applies the player's option values to a pack's sources before anything reads them.
 *
 * <p>Iris applies an option by editing the lines that declare it, so a {@code const} option changes
 * as well as a {@code #define}. Chimera makes the same edits on disk: a zip pack's extracted tree is
 * edited in place, and a folder pack is copied first, since that folder is the player's own.
 *
 * <p>The option file's values follow Iris's rules: a name the pack does not declare, or a toggle
 * that is not {@code true} or {@code false}, is ignored. A {@code chimera.option.*} JVM property for
 * a declared option applies on top, with {@code 1} and {@code 0} also accepted for a toggle. Any
 * other JVM property stays a locked preprocessor macro, for development runs.
 */
final class PackOptionStaging {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    private PackOptionStaging() {}

    /** Where the sources are after staging, with the options they were staged with. */
    record Staged(Path shadersDir, Path temporaryRoot, StagedOptions options) {}

    /**
     * Discovers the pack's options in {@code shadersDir}, applies the stored values and returns the
     * tree to read. {@code temporaryRoot} is the extraction root of a zip pack and null for a folder.
     * The stored values are the option file's with {@code pending} on top, or none at all when
     * {@code pending} asks for a reset; they are what the option file should hold afterwards.
     */
    static Staged stage(Path packPath, Path shadersDir, Path temporaryRoot, PendingOptions.Drained pending,
                        List<String> deviations) {
        Stored stored = stored(packPath, pending, deviations);
        ShaderPackOptions pack = ShaderPackOptions.load(shadersDir, stored.values());
        StagedOptions options = withOverrides(pack, stored.readable(), deviations);
        Map<String, String> edits = pack.editedFiles(options.applied());
        if (edits.isEmpty()) return new Staged(shadersDir, temporaryRoot, options);
        return writeEdits(shadersDir, temporaryRoot, edits, options, deviations);
    }

    /** The stored values, and whether the option file could be read (an unreadable one is kept as is). */
    private record Stored(Map<String, String> values, boolean readable) {}

    private static Stored stored(Path packPath, PendingOptions.Drained pending, List<String> deviations) {
        Map<String, String> values = new TreeMap<>();
        if (pending.reset()) return new Stored(values, true);
        boolean readable = true;
        Path file = packPath == null ? null : OptionFile.of(packPath);
        if (file != null && Files.isRegularFile(file)) {
            try {
                values.putAll(OptionFile.readStrict(file));
            } catch (IOException unreadable) {
                // Iris refuses such a file rather than rewrite it; keep it for the player to fix.
                LOGGER.warn("[chimera] cannot read the option file {}: {}", file, unreadable.getMessage());
                deviations.add("PACK_OPTION_FILE_UNREADABLE");
                readable = false;
            }
        }
        values.putAll(pending.values());
        return new Stored(values, readable);
    }

    /** The stored values with the JVM overrides for declared options on top. */
    private static StagedOptions withOverrides(ShaderPackOptions pack, boolean fileReadable,
                                               List<String> deviations) {
        OptionValues applied = pack.values().copy();
        Map<String, String> undeclared = new TreeMap<>();
        PackOptionSources.systemOverrides().forEach((name, text) -> {
            String declared = declaredText(pack.options(), name, text);
            if (declared == null) {
                undeclared.put(name, text);
            } else {
                applied.apply(Map.of(name, declared));
            }
        });
        applied.changes().keySet().forEach(name -> deviations.add("PACK_OPTION_OVERRIDE:" + name));
        return new StagedOptions(pack, applied, undeclared, Set.copyOf(pack.profileDisabledPrograms(applied)),
                fileReadable);
    }

    /** Writes the edited lines into the extracted tree, or into a copy of a folder pack. */
    private static Staged writeEdits(Path shadersDir, Path temporaryRoot, Map<String, String> edits,
                                     StagedOptions options, List<String> deviations) {
        Path root = temporaryRoot;
        try {
            if (root == null) {
                root = Files.createTempDirectory("chimera-pack-");
                copyTree(shadersDir, root.resolve("shaders"));
            }
            Path staged = root.resolve("shaders");
            for (Map.Entry<String, String> edit : edits.entrySet()) {
                Files.writeString(staged.resolve(edit.getKey().substring(1)), edit.getValue(), StandardCharsets.UTF_8);
            }
            return new Staged(staged, root, options);
        } catch (IOException failure) {
            LOGGER.warn("[chimera] pack options cannot be applied to {}: {}", shadersDir, failure.getMessage());
            deviations.add("PACK_OPTION_STAGING_FAILED");
            if (temporaryRoot == null) {
                PackSource.deleteTree(root);
                return new Staged(shadersDir, null, options);
            }
            return new Staged(root.resolve("shaders"), root, options);
        }
    }

    /** The option-file text a JVM override means for a declared option; null when it is not one. */
    private static String declaredText(OptionSet options, String name, String text) {
        if (options.isChoice(name)) return text;
        if (!options.isToggle(name)) return null;
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1" -> "true";
            case "false", "0" -> "false";
            default -> null;
        };
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }
}
