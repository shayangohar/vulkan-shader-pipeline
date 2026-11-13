package net.chimera.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A post stage that throws is skipped and the chain goes on; the device's own trouble, or a third
 * failing stage, still turns the pack off (TASK-922).
 */
final class PostStageFailuresTest {
    @Test
    void aStageThatThrowsIsSkippedAndTheChainGoesOn() {
        PostStageFailures failures = new PostStageFailures();
        assertFalse(failures.skipped("composite5"));
        assertTrue(failures.isolate("composite5", new IllegalStateException("Unexpected value:1")));
        assertTrue(failures.skipped("composite5"));
        assertFalse(failures.skipped("composite6"));
    }

    @Test
    void theThirdFailingStageTurnsThePackOff() {
        PostStageFailures failures = new PostStageFailures();
        assertTrue(failures.isolate("deferred1", new IllegalStateException("a")));
        assertTrue(failures.isolate("composite2", new IllegalStateException("b")));
        assertFalse(failures.isolate("composite3", new IllegalStateException("c")));
        assertTrue(PostStageFailures.VANILLA_AT == 3);
    }

    @Test
    void theSameStageTwiceCountsOnce() {
        PostStageFailures failures = new PostStageFailures();
        assertTrue(failures.isolate("final", new IllegalStateException("a")));
        assertTrue(failures.isolate("final", new IllegalStateException("a")));
        assertTrue(failures.isolate("composite", new IllegalStateException("b")));
    }

    @Test
    void deviceLevelFailuresAreNotIsolated() {
        assertTrue(PostStageFailures.deviceLevel(new RuntimeException("Failed to submit draw command buffer: "
                + "VK_ERROR_DEVICE_LOST")));
        assertTrue(PostStageFailures.deviceLevel(new IllegalStateException("advanced image allocation failed: -2")));
        assertTrue(PostStageFailures.deviceLevel(new RuntimeException("Failed to record command buffer: -4")));
        assertTrue(PostStageFailures.deviceLevel(new RuntimeException("wrapped",
                new RuntimeException("VK_ERROR_OUT_OF_HOST_MEMORY"))));
        assertTrue(PostStageFailures.deviceLevel(new RuntimeException("wrapped", new OutOfMemoryError())));
        assertFalse(PostStageFailures.deviceLevel(new IllegalStateException("Unexpected value:1")));
        assertFalse(PostStageFailures.deviceLevel(new IllegalStateException("missing output target 4")));
        assertFalse(PostStageFailures.deviceLevel(new IllegalStateException((String) null)));

        PostStageFailures failures = new PostStageFailures();
        assertFalse(failures.isolate("composite", new RuntimeException("VK_ERROR_DEVICE_LOST")));
        assertFalse(failures.skipped("composite"), "a device-level failure turns the pack off instead");
    }

    @Test
    void aNewPackSessionForgetsTheSkips() {
        PostStageFailures failures = new PostStageFailures();
        failures.isolate("composite", new IllegalStateException("a"));
        failures.clear();
        assertFalse(failures.skipped("composite"));
    }
}
