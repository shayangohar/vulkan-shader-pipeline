package net.chimera.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** File helpers for tests that build a directory outside a JUnit {@code @TempDir}. */
public final class TestFiles {
    private TestFiles() {}

    /** Deletes a directory and everything under it; a missing directory is already deleted. */
    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
