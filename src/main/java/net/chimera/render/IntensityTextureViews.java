package net.chimera.render;

/**
 * Marks the image views VulkanMod creates while TextureUtil.prepareImage allocates a RED texture
 * (a TrueType glyph page; ChimeraIntensityTextureMixin), and ChimeraIntensityViewMixin gives them
 * an RRRR swizzle, as Iris swizzles that texture for its TEXT_INTENSITY keys. A pack program then reads the glyph's
 * coverage in every channel; vanilla's intensity shader reads red alone and draws as before.
 * Chimera's own single-channel images are built from a Vulkan format and keep identity views.
 */
public final class IntensityTextureViews {
    // Render thread only: prepareImage asserts it.
    private static boolean preparing;

    private IntensityTextureViews() {}

    public static void setPreparing(boolean value) {
        preparing = value;
    }

    public static boolean preparing() {
        return preparing;
    }
}
