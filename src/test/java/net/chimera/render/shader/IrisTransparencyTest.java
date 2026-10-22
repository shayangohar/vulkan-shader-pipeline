package net.chimera.render.shader;

import net.minecraft.client.renderer.RenderStateShard;
import org.junit.jupiter.api.Test;

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

    @Test
    void separateEntityDrawsDrawsTheOpaqueKindsBeforeDeferred() {
        for (IrisTransparency kind : IrisTransparency.values()) {
            boolean before = kind == IrisTransparency.OPAQUE || kind == IrisTransparency.OPAQUE_DECAL
                    || kind == IrisTransparency.WATER_MASK;
            assertEquals(before, kind.drawsBeforeDeferred(), kind.name());
        }
        assertTrue(IrisTransparency.OPAQUE.drawsBeforeDeferred());
        assertFalse(IrisTransparency.GENERAL_TRANSPARENT.drawsBeforeDeferred());
    }
}
