package net.chimera.render.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.PackPipelines;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.RenderPipelines;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;

/**
 * Small host-aware bridge for sky and cloud passes. It deliberately selects
 * only the host pipelines whose topology and depth contract are known.
 */
public final class ChimeraSkyBridge {
    private static PackPipelines.PackSkyFamily skyBasic;
    private static PackPipelines.PackSkyFamily skyTextured;
    private static PackPipelines.PackSky clouds;
    private static boolean cloudsDrawNothing;
    private static CloudStatus packCloudStatus;
    private static PackPipelines.PackSky active;
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

    /** The installed pack's clouds directive, which replaces the player's cloud setting (Iris). */
    public static CloudStatus packCloudStatus() {
        return packCloudStatus;
    }

    public static boolean isInstalled() {
        return skyBasic != null || skyTextured != null || clouds != null || cloudsDrawNothing;
    }

    /**
     * True when the active pack cloud program draws nothing, so VulkanMod's
     * host cloud mesh must not be drawn in its place.
     */
    public static boolean skipHostClouds() {
        return enabled && cloudsDrawNothing;
    }

    public static boolean isDrawActive() {
        return enabled && active != null;
    }

    public static boolean shouldUsePackPipeline(RenderPipeline host) {
        if (!enabled || host == null) {
            return false;
        }
        active = pipelineFor(host);
        return active != null;
    }

    public static GraphicsPipeline pipeline() {
        return active == null ? null : active.pipeline();
    }

    public static boolean hasBasicSky() {
        return enabled && skyBasic != null;
    }

    public static GeometryOutputPlan outputPlan() {
        return active == null ? null : active.outputPlan();
    }

    public static boolean isCloudPipeline(Pipeline pipeline) {
        return enabled && clouds != null && pipeline == clouds.pipeline();
    }

    /** The pack cloud program PackCloudRenderer draws the clouds with, or null when none is installed. */
    public static PackPipelines.PackSky cloudPipeline() {
        return enabled ? clouds : null;
    }

    private static PackPipelines.PackSky pipelineFor(RenderPipeline host) {
        if (host == RenderPipelines.SKY || host == RenderPipelines.SUNRISE_SUNSET
                || host == RenderPipelines.STARS) {
            return skyBasic == null ? null : skyBasic.forFormat(host.getVertexFormat());
        }
        if (host == RenderPipelines.END_SKY || host == RenderPipelines.CELESTIAL) {
            return skyTextured == null ? null : skyTextured.forFormat(host.getVertexFormat());
        }
        return null;
    }

}
