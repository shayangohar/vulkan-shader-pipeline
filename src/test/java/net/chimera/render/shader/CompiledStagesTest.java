package net.chimera.render.shader;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Complementary's hand binds one fragment source in four vertex layouts, and a dimension visited
 * again compiles the same sources again. Each is compiled once per session; a different kind or
 * pack treatment of the same text is another module; past its byte budget the store lets the
 * least recently used modules go.
 */
final class CompiledStagesTest {
    @Test
    void compilesEachStageOncePerSession() {
        CompiledStages stages = new CompiledStages(1024);
        AtomicInteger compiles = new AtomicInteger();
        byte[] first = stages.moduleOf(1, true, "void main() {}", () -> compiled(compiles, 7, 4));
        byte[] again = stages.moduleOf(1, true, "void main() {}", () -> compiled(compiles, 9, 4));
        assertArrayEquals(first, again);
        assertEquals(1, compiles.get(), "the same stage was compiled twice");

        stages.moduleOf(0, true, "void main() {}", () -> compiled(compiles, 1, 4));
        stages.moduleOf(1, false, "void main() {}", () -> compiled(compiles, 2, 4));
        assertEquals(3, compiles.get(), "another kind or pack treatment is another module");
        assertEquals("3 compiled, 1 reused; the session keeps 3 modules, 0.0 MB", stages.report());

        stages.moduleOf(1, true, "void main() {}", () -> compiled(compiles, 7, 4));
        assertEquals(3, compiles.get(), "the next load compiled a stage the session already has");
    }

    @Test
    void dropsTheLeastRecentlyUsedPastItsBudget() {
        CompiledStages stages = new CompiledStages(10);
        AtomicInteger compiles = new AtomicInteger();
        stages.moduleOf(1, true, "a", () -> compiled(compiles, 1, 4));
        stages.moduleOf(1, true, "b", () -> compiled(compiles, 2, 4));
        stages.moduleOf(1, true, "a", () -> compiled(compiles, 1, 4));
        stages.moduleOf(1, true, "c", () -> compiled(compiles, 3, 4));
        assertEquals(3, compiles.get());
        stages.moduleOf(1, true, "a", () -> compiled(compiles, 1, 4));
        assertEquals(3, compiles.get(), "the module used most recently was dropped");
        stages.moduleOf(1, true, "b", () -> compiled(compiles, 2, 4));
        assertEquals(4, compiles.get(), "the least recently used module was kept past the budget");
    }

    private static byte[] compiled(AtomicInteger compiles, int marker, int length) {
        compiles.incrementAndGet();
        byte[] module = new byte[length];
        module[0] = (byte) marker;
        return module;
    }
}
