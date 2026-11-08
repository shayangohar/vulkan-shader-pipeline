package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The images vanilla's NativeImage upload asked to clamp to their edge (ChimeraNativeImageClampMixin):
 * a texture's "clamp" metadata, as misc/shadow.png has. VulkanMod drops the request and samples
 * them repeating; a pack draw samples them clamped (SamplerProfile.asGl), as OpenGL does for Iris.
 * A freed image leaves with its last reference.
 */
public final class EdgeClampedImages {
    // Render thread only: NativeImage._upload asserts it, and pack draws bind there.
    private static final Set<VulkanImage> IMAGES = Collections.newSetFromMap(new WeakHashMap<>());

    private EdgeClampedImages() {}

    public static void add(VulkanImage image) {
        IMAGES.add(image);
    }

    public static boolean contains(VulkanImage image) {
        return image != null && IMAGES.contains(image);
    }
}
