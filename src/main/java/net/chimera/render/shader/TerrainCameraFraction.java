package net.chimera.render.shader;

import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.util.MappedBuffer;

/**
 * The fractional part of the camera position a terrain layer draws from, served to Chimera's
 * terrain and shadow shaders as the view block's {@value #UNIFORM}.
 *
 * <p>VulkanMod rounds each chunk area's camera-relative origin (the ChunkOffset push constant) to
 * float on its own, so a corner two areas share landed a float step apart in each, and pixels along
 * the seam got no fragment. With this fraction the shaders recover each area's whole-block offset
 * exactly and subtract the one fraction last: every area then rounds a shared corner the same way
 * (GlslVertexPreambles.chimeraWorldPosition).</p>
 */
public final class TerrainCameraFraction {
    public static final String UNIFORM = "ChimeraCameraFraction";
    private static final MappedBuffer FRACTION = new MappedBuffer(16);

    private TerrainCameraFraction() {}

    /** Serves the field by name, as the host serves MVP, before any pipeline that declares it is built. */
    public static void register() {
        Uniforms.vec4f_uniformMap.put(UNIFORM, () -> FRACTION);
    }

    /** Records the camera of the layer renderSectionLayer is about to draw, before it uploads the layer's UBOs. */
    public static void set(double cameraX, double cameraY, double cameraZ) {
        FRACTION.putFloat(0, fraction(cameraX));
        FRACTION.putFloat(4, fraction(cameraY));
        FRACTION.putFloat(8, fraction(cameraZ));
    }

    static float fraction(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate));
    }
}
