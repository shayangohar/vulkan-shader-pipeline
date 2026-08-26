package net.chimera.render;

import net.chimera.ChimeraMod;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.pass.MainPass;

/**
 * Owns the main-pass takeover lifecycle:
 * - onHostRendererReady: capture VulkanMod's installed MainPass and install
 *   chimera's segmented pass.
 * - toggle (F8): swap between chimera and host.
 * - screen mode: GUI_SHADING (-Dchimera.guiShading) keeps the renderer
 *   installed and shaded behind vanilla screens over a LIVE level.
 *   DEFAULT OFF: keeping it on across level teardown/swap churn (respawn,
 *   portals) crashes in vkCmdPipelineBarrier during renderLevel even with
 *   the setLevel handoff - isolated via gated bisect (TASK-63 round 4).
 */
public final class ChimeraRenderer {

    /**
     * True = keep chimera installed and shaded behind vanilla screens over a
     * LIVE level (parity with Iris/Beryl); false - or any level-less screen -
     * = legacy immediate host passthrough, kept verbatim as a one-flag
     * rollback for the blur-chain barrier crash class (KNOW-47).
     *
     * Opt-in via -Dchimera.guiShading; default off - enabling it across
     * level transitions crashes (TASK-63 / TASK-64).
     */
    public static final boolean GUI_SHADING = debugFlag("chimera.guiShading");
    private static boolean ready;
    private static boolean installed;
    private static boolean screenMode;
    private static boolean screenOpen;
    private static MainPass hostPass;
    private static ChimeraMainPass chimeraPass;

    private ChimeraRenderer() {}

    /**
     * Debug/bisect switch parser: absent = disabled; a bare -Dname (empty
     * value) or any value other than "false" (case-insensitive) = enabled.
     * Boolean.getBoolean would ignore bare flags - it demands the literal
     * string "true" - which silently deactivated the whole gate matrix.
     */
    public static boolean debugFlag(String name) {
        String value = System.getProperty(name);
        return value != null && !"false".equalsIgnoreCase(value);
    }

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
    // Screen mode: parity keeps chimera live behind vanilla screens over a
    // LIVE level; level teardown/swap hands off to the host via the
    // WorldRenderer.setLevel hook until a live level returns.
    // ------------------------------------------------------------------

    public static void setScreenOpen(boolean open) {
        screenOpen = open;
    }

    /**
     * Legacy passthrough: hand the frame back to the host until reinstated.
     * Single source shared by the setScreen fallback and level hooks.
     */
    private static void legacyUnhand() {
        Renderer.getInstance().endRenderPass();
        Renderer.getInstance().setMainPass(hostPass);
        ChimeraTerrainPipelines.suspendForScreens();
        // Reflect reality: the host pass is now the installed pass. Without
        // this, install() early-returns on the installed flag and chimera's
        // pass is never restored - every frame after runs on the host
        // renderer (no HDR, no shadows).
        installed = false;
        screenMode = true;
    }

    public static void enterScreenMode() {
        if (GUI_SHADING && Minecraft.getInstance().level != null) {
            // Parity over a live level: PostPassM ends any open pass before
            // its barriers, Renderer's endRenderPass is null-safe when nothing
            // is recording, and encoder draws targeting the main RT rebind our
            // aux pass - the blur chain composes with the live frame.
            ChimeraMod.LOGGER.info("Screen open - chimera keeps rendering (shaded GUI backgrounds)");
            return;
        }

        // No live level (loading/transition screens) or legacy flag: the
        // teardown/swap churn around those frames crashed the segmented
        // frame's image transitions - use the proven host path instead.
        if (!installed) {
            return;
        }
        legacyUnhand();
        ChimeraMod.LOGGER.info("Screen mode: host renderer in charge until screen closes");
    }

    public static void exitScreenMode() {
        if (GUI_SHADING) {
            if (screenMode) {
                screenMode = false;
                install();
                ChimeraMod.LOGGER.info("Screen closed - chimera resumed");
            }
            return;
        }

        if (!screenMode) {
            return;
        }
        screenMode = false;
        install();
        ChimeraMod.LOGGER.info("Screen closed - chimera resumed");
    }

    /** Level went away mid-screen: host takes over until a live level returns. */
    public static void onLevelUnloaded() {
        if (!GUI_SHADING || !screenOpen || screenMode || !installed) {
            return;
        }

        legacyUnhand();
        ChimeraMod.LOGGER.info("Level unloaded - host renderer in charge (loading transition)");
    }

    /** Live level returned while a screen is still up: restore parity behind it. */
    public static void onLevelLoaded() {
        if (!GUI_SHADING || !screenOpen || !screenMode) {
            return;
        }

        install();
        screenMode = false;
        ChimeraMod.LOGGER.info("Level loaded - chimera resumes behind screen");
    }
}
