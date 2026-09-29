package net.chimera.render.shader;

import net.chimera.ChimeraMod;

/**
 * Opt-in diagnostic ({@code -Dchimera.frameClockLog=true}) for pack animation
 * smoothness. It compares the real interval between pack frames with the
 * frameTime the packs receive, and reports hitches as they happen.
 *
 * <p>Pack animation (foliage sway, water) advances by frameTimeCounter. A clock
 * that under-reports frames, or real frames that stall, both look like the
 * animation jumping; this log tells the two apart.</p>
 */
final class PackFrameClockLog {
    static final boolean ENABLED = Boolean.getBoolean("chimera.frameClockLog");
    private static final long REPORT_INTERVAL_NANOS = 2_000_000_000L;
    private static final long HITCH_NANOS = 50_000_000L;

    private long windowStart;
    private int frames;
    private long realNanos;
    private long minNanos = Long.MAX_VALUE;
    private long maxNanos;
    private double fedSeconds;
    private int zeroFed;
    private int hitches;

    void record(long now, long elapsedNanos, float fedSeconds, float frameTimeCounter) {
        if (windowStart == 0L) {
            windowStart = now;
        }
        frames++;
        realNanos += elapsedNanos;
        minNanos = Math.min(minNanos, elapsedNanos);
        maxNanos = Math.max(maxNanos, elapsedNanos);
        this.fedSeconds += fedSeconds;
        if (fedSeconds == 0.0f) zeroFed++;
        if (elapsedNanos >= HITCH_NANOS) {
            hitches++;
            ChimeraMod.LOGGER.info("[chimera] frame clock hitch: {} ms real, frameTime {} s, frameTimeCounter {}",
                    String.format("%.1f", elapsedNanos / 1.0e6), fedSeconds, frameTimeCounter);
        }
        if (now - windowStart < REPORT_INTERVAL_NANOS) {
            return;
        }
        double realSeconds = realNanos / 1.0e9;
        ChimeraMod.LOGGER.info("[chimera] frame clock: {} frames, {} fps, real ms min/mean/max {}/{}/{}, "
                        + "fed/real {}, zero-frameTime frames {}, hitches {}, frameTimeCounter {}",
                frames,
                String.format("%.0f", frames / Math.max(realSeconds, 1e-9)),
                String.format("%.2f", minNanos / 1.0e6),
                String.format("%.2f", realNanos / 1.0e6 / frames),
                String.format("%.2f", maxNanos / 1.0e6),
                String.format("%.3f", this.fedSeconds / Math.max(realSeconds, 1e-9)),
                zeroFed, hitches, frameTimeCounter);
        windowStart = now;
        frames = 0;
        realNanos = 0L;
        minNanos = Long.MAX_VALUE;
        maxNanos = 0L;
        this.fedSeconds = 0.0;
        zeroFed = 0;
        hitches = 0;
    }
}
