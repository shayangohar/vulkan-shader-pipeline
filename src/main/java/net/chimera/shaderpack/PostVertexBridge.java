package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * Converts a post program's authored vertex stage and pairs its varyings with the fragment stage;
 * a post program without one draws on Chimera's fullscreen vertex.
 */
final class PostVertexBridge {
    private PostVertexBridge() {}

    static final Pattern POST_VARYING_DECL = Pattern.compile(
            "(?m)^\\s*(?:(flat|noperspective|smooth|centroid|sample)\\s+)?"
                    + "(varying|in)\\s+([A-Za-z_]\\w*)\\s+([^;]+);");

    /** Post inputs get Chimera's varying locations; outputs keep theirs for PostTargetPlan.modernOutputs. */
    static final Pattern MODERN_INPUT_LAYOUT_DECL = Pattern.compile(
            "(?m)^([ \\t]*)layout\\s*\\([^;{}()]*\\)\\s*((?:(?:flat|noperspective|smooth|centroid|sample)\\s+)?)"
                    + "(in|varying)\\b");

    /**
     * Normalizes modern post syntax before interface scanning: the version,
     * precision and input layouts go, and the existing token translator
     * remains the only executable rewrite path.
     */
    static String normalizeModernPost(String source) {
        if (source == null) {
            throw new IllegalArgumentException("post source is missing");
        }
        GlslVersion.requireSupported(source, "modern post", true);
        String result = LegacyGlslConverter.VERSION_LINE.matcher(source).replaceAll("");
        result = LegacyGlslConverter.KNOWN_LEGACY_EXTENSIONS.matcher(result).replaceAll("");
        result = LegacyGlslConverter.PRECISION_DECL.matcher(result).replaceAll("");
        result = MODERN_INPUT_LAYOUT_DECL.matcher(result).replaceAll("$1$2$3");
        result = GlslTokenRewriter.replaceIdentifiers(result, Map.of(
                "lowp", "", "mediump", "", "highp", ""));
        return GlslTokenRewriter.relaxNonConstantGlobals(result);
    }

