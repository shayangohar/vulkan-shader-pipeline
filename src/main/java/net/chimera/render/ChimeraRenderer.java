package net.chimera.render;

import net.chimera.ChimeraMod;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.pass.MainPass;

/**
 * Owns the main-pass takeover lifecycle:
 * - onHostRendererReady: capture VulkanMod's installed MainPass and install
 *   chimera's segmented pass.
 * - toggle (F8): swap between chimera and host.
 * - screen mode: while a vanilla screen is open, fully unhand the renderer
 *   (host pass + host terrain) - the configuration proven crash-free on ESC.
 *   Resources stay alive; only routing flips.
 */
public final class ChimeraRenderer {
    private static boolean ready;
    private static boolean installed;
    private static boolean screenMode;
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
        chimeraPass = new ChimeraMainPass();
        ready = true;

        ChimeraTerrainPipelines.init();

        ChimeraMod.LOGGER.info("Captured host main pass: {}", hostPass.getClass().getName());
        install();
    }

    public static void toggle() {
        if (screenMode) {
            // F8 while a screen is open: leave screen mode via full toggle-off.
            uninstall();
            return;
        }

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

    public static ChimeraMainPass getMainPass() {
        return chimeraPass;
    }

    private static int depthRedirectLogs = 3;
    private static int depthCallSiteLogs = 6;

    public static void debugDepthCallSite() {
        if (depthCallSiteLogs > 0) {
            depthCallSiteLogs--;
            ChimeraMod.LOGGER.info("[dbg] clearDepthTexture call site reached; boundPass={}",
                    Renderer.getInstance().getBoundRenderPass() != null ? "open" : "closed");
        }
    }

    public static void debugDepthRedirect() {
        if (depthRedirectLogs > 0) {
            depthRedirectLogs--;
            ChimeraMod.LOGGER.info("[dbg] hand depth-clear redirected (in-pass)");
        }
    }

    private static void install() {
        if (!ready || installed) {
            return;
        }

        Renderer.getInstance().setMainPass(chimeraPass);
        ChimeraTerrainPipelines.enable();
        installed = true;
        ChimeraMod.LOGGER.info("chimera ACTIVE - HDR frame + terrain pipelines (F8 to toggle back)");
    }

    private static void uninstall() {
        if (!installed) {
            return;
        }

        Renderer.getInstance().endRenderPass();
        Renderer.getInstance().setMainPass(hostPass);
        ChimeraTerrainPipelines.disable();
        installed = false;
        ChimeraMod.LOGGER.info("Reverted to host main pass + host terrain shaders");
    }

    // ------------------------------------------------------------------
    // Screen mode: full temporary passthrough while a vanilla screen is open
    // ------------------------------------------------------------------

    public static void enterScreenMode() {
        if (!installed) {
            return;
        }

        Renderer.getInstance().endRenderPass();
        Renderer.getInstance().setMainPass(hostPass);
        ChimeraTerrainPipelines.suspendForSimpleMode();
        screenMode = true;
        ChimeraMod.LOGGER.info("Screen mode: host renderer in charge until screen closes");
    }

    public static void exitScreenMode() {
        if (!screenMode) {
            return;
        }

        screenMode = false;
        install();
        ChimeraMod.LOGGER.info("Screen closed - chimera resumed");
    }
}
