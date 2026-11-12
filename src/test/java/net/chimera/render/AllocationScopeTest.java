package net.chimera.render;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The hooks inside VulkanMod's image, view and pipeline allocation read an AllocationScope to tell
 * the allocation this thread opened from every other one, so its value lasts exactly one call.
 */
final class AllocationScopeTest {
    @Test
    void aValueLastsOneCallOnOneThread() throws Exception {
        AllocationScope<String> scope = new AllocationScope<>();
        assertNull(scope.current(), "nothing outside a call");
        String seenElsewhere = scope.with("image", () ->
                CompletableFuture.supplyAsync(scope::current).join());
        assertNull(seenElsewhere, "another thread's allocation is not this one");
        assertEquals("image", scope.with("image", scope::current));
        assertNull(scope.current(), "cleared after the call");
    }

    @Test
    void aThrowingCallClearsItsValue() {
        AllocationScope<String> scope = new AllocationScope<>();
        assertThrows(IllegalStateException.class, () -> scope.run("image", () -> {
            throw new IllegalStateException("allocation failed");
        }));
        assertNull(scope.current(), "a failed allocation leaves nothing behind");
    }

    @Test
    void anInnerCallRestoresTheOuterValue() {
        AllocationScope<String> scope = new AllocationScope<>();
        scope.run("outer", () -> {
            assertEquals("inner", scope.with("inner", scope::current));
            assertEquals("outer", scope.current());
        });
        assertNull(scope.current());
    }
}
