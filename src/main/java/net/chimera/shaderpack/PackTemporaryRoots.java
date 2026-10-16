package net.chimera.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The temporary folders a pack is unpacked or copied into ({@code chimera-pack-*}). Each folder holds a
 * lock file that this process keeps locked while the folder is in use. A game that exits without
 * cleaning up (a crash, or a driver fault at shutdown) leaves its folder behind; the next start
 * removes every such folder no running game still holds.
 */
public final class PackTemporaryRoots {
    static final String PREFIX = "chimera-pack-";
    static final String LOCK_FILE = ".chimera-lock";
    /** A folder without a lock file may be one another game created a moment ago and is about to lock. */
    private static final Duration UNLOCKED_GRACE = Duration.ofMinutes(1);
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private static final Map<Path, FileChannel> HELD = new ConcurrentHashMap<>();

    private PackTemporaryRoots() {}

    /** A new folder in the system temporary directory, locked until {@link #delete}. */
    static Path create() throws IOException {
        return create(Path.of(System.getProperty("java.io.tmpdir")));
    }

    static Path create(Path directory) throws IOException {
        Path root = Files.createTempDirectory(directory, PREFIX);
        try {
            FileChannel channel = FileChannel.open(root.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (channel.tryLock() == null) {
                channel.close();
                throw new IOException("cannot lock " + root);
            }
            HELD.put(root, channel);
            return root;
        } catch (IOException | RuntimeException failure) {
            PackSource.deleteTree(root);
            throw failure;
        }
    }

    /** Releases the folder's lock and removes it with everything in it. */
    static void delete(Path root) {
        if (root == null) return;
        FileChannel channel = HELD.remove(root);
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException failure) {
                LOGGER.warn("[chimera] pack: cannot release {}: {}", root, failure.getMessage());
            }
        }
        PackSource.deleteTree(root);
    }

    /** Removes, off the render thread, the folders earlier games left in the system temporary directory. */
    public static void sweepInBackground() {
        Thread sweeper = new Thread(() -> sweep(Path.of(System.getProperty("java.io.tmpdir"))),
                "chimera-temp-sweep");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    /** Removes the folders in {@code directory} that no running game holds; returns how many. */
    static int sweep(Path directory) {
        int removed = 0;
        try (DirectoryStream<Path> roots = Files.newDirectoryStream(directory, PREFIX + "*")) {
            for (Path root : roots) {
                if (Files.isDirectory(root) && !HELD.containsKey(root) && abandoned(root)) {
                    PackSource.deleteTree(root);
                    removed++;
                }
            }
        } catch (IOException failure) {
            LOGGER.warn("[chimera] pack: cannot list {}: {}", directory, failure.getMessage());
        }
        if (removed > 0) LOGGER.info("[chimera] removed {} pack folders earlier games left in {}", removed, directory);
        return removed;
    }

    /** True when no game holds the folder: its lock is free, or it has none and is not brand new. */
    private static boolean abandoned(Path root) {
        Path lock = root.resolve(LOCK_FILE);
        try {
            if (!Files.exists(lock)) {
                Instant modified = Files.getLastModifiedTime(root).toInstant();
                return modified.isBefore(Instant.now().minus(UNLOCKED_GRACE));
            }
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
                FileLock free = channel.tryLock();
                if (free == null) return false;
                free.release();
                return true;
            }
        } catch (IOException | OverlappingFileLockException held) {
            return false;
        }
    }
}
