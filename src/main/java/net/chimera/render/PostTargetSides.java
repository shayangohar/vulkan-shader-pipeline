package net.chimera.render;

import net.chimera.shaderpack.PackFlipPlan;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PostTargetPlan;

import java.util.Comparator;
import java.util.List;

/**
 * Which image holds each colour target's main and alt side through a frame, following the load-time
 * flip plan (PackFlipPlan) and nothing else. Iris fixes every pass's sides when it builds its
 * renderers, and copies each flipped, uncleared target back into main at frame end, so every frame
 * starts unflipped. Side 0 is main, side 1 alt. Pure Java: PackPostTargets carries out the copies
 * and clears this asks for, and the corpus tests drive it against an Iris model with no device.
 *
 * <p>colortex0 has a third image. The world draws into the HDR image, which stands in for the
 * colortex0 side the world's state names: Iris's gbuffers write the side their flip state reads.
 * Before the world resumes, the HDR image is loaded with that side. Before a pass writes the side
 * the HDR image stands in for, or needs it as a graph image (a mip chain, a store), the HDR image's
 * content is written back to it.
 */
final class PostTargetSides {
    /** An image: a target's graph side, or the HDR image (target 0, side {@link #HDR}). */
    record Image(int target, int side) {
        boolean hdr() {
            return side == HDR;
        }
    }

    /** The copies and clears a side change needs, recorded in order. */
    interface Ops {
        void copy(Image from, Image to);

        void clear(Image image);
    }

    static final int HDR = 2;
    private static final int TARGET_COUNT = PostTargetPlan.MAX_TARGET + 1;

    private final PackFlipPlan plan;
    private final int allocated;
    private final int cleared;
    private final List<PackFlipPlan.Step> steps;
    /** The flipped set now: a target in it is read from alt. */
    private int state;
    /** The next plan step to run or pass through. */
    private int cursor;
    /** The colortex0 side the HDR image stands in for, or -1 when it holds neither. */
    private int hdrSide = -1;
    /** Whether the HDR image may hold content its side's graph image lacks. */
    private boolean hdrNewer;
    private boolean fullClearDue = true;

    /**
     * @param allocated the targets the session made images for, as a mask
     * @param cleared   the targets cleared every frame (colortexNClear, default true), as a mask
     */
    PostTargetSides(PackFlipPlan plan, int allocated, int cleared) {
        this.plan = plan == null ? PackFlipPlan.empty() : plan;
        this.allocated = allocated;
        this.cleared = cleared;
        this.steps = this.plan.steps();
    }

    /** Whether a target has an alt image: the plan flips or writes it. */
    boolean sided(int target) {
        return allocated(target) && plan.sided(target);
    }

    boolean allocated(int target) {
        return target >= 0 && target < TARGET_COUNT && (allocated & (1 << target)) != 0;
    }

    boolean clearedEachFrame(int target) {
        return allocated(target) && (cleared & (1 << target)) != 0;
    }

    /** Whether the next frame clears every side of every target, as Iris does on its first frame. */
    boolean fullClearDue() {
        return fullClearDue;
    }

    /**
     * Opens a frame. The first after the images were made clears every side of every target
     * (Iris's full clear). Later ones copy each target the last frame left flipped and that is not
     * cleared from alt back into main (FinalPassRenderer's swap passes), then clear both sides of
     * each cleared target (ClearPassCreator). The HDR image then stands in for main: the caller
     * clears it with colortex0's colour when colortex0 is cleared, and this loads it from main when
     * not. The world draws into it from here on, so it counts as newer.
     */
    void beginFrame(Ops ops) {
        boolean full = fullClearDue;
        fullClearDue = false;
        if (!full) {
            if (!clearedEachFrame(0) && hdrSide >= 0) writeBackHdr(ops, hdrSide);
            for (int target = 0; target < TARGET_COUNT; target++) {
                if (sided(target) && !clearedEachFrame(target) && PackFlipPlan.flipped(state, target)) {
                    ops.copy(new Image(target, 1), new Image(target, 0));
                }
            }
        }
        for (int target = 0; target < TARGET_COUNT; target++) {
            if (!allocated(target) || !full && !clearedEachFrame(target)) continue;
            ops.clear(new Image(target, 0));
            if (sided(target)) ops.clear(new Image(target, 1));
        }
        state = 0;
        cursor = 0;
        if (allocated(0) && !clearedEachFrame(0)) ops.copy(new Image(0, 0), new Image(0, HDR));
        hdrSide = 0;
        hdrNewer = true;
    }

    int state() {
        return state;
    }

    /** The side {@code target} is read from now; the world also writes it there. */
    int readSide(int target) {
        return sided(target) && PackFlipPlan.flipped(state, target) ? 1 : 0;
    }

