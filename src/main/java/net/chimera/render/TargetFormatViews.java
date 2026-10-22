package net.chimera.render;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

/**
 * Views of target images in other formats of their texel size, for a storage binding that declares
 * one: a GL image unit reads the same bits through the format the shader names (KNOW-795), and
 * Alpha Piscium's begin2_b clears its R32UI colortex9 as rgba8. Such a target is created mutable
 * (ChimeraMutableImageMixin) and owns one storage view per format until it is freed.
 */
public final class TargetFormatViews {
    // Render thread only: targets are created, bound and freed while the frame is recorded.
    private static boolean preparing;
    private static final Map<VulkanImage, Map<Integer, Long>> VIEWS = new IdentityHashMap<>();

    private TargetFormatViews() {}

    /** Whether the image VulkanMod is allocating now is to be created mutable. */
    public static boolean preparing() {
        return preparing;
    }

    /** The image {@code create} allocates, mutable with a storage view in each of {@code formats} but its own. */
    static VulkanImage create(Supplier<VulkanImage> create, int format, Set<Integer> formats) {
        Map<Integer, Long> views = new TreeMap<>();
        boolean mutable = formats.stream().anyMatch(view -> view != format);
        preparing = mutable;
        VulkanImage image;
        try {
            image = create.get();
        } finally {
            preparing = false;
        }
        if (!mutable) return image;
        for (int view : formats) {
            if (view != format) views.put(view, ImageViews.createStorage(image.getId(), VK_IMAGE_VIEW_TYPE_2D, view));
        }
        VIEWS.put(image, views);
        return image;
    }

    /** The view a storage binding reads {@code image} through in {@code format}: its level-0 view in its own. */
    static long storageView(VulkanImage image, int format) {
        Map<Integer, Long> views = VIEWS.get(image);
        Long view = views == null ? null : views.get(format);
        return view != null ? view : PackPostTargets.attachmentView(image);
    }

    /** Frees {@code image} and the views it owns. */
    static void free(VulkanImage image) {
        Map<Integer, Long> views = VIEWS.remove(image);
        if (views != null) views.values().forEach(view -> vkDestroyImageView(Vulkan.getVkDevice(), view, null));
        image.free();
    }
}
