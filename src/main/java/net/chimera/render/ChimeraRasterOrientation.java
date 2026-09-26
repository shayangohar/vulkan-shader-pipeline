package net.chimera.render;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE;

/**
 * Render passes whose images keep GL's bottom-up row order.
 *
 * VulkanMod rasterises with a negative-height viewport, so its images are
 * stored top-down and gl_FragCoord.y counts from the top. Iris packs rebuild
 * view rays and shadow lookups with GL conventions (screen and shadow-map v
 * are 0 at the bottom), so Chimera's world and pack-shadow raster passes
 * store GL-oriented images instead: a positive viewport, an unflipped
 * scissor and the mirrored front face. Fullscreen passes keep VulkanMod's
 * viewport; they copy memory rows between images that already share an
 * orientation. One flip moves the world into the host-oriented output.
 */
public final class ChimeraRasterOrientation {
    private static final Set<RenderPass> GL_ORIENTED =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private ChimeraRasterOrientation() {}

    public static void markGlOriented(RenderPass pass) {
        if (pass != null) GL_ORIENTED.add(pass);
    }

    public static void unmark(RenderPass pass) {
        if (pass != null) GL_ORIENTED.remove(pass);
    }

    public static boolean isGlOriented(RenderPass pass) {
        return pass != null && GL_ORIENTED.contains(pass);
    }

    /** True while the bound render pass rasterises in GL row order. */
    public static boolean boundPassGlOriented() {
        return isGlOriented(Renderer.getInstance().getBoundRenderPass());
    }

    /** Mirrors VulkanMod's front face for GL-oriented passes. */
    public static int frontFace(RenderPass pass, int hostFrontFace) {
        return isGlOriented(pass) ? VK_FRONT_FACE_CLOCKWISE : hostFrontFace;
    }
}
