package net.chimera.render;

import java.util.function.Supplier;

/**
 * A value this thread holds while it makes one thing, for a hook deep inside VulkanMod's or the
 * game's allocation to read: image creation (TargetFormatViews), image view creation
 * (IntensityTextureViews) and pipeline construction (GeometryStageModules) run shared code, and
 * only the allocation this thread opened is the one to change. The value is set for one call and
 * cleared however the call ends, so no other allocation, on this thread or another, sees it.
 */
public final class AllocationScope<T> {
    private final ThreadLocal<T> current = new ThreadLocal<>();

    /** Returns what {@code body} makes while {@code value} is current on this thread. */
    public <R> R with(T value, Supplier<R> body) {
        T outer = this.current.get();
        this.current.set(value);
        try {
            return body.get();
        } finally {
            if (outer == null) this.current.remove();
            else this.current.set(outer);
        }
    }

    /** Runs {@code body} while {@code value} is current on this thread. */
    public void run(T value, Runnable body) {
        with(value, () -> {
            body.run();
            return null;
        });
    }

    /** The value of the allocation this thread is making, or null outside one. */
    public T current() {
        return this.current.get();
    }
}
