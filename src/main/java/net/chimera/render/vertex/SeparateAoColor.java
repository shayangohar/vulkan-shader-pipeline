package net.chimera.render.vertex;

import net.vulkanmod.vulkan.util.ColorUtil;

/**
 * Iris's separateAo terrain vertex colour (XHFPTerrainVertex): the tint stays in RGB and the
 * light pipeline's per-vertex brightness, ambient occlusion times face shade, moves into alpha
 * instead of being multiplied into RGB.
 */
public final class SeparateAoColor {
    private SeparateAoColor() {}

    /**
     * VulkanMod's packed vertex colour (RGBA, red in the low byte) with the brightness in alpha.
     * The brightness is clamped first: VulkanMod masks each channel to eight bits, so a value
     * just above 1 would wrap to black.
     */
    public static int rgba(float red, float green, float blue, float brightness) {
        return ColorUtil.RGBA.pack(red, green, blue, Math.max(0.0F, Math.min(1.0F, brightness)));
    }
}
