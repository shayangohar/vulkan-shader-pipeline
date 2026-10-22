package net.chimera.render.shader;

import net.minecraft.client.renderer.RenderStateShard;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris's batched-entity transparency (MixinCompositeRenderType), which separateEntityDraws splits the
 * entity batches by: the opaque kinds before the deferred passes, the rest after.
 */
final class IrisTransparencyTest {
    @Test
    void compositeStateDecidesTheKind() {
        assertEquals(IrisTransparency.OPAQUE, IrisTransparency.ofComposite("entity_solid",
                RenderStateShard.NO_TRANSPARENCY, RenderStateShard.LEQUAL_DEPTH_TEST));
        assertEquals(IrisTransparency.GENERAL_TRANSPARENT, IrisTransparency.ofComposite("entity_translucent",
                RenderStateShard.TRANSLUCENT_TRANSPARENCY, RenderStateShard.LEQUAL_DEPTH_TEST));
        assertEquals(IrisTransparency.DECAL, IrisTransparency.ofComposite("glint",
                RenderStateShard.GLINT_TRANSPARENCY, RenderStateShard.EQUAL_DEPTH_TEST));
        assertEquals(IrisTransparency.DECAL, IrisTransparency.ofComposite("crumbling",
                RenderStateShard.CRUMBLING_TRANSPARENCY, RenderStateShard.LEQUAL_DEPTH_TEST));
        assertEquals(IrisTransparency.WATER_MASK, IrisTransparency.ofComposite("water_mask",
                RenderStateShard.NO_TRANSPARENCY, RenderStateShard.LEQUAL_DEPTH_TEST));
        assertEquals(IrisTransparency.WATER_MASK, IrisTransparency.ofComposite("text_see_through",
                RenderStateShard.TRANSLUCENT_TRANSPARENCY, RenderStateShard.NO_DEPTH_TEST),
                "no depth test reads as the water mask, before the name");
        assertEquals(IrisTransparency.LINES, IrisTransparency.ofComposite("lines",
                RenderStateShard.TRANSLUCENT_TRANSPARENCY, RenderStateShard.LEQUAL_DEPTH_TEST));
    }

    /** Iris's MixinLevelRenderer ends exactly these three kinds at the translucent boundary. */
    @Test
    void separateEntityDrawsDrawsTheOpaqueKindsBeforeDeferred() {
        Set<IrisTransparency> before = EnumSet.noneOf(IrisTransparency.class);
        for (IrisTransparency kind : IrisTransparency.values()) {
            if (kind.drawsBeforeDeferred()) before.add(kind);
        }
        assertEquals(EnumSet.of(IrisTransparency.OPAQUE, IrisTransparency.OPAQUE_DECAL, IrisTransparency.WATER_MASK),
                before);
    }

    /** Iris draws its batches in TransparencyType order, which the family batches sort by. */
    @Test
    void batchesSortInIrisOrder() {
        assertEquals(List.of(IrisTransparency.OPAQUE, IrisTransparency.OPAQUE_DECAL,
                        IrisTransparency.GENERAL_TRANSPARENT, IrisTransparency.DECAL, IrisTransparency.WATER_MASK,
                        IrisTransparency.LINES),
                Arrays.stream(IrisTransparency.values()).sorted().toList());
    }

    /**
     * A level source may hold a batch across many flushes for its deferred passes; trimming idle
     * types must never free one that holds a started batch, however long ago it was drawn.
     */
    @Test
    void aHeldBatchIsNeverIdle() {
        assertFalse(FamilyBufferSource.idle(true, 1000, 0), "a started, held batch keeps its buffer");
        assertTrue(FamilyBufferSource.idle(false, 1000, 0), "an empty type unseen for long is freed");
        assertFalse(FamilyBufferSource.idle(false, 64, 0), "an empty type drawn recently stays");
    }
}
