package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shaders.properties switches that choose which entities cast pack shadows, read as
 * Iris ShaderProperties reads them: "true" or "1" switches on, "false" or "0" switches off,
 * and any other value keeps Iris's default (PackShadowDirectives: entities and block
 * entities on, the player alone and light-emitting block entities alone off).
 *
 * <p>Iris draws the player through the ordinary entity list; {@code shadowPlayer} only adds
 * the player (and its vehicle) when {@code shadowEntities} is off. Likewise
 * {@code shadowLightBlockEntities} only matters when {@code shadowBlockEntities} is off.</p>
 */
public record ShadowCasterDirectives(
        boolean entities,
        boolean player,
        boolean blockEntities,
        boolean lightBlockEntities,
        List<String> deviations
) {
    public static final ShadowCasterDirectives DEFAULTS =
            new ShadowCasterDirectives(true, false, true, false, List.of());

    public ShadowCasterDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    static ShadowCasterDirectives fromProperties(Map<String, String> properties) {
        List<String> deviations = new ArrayList<>();
        return new ShadowCasterDirectives(
                flag(properties, "shadowEntities", DEFAULTS.entities(), deviations),
                flag(properties, "shadowPlayer", DEFAULTS.player(), deviations),
                flag(properties, "shadowBlockEntities", DEFAULTS.blockEntities(), deviations),
                flag(properties, "shadowLightBlockEntities", DEFAULTS.lightBlockEntities(), deviations),
                deviations);
    }

    /** True when some entity reaches the shadow pass. */
    public boolean anyEntities() {
        return entities || player;
    }

    /** True when some block entity reaches the shadow pass. */
    public boolean anyBlockEntities() {
        return blockEntities || lightBlockEntities;
    }

    private static boolean flag(Map<String, String> properties, String key, boolean fallback,
                                List<String> deviations) {
        return PackBooleanDirective.read(properties, key, fallback, deviations,
                "SHADOW_DIRECTIVE_MALFORMED:" + key);
    }
}
