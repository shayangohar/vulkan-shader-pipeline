package net.chimera.shaderpack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The canonical uniform catalog: every name Chimera serves, its types, source and default. */
final class UniformCatalog {
    private UniformCatalog() {}

    static Map<String, UniformRegistry.UniformDescriptor> uniformSpecs() {
        Map<String, UniformRegistry.UniformDescriptor> specs = new LinkedHashMap<>();
        addWorldAndCameraUniforms(specs);
        addIrisStandardUniforms(specs);
        addIrisPlayerState(specs);
        addHostShaderUniforms(specs);
        return Map.copyOf(specs);
    }

    /** The OptiFine world, weather, camera, sky, shadow and frame uniforms. */
    static void addWorldAndCameraUniforms(Map<String, UniformRegistry.UniformDescriptor> specs) {
        addLive(specs, "cameraPosition", "vec3");
        addLive(specs, "worldTime", "int");
        addLive(specs, "frameTimeCounter", "float");
        addLive(specs, "rainStrength", "float");
        addLive(specs, "thunderStrength", "float");
        addLive(specs, "isEyeInWater", "int");
        addLive(specs, "moonPhase", "int");
        addLive(specs, "sunPosition", "vec3");
        addLive(specs, "moonPosition", "vec3");
        addLive(specs, "shadowLightPosition", "vec3");
        addLive(specs, "shadowModelView", "mat4");
        addLive(specs, "shadowProjection", "mat4");
        addLive(specs, "viewWidth", "float");
        addLive(specs, "viewHeight", "float");
        addLive(specs, "aspectRatio", "float");
        addLive(specs, "near", "float");
        addLive(specs, "far", "float");
        addLive(specs, "wetness", "float");
        addLive(specs, "sunAngle", "float");
        addLive(specs, "shadowAngle", "float");
        addLive(specs, "upPosition", "vec3");
        addLive(specs, "skyColor", "vec3");
        addLive(specs, "frameCounter", "int");
        addLive(specs, "cameraPositionInt", "ivec3");
        addLive(specs, "previousCameraPositionInt", "ivec3");
        addLive(specs, "cameraPositionFract", "vec3");
        addLive(specs, "previousCameraPositionFract", "vec3");
        addLive(specs, "previousCameraPosition", "vec3");
        addLive(specs, "cloudHeight", "float");
    }

    /**
     * Common standard Iris values used by real legacy post sources. The
     * provider supplies live values where Chimera has a source of truth;
     * the remaining values are explicit zero or identity defaults.
     */
    static void addIrisStandardUniforms(Map<String, UniformRegistry.UniformDescriptor> specs) {
        addLive(specs, "bedrockLevel", "int");
        addLive(specs, "blindness", "float", "blindness");
        addLive(specs, "darknessFactor", "float");
        addLive(specs, "darknessLightFactor", "float");
        addLive(specs, "frameTime", "float");
        addLive(specs, "nightVision", "float");
        addLive(specs, "screenBrightness", "float");
        addDefault(specs, "velocity", "float");
        addLive(specs, "worldDay", "int");
        addLive(specs, "atlasSize", "ivec2");
        addLive(specs, "eyeBrightness", "ivec2");
        addLive(specs, "eyeBrightnessSmooth", "ivec2");
        addLive(specs, "eyeAltitude", "float");
        addLive(specs, "eyePosition", "vec3");
        addLive(specs, "playerLookVector", "vec3");
        addLive(specs, "relativeEyePosition", "vec3");
        addLive(specs, "biome", "int");
        addLive(specs, "biome_category", "int");
        addLive(specs, "biome_precipitation", "int");
        addLive(specs, "dimension", "int");
        addLive(specs, "heightLimit", "int");
        addLive(specs, "logicalHeightLimit", "int");
        // Iris serves these through uniform1b; packs declare them bool or int.
        addLive(specs, "hasCeiling", List.of("int", "bool"), "hasCeiling");
        addLive(specs, "hasSkylight", List.of("int", "bool"), "hasSkylight");
        addLive(specs, "ambientLight", "float");
        addLive(specs, "temperature", "float");
        addLive(specs, "rainfall", "float");
        addDefault(specs, "centerDepthSmooth", "float");
        addDefault(specs, "entityColor", "vec4");
        addLive(specs, "lightningBoltPosition", "vec4");
        addDefault(specs, "blockEntityId", "int");
        addDefault(specs, "currentRenderedItemId", "int");
        addDefault(specs, "entityId", "int");
        // IdMapUniforms: each hand's item.properties id, its light and that light's colour.
        addLive(specs, "heldBlockLightValue", "int");
        addLive(specs, "heldBlockLightValue2", "int");
        addLive(specs, "heldItemId", "int");
        addLive(specs, "heldItemId2", "int");
        addLive(specs, "heldBlockLightColor", "vec3");
        addLive(specs, "heldBlockLightColor2", "vec3");
        addLive(specs, "gbufferModelView", "mat4");
        addLive(specs, "gbufferModelViewInverse", "mat4");
        addLive(specs, "gbufferPreviousModelView", "mat4");
        addLive(specs, "gbufferPreviousProjection", "mat4");
        addLive(specs, "gbufferProjection", "mat4");
        addLive(specs, "gbufferProjectionInverse", "mat4");
        addLive(specs, "shadowModelViewInverse", "mat4");
        addLive(specs, "shadowProjectionInverse", "mat4");
        addLive(specs, "renderStage", "int");
    }

