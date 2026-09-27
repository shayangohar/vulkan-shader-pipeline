package net.chimera.render.shader;

import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stitched atlas dimensions by image, so {@code atlasSize} answers for the
 * texture a draw samples, as Iris does: an atlas reports its size and any
 * other texture reports zero. Packs divide by it to step between atlas
 * texels (Complementary's POM slope normals), so a zero on the block atlas
 * turns those lookups into infinities.
 */
public final class AtlasSizes {
    private static final int[] NONE = {0, 0};
    private static final Map<VulkanImage, int[]> SIZES = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile VulkanImage blocks;

    private AtlasSizes() {}

    /** Records a finished atlas upload. */
    public static void note(VulkanImage image, int width, int height, boolean blockAtlas) {
        if (image == null) return;
        SIZES.put(image, new int[] {width, height});
        if (blockAtlas) blocks = image;
    }

    /** The atlas size of {@code image}, or zero when it is not an atlas. */
    public static int[] of(VulkanImage image) {
        int[] size = image == null ? null : SIZES.get(image);
        return size == null ? NONE : size;
    }

    /** The block atlas size, which terrain and shadow draws sample. */
    public static int[] blocks() {
        return of(blocks);
    }
}
