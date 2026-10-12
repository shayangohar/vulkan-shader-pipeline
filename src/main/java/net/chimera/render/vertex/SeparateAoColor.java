package net.chimera.render.vertex;

import net.vulkanmod.vulkan.util.ColorUtil;

/**
 * Iris's separateAo terrain vertex colour (XHFPTerrainVertex): the block's tint stays in RGB
 * and the light pipeline's per-vertex brightness, ambient occlusion times face shade, moves
 * into alpha instead of being multiplied into RGB.
 */
public final class SeparateAoColor {
    private SeparateAoColor() {}

    /** A FRAPI quad colour (ARGB) with its alpha replaced by the brightness. */
    public static int argb(int color, float brightness) {
        return (color & 0x00FFFFFF) | (alphaByte(brightness) << 24);
    }

    /** VulkanMod's packed fluid colour (RGBA, red in the low byte) with the brightness in alpha. */
    public static int rgba(float red, float green, float blue, float brightness) {
        return ColorUtil.RGBA.pack(red, green, blue, Math.max(0.0F, Math.min(1.0F, brightness)));
    }

    /**
     * The brightness as a byte, truncated as VulkanMod and Sodium convert colours. Clamped
     * first: VulkanMod masks to eight bits, so a value just above 1 would wrap to black.
     */
    static int alphaByte(float brightness) {
        return (int) (Math.max(0.0F, Math.min(1.0F, brightness)) * 255.0F);
    }
}
