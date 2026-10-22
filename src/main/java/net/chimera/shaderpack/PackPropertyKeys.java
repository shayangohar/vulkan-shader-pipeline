package net.chimera.shaderpack;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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
     * Keys Chimera reads: clouds (PackPlan), shadow.culling (ShadowCullingPlan), the hand-light,
     * terrain-shading, shadow-caster, sky and screen-overlay directives, and the option menu's
     * screen and sliders. The source consts it also reads here are PackConfig's, which reports
     * the ones it cannot apply (KNOW-813).
     */
    private static final Set<String> APPLIED_NAMES = Set.of(
            "clouds", "shadow.culling", "oldHandLight", "oldLighting", "separateAo", "shadowTerrain",
            "shadowTranslucent", "shadowEntities", "shadowPlayer", "shadowBlockEntities",
            "shadowLightBlockEntities", "underwaterOverlay", "vignette", "sun", "moon", "stars", "sky",
            "screen", "sliders", "sunPathRotation", "sunPathOffset", "shadowMapResolution", "shadowDistance",
            "shadowMapSize", "shadowMapFov", "shadowDistanceRenderMul");
    private static final Pattern APPLIED_TARGET_CONST = Pattern.compile(
            "colortex\\d+(?:Format|Clear|ClearColor|MipmapEnabled)");

    /** ShaderProperties keys Iris 1.21.1 reads that Chimera does not apply. */
    private static final Set<String> IRIS_ONLY_NAMES = Set.of(
            "dhClouds", "dynamicHandLight", "backFace.solid", "backFace.cutout", "backFace.cutoutMipped",
            "backFace.translucent", "rain.depth", "allowConcurrentCompute", "beacon.beam.depth",
            "voxelizeLightBlocks", "separateEntityDraws", "frustum.culling", "occlusion.culling",
            "shadow.enabled", "skipAllRendering", "dhShadow.enabled", "prepareBeforeShadow",
            "supportsColorCorrection", "fallbackTex", "weather");
    private static final List<String> IRIS_ONLY_PREFIXES = List.of("scale.");

    private PackPropertyKeys() {}

    /** Why a key Chimera does not apply is recorded: SETTING_UNSUPPORTED or SETTING_UNKNOWN; null if it applies. */
    static String unread(String key) {
        if (APPLIED_PREFIXES.stream().anyMatch(key::startsWith) || APPLIED_NAMES.contains(key)
                || APPLIED_TARGET_CONST.matcher(key).matches()) {
            return null;
        }
        return IRIS_ONLY_NAMES.contains(key) || IRIS_ONLY_PREFIXES.stream().anyMatch(key::startsWith)
                ? "SETTING_UNSUPPORTED" : "SETTING_UNKNOWN";
    }
}
