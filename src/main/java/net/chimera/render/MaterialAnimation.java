package net.chimera.render;

import java.util.List;

/**
 * One animated material map in an atlas slot: its frame schedule, each
 * frame's logical mip chain, and a ticker that mirrors Minecraft 1.21.11's
 * {@code SpriteContents.AnimationState}, so the map runs in lockstep with
 * the atlas it belongs to.
 *
 * <p>Game semantics, copied rather than approximated: the first schedule
 * entry shows first; every atlas tick adds one sub-frame; reaching the
 * entry's time moves to the next entry (wrapping) and restarts the count.
 * A non-interpolated map redraws only when the frame index changes. An
 * interpolated map redraws every tick, mixing the current and next frame on
 * all four channels by {@code subFrame / time} truncated to thousandths,
 * each mip level from that frame's own level ({@code animate_sprite} and
 * {@code animate_sprite_interpolate}). Pure CPU state: no GPU, no natives.</p>
 */
final class MaterialAnimation {
    /** One schedule entry: which frame of the strip to show, and for how many ticks. */
    record Frame(int index, int time) {}

    private final List<Frame> frames;
    private final boolean interpolate;
    /** Logical pixels by strip frame index, then mip level. */
    private final int[][][] frameLevels;
    private final int width;
    private final int height;
    private int frame;
    private int subFrame;

    MaterialAnimation(List<Frame> frames, boolean interpolate, int[][][] frameLevels, int width, int height) {
        if (frames.size() < 2) {
            throw new IllegalArgumentException("MATERIAL_MAP_ANIMATION:needs two frames");
        }
        for (Frame entry : frames) {
            if (entry.index() < 0 || entry.index() >= frameLevels.length || entry.time() <= 0) {
                throw new IllegalArgumentException("MATERIAL_MAP_ANIMATION:frame " + entry);
            }
        }
        this.frames = List.copyOf(frames);
        this.interpolate = interpolate;
        this.frameLevels = frameLevels;
        this.width = width;
        this.height = height;
    }

    /**
     * Advances one atlas tick. Returns true when the slot must be redrawn:
     * the frame index changed, or the map interpolates between two different
     * frames.
     */
    boolean tick() {
        subFrame++;
        boolean changed = false;
        Frame current = frames.get(frame);
        if (subFrame >= current.time()) {
            int previous = current.index();
            frame = (frame + 1) % frames.size();
            subFrame = 0;
            changed = previous != frames.get(frame).index();
        }
        return changed || (interpolate && currentIndex() != nextIndex());
    }

    /** The logical pixels the slot shows now at {@code level}. */
    int[] level(int level) {
        int[] current = frameLevels[currentIndex()][level];
        if (!interpolate || currentIndex() == nextIndex()) {
            return current;
        }
        int[] next = frameLevels[nextIndex()][level];
        float progress = (int) ((float) subFrame / (float) frames.get(frame).time() * 1000.0f) / 1000.0f;
        int[] mixed = new int[current.length];
        for (int i = 0; i < current.length; i++) {
            mixed[i] = MaterialMapPixels.pack(
                    mix(MaterialMapPixels.alpha(current[i]), MaterialMapPixels.alpha(next[i]), progress),
                    mix(MaterialMapPixels.blue(current[i]), MaterialMapPixels.blue(next[i]), progress),
                    mix(MaterialMapPixels.green(current[i]), MaterialMapPixels.green(next[i]), progress),
                    mix(MaterialMapPixels.red(current[i]), MaterialMapPixels.red(next[i]), progress));
        }
        return mixed;
    }

    /** GLSL mix on normalized channels, stored to UNORM8 with round-to-nearest. */
    private static int mix(int current, int next, float progress) {
        float value = current / 255.0f * (1.0f - progress) + next / 255.0f * progress;
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }

    int levels() {
        return frameLevels[0].length;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int currentIndex() {
        return frames.get(frame).index();
    }

    private int nextIndex() {
        return frames.get((frame + 1) % frames.size()).index();
    }
}
