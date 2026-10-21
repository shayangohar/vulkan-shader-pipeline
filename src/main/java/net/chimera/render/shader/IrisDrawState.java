package net.chimera.render.shader;

import net.chimera.shaderpack.PackBlendPlan;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Iris's per-draw texture and blend uniforms (CommonUniforms), set as each draw uploads its
 * uniforms. gtextureSize is the size of the texture on unit 0, the draw's albedo (ExtendedShader
 * binds RenderSystem's shader texture 0 there before it updates its uniforms). blendFunc is the
 * draw's blend as GL factors, or zero with blending off: the vanilla render type's blend, or the
 * program's {@code blend.<program>} where Iris reports it (PackPipelines.blendFuncOverride).
 */
final class IrisDrawState implements FrameValueSource {
    static final Set<String> NAMES = Set.of("gtextureSize", "blendFunc");

    private static final Map<Pipeline, PackBlendPlan.Mode> PROGRAM_BLENDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final int[] textureSize = new int[2];
    private final int[] blendFunc = new int[4];

    /** Records the blend a pack pipeline's draws report in place of the host's. */
    static void registerProgramBlend(Pipeline pipeline, PackBlendPlan.Mode mode) {
        if (pipeline != null && mode != null) PROGRAM_BLENDS.put(pipeline, mode);
    }

    /** The blend a pipeline's draws report in place of the host's, or null. */
    static PackBlendPlan.Mode programBlend(Pipeline pipeline) {
        return pipeline == null ? null : PROGRAM_BLENDS.get(pipeline);
    }

    /** Sets the next draw's values, the program's blend or else the host's; true when either changed. */
    boolean set(int width, int height, PackBlendPlan.Mode programBlend, PipelineState.BlendInfo host) {
        boolean changed = textureSize[0] != width || textureSize[1] != height;
        textureSize[0] = width;
        textureSize[1] = height;
        if (programBlend != null) {
            return setBlend(programBlend.enabled(), programBlend.srcColor(), programBlend.dstColor(),
                    programBlend.srcAlpha(), programBlend.dstAlpha()) || changed;
        }
        return setBlend(host.enabled, host.srcRgbFactor, host.dstRgbFactor, host.srcAlphaFactor,
                host.dstAlphaFactor) || changed;
    }

    /** Writes a blend's GL factors, or zero with blending off, in place; true when they changed. */
    private boolean setBlend(boolean enabled, int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
        int[] factors = {srcColor, dstColor, srcAlpha, dstAlpha};
        boolean changed = false;
        for (int i = 0; i < 4; i++) {
            int value = enabled ? PackBlendPlan.glFactor(factors[i]) : 0;
            changed |= blendFunc[i] != value;
            blendFunc[i] = value;
        }
        return changed;
    }

    @Override
    public Set<String> names() {
        return NAMES;
    }

    @Override
    public double value(String name, int component) {
        int c = Math.max(component, 0);
        return switch (name) {
            case "gtextureSize" -> c < 2 ? textureSize[c] : Double.NaN;
            case "blendFunc" -> c < 4 ? blendFunc[c] : Double.NaN;
            default -> Double.NaN;
        };
    }

    @Override
    public void write(String name, String type, MappedBuffer target) {
        int[] values = name.equals("gtextureSize") ? textureSize : blendFunc;
        for (int component = 0; component < values.length; component++) {
            target.putInt(component * 4, values[component]);
        }
    }
}
