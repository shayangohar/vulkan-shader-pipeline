package net.chimera.render;

import java.util.Arrays;

/**
 * Small render-thread state machine for previous-frame validity. Physical
 * images remain owned by PackPostTargets and PackDepthTargets.
 */
public final class PackTemporalState {
    private final boolean[] current = new boolean[8];
    private final boolean[] previous = new boolean[8];
    private final boolean[] pending = new boolean[8];
    private boolean firstFrame = true;
    private boolean frameOpen;

    public void beginFrame(boolean hdrIdentity) {
        Arrays.fill(current, false);
        Arrays.fill(pending, false);
        current[0] = hdrIdentity;
        frameOpen = true;
    }

    public void seedCurrent(int target) {
        if (frameOpen && valid(target)) current[target] = true;
    }

    public void stageWrite(int target) {
        if (frameOpen && valid(target)) pending[target] = true;
    }

    public boolean commit() {
        if (!frameOpen) return false;
        for (int i = 0; i < current.length; i++) {
            if (pending[i]) current[i] = true;
        }
        System.arraycopy(current, 0, previous, 0, current.length);
        firstFrame = false;
        frameOpen = false;
        return true;
    }

    public void abort() {
        Arrays.fill(pending, false);
        frameOpen = false;
    }

    public void invalidate(int target) {
        if (valid(target)) current[target] = false;
    }

    public boolean currentAvailable(int target) {
        return valid(target) && current[target];
    }

    public boolean previousAvailable(int target) {
        return valid(target) && previous[target];
    }

    public boolean firstFrame() { return firstFrame; }
    public boolean frameOpen() { return frameOpen; }

    public void reset() {
        Arrays.fill(current, false);
        Arrays.fill(previous, false);
        Arrays.fill(pending, false);
        firstFrame = true;
        frameOpen = false;
    }

    static boolean commitTarget(boolean current, boolean pending, boolean committed) {
        return committed && (current || pending);
    }

    static int nextSide(int currentSide, int sideCount) {
        if (sideCount < 2) return 0;
        return (currentSide + 1) % sideCount;
    }

    private static boolean valid(int target) {
        return target >= 0 && target < 8;
    }
}
