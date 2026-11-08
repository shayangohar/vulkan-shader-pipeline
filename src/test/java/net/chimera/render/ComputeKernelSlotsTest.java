package net.chimera.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A compute kernel lives for the pack session, and the player can raise VulkanMod's frame queue
 * size within it. Slots sized to the frames in flight at build were then shared by two frames still
 * in flight (frames 0 and 2 on a 2-slot ring at 3 frames), rewriting a set and block the other used.
 */
final class ComputeKernelSlotsTest {
    @Test
    void aKernelHasASlotForEveryFrameVulkanModCanHaveInFlight() {
        assertEquals(5, ComputeKernel.slotCount(2), "VulkanMod's frame queue reaches 5");
        assertEquals(5, ComputeKernel.slotCount(3));
        assertEquals(5, ComputeKernel.slotCount(0));
        for (int frames = 2; frames <= 5; frames++) {
            int slots = ComputeKernel.slotCount(2);
            for (int frame = 0; frame < frames; frame++) {
                for (int other = frame + 1; other < frames; other++) {
                    assertEquals(false, frame % slots == other % slots, frames + " frames share no slot");
                }
            }
        }
    }
}
