package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small token-safe normalization pass shared by planning and conversion.
 * It contains only legacy built-ins that Iris rewrites the same way in every
 * program: gl_Fog fields to Chimera's fog uniforms, and gl_FogFragCoord to
 * an ordinary varying. It also gives every uniform its own declaration, so
 * per-name rewriters never meet a declarator list, and expands macros that
 * alias a sampler, so planning sees every sampler a program uses.
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

    static Result normalize(String source, boolean vertexStage) {
        if (source == null) {
            return new Result(null, List.of(), false);
        }
        try {
            List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(GlslTokenRewriter.expandSamplerAliases(
                    GlslTokenRewriter.splitUniformDeclarators(source))));
            Map<String, String> required = new TreeMap<>();
            boolean fogFragCoord = false;
            for (int index = 0; index < tokens.size(); index++) {
                GlslLexer.Token token = tokens.get(index);
                if (token.identifier("gl_FogFragCoord")) {
                    tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, FOG_FRAG_COORD));
                    fogFragCoord = true;
                    continue;
                }
                if (!token.identifier("gl_Fog")) {
                    continue;
                }
                int dot = GlslLexer.nextSignificant(tokens, index);
                int field = dot < 0 ? -1 : GlslLexer.nextSignificant(tokens, dot);
                if (dot < 0 || field < 0 || !tokens.get(dot).symbol(".")
                        || tokens.get(field).kind() != GlslLexer.Kind.IDENTIFIER) {
                    return unsupported("unknown");
                }
                String fieldName = tokens.get(field).text();
                String replacement = switch (fieldName) {
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
                if (replacement == null) {
                    return unsupported(fieldName);
                }
                tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, replacement));
                tokens.set(dot, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, ""));
                tokens.set(field, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, ""));
            }

            String normalized = GlslLexer.render(tokens);
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
