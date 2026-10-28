package net.chimera.render.shader;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Complementary's hand binds one fragment source in four vertex layouts. A load compiles that
 * stage once; a different kind or pack treatment of the same text is another module; the load's
 * end reports the counts and lets the modules go.
 */
final class CompiledStagesTest {
    @Test
    void compilesEachStageOncePerLoad() {
        CompiledStages.endLoad();
        AtomicInteger compiles = new AtomicInteger();
        byte[] first = CompiledStages.module(1, true, "void main() {}", () -> compiled(compiles, 7));
        byte[] again = CompiledStages.module(1, true, "void main() {}", () -> compiled(compiles, 9));
        assertArrayEquals(first, again);
        assertEquals(1, compiles.get(), "the same stage was compiled twice in one load");

        CompiledStages.module(0, true, "void main() {}", () -> compiled(compiles, 1));
        CompiledStages.module(1, false, "void main() {}", () -> compiled(compiles, 2));
        assertEquals(3, compiles.get(), "another kind or pack treatment is another module");
        assertEquals("3 compiled, 1 reused", CompiledStages.endLoad());

        CompiledStages.module(1, true, "void main() {}", () -> compiled(compiles, 7));
        assertEquals(4, compiles.get(), "a new load kept the last load's modules");
        CompiledStages.endLoad();
    }

    private static byte[] compiled(AtomicInteger compiles, int marker) {
        compiles.incrementAndGet();
        return new byte[] {(byte) marker};
    }
}
