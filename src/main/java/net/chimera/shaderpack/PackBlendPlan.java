package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable blend directives for one program, as Iris reads them from
 * shaders.properties: {@code blend.<program>} replaces the draw's blend for
 * every buffer, and {@code blend.<program>.<buffer>} replaces it for one
 * colour target. Each value is {@code off} or four OpenGL factor names
 * (source colour, destination colour, source alpha, destination alpha).
 *
 * <p>A target with no directive keeps the host draw's own blend, which is the
 * vanilla render type's blend Iris also leaves in place. A malformed
 * directive is ignored with a named deviation, as Iris ignores it.</p>
 */
public record PackBlendPlan(Mode program, Map<Integer, Mode> buffers, List<String> deviations) {

    /** One attachment's blend: disabled, or enabled with Vulkan blend factors and ADD ops. */
    public record Mode(boolean enabled, int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
        public static final Mode OFF = new Mode(false, 0, 0, 0, 0);
    }

    // OpenGL factor names -> VkBlendFactor values.
    private static final Map<String, Integer> FACTORS = Map.ofEntries(
            Map.entry("ZERO", 0),
            Map.entry("ONE", 1),
            Map.entry("SRC_COLOR", 2),
            Map.entry("ONE_MINUS_SRC_COLOR", 3),
            Map.entry("DST_COLOR", 4),
            Map.entry("ONE_MINUS_DST_COLOR", 5),
            Map.entry("SRC_ALPHA", 6),
            Map.entry("ONE_MINUS_SRC_ALPHA", 7),
            Map.entry("DST_ALPHA", 8),
            Map.entry("ONE_MINUS_DST_ALPHA", 9),
            Map.entry("SRC_ALPHA_SATURATE", 14)
    );

    public PackBlendPlan {
        buffers = buffers == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(buffers));
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public static PackBlendPlan empty() {
        return new PackBlendPlan(null, Map.of(), List.of());
    }

    static PackBlendPlan forProgram(String programName, PackSettingsPlan settings) {
        if (programName == null || programName.isBlank() || settings == null) return empty();
        Map<String, String> properties = settings.propertyValues();
        String prefix = "blend." + programName;
        Mode program = null;
        Map<Integer, Mode> buffers = new TreeMap<>();
        List<String> deviations = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(properties).entrySet()) {
            String key = entry.getKey();
            if (!key.equals(prefix) && !key.startsWith(prefix + ".")) continue;
            Mode mode = parse(entry.getValue());
            if (mode == null) {
                deviations.add("BLEND_DIRECTIVE_MALFORMED:" + key);
                continue;
            }
            if (key.equals(prefix)) {
                program = mode;
                continue;
            }
            Integer target = PackResourcePlan.targetIndex(key.substring(prefix.length() + 1));
            if (target == null) {
                deviations.add("BLEND_DIRECTIVE_MALFORMED:" + key);
                continue;
            }
            buffers.put(target, mode);
        }
        if (program != null || !buffers.isEmpty()) deviations.add("BLEND_DIRECTIVE_APPLIED:" + programName);
        return new PackBlendPlan(program, buffers, deviations);
    }

    static Mode parse(String authored) {
        String value = authored == null ? "" : authored.trim();
        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false")) return Mode.OFF;
        String[] parts = value.split("\\s+");
        if (parts.length != 4) return null;
        int[] factors = new int[4];
        for (int i = 0; i < 4; i++) {
            String name = parts[i].toUpperCase(Locale.ROOT);
            if (name.startsWith("GL_")) name = name.substring(3);
            Integer factor = FACTORS.get(name);
            if (factor == null) return null;
            factors[i] = factor;
        }
        return new Mode(true, factors[0], factors[1], factors[2], factors[3]);
    }

    public boolean overridesAnything() {
        return program != null || !buffers.isEmpty();
    }

    /**
     * Per-attachment modes for the given output order; a null entry keeps
     * the host draw's blend. Returns null when nothing is overridden.
     */
    public Mode[] attachments(List<Integer> targets) {
        if (!overridesAnything() || targets == null) return null;
        Mode[] result = new Mode[targets.size()];
        for (int i = 0; i < result.length; i++) {
            Mode buffer = buffers.get(targets.get(i));
            result[i] = buffer != null ? buffer : program;
        }
        return result;
    }
}
