package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * The threads a pack load shares its independent work among: planning each program, compiling
 * each shader stage. There is one fewer worker than the machine has processors, so the render
 * thread, the integrated server and the chunk builders keep one, and at most {@link #MAX_WORKERS}.
 * With one or two processors the work stays on the calling thread. The pool starts on first use;
 * its threads are daemons and idle between loads.
 */
public final class PackWorkers {
    /** Past eight a load gains little: a few large programs set its length, and each worker holds parse trees. */
    static final int MAX_WORKERS = 8;
    private static final int WORKERS = workersFor(Runtime.getRuntime().availableProcessors());
    private static volatile ForkJoinPool pool;

    private PackWorkers() {}

    /** The workers for a machine of {@code processors}; below two the work stays serial. */
    static int workersFor(int processors) {
        return Math.max(1, Math.min(MAX_WORKERS, processors - 1));
    }

    /**
     * Applies {@code work} to each input, in parallel when there are workers to share it, and
     * returns the results in the inputs' order. Every input is worked; when any fails, the first
     * failure in input order is rethrown, as a loop over the inputs would throw it.
     */
    public static <T, R> List<R> map(List<T> inputs, Function<? super T, ? extends R> work) {
        return map(inputs, work, WORKERS);
    }

    static <T, R> List<R> map(List<T> inputs, Function<? super T, ? extends R> work, int workers) {
        if (workers < 2 || inputs.size() < 2) {
            List<R> results = new ArrayList<>(inputs.size());
            for (T input : inputs) results.add(work.apply(input));
            return results;
        }
        Object[] results = new Object[inputs.size()];
        Throwable[] failures = new Throwable[inputs.size()];
        pool(workers).invoke(ForkJoinTask.adapt(() -> IntStream.range(0, inputs.size()).parallel().forEach(index -> {
            try {
                results[index] = work.apply(inputs.get(index));
            } catch (Throwable failure) {
                failures[index] = failure;
            }
        })));
        for (Throwable failure : failures) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException(failure);
        }
        @SuppressWarnings("unchecked")
        List<R> ordered = new ArrayList<>((List<R>) Arrays.asList(results));
        return ordered;
    }

    /** The pool of {@code workers}; only tests ask for another size than the machine's. */
    private static ForkJoinPool pool(int workers) {
        ForkJoinPool current = pool;
        if (current != null && current.getParallelism() == workers) return current;
        synchronized (PackWorkers.class) {
            if (pool == null || pool.getParallelism() != workers) pool = newPool(workers);
            return pool;
        }
    }

    /** Daemon workers that resolve classes as the thread that started the pool does (the mod's loader). */
    private static ForkJoinPool newPool(int workers) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        AtomicInteger count = new AtomicInteger();
        return new ForkJoinPool(workers, owner -> {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(owner);
            thread.setName("Chimera pack worker " + count.incrementAndGet());
            thread.setContextClassLoader(loader);
            return thread;
        }, null, false);
    }
}
