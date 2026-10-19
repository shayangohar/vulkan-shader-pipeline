package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small token-safe normalization pass shared by planning and conversion.
 * It contains only legacy built-ins that Iris rewrites the same way in every
 * program: gl_Fog fields to Chimera's fog uniforms, and gl_FogFragCoord to
 * an ordinary varying. The legacy colour and texture-coordinate varyings
 * (gl_FrontColor and gl_Color, gl_TexCoord[n]) become ordinary varyings too,
 * which GL's compatibility profile gives packs and Vulkan does not. In the
 * vertex stage it starts every referenced output
 * at zero, the value GL drivers leave in one a path never writes. It also
 * gives every uniform its own declaration, so
 * per-name rewriters never meet a declarator list. Its input is already
 * macro-expanded ({@link ShaderSourcePreprocessor}).
 */
final class LegacyShaderNormalizer {
    /** Iris's name for the legacy built-in fog distance varying. */
    static final String FOG_FRAG_COORD = "iris_FogFragCoord";
    private static final Pattern VERSION_LINE = Pattern.compile(
            "(?im)^([ \\t]*#version[^\\r\\n]*(?:\\r?\\n|$))");

    record Result(String source, List<String> deviations, boolean successful) {
        Result {
            deviations = deviations == null
                    ? List.of() : deviations.stream().distinct().sorted().toList();
        }
    }

    private LegacyShaderNormalizer() {}

    /**
     * Built-in functions GLSL added after 1.20, each with the version that added it. A pack
     * authored before that version may define its own (Bliss defines {@code float tanh(float)} at
     * 1.20, Bloop {@code uint packSnorm4x8(vec4)} at 1.40); every call in that source means the
     * pack's function, but the #version 460 Chimera compiles at rejects the redefinition, so the
     * pack's copy is renamed.
     */
    private static final Map<String, Integer> LATE_BUILTINS = lateBuiltins();
    private static final Pattern VERSION_NUMBER = Pattern.compile("(?m)^[ \\t]*#version[ \\t]+(\\d+)");
    /** Interface types whose {@code T(0)} constructor is zero: scalars, vectors and matrices. */
    private static final Pattern ZEROABLE_TYPE = Pattern.compile(
            "(?:float|double|int|uint|bool|[biud]?vec[234]|d?mat[234](?:x[234])?)");

    /** The authored #version; GLSL treats a source without one as 110. */
    private static int authoredVersion(String source) {
        Matcher version = VERSION_NUMBER.matcher(source);
        return version.find() ? Integer.parseInt(version.group(1)) : 110;
    }

