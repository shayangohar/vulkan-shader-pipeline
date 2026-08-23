package net.chimera.render;

import net.chimera.ChimeraMod;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.pass.MainPass;

/**
 * Owns the main-pass takeover lifecycle:
 * - onHostRendererReady: capture VulkanMod's installed MainPass and wrap it.
 * - install/uninstall: swap Renderer's main pass reference between host and
 *   chimera wrapper. setMainPass is a plain field write on the host side.
 */
public final class ChimeraRenderer {
    private static boolean ready;
    private static boolean installed;
    private static MainPass hostPass;
    private static ChimeraMainPass chimeraPass;

    private ChimeraRenderer() {}

    public static void onHostRendererReady() {
        Renderer renderer = Renderer.getInstance();
        if (renderer == null || ready) {
            return;
        }

        MainPass current = renderer.getMainPass();
        if (current == null) {
            ChimeraMod.LOGGER.warn("VulkanMod Renderer ready but no main pass installed; chimera idle");
            return;
        }

        hostPass = current;
        chimeraPass = new ChimeraMainPass(hostPass);
        ready = true;

        ChimeraTerrainPipelines.init();

        ChimeraMod.LOGGER.info("Captured host main pass: {}", hostPass.getClass().getName());
        install();
    }

    public static void toggle() {
        if (installed) {
            uninstall();
        } else {
            install();
        }
    }

    public static boolean isReady() {
        return ready;
    }

    public static boolean isInstalled() {
        return installed;
    }

    private static void install() {
        if (!ready || installed) {
            return;
        }

        Renderer.getInstance().setMainPass(chimeraPass);
        ChimeraTerrainPipelines.enable();
        installed = true;
        ChimeraMod.LOGGER.info("chimera ACTIVE - main pass + terrain pipelines (F8 to toggle back)");
    }

    private static void uninstall() {
        if (!installed) {
            return;
        }

        Renderer.getInstance().setMainPass(hostPass);
        ChimeraTerrainPipelines.disable();
        installed = false;
        ChimeraMod.LOGGER.info("Reverted to host main pass + host terrain shaders");
    }
}
