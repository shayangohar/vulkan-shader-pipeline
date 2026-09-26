package net.chimera.mixin;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.chimera.render.EntityTransformBinding;
import net.chimera.render.shader.ChimeraEntityBridge;

/**
 * Pure checks for the guarded family host-transform policy and its admission
 * seam: which route one host slice takes, when a batch may widen the host
 * vertex format at all, and that a widened batch is never routed back to the
 * host pipeline.
 */
public final class M87EntityTransformBindingHarness {
    private M87EntityTransformBindingHarness() {}

    public static void main(String[] args) {
        assertTrue(!EntityTransformBinding.class.getPackageName().startsWith("net.chimera.mixin"),
                "runtime helper must not be placed in the package owned by chimera.mixins.json");
        int dynamicTransformsBytes = 160;
        int projectionBytes = 64;

        assertTrue(EntityTransformBinding.validUniformRange(
                256, dynamicTransformsBytes, 4096, dynamicTransformsBytes),
                "valid DynamicTransforms range was rejected");
        assertTrue(EntityTransformBinding.validUniformRange(
                512, projectionBytes, 4096, projectionBytes),
                "valid Projection range was rejected");
        assertTrue(!EntityTransformBinding.validUniformRange(
                256, dynamicTransformsBytes - 1L, 4096, dynamicTransformsBytes),
                "short DynamicTransforms slice was accepted");
        assertTrue(!EntityTransformBinding.validUniformRange(
                4032, dynamicTransformsBytes, 4096, dynamicTransformsBytes),
                "out-of-bounds DynamicTransforms slice was accepted");
        assertTrue(!EntityTransformBinding.validUniformRange(
                -1, projectionBytes, 4096, projectionBytes),
                "negative UBO offset was accepted");
        assertTrue(!EntityTransformBinding.validUniformRange(
                0, projectionBytes, 4096, 0),
                "zero-size UBO contract was accepted");

        // A guarded family batch widened its vertex format, so it must run the
        // pack pipeline: an absent or unusable source abandons the draw instead
        // of binding VulkanMod's global buffer, which carries this shader's own
        // uniform suppliers and would move the object.
        assertEquals(EntityTransformBinding.HostTransformRoute.BIND_SLICE,
                EntityTransformBinding.classifyHostTransform(true, true, true),
                "valid per-draw slice was not bound");
        assertEquals(EntityTransformBinding.HostTransformRoute.UNAVAILABLE,
                EntityTransformBinding.classifyHostTransform(true, true, false),
                "unusable per-draw slice did not abandon the draw");
        assertEquals(EntityTransformBinding.HostTransformRoute.UNAVAILABLE,
                EntityTransformBinding.classifyHostTransform(true, false, true),
                "absent per-draw slice did not abandon the draw");
        assertEquals(EntityTransformBinding.HostTransformRoute.UNAVAILABLE,
                EntityTransformBinding.classifyHostTransform(true, false, false),
                "absent and unusable per-draw slice did not abandon the draw");
        // A lane that keeps the host vertex format behaves like the host
        // pipeline: bind the slice when it can serve the block, otherwise keep
        // the global buffer.
        assertEquals(EntityTransformBinding.HostTransformRoute.BIND_SLICE,
                EntityTransformBinding.classifyHostTransform(false, true, true),
                "host-format lane did not bind a valid slice");
        assertEquals(EntityTransformBinding.HostTransformRoute.USE_GLOBAL,
                EntityTransformBinding.classifyHostTransform(false, true, false),
                "host-format lane did not keep the global buffer");
        assertEquals(EntityTransformBinding.HostTransformRoute.USE_GLOBAL,
                EntityTransformBinding.classifyHostTransform(false, false, true),
                "host-format lane did not keep the global buffer without a slice");

        // Admission: a batch that widens the host format needs both host
        // transform blocks, or it stays on the host format from the outset.
        assertTrue(EntityTransformBinding.canServeHostTransforms(true, true, true),
                "widened batch was rejected with both transform blocks declared");
        assertTrue(!EntityTransformBinding.canServeHostTransforms(true, true, false),
                "widened batch was admitted without a Projection block");
        assertTrue(!EntityTransformBinding.canServeHostTransforms(true, false, true),
                "widened batch was admitted without a DynamicTransforms block");
        assertTrue(EntityTransformBinding.canServeHostTransforms(false, false, false),
                "host-format lane needed transform blocks it never binds");

        // Seam: with no admitted family the host format is untouched, and no
        // family batch is admitted without an installed pack pipeline, so the
        // mesh keeps its 36-byte host layout and the host pipeline draws it.
        assertTrue(ChimeraEntityBridge.extendedFormat(DefaultVertexFormat.NEW_ENTITY)
                        == DefaultVertexFormat.NEW_ENTITY,
                "host format widened outside an admitted family draw");
        assertTrue(!ChimeraEntityBridge.shouldExtendEntityFormat(DefaultVertexFormat.NEW_ENTITY),
                "host format marked for widening outside an admitted family draw");
        assertTrue(!ChimeraEntityBridge.beginDraw(ChimeraEntityBridge.Family.ENTITY),
                "entity batch admitted without an installed pack pipeline");
        assertTrue(!ChimeraEntityBridge.requiresExtendedVertexFormat(),
                "widened draw reported without an admitted family");

        System.out.println("[chimera] M8.7 entity transform binding conformance: PASS");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
