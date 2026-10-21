package net.chimera.render.shader;

import net.chimera.shaderpack.PackBlendPlan;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Iris's per-draw texture and blend uniforms (CommonUniforms), set as each draw uploads its
 * uniforms. gtextureSize is the size of the texture on unit 0, the draw's albedo (ExtendedShader
 * binds RenderSystem's shader texture 0 there before it updates its uniforms). blendFunc is the
 * draw's blend as GL factors, or zero with blending off: the vanilla render type's blend when the
 * uniforms update, then the program's {@code blend.<program>} when that directive enables
 * blending (its glBlendFuncSeparate notifies the uniform). An {@code off} directive disables
 * blending without notifying, so the uniform keeps the vanilla blend.
 */
final class IrisDrawState implements FrameValueSource {
    static final Set<String> NAMES = Set.of("gtextureSize", "blendFunc");

    private static final Map<Pipeline, PackBlendPlan.Mode> PROGRAM_BLENDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final int[] textureSize = new int[2];
    private final int[] blendFunc = new int[4];

    /** Records a pack pipeline's program-level blend directive. */
    static void registerProgramBlend(Pipeline pipeline, PackBlendPlan.Mode mode) {
        if (pipeline != null && mode != null) PROGRAM_BLENDS.put(pipeline, mode);
    }

    /** A pipeline's program-level blend directive, or null without one. */
    static PackBlendPlan.Mode programBlend(Pipeline pipeline) {
        return pipeline == null ? null : PROGRAM_BLENDS.get(pipeline);
    }

    /** Sets the next draw's values; true when either changed. */
    boolean set(int width, int height, PackBlendPlan.Mode programBlend, PackBlendPlan.Mode hostBlend) {
        int[] blend = (programBlend != null && programBlend.enabled() ? programBlend : hostBlend).glFunc();
        boolean changed = textureSize[0] != width || textureSize[1] != height || !Arrays.equals(blendFunc, blend);
        textureSize[0] = width;
        textureSize[1] = height;
        System.arraycopy(blend, 0, blendFunc, 0, 4);
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
