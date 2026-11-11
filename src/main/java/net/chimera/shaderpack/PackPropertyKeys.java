package net.chimera.shaderpack;

import java.util.List;
import java.util.Set;

/**
 * What Chimera does with each shaders.properties key, against what Iris 1.21.1's ShaderProperties
 * reads. A key Chimera applies is named by the reader that takes it; one Iris reads and Chimera
 * does not is unsupported; one Iris does not read either is unknown, and both ignore it.
 */
final class PackPropertyKeys {
    /** Prefixes Chimera reads: settings, resources, programs, directives and the option menu. */
    private static final List<String> APPLIED_PREFIXES = List.of(
            "profile.", "program.", "iris.features.", "size.buffer.", "flip.", "customTexture.", "texture.",
            "alphaTest.", "blend.", "indirect.", "image.", "bufferObject.", "uniform.", "variable.", "screen.",
            "particles.");

    /**
     * Keys Chimera reads: clouds (PackPlan), shadow.culling (ShadowCullingPlan), shadow.enabled
     * (ShadowPassUse), the hand-light,
     * terrain-shading (voxelizeLightBlocks included), shadow-caster, sky, screen-overlay and weather
     * (rain.depth) directives, separateEntityDraws (ParticleDirectives), supportsColorCorrection
     * (the COLOR_SPACE_* defines, PackSettingsPlan.preprocessorDefines), and the option menu's
     * screen and sliders. A source const's name (colortexNFormat, shadowMapResolution) is no key
     * here: neither reader takes it from this file.
     */
    private static final Set<String> APPLIED_NAMES = Set.of(
            "clouds", "shadow.culling", "shadow.enabled", "oldHandLight", "oldLighting", "separateAo", "shadowTerrain",
            "shadowTranslucent", "shadowEntities", "shadowPlayer", "shadowBlockEntities",
            "shadowLightBlockEntities", "underwaterOverlay", "vignette", "sun", "moon", "stars", "sky",
            "supportsColorCorrection", "voxelizeLightBlocks", "separateEntityDraws", "rain.depth", "screen",
            "sliders");

    /** ShaderProperties keys Iris 1.21.1 reads that Chimera does not apply. */
    private static final Set<String> IRIS_ONLY_NAMES = Set.of(
            "dhClouds", "dynamicHandLight", "backFace.solid", "backFace.cutout", "backFace.cutoutMipped",
            "backFace.translucent", "allowConcurrentCompute", "beacon.beam.depth",
            "frustum.culling", "occlusion.culling", "skipAllRendering", "dhShadow.enabled",
            "prepareBeforeShadow", "fallbackTex", "weather");
    private static final List<String> IRIS_ONLY_PREFIXES = List.of("scale.");

    private PackPropertyKeys() {}

    /** Why a key Chimera does not apply is recorded: SETTING_UNSUPPORTED or SETTING_UNKNOWN; null if it applies. */
    static String unread(String key) {
        if (APPLIED_PREFIXES.stream().anyMatch(key::startsWith) || APPLIED_NAMES.contains(key)) {
            return null;
        }
        return IRIS_ONLY_NAMES.contains(key) || IRIS_ONLY_PREFIXES.stream().anyMatch(key::startsWith)
                ? "SETTING_UNSUPPORTED" : "SETTING_UNKNOWN";
    }
}
