package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pack folders a game left behind go at the next start; folders a running game holds stay. */
final class PackTemporaryRootsTest {
    private static Path folder(Path directory, String name, boolean withLock, Duration age) throws IOException {
        Path root = Files.createDirectories(directory.resolve(name).resolve("shaders"));
        Files.writeString(root.resolve("final.fsh"), "void main() {}\n");
        if (withLock) Files.writeString(root.getParent().resolve(PackTemporaryRoots.LOCK_FILE), "");
        Files.setLastModifiedTime(root.getParent(), FileTime.from(Instant.now().minus(age)));
        return root.getParent();
    }

    @Test
    void foldersNoGameHoldsAreRemoved(@TempDir Path directory) throws IOException {
        Path held = PackTemporaryRoots.create(directory);
        Path unlocked = folder(directory, "chimera-pack-1", true, Duration.ofDays(2));
        Path beforeLocks = folder(directory, "chimera-pack-2", false, Duration.ofHours(1));
        Path brandNew = folder(directory, "chimera-pack-3", false, Duration.ZERO);
        Path other = folder(directory, "chimera-renderdoc-1", false, Duration.ofDays(2));

        assertEquals(2, PackTemporaryRoots.sweep(directory));
        assertTrue(Files.isDirectory(held), "this game's folder is still in use");
        assertFalse(Files.exists(unlocked), "a folder whose lock is free was left by a game that is gone");
        assertFalse(Files.exists(beforeLocks), "a folder from a build before locks is gone too");
        assertTrue(Files.isDirectory(brandNew), "a new unlocked folder may be one another game is about to lock");
        assertTrue(Files.isDirectory(other), "only pack folders are touched");

        PackTemporaryRoots.delete(held);
        assertFalse(Files.exists(held));
    }
}
