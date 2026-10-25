package net.chimera.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PackImageLimitsTest {
    /**
     * Alpha Piscium's 4096x2048 star map fits a 16384 limit: only the device's image limit refuses
     * a pack texture, not its encoded size or pixel count.
     */
    @Test
    void anImageWithinTheDeviceLimitIsMade() {
        assertNull(PackImageLimits.failure("texture", 2, new int[] {4096, 2048}, 16384));
        assertNull(PackImageLimits.failure("texture", 2, new int[] {16384, 16384}, 16384), "the limit itself fits");
    }

    /** Each axis is checked against the limit for the image's dimensions; only those axes count. */
    @Test
    void anAxisPastTheLimitNamesTheImageAndTheLimit() {
        assertEquals("texture at 16385x8 exceeds the device's 2D limit 16384",
                PackImageLimits.failure("texture", 2, new int[] {16385, 8}, 16384));
        assertEquals("raw texture at 64x64x4096 exceeds the device's 3D limit 2048",
                PackImageLimits.failure("raw texture", 3, new int[] {64, 64, 4096}, 2048));
        assertNull(PackImageLimits.failure("raw texture", 1, new int[] {256, 99999, 99999}, 16384),
                "a 1D image's unused axes are not its extent");
    }

    /** Without a device, as in these tests, nothing refuses the image. */
    @Test
    void noDeviceRefusesNothing() {
        assertNull(PackImageLimits.deviceFailure("texture", 2, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
