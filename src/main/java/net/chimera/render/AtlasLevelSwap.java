package net.chimera.render;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;

/**
 * Swaps the block atlas's sprite levels between VulkanMod's and the alpha-weighted ones Iris setups
 * build (PackAtlasMipmaps). A sprite's VulkanMod levels are kept before any of its levels changes,
 * and a restore lets go of each sprite as it puts its levels back, so a swap that stops partway can
 * always be undone. A failed swap leaves the levels mixed and is not tried again until the wanted
 * levels change or {@link #retry} allows it: each try would fail and upload the atlas again.
 */
final class AtlasLevelSwap<K> {
    /** One sprite's mip levels as ABGR texels; level 0 is the sprite's own and never changes. */
    interface Levels {
        int count();

        int width(int level);

        int height(int level);

        int[] read(int level);

        void write(int level, int[] abgr);
    }

    private enum State { HOST, PACK, MIXED }

    /** VulkanMod's levels 1.. of each sprite whose levels may not be VulkanMod's, by level. */
    private final Map<K, int[][]> host = new IdentityHashMap<>();
    private State state = State.HOST;
    /** The wanted levels (pack or not) whose swap failed, not tried again; null when none failed. */
    private Boolean failedFor;

    /** The atlas was uploaded again: every sprite has VulkanMod's levels. */
    void reset() {
        host.clear();
        state = State.HOST;
        failedFor = null;
    }

    /** Lets a failed swap be tried once more, as at the next pack load. */
    void retry() {
        failedFor = null;
    }

    /** Whether a swap to the {@code pack} levels or VulkanMod's is due: they differ and no swap to them failed. */
    boolean due(boolean pack) {
        return state != (pack ? State.PACK : State.HOST) && !Boolean.valueOf(pack).equals(failedFor);
    }

    /**
     * Gives the sprites the {@code pack} levels or VulkanMod's, then runs {@code upload}; returns how
     * many sprites changed. A failure is rethrown with the levels recorded as mixed.
     */
    int swap(boolean pack, Iterable<K> sprites, Function<K, Levels> levelsOf, Runnable upload) {
        try {
            int changed = pack ? rewrite(sprites, levelsOf) : restore(levelsOf);
            upload.run();
            state = pack ? State.PACK : State.HOST;
            failedFor = null;
            return changed;
        } catch (RuntimeException failure) {
            state = State.MIXED;
            failedFor = pack;
            throw failure;
        }
    }

    /**
     * Rebuilds every mipmapped sprite's levels from its level 0. Each sprite's VulkanMod levels are
     * kept first; a sprite already kept, by a swap that stopped partway, keeps what it had.
     */
    private int rewrite(Iterable<K> sprites, Function<K, Levels> levelsOf) {
        int rewritten = 0;
        for (K sprite : sprites) {
            Levels levels = levelsOf.apply(sprite);
            if (levels == null) continue;
            host.computeIfAbsent(sprite, ignored -> hostLevels(levels));
            int[] above = levels.read(0);
            for (int level = 1; level < levels.count(); level++) {
                int[] below = AtlasMipBlend.reduce(above, levels.width(level - 1), levels.height(level - 1),
                        levels.width(level), levels.height(level));
                levels.write(level, below);
                above = below;
            }
            rewritten++;
        }
        return rewritten;
    }

    private static int[][] hostLevels(Levels levels) {
        int[][] kept = new int[levels.count()][];
        for (int level = 1; level < kept.length; level++) kept[level] = levels.read(level);
        return kept;
    }

    /** Puts VulkanMod's levels back into every kept sprite, letting go of each once it has them. */
    private int restore(Function<K, Levels> levelsOf) {
        int restored = 0;
        for (Iterator<Map.Entry<K, int[][]>> entries = host.entrySet().iterator(); entries.hasNext(); ) {
            Map.Entry<K, int[][]> entry = entries.next();
            Levels levels = levelsOf.apply(entry.getKey());
            int[][] kept = entry.getValue();
            for (int level = 1; level < kept.length; level++) levels.write(level, kept[level]);
            entries.remove();
            restored++;
        }
        return restored;
    }
}
