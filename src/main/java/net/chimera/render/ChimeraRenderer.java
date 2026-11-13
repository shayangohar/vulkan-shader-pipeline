package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.chimera.ChimeraMod;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.TerrainVertexUniforms;
import net.chimera.shaderpack.ParticleDirectives;
import net.chimera.shaderpack.ScreenOverlayDirectives;
import net.chimera.shaderpack.SkyDirectives;
import net.chimera.shaderpack.StagedOptions;
import net.chimera.shaderpack.WeatherDirectives;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.pass.MainPass;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    /**
     * False after a failed pack rebuild left the host renderer in charge, so
     * that pack is not retried every frame. The next pack command clears it.
     */
    private static boolean chimeraEnabled = true;
    private static boolean noPackLogged;
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
            ChimeraMod.LOGGER.warn("[chimera] VulkanMod has no main pass; Chimera stays idle");
            return;
        }
        if (!ChimeraDevice.dynamicRendering()) {
            ChimeraMod.LOGGER.debug("[chimera] no dynamic rendering; Chimera stays idle");
            return;
        }

        hostPass = current;
        // Before the main pass and the terrain pipelines build the terrain and shadow configs.
        TerrainVertexUniforms.register();
        chimeraPass = new ChimeraMainPass();
        renderer.addOnResizeCallback(ChimeraRenderer::refreshIdleHostPass);
        ready = true;

        ChimeraTerrainPipelines.init();

        ChimeraMod.LOGGER.debug("[chimera] host main pass: {}", hostPass.getClass().getName());
        chimeraEnabled = true;
        install();
    }

    /**
     * VulkanMod's resize calls {@code onResize} only on the current main pass. While Chimera
     * owns the frame, the host pass would keep its old swapchain-sized depth texture, and
     * after {@code /chimera pack off} vanilla's hand depth clear compares against that stale
     * image, skips the clear, and the hand clips into the world. Refresh it with the swapchain.
     */
    private static void refreshIdleHostPass() {
        if (hostPass != null && Renderer.getInstance().getMainPass() != hostPass) {
            hostPass.onResize();
        }
    }

    /**
     * VulkanMod's final teardown (Renderer.cleanUpResources, from Vulkan.cleanUp after the device
     * went idle) cleans only the installed main pass. Chimera hands the frame to the host pass when
     * the level closes, so on quit its own pass was never cleaned: its Vulkan objects outlived the
     * device and an unpacked ZIP's temporary folder waited for the next start's sweep. Cleans it
     * here unless it is the installed pass, which VulkanMod cleans itself. Any failure, an Error
     * included, is logged: one escaping would skip the rest of VulkanMod's teardown, from the host
     * pass to the device. Chimera is not ready afterwards, so nothing installs or drives the torn
     * down pass if the host ever reached this outside quitting.
     */
    public static void beforeHostCleanup() {
        ChimeraMainPass pass = chimeraPass;
        boolean installedPass = pass != null && Renderer.getInstance().getMainPass() == pass;
        ready = false;
        if (pass == null || installedPass) {
            return;
        }
        try {
            pass.cleanUp();
        } catch (Throwable failure) {
            ChimeraMod.LOGGER.error("[chimera] final cleanup of the idle Chimera pass failed", failure);
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
        if (!chimeraPass.queuePackChange(path, false)) {
            return PackRequestResult.ALREADY_ACTIVE;
        }
        chimeraEnabled = true;
        return PackRequestResult.QUEUED;
    }

    public static PackRequestResult reloadPack() {
        if (!ready || chimeraPass == null) {
            return PackRequestResult.NOT_READY;
        }
        if (!chimeraPass.queuePackReload()) {
            return PackRequestResult.ALREADY_ACTIVE;
        }
        chimeraEnabled = true;
        return PackRequestResult.QUEUED;
    }

    public static PackRequestResult disablePack() {
        return requestPack(null);
    }

    /** The pack rendering now or about to (a queued change wins); null for vanilla. */
    public static Path selectedPackPath() {
        return ready && chimeraPass != null ? chimeraPass.selectedPackPath() : null;
    }

    /** A loaded pack and the options it was loaded with. A new load gives a new instance. */
    public record LoadedPack(Path path, StagedOptions options) {}

    /** The pack loaded now, whatever is queued; null when none is. */
    public static LoadedPack loadedPack() {
        return ready && chimeraPass != null ? chimeraPass.loadedPack() : null;
    }

    public static String packStatus() {
        if (!ready || chimeraPass == null) {
            return "Chimera pack: renderer not ready";
        }
        String status = chimeraPass.packStatus() + ", renderer=" + (installed ? "chimera" : "vanilla");
        List<String> failures = PackFailureNotices.statusLines();
        return failures.isEmpty() ? status : status + "\nFailures this load:\n" + String.join("\n", failures);
    }

    /**
     * F3's lines below the system information, as Iris's MixinDebugScreenOverlay: the version, the
     * pack with whether it fell back or lost programs (PackFailureNotices.label), and its profile.
     */
    public static List<String> debugLines() {
        List<String> lines = new ArrayList<>(List.of("",
                "[Chimera] Version: " + ChimeraMod.version() + " (" + ChimeraMod.commit() + ")", ""));
        Path pack = selectedPackPath();
        if (pack == null) {
            lines.add("[Chimera] Shaders are disabled");
            return lines;
        }
        String name = PackSessionLifecycle.packLabel(pack);
        lines.add("[Chimera] Shaderpack: " + name + PackFailureNotices.label(name));
        LoadedPack loaded = loadedPack();
        if (loaded != null && loaded.path().equals(pack)) lines.add("[Chimera] " + loaded.options().profileInfo());
        return lines;
    }

    /**
     * F3's line below the game information, Iris's shadow lines in one (IrisRenderingPipeline and
     * ShadowRenderer.addDebugText): the map's size and distance, the sections kept of those that
     * cast (C), and the entities (E) and block entities (BE) drawn; or that the pack uses no shadow
     * map. Nothing while the world is not pack-rendered.
     */
    public static List<String> shadowDebugLines() {
        if (!packProjectionActive()) return List.of();
        ChimeraShadowMap shadowMap = chimeraPass.shadowPass.shadowMap;
        if (!chimeraPass.session.shadowUse.runs() || !shadowMap.isInitialized()) {
            return List.of("", "[Chimera] Shadows: not used by pack");
        }
        return List.of("", "[Chimera] Shadows: " + shadowMap.debugInfo() + ", C: "
                + ShadowSectionQueue.sectionCounts() + ", " + ChimeraShadowCasters.counts());
    }

    /**
     * Applies pending pack work before VulkanMod begins a new main command buffer. The frame records
     * after this, so a pack change's idle window ends here (HandleRetirement).
     */
    public static void beforeMainCommandBuffer() {
        if (!ready || chimeraPass == null) {
            return;
        }
        try {
            // VulkanMod copies RenderSystem's texture matrix into VRenderSystem.TextureMatrix only when it
            // is set or reset on the render thread, and allocates that copy uninitialized. Pack entity and
            // hand stages read it as gl_TextureMatrix[0], which Iris serves from RenderSystem.getTextureMatrix():
            // until the first glint draw Solas's hand sampled its skin through a zero matrix and drew black.
            VRenderSystem.setTextureMatrix(RenderSystem.getTextureMatrix());
            // Material companions are independent of shader-pack state: atlas
            // uploads arrive whether or not a pack is installed.
            chimeraPass.pumpMaterialMapBuilds();
            chimeraPass.resources.refreshWorldResourcesIfDue();
            ChimeraTerrainPipelines.rebuildingOnce(ChimeraRenderer::changePackAtFrameBoundary);
            // After any pack change above, so a replaced pack's teardown and install upload the atlas once.
            PackAtlasMipmaps.reconcile();
        } finally {
            HandleRetirement.recording();
        }
    }

    /** Hands a pending pack change or dimension variant to the host, applies it, and resumes Chimera. */
    private static void changePackAtFrameBoundary() {
        if (chimeraPass.hasPendingPackChange() && installed) {
            Renderer.getInstance().setMainPass(hostPass);
            ChimeraTerrainPipelines.disable();
            ChimeraEntityBridge.setEnabled(false);
            ChimeraSkyBridge.setEnabled(false);
            installed = false;
            resumeAfterVariant = chimeraEnabled;
            ChimeraMod.LOGGER.debug("[chimera] host renders during the pack change");
        }
        if (!chimeraPass.applyPendingPackVariantAtFrameBoundary()) {
            return;
        }
        boolean liveLevel = Minecraft.getInstance().level != null;
        // A first pack load from the host renderer installs too: with no
        // pack loaded Chimera never took the frame.
        boolean packArrived = !screenMode && chimeraPass.packLoaded();
        if (chimeraEnabled && (resumeAfterVariant || packArrived || (screenMode && liveLevel)) && !installed) {
            if (install()) {
                resumeAfterVariant = false;
                screenMode = false;
                ChimeraMod.LOGGER.debug("[chimera] resumed");
            }
        }
    }


    /** Chimera's world segments may run while its pass owns the frame. */
    public static boolean segmentsActive() {
        return installed && !screenMode;
    }

    /**
     * True when this frame's world is rendered for a loaded shader pack, which
     * reads gbufferProjection with the vanilla finite far plane (see
     * ChimeraProjectionFarMixin).
     */
    public static boolean packProjectionActive() {
        return segmentsActive() && chimeraPass != null && chimeraPass.packLoaded();
    }

    /** True while Chimera's shadow pass records (ChimeraShadowProjectionMixin). */
    public static boolean shadowPassActive() {
        return chimeraPass != null && chimeraPass.shadowPass.shadowPassActive;
    }

    /**
     * The screen overlay switches of the pack rendering this frame's world, or null when the
     * world is not pack-rendered and Minecraft's overlays draw as usual (Iris checks its active
     * pipeline the same way in MixinGui and MixinScreenEffectRenderer).
     */
    public static ScreenOverlayDirectives packScreenOverlays() {
        return packProjectionActive() ? chimeraPass.screenOverlays() : null;
    }

    /**
     * The sky switches of the pack rendering this frame's world, or null when the world is not
     * pack-rendered and Minecraft's whole sky draws (Iris MixinLevelRenderer_SunMoonToggle).
     */
    public static SkyDirectives packSkyDirectives() {
        return packProjectionActive() ? chimeraPass.skyDirectives() : null;
    }

    /**
     * The weather switch of the pack rendering this frame's world, or null when the world is not
     * pack-rendered and rain and snow draw as vanilla's (Iris MixinLevelRenderer checks its active
     * pipeline the same way).
     */
    public static WeatherDirectives packWeatherDirectives() {
        return packProjectionActive() ? chimeraPass.weatherDirectives() : null;
    }

    /**
     * Where the pack rendering this frame's world draws particles against its deferred passes, or
     * null when the world is not pack-rendered and NeoForge's split stands (Iris answers MIXED then).
     */
    public static ParticleDirectives.Ordering packParticleOrdering() {
        return packProjectionActive() ? chimeraPass.packPlan.particleDirectives().ordering() : null;
    }

    /** The installed pack's separateEntityDraws, as Iris's batched entity rendering reads it; false with no pack. */
    public static boolean packSeparatesEntityDraws() {
        return packProjectionActive() && chimeraPass.packPlan.particleDirectives().separateEntityDraws();
    }

    /**
     * Opens Chimera's level segment only on a recording main command buffer.
     * A nested Minecraft.runTick (the respawn loading screen) makes VulkanMod
     * submit the outer frame before that frame renders its level. VulkanMod's
     * own passes resume through Renderer.beginRenderPass; Chimera records raw
     * commands, so it resumes the same way before its first barrier. The new
     * frame boundary may hand the frame to the host, so re-check ownership.
     */
    public static boolean beginLevelSegments() {
        if (!segmentsActive()) {
            return false;
        }
        if (!Renderer.isRecording()) {
            ChimeraMod.LOGGER.debug("[chimera] level segment: resuming a frame a nested tick submitted");
            Renderer.getInstance().beginFrame();
        }
        return segmentsActive();
    }

    public static ChimeraMainPass getMainPass() {
        return chimeraPass;
    }

    private static boolean install() {
        if (!ready || installed) {
            return installed;
        }
        if (!chimeraPass.packLoaded()) {
            // No shader pack: the frame stays vanilla, as it does in Iris.
            // Chimera's own terrain shading and shadow map are not applied.
            if (!noPackLogged) {
                noPackLogged = true;
                ChimeraMod.LOGGER.info("[chimera] Shaders are disabled");
            }
            resumeAfterVariant = false;
            return false;
        }

        if (!chimeraPass.prepareForInstall()) {
            // An install can be requested while the current command buffer is
            // still recording. Keep the request alive so the pre-command-buffer
            // hook retries it at the next safe boundary.
            resumeAfterVariant = true;
            ChimeraMod.LOGGER.debug("[chimera] install deferred to the next frame");
            return false;
        }
        Renderer.getInstance().setMainPass(chimeraPass);
        ChimeraTerrainPipelines.enable();
        ChimeraEntityBridge.setEnabled(!chimeraPass.packRuntimeRejected() && ChimeraEntityBridge.isInstalled());
        ChimeraSkyBridge.setEnabled(!chimeraPass.packRuntimeRejected() && ChimeraSkyBridge.isInstalled());
        installed = true;
        noPackLogged = false;
        ChimeraMod.LOGGER.debug("[chimera] installed");
        return true;
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
        ChimeraMod.LOGGER.warn("[chimera] pack variant failed; rendering vanilla");
    }

    public static void enterScreenMode() {
        if (Minecraft.getInstance().level != null) {
            // Parity over a live level: PostPassM ends any open pass before
            // its barriers, Renderer's endRenderPass is null-safe when nothing
            // is recording, and encoder draws targeting the main RT rebind our
            // aux pass - the blur chain composes with the live frame.
            ChimeraMod.LOGGER.debug("[chimera] screen open; rendering continues");
            return;
        }

        // No live level (loading/transition screens): the teardown/swap churn
        // around those frames crashed the segmented frame's image transitions
        // - use the proven host path instead.
        if (!installed) {
            return;
        }
        legacyUnhand();
        ChimeraMod.LOGGER.debug("[chimera] screen without a level; host renders until it closes");
    }

    public static void exitScreenMode() {
        if (!screenMode) {
            return;
        }
        ChimeraMod.LOGGER.debug("[chimera] screen closed; resuming at the next frame boundary");
    }

    /** Level went away mid-screen: host takes over until a live level returns. */
    public static void onLevelUnloaded() {
        ShadowSectionQueue.release();
        if (!screenOpen || screenMode || !installed) {
            return;
        }

        legacyUnhand();
        ChimeraMod.LOGGER.debug("[chimera] level unloaded; host renders");
    }

    /** Live level returned while a screen is still up: restore parity behind it. */
    public static void onLevelLoaded() {
        onLevelLoaded(Minecraft.getInstance().level);
    }

    /** Re-selects the pack variant before applying the screen lifecycle state. */
    public static void onLevelLoaded(ClientLevel level) {
        ShadowSectionQueue.release();
        if (chimeraPass != null && level != null) {
            chimeraPass.onLevelChanged(level.dimension().location().toString());
        }
        if (!screenOpen || !screenMode) {
            return;
        }

        ChimeraMod.LOGGER.debug("[chimera] level loaded; resuming at the next frame boundary");
    }
}
