package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shaders.properties switches for the parts of Minecraft's sky, read as Iris ShaderProperties
 * reads them. All default to on (PackDirectives: {@code getSun().orElse(true)} and the rest).
 * Iris's MixinLevelRenderer_SunMoonToggle applies them in LevelRenderer.renderSky:
 * <ul>
 *   <li>{@code sun} and {@code moon}: the textured quad draws, or is dropped.</li>
 *   <li>{@code stars}: the star brightness reads below zero, so the star buffer never draws.</li>
 *   <li>{@code sky}: the sky disc, the sunrise fan and the dark disc below the horizon are dropped,
 *       and so is the horizon Iris draws ahead of the sky.</li>
 * </ul>
 * Bliss turns the stars off always, and the sun and moon off unless its resource-pack sky mode is on.
 */
public record SkyDirectives(boolean sun, boolean moon, boolean stars, boolean sky, List<String> deviations) {
    public static final SkyDirectives DEFAULTS = new SkyDirectives(true, true, true, true, List.of());

    public SkyDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    static SkyDirectives fromProperties(Map<String, String> properties) {
        List<String> deviations = new ArrayList<>();
        return new SkyDirectives(
                flag(properties, "sun", DEFAULTS.sun(), deviations),
                flag(properties, "moon", DEFAULTS.moon(), deviations),
                flag(properties, "stars", DEFAULTS.stars(), deviations),
                flag(properties, "sky", DEFAULTS.sky(), deviations),
                deviations);
    }

    private static boolean flag(Map<String, String> properties, String key, boolean fallback,
                                List<String> deviations) {
        return PackBooleanDirective.read(properties, key, fallback, deviations, "SKY_DIRECTIVE_MALFORMED:" + key);
    }
}
