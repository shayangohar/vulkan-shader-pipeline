package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Token-safe rewrites for the small legacy-to-Vulkan GLSL bridge. */
final class GlslTokenRewriter {
    private GlslTokenRewriter() {}

    static String replaceIdentifiers(String source, Map<String, String> replacements) {
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER) {
                continue;
            }
            String replacement = replacements.get(token.text());
            if (replacement != null) {
                tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, replacement));
            }
        }
        return GlslLexer.render(tokens);
    }

    static String rewriteTextureCalls(String source) {
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            String replacement = switch (token.text()) {
                case "texture2DLod", "texture3DLod" -> "textureLod";
                case "texture2D", "texture3D" -> "texture";
                case "texture2DProj" -> "textureProj";
                default -> null;
            };
            if (token.kind() != GlslLexer.Kind.IDENTIFIER || replacement == null) {
                continue;
            }
            int open = GlslLexer.nextSignificant(tokens, index);
            if (open < 0 || !tokens.get(open).symbol("(")) {
                continue;
            }
            if (GlslLexer.matching(tokens, open, "(", ")") < 0) {
                throw new IllegalArgumentException("unbalanced texture call");
            }
            tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, replacement));
        }
        return GlslLexer.render(tokens);
    }

    /**
     * Converts legacy shadow lookups to expressions using the declared sampler.
     * GLSL opaque sampler types cannot be function parameters, so this rewrite
     * deliberately emits an inline expression instead of a generated helper.
     */
    static String rewriteShadowCalls(String source, Map<String, String> samplerTypes) {
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        // Work from the innermost calls outward. This keeps nested legacy
        // lookups valid when the outer coordinate expression is rendered.
        for (int index = tokens.size() - 1; index >= 0; index--) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER
                    || (!token.text().equals("shadow2D")
                    && !token.text().equals("shadow2DLod")
                    && !token.text().equals("texture2DShadow"))) {
                continue;
            }
            int open = GlslLexer.nextSignificant(tokens, index);
            int close = open < 0 ? -1 : GlslLexer.matching(tokens, open, "(", ")");
            if (open < 0 || !tokens.get(open).symbol("(") || close < 0) {
                throw new IllegalArgumentException("unbalanced legacy shadow call");
            }
            List<int[]> arguments = argumentRanges(tokens, open + 1, close);
            boolean lodCall = token.text().equals("shadow2DLod");
            int expectedArguments = lodCall ? 3 : 2;
            if (arguments.size() != expectedArguments) {
                throw new IllegalArgumentException("invalid legacy shadow argument count");
            }
            String sampler = singleIdentifier(tokens, arguments.get(0));
            String samplerType = samplerTypes == null ? null : samplerTypes.get(sampler);
            if (samplerType == null) {
                throw new IllegalArgumentException("legacy shadow sampler is not declared: " + sampler);
            }
            String coordinate = argumentText(tokens, arguments.get(1));
            boolean scalarCall = token.text().equals("texture2DShadow");
            String expression;
            if (samplerType.equals("sampler2DShadow")) {
                expression = (scalarCall ? "texture(" : "vec4(texture(")
                        + sampler + ", " + coordinate;
                if (lodCall) {
                    expression = (scalarCall ? "textureLod(" : "vec4(textureLod(")
                            + sampler + ", " + coordinate
                            + ", " + argumentText(tokens, arguments.get(2))
                            + (scalarCall ? ")" : "))");
                } else {
                    expression += scalarCall ? ")" : "))";
                }
            } else if (samplerType.equals("sampler2D")) {
                String depthSample = lodCall
                        ? "textureLod(" + sampler + ", " + coordinate + ".xy, "
                        + argumentText(tokens, arguments.get(2)) + ").r"
                        : "texture(" + sampler + ", " + coordinate + ".xy).r";
                expression = scalarCall
                        ? "step(" + coordinate + ".z, " + depthSample + ")"
                        : "vec4(step(" + coordinate + ".z, " + depthSample + "))";
            } else {
                throw new IllegalArgumentException("unsupported legacy shadow sampler type: "
                        + samplerType);
            }
            tokens.set(index, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, expression));
            for (int clear = index + 1; clear <= close; clear++) {
                tokens.set(clear, new GlslLexer.Token(GlslLexer.Kind.TRIVIA, ""));
            }
        }
        return GlslLexer.render(tokens);
    }

    private static List<int[]> argumentRanges(List<GlslLexer.Token> tokens, int start, int end) {
        List<int[]> ranges = new ArrayList<>();
        int argumentStart = start;
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        for (int index = start; index < end; index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("(")) {
                parentheses++;
            } else if (token.symbol(")")) {
                parentheses--;
            } else if (token.symbol("[")) {
                brackets++;
            } else if (token.symbol("]")) {
                brackets--;
            } else if (token.symbol("{")) {
                braces++;
            } else if (token.symbol("}")) {
                braces--;
            } else if (token.symbol(",") && parentheses == 0 && brackets == 0 && braces == 0) {
                ranges.add(new int[] {argumentStart, index});
                argumentStart = index + 1;
            }
        }
        ranges.add(new int[] {argumentStart, end});
        return ranges;
    }

    private static String singleIdentifier(List<GlslLexer.Token> tokens, int[] range) {
        int first = -1;
        int last = -1;
        for (int index = range[0]; index < range[1]; index++) {
            if (!tokens.get(index).significant()) {
                continue;
            }
            if (first < 0) {
                first = index;
            }
            last = index;
        }
        if (first < 0 || first != last || tokens.get(first).kind() != GlslLexer.Kind.IDENTIFIER) {
            throw new IllegalArgumentException("legacy shadow sampler must be an identifier");
        }
        return tokens.get(first).text();
    }

    private static String argumentText(List<GlslLexer.Token> tokens, int[] range) {
        return GlslLexer.render(tokens.subList(range[0], range[1])).trim();
    }

    static String rewritePostOutputs(String source, PostTargetPlan targetPlan) {
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.identifier("gl_FragColor")) {
                tokens.set(index, identifier("chimeraFragColor0"));
                continue;
            }
            if (!token.identifier("gl_FragData")) {
                continue;
            }
            int open = GlslLexer.nextSignificant(tokens, index);
            int number = open < 0 ? -1 : GlslLexer.nextSignificant(tokens, open);
            int close = number < 0 ? -1 : GlslLexer.nextSignificant(tokens, number);
            if (open < 0 || number < 0 || close < 0
                    || !tokens.get(open).symbol("[")
                    || tokens.get(number).kind() != GlslLexer.Kind.NUMBER
                    || !tokens.get(close).symbol("]")) {
                throw new IllegalArgumentException("invalid gl_FragData access");
            }
            int location;
            try {
                location = Integer.parseInt(tokens.get(number).text());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("non-constant gl_FragData index");
            }
            if (targetPlan == null || !targetPlan.outputLocations().contains(location)) {
                throw new IllegalArgumentException("fragment output is not in the target plan");
            }
            tokens.set(index, identifier("chimeraFragColor" + location));
            tokens.set(open, raw(""));
            tokens.set(number, raw(""));
            tokens.set(close, raw(""));
        }
        return GlslLexer.render(tokens);
    }

    static String rewriteSingleOutput(String source) {
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.identifier("gl_FragColor")) {
                tokens.set(index, identifier("fragColor"));
                continue;
            }
            if (!token.identifier("gl_FragData")) {
                continue;
            }
            int open = GlslLexer.nextSignificant(tokens, index);
            int number = open < 0 ? -1 : GlslLexer.nextSignificant(tokens, open);
            int close = number < 0 ? -1 : GlslLexer.nextSignificant(tokens, number);
            if (open < 0 || number < 0 || close < 0
                    || !tokens.get(open).symbol("[")
                    || !tokens.get(number).kind().equals(GlslLexer.Kind.NUMBER)
                    || !tokens.get(close).symbol("]")) {
                continue;
            }
            if (!tokens.get(number).text().equals("0")) {
                continue;
            }
            tokens.set(index, identifier("fragColor"));
            tokens.set(open, raw(""));
            tokens.set(number, raw(""));
            tokens.set(close, raw(""));
        }
        return GlslLexer.render(tokens);
    }

    static boolean containsIdentifier(String source, String name) {
        for (GlslLexer.Token token : GlslLexer.lex(source)) {
            if (token.identifier(name)) {
                return true;
            }
        }
        return false;
    }

    private static GlslLexer.Token identifier(String value) {
        return new GlslLexer.Token(GlslLexer.Kind.IDENTIFIER, value);
    }

    private static GlslLexer.Token raw(String value) {
        return new GlslLexer.Token(GlslLexer.Kind.TRIVIA, value);
    }
}