    /**
     * The host's core-shader uniforms (MVP, the Fog block, ScreenSize and the rest), then the
     * lowercase spellings of the fog and size ones.
     */
    static void addHostShaderUniforms(Map<String, UniformRegistry.UniformDescriptor> specs) {
        addLive(specs, "MVP", "mat4");
        addLive(specs, "ModelViewMat", "mat4");
        addLive(specs, "ProjMat", "mat4");
        addLive(specs, "TextureMat", "mat4");
        addLive(specs, "FogColor", "vec4");
        addLive(specs, "FogStart", "float");
        addLive(specs, "FogEnd", "float");
        addLive(specs, "AlphaCutout", "float");
        addLive(specs, UniformRegistry.ENTITY_ALPHA_REFERENCE, "float");
        addLive(specs, UniformRegistry.GLINT_ALPHA, "float");
        addLive(specs, UniformRegistry.HOST_ENTITY_ID, "int");
        addLive(specs, "ScreenSize", "vec2");
        addLive(specs, "Light0_Direction", "vec3");
        addLive(specs, "Light1_Direction", "vec3");
        addLive(specs, "ColorModulator", "vec4");
        addLive(specs, "LineWidth", "float");
        addLive(specs, "ChunkOffset", "vec3");
        addLive(specs, "EndPortalLayers", "int");

        addLive(specs, "fogColor", List.of("vec3", "vec4"), "fogColor");
        addLive(specs, "fogStart", "float");
        addLive(specs, "fogEnd", "float");
        addLive(specs, "screenSize", "vec2");
    }

    /**
     * Iris's player, HUD, clock and Distant Horizons uniforms (see IrisPlayerState).
     * Booleans take bool or int, as Iris's uniform1b serves either.
     */
    static void addIrisPlayerState(Map<String, UniformRegistry.UniformDescriptor> specs) {
        for (String flag : List.of("hideGUI", "isRightHanded", "firstPersonCamera", "isSpectator",
                "is_burning", "is_hurt", "is_invisible", "is_on_ground", "is_sneaking", "is_sprinting", "is_alive")) {
            addLive(specs, flag, List.of("bool", "int"), flag);
        }
        for (String value : List.of("currentPlayerHealth", "maxPlayerHealth", "currentPlayerHunger",
                "maxPlayerHunger", "currentPlayerArmor", "maxPlayerArmor", "currentPlayerAir",
                "maxPlayerAir", "playerMood", "pi", "lastFrameTime", "dhNearPlane", "dhFarPlane")) {
            addLive(specs, value, "float");
        }
        for (String value : List.of("currentColorSpace", "dhRenderDistance")) {
            addLive(specs, value, "int");
        }
        addLive(specs, "playerBodyVector", "vec3");
        addLive(specs, "currentDate", "ivec3");
        addLive(specs, "currentTime", "ivec3");
        addLive(specs, "currentYearTime", "ivec2");
        addLive(specs, "dhProjection", "mat4");
        addLive(specs, "dhProjectionInverse", "mat4");
        addLive(specs, "dhPreviousProjection", "mat4");
    }

    static void addLive(Map<String, UniformRegistry.UniformDescriptor> specs, String name, String type) {
        addLive(specs, name, List.of(type), name);
    }

    static void addLive(Map<String, UniformRegistry.UniformDescriptor> specs, String name,
                                String type, String sourceKey) {
        addLive(specs, name, List.of(type), sourceKey);
    }

    static void addLive(Map<String, UniformRegistry.UniformDescriptor> specs, String name,
                                List<String> types, String sourceKey) {
        specs.put(name, new UniformRegistry.UniformDescriptor(name, types, UniformRegistry.Availability.LIVE,
                sourceKey, UniformRegistry.DefaultPolicy.ZERO));
    }

    static void addDefault(Map<String, UniformRegistry.UniformDescriptor> specs, String name, String type) {
        addDefault(specs, name, type, UniformRegistry.DefaultPolicy.ZERO);
    }

    static void addDefault(Map<String, UniformRegistry.UniformDescriptor> specs, String name,
                                   String type, UniformRegistry.DefaultPolicy policy) {
        specs.put(name,
                new UniformRegistry.UniformDescriptor(name, List.of(type), UniformRegistry.Availability.DEFAULTED,
                name, policy));
    }
}
