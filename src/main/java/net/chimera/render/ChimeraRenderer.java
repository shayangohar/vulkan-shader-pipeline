package net.chimera.render;

import net.chimera.ChimeraMod;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.pass.MainPass;

/**
 * Owns the main-pass takeover lifecycle:
 * - onHostRendererReady: capture VulkanMod's installed MainPass and install
 *   chimera's segmented pass.
 * - toggle (F8): swap between chimera and host.
 * - screen mode: with SCREEN_SHADING_PARITY=true the renderer stays
 *   installed and shaded while a vanilla screen is open (blur chain
 *   included); =false restores the legacy immediate host passthrough.
 */
public final class ChimeraRenderer {

    /**
     * True = keep chimera installed and shaded while vanilla screens are
     * open (parity with Iris/Beryl). False = legacy immediate host
     * passthrough on setScreen, kept verbatim as a one-flag rollback for
     * the blur-chain barrier crash class (KNOW-47).
     */
    public static final boolean SCREEN_SHADING_PARITY = true;
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
        hostColorView = hostPass.getColorAttachmentView();
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

    /** True while a vanilla screen has forced the host renderer takeover. */
    public static boolean isScreenMode() {
        return screenMode;
    }

    /** Chimera's segments may run: installed and not in screen mode. */
    public static boolean segmentsActive() {
        return installed && !screenMode;
    }

    public static ChimeraMainPass getMainPass() {
        return chimeraPass;
    }

    private static com.mojang.blaze3d.textures.GpuTextureView hostColorView;

    /** True when the view is a main-target-family view (chimera or host). */
    public static boolean isMainFamilyView(com.mojang.blaze3d.textures.GpuTextureView view) {
        if (chimeraPass != null && chimeraPass.isFamilyView(view)) {
            return true;
        }
        return hostColorView != null && view == hostColorView;
    }

    /** The live main-target color texture for the current phase. */
    public static com.mojang.blaze3d.textures.GpuTexture getCurrentMainColorTexture() {
        return chimeraPass != null ? chimeraPass.currentMainColorTexture() : null;
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
        // Our post segments leave depth/cull/blend/topology state disabled;
        // restore the neutral state the host frame flow expects.
        VRenderSystem.enableDepthTest();
        VRenderSystem.enableCull();
        VRenderSystem.enableBlend();
        installed = false;
        ChimeraMod.LOGGER.info("Reverted to host main pass + host terrain shaders");
    }

    // ------------------------------------------------------------------
    // Screen mode: parity keeps chimera live behind vanilla screens; the
    // legacy branch below preserves the old full-passthrough behavior.
    // ------------------------------------------------------------------

    public static void enterScreenMode() {
        if (!SCREEN_SHADING_PARITY) {
            if (!installed) {
                return;
            }

            Renderer.getInstance().endRenderPass();
            Renderer.getInstance().setMainPass(hostPass);
            ChimeraTerrainPipelines.suspendForScreens();
            // Reflect reality: the host pass is now the installed pass. Without
            // this, exitScreenMode's install() early-returns on the installed
            // flag and chimera's pass is never restored - every frame after the
            // first screen cycle runs on the host renderer (no HDR, no shadows).
            installed = false;
            screenMode = true;
            ChimeraMod.LOGGER.info("Screen mode: host renderer in charge until screen closes");
            return;
        }

        // Parity mode: chimera stays installed and shaded behind the screen.
        // PostPassM ends any open pass before its barriers, Renderer's
        // endRenderPass is null-safe when nothing is recording, and encoder
        // draws targeting the main RT rebind our aux pass - so the blur
        // chain composes with the live frame without special handling.
        ChimeraMod.LOGGER.info("Screen open - chimera keeps rendering (shaded GUI backgrounds)");
    }

    public static void exitScreenMode() {
        if (!SCREEN_SHADING_PARITY) {
            if (!screenMode) {
                return;
            }

            screenMode = false;
            install();
            ChimeraMod.LOGGER.info("Screen closed - chimera resumed");
        }
    }
}
