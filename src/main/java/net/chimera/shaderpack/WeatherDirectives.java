package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shaders.properties switch for Minecraft's weather, read as Iris reads it: {@code rain.depth}
 * lets rain and snow write depth, so the passes after them see the weather in depthtex0. It
 * defaults to off (PackDirectives: {@code getRainDepth().orElse(false)}). Iris's MixinLevelRenderer
 * applies it by forcing renderSnowAndRain's first RenderSystem.depthMask to true. Pegasus 0.5 and
 * RenderPearl 2.4.0 turn it on.
 */
public record WeatherDirectives(boolean rainDepth, List<String> deviations) {
    public static final WeatherDirectives DEFAULTS = new WeatherDirectives(false, List.of());

    public WeatherDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    static WeatherDirectives fromProperties(Map<String, String> properties) {
        List<String> deviations = new ArrayList<>();
        boolean rainDepth = PackBooleanDirective.read(properties, "rain.depth", DEFAULTS.rainDepth(), deviations,
                "WEATHER_DIRECTIVE_MALFORMED:rain.depth");
        return new WeatherDirectives(rainDepth, deviations);
    }
}
