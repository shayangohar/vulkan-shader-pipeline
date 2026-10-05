package net.chimera.render.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.chimera.ChimeraMod;
import net.minecraft.client.renderer.RenderPipelines;
import net.chimera.shaderpack.PackPipelines;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

/**
 * Small host-aware bridge for sky and cloud passes. It deliberately selects
 * only the host pipelines whose topology and depth contract are known.
 */
public final class ChimeraSkyBridge {
    private static PackPipelines.PackSkyFamily skyBasic;
    private static PackPipelines.PackSkyFamily skyTextured;
    private static PackPipelines.PackSky clouds;
    private static boolean cloudsDrawNothing;
    private static PackPipelines.PackSky active;
    private static boolean enabled;
    private static boolean cloudTraceLogged;

    private ChimeraSkyBridge() {}

    public static void install(PackPipelines.PackSkyFamily basic,
                               PackPipelines.PackSkyFamily textured,
                               PackPipelines.PackSky cloud,
                               boolean authoredCloudsDrawNothing) {
        skyBasic = basic;
        skyTextured = textured;
        clouds = cloud;
        cloudsDrawNothing = authoredCloudsDrawNothing;
        active = null;
        enabled = false;
        cloudTraceLogged = false;
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
        cloudTraceLogged = false;
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

    public static net.chimera.shaderpack.GeometryOutputPlan outputPlan() {
        return active == null ? null : active.outputPlan();
    }

    public static boolean isCloudPipeline(net.vulkanmod.vulkan.shader.Pipeline pipeline) {
        return enabled && clouds != null && pipeline == clouds.pipeline();
    }

    /** Replaces VulkanMod's direct cloud pipeline bind while preserving its draw state. */
    public static GraphicsPipeline replaceCloudPipeline(GraphicsPipeline host) {
        if (!enabled || clouds == null || host == null
                || host != PipelineManager.getCloudsPipeline()) {
            return host;
        }
        active = clouds;
        if (!cloudTraceLogged) {
            cloudTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] cloud draw bridge active: host pipeline replaced with pack format={}",
                    clouds.vertexFormat());
        }
        return clouds.pipeline();
    }

    private static PackPipelines.PackSky pipelineFor(RenderPipeline host) {
        if (host == RenderPipelines.SKY || host == RenderPipelines.SUNRISE_SUNSET
                || host == RenderPipelines.STARS) {
            return skyBasic == null ? null : skyBasic.forFormat(host.getVertexFormat());
        }
        if (host == RenderPipelines.END_SKY || host == RenderPipelines.CELESTIAL) {
            return skyTextured == null ? null : skyTextured.forFormat(host.getVertexFormat());
        }
        if (host == RenderPipelines.CLOUDS || host == RenderPipelines.FLAT_CLOUDS) {
            return clouds != null && clouds.vertexFormat() == host.getVertexFormat()
                    ? clouds : null;
        }
        return null;
    }

}
