package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shaders.properties {@code oldLighting} switch, read as Iris ShaderProperties reads it:
 * "true" or "1" keeps Minecraft's directional face shading, "false" or "0" removes it, and any
 * other value keeps Iris's default, off (PackDirectives: {@code getOldLighting().orElse(false)}).
 *
 * <p>Without old lighting, Iris meshes terrain and fluids unshaded (ClientLevel.getShade sees
 * {@code shaded = false}), because the pack lights faces itself; the vanilla shade would darken
 * every side face and underside a second time.</p>
 */
public record OldLightingDirective(boolean oldLighting, List<String> deviations) {
    public static final OldLightingDirective DEFAULT = new OldLightingDirective(false, List.of());

    public OldLightingDirective {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    static OldLightingDirective fromProperties(Map<String, String> properties) {
        String value = properties == null ? null : properties.get("oldLighting");
        if (value == null) return DEFAULT;
        return switch (value.trim()) {
            case "true", "1" -> new OldLightingDirective(true, List.of());
            case "false", "0" -> DEFAULT;
            default -> {
                List<String> deviations = new ArrayList<>();
                deviations.add("OLD_LIGHTING_DIRECTIVE_MALFORMED");
                yield new OldLightingDirective(DEFAULT.oldLighting(), deviations);
            }
        };
    }

    /** True when the pack lights faces itself, so the host's directional shade must not apply. */
    public boolean disablesDirectionalShading() {
        return !oldLighting;
    }
}
