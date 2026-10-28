package net.chimera.render.shader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The SPIR-V of the stages a pack load compiled, by shader kind, pack treatment and source. A
 * program drawn in several vertex layouts binds the same fragment source in each (the hand in four,
 * an entity family in its lanes), and compiling it again only gives the same module again.
 * Complementary compiled its hand fragment stage four times per load. A load ends with
 * {@link #endLoad}, which empties the store; between loads it holds no more than
 * {@link #RECENT} modules.
 */
public final class CompiledStages {
    private static final int RECENT = 32;
    private static final Map<Key, byte[]> MODULES = new LinkedHashMap<>(RECENT, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, byte[]> eldest) {
            return size() > RECENT;
        }
    };
    private static int compiled;
    private static int reused;

    private record Key(int kind, boolean packModule, String source) {}

    private CompiledStages() {}

    /** The module of this stage, compiled by {@code compile} unless it was compiled before. */
    static byte[] module(int kind, boolean packModule, String source, Supplier<byte[]> compile) {
        Key key = new Key(kind, packModule, source);
        synchronized (MODULES) {
            byte[] known = MODULES.get(key);
            if (known != null) {
                reused++;
                return known;
            }
        }
        byte[] module = compile.get();
        synchronized (MODULES) {
            MODULES.put(key, module);
            compiled++;
        }
        return module;
    }

    /** Ends a load: how many stages it compiled and how many it reused. The store is emptied. */
    public static String endLoad() {
        synchronized (MODULES) {
            String counts = compiled + " compiled, " + reused + " reused";
            MODULES.clear();
            compiled = 0;
            reused = 0;
            return counts;
        }
    }
}
