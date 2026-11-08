package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pack load shares its programs among PackWorkers. The results must be what a loop over the
 * programs returns, on any processor count: in input order, with the first failure in input order
 * rethrown, and on one or two processors without leaving the calling thread.
 */
final class PackWorkersTest {
    @Test
    void keepsInputOrder() {
        List<Integer> inputs = IntStream.range(0, 200).boxed().toList();
        Set<String> threads = ConcurrentHashMap.newKeySet();
        List<Integer> squares = PackWorkers.map(inputs, value -> {
            threads.add(Thread.currentThread().getName());
            return value * value;
        }, 4);
        assertEquals(inputs.stream().map(value -> value * value).toList(), squares);
        assertTrue(threads.stream().anyMatch(name -> name.startsWith("Chimera pack worker")),
                "four workers ran nothing on the pool: " + threads);
    }

    @Test
    void rethrowsTheFirstFailureInInputOrder() {
        AtomicInteger worked = new AtomicInteger();
        List<Integer> inputs = IntStream.range(0, 50).boxed().toList();
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> PackWorkers.map(inputs, value -> {
                    worked.incrementAndGet();
                    if (value == 7 || value == 30) throw new IllegalArgumentException("input " + value);
                    return value;
                }, 4));
        assertEquals("input 7", thrown.getMessage());
        assertEquals(inputs.size(), worked.get(), "every input is worked before the failure is rethrown");
    }

    @Test
    void staysOnTheCallerWithoutWorkers() {
        Thread caller = Thread.currentThread();
        List<Boolean> onCaller = PackWorkers.map(List.of(1, 2, 3), value -> Thread.currentThread() == caller, 1);
        assertEquals(List.of(true, true, true), onCaller);
    }
}
