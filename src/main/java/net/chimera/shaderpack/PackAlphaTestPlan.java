package net.chimera.shaderpack;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        /**
         * Each terrain layer tests against the reference the main pass sets for it: Iris's 0.1
         * for its cutout pass, none for solid and translucent (SodiumPrograms.getAlphaTest).
         */
        DYNAMIC_TERRAIN,
        /** Each entity-family draw tests against its host pipeline's ALPHA_CUTOUT. */
        DYNAMIC_ENTITY,
        /**
         * Each shadow terrain layer tests against the reference the shadow
         * pass sets for it: Iris's 0.1 for solid and cutout, none for
         * translucent. Without it every foliage quad cast a square shadow.
         * Entity casters test at 0.1 (Iris SHADOW_ENTITIES_CUTOUT).
         */
        DYNAMIC_SHADOW,
        /**
         * Each sky draw tests against its host format's Iris ShaderKey (SKY_BASIC_COLOR:
         * NON_ZERO_ALPHA; SKY_BASIC none), which the sky lane sets per draw (ChimeraSkyBridge).
         */
        DYNAMIC_SKY,
        FIXED
    }

    /** Iris's world programs whose ShaderKeys test nothing; a pack's alphaTest.<program> still applies. */
    private static final Set<String> UNTESTED_KEY_PROGRAMS = Set.of(
            "gbuffers_skytextured", "gbuffers_beaconbeam", "gbuffers_line", "gbuffers_lightning", "gbuffers_basic");

    /** The vertex colour's alpha, forwarded to a fragment whose draw test reads it. */
    static final String VERTEX_ALPHA_VARYING = "chimeraVertexAlpha";

    private static final Pattern VERSION_LINE =
            Pattern.compile("(?m)^[ \\t]*#[ \\t]*version\\b[^\\r\\n]*\\R?");

    private static final List<String> FUNCTIONS = List.of(
            "NEVER", "LESS", "EQUAL", "LEQUAL", "GREATER", "NOTEQUAL", "GEQUAL", "ALWAYS");

    PackAlphaTestPlan {
        mode = mode == null ? Mode.OFF : mode;
        function = function == null ? "" : function;
        reference = Float.isFinite(reference) ? reference : 0.0F;
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    static PackAlphaTestPlan forProgram(String programName, PackSettingsPlan settings) {
        return forProgram(programName, programName, settings);
    }

    static PackAlphaTestPlan forProgram(String programName, String sourceProgram, PackSettingsPlan settings) {
        String name = programName == null ? "" : programName;
        PackAlphaTestPlan irisDefault = irisDefault(name);
        if (irisDefault == null) {
            return off(false);
        }
        String value = settings == null ? null : settings.propertyValues().get("alphaTest." + sourceProgram);
        return value == null ? irisDefault : parse(name, value, irisDefault);
    }

    /** The test Iris gives a program's shader key when the pack sets none; null when it has none. */
    private static PackAlphaTestPlan irisDefault(String name) {
        if (FamilyAdapterRegistry.isCloudFamily(name)) {
            // Iris ShaderKey.CLOUDS: AlphaTests.ONE_TENTH_ALPHA.
            return new PackAlphaTestPlan(Mode.FIXED, "GREATER", 0.1F, false, List.of());
        }
        if (FamilyAdapterRegistry.isShadowFamily(name)) {
            return new PackAlphaTestPlan(Mode.DYNAMIC_SHADOW, "DYNAMIC", 0.0F, false, List.of());
        }
        // Iris tests entity programs against the render type's own threshold
        // (iris_currentAlphaTest); packs rely on it to drop the transparent texels of
        // layered skins and cutout models. Iris TEXT_BG tests ONE_TENTH_ALPHA, the
        // lane's per-draw reference.
        if (FamilyAdapterRegistry.isEntityLike(name)
                || FamilyAdapterRegistry.isParticleLike(name) || FamilyAdapterRegistry.isWeatherFamily(name)
                || FamilyAdapterRegistry.isGlintFamily(name)
                || FamilyAdapterRegistry.forProgram(name).family() == FamilyAdapterPlan.Family.TEXT_BG) {
            return new PackAlphaTestPlan(Mode.DYNAMIC_ENTITY, "DYNAMIC", 0.0F, false, List.of());
        }
        if (name.equals("gbuffers_terrain") || name.equals("gbuffers_water")) {
            return new PackAlphaTestPlan(Mode.DYNAMIC_TERRAIN, "DYNAMIC", 0.0F, false, List.of());
        }
        if (name.equals("gbuffers_skybasic")) {
            return new PackAlphaTestPlan(Mode.DYNAMIC_SKY, "DYNAMIC", 0.0F, false, List.of());
        }
        return UNTESTED_KEY_PROGRAMS.contains(name) ? off(false) : null;
    }

    String injectDrawTest(String fragment) {
        return injectDrawTest(fragment, false);
    }

    /**
     * Appends the entity-family alpha test to the authored fragment, as Iris
     * does, before its interface is planned. The dynamic test declares the
     * per-draw reference uniform, so the ordinary descriptor contract carries
     * it. Iris tests only gl_FragData[0], indexed by a literal, or gl_FragColor, which it
     * renames to that (CommonTransformer); a fragment writing neither is left unchanged.
     * With {@code vertexAlpha} a negative reference tests against the vertex
     * colour's alpha instead (Iris AlphaTests.VERTEX_ALPHA), which the entity
     * vertex bridge forwards as {@link #VERTEX_ALPHA_VARYING}.
     */
    String injectDrawTest(String fragment, boolean vertexAlpha) {
        if (fragment == null || !active()
                || (!perDrawReference() && mode != Mode.FIXED)) return fragment;
        String output = indexesFragDataZero(fragment) ? "gl_FragData[0]"
                : GlslTokenRewriter.containsIdentifier(fragment, "gl_FragColor") ? "gl_FragColor" : null;
        if (output == null) return fragment;
        String reference = UniformRegistry.ENTITY_ALPHA_REFERENCE;
        String referenceTest = reference + " > 0.0 && !(" + output + ".a > " + reference + ")";
        String test = vertexAlpha
                ? reference + " < 0.0 ? !(" + output + ".a > " + VERTEX_ALPHA_VARYING + ") : " + referenceTest
                : referenceTest;
        String rejection = perDrawReference()
                ? "if (" + test + ") {\n        discard;\n    }"
                : rejection(output);
        if (rejection.isBlank()) return fragment;
        String tested = GlslTokenRewriter.appendMainBody(fragment, rejection);
        return perDrawReference() ? declarePerDrawReference(tested) : tested;
    }

    /**
     * Gives a stage's {@code alphaTestRef} the program's current alpha-test reference, as Iris
     * does: its core transformers rename the name to iris_currentAlphaTest, and
     * IrisInternalUniforms serves it as the same value, CapturedRenderingState's current alpha
     * test, which ExtendedShader and SodiumShader set to the program's AlphaTest reference. The
     * declaration goes; each reference reads the per-draw reference, the fixed one, or 0 for a
     * program Iris tests with AlphaTest.ALWAYS. Iris's VERTEX_ALPHA reference is Float.MAX_VALUE.
     */
    String serveReference(String source) {
        String perDraw = UniformRegistry.ENTITY_ALPHA_REFERENCE;
        String value = !active() ? "0.0" : perDrawReference()
                ? "(" + perDraw + " < 0.0 ? 3.4028235e38 : " + perDraw + ")"
                : Float.toString(reference);
        String served = ServedUniforms.serve(source, IRIS_REFERENCE, "float", value);
        return served != source && perDrawReference() && active() ? declarePerDrawReference(served) : served;
    }

    static final String IRIS_REFERENCE = "alphaTestRef";

    /** Declares the per-draw reference uniform after the version line, unless the source already does. */
    private static String declarePerDrawReference(String source) {
        String reference = UniformRegistry.ENTITY_ALPHA_REFERENCE;
        boolean declared = GlslDeclarations.variables(GlslTree.parse(source)).stream()
                .anyMatch(variable -> variable.name().equals(reference) && variable.qualified("uniform"));
        if (declared) return source;
        String declaration = "uniform float " + reference + ";\n";
        Matcher version = VERSION_LINE.matcher(source);
        return version.find()
                ? source.substring(0, version.end()) + declaration + source.substring(version.end())
                : declaration + source;
    }

    /**
     * Whether the fragment names gl_FragData[0] outside comments, its index an integer literal whose
     * value is 0, as CommonTransformer's glFragDataI matcher reads it: {@code 0u}, {@code 0x0} and
     * {@code 00} count.
     */
    private static boolean indexesFragDataZero(String fragment) {
        List<GlslLexer.Token> tokens = GlslLexer.lex(fragment).stream().filter(GlslLexer.Token::significant).toList();
        for (int i = 0; i + 3 < tokens.size(); i++) {
            if (tokens.get(i).identifier("gl_FragData") && tokens.get(i + 1).symbol("[")
                    && zeroLiteral(tokens.get(i + 2).text()) && tokens.get(i + 3).symbol("]")) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code text} is a GLSL integer literal (decimal, octal or hex, optionally unsigned) of value 0. */
    private static boolean zeroLiteral(String text) {
        String digits = text.endsWith("u") || text.endsWith("U") ? text.substring(0, text.length() - 1) : text;
        try {
            if (digits.startsWith("0x") || digits.startsWith("0X")) return Long.parseLong(digits.substring(2), 16) == 0;
            return digits.startsWith("0") ? Long.parseLong(digits, 8) == 0 : Long.parseLong(digits) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * The test {@code alphaTest.<program>} sets, as Iris's ShaderProperties reads it: exactly
     * "off" or "false" tests nothing; otherwise a function (Iris's names, or GL_ALWAYS) and a
     * reference, split on single spaces, where a third part is ignored. A directive Iris rejects,
     * with too few parts, an unknown function or a reference Float.parseFloat refuses, leaves the
     * key's own test in place and is named.
     */
    private static PackAlphaTestPlan parse(String programName, String authored, PackAlphaTestPlan irisDefault) {
        String value = authored.stripLeading();
        if (value.equals("off") || value.equals("false")) {
            return new PackAlphaTestPlan(Mode.OFF, "OFF", 0.0F, true, List.of());
        }
        String[] parts = value.split(" ");
        String function = parts.length < 2 ? null : parts[0].equals("GL_ALWAYS") ? "ALWAYS" : parts[0];
        if (function == null || !FUNCTIONS.contains(function)) return ignored(programName, value, irisDefault);
        try {
            return new PackAlphaTestPlan(Mode.FIXED, function, Float.parseFloat(parts[1]), true, List.of());
        } catch (NumberFormatException failure) {
            return ignored(programName, value, irisDefault);
        }
    }

    /** The key's own test, with the directive Iris rejected named. */
    private static PackAlphaTestPlan ignored(String programName, String value, PackAlphaTestPlan irisDefault) {
        return new PackAlphaTestPlan(irisDefault.mode(), irisDefault.function(), irisDefault.reference(),
                irisDefault.configured(), List.of("ALPHA_TEST_DIRECTIVE_IGNORED:" + programName + ":" + value));
    }

    private static PackAlphaTestPlan off(boolean configured) {
        return new PackAlphaTestPlan(Mode.OFF, "OFF", 0.0F, configured, List.of());
    }

    static PackAlphaTestPlan disabled() {
        return off(false);
    }

    /** Entity, shadow and terrain draws test against a reference set per draw (Iris iris_currentAlphaTest). */
    boolean perDrawReference() {
        return mode == Mode.DYNAMIC_ENTITY || mode == Mode.DYNAMIC_SHADOW || mode == Mode.DYNAMIC_TERRAIN
                || mode == Mode.DYNAMIC_SKY;
    }

    boolean active() {
        return mode != Mode.OFF;
    }

    String appliedDeviation(String programName) {
        if (!active()) return "";
        if (perDrawReference()) {
            return "ALPHA_TEST_DYNAMIC:" + programName;
        }
        return "ALPHA_TEST_APPLIED:" + programName + ":" + function + ":" + Float.toString(reference);
    }

    /** The fixed test's rejection of {@code outputName}; empty for a per-draw or inactive test. */
    String rejection(String outputName) {
        if (!active() || outputName == null || outputName.isBlank() || perDrawReference()) return "";
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
