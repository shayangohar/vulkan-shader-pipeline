package net.chimera.testing;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Tests that create a headless Vulkan device run only when the build asks for them. */
public final class HeadlessVulkan {
    private HeadlessVulkan() {}

    /** Skips the calling test unless {@code -Pchimera.gpu=true}. */
    public static void assumeEnabled() {
        assumeTrue(Boolean.getBoolean("chimera.gpu"), "headless Vulkan checks run with -Pchimera.gpu=true");
    }
}
