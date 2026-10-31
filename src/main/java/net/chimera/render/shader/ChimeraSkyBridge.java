package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.chimera.shaderpack.PackPipelines;
import net.minecraft.client.CloudStatus;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

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

    /**
     * True inside LevelRenderer.renderSky, where Iris draws position_tex and position_tex_color with
     * its sky keys rather than TEXTURED (MixinGameRenderer checks isSky first).
     */
    public static boolean inSkyWindow() {
        return skyWindow;
    }

    /**
     * True for a position-shader draw in the sky window (the sky disc, the dark disc) when the pack
     * serves no gbuffers_skybasic: Iris then draws SKY_BASIC with its fallback program, which
     * writes the sky colour unfogged (FogMode.OFF), where the host's position shader fogs it.
     */
    public static boolean drawsUnfoggedSky(String shader) {
        return skyWindow && !hasBasicSky() && "position".equals(shader);
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

    /** Opens a sky or cloud draw, with the sky's per-draw alpha reference (skyAlphaReference). */
    public static void beginDraw(PackPipelines.PackSky program) {
        active = program;
        VertexFormat format = program.vertexFormat();
        boolean basicSky = skyBasic != null && program == skyBasic.forFormat(format);
        PackUniformProvider.updateDrawAlphaReference(skyAlphaReference(basicSky, format));
    }

    /**
     * The reference a sky draw tests against: a gbuffers_skybasic draw on POSITION_COLOR tests
     * Iris's SKY_BASIC_COLOR NON_ZERO_ALPHA and one on POSITION nothing (SKY_BASIC). A program the
     * pack gives an alphaTest reads its own reference instead (PackAlphaTestPlan).
     */
    public static float skyAlphaReference(boolean basicSky, VertexFormat format) {
        return basicSky && format == DefaultVertexFormat.POSITION_COLOR ? HostAlphaTest.NON_ZERO_ALPHA : 0.0F;
    }

    public static void endDraw() {
        active = null;
    }

    /**
     * Binds Iris's level samplers for the open sky or cloud draw (IrisSamplers.addLevelSamplers): a
     * format without texture coordinates (the sky disc, stars and sunrise fan) reads Iris's white
     * pixel as its albedo, and one without light coordinates, as every sky and cloud key's is, reads
     * it as lightmap. Returns units 0 and 2 as they were, for restoreLevelSamplers.
     */
    public static VulkanImage[] bindLevelSamplers() {
        VulkanImage[] bound = {VTextureSelector.getBoundTexture(0), VTextureSelector.getBoundTexture(2)};
        VertexFormat format = active == null ? null : active.vertexFormat();
        if (format == null) return bound;
        if (whiteAlbedo(format)) VTextureSelector.bindTexture(0, VTextureSelector.getWhiteTexture());
        if (whiteLightmap(format)) VTextureSelector.bindTexture(2, VTextureSelector.getWhiteTexture());
        return bound;
    }

    public static void restoreLevelSamplers(VulkanImage[] bound) {
        VTextureSelector.bindTexture(0, bound[0]);
        VTextureSelector.bindTexture(2, bound[1]);
    }

    public static boolean whiteAlbedo(VertexFormat format) {
        return !format.contains(VertexFormatElement.UV0);
    }

    public static boolean whiteLightmap(VertexFormat format) {
        return !format.contains(VertexFormatElement.UV2);
    }

    /** The open draw's pipeline, or null when no sky or cloud draw is open. */
    public static GraphicsPipeline pipeline() {
        return active == null ? null : active.pipeline();
    }
}
