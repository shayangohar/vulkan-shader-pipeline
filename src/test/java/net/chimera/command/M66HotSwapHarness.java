package net.chimera.command;

import net.chimera.render.ChimeraMainPass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Pure M6.6 checks for command path resolution and queued replacement state. */
public final class M66HotSwapHarness {
    private M66HotSwapHarness() {}

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("chimera-m66-");
        try {
            verifyPathResolution(root);
            verifyPackChangeQueue();
            System.out.println("[chimera] M6.6 hot-swap harness: PASS");
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyPathResolution(Path root) throws IOException {
        Path shaderpacks = root.resolve("shaderpacks");
        Path gameDirectory = root.resolve("game with spaces");
        Files.createDirectories(shaderpacks);
        Files.createDirectories(gameDirectory);

        Path simplex = Files.createDirectories(shaderpacks.resolve("Simplex"));
        Path bsl = shaderpacks.resolve("BSL.zip");
        Files.writeString(bsl, "zip placeholder", StandardCharsets.UTF_8);
        Files.createDirectories(shaderpacks.resolve("Other"));
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
        Files.createDirectories(explicit);
        assertPath(explicit, ChimeraCommands.resolvePackPath(
                        shaderpacks, gameDirectory, "\".\\local pack\""),
                "quoted relative path");
        assertTrue(!ChimeraCommands.resolvePackPath(shaderpacks, gameDirectory, "missing").valid(),
                "missing pack must fail");

        Files.createDirectories(shaderpacks.resolve("Duplicate"));
        Files.writeString(shaderpacks.resolve("Duplicate.zip"), "zip placeholder", StandardCharsets.UTF_8);
        ChimeraCommands.Resolution ambiguous = ChimeraCommands.resolvePackPath(
                shaderpacks, gameDirectory, "Duplicate");
        assertTrue(!ambiguous.valid() && ambiguous.error().contains("ambiguous"),
                "duplicate name must be rejected");
        assertTrue(!ChimeraCommands.resolvePackPath(shaderpacks, gameDirectory, "ignored.txt").valid(),
                "non-ZIP file must fail");
    }

    private static void verifyPackChangeQueue() {
        Path active = Path.of("active");
        Path first = Path.of("first");
        Path second = Path.of("second");
        ChimeraMainPass.PackChangeQueue queue = new ChimeraMainPass.PackChangeQueue();

        assertTrue(!queue.request(active, active, false), "same active pack must be a no-op");
        assertTrue(!queue.request(Path.of("relative", "active"),
                        Path.of("relative", ".", "active").toAbsolutePath(), false),
                "equivalent normalized paths must be a no-op");
        assertTrue(queue.request(active, first, false), "first request must queue");
        assertEquals(first, queue.pendingPath(), "first pending pack");
        assertTrue(queue.request(active, second, false), "latest request must replace first");
        assertEquals(second, queue.pendingPath(), "latest pending pack");
        assertTrue(!queue.request(active, second, false), "duplicate pending request must be a no-op");
        assertTrue(queue.request(active, second, true), "forced reload must queue");
        assertTrue(queue.pendingForced(), "forced reload marker");
        queue.clear();
        assertTrue(!queue.hasPending(), "clear must remove pending request");
        assertTrue(queue.request(active, null, false), "identity selection must queue");
        assertTrue(queue.pendingPath() == null, "identity selection path");
    }

    private static void assertPath(Path expected, ChimeraCommands.Resolution actual, String message) {
        assertTrue(actual.valid(), message + " should resolve: " + actual.error());
        assertEquals(expected.toAbsolutePath().normalize(), actual.path(), message);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
        }
    }
}
