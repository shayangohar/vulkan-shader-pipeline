package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shrimple's spider and enderman eyes drew black. The eyes render type sets no overlay, so the
 * overlay unit held VulkanMod's 1x1 white image while UV1 was vanilla's NO_OVERLAY (0, 10); the
 * fetch fell outside the image, NVIDIA read zero, and entityColor came out opaque black, which
 * Shrimple mixes over the eye texture. Iris's GL reads unbound texture 0 as (0, 0, 0, 1).
 */
final class EntityOverlayColorTest {
    private static final String FRAGMENT =
            "#version 120\nuniform vec4 entityColor;\nvoid main() { gl_FragColor = entityColor; }\n";

    @Test
    void aFetchOutsideTheBoundOverlayReadsGlsEmptyTexel() {
        String fragment = EntityOverlayColor.inject(FRAGMENT);
        assertTrue(fragment.contains("vec4 chimeraOverlayTexel = all(lessThan(uvec2(chimeraOverlayUV), "
                + "uvec2(textureSize(chimeraOverlay, 0)))) ? texelFetch(chimeraOverlay, chimeraOverlayUV, 0)"
                + " : vec4(0.0, 0.0, 0.0, 1.0);"), fragment);
        assertTrue(fragment.contains("entityColor = vec4(chimeraOverlayTexel.rgb, 1.0 - chimeraOverlayTexel.a);"),
                "the empty texel leaves entityColor's alpha 0, so nothing is mixed over the eyes");
    }

    /** A shadow geometry stage reads the primitive's first vertex the same way. */
    @Test
    void aGeometryStageFetchIsGuardedToo() {
        String geometry = "#version 150\nlayout(triangles) in;\nlayout(triangle_strip, max_vertices = 3) out;\n"
                + "uniform vec4 entityColor;\nvoid main() { if (entityColor.a > 0.5) EmitVertex(); }\n";
        EntityOverlayColor.ShadowStages stages = EntityOverlayColor.injectShadow(
                "#version 150\nin ivec2 UV1;\nvoid main() { gl_Position = vec4(0.0); }\n", geometry,
                "#version 150\nvoid main() {}\n", true);
        assertTrue(stages.geometry().contains("all(lessThan(uvec2(chimeraOverlayUV[0]), "
                + "uvec2(textureSize(chimeraOverlay, 0)))) ? texelFetch(chimeraOverlay, chimeraOverlayUV[0], 0)"
                + " : vec4(0.0, 0.0, 0.0, 1.0);"), stages.geometry());
    }
}
