package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.shaderpack.PackPipelines;
import net.minecraft.client.CloudStatus;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

/**
 * The pack's sky and cloud programs, chosen per host draw as Iris 1.21.1 overrides the sky's
 * shaders: inside LevelRenderer.renderSky (the sky window) the position shaders draw with
 * gbuffers_skybasic and the textured ones with gbuffers_skytextured, and the cloud shader draws
 * with gbuffers_clouds. PackHostDraws opens and closes each draw.
 */
public final class ChimeraSkyBridge {
    private static PackPipelines.PackSkyFamily skyBasic;
    private static PackPipelines.PackSkyFamily skyTextured;
    private static PackPipelines.PackSky clouds;
    private static boolean cloudsDrawNothing;
    private static CloudStatus packCloudStatus;
    private static PackPipelines.PackSky active;
    private static boolean skyWindow;
    private static boolean enabled;

    private ChimeraSkyBridge() {}

    public static void install(PackPipelines.PackSkyFamily basic,
                               PackPipelines.PackSkyFamily textured,
                               PackPipelines.PackSky cloud,
                               boolean authoredCloudsDrawNothing,
                               String cloudSetting) {
        skyBasic = basic;
        skyTextured = textured;
        clouds = cloud;
        cloudsDrawNothing = authoredCloudsDrawNothing;
        packCloudStatus = cloudSetting == null ? null : switch (cloudSetting) {
            case "off" -> CloudStatus.OFF;
            case "fast" -> CloudStatus.FAST;
            case "fancy" -> CloudStatus.FANCY;
            default -> null;
        };
        active = null;
        enabled = false;
    }

    public static void setEnabled(boolean value) {
        enabled = value && isInstalled();
        if (!enabled) active = null;
    }

    public static void disable() {
        enabled = false;
        active = null;
        skyBasic = null;
        skyTextured = null;
        clouds = null;
        cloudsDrawNothing = false;
        packCloudStatus = null;
    }

    /**
     * The pack's clouds directive while its programs run, which replaces the player's cloud
     * setting; null otherwise, as Iris overrides it only while a pack pipeline is active.
     */
    public static CloudStatus packCloudStatus() {
        return enabled ? packCloudStatus : null;
    }

    public static boolean isInstalled() {
        return skyBasic != null || skyTextured != null || clouds != null || cloudsDrawNothing;
    }

    /** True when the active pack cloud program draws nothing, so the host clouds must not draw. */
    public static boolean skipHostClouds() {
        return enabled && cloudsDrawNothing;
    }

    public static boolean hasBasicSky() {
        return enabled && skyBasic != null;
    }

    /** Opens the sky window around LevelRenderer.renderSky. */
    public static void beginSky() {
        skyWindow = true;
    }

    public static void endSky() {
        skyWindow = false;
    }

    /** The pack program that draws a host mesh of {@code shader} on {@code format}, or null. */
    public static PackPipelines.PackSky programFor(String shader, VertexFormat format) {
        if (!enabled || shader == null) return null;
        if (shader.equals("rendertype_clouds")) {
            return clouds != null && clouds.vertexFormat() == format ? clouds : null;
        }
        if (!skyWindow) return null;
        PackPipelines.PackSkyFamily family = switch (shader) {
            case "position", "position_color" -> skyBasic;
            case "position_tex", "position_tex_color" -> skyTextured;
            default -> null;
        };
        return family == null ? null : family.forFormat(format);
    }

    public static void beginDraw(PackPipelines.PackSky program) {
        active = program;
    }

    public static void endDraw() {
        active = null;
    }

    /** The open draw's pipeline, or null when no sky or cloud draw is open. */
    public static GraphicsPipeline pipeline() {
        return active == null ? null : active.pipeline();
    }
}
