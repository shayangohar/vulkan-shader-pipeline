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
        /** Each entity-family draw tests against its host pipeline's ALPHA_CUTOUT. */
        DYNAMIC_ENTITY,
        /**
         * Each shadow terrain layer tests against the reference the shadow
         * pass sets for it: Iris's 0.1 for solid and cutout, none for
         * translucent. Without it every foliage quad cast a square shadow.
         */
        DYNAMIC_SHADOW,
        FIXED
    }

    private static final java.util.regex.Pattern VERSION_LINE =
            java.util.regex.Pattern.compile("(?m)^[ \\t]*#[ \\t]*version\\b[^\\r\\n]*\\R?");
    private static final java.util.regex.Pattern MODERN_OUTPUT_ZERO = java.util.regex.Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+vec4\\s+(\\w+)\\s*;");

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
        boolean entity = FamilyAdapterRegistry.isEntityLike(name);
        boolean shadow = name.equals("shadow");
        if (!geometry && !entity && !shadow) {
            return off(false);
        }
        String key = "alphaTest." + name;
        String value = settings == null ? null : settings.propertyValues().get(key);
        if (value == null && shadow) {
            return new PackAlphaTestPlan(Mode.DYNAMIC_SHADOW, "DYNAMIC", 0.0F, false, List.of());
        }
        if (value == null) {
            // Iris tests entity programs against the render type's own
            // threshold (iris_currentAlphaTest); packs rely on it to drop the
            // transparent texels of layered skins and cutout models.
            return new PackAlphaTestPlan(entity ? Mode.DYNAMIC_ENTITY : Mode.DYNAMIC_TERRAIN,
                    "DYNAMIC", 0.0F, false, List.of());
        }
        return parse(name, value);
    }

    /**
     * Appends the entity-family alpha test to the authored fragment, as Iris
     * does, before its interface is planned. The dynamic test declares the
     * per-draw reference uniform, so the ordinary descriptor contract carries
     * it. A fragment with no location-0 colour output is left unchanged.
     */
    String injectDrawTest(String fragment) {
        if (fragment == null || !active()
                || (!perDrawReference() && mode != Mode.FIXED)) return fragment;
        String output = GlslTokenRewriter.containsIdentifier(fragment, "gl_FragData") ? "gl_FragData[0]"
                : GlslTokenRewriter.containsIdentifier(fragment, "gl_FragColor") ? "gl_FragColor"
                : modernOutputZero(fragment);
        if (output == null) return fragment;
        String reference = UniformRegistry.ENTITY_ALPHA_REFERENCE;
        String rejection = perDrawReference()
                ? "if (" + reference + " > 0.0 && !(" + output + ".a > " + reference + ")) {\n"
                + "        discard;\n    }"
                : rejection(output, null);
        if (rejection.isBlank()) return fragment;
        String tested = GlslTokenRewriter.appendMainEpilogue(fragment, rejection);
        if (!perDrawReference()) return tested;
        String declaration = "uniform float " + reference + ";\n";
        java.util.regex.Matcher version = VERSION_LINE.matcher(tested);
        return version.find()
                ? tested.substring(0, version.end()) + declaration + tested.substring(version.end())
                : declaration + tested;
    }

    private static String modernOutputZero(String fragment) {
        java.util.regex.Matcher matcher = MODERN_OUTPUT_ZERO.matcher(fragment);
        return matcher.find() ? matcher.group(1) : null;
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

    /** Entity and shadow draws test against a reference set per draw, not a host block. */
    boolean perDrawReference() {
        return mode == Mode.DYNAMIC_ENTITY || mode == Mode.DYNAMIC_SHADOW;
    }

    boolean needsHostThreshold() {
        return mode == Mode.DYNAMIC_TERRAIN;
    }

    boolean active() {
        return mode != Mode.OFF && valid();
    }

    String appliedDeviation(String programName) {
        if (!active()) return "";
        if (mode == Mode.DYNAMIC_TERRAIN || perDrawReference()) {
            return "ALPHA_TEST_DYNAMIC:" + programName;
        }
        return "ALPHA_TEST_APPLIED:" + programName + ":" + function + ":" + Float.toString(reference);
    }

    String rejection(String outputName, String hostInstance) {
        if (!active() || outputName == null || outputName.isBlank()) return "";
        if (perDrawReference()) return "";
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
