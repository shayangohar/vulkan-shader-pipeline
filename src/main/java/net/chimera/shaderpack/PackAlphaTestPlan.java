package net.chimera.shaderpack;

import java.util.List;
import java.util.Locale;

/** Immutable load-time alpha-test contract for a geometry program. */
record PackAlphaTestPlan(
        Mode mode,
        String function,
        float reference,
        boolean configured,
        List<String> deviations
) {
    enum Mode {
        OFF,
        DYNAMIC_TERRAIN,
        FIXED
    }

    private static final List<String> FUNCTIONS = List.of(
            "NEVER", "LESS", "EQUAL", "LEQUAL", "GREATER", "NOTEQUAL", "GEQUAL", "ALWAYS");

    PackAlphaTestPlan {
        mode = mode == null ? Mode.OFF : mode;
        function = function == null ? "" : function;
        reference = Float.isFinite(reference) ? reference : 0.0F;
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    static PackAlphaTestPlan forProgram(String programName, PackSettingsPlan settings) {
        String name = programName == null ? "" : programName;
        boolean geometry = name.equals("gbuffers_terrain") || name.equals("gbuffers_water");
        if (!geometry) {
            return off(false);
        }
        String key = "alphaTest." + name;
        String value = settings == null ? null : settings.propertyValues().get(key);
        if (value == null) {
            return new PackAlphaTestPlan(
                    Mode.DYNAMIC_TERRAIN, "DYNAMIC", 0.0F, false, List.of());
        }
        return parse(name, value);
    }

    private static PackAlphaTestPlan parse(String programName, String authored) {
        String value = authored == null ? "" : authored.trim();
        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false")) {
            return new PackAlphaTestPlan(Mode.OFF, "OFF", 0.0F, true, List.of());
        }
        String[] parts = value.split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return invalid(programName, "empty");
        }
        String function = parts[0].toUpperCase(Locale.ROOT);
        if (function.startsWith("GL_")) {
            function = function.substring(3);
        }
        if (!FUNCTIONS.contains(function)) {
            return invalid(programName, function);
        }
        if (function.equals("ALWAYS") || function.equals("NEVER")) {
            if (parts.length > 2) return invalid(programName, value);
            return new PackAlphaTestPlan(Mode.FIXED, function, 0.0F, true, List.of());
        }
        if (parts.length != 2) {
            return invalid(programName, value);
        }
        try {
            String numeric = parts[1].replaceFirst("[fF]$", "");
            float reference = Float.parseFloat(numeric);
            if (!Float.isFinite(reference)) throw new NumberFormatException("non-finite");
            return new PackAlphaTestPlan(Mode.FIXED, function, reference, true, List.of());
        } catch (NumberFormatException failure) {
            return invalid(programName, value);
        }
    }

    private static PackAlphaTestPlan invalid(String programName, String value) {
        return new PackAlphaTestPlan(Mode.OFF, "", 0.0F, true,
                List.of("ALPHA_TEST_MALFORMED:" + programName + ":" + value));
    }

    private static PackAlphaTestPlan off(boolean configured) {
        return new PackAlphaTestPlan(Mode.OFF, "OFF", 0.0F, configured, List.of());
    }

    static PackAlphaTestPlan disabled() {
        return off(false);
    }

    boolean valid() {
        return deviations.isEmpty();
    }

    boolean needsHostThreshold() {
        return mode == Mode.DYNAMIC_TERRAIN;
    }

    boolean active() {
        return mode != Mode.OFF && valid();
    }

    String appliedDeviation(String programName) {
        if (!active()) return "";
        if (mode == Mode.DYNAMIC_TERRAIN) {
            return "ALPHA_TEST_DYNAMIC:" + programName;
        }
        return "ALPHA_TEST_APPLIED:" + programName + ":" + function + ":" + Float.toString(reference);
    }

    String rejection(String outputName, String hostInstance) {
        if (!active() || outputName == null || outputName.isBlank()) return "";
        if (mode == Mode.DYNAMIC_TERRAIN) {
            return "if (" + hostInstance + ".AlphaCutout > 0.0 && "
                    + outputName + ".a < " + hostInstance + ".AlphaCutout) {\n"
                    + "        discard;\n    }";
        }
        if (function.equals("ALWAYS")) return "";
        if (function.equals("NEVER")) return "discard;";
        String operator = switch (function) {
            case "LESS" -> "<";
            case "EQUAL" -> "==";
            case "LEQUAL" -> "<=";
            case "GREATER" -> ">";
            case "NOTEQUAL" -> "!=";
            case "GEQUAL" -> ">=";
            default -> throw new IllegalStateException("unsupported alpha function: " + function);
        };
        return "if (!(" + outputName + ".a " + operator + " " + Float.toString(reference) + ")) {\n"
                + "        discard;\n    }";
    }
}
