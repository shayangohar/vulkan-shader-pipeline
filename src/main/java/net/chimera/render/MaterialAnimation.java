package net.chimera.render;

import java.util.List;

/**
 * One animated material map in an atlas slot: its frame schedule, each
 * frame's logical mip chain, and a ticker that mirrors Minecraft 1.21.1's
 * {@code SpriteContents.Ticker}, so the map runs in lockstep with the atlas
 * it belongs to.
 *
 * <p>Game semantics, copied rather than approximated: the first schedule
 * entry shows first; every atlas tick adds one sub-frame; reaching the
 * entry's time moves to the next entry (wrapping) and restarts the count.
 * A non-interpolated map redraws only when the frame index changes. An
 * interpolated map also redraws on every other tick while the next entry
 * shows a different frame. Every redraw writes every mip level, each from
 * that frame's own level. Pure CPU state: no GPU, no natives.</p>
 *
 * <p>Iris parity: Iris 1.21.1 runs with Sodium 0.8.13, whose
 * {@code SpriteContentsInterpolationMixin} replaces vanilla's interpolation:
 * {@code ColorMixer.mix} on the colour channels with the current frame's
 * weight {@code 1 - subFrame / time} as a byte, and the current frame's
 * alpha. Its "animate only visible textures" skips the upload of a sprite
 * off screen; a map off screen cannot be seen, so that is not mirrored.</p>
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
        // Sodium ColorU8.normalizedFloatToByte: the current frame's weight as a byte.
        int weight = (int) ((1.0f - (float) subFrame / (float) frames.get(frame).time()) * 255.0f) & 0xFF;
        int[] mixed = new int[current.length];
        for (int i = 0; i < current.length; i++) {
            mixed[i] = mix(current[i], next[i], weight) & 0x00FFFFFF | current[i] & 0xFF000000;
        }
        return mixed;
    }

    /** Sodium ColorMixer.mix: every channel weighted by {@code weight / 255}, rounded as Sodium rounds. */
    private static int mix(int current, int next, int weight) {
        long redBlue = (current & 0x00FF00FFL) * weight + (next & 0x00FF00FFL) * (255 - weight);
        long greenAlpha = (current & 0xFF00FF00L) * weight + (next & 0xFF00FF00L) * (255 - weight);
        long mixedRedBlue = (redBlue + 0x00FF00FFL) >>> 8 & 0x00FF00FFL;
        long mixedGreenAlpha = (greenAlpha + 0xFF00FF00L) >>> 8 & 0xFF00FF00L;
        return (int) (mixedRedBlue | mixedGreenAlpha);
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
