package net.chimera.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The queued pack-change state an in-game command fills and the next safe frame boundary applies. */
final class PackSessionLifecycleTest {
    @Test
    void packChangeQueue() {
        Path active = Path.of("active");
        Path first = Path.of("first");
        Path second = Path.of("second");
        PackSessionLifecycle.PackChangeQueue queue = new PackSessionLifecycle.PackChangeQueue();

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
}
