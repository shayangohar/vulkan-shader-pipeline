package net.chimera.render;

import net.chimera.ChimeraMod;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.pass.MainPass;

import java.nio.file.Path;

/**
 * Owns the main-pass takeover lifecycle:
 * - onHostRendererReady: capture VulkanMod's installed MainPass and install
 *   chimera's segmented pass.
 * - screen mode (default on): the renderer stays installed and shaded behind
 *   vanilla screens over a LIVE level; level teardown/swap churn (respawn,
 *   portals, loading screens) hands off to the host via the setLevel hook
 *   until a live level returns.
 */
public final class ChimeraRenderer {

    private static boolean ready;
    private static boolean installed;
    private static boolean screenMode;
    private static boolean screenOpen;
    /** Keeps a normal Chimera install request alive across a safe rebuild. */
    private static boolean resumeAfterVariant;
    /** F8-controlled master state; pack commands must not change it. */
    private static boolean chimeraEnabled = true;
    private static MainPass hostPass;
    private static ChimeraMainPass chimeraPass;

    public enum PackRequestResult {
        QUEUED,
        ALREADY_ACTIVE,
        NOT_READY
    }

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
        chimeraEnabled = true;
        install();
    }

    public static void toggle() {
        if (screenMode) {
            // F8 while a screen is open: leave screen mode via full toggle-off.
            chimeraEnabled = false;
            if (installed) {
                uninstall();
            } else {
                screenMode = false;
                resumeAfterVariant = false;
            }
            return;
        }

        if (!installed && resumeAfterVariant) {
            chimeraEnabled = false;
            resumeAfterVariant = false;
            ChimeraMod.LOGGER.info("F8 disabled pending Chimera resume");
            return;
        }

        if (installed) {
            chimeraEnabled = false;
            uninstall();
        } else {
            chimeraEnabled = true;
            install();
        }
    }

    public static boolean isReady() {
        return ready;
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static PackRequestResult requestPack(Path path) {
        if (!ready || chimeraPass == null) {
            return PackRequestResult.NOT_READY;
        }
        return chimeraPass.queuePackChange(path, false)
                ? PackRequestResult.QUEUED : PackRequestResult.ALREADY_ACTIVE;
    }

    public static PackRequestResult reloadPack() {
        if (!ready || chimeraPass == null) {
            return PackRequestResult.NOT_READY;
        }
        return chimeraPass.queuePackReload()
                ? PackRequestResult.QUEUED : PackRequestResult.ALREADY_ACTIVE;
    }

    public static PackRequestResult disablePack() {
        return requestPack(null);
    }

    public static String packStatus() {
        if (!ready || chimeraPass == null) {
            return "Chimera pack: renderer not ready";
        }
        return chimeraPass.packStatus() + ", F8=" + (chimeraEnabled ? "enabled" : "disabled")
                + ", renderer=" + (installed ? "chimera" : "host");
    }

    /** Applies pending pack work before VulkanMod begins a new main command buffer. */
    public static void beforeMainCommandBuffer() {
        if (!ready || chimeraPass == null) {
            return;
        }
        if (chimeraPass.hasPendingPackChange() && installed) {
            Renderer.getInstance().setMainPass(hostPass);
            ChimeraTerrainPipelines.disable();
            ChimeraEntityBridge.setEnabled(false);
            ChimeraSkyBridge.setEnabled(false);
            installed = false;
            resumeAfterVariant = chimeraEnabled;
            ChimeraMod.LOGGER.info("Pack change handed to host at safe boundary");
        }
        if (!chimeraPass.applyPendingPackVariantAtFrameBoundary()) {
            return;
        }
        boolean liveLevel = Minecraft.getInstance().level != null;
        if (chimeraEnabled && (resumeAfterVariant || (screenMode && liveLevel)) && !installed) {
            if (install()) {
                resumeAfterVariant = false;
                screenMode = false;
                ChimeraMod.LOGGER.info("Safe frame boundary - chimera resumed");
            }
        }
    }


    /** Chimera's world segments may run while its pass owns the frame. */
    public static boolean segmentsActive() {
        return installed && !screenMode;
    }

    public static ChimeraMainPass getMainPass() {
        return chimeraPass;
    }

    private static com.mojang.blaze3d.textures.GpuTextureView hostColorView;

    /** True when the view is a main-target-family view (chimera or host). */
    public static boolean isMainFamilyView(com.mojang.blaze3d.textures.GpuTextureView view) {
        if (view == null) {
            return false;
        }
        if (chimeraPass != null && chimeraPass.isFamilyView(view)) {
            return true;
        }
        com.mojang.blaze3d.textures.GpuTexture texture = view.texture();
        if (hostColorView != null && texture == hostColorView.texture()) {
            return true;
        }
        com.mojang.blaze3d.textures.GpuTexture current = getCurrentMainColorTexture();
        return current != null && texture == current;
    }

    /** The live main-target color texture for the current phase. */
    public static com.mojang.blaze3d.textures.GpuTexture getCurrentMainColorTexture() {
        return chimeraPass != null ? chimeraPass.currentMainColorTexture() : null;
    }

    private static boolean install() {
        if (!ready || installed) {
            return installed;
        }

        if (!chimeraPass.prepareForInstall()) {
            // A toggle can arrive while the current command buffer is still
            // recording. Keep the request alive so the pre-command-buffer
            // hook retries it at the next safe boundary instead of leaving
            // the host renderer installed until another manual toggle.
            resumeAfterVariant = true;
            ChimeraMod.LOGGER.warn("Chimera install deferred; host main pass remains active");
            return false;
        }
        Renderer.getInstance().setMainPass(chimeraPass);
        ChimeraTerrainPipelines.enable();
        ChimeraEntityBridge.setEnabled(ChimeraEntityBridge.isInstalled());
        ChimeraSkyBridge.setEnabled(ChimeraSkyBridge.isInstalled());
        installed = true;
        ChimeraMod.LOGGER.info("chimera ACTIVE - HDR frame + terrain/entity pipelines (F8 to toggle back)");
        return true;
    }

    private static void uninstall() {
        if (!installed) {
            return;
        }

        Renderer.getInstance().endRenderPass();
        Renderer.getInstance().setMainPass(hostPass);
        ChimeraTerrainPipelines.disable();
        ChimeraEntityBridge.setEnabled(false);
        ChimeraSkyBridge.setEnabled(false);
        // Our post segments leave depth/cull/blend/topology state disabled;
        // restore the neutral state the host frame flow expects.
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.enableCull();
        VRenderSystem.enableBlend();
        VRenderSystem.colorMask(true, true, true, true);
        installed = false;
        resumeAfterVariant = false;
        ChimeraMod.LOGGER.info("Reverted to host main pass + host terrain shaders");
    }

    // ------------------------------------------------------------------
    // Screen mode: parity keeps chimera live behind vanilla screens over a
    // LIVE level; level teardown/swap hands off to the host via the
    // WorldRenderer.setLevel hook until a live level returns.
    // ------------------------------------------------------------------

    public static void setScreenOpen(boolean open) {
        screenOpen = open;
        if (chimeraPass != null) {
            chimeraPass.scheduleScreenResourceReset();
        }
    }

    /**
     * Legacy passthrough: hand the frame back to the host until reinstated.
     * Single source shared by the setScreen fallback and level hooks.
     */
    private static void legacyUnhand() {
        Renderer.getInstance().endRenderPass();
        Renderer.getInstance().setMainPass(hostPass);
        ChimeraTerrainPipelines.suspendForScreens();
        ChimeraEntityBridge.setEnabled(false);
        ChimeraSkyBridge.setEnabled(false);
        // Return the global pipeline state VulkanMod snapshots at bind to the
        // neutral host defaults before the host's next frame records.
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.enableCull();
        VRenderSystem.enableBlend();
        VRenderSystem.colorMask(true, true, true, true);
        // Reflect reality: the host pass is now the installed pass. Without
        // this, install() early-returns on the installed flag and chimera's
        // pass is never restored - every frame after runs on the host
        // renderer (no HDR, no shadows).
        installed = false;
        resumeAfterVariant = false;
        screenMode = true;
    }

    /** Leaves the host renderer active after a failed Chimera variant rebuild. */
    static void fallbackToHostRenderer() {
        if (installed) {
            Renderer.getInstance().endRenderPass();
            Renderer.getInstance().setMainPass(hostPass);
        }
        ChimeraTerrainPipelines.disable();
        ChimeraEntityBridge.setEnabled(false);
        ChimeraSkyBridge.setEnabled(false);
        VRenderSystem.enableDepthTest();
        VRenderSystem.depthMask(true);
        VRenderSystem.enableCull();
        VRenderSystem.enableBlend();
        VRenderSystem.colorMask(true, true, true, true);
        installed = false;
        screenMode = false;
        resumeAfterVariant = false;
        chimeraEnabled = false;
        ChimeraMod.LOGGER.warn("Chimera variant fallback - host main pass restored");
    }

    public static void enterScreenMode() {
        if (Minecraft.getInstance().level != null) {
            // Parity over a live level: PostPassM ends any open pass before
            // its barriers, Renderer's endRenderPass is null-safe when nothing
            // is recording, and encoder draws targeting the main RT rebind our
            // aux pass - the blur chain composes with the live frame.
            ChimeraMod.LOGGER.info("Screen open - chimera keeps rendering (shaded GUI backgrounds)");
            return;
        }

        // No live level (loading/transition screens): the teardown/swap churn
        // around those frames crashed the segmented frame's image transitions
        // - use the proven host path instead.
        if (!installed) {
            return;
        }
        legacyUnhand();
        ChimeraMod.LOGGER.info("Screen mode: host renderer in charge until screen closes");
    }

    public static void exitScreenMode() {
        if (!screenMode) {
            return;
        }
        ChimeraMod.LOGGER.info("Screen closed - chimera resume deferred to the next safe frame boundary");
    }

    /** Level went away mid-screen: host takes over until a live level returns. */
    public static void onLevelUnloaded() {
        if (!screenOpen || screenMode || !installed) {
            return;
        }

        legacyUnhand();
        ChimeraMod.LOGGER.info("Level unloaded - host renderer in charge (loading transition)");
    }

    /** Live level returned while a screen is still up: restore parity behind it. */
    public static void onLevelLoaded() {
        onLevelLoaded(Minecraft.getInstance().level);
    }

    /** Re-selects the pack variant before applying the screen lifecycle state. */
    public static void onLevelLoaded(ClientLevel level) {
        if (chimeraPass != null && level != null) {
            chimeraPass.onLevelChanged(level.dimension().identifier().toString());
        }
        if (!screenOpen || !screenMode) {
            return;
        }

        ChimeraMod.LOGGER.info("Level loaded - chimera resume deferred to the next safe frame boundary");
    }
}
