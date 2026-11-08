package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;

/**
 * Iris's pack-wide scalar directives (PackDirectives.acceptDirectivesFrom) with its defaults, read
 * as const directives in IrisConstDirectives' order. Iris's drynessHalflife directive writes the
 * wetness half-life, so the dryness half-life always keeps its default 200, and a pack declaring
 * both gets whichever it declares last as its wetness half-life. ambientOcclusionLevel is clamped to
 * [0, 1] as Iris clamps it.
 */
public record PackDirectives(int noiseTextureResolution, float wetnessHalfLife, float drynessHalfLife,
                             float eyeBrightnessHalfLife, float centerDepthHalfLife, float ambientOcclusionLevel) {
    public static final PackDirectives DEFAULTS = new PackDirectives(256, 600.0F, 200.0F, 10.0F, 1.0F, 1.0F);

    /** Each name this record holds, with the type Iris reads it as. */
    private static final Map<String, String> TYPES = Map.of("noiseTextureResolution", "int",
            "wetnessHalflife", "float", "drynessHalflife", "float", "eyeBrightnessHalflife", "float",
            "centerDepthHalflife", "float", "ambientOcclusionLevel", "float");

    /** The values {@code directives}, in order, declare; a directive of another type or value is ignored. */
    static PackDirectives read(List<IrisConstDirectives.Directive> directives) {
        int noise = DEFAULTS.noiseTextureResolution;
        float wetness = DEFAULTS.wetnessHalfLife;
        float eyeBrightness = DEFAULTS.eyeBrightnessHalfLife;
        float centerDepth = DEFAULTS.centerDepthHalfLife;
        float occlusion = DEFAULTS.ambientOcclusionLevel;
        for (IrisConstDirectives.Directive directive : directives) {
            if (!directive.type().equals(TYPES.get(directive.name())) || !directive.numeric()) continue;
            switch (directive.name()) {
                case "noiseTextureResolution" -> noise = Integer.parseInt(directive.value());
                case "wetnessHalflife", "drynessHalflife" -> wetness = Float.parseFloat(directive.value());
                case "eyeBrightnessHalflife" -> eyeBrightness = Float.parseFloat(directive.value());
                case "centerDepthHalflife" -> centerDepth = Float.parseFloat(directive.value());
                default -> occlusion = Math.clamp(Float.parseFloat(directive.value()), 0.0F, 1.0F);
            }
        }
        return new PackDirectives(noise, wetness, DEFAULTS.drynessHalfLife, eyeBrightness, centerDepth, occlusion);
    }
}
