package net.chimera.render;

import java.util.Map;

/** Behavioral checks for the scoped image-plus-sampler binding state. */
public final class ChimeraTextureBindingStateHarness {
    private ChimeraTextureBindingStateHarness() {}

    public static void verify() {
        ChimeraTextureBindingState.BindingStore<Object> state = new ChimeraTextureBindingState.BindingStore<>();
        Object atlas = new Object();
        Object lightmap = new Object();
        Object overlay = new Object();
        Object entity = new Object();
        Object packTexture = new Object();

        state.setTerrainSampler(101L);
        ChimeraTextureBindingState.BindingStore.Entry<Object> terrain =
                state.bindTerrainAtlas(atlas, 1L);
        assertSame(atlas, terrain.image(), "terrain image identity");
        assertEquals(101L, terrain.sampler(), "authoritative chunk sampler");

        state.bindExact(2, lightmap, 202L);
        state.bindExact(1, overlay, 303L);
        state.bindExact(9, entity, 404L);
        assertEquals(101L, state.binding(0).sampler(),
                "later lightmap, overlay, and entity bindings replaced terrain sampler");

        state.bind(14, packTexture, 505L);
        assertSame(packTexture, state.binding(14).image(), "pack resource slot image");
        assertEquals(101L, state.binding(0).sampler(),
                "pack resource binding changed terrain sampler");

        Map<Integer, ChimeraTextureBindingState.BindingStore.Entry<Object>> outer = state.snapshot();
        state.bindExact(0, entity, 606L);
        Map<Integer, ChimeraTextureBindingState.BindingStore.Entry<Object>> inner = state.snapshot();
        state.bindExact(0, packTexture, 707L);
        state.clear(2);
        state.restore(inner);
        assertSame(entity, state.binding(0).image(), "nested scope restore image");
        assertEquals(606L, state.binding(0).sampler(), "nested scope restore sampler");
        assertTrue(state.binding(2) != null, "nested scope restore lost lightmap binding");
        state.restore(outer);
        assertSame(atlas, state.binding(0).image(), "outer scope restore image");
        assertEquals(101L, state.binding(0).sampler(), "outer scope restore sampler");

        state.clearTerrainSampler();
        ChimeraTextureBindingState.BindingStore.Entry<Object> fallback =
                state.bindTerrainAtlas(atlas, 808L);
        assertEquals(808L, fallback.sampler(),
                "missing authoritative sampler did not fail closed to image sampler");

        state.reset();
        assertTrue(state.binding(0) == null && state.binding(14) == null,
                "cleanup left pack bindings active");

        // Terrain starts with only a selector snapshot. The override must be
        // absent until VulkanMod has synchronized the actual atlas image.
        state.setTerrainSampler(909L);
        Object actualAtlas = new Object();
        assertTrue(state.binding(0) == null,
                "terrain binding was installed before selector synchronization");
        ChimeraTextureBindingState.BindingStore.Entry<Object> actualTerrain =
                state.bindTerrainAtlas(actualAtlas, 808L);
        assertSame(actualAtlas, actualTerrain.image(),
                "selector-synchronized atlas was not paired");
        assertEquals(909L, actualTerrain.sampler(),
                "selector-synchronized atlas did not use the captured sampler");
        state.clear(0);
        assertTrue(state.binding(0) == null,
                "terrain override survived the terrain window return");

        ChimeraMainPass.HandBoundaryPolicy handPolicy = ChimeraMainPass.handBoundaryPolicy();
        assertTrue(handPolicy.loadColor() && handPolicy.clearDepth(),
                "hand continuation must load color and clear depth");
        System.out.println("[chimera] M8.6a sampler ownership behavior: PASS");
    }

    private static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
