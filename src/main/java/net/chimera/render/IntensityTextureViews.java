package net.chimera.render;

/**
 * Marks the image views VulkanMod creates while TextureUtil.prepareImage allocates a RED texture
 * (a TrueType glyph page; ChimeraIntensityTextureMixin), and ChimeraIntensityViewMixin gives them
 * an RRRR swizzle, as Iris swizzles that texture for its TEXT_INTENSITY keys. A pack program then reads the glyph's
 * coverage in every channel; vanilla's intensity shader reads red alone and draws as before.
 * Chimera's own single-channel images are built from a Vulkan format and keep identity views.
 */
public final class IntensityTextureViews {
    private static final AllocationScope<Boolean> PREPARING = new AllocationScope<>();

    private IntensityTextureViews() {}

    /** Runs {@code allocate}, a RED texture's allocation, with the views it creates marked. */
    public static void allocate(Runnable allocate) {
        PREPARING.run(Boolean.TRUE, allocate);
    }

    /** Whether the view VulkanMod is creating belongs to a RED texture this thread is allocating. */
    public static boolean preparing() {
        return PREPARING.current() != null;
    }
}
