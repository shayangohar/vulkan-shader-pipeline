package net.chimera.render.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Set;

/**
 * The discard threshold Iris tests a host pipeline's draw against (its AlphaTests), which the pack
 * program reads per draw as iris_currentAlphaTest. Host-layout lanes carry their own instead.
 */
final class HostAlphaTest {
    /** Iris AlphaTests.ONE_TENTH_ALPHA: the fixed core particle, weather, item and crumbling threshold. */
    private static final float ONE_TENTH_ALPHA = 0.1F;
    /** Iris AlphaTests.NON_ZERO_ALPHA: GREATER 0.0001. */
    private static final float NON_ZERO_ALPHA = 0.0001F;
    /** Host pipelines tested at {@link #ONE_TENTH_ALPHA}. */
    private static final Set<RenderPipeline> ONE_TENTH_PIPELINES = Set.of(
            RenderPipelines.CRUMBLING,
            RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL,
            RenderPipelines.OPAQUE_PARTICLE,
            RenderPipelines.TRANSLUCENT_PARTICLE,
            RenderPipelines.WEATHER_DEPTH_WRITE,
            RenderPipelines.WEATHER_NO_DEPTH_WRITE,
            // Iris ENTITIES_EYES_TRANS: ONE_TENTH_ALPHA.
            RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE
    );

    private HostAlphaTest() {}

    /**
     * The host pipeline's discard threshold (fixed core value or ALPHA_CUTOUT define), or 0 for a
     * pipeline that never discards.
     *
     * <p>The item pipeline declares no define: its core shader discards below a fixed 0.1, and Iris
     * tests held items against 0.1 too. Without it the transparent texels of a flat item wrote depth
     * and gbuffer data across the whole quad, and its glint lit that box.</p>
     */
    static float reference(RenderPipeline pipeline, boolean shadowCaster, boolean handFamily) {
        // Iris SHADOW_ENTITIES_CUTOUT tests every entity caster at ONE_TENTH_ALPHA.
        if (shadowCaster) return ONE_TENTH_ALPHA;
        if (pipeline != null && ONE_TENTH_PIPELINES.contains(pipeline)) return ONE_TENTH_ALPHA;
        // Iris ENTITIES_EYES and GLINT: NON_ZERO_ALPHA.
        if (pipeline == RenderPipelines.EYES || pipeline == RenderPipelines.GLINT) return NON_ZERO_ALPHA;
        // Iris TEXT and TEXT_BE: ONE_TENTH_ALPHA; HAND_TEXT: NON_ZERO_ALPHA. The core
        // text shader discards below 0.1 without a define; untested, every glyph
        // quad wrote its transparent texels.
        if (ChimeraEntityBridge.isTextPipeline(pipeline)) return handFamily ? NON_ZERO_ALPHA : ONE_TENTH_ALPHA;
        return definedAlphaCutout(pipeline);
    }

    /** The pipeline's positive ALPHA_CUTOUT define, or 0 when it is absent or malformed. */
    private static float definedAlphaCutout(RenderPipeline pipeline) {
        String cutout = pipeline == null ? null : pipeline.getShaderDefines().values().get("ALPHA_CUTOUT");
        if (cutout == null) return 0.0F;
        try {
            float value = Float.parseFloat(cutout.trim().replaceFirst("[fF]$", ""));
            return Float.isFinite(value) && value > 0.0F ? value : 0.0F;
        } catch (NumberFormatException malformed) {
            return 0.0F;
        }
    }
}
