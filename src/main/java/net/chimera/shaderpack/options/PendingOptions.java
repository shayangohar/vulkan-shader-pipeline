package net.chimera.shaderpack.options;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Option changes the settings screen has made that no pack load has applied yet, kept as Iris keeps
 * them: one queue for the whole game, emptied by the next load of a pack, so any reload (Apply, the
 * reload key) applies them. A reset request makes that load ignore the queue and the option file
 * alike, so the pack comes back with every option at its default.
 */
public final class PendingOptions {
    /** What a load takes from the queue. */
    public record Drained(Map<String, String> values, boolean reset) {
        public static final Drained NONE = new Drained(Map.of(), false);

        public Drained {
            values = Map.copyOf(values);
        }
    }

    private static final Map<String, String> QUEUE = new LinkedHashMap<>();
    private static boolean reset;

    private PendingOptions() {}

    /** Queues one option's value as option-file text. */
    public static synchronized void put(String name, String value) {
        QUEUE.put(name, value);
    }

    public static synchronized void putAll(Map<String, String> values) {
        QUEUE.putAll(values);
    }

    /** A copy of the queue. */
    public static synchronized Map<String, String> view() {
        return Map.copyOf(QUEUE);
    }

    public static synchronized boolean isEmpty() {
        return QUEUE.isEmpty();
    }

    /** Drops every queued value: Cancel, or applying a different pack. */
    public static synchronized void clear() {
        QUEUE.clear();
    }

    public static synchronized void requestReset() {
        reset = true;
    }

    public static synchronized boolean resetRequested() {
        return reset;
    }

    /** Takes the queue and the reset request for a load, leaving both empty. */
    public static synchronized Drained drain() {
        Drained drained = new Drained(QUEUE, reset);
        QUEUE.clear();
        reset = false;
        return drained;
    }
}
