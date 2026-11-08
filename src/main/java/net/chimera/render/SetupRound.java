package net.chimera.render;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One round of Iris's setup computes, which run once the pipeline is made and after each resize
 * (IrisRenderingPipeline): each runs once, in order. One that skips a frame, as before a depth
 * snapshot exists, holds the rest of the round to a later frame; after {@link #TRIES} frames the
 * round goes on without it, and the next round tries it again.
 */
final class SetupRound {
    static final int TRIES = 8;

    private final Set<String> done = new HashSet<>();
    private boolean due;
    /** Frames the round's next compute has skipped. */
    private int tries;

    /** Starts a round, due when there is a setup compute to run. */
    void start(boolean any) {
        tries = 0;
        done.clear();
        due = any;
    }

    /** Whether a compute of this round has yet to run. */
    boolean due() {
        return due;
    }

    /**
     * Runs the round's next computes, in order, through {@code dispatch} (false: it skipped this
     * frame) until one skips or every one has run; the names this call gave up on.
     */
    List<String> advance(List<String> names, Predicate<String> dispatch) {
        List<String> abandoned = new ArrayList<>();
        for (String name : names) {
            if (done.contains(name)) continue;
            if (!dispatch.test(name)) {
                if (++tries < TRIES) return abandoned;
                abandoned.add(name);
            }
            done.add(name);
            tries = 0;
        }
        due = false;
        return abandoned;
    }
}