    static boolean supportsModernPost(String source) {
        try {
            normalizeModernPost(source);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Authored fullscreen vertex translation and named compatibility deviations. */
    public record PostVertexConversion(String source, List<String> deviations) {
        public PostVertexConversion {
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }
    }

    /** Canonical inputs shared by a prepared fullscreen vertex and fragment pair. */
    public record PostVaryingLayout(Map<String, Integer> locations, Map<String, String> types) {
        public PostVaryingLayout {
            locations = Collections.unmodifiableMap(new TreeMap<>(locations == null ? Map.of() : locations));
            types = Collections.unmodifiableMap(new TreeMap<>(types == null ? Map.of() : types));
        }
    }

    /** Translates the authored vertex body against the fullscreen triangle inputs. */
    public static PostVertexConversion convertPostVertex(
            String source,
            GlslInterfaceScanner.StageInterface vertexInterface,
            GlslInterfaceScanner.StageInterface fragmentInterface,
            GlslInterfaceScanner.ProgramMatch match,
            UniformRegistry.ProgramInterface interfacePlan,
            Map<String, String> packConstants
    ) {
        try {
            if (source == null || !executableInterfaces(vertexInterface, fragmentInterface, match, interfacePlan)) {
                throw new IllegalArgumentException("POST_VERTEX_INTERFACE_UNSUPPORTED");
            }
            String converted = LegacyGlslConverter.prepareSource(source, null, true);
            validatePostVersion(converted);
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(converted);
            if (!usage.successful()) {
                throw new IllegalArgumentException("POST_VERTEX_RESOURCE_ANALYSIS_UNSUPPORTED");
            }
            converted = GlslTokenRewriter.removeUnreachableFunctions(converted, usage);
            if (!stripComments(converted).matches("(?s).*\\bvoid\\s+main\\s*\\(.*")) {
                throw new IllegalArgumentException("POST_VERTEX_MAIN_MISSING");
            }
            for (GlslInterfaceScanner.Declaration input : vertexInterface.inputs()) {
                throw new IllegalArgumentException("POST_VERTEX_INPUT_UNSUPPORTED:" + input.name());
            }
            Map<String, Integer> locations = outputLocations(vertexInterface, match);
            converted = GlslInterfaceScanner.rewrite(LegacyGlslConverter.VERSION_LINE.matcher(converted).replaceAll(""),
                    true,
                    declaration -> {
                        Integer location = locations.get(declaration.name());
                        if (declaration.input() || location == null) {
                            throw new IllegalArgumentException("POST_VERTEX_INPUT_UNSUPPORTED:" + declaration.name());
                        }
                        return interfaceDeclaration(location, declaration, "out");
                    });
            converted = UniformRegistry.removeUniformDeclarations(converted, interfacePlan);
            converted = LegacyGlslConverter.KNOWN_LEGACY_EXTENSIONS.matcher(converted).replaceAll("");
            converted = LegacyGlslConverter.removePackMetadataConstants(converted);
            converted = LegacyGlslConverter.stripUnusedConsumedConsts(converted, packConstants);
            converted = LegacyGlslConverter.injectPackConstants(converted, packConstants);
            converted = LegacyGlslConverter.convertTextureCalls(converted);
            converted = bindPostSamplers(converted, interfacePlan);
            converted = GlslTokenRewriter.replaceIdentifiers(converted, fullscreenInputs());
            converted = converted.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*[0-7]\\s*\\]", "mat4(1.0)");
            converted = converted.replaceAll("\\bftransform\\s*\\(\\s*\\)", "chimeraFullscreenPosition()");
            requireServedBuiltins(converted);
            // The pack positions geometry in GL clip space, where NDC y = -1 is image row 0.
            // Post passes keep VulkanMod's negative-height viewport, which maps y = -1 to the
            // last row, so the authored position is mirrored once here. Packs that draw a
            // partial quad and address it by gl_FragCoord (Bliss's exposure texel) need this.
            converted = converted.replaceFirst("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)", "void chimeraPostMain()");
            converted += "\nvoid main() { chimeraPostMain(); gl_Position.y = -gl_Position.y; }\n";
            String uniforms = interfacePlan.executableUniforms().isEmpty() ? ""
                    : FragmentConverter.generatedUniformBlock(interfacePlan.executableUniforms(), converted);
            return new PostVertexConversion("#version 460\n" + uniforms + GlslVertexPreambles.FULLSCREEN_VERTEX_INPUTS
                    + converted,
                    List.of("POST_VERTEX_AUTHORED_TRANSLATED"));
        } catch (RuntimeException failure) {
            return new PostVertexConversion(null, List.of(failure.getMessage() == null
                    ? "POST_VERTEX_TRANSLATION_UNSUPPORTED" : failure.getMessage()));
        }
    }

    /** Both stage interfaces are present and the program match and uniform plan are executable. */
    private static boolean executableInterfaces(
            GlslInterfaceScanner.StageInterface vertexInterface,
            GlslInterfaceScanner.StageInterface fragmentInterface,
            GlslInterfaceScanner.ProgramMatch match,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return vertexInterface != null && fragmentInterface != null && match != null && match.executable()
                && interfacePlan != null && interfacePlan.executable();
    }

    /**
     * Matched outputs keep the fragment's locations; unread outputs follow them, each
     * taking as many locations as its type occupies.
     */
    private static Map<String, Integer> outputLocations(GlslInterfaceScanner.StageInterface vertexInterface,
                                                        GlslInterfaceScanner.ProgramMatch match) {
        Map<String, Integer> locations = new TreeMap<>(match.locations());
        int nextLocation = 0;
        for (GlslInterfaceScanner.Declaration output : vertexInterface.outputs()) {
            Integer location = locations.get(output.name());
            if (location != null) nextLocation = Math.max(nextLocation, location + output.slots());
        }
        for (GlslInterfaceScanner.Declaration output : vertexInterface.outputs()) {
            if (!locations.containsKey(output.name())) {
                locations.put(output.name(), nextLocation);
                nextLocation += output.slots();
            }
        }
        return locations;
    }

    /** Binds each declared sampler after the uniform block, if any, and rewrites its shadow calls. */
    private static String bindPostSamplers(String source, UniformRegistry.ProgramInterface interfacePlan) {
        String converted = source;
        int samplerBase = interfacePlan.executableUniforms().isEmpty() ? 0 : 1;
        Map<String, String> samplerTypes = new TreeMap<>();
        for (UniformRegistry.SamplerBinding sampler : interfacePlan.samplers()) {
            samplerTypes.put(sampler.name(), sampler.glslType());
            if (FragmentConverter.hasSamplerDeclaration(converted, sampler.name())) {
                converted = FragmentConverter.rewriteSamplerDeclaration(converted, sampler.name(),
                        samplerBase + interfacePlan.samplerIndex(sampler.name()));
            }
        }
        return GlslTokenRewriter.rewriteShadowCalls(converted, samplerTypes);
    }

    /** The legacy vertex inputs as the fullscreen triangle serves them. */
    private static Map<String, String> fullscreenInputs() {
        Map<String, String> inputs = new TreeMap<>();
        inputs.put("gl_Vertex", "vec4(chimeraFullscreenUv(), 0.0, 1.0)");
        inputs.put("gl_MultiTexCoord0", "vec4(chimeraFullscreenUv(), 0.0, 1.0)");
        // A fullscreen draw has only position/UV0, exactly as the legacy
        // fullscreen contract: other texture coordinates have no mesh data.
        for (int unit = 1; unit < 8; unit++) {
            inputs.put("gl_MultiTexCoord" + unit, "vec4(0.0, 0.0, 0.0, 1.0)");
        }
        inputs.put("gl_Color", "vec4(1.0)");
        inputs.put("gl_Normal", "vec3(0.0, 0.0, 1.0)");
        inputs.put("gl_ModelViewMatrix", "mat4(1.0)");
        inputs.put("gl_ModelViewProjectionMatrix", "chimeraFullscreenProjection()");
        inputs.put("gl_ProjectionMatrix", "chimeraFullscreenProjection()");
        inputs.put("gl_NormalMatrix", "mat3(1.0)");
        inputs.put("gl_VertexID", "gl_VertexIndex");
        return inputs;
    }

    /** Built-ins a converted post vertex may still name; any other gl_ name has no fullscreen value. */
    private static final Set<String> SERVED_POST_BUILTINS = Set.of("gl_Position", "gl_VertexIndex",
            "gl_InstanceIndex", "gl_PointSize", "gl_ClipDistance", "gl_CullDistance");

    private static void requireServedBuiltins(String converted) {
        for (GlslLexer.Token token : GlslLexer.lex(converted)) {
            if (token.text().startsWith("gl_") && !SERVED_POST_BUILTINS.contains(token.text())) {
                throw new IllegalArgumentException("POST_VERTEX_BUILTIN_UNSUPPORTED:" + token.text());
            }
        }
    }

    /** The fixed fullscreen vertex produces one vec2 input at location zero. */
    static String convertPostVaryings(String source) {
        List<PostVaryingDeclaration> declarations = postVaryingDeclarations(source);
        List<String> referenced = referencedPostVaryings(source, declarations);
        if (referenced.size() > 1) {
            throw new IllegalArgumentException("post shader requires unsupported varying inputs: " + referenced);
        }

        StringBuilder converted = new StringBuilder();
        int last = 0;
        for (PostVaryingDeclaration declaration : declarations) {
            converted.append(source, last, declaration.start());
            for (String name : declaration.names()) {
                if (!referenced.contains(name)) {
                    continue;
                }
                if (!declaration.type().equals("vec2")
                        || (declaration.qualifier() != null
                        && !declaration.qualifier().equals("noperspective"))) {
                    throw new IllegalArgumentException("post varying is not a fixed vec2 input: " + name);
                }
                converted.append("layout(location = 0) in vec2 ").append(name).append(';');
            }
            last = declaration.end();
        }
        converted.append(source, last, source.length());
        return converted.toString();
    }

    /** Gives each read fragment input the location its paired vertex output writes. */
    static String convertPostVaryings(String source, PostVaryingLayout layout) {
        return GlslInterfaceScanner.rewrite(source, false, declaration -> {
            if (!declaration.input()) {
                return null;
            }
            if (!declaration.referenced()) {
                return "";
            }
            Integer location = layout.locations().get(declaration.name());
            String type = layout.types().get(declaration.name());
            String zero = location == null ? GlslInterfaceScanner.zeroValue(declaration) : null;
            if (zero != null) {
                // No vertex output writes it: Iris zeroes it (GlslInterfaceScanner.UNWRITTEN_ZEROED).
                return declaration.type() + " " + declaration.name() + " = " + zero + ";\n";
            }
            if (location == null || !declaration.typeWithArray().equals(type)) {
                throw new IllegalArgumentException("post varying is not matched: " + declaration.name());
            }
            return interfaceDeclaration(location, declaration, "in");
        });
    }

    /** {@code layout(location = N) [interpolation] in|out type name[size];} for one name. */
    static String interfaceDeclaration(int location, GlslInterfaceScanner.Declaration declaration,
                                               String direction) {
        StringBuilder result = new StringBuilder("layout(location = ").append(location).append(") ");
        if (declaration.qualifier() != null) {
            result.append(declaration.qualifier()).append(' ');
        }
        return result.append(direction).append(' ').append(declaration.type()).append(' ')
                .append(declaration.declarator()).append(";\n").toString();
    }

    static List<PostVaryingDeclaration> postVaryingDeclarations(String source) {
        List<PostVaryingDeclaration> declarations = new ArrayList<>();
        Matcher matcher = POST_VARYING_DECL.matcher(source);
        while (matcher.find()) {
            declarations.add(new PostVaryingDeclaration(matcher.start(), matcher.end(),
                    matcher.group(1), matcher.group(3), parsePostVaryingNames(matcher.group(4))));
        }
        return declarations;
    }

    static List<String> parsePostVaryingNames(String names) {
        List<String> result = new ArrayList<>();
        for (String value : names.split(",")) {
            String name = value.trim();
            if (!name.matches("[A-Za-z_]\\w*")) {
                throw new IllegalArgumentException("unsupported post varying declaration: " + names);
            }
            result.add(name);
        }
        return result;
    }

    static List<String> referencedPostVaryings(
            String source,
            List<PostVaryingDeclaration> declarations
    ) {
        List<String> referenced = new ArrayList<>();
        for (PostVaryingDeclaration declaration : declarations) {
            for (String name : declaration.names()) {
                String withoutDeclaration = source.substring(0, declaration.start())
                        + source.substring(declaration.end());
                if (LegacyGlslConverter.containsIdentifier(withoutDeclaration, name)) {
                    if (!referenced.contains(name)) {
                        referenced.add(name);
                    }
                }
            }
        }
        return referenced;
    }

    /** Probe-visible reason for a post varying that the fixed vertex cannot produce. */
    static List<String> postVaryingDeviations(String source) {
        String input = source == null ? "" : source;
        List<String> deviations = new ArrayList<>();
        try {
            List<PostVaryingDeclaration> declarations = postVaryingDeclarations(input);
            List<String> referenced = referencedPostVaryings(input, declarations);
            for (PostVaryingDeclaration declaration : declarations) {
                for (String name : declaration.names()) {
                    if (!referenced.contains(name)) {
                        continue;
                    }
                    if (!declaration.type().equals("vec2")
                            || (declaration.qualifier() != null
                            && !declaration.qualifier().equals("noperspective"))) {
                        deviations.add("POST_VARYING_UNSUPPORTED:" + name);
                    }
                }
            }
            if (referenced.size() > 1) {
                for (String name : referenced) {
                    deviations.add("POST_VARYING_UNSUPPORTED:" + name);
                }
            }
        } catch (IllegalArgumentException e) {
            deviations.add("POST_VARYING_UNSUPPORTED:declaration");
        }
        return deviations.stream().distinct().sorted().toList();
    }

    /**
     * A fragment input the vertex stage does not write and the fragment never reads (Bliss's
     * Flashing outside the program that sets it). GL links such a declaration as dead, so Iris
     * accepts it; it is dropped. One that is read stays an error, as it is a link error in GL.
     */
    static boolean unproducedAndUnread(String source, Matcher declaration, String name,
                                               LegacyGlslConverter.TerrainVaryingLayout layout) {
        if (layout == null || layout.locations().containsKey(name)) {
            return false;
        }
        return !LegacyGlslConverter.containsIdentifier(source.substring(0, declaration.start())
                + source.substring(declaration.end()), name);
    }

    static void validatePostVersion(String source) {
        GlslVersion.requireSupported(source, "post", false);
    }

    record PostVarying(int start, int end, String type, String name) {}

    record PostVaryingDeclaration(
            int start,
            int end,
            String qualifier,
            String type,
            List<String> names
    ) {}
}
