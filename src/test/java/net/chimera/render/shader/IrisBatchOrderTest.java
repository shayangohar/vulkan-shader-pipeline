package net.chimera.render.shader;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order Iris draws a frame's held batches in (GraphTranslucencyRenderOrderManager). */
final class IrisBatchOrderTest {
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