    /**
     * GLSL added line continuation in 4.20. Before it, a {@code //} comment ending in a backslash
     * (Bliss: {@code /// --- RAYMARCHING STUFF --- \\}) ends at its line; at the #version 460
     * Chimera compiles at, it would swallow the next line of live code.
     */
    private static void endLineCommentsAtTheirLine(List<GlslLexer.Token> tokens, int version) {
        if (version >= 420) return;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() == GlslLexer.Kind.TRIVIA && token.text().startsWith("//")
                    && token.text().stripTrailing().endsWith("\\")) {
                tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.TRIVIA,
                        token.text().replaceAll("[\\\\\\s]+$", "")));
            }
        }
    }

    private static Map<String, Integer> lateBuiltins() {
        Map<String, Integer> versions = new HashMap<>();
        for (String name : List.of("sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "trunc", "round",
                "roundEven", "modf", "isnan", "isinf")) versions.put(name, 130);
        versions.put("inverse", 140);
        versions.put("determinant", 150);
        for (String name : List.of("floatBitsToInt", "floatBitsToUint", "intBitsToFloat", "uintBitsToFloat")) {
            versions.put(name, 330);
        }
        for (String name : List.of("fma", "frexp", "ldexp", "packUnorm2x16", "unpackUnorm2x16", "packUnorm4x8",
                "unpackUnorm4x8", "packSnorm4x8", "unpackSnorm4x8", "bitfieldExtract", "bitfieldInsert",
                "bitfieldReverse", "bitCount", "findLSB", "findMSB")) versions.put(name, 400);
        for (String name : List.of("packSnorm2x16", "unpackSnorm2x16", "packHalf2x16", "unpackHalf2x16")) {
            versions.put(name, 420);
        }
        return Map.copyOf(versions);
    }

    private static void renameLateBuiltinDefinitions(List<GlslLexer.Token> tokens, int version) {
        Set<String> defined = new HashSet<>();
        int depth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("{")) depth++;
            else if (token.symbol("}")) depth = Math.max(0, depth - 1);
            if (depth != 0 || token.kind() != GlslLexer.Kind.IDENTIFIER
                    || version >= LATE_BUILTINS.getOrDefault(token.text(), 0)) {
                continue;
            }
            // `type name(` at file scope declares or defines the pack's own function.
            int type = GlslLexer.previousSignificant(tokens, index);
            int open = GlslLexer.nextSignificant(tokens, index);
            if (type >= 0 && tokens.get(type).kind() == GlslLexer.Kind.IDENTIFIER
                    && open >= 0 && tokens.get(open).symbol("(")) {
                defined.add(token.text());
            }
        }
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() == GlslLexer.Kind.IDENTIFIER && defined.contains(token.text())) {
                tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, "chimera_" + token.text()));
            }
        }
    }

    static Result normalize(String source, boolean vertexStage) {
        if (source == null) {
            return new Result(null, List.of(), false);
        }
        try {
            List<GlslLexer.Token> tokens = new ArrayList<>(
                    GlslLexer.lex(GlslTokenRewriter.splitUniformDeclarators(source)));
            int version = authoredVersion(source);
            renameLateBuiltinDefinitions(tokens, version);
            endLineCommentsAtTheirLine(tokens, version);
            Map<String, String> required = new TreeMap<>();
            String unsupportedField = rewriteFogFields(tokens, required);
            if (unsupportedField != null) {
                return unsupported(unsupportedField);
            }
            boolean fogFragCoord = renameFogFragCoord(tokens);
            Set<String> privateGlobals = new TreeSet<>();
            Set<String> varyings = renameLegacyVaryings(tokens, vertexStage, privateGlobals);

            String normalized = GlslLexer.render(tokens);
            if (!varyings.isEmpty() || !privateGlobals.isEmpty()) {
                StringBuilder declarations = new StringBuilder();
                varyings.forEach(name -> declarations.append("varying vec4 ").append(name).append(";\n"));
                privateGlobals.forEach(name -> declarations.append("vec4 ").append(name).append(";\n"));
                normalized = insertDeclarations(normalized, declarations.toString());
            }
            if (vertexStage) {
                normalized = zeroReferencedOutputs(normalized);
            }
            List<String> missing = new ArrayList<>();
            List<GlslLexer.Token> normalizedTokens = GlslLexer.lex(normalized);
            for (Map.Entry<String, String> uniform : required.entrySet()) {
                if (declaredUniformType(normalizedTokens, uniform.getKey()) == null) {
                    missing.add("uniform " + uniform.getValue() + " " + uniform.getKey() + ";");
                }
            }
            if (fogFragCoord) {
                // Iris CommonTransformer: an ordinary varying, zeroed first in the vertex stage.
                missing.add("varying float " + FOG_FRAG_COORD + ";");
                if (vertexStage) {
                    normalized = GlslTokenRewriter.prependMainPrologue(normalized, FOG_FRAG_COORD + " = 0.0;");
                }
            }
            if (!missing.isEmpty()) {
                normalized = insertDeclarations(normalized, String.join("\n", missing) + "\n");
            }
            return new Result(normalized, List.of(), true);
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new Result(null, List.of("SOURCE_NORMALIZATION_FAILED:" + reason), false);
        }
    }

    /**
     * Rewrites each {@code gl_Fog.<field>} to Chimera's fog uniforms and records the uniforms the
     * rewrites read. Returns the first field it cannot serve, or null when it serves them all.
     */
    private static String rewriteFogFields(List<GlslLexer.Token> tokens, Map<String, String> required) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).identifier("gl_Fog")) {
                continue;
            }
            int dot = GlslLexer.nextSignificant(tokens, index);
            int field = dot < 0 ? -1 : GlslLexer.nextSignificant(tokens, dot);
            if (dot < 0 || field < 0 || !tokens.get(dot).symbol(".")
                    || tokens.get(field).kind() != GlslLexer.Kind.IDENTIFIER) {
                return "unknown";
            }
            String fieldName = tokens.get(field).text();
            String replacement = fogFieldValue(fieldName, tokens, required);
            if (replacement == null) {
                return fieldName;
            }
            tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, replacement));
            tokens.set(dot, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, ""));
            tokens.set(field, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, ""));
        }
        return null;
    }

    /** The expression that serves one gl_Fog field, or null for a field Chimera does not serve. */
    private static String fogFieldValue(String fieldName, List<GlslLexer.Token> tokens,
                                        Map<String, String> required) {
        return switch (fieldName) {
            case "start" -> {
                required.put("fogStart", "float");
                yield "fogStart";
            }
            case "end" -> {
                required.put("fogEnd", "float");
                yield "fogEnd";
            }
            case "scale" -> {
                required.put("fogStart", "float");
                required.put("fogEnd", "float");
                yield "(1.0 / (fogEnd - fogStart))";
            }
            // Iris's iris_FogParameters.color is the fog RGBA, iris_FogColor. Chimera's fogColor is
            // the same value; a pack that already declares it vec3 keeps its declaration.
            case "color" -> {
                String declared = declaredUniformType(tokens, "fogColor");
                if (declared == null) {
                    required.put("fogColor", "vec4");
                }
                yield "vec3".equals(declared) ? "vec4(fogColor, 1.0)" : "fogColor";
            }
            default -> null;
        };
    }

    /**
     * Starts every vertex output the shader references at zero. An output written on some paths
     * only (BSL's gbuffers_basic sets isSelection for the selection outline alone) reads 0 under
     * GL drivers in practice, but is undefined in Vulkan: the lead took BSL's unlit outline path
     * on random fragments, a different set each frame. A source whose interface the scanner
     * cannot read, or that has no main, is left as it is; re-normalizing adds nothing.
     */
    private static String zeroReferencedOutputs(String source) {
        GlslInterfaceScanner.StageInterface stage = GlslInterfaceScanner.scan(source, true);
        if (!stage.deviations().isEmpty()) {
            return source;
        }
        List<String> statements = new ArrayList<>();
        for (GlslInterfaceScanner.Declaration output : stage.outputs()) {
            String statement = zeroStatement(output);
            if (statement != null && !source.contains(statement)) {
                statements.add(statement);
            }
        }
        if (statements.isEmpty()) {
            return source;
        }
        try {
            return GlslTokenRewriter.prependMainPrologue(source, String.join("\n", statements));
        } catch (IllegalArgumentException noMain) {
            return source;
        }
    }

    /** {@code name = T(0);} for a referenced output of a built-in type, or null to leave it alone. */
    private static String zeroStatement(GlslInterfaceScanner.Declaration output) {
        // An unread output is dropped as dead, so it gets no write that would keep it alive;
        // iris_FogFragCoord has Iris's own zero, added with its declaration.
        if (!output.referenced() || output.name().startsWith("gl_") || output.name().equals(FOG_FRAG_COORD)
                || !ZEROABLE_TYPE.matcher(output.type()).matches()) {
            return null;
        }
        String zero = output.type() + "(0)";
        if (output.arraySize() == 0) {
            return output.name() + " = " + zero + ";";
        }
        return "for (int chimeraZeroIndex = 0; chimeraZeroIndex < " + output.arraySize()
                + "; chimeraZeroIndex++) " + output.name() + "[chimeraZeroIndex] = " + zero + ";";
    }

    /** Renames gl_FogFragCoord to Iris's ordinary varying; true when the source uses it. */
    private static boolean renameFogFragCoord(List<GlslLexer.Token> tokens) {
        boolean found = false;
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).identifier("gl_FogFragCoord")) {
                tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, FOG_FRAG_COORD));
                found = true;
            }
        }
        return found;
    }

    /**
     * Renames the legacy colour and texture-coordinate varyings to ordinary ones: a vertex stage's
     * gl_FrontColor, gl_FrontSecondaryColor and gl_TexCoord[n] become the varyings a fragment
     * stage's gl_Color, gl_SecondaryColor and gl_TexCoord[n] read. The back colours, which GL
     * interpolates only under two-sided lighting, become globals into {@code privateGlobals}.
     * A gl_TexCoord index that is not a literal is left for the compiler to reject. Returns the
     * varyings the source now uses.
     */
    private static Set<String> renameLegacyVaryings(List<GlslLexer.Token> tokens, boolean vertexStage,
                                                    Set<String> privateGlobals) {
        Set<String> varyings = new TreeSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER) continue;
            String name = legacyVarying(tokens, index, vertexStage);
            if (vertexStage && (token.text().equals("gl_BackColor") || token.text().equals("gl_BackSecondaryColor"))) {
                String global = "chimera_" + token.text().substring(3);
                privateGlobals.add(global);
                tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, global));
                continue;
            }
            if (name == null) continue;
            varyings.add(name);
            tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, name));
        }
        return varyings;
    }

    /** The ordinary varying that stands for the legacy one at {@code index} in this stage, or null. */
    private static String legacyVarying(List<GlslLexer.Token> tokens, int index, boolean vertexStage) {
        return switch (tokens.get(index).text()) {
            case "gl_FrontColor" -> vertexStage ? "chimera_FrontColor" : null;
            case "gl_FrontSecondaryColor" -> vertexStage ? "chimera_SecondaryColor" : null;
            case "gl_Color" -> vertexStage ? null : "chimera_FrontColor";
            case "gl_SecondaryColor" -> vertexStage ? null : "chimera_SecondaryColor";
            case "gl_TexCoord" -> texCoordVarying(tokens, index);
            default -> null;
        };
    }

    /** {@code chimera_TexCoordN} for {@code gl_TexCoord[N]}, clearing the subscript; null for any other index. */
    private static String texCoordVarying(List<GlslLexer.Token> tokens, int name) {
        int open = GlslLexer.nextSignificant(tokens, name);
        int number = open < 0 ? -1 : GlslLexer.nextSignificant(tokens, open);
        int close = number < 0 ? -1 : GlslLexer.nextSignificant(tokens, number);
        if (close < 0 || !tokens.get(open).symbol("[") || !tokens.get(close).symbol("]")
                || !tokens.get(number).text().matches("\\d+")) {
            return null;
        }
        String varying = "chimera_TexCoord" + Integer.parseInt(tokens.get(number).text());
        for (int index = name + 1; index <= close; index++) {
            tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, ""));
        }
        return varying;
    }

    private static Result unsupported(String field) {
        return new Result(null, List.of("LEGACY_FOG_FIELD_UNSUPPORTED:" + field), false);
    }

    /** The declared type of a uniform, or null when the source does not declare it. */
    private static String declaredUniformType(List<GlslLexer.Token> tokens, String name) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).identifier("uniform")) {
                continue;
            }
            int type = GlslLexer.nextSignificant(tokens, index);
            if (type < 0) {
                continue;
            }
            boolean expectName = true;
            for (int cursor = type + 1; cursor < tokens.size(); cursor++) {
                GlslLexer.Token token = tokens.get(cursor);
                if (!token.significant()) {
                    continue;
                }
                if (token.symbol(";")) {
                    break;
                }
                if (token.symbol(",")) {
                    expectName = true;
                    continue;
                }
                if (expectName && token.identifier(name)) {
                    return tokens.get(type).text();
                }
                if (expectName && token.kind() == GlslLexer.Kind.IDENTIFIER) {
                    expectName = false;
                }
            }
        }
        return null;
    }

    private static String insertDeclarations(String source, String declarations) {
        Matcher version = VERSION_LINE.matcher(source);
        if (version.find()) {
            return source.substring(0, version.end()) + declarations + source.substring(version.end());
        }
        return declarations + source;
    }
}
