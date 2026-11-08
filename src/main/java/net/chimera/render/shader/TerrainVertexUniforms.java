package net.chimera.render.shader;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.util.MappedBuffer;

/**
 * The view-block values Chimera's terrain and shadow vertex shaders read beside MVP, served by name
 * as the host serves its own (chimera_terrain.json, chimera_shadow.json).
 *
 * <p>{@value #CAMERA_FRACTION}: the fractional part of the camera position a terrain layer draws
 * from. VulkanMod rounds each chunk area's camera-relative origin (the ChunkOffset push constant) to
 * float on its own, so a corner two areas share landed a float step apart in each, and pixels along
 * the seam got no fragment. With this fraction the shaders recover each area's whole-block offset
 * exactly and subtract the one fraction last (GlslVertexPreambles.chimeraWorldPosition).</p>
 *
 * <p>{@value #TEXCOORD_SHRINK}: how far the shaders take back the step toward the quad's centre that
 * ChimeraExtVertexBuilder.encodeTexture adds, per axis of the block atlas: one 1/32768 step less one
 * rasterizer sub-texel, as Iris's u_TexCoordShrink. Every terrain coordinate then lands one sub-texel
 * inside its sprite, never on its edge, where a lookup could read the neighbouring sprite.</p>
 */
public final class TerrainVertexUniforms {
    public static final String CAMERA_FRACTION = "ChimeraCameraFraction";
    public static final String TEXCOORD_SHRINK = "ChimeraTexCoordShrink";
    private static final float TEXCOORD_STEP = 1.0F / 32768.0F;
    private static final MappedBuffer FRACTION = new MappedBuffer(16);
    private static final MappedBuffer SHRINK = new MappedBuffer(16);
    private static int subTexelBits = 8;

    private TerrainVertexUniforms() {}

    /** Serves both fields by name, before any pipeline that declares them is built. */
    public static void register() {
        subTexelBits = DeviceManager.deviceProperties.limits().subTexelPrecisionBits();
        Uniforms.vec4f_uniformMap.put(CAMERA_FRACTION, () -> FRACTION);
        Uniforms.vec4f_uniformMap.put(TEXCOORD_SHRINK, () -> SHRINK);
    }

    /** Records the layer renderSectionLayer is about to draw, before it uploads the layer's UBOs. */
    public static void update(double cameraX, double cameraY, double cameraZ) {
        FRACTION.putFloat(0, fraction(cameraX));
        FRACTION.putFloat(4, fraction(cameraY));
        FRACTION.putFloat(8, fraction(cameraZ));
        int[] atlas = AtlasSizes.blocks();
        SHRINK.putFloat(0, texcoordShrink(atlas[0], subTexelBits));
        SHRINK.putFloat(4, texcoordShrink(atlas[1], subTexelBits));
    }

    static float fraction(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate));
    }

    /** One coordinate step less one sub-texel of an atlas {@code size} texels across; a whole step without one. */
    public static float texcoordShrink(int size, int subTexelBits) {
        return size <= 0 ? TEXCOORD_STEP : (float) (TEXCOORD_STEP - 1.0 / size / (1 << subTexelBits));
    }
}
