package net.chimera.render.shader;

import net.minecraft.client.renderer.RenderStateShard;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris's batched-entity transparency (MixinCompositeRenderType), which separateEntityDraws splits the
 * entity batches by: the opaque kinds before the deferred passes, the rest after. Held batches draw
 * in Iris's order (GraphTranslucencyRenderOrderManager).
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
     * A level source holds every batch, vanilla's own flushes included, until Iris's translucent
     * boundary (FullyBufferedMultiBufferSource ignores endBatch(type)). There it draws everything, or
     * with separateEntityDraws the opaque kinds; after the deferred passes, everything.
     */
    @Test
    void levelSourcesHoldTheirBatchesToTheBoundary() {
        for (IrisTransparency kind : IrisTransparency.values()) {
            for (boolean separates : new boolean[] {false, true}) {
                assertFalse(FamilyBufferSource.levelDraws(kind, false, false, separates),
                        kind + " before the boundary");
                assertEquals(!separates || kind.drawsBeforeDeferred(),
                        FamilyBufferSource.levelDraws(kind, true, false, separates), kind + " at the boundary");
                assertTrue(FamilyBufferSource.levelDraws(kind, true, true, separates), kind + " after deferred");
            }
        }
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

    /**
     * A pig writes its model, then its lead, in one entity's render; the chest in front of it is a
     * block entity, written after every entity. Iris draws the chest first, then the pig, then the
     * lead, which fails the depth test behind the chest: gbuffers_basic's lead writes targets the
     * chest's program does not, so drawn first it would show through.
     */
    @Test
    void aLeadDrawsAfterItsEntityAndTheBlockEntitiesInFrontOfIt() {
        IrisBatchOrder<String> order = new IrisBatchOrder<>();
        assertTrue(order.startGroup());
        order.use("pig", IrisTransparency.OPAQUE);
        order.use("lead", IrisTransparency.OPAQUE);
        order.endGroup();
        order.use("chest", IrisTransparency.OPAQUE);
        order.use("sign", IrisTransparency.OPAQUE);
        assertEquals(List.of("sign", "chest", "pig", "lead"), sorted(order, "lead", "chest", "pig", "sign"));
    }

    /** Kinds draw in Iris's order whatever order they were written in. */
    @Test
    void anOpaqueBatchDrawsBeforeADecal() {
        IrisBatchOrder<String> order = new IrisBatchOrder<>();
        order.use("glint", IrisTransparency.DECAL);
        order.use("zombie", IrisTransparency.OPAQUE);
        order.use("outline", IrisTransparency.LINES);
        assertEquals(List.of("zombie", "glint", "outline"), sorted(order, "outline", "glint", "zombie"));
    }

    /** A banner's group keeps its base before the patterns layered on it, and a nested group is not opened. */
    @Test
    void aBannerDrawsItsBaseFirst() {
        IrisBatchOrder<String> order = new IrisBatchOrder<>();
        assertTrue(order.startGroup());
        order.use("base", IrisTransparency.OPAQUE);
        order.use("patterns", IrisTransparency.OPAQUE);
        assertTrue(!order.startGroup(), "a group is open");
        order.endGroup();
        assertEquals(List.of("base", "patterns"), sorted(order, "patterns", "base"));
    }

    /** Two entities that write two types in opposite orders still rank both. */
    @Test
    void aCycleStillOrdersEveryType() {
        IrisBatchOrder<String> order = new IrisBatchOrder<>();
        order.startGroup();
        order.use("a", IrisTransparency.OPAQUE);
        order.use("b", IrisTransparency.OPAQUE);
        order.endGroup();
        order.startGroup();
        order.use("b", IrisTransparency.OPAQUE);
        order.use("a", IrisTransparency.OPAQUE);
        order.endGroup();
        assertEquals(2, order.ranks().size());
    }

    private static List<String> sorted(IrisBatchOrder<String> order, String... types) {
        Map<String, Integer> ranks = order.ranks();
        return List.of(types).stream().sorted(Comparator.comparingInt(ranks::get)).toList();
    }
}
