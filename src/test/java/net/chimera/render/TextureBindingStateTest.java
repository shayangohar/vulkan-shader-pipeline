package net.chimera.render;

import net.vulkanmod.vulkan.texture.SamplerManager;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL14.GL_COMPARE_R_TO_TEXTURE;

/** Behavioral checks for the scoped image-plus-sampler binding state. */
final class TextureBindingStateTest {
    @Test
    void scopedBindings() {
        ChimeraTextureBindingState.BindingStore<Object> state = new ChimeraTextureBindingState.BindingStore<>();
        Object atlas = new Object();
        Object lightmap = new Object();
        Object entity = new Object();
        Object packTexture = new Object();

        state.bind(0, atlas, 101L);
        state.bind(2, lightmap, 202L);
        state.bind(14, packTexture, 505L);
        assertSame(atlas, state.binding(0).image(), "terrain image identity");
        assertEquals(101L, state.binding(0).sampler(), "terrain sampler");
        assertSame(packTexture, state.binding(14).image(), "pack resource slot image");
        assertEquals(101L, state.binding(0).sampler(), "pack resource binding changed terrain sampler");

        Map<Integer, ChimeraTextureBindingState.BindingStore.Entry<Object>> outer = state.snapshot();
        state.bind(0, entity, 606L);
        Map<Integer, ChimeraTextureBindingState.BindingStore.Entry<Object>> inner = state.snapshot();
        state.bind(0, packTexture, 707L);
        state.clear(2);
        state.restore(inner);
        assertSame(entity, state.binding(0).image(), "nested scope restore image");
        assertEquals(606L, state.binding(0).sampler(), "nested scope restore sampler");
        assertTrue(state.binding(2) != null, "nested scope restore lost lightmap binding");
        state.restore(outer);
        assertSame(atlas, state.binding(0).image(), "outer scope restore image");
        assertEquals(101L, state.binding(0).sampler(), "outer scope restore sampler");

        state.reset();
        assertTrue(state.binding(0) == null && state.binding(14) == null,
                "cleanup left pack bindings active");
    }

    @Test
    void handBoundary() {
        ChimeraMainPass.HandBoundaryPolicy handPolicy = ChimeraMainPass.handBoundaryPolicy();
        assertTrue(handPolicy.loadColor() && handPolicy.clearDepth(),
                "hand continuation must load color and clear depth");
    }

    @Test
    void shadowCompareSampler() {
        // The converted shadow image needs a compare-enabled sampler for
        // sampler2DShadow lookups, and only for those: a program that declares the
        // same texture as sampler2D keeps the image's non-compare sampler.
        assertTrue(PackShadowDepth.requiresCompareSampler("sampler2DShadow"),
                "sampler2DShadow did not request the compare sampler");
        assertTrue(!PackShadowDepth.requiresCompareSampler("sampler2D"),
                "plain sampler2D requested the compare sampler");
        assertTrue(!PackShadowDepth.requiresCompareSampler(null),
                "undeclared sampler type requested the compare sampler");
        SamplerManager.SamplerKey compare = ChimeraSamplers.depthCompareKey();
        assertEquals(GL_COMPARE_R_TO_TEXTURE, compare.compareMode(), "shadow sampler is not compare-enabled");
        assertEquals(GL_LEQUAL, compare.compareFunc(), "shadow sampler comparison operator");
        assertEquals(GL_CLAMP_TO_EDGE, compare.wrapS(), "shadow sampler wrap");
        assertEquals(GL_LINEAR, compare.minFilter(), "shadow sampler min filter");
    }

    @Test
    void depthViewOverride() {
        // A depth-only override is scoped to one image identity and cleared after
        // the conversion draw; the host image keeps its own view.
        ChimeraDepthViewOverride.bind(null, 4242L);
        assertEquals(4242L, ChimeraDepthViewOverride.viewFor(null),
                "bound depth view was not visible");
        ChimeraDepthViewOverride.clear();
        assertEquals(0L, ChimeraDepthViewOverride.viewFor(null),
                "cleared depth view was still visible");
    }
}
