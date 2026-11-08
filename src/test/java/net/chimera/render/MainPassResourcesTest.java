package net.chimera.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/** When a recreated swapchain keeps the main pass's resources, and with them the pack's history. */
final class MainPassResourcesTest {
    private static final int FORMAT = VK_FORMAT_B8G8R8A8_UNORM;

    /** A minimized window recreates the swapchain at 0x0; Minecraft and Iris ignore that size. */
    @Test
    void aMinimizedWindowKeepsTheResources() {
        assertTrue(MainPassResources.serves(2560, 1361, FORMAT, 0, 0, FORMAT));
    }

    /** The restore recreates it at the old size, which is no resize for Iris either. */
    @Test
    void aRestoreAtTheBuiltSizeKeepsTheResources() {
        assertTrue(MainPassResources.serves(2560, 1361, FORMAT, 2560, 1361, FORMAT));
    }

    @Test
    void aNewSizeRebuildsTheResources() {
        assertFalse(MainPassResources.serves(2560, 1361, FORMAT, 1280, 720, FORMAT));
        assertFalse(MainPassResources.serves(2560, 1361, FORMAT, 2560, 1440, FORMAT));
    }

    /** Resources built while minimized are 1x1, so the restore rebuilds them at full size. */
    @Test
    void resourcesBuiltWhileMinimizedAreRebuiltOnRestore() {
        assertFalse(MainPassResources.serves(1, 1, FORMAT, 2560, 1361, FORMAT));
    }

    /** The present render pass is built for the swapchain's format. */
    @Test
    void aNewPresentFormatRebuildsTheResources() {
        assertFalse(MainPassResources.serves(2560, 1361, FORMAT, 2560, 1361, VK_FORMAT_R8G8B8A8_UNORM));
    }
}
