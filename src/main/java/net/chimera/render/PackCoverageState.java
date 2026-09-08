package net.chimera.render;

import java.util.Arrays;

/** Pure per-frame logical state for scene-seed coverage. */
public final class PackCoverageState {
    private boolean frameActive;
    private boolean seedApplied;
    private boolean writePending;
    private int writeCount;

    public void beginFrame() {
        frameActive = true;
        seedApplied = false;
        writePending = false;
        writeCount = 0;
    }

    public void beginPackWrite() {
        if (!frameActive) throw new IllegalStateException("coverage frame is not active");
        writePending = true;
    }

    public void commitPackWrite() {
        if (writePending) {
            writeCount++;
            writePending = false;
        }
    }

    public void abortPackWrite() {
        writePending = false;
    }

    public boolean canSeed() {
        return frameActive && !seedApplied;
    }

    public void commitSeed() {
        if (!canSeed()) throw new IllegalStateException("coverage seed is not available");
        seedApplied = true;
    }

    public boolean seedApplied() { return seedApplied; }
    public boolean frameActive() { return frameActive; }
    public int writeCount() { return writeCount; }

    public void endFrame() {
        frameActive = false;
        writePending = false;
    }

    public static boolean seedPreservesCoverage(float coverage, float emptySentinel) {
        return coverage != emptySentinel;
    }

    public static boolean[] copy(boolean[] values) {
        return values == null ? new boolean[0] : Arrays.copyOf(values, values.length);
    }
}