    /** The image a sampler reads {@code target} from now: for colortex0, the HDR image while it is newer. */
    Image read(int target) {
        int side = readSide(target);
        if (target == 0 && hdrSide == side && hdrNewer) return new Image(0, HDR);
        return new Image(target, side);
    }

    /**
     * The graph image of {@code target}'s read side, holding its newest content: for a mip chain,
     * or a store through colorimgN, which binds the read side (IrisImages). A store leaves the HDR
     * image behind its side.
     */
    Image readGraph(int target, boolean store, Ops ops) {
        int side = readSide(target);
        if (target == 0 && hdrSide == side) {
            writeBackHdr(ops, side);
            if (store) hdrSide = -1;
        }
        return new Image(target, side);
    }

    /**
     * Opens {@code window}'s group: every earlier step that did not run passes through, then the
     * state is the group's as it opens, after its pre-flips.
     */
    void openWindow(PackFrameSchedulePlan.PostWindow window, Ops ops) {
        passThroughBefore(window, ops);
        state = plan.open(window);
    }

    /**
     * Brings the frame up to {@code pass}, a step or a compute-only pass: each earlier step that did
     * not run passes its input through, so the sides stay the plan's. Returns the step, or null
     * when {@code pass} is none.
     */
    PackFlipPlan.Step advanceTo(String pass, Ops ops) {
        Comparator<String> order = PostTargetPlan.programComparator();
        while (cursor < steps.size() && order.compare(steps.get(cursor).program(), pass) < 0) {
            passThrough(steps.get(cursor), ops);
        }
        if (cursor < steps.size() && steps.get(cursor).program().equals(pass)) {
            PackFlipPlan.Step step = steps.get(cursor);
            state = step.before();
            return step;
        }
        return null;
    }

    /**
     * The image a running step writes draw buffer {@code target} to: the side it does not read. A
     * write over the side the HDR image stands in for takes the HDR image's content first, as the
     * base a blend or a discard leaves, and leaves the HDR image behind.
     */
    Image write(PackFlipPlan.Step step, int target, Ops ops) {
        int side = sided(target) ? step.writeSide(target) : 0;
        if (target == 0 && hdrSide == side) {
            writeBackHdr(ops, side);
            hdrSide = -1;
        }
        return new Image(target, side);
    }

    /** {@code step} ran: its flips take effect. */
    void finish(PackFlipPlan.Step step) {
        state = step.after();
        cursor = Math.max(cursor, steps.indexOf(step) + 1);
    }

    /**
     * A step that did not run passes each draw buffer's read side through to its write side, as an
     * identity pass would, then flips as the plan says: later passes read the sides Iris's do, and
     * each target keeps the last content written to it.
     */
    private void passThrough(PackFlipPlan.Step step, Ops ops) {
        state = step.before();
        for (int target : step.drawBuffers()) {
            if (!sided(target)) continue;
            Image from = read(target);
            Image to = write(step, target, ops);
            if (!from.equals(to)) ops.copy(from, to);
        }
        finish(step);
    }

    private void passThroughBefore(PackFrameSchedulePlan.PostWindow window, Ops ops) {
        while (cursor < steps.size() && steps.get(cursor).window().ordinal() < window.ordinal()) {
            passThrough(steps.get(cursor), ops);
        }
    }

    /**
     * The world draws again after {@code window}'s group: BEGIN's for the shadow pass, PREPARE's
     * for the opaque world, EARLY's for the translucents. The group's remaining steps pass through,
     * the state is the one Iris's gbuffers read and write, and the HDR image takes colortex0's side.
     */
    void enterWorld(PackFrameSchedulePlan.PostWindow window, Ops ops) {
        passThroughBefore(next(window), ops);
        state = plan.close(window);
        int side = readSide(0);
        if (hdrSide != side) {
            if (hdrSide >= 0) writeBackHdr(ops, hdrSide);
            ops.copy(new Image(0, side), new Image(0, HDR));
            hdrSide = side;
        }
        hdrNewer = true;
    }

    /** The composite group is over: final reads the plan's final state, which the next frame copies back. */
    void enterFinal(Ops ops) {
        passThroughBefore(PackFrameSchedulePlan.PostWindow.FINAL, ops);
        state = plan.finalState();
    }

    private static PackFrameSchedulePlan.PostWindow next(PackFrameSchedulePlan.PostWindow window) {
        PackFrameSchedulePlan.PostWindow[] windows = PackFrameSchedulePlan.PostWindow.values();
        return windows[Math.min(window.ordinal() + 1, windows.length - 1)];
    }

    /** Writes the HDR image's content back to colortex0's graph {@code side}, when it stands in for that side. */
    private void writeBackHdr(Ops ops, int side) {
        if (hdrSide == side && hdrNewer) {
            ops.copy(new Image(0, HDR), new Image(0, side));
            hdrNewer = false;
        }
    }
}
