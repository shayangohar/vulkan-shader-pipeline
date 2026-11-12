package net.chimera.render;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
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
 * one: NVIDIA and Mesa drivers read the same bits through the format the shader names, though GL
 * leaves a qualifier that differs from the image unit's format undefined (KNOW-795), and Alpha
 * Piscium's begin2_b clears its R32UI colortex9 as rgba8. Such a target is created mutable
 * (ChimeraMutableImageMixin) and owns one storage view per format until it is freed.
 */
public final class TargetFormatViews {
    /** Per thread: MemoryManager.createImage is shared, and only this thread's allocation is the target. */
    private static final AllocationScope<Boolean> MUTABLE = new AllocationScope<>();
    // Render thread only: targets are created, bound and freed while the frame is recorded.
    private static final Map<VulkanImage, Map<Integer, Long>> VIEWS = new IdentityHashMap<>();

    private TargetFormatViews() {}

    /** Whether the image VulkanMod is allocating now is to be created mutable. */
    public static boolean preparing() {
        return MUTABLE.current() != null;
    }

    /** The image {@code create} allocates, mutable with a storage view in each of {@code formats} but its own. */
    static VulkanImage create(Supplier<VulkanImage> create, int format, Set<Integer> formats) {
        Map<Integer, Long> views = new TreeMap<>();
        boolean mutable = formats.stream().anyMatch(view -> view != format);
        if (!mutable) return create.get();
        VulkanImage image = MUTABLE.with(Boolean.TRUE, create);
        try {
            for (int view : formats) {
                if (view != format) {
                    views.put(view, ImageViews.createStorage(image.getId(), VK_IMAGE_VIEW_TYPE_2D, view));
                }
            }
        } catch (RuntimeException failure) {
            // Nothing has recorded a use of the new image yet, so it and its views go now.
            views.values().forEach(view -> vkDestroyImageView(Vulkan.getVkDevice(), view, null));
            image.free();
            throw failure;
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

    /**
     * Frees {@code image} and the views it owns once every recorded use has finished on the GPU.
     * MemoryManager.initFrame frees retired images before it runs frame ops, so an image with views
     * is retired from the frame op that destroys them, after them.
     */
    static void free(VulkanImage image) {
        Map<Integer, Long> views = VIEWS.remove(image);
        if (views == null) {
            image.free();
            return;
        }
        MemoryManager.getInstance().addFrameOp(() -> {
            views.values().forEach(view -> vkDestroyImageView(Vulkan.getVkDevice(), view, null));
            image.free();
        });
    }
}
