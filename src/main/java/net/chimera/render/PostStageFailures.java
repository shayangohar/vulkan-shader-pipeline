package net.chimera.render;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which post stages threw while recording this session, and whether the chain may go on without
 * them (TASK-922). A stage that throws is skipped from then on, as an identity stage, and the rest
 * of the chain and the gbuffers keep running. A device-level failure, or a third stage failing,
 * still turns the pack off: the first says the device itself is in trouble, the second that the
 * failures are not one optional pass's.
 */
final class PostStageFailures {
    /** The distinct failing stages at which the session stops isolating and falls back to vanilla. */
    static final int VANILLA_AT = 3;
    /** VkResult codes for out of host memory, out of device memory and device lost. */
    private static final Set<String> DEVICE_RESULTS = Set.of("-1", "-2", "-4");

    private final Set<String> skipped = new LinkedHashSet<>();

    /** True when {@code stage} failed earlier this session and now runs as identity. */
    boolean skipped(String stage) {
        return this.skipped.contains(stage);
    }

    /**
     * Records {@code stage}'s failure. True when the chain may go on without it; false when the
     * session falls back to vanilla.
     */
    boolean isolate(String stage, Throwable failure) {
        if (deviceLevel(failure)) return false;
        this.skipped.add(stage);
        return this.skipped.size() < VANILLA_AT;
    }

    /** How many distinct stages have failed this session. */
    int count() {
        return this.skipped.size();
    }

    void clear() {
        this.skipped.clear();
    }

    /**
     * True when the failure, or one of its causes, is the device's rather than one pass's: Java's
     * out of memory, or a Vulkan result of out of host or device memory or device lost. VulkanMod
     * names the result (VkResult.decode); Chimera's own checks end the message with its code.
     */
    static boolean deviceLevel(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof OutOfMemoryError) return true;
            String message = cause.getMessage();
            if (message == null) continue;
            String upper = message.toUpperCase(Locale.ROOT);
            if (upper.contains("VK_ERROR_DEVICE_LOST") || upper.contains("VK_ERROR_OUT_OF_DEVICE_MEMORY")
                    || upper.contains("VK_ERROR_OUT_OF_HOST_MEMORY")) {
                return true;
            }
            int colon = message.lastIndexOf(':');
            if (colon >= 0 && DEVICE_RESULTS.contains(message.substring(colon + 1).trim())) return true;
        }
        return false;
    }
}
