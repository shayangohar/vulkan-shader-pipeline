package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;

/**
 * One shaders.properties boolean switch, read as Iris ShaderProperties.handleBooleanDirective
 * reads it: "true" or "1" switches on, "false" or "0" switches off, and any other value keeps
 * the directive's default and is reported (Iris logs a warning and leaves it unset).
 */
final class PackBooleanDirective {
    private PackBooleanDirective() {}

    /** The switch's value, or {@code fallback} when absent or malformed; a malformed value adds {@code malformed}. */
    static boolean read(Map<String, String> properties, String key, boolean fallback,
                        List<String> deviations, String malformed) {
        String value = properties == null ? null : properties.get(key);
        if (value == null) return fallback;
        return switch (value.trim()) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> {
                deviations.add(malformed);
                yield fallback;
            }
        };
    }
}
