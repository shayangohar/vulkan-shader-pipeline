package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shaders.properties switches for Minecraft's screen overlays, read as Iris reads them:
 * {@code vignette} keeps the HUD vignette and {@code underwaterOverlay} keeps the underwater
 * texture overlay. Both default to off (PackDirectives: {@code getVignette().orElse(false)},
 * {@code getUnderwaterOverlay().orElse(false)}), so while a pack renders the world Iris skips
 * Gui.renderVignette and ScreenEffectRenderer.renderWater unless the pack asks for them.
 */
public record ScreenOverlayDirectives(boolean vignette, boolean underwaterOverlay, List<String> deviations) {
    public static final ScreenOverlayDirectives DEFAULTS = new ScreenOverlayDirectives(false, false, List.of());

    public ScreenOverlayDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    static ScreenOverlayDirectives fromProperties(Map<String, String> properties) {
        List<String> deviations = new ArrayList<>();
        return new ScreenOverlayDirectives(
                flag(properties, "vignette", DEFAULTS.vignette(), deviations),
                flag(properties, "underwaterOverlay", DEFAULTS.underwaterOverlay(), deviations),
                deviations);
    }

    private static boolean flag(Map<String, String> properties, String key, boolean fallback,
                                List<String> deviations) {
        return PackBooleanDirective.read(properties, key, fallback, deviations,
                "SCREEN_OVERLAY_DIRECTIVE_MALFORMED:" + key);
    }
}
