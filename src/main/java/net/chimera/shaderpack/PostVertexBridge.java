package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Converts a post program's authored vertex stage and pairs its varyings with the fragment stage;
 * a post program without one draws on Chimera's fullscreen vertex.
 */
final class PostVertexBridge {
    private PostVertexBridge() {}

    private static final Set<String> INTERPOLATION = Set.of("flat", "noperspective", "smooth", "centroid", "sample");
    private static final Set<String> INPUT_STORAGE = Set.of("in", "varying");

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
        String result = LegacyGlslConverter.withoutVersionLines(source);
        result = LegacyGlslConverter.withoutKnownLegacyExtensions(result);
        result = LegacyGlslConverter.withoutPrecisionStatements(result);
        result = withoutInputLayouts(result);
        result = GlslTokenRewriter.replaceIdentifiers(result, Map.of(
                "lowp", "", "mediump", "", "highp", ""));
        return GlslTokenRewriter.relaxNonConstantGlobals(result);
    }

    /**
     * Post inputs get Chimera's varying locations, and outputs keep theirs for
     * PostTargetPlan.modernOutputs: an input's {@code layout(...)} goes with the space after it,
     * and its interpolation and storage qualifiers stay.
     */
    private static String withoutInputLayouts(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            List<GlslTree.Qualifier> qualifiers = switch (external) {
                case GlslTree.Declaration declaration -> declaration.qualifiers();
                case GlslTree.InterfaceBlock block -> block.qualifiers();
                default -> List.of();
            };
            if (qualifiers.size() < 2 || !keyword(tree, qualifiers.get(0)).equals("layout")) continue;
            int storage = INTERPOLATION.contains(keyword(tree, qualifiers.get(1))) && qualifiers.size() > 2 ? 2 : 1;
            if (INPUT_STORAGE.contains(keyword(tree, qualifiers.get(storage)))) {
                edits.add(new GlslDeclarations.TextEdit(tree.offset(qualifiers.get(0).range().start()),
                        tree.offset(qualifiers.get(1).range().start()), ""));
            }
        }
        return GlslDeclarations.apply(source, edits);
    }

    /** A legacy {@code attribute} declaration, read as the vertex input it is. */
    private static String attributesAsInputs(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Declaration declaration)) continue;
            for (GlslTree.Qualifier qualifier : declaration.qualifiers()) {
                if (keyword(tree, qualifier).equals("attribute")) {
                    edits.add(new GlslDeclarations.TextEdit(tree.offset(qualifier.keyword()),
                            tree.offset(qualifier.keyword() + 1), "in"));
                }
            }
        }
        return GlslDeclarations.apply(source, edits);
    }

    private static String keyword(GlslTree tree, GlslTree.Qualifier qualifier) {
        return tree.text(qualifier.keyword());
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
            if (GlslDeclarations.main(GlslTree.parse(converted)) == null) {
                throw new IllegalArgumentException("POST_VERTEX_MAIN_MISSING");
            }
            for (GlslInterfaceScanner.Declaration input : vertexInterface.inputs()) {
                throw new IllegalArgumentException("POST_VERTEX_INPUT_UNSUPPORTED:" + input.name());
            }
            Map<String, Integer> locations = outputLocations(vertexInterface, match);
            converted = attributesAsInputs(converted);
            converted = GlslInterfaceScanner.rewrite(LegacyGlslConverter.withoutVersionLines(converted),
                    true,
                    declaration -> {
                        // A fullscreen pass feeds no vertex attributes; Iris leaves an unread
                        // one unbound (I Like Vanilla's composites declare mc_Entity in a header).
                        if (declaration.input() && !declaration.referenced()) return "";
                        Integer location = locations.get(declaration.name());
                        if (declaration.input() || location == null) {
                            throw new IllegalArgumentException("POST_VERTEX_INPUT_UNSUPPORTED:" + declaration.name());
                        }
                        return interfaceDeclaration(location, declaration, "out");
                    });
            converted = UniformRegistry.removeUniformDeclarations(converted, interfacePlan);
            converted = LegacyGlslConverter.withoutKnownLegacyExtensions(converted);
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
            converted = GlslDeclarations.renameMain(converted, "chimeraPostMain");
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

    /**
     * The inputs a post source declares as {@code [interpolation] in|varying type names;} at the
     * start of a line. A declaration's span starts at the first line start after the text before
     * it, so the blank lines and indentation before it go with it when it is replaced.
     */
    static List<PostVaryingDeclaration> postVaryingDeclarations(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<PostVaryingDeclaration> declarations = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Declaration declaration) || declaration.type() == null) continue;
            int start = GlslDeclarations.lineStartBefore(source, tree.offset(declaration.range().start()));
            PostVaryingDeclaration varying = start < 0 ? null
                    : postVarying(tree, declaration, start, tree.offset(declaration.range().end()));
            if (varying != null) declarations.add(varying);
        }
        return declarations;
    }

    /**
     * One input declaration; null for another declaration. An input with more than one leading
     * interpolation qualifier, a precision qualifier, an array or an initializer is refused.
     */
    private static PostVaryingDeclaration postVarying(GlslTree tree, GlslTree.Declaration declaration, int start,
                                                      int end) {
        List<String> qualifiers = declaration.qualifiers().stream().map(value -> keyword(tree, value)).toList();
        int storage = !qualifiers.isEmpty() && INTERPOLATION.contains(qualifiers.get(0)) ? 1 : 0;
        if (qualifiers.size() <= storage || !INPUT_STORAGE.contains(qualifiers.get(storage))) return null;
        boolean plain = qualifiers.size() == storage + 1 && declaration.type().name() >= 0
                && declaration.type().arrays().isEmpty() && declaration.declarators().stream()
                .allMatch(value -> value.arrays().isEmpty() && value.initializer() == null);
        if (!plain) {
            throw new IllegalArgumentException("unsupported post varying declaration: "
                    + tree.text(declaration.range()));
        }
        List<String> names = declaration.declarators().stream().map(value -> tree.text(value.name())).toList();
        return new PostVaryingDeclaration(start, end, storage == 1 ? qualifiers.get(0) : null,
                tree.text(declaration.type().name()), names);
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
    static boolean unproducedAndUnread(String source, int start, int end, String name,
                                               LegacyGlslConverter.TerrainVaryingLayout layout) {
        if (layout == null || layout.locations().containsKey(name)) {
            return false;
        }
        return !LegacyGlslConverter.containsIdentifier(source.substring(0, start)
                + source.substring(end), name);
    }

    static void validatePostVersion(String source) {
        GlslVersion.requireSupported(source, "post", false);
    }

    record PostVaryingDeclaration(
            int start,
            int end,
            String qualifier,
            String type,
            List<String> names
    ) {}
}
