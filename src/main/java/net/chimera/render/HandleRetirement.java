package net.chimera.render;

import net.vulkanmod.vulkan.memory.MemoryManager;

import java.util.function.Consumer;

/**
 * Chimera's one rule for destroying a Vulkan handle it owns once it is in use: never while a frame
 * that may have recorded it can still run on the GPU (KNOW-509).
 * <ul>
 * <li>Between an idle wait and the next frame's recording (a pack change, a dimension variant, the
 * final cleanup), no frame can, and the handle goes at once. A pack change then never holds the
 * old pack's images beside the new pack's.</li>
 * <li>Otherwise it goes in a VulkanMod frame op. That runs when this frame slot comes round again,
 * after its fence, so after every frame recorded so far. At shutdown, Vulkan.cleanUp runs the
 * pending ops (MemoryManager.cleanUp) before it destroys the device.</li>
 * </ul>
 * Never retire from inside a frame op (KNOW-702): an op queued while VulkanMod runs a slot's ops
 * runs in the same pass, before the other slots' frames finish. A handle that nothing has used
 * yet, such as one a failed creation leaves, is destroyed directly.
 */
final class HandleRetirement {
    /** Render thread only, like every caller. */
    private static boolean idle;
    /** Where deferred destroys go: VulkanMod's frame ops; tests swap it. */
    static Consumer<Runnable> frameOps = destroy -> MemoryManager.getInstance().addFrameOp(destroy);

    private HandleRetirement() {}

    /** The device went idle (Vulkan.waitIdle) at a frame boundary, and nothing records until {@link #recording}. */
    static void idleUntilRecording() {
        idle = true;
    }

    /** A frame is about to record (ChimeraRenderer.beforeMainCommandBuffer ends): destroys wait again. */
    static void recording() {
        idle = false;
    }

    /** Destroys a handle by {@code destroy} now if no frame can use it, else once every recorded frame has run. */
    static void retire(Runnable destroy) {
        if (idle) {
            destroy.run();
        } else {
            frameOps.accept(destroy);
        }
    }
}
