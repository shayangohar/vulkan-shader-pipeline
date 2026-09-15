package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * Limits an alternate depth-only image view to one pack depth conversion draw.
 * The host image keeps its original depth-stencil view and is never replaced.
 */
public final class ChimeraDepthViewOverride {
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private ChimeraDepthViewOverride() {
    }

    public static void bind(VulkanImage image, long view) {
        State state = STATE.get();
        state.image = image;
        state.view = view;
    }

    public static void clear() {
        State state = STATE.get();
        state.image = null;
        state.view = 0L;
    }

    public static long viewFor(VulkanImage image) {
        State state = STATE.get();
        return state.image == image ? state.view : 0L;
    }

    private static final class State {
        private VulkanImage image;
        private long view;
    }
}
