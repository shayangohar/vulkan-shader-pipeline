package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a legacy OptiFine-style fragment shader (#version 120/120e,
 * varying, gl_FragColor/gl_FragData, texture2D, OptiFine texture-name
 * uniforms) into modern Vulkan GLSL: #version 460, explicit varying
 * locations, samplers declared on layout(binding = N) matching the generated
 * pipeline config, #include inlined relative to the including file.
 * Deterministic, no external state; returns null (never throws) so the caller
 * falls back to the identity pipeline.
 *
 * <p>Two stages:
 * <ul>
 * <li>POST (geometryStage=false): fullscreen seams; varying -> location 0,
 *     an optional generated pack UBO at binding 0, then samplers at binding
 *     1,2,... . No-uniform programs keep sampler binding 0,1,... .
 * <li>GEOMETRY/SHADOW/TRANSLUCENT (geometryStage=true): the fixed chimera terrain vertex's
 *     outputs consumed by name (color->0, texcoord->1, lightSpacePos->5);
 *     samplers at bindings 3+i (three UBO blocks precede them in the terrain
 *     config); gl_FragData[N] single-target output (target 0 kept, higher
 *     targets stripped - M4 passes have one color attachment).
 * </ul>
 */
public final class LegacyGlslConverter {
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^\\s*#version\\s+\\S+.*$");
    private static final Pattern VARYING_DECL =
            Pattern.compile("(?m)^\\s*varying\\s+(float|vec2|vec3|vec4)\\s+(\\w+)\\s*;");
    private static final Pattern POST_VARYING_DECL = Pattern.compile(
            "(?m)^\\s*(?:(flat|noperspective|smooth|centroid|sample)\\s+)?"
                    + "(varying|in)\\s+([A-Za-z_]\\w*)\\s+([^;]+);");
    private static final Pattern TERRAIN_VARYING_DECL =
            Pattern.compile("(?m)\\bvarying\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;");
    private static final Pattern ENTITY_VARYING_DECL = Pattern.compile(
            "(?m)^\\s*(?:(flat|noperspective)\\s+)?(varying|in|out)\\s+"
                    + "(float|vec2|vec3|vec4)\\s+(\\w+)\\s*;");
    /**
     * Legacy built-ins with token mappings in the entity vertex inputs
     * table. Every other gl_ builtin fails closed with its own name.
     */
    private static final Set<String> ENTITY_SERVED_BUILTINS = Set.of(
            "gl_Vertex", "gl_Color", "gl_MultiTexCoord0", "gl_MultiTexCoord1",
            "gl_MultiTexCoord2", "gl_Normal", "gl_NormalMatrix", "gl_ModelViewMatrix",
            "gl_ModelViewProjectionMatrix", "gl_ProjectionMatrix", "gl_TextureMatrix",
            "gl_Position");
    private static final Pattern TERRAIN_ATTRIBUTE_DECL =
            Pattern.compile("(?m)\\battribute\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;");
    private static final Pattern SHADOW_ATTRIBUTE_DECL = Pattern.compile(
            "(?m)^[ \\t]*attribute[ \\t]+([A-Za-z_]\\w*)[ \\t]+"
                    + "(mc_Entity|mc_midTexCoord|at_midBlock)[ \\t]*;[ \\t]*(?:\\r?\\n|$)");
    private static final Pattern MODERN_TERRAIN_DECL = Pattern.compile(
            "(?m)^\\s*(?:(flat|noperspective|smooth|centroid|sample)\\s+)?"
                    + "(in|out|varying)\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;");
    private static final Pattern MODERN_VARYING_LIST_DECL = Pattern.compile(
            "(?m)^([ \\t]*)(?:(flat|noperspective|smooth|centroid|sample)\\s+)?"
                    + "(in|out|varying)\\s+(float|int|vec2|vec3|vec4)\\s+"
                    + "([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)+)\\s*;");
    private static final Pattern MODERN_TERRAIN_INPUT_DECL = Pattern.compile(
            "(?m)^\\s*(?:(flat|noperspective|smooth|centroid|sample)\\s+)?"
                    + "(?:in|varying)\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;");
    private static final Pattern MODERN_GEOMETRY_OUTPUT_DECL = Pattern.compile(
            "(?m)^\\s*out\\s+vec4\\s+(\\w+)\\s*;\\s*");
    private static final Pattern TERRAIN_VERSION =
            Pattern.compile("(?m)^\\s*#version\\s+120(?:e)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY_VERSION = Pattern.compile(
            "(?im)^\\s*#version\\s+(?:120e?|130)(?:\\s+.*)?$");
    private static final Pattern ENTITY_ID_DECLARATION = Pattern.compile(
            "(?m)\\b(uniform|attribute|in)\\s+(int|float)\\s+"
                    + "([^;{}]*\\b(?:entityId|blockEntityId)\\b[^;{}]*);\\s*");
    /**
     * Iris EntityPatcher identities: each name reads one component of the
     * per-vertex EntityIds input (Iris iris_Entity), passed flat to fragments.
     */
    private static final List<String> ENTITY_ID_NAMES = List.of("entityId", "blockEntityId");
    private static final String ENTITY_ID_VARYING = "chimeraEntityId";

    private static String entityIdVarying(String name) {
        return name.equals("entityId") ? ENTITY_ID_VARYING : "chimeraBlockEntityId";
    }

    private static String entityIdComponent(String name) {
        return name.equals("entityId") ? "x" : "y";
    }
    private static final Pattern POST_DRAWBUFFERS_DEFINE = Pattern.compile(
            "(?m)^\\s*#define\\s+DRAWBUFFERS[0-9]+\\s*$");
    /** Pack metadata declarations consumed by PackConfig, not executable GLSL. */
    private static final Pattern CONSUMED_CONSTS =
            Pattern.compile("(?m)^\\s*(?:const\\s+)?(?:int|float|bool|vec4)\\s+(colortex\\d+Format|gaux\\d+Format|colortex\\d+(?:Clear|ClearColor|MipmapEnabled)|shadowMapResolution|shadowDistance|shadowMapDistance|shadowMapSize|shadowMapFov|shadowDistanceRenderMul|sunPathRotation|sunPathOffset)\\s*=\\s*[A-Za-z0-9+_.(), /-]+\\s*;\\s*(?://.*)?$");

    /**
     * Removes consumed metadata constants unless something reads them
     * without a replacement. A referenced constant stays when no pack
     * constant will be injected for it (BSL's shadowMapBias initializer
     * reading shadowDistance); it goes when injection supplies the value,
     * and dead ones always go.
     */
    private static String stripUnusedConsumedConsts(
            String source, Map<String, String> packConstants) {
        if (source == null || source.isBlank()) return source;
        String checkSource = CONSUMED_CONSTS.matcher(source).replaceAll("");
        Matcher matcher = CONSUMED_CONSTS.matcher(source);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            boolean injected = packConstants != null && packConstants.containsKey(name);
            boolean referenced =
                    GlslTokenRewriter.containsIdentifier(checkSource, name);
            matcher.appendReplacement(output,
                    !referenced || injected ? "" : Matcher.quoteReplacement(matcher.group(0)));
        }
        matcher.appendTail(output);
        return output.toString();
    }
    private static final Pattern KNOWN_LEGACY_EXTENSIONS = Pattern.compile(
            "(?im)^\\s*#extension\\s+GL_ARB_shader_texture_lod\\s*:\\s*(?:enable|require|disable)\\s*$\\r?\\n?");
    private static final Pattern MODERN_LAYOUT_DECL = Pattern.compile(
            "(?m)^([ \\t]*)layout\\s*\\([^;{}\\r\\n]*\\)\\s*((?:(?:flat|noperspective|smooth|centroid|sample)\\s+)?)"
                    + "(in|out|varying)\\b");
    /** Post inputs get Chimera's varying locations; outputs keep theirs for PostTargetPlan.modernOutputs. */
    private static final Pattern MODERN_INPUT_LAYOUT_DECL = Pattern.compile(
            "(?m)^([ \\t]*)layout\\s*\\([^;{}()]*\\)\\s*((?:(?:flat|noperspective|smooth|centroid|sample)\\s+)?)"
                    + "(in|varying)\\b");
    private static final Pattern PRECISION_DECL = Pattern.compile(
            "(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+(?:float|int)\\s*;\\s*$\\r?\\n?");

    /** Geometry fragments receive the fixed chimera terrain vertex's outputs by name. */
    private static final Map<String, Integer> GEOMETRY_VARYING_LOCATIONS = Map.of(
            "color", 0,
            "texcoord", 1,
            "lightSpacePos", 5
    );
    /** Three UBO blocks precede samplers in the terrain pipeline config. */
    private static final int GEOMETRY_SAMPLER_BINDING_BASE = 3;

    static int geometrySamplerBindingBase() {
        return GEOMETRY_SAMPLER_BINDING_BASE;
    }

    private LegacyGlslConverter() {}

    /**
     * Normalizes the measured modern post subset before interface scanning.
     * This is deliberately narrow: the existing token translator remains the
     * only executable rewrite path and unsupported versions fail closed.
     */
    static String normalizeModernPost(String source) {
        if (source == null) {
            throw new IllegalArgumentException("post source is missing");
        }
        Matcher versions = Pattern.compile("(?im)^\\s*#version\\s+(\\d+)(?:\\s+.*)?$")
                .matcher(source);
        boolean found = false;
        while (versions.find()) {
            found = true;
            int version = Integer.parseInt(versions.group(1));
            if (version != 120 && version != 130 && version != 330 && version != 400) {
                throw new IllegalArgumentException("unsupported modern post GLSL version: " + version);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("post source is missing a GLSL version");
        }
        String result = VERSION_LINE.matcher(source).replaceAll("");
        result = KNOWN_LEGACY_EXTENSIONS.matcher(result).replaceAll("");
        result = PRECISION_DECL.matcher(result).replaceAll("");
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

    /** Deterministic interface shared by the narrow terrain vertex and fragment bridge. */
    public record TerrainVaryingLayout(Map<String, String> types, Map<String, Integer> locations,
            Map<String, String> qualifiers) {
        public TerrainVaryingLayout(Map<String, String> types, Map<String, Integer> locations) {
            this(types, locations, Map.of());
        }
        public TerrainVaryingLayout {
            types = Collections.unmodifiableMap(new TreeMap<>(types));
            locations = Collections.unmodifiableMap(new TreeMap<>(locations));
            qualifiers = Collections.unmodifiableMap(new TreeMap<>(qualifiers));
        }

        /** Interpolation qualifier carried from the authored declaration, or empty when smooth. */
        public String qualifier(String name) {
            String qualifier = qualifiers.get(name);
            return qualifier == null ? "" : qualifier;
        }

        public int location(String name) {
            Integer location = locations.get(name);
            if (location == null) {
                throw new IllegalArgumentException("terrain varying is not produced by the vertex stage: " + name);
            }
            return location;
        }
    }

    /** Converted terrain vertex source plus the interface used by its fragment stage. */
    public record TerrainVertexConversion(String source, TerrainVaryingLayout layout) {}

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

    /**
     * Immutable inputs for one fragment conversion. Replaces the former
     * positional overload ladder: callers start from {@link #of} or
     * {@link #withAutoPlan} and add only the plans their stage needs.
     * Missing plans keep the old overload defaults (empty or disabled),
     * so migrating a caller is behavior-preserving by construction.
     */
    public record FragmentConversionRequest(
            String source,
            Path sourceFile,
            boolean geometryStage,
            int[] geometrySamplerSlots,
            TerrainVaryingLayout terrainLayout,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan,
            GeometryOutputPlan geometryOutputPlan,
            Map<String, String> packConstants,
            PostVaryingLayout postVaryingLayout,
            Set<String> atlasSamplers,
            PackAlphaTestPlan alphaTestPlan
    ) {
        public FragmentConversionRequest {
            packConstants = packConstants == null ? Map.of() : packConstants;
            atlasSamplers = atlasSamplers == null ? Set.of() : atlasSamplers;
            alphaTestPlan = alphaTestPlan == null ? PackAlphaTestPlan.disabled() : alphaTestPlan;
        }

        /** Minimal request; every plan defaults to empty or disabled. */
        public static FragmentConversionRequest of(String source, Path sourceFile,
                boolean geometryStage, int[] geometrySamplerSlots) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    null, null, null, null, Map.of(), null, Set.of(), PackAlphaTestPlan.disabled());
        }

        /** Minimal request with the interface plan derived from the source, as the old shims did. */
        public static FragmentConversionRequest withAutoPlan(String source, Path sourceFile,
                boolean geometryStage, int[] geometrySamplerSlots, TerrainVaryingLayout terrainLayout) {
            return of(source, sourceFile, geometryStage, geometrySamplerSlots)
                    .withTerrainLayout(terrainLayout)
                    .withInterfacePlan(UniformRegistry.plan(source, stageOf(geometryStage)));
        }

        public FragmentConversionRequest withTerrainLayout(TerrainVaryingLayout terrainLayout) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withInterfacePlan(UniformRegistry.ProgramInterface interfacePlan) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withTargetPlan(PostTargetPlan targetPlan) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withGeometryOutputPlan(GeometryOutputPlan geometryOutputPlan) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withPackConstants(Map<String, String> packConstants) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withPostVaryingLayout(PostVaryingLayout postVaryingLayout) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withAtlasSamplers(Set<String> atlasSamplers) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        public FragmentConversionRequest withAlphaTestPlan(PackAlphaTestPlan alphaTestPlan) {
            return new FragmentConversionRequest(source, sourceFile, geometryStage, geometrySamplerSlots,
                    terrainLayout, interfacePlan, targetPlan, geometryOutputPlan,
                    packConstants, postVaryingLayout, atlasSamplers, alphaTestPlan);
        }

        /** Runs the conversion; returns null when the source is outside the executable contract. */
        public String convert() {
            return convertFragment(this);
        }
    }

    /** Converts a post fragment with the M5.6 output target plan. */
    public static String convertPostFragment(
            String source,
            Path sourceFile,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan
    ) {
        return convertPostFragment(source, sourceFile, interfacePlan, targetPlan, Map.of());
    }

    /** Converts a post fragment using the pack constants already parsed by PackConfig. */
    public static String convertPostFragment(
            String source,
            Path sourceFile,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan,
            Map<String, String> packConstants
    ) {
        return FragmentConversionRequest.of(source, sourceFile, false, null)
                .withInterfacePlan(interfacePlan)
                .withTargetPlan(targetPlan)
                .withPackConstants(packConstants)
                .convert();
    }

    /** Converts a post fragment against a paired fullscreen vertex layout. */
    public static String convertPostFragment(
            String source,
            Path sourceFile,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan,
            Map<String, String> packConstants,
            Map<String, Integer> varyingLocations,
            Map<String, String> varyingTypes
    ) {
        PostVaryingLayout layout = varyingLocations == null
                ? null : new PostVaryingLayout(varyingLocations, varyingTypes);
        return FragmentConversionRequest.of(source, sourceFile, false, null)
                .withInterfacePlan(interfacePlan)
                .withTargetPlan(targetPlan)
                .withPackConstants(packConstants)
                .withPostVaryingLayout(layout)
                .convert();
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
            if (source == null || vertexInterface == null || fragmentInterface == null
                    || match == null || !match.executable() || interfacePlan == null
                    || !interfacePlan.executable()) {
                throw new IllegalArgumentException("POST_VERTEX_INTERFACE_UNSUPPORTED");
            }
            String converted = prepareSource(source, null, true);
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
            // Matched outputs keep the fragment's locations; unread outputs follow them, each
            // taking as many locations as its type occupies.
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
            converted = GlslInterfaceScanner.rewrite(VERSION_LINE.matcher(converted).replaceAll(""), true,
                    declaration -> {
                        Integer location = locations.get(declaration.name());
                        if (declaration.input() || location == null) {
                            throw new IllegalArgumentException("POST_VERTEX_INPUT_UNSUPPORTED:" + declaration.name());
                        }
                        return interfaceDeclaration(location, declaration, "out");
                    });
            converted = UniformRegistry.removeUniformDeclarations(converted, interfacePlan);
            converted = KNOWN_LEGACY_EXTENSIONS.matcher(converted).replaceAll("");
            converted = removePackMetadataConstants(converted);
            converted = stripUnusedConsumedConsts(converted, packConstants);
            converted = injectPackConstants(converted, packConstants);
            converted = convertTextureCalls(converted);
            int samplerBase = interfacePlan.executableUniforms().isEmpty() ? 0 : 1;
            Map<String, String> samplerTypes = new TreeMap<>();
            for (UniformRegistry.SamplerBinding sampler : interfacePlan.samplers()) {
                samplerTypes.put(sampler.name(), sampler.glslType());
                if (hasSamplerDeclaration(converted, sampler.name())) {
                    converted = rewriteSamplerDeclaration(converted, sampler.name(),
                            samplerBase + interfacePlan.samplerIndex(sampler.name()));
                }
            }
            converted = GlslTokenRewriter.rewriteShadowCalls(converted, samplerTypes);
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
            converted = GlslTokenRewriter.replaceIdentifiers(converted, inputs);
            converted = converted.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*[0-7]\\s*\\]", "mat4(1.0)");
            converted = converted.replaceAll("\\bftransform\\s*\\(\\s*\\)", "chimeraFullscreenPosition()");
            for (GlslLexer.Token token : GlslLexer.lex(converted)) {
                if (token.text().startsWith("gl_") && !Set.of("gl_Position", "gl_VertexIndex",
                        "gl_InstanceIndex", "gl_PointSize", "gl_ClipDistance", "gl_CullDistance").contains(token.text())) {
                    throw new IllegalArgumentException("POST_VERTEX_BUILTIN_UNSUPPORTED:" + token.text());
                }
            }
            String uniforms = interfacePlan.executableUniforms().isEmpty() ? ""
                    : generatedUniformBlock(interfacePlan.executableUniforms());
            return new PostVertexConversion("#version 460\n" + uniforms + FULLSCREEN_VERTEX_INPUTS + converted,
                    List.of("POST_VERTEX_AUTHORED_TRANSLATED"));
        } catch (RuntimeException failure) {
            return new PostVertexConversion(null, List.of(failure.getMessage() == null
                    ? "POST_VERTEX_TRANSLATION_UNSUPPORTED" : failure.getMessage()));
        }
    }

    private static final String FULLSCREEN_VERTEX_INPUTS = """
            vec2 chimeraFullscreenUv() {
                return vec2(float((gl_VertexIndex << 1) & 2), 1.0 - float(gl_VertexIndex & 2));
            }
            mat4 chimeraFullscreenProjection() {
                return mat4(2.0, 0.0, 0.0, 0.0, 0.0, -2.0, 0.0, 0.0,
                            0.0, 0.0, 1.0, 0.0, -1.0, 1.0, 0.0, 1.0);
            }
            vec4 chimeraFullscreenPosition() {
                return chimeraFullscreenProjection() * vec4(chimeraFullscreenUv(), 0.0, 1.0);
            }
            """;

    private static String convertFragment(FragmentConversionRequest request) {
        String source = request.source();
        Path sourceFile = request.sourceFile();
        boolean geometryStage = request.geometryStage();
        int[] geometrySamplerSlots = request.geometrySamplerSlots();
        TerrainVaryingLayout terrainLayout = request.terrainLayout();
        UniformRegistry.ProgramInterface interfacePlan = request.interfacePlan();
        PostTargetPlan targetPlan = request.targetPlan();
        GeometryOutputPlan geometryOutputPlan = request.geometryOutputPlan();
        Map<String, String> packConstants = request.packConstants();
        PostVaryingLayout postVaryingLayout = request.postVaryingLayout();
        Set<String> atlasSamplers = request.atlasSamplers();
        PackAlphaTestPlan alphaTestPlan = request.alphaTestPlan();
        try {
            boolean geometryInterface = interfacePlan != null
                    && (interfacePlan.stage() == UniformRegistry.Stage.GEOMETRY
                    || interfacePlan.stage() == UniformRegistry.Stage.SHADOW
                    || interfacePlan.stage() == UniformRegistry.Stage.TRANSLUCENT
                    || interfacePlan.stage() == UniformRegistry.Stage.ENTITY
                    || interfacePlan.stage() == UniformRegistry.Stage.BLOCK
                    || interfacePlan.stage() == UniformRegistry.Stage.HAND
                    || interfacePlan.stage() == UniformRegistry.Stage.PARTICLE
                    || interfacePlan.stage() == UniformRegistry.Stage.SKY
                    || interfacePlan.stage() == UniformRegistry.Stage.CLOUD);
            if (interfacePlan == null || !interfacePlan.executable()
                    || geometryInterface != geometryStage) {
                throw new IllegalArgumentException("pack interface is outside the executable contract");
            }
            if (targetPlan != null && (geometryStage || interfacePlan.stage() != UniformRegistry.Stage.POST
                    || !targetPlan.executable())) {
                throw new IllegalArgumentException("post target plan is outside the executable contract");
            }
            if (geometryOutputPlan != null && (!geometryStage || !geometryOutputPlan.executable())) {
                throw new IllegalArgumentException("geometry output plan is outside the executable contract");
            }
            PackAlphaTestPlan alpha = alphaTestPlan;
            String src = source;
            if (src == null) {
                throw new IllegalArgumentException("pack fragment source is missing");
            }
            src = prepareSource(src, sourceFile, false);
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(src);
            if (!usage.successful()) {
                throw new IllegalArgumentException(usage.deviations().toString());
            }
            src = GlslTokenRewriter.removeUnreachableFunctions(src, usage);

            boolean modern = false;
            if (!geometryStage) {
                validatePostVersion(src);
                src = VERSION_LINE.matcher(src).replaceAll("");
            } else {
                modern = src.contains("#version 460") || src.contains("#version 450")
                        || (interfacePlan.stage() == UniformRegistry.Stage.SHADOW
                        && MODERN_TERRAIN_DECL.matcher(src).find());
                if (interfacePlan.stage() == UniformRegistry.Stage.SHADOW && modern) {
                    src = VERSION_LINE.matcher(src).replaceAll("");
                    src = "#version 460\n" + src;
                }
                if (!modern) {
                    src = VERSION_LINE.matcher(src).replaceAll("");
                }
            }

            src = removePackMetadataConstants(src);
            src = stripUnusedConsumedConsts(src, packConstants);
            src = KNOWN_LEGACY_EXTENSIONS.matcher(src).replaceAll("");
            src = UniformRegistry.removeUniformDeclarations(src, interfacePlan);
            if (interfacePlan.stage() == UniformRegistry.Stage.ENTITY
                    || interfacePlan.stage() == UniformRegistry.Stage.BLOCK
                    || interfacePlan.stage() == UniformRegistry.Stage.HAND) {
                src = removeEntityIdDeclarations(src, interfacePlan);
                src = replaceEntityIdReferences(src, terrainLayout);
            }
            if (interfacePlan.stage() == UniformRegistry.Stage.ENTITY
                    || interfacePlan.stage() == UniformRegistry.Stage.BLOCK
                    || interfacePlan.stage() == UniformRegistry.Stage.HAND
                    || interfacePlan.stage() == UniformRegistry.Stage.PARTICLE) {
                src = rewriteElytraFlyingBool(src, interfacePlan);
            }
            if (geometryStage && modern && geometryOutputPlan == null
                    && interfacePlan.stage() != UniformRegistry.Stage.SHADOW) {
                src = convertModernGeometryOutputs(src);
            }
            src = convertVaryings(src, geometryStage, terrainLayout, postVaryingLayout,
                    interfacePlan.stage());
            src = injectPackConstants(src, packConstants);

            // Emit only the projected stage resources. Descriptor positions still
            // come from the canonical program layout, never the filtered stage order.
            List<UniformRegistry.SamplerBinding> samplers = interfacePlan.samplers();
            int bindingBase = geometryStage
                    ? (interfacePlan.stage() == UniformRegistry.Stage.ENTITY
                    || interfacePlan.stage() == UniformRegistry.Stage.BLOCK
                    || interfacePlan.stage() == UniformRegistry.Stage.HAND
                    || interfacePlan.stage() == UniformRegistry.Stage.PARTICLE)
                    ? (interfacePlan.executableUniforms().isEmpty() ? 2 : 3)
                    : (interfacePlan.stage() == UniformRegistry.Stage.SHADOW
                    && !interfacePlan.executableUniforms().isEmpty()
                    ? GEOMETRY_SAMPLER_BINDING_BASE + 1
                    : GEOMETRY_SAMPLER_BINDING_BASE
                    + (!interfacePlan.executableUniforms().isEmpty() ? 1 : 0))
                    : (interfacePlan.executableUniforms().isEmpty() ? 0 : 1);
            if (geometryStage) {
                // GLSL 460: 'texture' is the sampling function name, so a pack
                // sampler declared as 'uniform sampler2D texture;' (the OptiFine
                // atlas convention) is renamed uniformly. Runs BEFORE
                // convertTextureCalls so the function-name rewrite below
                // creates texture(chimeraTexture, ...) instead of renaming
                // the function into a variable call.
                src = GlslTokenRewriter.renameSamplerIdentifier(src, "texture", "chimeraTexture");
            }
            src = convertTextureCalls(src);
            if (terrainContract(interfacePlan.stage())) {
                Set<String> emittedAtlasSamplers = new java.util.HashSet<>();
                for (String name : atlasSamplers) {
                    emittedAtlasSamplers.add(name.equals("texture") ? "chimeraTexture" : name);
                }
                src = GlslTokenRewriter.rewriteTerrainAtlasSamples(src, emittedAtlasSamplers);
            }
            src = removeLegacyShadowSamplerHelper(src);
            if (GlslTokenRewriter.containsIdentifier(src, "shadow2DProj")
                    || GlslTokenRewriter.containsIdentifier(src, "shadow2DProjLod")) {
                throw new IllegalArgumentException("projected shadow lookup is outside the supported bridge");
            }
            Map<String, String> samplerTypes = new TreeMap<>();
            for (UniformRegistry.SamplerBinding sampler : samplers) {
                samplerTypes.put(sampler.name(), sampler.glslType());
            }
            src = GlslTokenRewriter.rewriteShadowCalls(src, samplerTypes);
            for (int i = 0; i < samplers.size(); i++) {
                String name = samplers.get(i).name();
                String srcName = geometryStage && name.equals("texture") ? "chimeraTexture" : name;
                int binding = geometryStage
                        ? bindingBase + configIndexOf(name, geometrySamplerSlots,
                        interfacePlan.stage(), interfacePlan)
                        : bindingBase + interfacePlan.samplerIndex(name);
                if (binding < bindingBase) {
                    throw new IllegalArgumentException("sampler is missing from the generated config: " + name);
                }
                if (hasSamplerDeclaration(src, srcName)) {
                    src = rewriteSamplerDeclaration(src, srcName, binding);
                }
            }

            String outDecl = null;
            if (targetPlan != null) {
                src = convertPostOutputs(src, targetPlan);
                outDecl = postOutputDeclarations(targetPlan);
            } else if (geometryOutputPlan != null) {
                src = convertGeometryOutputs(src, geometryOutputPlan);
                outDecl = geometryOutputDeclarations(geometryOutputPlan);
            } else if (interfacePlan.stage() == UniformRegistry.Stage.SHADOW) {
                PostTargetPlan shadowOutputs = PostTargetPlan.parse("shadow", src).plan();
                if (!shadowOutputs.executable() || shadowOutputs.targetSlots().stream()
                        .anyMatch(target -> target < 0 || target > 1)) {
                    throw new IllegalArgumentException("SHADOW_OUTPUT_TARGET_UNSUPPORTED");
                }
                src = convertPostOutputs(src, shadowOutputs);
                Map<String, String> outputs = new TreeMap<>();
                for (int location : shadowOutputs.outputLocations()) {
                    outputs.put("chimeraFragColor" + location,
                            "chimeraShadowColor" + shadowOutputs.targetForOutput(location));
                }
                src = GlslTokenRewriter.replaceIdentifiers(src, outputs);
                outDecl = "layout(location = 0) out vec4 chimeraShadowColor0;\n"
                        + "layout(location = 1) out vec4 chimeraShadowColor1;";
            } else if (src.contains("gl_FragColor") || src.contains("gl_FragData")) {
                src = GlslTokenRewriter.rewriteSingleOutput(src);
                // Targets beyond 0 are consumed by nothing in M4's single-attachment passes.
                src = src.replaceAll("gl_FragData\\s*\\[\\s*[1-9][0-9]*\\s*\\]\\s*=\\s*[^;]+;\\s*", "");
                outDecl = "layout(location = 0) out vec4 fragColor;";
            }

            String terrainHostInstance = null;
            if (geometryStage && terrainContract(interfacePlan.stage())
                    && geometryOutputPlan != null && alpha.active()) {
                terrainHostInstance = alpha.needsHostThreshold()
                        ? GlslTokenRewriter.uniqueIdentifier(src, "chimeraTerrainHost") : null;
                String rejection = alpha.rejection(geometryOutputPlan.locationZeroOutputName(),
                        terrainHostInstance);
                if (!rejection.isBlank()) {
                    src = GlslTokenRewriter.appendMainEpilogue(src, rejection);
                }
            }

            // GLSL requires global declarations to precede their first use;
            // an output declared at the end of the file is a forward reference
            // and glslang rejects it ("'fragColor' : undeclared identifier").
            // Emit the declaration directly after the version line.
            String uniformBlock;
            if (interfacePlan.stage() == UniformRegistry.Stage.SHADOW
                    && !interfacePlan.executableUniforms().isEmpty()) {
                uniformBlock = generatedShadowUniformBlock(interfacePlan.executableUniforms());
                src = qualifyShadowUniformReferences(src, interfacePlan.executableUniforms());
            } else if (geometryStage && terrainContract(interfacePlan.stage())) {
                StringBuilder geometryBlocks = new StringBuilder();
                if (terrainHostInstance != null) {
                    geometryBlocks.append(terrainHostUniformBlock(src, terrainHostInstance));
                }
                if (!interfacePlan.executableUniforms().isEmpty()) {
                    geometryBlocks.append(generatedGeometryUniformBlock(interfacePlan.executableUniforms()));
                }
                uniformBlock = geometryBlocks.toString();
            } else if ((interfacePlan.stage() == UniformRegistry.Stage.ENTITY
                    || interfacePlan.stage() == UniformRegistry.Stage.BLOCK
                    || interfacePlan.stage() == UniformRegistry.Stage.HAND
                    || interfacePlan.stage() == UniformRegistry.Stage.PARTICLE)
                    && !interfacePlan.executableUniforms().isEmpty()) {
                uniformBlock = entityUniformBlock(interfacePlan.executableUniforms());
            } else if ((interfacePlan.stage() == UniformRegistry.Stage.SKY
                    || interfacePlan.stage() == UniformRegistry.Stage.CLOUD)
                    && !interfacePlan.executableUniforms().isEmpty()) {
                uniformBlock = entityUniformBlock(interfacePlan.executableUniforms());
            } else if (!geometryStage && !interfacePlan.executableUniforms().isEmpty()) {
                uniformBlock = generatedUniformBlock(interfacePlan.executableUniforms());
            } else {
                uniformBlock = "";
            }
            String chimeraVaryingDeclaration = (interfacePlan.stage() == UniformRegistry.Stage.ENTITY
                    || interfacePlan.stage() == UniformRegistry.Stage.BLOCK
                    || interfacePlan.stage() == UniformRegistry.Stage.HAND)
                    ? chimeraFragmentVaryings(terrainLayout) : "";
            String declarations = chimeraVaryingDeclaration
                    + (outDecl != null ? outDecl + "\n" : "") + uniformBlock;
            if (interfacePlan.stage() == UniformRegistry.Stage.SHADOW) {
                declarations = SHADOW_RENDER_STAGE_DEFINES + declarations;
            }
            if (!geometryStage || !modern) {
                src = "#version 460\n" + declarations + src;
            } else if (!declarations.isEmpty()) {
                src = insertAfterFirstLine(src, declarations);
            }
            return src;
        } catch (Exception e) {
            // The planner reports POST_CONVERTER_UNSUPPORTED; the cause belongs in the log.
            org.slf4j.LoggerFactory.getLogger("chimera").warn(
                    "[chimera] fragment conversion failed: {}", e.toString());
            return null;
        }
    }

    private static String convertPostOutputs(String source, PostTargetPlan targetPlan) {
        String result = POST_DRAWBUFFERS_DEFINE.matcher(source).replaceAll("");
        result = rewriteModernOutputs(result, targetPlan);
        return GlslTokenRewriter.rewritePostOutputs(result, targetPlan);
    }

    /** Keeps the historical single-attachment geometry symbol stable. */
    private static String convertGeometryOutputs(String source, GeometryOutputPlan outputPlan) {
        String result = convertPostOutputs(source, outputPlan.targetPlan());
        if (outputPlan.targetSlots().size() == 1) {
            result = GlslTokenRewriter.replaceIdentifiers(result,
                    Map.of("chimeraFragColor0", "fragColor"));
        }
        return result;
    }

    private static String geometryOutputDeclarations(GeometryOutputPlan outputPlan) {
        String result = postOutputDeclarations(outputPlan.targetPlan());
        if (outputPlan.targetSlots().size() == 1) {
            result = GlslTokenRewriter.replaceIdentifiers(result,
                    Map.of("chimeraFragColor0", "fragColor"));
        }
        return result;
    }

    private static String rewriteModernOutputs(String source, PostTargetPlan targetPlan) {
        if (targetPlan == null) {
            return source;
        }
        List<PostTargetPlan.ModernOutput> outputs = PostTargetPlan.modernOutputs(source);
        if (outputs.isEmpty()) {
            return source;
        }
        StringBuilder result = new StringBuilder();
        Map<String, String> replacements = new TreeMap<>();
        int last = 0;
        for (PostTargetPlan.ModernOutput output : outputs) {
            if (!targetPlan.outputLocations().contains(output.location())) {
                throw new IllegalArgumentException("modern fragment output exceeds target route: " + output.name());
            }
            replacements.put(output.name(), "chimeraFragColor" + output.location());
            // The generated declaration replaces this one; keep its line breaks.
            result.append(source, last, output.start())
                    .append(source.substring(output.start(), output.end()).replaceAll("[^\\r\\n]", ""));
            last = output.end();
        }
        result.append(source.substring(last));
        return GlslTokenRewriter.replaceIdentifiers(result.toString(), replacements);
    }

    private static String postOutputDeclarations(PostTargetPlan targetPlan) {
        StringBuilder declarations = new StringBuilder();
        for (int location : targetPlan.outputLocations()) {
            declarations.append("layout(location = ").append(location).append(") out ")
                    .append(targetPlan.outputType(location))
                    .append(" chimeraFragColor").append(location).append(";\n");
        }
        return declarations.toString();
    }

    /**
     * Converts the strict M5.2 terrain vertex subset. A null result means that
     * the source must use the identity terrain pipeline.
     */
    public static TerrainVertexConversion convertTerrainVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertTerrainVertex(source, sourceFile, fragmentSource, false);
    }

    /** Converts legacy terrain source with storage blocks admitted by the plan. */
    public static TerrainVertexConversion convertTerrainVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            boolean allowStorageBuffers
    ) {
        return convertLegacyVertex(source, sourceFile, fragmentSource,
                TERRAIN_VERTEX_PREAMBLE, allowStorageBuffers);
    }

    /**
     * Converts the bounded modern terrain/water contract. This is intentionally
     * separate from the legacy bridge: modern source must use the shared
     * append-only material inputs and cannot silently fall through to the
     * 24-byte identity layout.
     */
    public static TerrainVertexConversion convertModernTerrainVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                MODERN_TERRAIN_VERTEX_PREAMBLE, null, false, false);
    }

    /** Converts an extended terrain vertex with the shared ordinary-uniform plan. */
    public static TerrainVertexConversion convertModernTerrainVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertModernTerrainVertex(source, sourceFile, fragmentSource,
                interfacePlan, false);
    }

    /** Converts modern terrain source with storage blocks admitted by the plan. */
    public static TerrainVertexConversion convertModernTerrainVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean allowStorageBuffers
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                MODERN_TERRAIN_VERTEX_PREAMBLE, interfacePlan, false, allowStorageBuffers);
    }

    /** Converts the measured modern shadow vertex subset onto the shadow inputs. */
    public static TerrainVertexConversion convertModernShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                MODERN_SHADOW_VERTEX_PREAMBLE, null, true, false);
    }

    /** Converts a shadow vertex with the shared cross-stage uniform plan. */
    public static TerrainVertexConversion convertModernShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                MODERN_SHADOW_VERTEX_PREAMBLE, interfacePlan, true, false);
    }

    private static TerrainVertexConversion convertModernTerrainVertexInternal(
            String source,
            Path sourceFile,
            String fragmentSource,
            String vertexPreamble,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean shadowStage,
            boolean allowStorageBuffers
    ) {
        try {
            boolean extendedShadow = shadowStage && usesExtendedShadowInputs(source);
            String vertex = shadowStage
                    ? normalizeShadowTerrain(defaultUnboundIdentityAttributes(prepareSource(source, sourceFile, true)))
                    : normalizeModernTerrain(defaultUnboundIdentityAttributes(prepareSource(source, sourceFile, true)));
            String fragment = fragmentSource == null ? "" : fragmentSource;
            if (shadowStage && hasShadowTerrainVersion(fragment)) {
                fragment = normalizeShadowTerrain(fragment);
            } else if (hasModernTerrainVersion(fragment)) {
                fragment = normalizeModernTerrain(fragment);
            }
            vertex = expandModernVaryingLists(vertex);
            fragment = expandModernVaryingLists(fragment);
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(vertex);
            if (!usage.successful()) {
                throw new IllegalArgumentException(usage.deviations().toString());
            }
            vertex = GlslTokenRewriter.removeUnreachableFunctions(vertex, usage);
            vertex = interfacePlan == null
                    ? stripShadowUniformDeclarations(vertex)
                    : UniformRegistry.removeUniformDeclarations(vertex, interfacePlan);
            String stripped = stripComments(vertex);
            if (!stripped.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")
                    || !stripped.matches("(?s).*\\bgl_Position\\b.*")) {
                throw new IllegalArgumentException("modern terrain vertex main or position is missing");
            }
            if ((!allowStorageBuffers && stripped.matches("(?s).*\\bbuffer\\b.*"))
                    || stripped.matches("(?s).*\\b(?:geometry|tessellation|compute)\\b.*")
                    || stripped.matches("(?s).*\\buniform\\s+(?!(?:sampler|isampler|usampler|u?i?image(?:1D|2D|3D|Cube)))\\w+.*")) {
                throw new IllegalArgumentException("modern terrain resource or stage is unsupported");
            }

            Map<String, String> vertexTypes = modernTerrainVaryings(vertex, true, true);
            Map<String, String> fragmentTypes = modernTerrainVaryings(fragment, false, true);
            for (Map.Entry<String, String> entry : fragmentTypes.entrySet()) {
                if (!entry.getValue().equals(vertexTypes.get(entry.getKey()))) {
                    throw new IllegalArgumentException("modern terrain varying mismatch: " + entry.getKey());
                }
            }
            Map<String, Integer> locations = new TreeMap<>();
            int location = 0;
            for (String name : vertexTypes.keySet().stream().sorted().toList()) {
                locations.put(name, location++);
            }
            TerrainVaryingLayout layout = new TerrainVaryingLayout(vertexTypes, locations);

            // The prepared source keeps one version directive so the shared
            // plan can inspect it. The generated bridge owns the final
            // directive, so remove the prepared copy before composing the
            // executable vertex shader.
            String converted = VERSION_LINE.matcher(removeShadowAttributes(
                            removeTerrainAttributes(removeModernTerrainInputs(vertex, layout), shadowStage), shadowStage))
                    .replaceAll("");
            Map<String, String> inputReplacements = modernTerrainInputReplacements(vertex);
            if (shadowStage) {
                inputReplacements.putAll(shadowAttributeInputReplacements(vertex));
                inputReplacements.putAll(shadowMatrixBuiltinReplacements());
            }
            converted = GlslTokenRewriter.replaceIdentifiers(converted, inputReplacements);
            converted = converted.replaceAll(
                    "\\bgl_TextureMatrix\\s*\\[\\s*[01]\\s*\\]", "mat4(1.0)");
            converted = converted.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                    "chimeraFtransform()");
            if (shadowStage) {
                converted = normalizeShadowLegacyBuiltins(converted);
            }
            converted = rewriteModernTerrainSamplers(converted, interfacePlan, shadowStage);
            converted = convertTextureCalls(converted);
            if (MODERN_TERRAIN_INPUT_DECL.matcher(stripComments(converted)).find()) {
                throw new IllegalArgumentException("modern terrain input was not consumed");
            }
            String uniformBlock = shadowStage && interfacePlan != null
                    && !interfacePlan.executableUniforms().isEmpty()
                    ? generatedShadowUniformBlock(interfacePlan.executableUniforms())
                    : !shadowStage && interfacePlan != null
                    && !interfacePlan.executableUniforms().isEmpty()
                    ? generatedGeometryUniformBlock(interfacePlan.executableUniforms()) : "";
            if (shadowStage && interfacePlan != null
                    && !interfacePlan.executableUniforms().isEmpty()) {
                converted = qualifyShadowUniformReferences(converted,
                        interfacePlan.executableUniforms());
            }
            String effectivePreamble = shadowStage && !extendedShadow
                    ? SHADOW_VERTEX_PREAMBLE : vertexPreamble;
            String body = shadowStage ? shadowClipRangeWrapper(converted) : converted;
            return new TerrainVertexConversion("#version 460\n"
                    + effectivePreamble + SHADOW_RENDER_STAGE_DEFINES
                    + uniformBlock + body, layout);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Converts the same strict bridge for the shadow pipeline's one-matrix UBO. */
    public static TerrainVertexConversion convertShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                MODERN_SHADOW_VERTEX_PREAMBLE, null, true, false);
    }

    /** Name the pack-authored shadow entry point keeps after the wrapper claims {@code main}. */
    private static final String AUTHORED_SHADOW_ENTRY = "chimeraAuthoredShadowMain";
    /** Converts the final legacy OpenGL clip range after the pack's authored vertex code runs. */
    private static String shadowClipRangeWrapper(String source) {
        return GlslTokenRewriter.replaceIdentifiers(source, Map.of("main", AUTHORED_SHADOW_ENTRY))
                + "\nvoid main() {\n"
                + "    " + AUTHORED_SHADOW_ENTRY + "();\n"
                + "    gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);\n"
                + "}\n";
    }

    /**
     * The shadow adapter's legacy built-ins describe the matrices used by the
     * current shadow draw. Camera gbuffer matrices remain separately exposed
     * through their Iris names.
     */
    private static Map<String, String> shadowMatrixBuiltinReplacements() {
        return Map.of(
                "gl_ModelViewMatrix", "shadowModelView",
                "gl_NormalMatrix", "mat3(transpose(shadowModelViewInverse))",
                "gl_ProjectionMatrix", "shadowProjection",
                "gl_ModelViewProjectionMatrix", "MVP");
    }

    /** Converts legacy and simple compatibility shadow vertices with one plan. */
    public static TerrainVertexConversion convertShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertShadowVertex(source, sourceFile, fragmentSource, interfacePlan, false);
    }

    /** Converts a shadow vertex with an admitted storage-buffer declaration. */
    public static TerrainVertexConversion convertShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean allowStorageBuffers
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                MODERN_SHADOW_VERTEX_PREAMBLE, interfacePlan, true, allowStorageBuffers);
    }

    /** Converts the strict legacy entity vertex bridge onto EXTENDED_ENTITY. */
    public static TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, null);
    }

    /** Converts an entity vertex with the locations selected by the shared stage matcher. */
    public static TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts an entity vertex with storage blocks admitted by the plan. */
    public static TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                ENTITY_VERTEX_PREAMBLE, allowStorageBuffers, List.of());
    }

    /**
     * Entity vertex conversion that reports the first concrete failure.
     * PackPlanBuilder records the reason alongside the generic bridge
     * deviation instead of collapsing every failure into one code.
     */
    public static TerrainVertexConversion convertEntityVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                ENTITY_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms);
    }

    /** Converts the block-entity variant, which uses the host ModelOffset field. */
    public static TerrainVertexConversion convertBlockVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertBlockVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts a block vertex with storage blocks admitted by the plan. */
    public static TerrainVertexConversion convertBlockVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                BLOCK_VERTEX_PREAMBLE, allowStorageBuffers, List.of());
    }

    /** Block vertex conversion that reports the first concrete failure. */
    public static TerrainVertexConversion convertBlockVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                BLOCK_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms);
    }

    /** Hand models use the real entity inputs; held items/maps get layout variants at build time. */
    public static TerrainVertexConversion convertHandVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertHandVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts a hand vertex with storage blocks admitted by the plan. */
    public static TerrainVertexConversion convertHandVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                HAND_VERTEX_PREAMBLE, allowStorageBuffers, List.of());
    }

    /** Hand vertex conversion that reports the first concrete failure. */
    public static TerrainVertexConversion convertHandVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                HAND_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms);
    }

    /** Host damage meshes keep BLOCK, including its lightmap and normal offsets. */
    public static TerrainVertexConversion convertCrumblingVertexChecked(
            String source, Path sourceFile, String fragmentSource,
            Map<String, Integer> sharedLocations, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        String liveInputs = stripComments(removeEntityAttributes(pruneUnreachableVertexFunctions(
                prepareSource(source, sourceFile, true))));
        if (containsIdentifier(liveInputs, "mc_midTexCoord") || containsIdentifier(liveInputs, "at_tangent")) {
            throw new IllegalArgumentException("crumbling BLOCK has no material mid-coordinate or tangent attribute");
        }
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                CRUMBLING_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms);
    }

    private static TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            String vertexPreamble,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        try {
            return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                    vertexPreamble, allowStorageBuffers, fragmentUniforms);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Entity vertex conversion that reports the first concrete failure
     * instead of collapsing to null. PackPlanBuilder records the reason
     * alongside the generic bridge deviation; capability predicates keep
     * using the null-returning overloads.
     */
    private static TerrainVertexConversion convertEntityVertexOrThrow(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            String vertexPreamble,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
            String src = prepareSource(source, sourceFile, true);
            src = expandModernVaryingLists(src);
            src = pruneUnreachableVertexFunctions(src);
            String stripped = stripComments(src);
            if (!ENTITY_VERSION.matcher(src).find()
                    || stripped.matches("(?s).*#version\\s+(?!120(?:e)?|130\\b)\\d+.*")) {
                throw new IllegalArgumentException("entity vertex requires #version 120, 120e, or 130");
            }
            if (!stripped.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")
                    || !stripped.matches("(?s).*\\bgl_Position\\b.*")) {
                throw new IllegalArgumentException("entity vertex requires main and gl_Position");
            }
            Map<String, String> entityIdTypes = new java.util.LinkedHashMap<>();
            for (String name : ENTITY_ID_NAMES) {
                String type = entityIdentifierType(name, stripped, fragmentSource);
                if (type != null) entityIdTypes.put(name, type);
            }
            // Catalog uniforms, legacy matrix built-ins, and stage-level
            // flat/layout qualifiers in real entity sources are translated
            // below; reject only what no adapter path can serve.
            String beforeFeatures = removeEntityIdDeclarations(stripped);
            rejectEntityVertexFeatures(
                    stripUnusableUniformDeclarations(beforeFeatures, fragmentUniforms),
                    allowStorageBuffers);

            Map<String, String> vertexTypes = parseEntityVaryings(stripped, true);
            String expandedFragment = expandModernVaryingLists(
                    fragmentSource == null ? "" : stripComments(fragmentSource));
            Map<String, String> fragmentTypes = parseEntityVaryings(expandedFragment, false);
            Map<String, String> vertexQualifiers =
                    parseEntityVaryingDeclarations(stripped, true).qualifiers();
            Map<String, String> fragmentQualifiers =
                    parseEntityVaryingDeclarations(expandedFragment, false).qualifiers();
            for (Map.Entry<String, String> entry : fragmentTypes.entrySet()) {
                String vertexType = vertexTypes.get(entry.getKey());
                if (!entry.getValue().equals(vertexType)) {
                    throw new IllegalArgumentException("entity varying mismatch: " + entry.getKey());
                }
                String vertexQualifier = vertexQualifiers.getOrDefault(entry.getKey(), "");
                String fragmentQualifier = fragmentQualifiers.getOrDefault(entry.getKey(), "");
                if (!vertexQualifier.equals(fragmentQualifier)) {
                    throw new IllegalArgumentException(
                            "entity varying qualifier mismatch: " + entry.getKey());
                }
            }

            Map<String, Integer> locations = new TreeMap<>();
            if (sharedLocations != null) {
                locations.putAll(sharedLocations);
            }
            int location = locations.values().stream().mapToInt(Integer::intValue)
                    .max().orElse(-1) + 1;
            for (String name : vertexTypes.keySet().stream().sorted().toList()) {
                if (!locations.containsKey(name)) {
                    locations.put(name, location++);
                }
            }
            for (Map.Entry<String, String> id : entityIdTypes.entrySet()) {
                locations.put(entityIdVarying(id.getKey()), location++);
                vertexTypes.put(entityIdVarying(id.getKey()), id.getValue());
            }
            boolean overlayUv = containsIdentifier(stripComments(fragmentSource == null ? "" : fragmentSource),
                    EntityOverlayColor.UV_VARYING);
            if (overlayUv) {
                locations.put(EntityOverlayColor.UV_VARYING, location++);
                vertexTypes.put(EntityOverlayColor.UV_VARYING, "ivec2");
            }
            TerrainVaryingLayout layout = new TerrainVaryingLayout(vertexTypes, locations,
                    parseEntityVaryingDeclarations(stripped, true).qualifiers());

            String converted = VERSION_LINE.matcher(src).replaceAll("");
            converted = removeEntityIdDeclarations(converted);
            converted = removeEntityAttributes(converted);
            converted = replaceEntityVaryings(converted, layout, "out", true);
            // Served and dead uniform declarations leave before identifier
            // replacement: the replacement would otherwise rewrite names
            // inside their own declarations into garbage.
            converted = stripUnusableUniformDeclarations(converted, fragmentUniforms);
            // Unused sampler declarations would fail Vulkan compilation:
            // opaque uniforms require layout(binding) even when unread.
            // Referenced samplers already failed loudly in rejection.
            converted = stripUnreferencedSamplerDeclarations(converted);
            // Legacy MVP is a mat4; substituting vec4 ftransform() silently
            // turns authored MVP * gl_Vertex into component-wise multiplication.
            Map<String, String> inputs = Map.ofEntries(
                    Map.entry("gl_Vertex", "chimeraEntityVertexValue()"),
                    Map.entry("gl_Color", vertexPreamble == PARTICLE_VERTEX_PREAMBLE || isSkyPreamble(vertexPreamble)
                            ? "(Color * ColorModulator)" : "Color"),
                    Map.entry("gl_MultiTexCoord0", "vec4(UV0, 0.0, 1.0)"),
                    Map.entry("gl_MultiTexCoord1", "vec4(vec2(UV2), 0.0, 1.0)"),
                    Map.entry("gl_MultiTexCoord2", "vec4(vec2(UV2), 0.0, 1.0)"),
                    Map.entry("gl_Normal", "Normal.xyz"),
                    Map.entry("gl_NormalMatrix", vertexPreamble == PARTICLE_VERTEX_PREAMBLE || isSkyPreamble(vertexPreamble)
                            ? "transpose(inverse(mat3(ModelViewMat)))" : "mat3(ModelViewMat)"),
                    Map.entry("gl_ModelViewMatrix", "ModelViewMat"),
                    Map.entry("gl_ModelViewProjectionMatrix", "(ProjMat * ModelViewMat)"),
                    Map.entry("gl_ProjectionMatrix", "ProjMat"),
                    Map.entry("gbufferModelView", "ModelViewMat"),
                    Map.entry("gbufferModelViewInverse", "chimeraEntityInverseModelView()"),
                    Map.entry("mc_midTexCoord", midTexCoordValue(stripped)),
                    Map.entry("at_tangent", "chimeraEntityTangentValue()"),
                    Map.entry("Tangent", "chimeraEntityTangentValue()"),
                    Map.entry("isElytraFlying", "(chimeraIsElytraFlying != 0)"),
                    Map.entry("entityId", entityIdExpression(layout, "entityId")),
                    Map.entry("blockEntityId", entityIdExpression(layout, "blockEntityId")));
            // ProjMat and gl_ProjectionMatrix belong to the host raster path.
            // Iris gbufferProjection uniforms stay in the shared pack UBO so
            // they use the legacy clip-depth convention instead.
            if (vertexPreamble == HAND_VERTEX_PREAMBLE) {
                inputs = new java.util.HashMap<>(inputs);
                inputs.put("gl_ProjectionMatrix", "chimeraHandProjection()");
                inputs.put("gl_ModelViewProjectionMatrix", "(chimeraHandProjection() * ModelViewMat)");
                inputs.remove("gbufferModelView");
                inputs.remove("gbufferModelViewInverse");
            }
            if (isSkyPreamble(vertexPreamble)) {
                inputs = new java.util.HashMap<>(inputs);
                inputs.put("gl_ProjectionMatrix", "chimeraSkyProjection()");
                inputs.put("gl_ModelViewProjectionMatrix", "(chimeraSkyProjection() * ModelViewMat)");
                // Celestial host transforms rotate the sun/moon mesh. Pack world
                // matrices must remain the frame's world matrices, as in Iris.
                inputs.remove("gbufferModelView");
                inputs.remove("gbufferModelViewInverse");
            }
            converted = GlslTokenRewriter.replaceIdentifiers(converted, inputs);
            converted = converted.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]",
                    "TextureMat");
            // Iris VanillaTransformer/VanillaCoreTransformer: raw UV2 for both
            // light-coordinate aliases, normalized once by the legacy matrix.
            converted = converted.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*[12]\\s*\\]",
                    "mat4(0.00390625, 0.0, 0.0, 0.0, "
                            + "0.0, 0.00390625, 0.0, 0.0, "
                            + "0.0, 0.0, 0.00390625, 0.0, "
                            + "0.03125, 0.03125, 0.03125, 1.0)");
            converted = converted.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                    "chimeraEntityFtransform()");
            for (String name : entityIdTypes.keySet()) {
                converted = injectMainPrologue(converted,
                        entityIdVarying(name) + " = EntityIds." + entityIdComponent(name) + ";");
            }
            if (overlayUv) {
                converted = injectMainPrologue(converted, EntityOverlayColor.UV_VARYING + " = UV1;");
            }
            Matcher leftoverDeclaration = Pattern.compile("(?m)^\\s*(?:attribute|varying|in|out)\\s+.*$")
                    .matcher(stripComments(converted));
            if (leftoverDeclaration.find()) {
                String line = leftoverDeclaration.group().trim();
                throw new IllegalArgumentException("entity declaration was not consumed: "
                        + line.substring(0, Math.min(80, line.length())));
            }
            String chimeraVaryings = "";
            for (String name : entityIdTypes.keySet()) {
                chimeraVaryings += "layout(location = " + layout.location(entityIdVarying(name))
                        + ") flat out uint " + entityIdVarying(name) + ";\n";
            }
            if (overlayUv) {
                chimeraVaryings += "layout(location = " + layout.location(EntityOverlayColor.UV_VARYING)
                        + ") flat out ivec2 " + EntityOverlayColor.UV_VARYING + ";\n";
            }
            String cameraBlock = entityCameraUniformBlock(converted, fragmentUniforms);
            if (vertexPreamble == HAND_VERTEX_PREAMBLE) {
                converted = converted.replaceFirst("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)", "void chimeraHandMain()");
                converted += "\nvoid main() { chimeraHandMain(); gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w); }\n";
            }
            if (isSkyPreamble(vertexPreamble)) {
                converted = converted.replaceFirst("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)", "void chimeraSkyMain()");
                converted += "\nvoid main() { chimeraSkyMain(); gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w); }\n";
            }
            return new TerrainVertexConversion("#version 460\n" + vertexPreamble
                    + chimeraVaryings + cameraBlock + converted,
                    layout);
    }

    /**
     * Live uniforms for the entity vertex path. The converted body keeps
     * its authored references; this emits the same fragment uniform block
     * so the shared pack buffer supplies live values at identical offsets.
     * Emitted whenever the body references any served member (cameraPosition
     * is the common case, not the only one). Fails when a referenced name
     * has no live uniform behind it instead of falling back to a placeholder.
     */
    private static String entityCameraUniformBlock(
            String converted,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        if (fragmentUniforms == null) {
            fragmentUniforms = List.of();
        }
        boolean referenced = false;
        for (UniformRegistry.UniformDeclaration uniform : fragmentUniforms) {
            if (GlslTokenRewriter.containsIdentifier(converted, uniform.name())) {
                referenced = true;
                break;
            }
        }
        if (!referenced) {
            return "";
        }
        return entityUniformBlock(fragmentUniforms);
    }

    /** Converts the legacy particle vertex contract onto DefaultVertexFormat.PARTICLE. */
    public static TerrainVertexConversion convertParticleVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertParticleVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts a particle vertex with storage blocks admitted by the plan. */
    public static TerrainVertexConversion convertParticleVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers
    ) {
        try {
            return convertParticleVertexChecked(source, sourceFile, fragmentSource,
                    sharedLocations, allowStorageBuffers, List.of());
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    /** HOST_PARTICLE shares legacy semantics, not the terrain converter's input restrictions. */
    public static TerrainVertexConversion convertParticleVertexChecked(
            String source, Path sourceFile, String fragmentSource,
            Map<String, Integer> sharedLocations, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        String liveInputs = stripComments(removeEntityAttributes(pruneUnreachableVertexFunctions(
                prepareSource(source, sourceFile, true))));
        if (containsIdentifier(liveInputs, "mc_midTexCoord") || containsIdentifier(liveInputs, "at_tangent")
                || containsIdentifier(liveInputs, "Tangent")) {
            throw new IllegalArgumentException("particle format has no material mid-coordinate or tangent attribute");
        }
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                PARTICLE_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms);
    }

    /** Converts a particle fragment with the shared host sampler and varying rules. */
    public static String convertParticleFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            TerrainVaryingLayout particleLayout,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertParticleFragment(source, sourceFile, samplerSlots,
                particleLayout, interfacePlan, null);
    }

    /** Particle fragment conversion carrying the authored geometry output plan. */
    public static String convertParticleFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            TerrainVaryingLayout particleLayout,
            UniformRegistry.ProgramInterface interfacePlan,
            GeometryOutputPlan geometryOutputPlan
    ) {
        try {
            String prepared = prepareSource(source, sourceFile, false);
            return FragmentConversionRequest.of(prepared, null, true, samplerSlots)
                    .withTerrainLayout(particleLayout)
                    .withInterfacePlan(interfacePlan)
                    .withGeometryOutputPlan(geometryOutputPlan)
                    .convert();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
    /** Converts an entity fragment with the shared varying and descriptor rules. */
    public static String convertEntityFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            TerrainVaryingLayout entityLayout,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertEntityFragment(source, sourceFile, samplerSlots, entityLayout,
                interfacePlan, null);
    }

    /** Entity fragment conversion carrying the authored geometry output plan. */
    public static String convertEntityFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            TerrainVaryingLayout entityLayout,
            UniformRegistry.ProgramInterface interfacePlan,
            GeometryOutputPlan geometryOutputPlan
    ) {
        try {
            String prepared = prepareSource(source, sourceFile, false);
            validateEntityFragmentVersion(prepared);
            return FragmentConversionRequest.of(prepared, null, true, samplerSlots)
                    .withTerrainLayout(entityLayout)
                    .withInterfacePlan(interfacePlan)
                    .withGeometryOutputPlan(geometryOutputPlan)
                    .convert();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static TerrainVertexConversion convertLegacyVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            String vertexPreamble
    ) {
        return convertLegacyVertex(source, sourceFile, fragmentSource, vertexPreamble, false);
    }

    private static TerrainVertexConversion convertLegacyVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            String vertexPreamble,
            boolean allowStorageBuffers
    ) {
        try {
            String src = defaultUnboundIdentityAttributes(prepareSource(source, sourceFile, true));
            String stripped = stripComments(src);
            if (!TERRAIN_VERSION.matcher(src).find()
                    || stripped.matches("(?s).*#version\\s+(?!120(?:e)?\\b)\\d+.*")) {
                throw new IllegalArgumentException("legacy vertex requires #version 120 or #version 120e");
            }
            if (!stripped.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")
                    || !stripped.matches("(?s).*\\bgl_Position\\b.*")) {
                throw new IllegalArgumentException("legacy vertex requires main and gl_Position");
            }
            rejectTerrainVertexFeatures(stripped, allowStorageBuffers);

            Map<String, String> vertexTypes = parseTerrainVaryings(stripped);
            Map<String, String> fragmentTypes = parseTerrainVaryings(stripComments(fragmentSource));
            for (Map.Entry<String, String> entry : fragmentTypes.entrySet()) {
                String vertexType = vertexTypes.get(entry.getKey());
                if (!entry.getValue().equals(vertexType)) {
                    throw new IllegalArgumentException("legacy varying mismatch: " + entry.getKey());
                }
            }

            Map<String, Integer> locations = new TreeMap<>();
            int location = 0;
            for (String name : vertexTypes.keySet().stream().sorted().toList()) {
                locations.put(name, location++);
            }
            TerrainVaryingLayout layout = new TerrainVaryingLayout(vertexTypes, locations);

            String converted = VERSION_LINE.matcher(src).replaceFirst("");
            converted = converted.replaceAll(
                    "(?m)^\\s*attribute\\s+(?:float|vec2)\\s+mc_Entity\\s*;\\s*", "");
            converted = replaceTerrainVaryings(converted, layout, "out");
            converted = converted.replaceAll("\\bgl_Vertex\\b", "chimeraVertexValue()");
            converted = converted.replaceAll("\\bgl_Color\\b", "chimeraColorValue()");
            converted = converted.replaceAll("\\bgl_MultiTexCoord0\\b", "chimeraTexCoord0Value()");
            converted = converted.replaceAll("\\bgl_MultiTexCoord1\\b", "chimeraTexCoord1Value()");
            converted = converted.replaceAll("\\bftransform\\s*\\(\\s*\\)", "chimeraFtransform()");
            String entityType = terrainEntityType(stripped);
            if (entityType != null) {
                converted = converted.replaceAll("\\bmc_Entity\\b",
                        entityType.equals("vec2") ? "chimeraMcEntityValue()" : "chimeraMcEntityValue().x");
            }
            if (converted.matches("(?s).*\\bmc_Entity\\b.*")) {
                throw new IllegalArgumentException("mc_Entity is used without a supported declaration");
            }
            return new TerrainVertexConversion(
                    "#version 460\n" + vertexPreamble + converted,
                    layout);
        } catch (Exception e) {
            return null;
        }
    }

    /** Static probe helper used by PackProbe without compiling a shader. */
    public static boolean supportsTerrainVertex(String source, String fragmentSource) {
        return convertTerrainVertex(source, null, fragmentSource) != null;
    }

    /** Selects the one host sky format that can provide the authored legacy inputs. */
    public static FamilyAdapterPlan.VertexContract skyVertexContract(
            String source, boolean textured
    ) {
        String stripped = stripComments(source == null ? "" : source);
        boolean color = stripped.matches("(?s).*\\bgl_Color\\b.*");
        if (textured) {
            return color ? FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR_UV
                    : FamilyAdapterPlan.VertexContract.SKY_POSITION_UV;
        }
        return color ? FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR
                : FamilyAdapterPlan.VertexContract.SKY_POSITION;
    }

    /** Converts the narrow host sky vertex contract. */
    public static TerrainVertexConversion convertSkyVertex(
            String source, Path sourceFile, String fragmentSource, boolean textured
    ) {
        return convertSkyVertex(source, sourceFile, fragmentSource,
                skyVertexContract(source, textured));
    }

    /** Converts a sky vertex against one validated host vertex format. */
    public static TerrainVertexConversion convertSkyVertex(
            String source, Path sourceFile, String fragmentSource,
            FamilyAdapterPlan.VertexContract contract
    ) {
        return convertSkyVertex(source, sourceFile, fragmentSource, contract, Map.of(), false, List.of());
    }

    public static TerrainVertexConversion convertSkyVertex(
            String source, Path sourceFile, String fragmentSource,
            FamilyAdapterPlan.VertexContract contract, Map<String, Integer> locations,
            boolean allowStorageBuffers, List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        try {
            String live = stripComments(removeEntityAttributes(pruneUnreachableVertexFunctions(
                    prepareSource(source, sourceFile, true))));
            if (containsIdentifier(live, "mc_midTexCoord") || containsIdentifier(live, "at_tangent")
                    || containsIdentifier(live, "Tangent")) {
                throw new IllegalArgumentException("sky format has no material mid-coordinate or tangent attribute");
            }
            return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, locations,
                    skyPreamble(contract), allowStorageBuffers, uniforms);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** One authored sky program runs against each real native sky mesh layout. */
    public static String skyVertexForContract(String converted, FamilyAdapterPlan.VertexContract contract) {
        for (var source : SKY_CONTRACTS) {
            String preamble = skyPreamble(source);
            if (converted != null && converted.contains(preamble)) {
                return converted.replace(preamble, skyPreamble(contract));
            }
        }
        throw new IllegalArgumentException("sky program lacks its native input contract");
    }

    /** Converts the generated cloud vertex contract. */
    public static TerrainVertexConversion convertCloudVertex(
            String source, Path sourceFile, String fragmentSource
    ) {
        return convertLegacyVertex(source, sourceFile, fragmentSource, CLOUD_VERTEX_PREAMBLE);
    }

    public static String convertSkyFragment(
            String source, Path sourceFile, int[] samplerSlots,
            TerrainVaryingLayout layout, UniformRegistry.ProgramInterface interfacePlan,
            GeometryOutputPlan outputPlan
    ) {
        return FragmentConversionRequest.of(source, sourceFile, true, samplerSlots)
                .withTerrainLayout(layout)
                .withInterfacePlan(interfacePlan)
                .withGeometryOutputPlan(outputPlan)
                .convert();
    }

    public static boolean supportsModernTerrain(String source, String fragmentSource) {
        if (!requiresExtendedTerrain(source) && !requiresExtendedTerrain(fragmentSource)) {
            return false;
        }
        if (!hasModernTerrainVersion(source)) return false;
        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.planProgram(
                fragmentSource, source, UniformRegistry.Stage.GEOMETRY, null, false, Map.of(), Map.of())
                .project("vertex", UniformRegistry.Stage.GEOMETRY);
        return convertModernTerrainVertex(source, null, fragmentSource, interfacePlan) != null;
    }

    /** Returns true for the measured real-pack terrain interface, not every legacy shader. */
    public static boolean requiresExtendedTerrain(String source) {
        String stripped = stripComments(source == null ? "" : source);
        if (stripped.isBlank()) return false;
        if (hasVersionDirective(source)) {
            int version = modernTerrainVersion(source);
            if (version != 120 && version != 130 && version != 330
                    && version != 400 && version != 460) {
                return false;
            }
        }
        return (hasModernTerrainVersion(source) && modernTerrainVersion(source) >= 130)
                || stripped.matches("(?s).*\\b(?:in|out|flat|noperspective|mc_midTexCoord|at_tangent|"
                + "gl_NormalMatrix|gl_ModelViewMatrix|gl_ProjectionMatrix|gl_TextureMatrix)\\b.*");
    }

    public static boolean supportsModernShadow(String source, String fragmentSource) {
        if (!hasShadowTerrainVersion(source) || shadowVersion(source) < 130) {
            return false;
        }
        return convertModernShadowVertex(source, null, fragmentSource) != null;
    }

    /** Adds the M8.2 coverage output to a converted geometry fragment. */
    public static String withCoverageOutput(String source) {
        if (source == null || source.contains("gl_FragDepth")
                || source.matches("(?s).*\\bchimeraCoverage\\b.*")) {
            throw new IllegalArgumentException("coverage requires implicit fragment depth");
        }
        Matcher matcher = Pattern.compile(
                "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+"
                        + "(?:float|vec2|vec3|vec4)\\s+\\w+\\s*;").matcher(source);
        int max = -1;
        while (matcher.find()) max = Math.max(max, Integer.parseInt(matcher.group(1)));
        if (max < 0 || max >= 7) {
            throw new IllegalArgumentException("coverage output has no safe color location");
        }
        String declaration = "layout(location = " + (max + 1) + ") out float chimeraCoverage;\n";
        int versionEnd = source.indexOf('\n');
        int insertAt = versionEnd < 0 ? 0 : versionEnd + 1;
        String result = source.substring(0, insertAt) + declaration + source.substring(insertAt);
        return GlslTokenRewriter.appendMainEpilogue(result,
                "chimeraCoverage = gl_FragCoord.z;");
    }

    /** Static probe helper for the strict shadow vertex bridge. */
    public static boolean supportsShadowVertex(String source, String fragmentSource) {
        return convertShadowVertex(source, null, fragmentSource) != null;
    }

    /** Static probe helper for the strict entity vertex bridge. */
    public static boolean supportsEntityVertex(String source, String fragmentSource) {
        return convertEntityVertex(source, null, fragmentSource) != null;
    }

    /** Static probe helper for the particle-layout hand bridge. */
    public static boolean supportsHandVertex(String source, String fragmentSource) {
        return convertHandVertex(source, null, fragmentSource, null) != null;
    }

    private static UniformRegistry.Stage stageOf(boolean geometryStage) {
        return geometryStage ? UniformRegistry.Stage.GEOMETRY : UniformRegistry.Stage.POST;
    }

    /**
     * gbuffers_terrain and gbuffers_water share one terrain contract: the
     * terrain vertex inputs, the generated pack uniform block at binding 3,
     * the host terrain block at binding 1, and the terrain alpha test. They
     * differ only in the sampled inputs their stage table admits.
     */
    static boolean terrainContract(UniformRegistry.Stage stage) {
        return stage == UniformRegistry.Stage.GEOMETRY || stage == UniformRegistry.Stage.TRANSLUCENT;
    }

    /** Position of the sampler's registry slot in the emitted geometry config array. */
    private static int configIndexOf(
            String name,
            int[] slots,
            UniformRegistry.Stage stage,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        if (stage == UniformRegistry.Stage.SHADOW) {
            Integer slotValue = UniformRegistry.SHADOW_NAME_TO_SLOT.get(name);
            if (slotValue == null && interfacePlan != null) {
                slotValue = interfacePlan.samplers().stream()
                        .filter(value -> value.name().equals(name))
                        .map(UniformRegistry.SamplerBinding::slot)
                        .findFirst()
                        .orElse(null);
            }
            if (slotValue == null) {
                return -1;
            }
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == slotValue) return i;
            }
            return -1;
        }
        Map<String, Integer> mapping = (stage == UniformRegistry.Stage.ENTITY
                || stage == UniformRegistry.Stage.BLOCK
                || stage == UniformRegistry.Stage.HAND
                || stage == UniformRegistry.Stage.PARTICLE)
                ? UniformRegistry.ENTITY_NAME_TO_SLOT
                : stage == UniformRegistry.Stage.TRANSLUCENT
                ? UniformRegistry.TRANSLUCENT_NAME_TO_SLOT : UniformRegistry.GEOMETRY_NAME_TO_SLOT;
        Integer slotValue = mapping.get(name);
        if (slotValue == null && interfacePlan != null) {
            slotValue = interfacePlan.samplers().stream()
                    .filter(value -> value.name().equals(name))
                    .map(UniformRegistry.SamplerBinding::slot)
                    .findFirst()
                    .orElse(null);
        }
        if (slotValue == null) {
            return -1;
        }
        int slot = slotValue;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private static String insertAfterFirstLine(String src, String line) {
        int newline = src.indexOf('\n');
        if (newline < 0) {
            return line + "\n" + src;
        }
        return src.substring(0, newline + 1) + line + src.substring(newline + 1);
    }

    private static String generatedUniformBlock(List<UniformRegistry.UniformDeclaration> uniforms) {
        StringBuilder block = new StringBuilder("layout(binding = 0) uniform ChimeraPackUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            block.append("    ").append(uniform.glslType()).append(' ')
                    .append(uniform.name()).append(";\n");
        }
        return block.append("};\n").toString();
    }

    private static String generatedShadowUniformBlock(
            List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        StringBuilder block = new StringBuilder("layout(binding = 3) uniform ChimeraShadowUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            block.append("    ").append(uniform.glslType()).append(' ')
                    .append(uniform.name()).append(";\n");
        }
        return block.append("} chimeraShadowUniforms;\n").toString();
    }

    /**
     * Vulkan GLSL requires named shadow-block members to be accessed through
     * the block instance. Keep canonical field names in PipelineConfig and
     * qualify only executable source references.
     */
    private static String qualifyShadowUniformReferences(
            String source,
            List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        Map<String, String> replacements = new TreeMap<>();
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            replacements.put(uniform.name(), "chimeraShadowUniforms." + uniform.name());
        }
        return replacements.isEmpty()
                ? source : GlslTokenRewriter.replaceIdentifiers(source, replacements);
    }

    /** Ordinary terrain pack uniforms share the binding reserved after host section data. */
    private static String generatedGeometryUniformBlock(
            List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        StringBuilder block = new StringBuilder("layout(binding = 3) uniform ChimeraTerrainPackUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            block.append("    ").append(uniform.glslType()).append(' ')
                    .append(uniform.name()).append(";\n");
        }
        return block.append("};\n").toString();
    }

    /** Exact binding-1 layout, with an instance reserved for generated alpha testing. */
    private static String terrainHostUniformBlock(String source, String instance) {
        String block = GlslTokenRewriter.uniqueIdentifier(source, "ChimeraTerrainHostBlock");
        return """
                layout(binding = 1) uniform %s {
                    vec4 FogColor;
                    float FogEnvironmentalStart;
                    float FogEnvironmentalEnd;
                    float FogRenderDistanceStart;
                    float FogRenderDistanceEnd;
                    float FogSkyEnd;
                    float FogCloudsEnd;
                    float AlphaCutout;
                    ivec2 TextureSize;
                    vec2 TexelSize;
                    int UseRgss;
                } %s;
                """.formatted(block, instance);
    }

    /** Generated entity fragment UBO. Vertex transform blocks are supplied by the host. */
    private static String entityUniformBlock(List<UniformRegistry.UniformDeclaration> uniforms) {
        StringBuilder block = new StringBuilder("layout(binding = 2) uniform ChimeraEntityUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            block.append("    ").append(uniform.glslType()).append(' ')
                    .append(uniform.name()).append(";\n");
        }
        return block.append("};\n").toString();
    }

    /** Compatibility adapter for callers that still pass raw source and a path. */
    private static String prepareSource(String source, Path sourceFile, boolean vertexStage) {
        String preparedSource = source;
        if (sourceFile != null) {
            Path root = sourceFile.getParent();
            ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepare(
                    root, sourceFile, source,
                    PackEngineDefines.forPack(Map.of()),
                    PackEngineDefines.lockedNames(Set.of(), false, false));
            if (!prepared.successful()) {
                throw new IllegalArgumentException(prepared.deviations().toString());
            }
            preparedSource = prepared.source();
        }
        LegacyShaderNormalizer.Result normalized = LegacyShaderNormalizer.normalize(preparedSource, vertexStage);
        if (!normalized.successful()) {
            throw new IllegalArgumentException(normalized.deviations().toString());
        }
        return normalized.source();
    }

    private static String convertVaryings(
            String src,
            boolean geometryStage,
            TerrainVaryingLayout terrainLayout,
            PostVaryingLayout postVaryingLayout,
            UniformRegistry.Stage stage
    ) {
        src = expandModernVaryingLists(src);
        if (!geometryStage) {
            return postVaryingLayout == null
                    ? convertPostVaryings(src)
                    : convertPostVaryings(src, postVaryingLayout);
        }
        if (stage == UniformRegistry.Stage.ENTITY
                || stage == UniformRegistry.Stage.BLOCK
                || stage == UniformRegistry.Stage.HAND
                || stage == UniformRegistry.Stage.PARTICLE
                || stage == UniformRegistry.Stage.SKY
                || stage == UniformRegistry.Stage.CLOUD) {
            return replaceEntityVaryings(expandModernVaryingLists(src), terrainLayout, "in", false);
        }
        if (src.contains("#version 460")) {
            Matcher modern = MODERN_TERRAIN_INPUT_DECL.matcher(src);
            StringBuilder modernOut = new StringBuilder();
            int modernLast = 0;
            while (modern.find()) {
                String qualifier = modern.group(1);
                String type = modern.group(2);
                String name = modern.group(3);
                if (unproducedAndUnread(src, modern, name, terrainLayout)) {
                    modernOut.append(src, modernLast, modern.start());
                    modernLast = modern.end();
                    continue;
                }
                int location = terrainLayout == null
                        ? GEOMETRY_VARYING_LOCATIONS.getOrDefault(name, -1)
                        : terrainLayout.location(name);
                if (location < 0) {
                    throw new IllegalArgumentException("modern geometry varying has no fixed slot: " + name);
                }
                modernOut.append(src, modernLast, modern.start());
                modernOut.append("layout(location = ").append(location).append(") ");
                if (qualifier != null) {
                    modernOut.append(qualifier).append(' ');
                }
                modernOut.append("in ").append(type).append(' ').append(name).append(';');
                modernLast = modern.end();
            }
            modernOut.append(src, modernLast, src.length());
            return modernOut.toString();
        }
        Matcher matcher = VARYING_DECL.matcher(src);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (unproducedAndUnread(src, matcher, name, terrainLayout)) {
                out.append(src, last, matcher.start());
                last = matcher.end();
                continue;
            }
            int location;
            if (geometryStage && terrainLayout != null) {
                location = terrainLayout.location(name);
            } else if (geometryStage) {
                Integer slot = GEOMETRY_VARYING_LOCATIONS.get(name);
                if (slot == null) {
                    throw new IllegalArgumentException(
                            "geometry varying '" + name + "' has no fixed-vertex slot (expected one of "
                                    + GEOMETRY_VARYING_LOCATIONS.keySet() + ")");
                }
                location = slot;
            } else {
                location = 0;
            }
            out.append(src, last, matcher.start());
            out.append("layout(location = ").append(location).append(") in ").append(type).append(' ').append(name).append(';');
            last = matcher.end();
        }
        out.append(src, last, src.length());
        return out.toString();
    }

    /** The fixed fullscreen vertex produces one vec2 input at location zero. */
    private static String convertPostVaryings(String source) {
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
    private static String convertPostVaryings(String source, PostVaryingLayout layout) {
        return GlslInterfaceScanner.rewrite(source, false, declaration -> {
            if (!declaration.input()) {
                return null;
            }
            if (!declaration.referenced()) {
                return "";
            }
            Integer location = layout.locations().get(declaration.name());
            String type = layout.types().get(declaration.name());
            if (location == null || !declaration.typeWithArray().equals(type)) {
                throw new IllegalArgumentException("post varying is not matched: " + declaration.name());
            }
            return interfaceDeclaration(location, declaration, "in");
        });
    }

    /** {@code layout(location = N) [interpolation] in|out type name[size];} for one name. */
    private static String interfaceDeclaration(int location, GlslInterfaceScanner.Declaration declaration,
                                               String direction) {
        StringBuilder result = new StringBuilder("layout(location = ").append(location).append(") ");
        if (declaration.qualifier() != null) {
            result.append(declaration.qualifier()).append(' ');
        }
        return result.append(direction).append(' ').append(declaration.type()).append(' ')
                .append(declaration.declarator()).append(";\n").toString();
    }

    private static List<PostVaryingDeclaration> postVaryingDeclarations(String source) {
        List<PostVaryingDeclaration> declarations = new ArrayList<>();
        Matcher matcher = POST_VARYING_DECL.matcher(source);
        while (matcher.find()) {
            declarations.add(new PostVaryingDeclaration(matcher.start(), matcher.end(),
                    matcher.group(1), matcher.group(3), parsePostVaryingNames(matcher.group(4))));
        }
        return declarations;
    }

    private static List<String> parsePostVaryingNames(String names) {
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

    private static List<String> referencedPostVaryings(
            String source,
            List<PostVaryingDeclaration> declarations
    ) {
        List<String> referenced = new ArrayList<>();
        for (PostVaryingDeclaration declaration : declarations) {
            for (String name : declaration.names()) {
                String withoutDeclaration = source.substring(0, declaration.start())
                        + source.substring(declaration.end());
                if (containsIdentifier(withoutDeclaration, name)) {
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
    private static boolean unproducedAndUnread(String source, Matcher declaration, String name,
                                               TerrainVaryingLayout layout) {
        if (layout == null || layout.locations().containsKey(name)) {
            return false;
        }
        return !containsIdentifier(source.substring(0, declaration.start())
                + source.substring(declaration.end()), name);
    }

    private static boolean containsIdentifier(String source, String name) {
        return GlslTokenRewriter.containsIdentifier(source, name);
    }

    private static void validatePostVersion(String source) {
        Matcher versions = Pattern.compile("(?im)^\\s*#version\\s+([0-9]+)(e?)(?:\\s+.*)?$")
                .matcher(source);
        while (versions.find()) {
            int version = Integer.parseInt(versions.group(1));
            if (version != 120 && version != 130 && version != 330 && version != 400) {
                throw new IllegalArgumentException("unsupported post GLSL version: " + version);
            }
        }
    }

    private static void validateEntityFragmentVersion(String source) {
        Matcher versions = Pattern.compile(
                "(?im)^\\s*#version\\s+([0-9]+)(e?)(?:\\s+.*)?$")
                .matcher(source == null ? "" : source);
        boolean found = false;
        while (versions.find()) {
            found = true;
            int version = Integer.parseInt(versions.group(1));
            if (version != 120 && version != 130) {
                throw new IllegalArgumentException("unsupported entity GLSL version: " + version);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("entity fragment is missing a supported GLSL version");
        }
    }

    private static String removePackMetadataConstants(String source) {
        Matcher declarations = Pattern.compile(
                "(?m)^\\s*const\\s+(?:int|float|bool|vec4)\\s+"
                        + "((?:colortex|gaux)\\d+(?:Format|Clear|ClearColor|MipmapEnabled))"
                        + "\\s*=\\s*[^;]+;\\s*(?://.*)?$").matcher(source);
        List<PostVarying> matches = new ArrayList<>();
        while (declarations.find()) {
            matches.add(new PostVarying(declarations.start(), declarations.end(),
                    declarations.group(1), declarations.group(1)));
        }
        for (PostVarying declaration : matches) {
            String withoutDeclaration = source.substring(0, declaration.start())
                    + source.substring(declaration.end());
            if (containsIdentifier(withoutDeclaration, declaration.name())) {
                throw new IllegalArgumentException("pack metadata constant is used: " + declaration.name());
            }
        }
        return declarations.reset().replaceAll("");
    }

    private static String injectPackConstants(String source, Map<String, String> packConstants) {
        if (packConstants == null || packConstants.isEmpty()) {
            return source;
        }
        String stripped = stripComments(source);
        StringBuilder declarations = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(packConstants).entrySet()) {
            if (!containsIdentifier(stripped, entry.getKey())
                    || Pattern.compile("(?m)^\\s*#define\\s+" + Pattern.quote(entry.getKey()) + "\\b")
                    .matcher(stripped).find()) {
                continue;
            }
            String type = entry.getKey().equals("shadowMapResolution") ? "int" : "float";
            declarations.append("const ").append(type).append(' ')
                    .append(entry.getKey()).append(" = ").append(entry.getValue()).append(";\n");
        }
        if (declarations.length() == 0) {
            return source;
        }
        return source.matches("(?s)^\\s*#version\\b.*")
                ? insertAfterFirstLine(source, declarations.toString())
                : declarations + source;
    }

    private record PostVarying(int start, int end, String type, String name) {}

    private record PostVaryingDeclaration(
            int start,
            int end,
            String qualifier,
            String type,
            List<String> names
    ) {}

    private static String replaceTerrainVaryings(
            String source,
            TerrainVaryingLayout layout,
            String direction
    ) {
        Matcher matcher = TERRAIN_VARYING_DECL.matcher(source);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (!layout.types().containsKey(name)
                    || !layout.types().get(name).equals(type)
                    || !(type.equals("float") || type.equals("vec2")
                    || type.equals("vec3") || type.equals("vec4"))) {
                throw new IllegalArgumentException("unsupported terrain varying: " + name);
            }
            out.append(source, last, matcher.start());
            out.append("layout(location = ").append(layout.location(name)).append(") ")
                    .append(direction).append(' ').append(type).append(' ').append(name).append(';');
            last = matcher.end();
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    private static String replaceEntityVaryings(
            String source,
            TerrainVaryingLayout layout,
            String direction,
            boolean vertexStage
    ) {
        if (layout == null) {
            throw new IllegalArgumentException("entity varying layout is missing");
        }
        Matcher matcher = ENTITY_VARYING_DECL.matcher(source);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String type = matcher.group(3);
            String name = matcher.group(4);
            // An unread declaration is dropped before the layout is consulted: GL links it as
            // dead whether or not the other stage declares it.
            String withoutDeclaration = source.substring(0, matcher.start())
                    + source.substring(matcher.end());
            if (!containsIdentifier(withoutDeclaration, name)) {
                out.append(source, last, matcher.start());
                last = matcher.end();
                continue;
            }
            if (!layout.types().containsKey(name) || !layout.types().get(name).equals(type)) {
                throw new IllegalArgumentException("entity varying is not in the shared layout: " + name);
            }
            out.append(source, last, matcher.start());
            String qualifier = layout.qualifier(name);
            out.append("layout(location = ").append(layout.location(name)).append(") ")
                    .append(direction).append(' ');
            if (!qualifier.isEmpty()) {
                out.append(qualifier).append(' ');
            }
            out.append(type).append(' ').append(name).append(';');
            last = matcher.end();
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    /**
     * Rewrites the boolean Iris isElytraFlying uniform onto the live int
     * catalog field. Pack sources read it as {@code !isElytraFlying}, which
     * VulkanMod's admitted int UBO type cannot express, so the negation
     * becomes an integer comparison and other uses cast to bool.
     */
    private static String rewriteElytraFlyingBool(
            String source, UniformRegistry.ProgramInterface interfacePlan
    ) {
        if (source == null || !GlslTokenRewriter.containsIdentifier(source, "isElytraFlying")) {
            return source;
        }
        boolean live = interfacePlan != null && interfacePlan.executableUniforms().stream()
                .anyMatch(uniform -> uniform.name().equals("isElytraFlying"));
        if (!live) {
            return source;
        }
        String result = source.replaceAll(
                "(?s)\\b!\\s*isElytraFlying\\b", "chimeraIsElytraFlying == 0");
        return GlslTokenRewriter.replaceIdentifiers(result,
                Map.of("isElytraFlying", "(chimeraIsElytraFlying != 0)"));
    }

    private static String removeEntityIdDeclarations(String source) {
        return removeEntityIdDeclarations(source, null);
    }

    private static String removeEntityIdDeclarations(
            String source,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        String input = source == null ? "" : source;
        Matcher matcher = ENTITY_ID_DECLARATION.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            List<String> remaining = new ArrayList<>();
            for (String declarator : matcher.group(3).split(",")) {
                String value = declarator.trim();
                int equals = value.indexOf('=');
                String name = equals < 0 ? value : value.substring(0, equals).trim();
                boolean executable = interfacePlan != null && interfacePlan.executableUniforms().stream()
                        .anyMatch(uniform -> uniform.name().equals(name));
                if (!ENTITY_ID_NAMES.contains(name) && !executable) {
                    remaining.add(value);
                }
            }
            String replacement = remaining.isEmpty()
                    ? ""
                    : matcher.group(1) + " " + matcher.group(2) + " "
                    + String.join(", ", remaining) + ";";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /**
     * Terrain-format programs carry no iris_Entity, and Iris applies EntityPatcher only
     * to vanilla-format programs, so an entityId/blockEntityId attribute there stays
     * unbound and GL reads 0 (MakeUp 9.5g declares attribute int blockEntityId in its
     * shared block vertex). Serve that value instead of rejecting the program.
     */
    static String defaultUnboundIdentityAttributes(String source) {
        Matcher matcher = Pattern.compile(
                "(?m)^[ \\t]*(?:attribute|in)[ \\t]+(int|float)[ \\t]+(entityId|blockEntityId)[ \\t]*;")
                .matcher(source == null ? "" : source);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String zero = matcher.group(1).equals("int") ? "0" : "0.0";
            matcher.appendReplacement(output, Matcher.quoteReplacement(
                    "const " + matcher.group(1) + " " + matcher.group(2) + " = " + zero + ";"));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    static String removeEntityAttributes(String source) {
        // The trailing class eats spaces and tabs but never newlines: an
        // eager \s* would swallow the next line's indentation and hide the
        // following declaration from the ^ anchor, silently keeping every
        // second consecutive attribute.
        Matcher matcher = Pattern.compile(
                "(?m)^\\s*(?:attribute|in)\\s+(float|vec2|vec4)\\s+"
                        + "(mc_Entity|mc_midTexCoord|at_tangent|Tangent)[ \\t]*;[ \\t]*")
                .matcher(source == null ? "" : source);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            // Iris's entity vertex format has no mc_Entity (only iris_Entity),
            // so GL reads the unbound attribute's default (0, 0, 0, 1).
            String replacement = !matcher.group(2).equals("mc_Entity") ? ""
                    : "\nconst " + matcher.group(1) + " mc_Entity = " + switch (matcher.group(1)) {
                        case "float" -> "0.0";
                        case "vec2" -> "vec2(0.0)";
                        default -> "vec4(0.0, 0.0, 0.0, 1.0)";
                    } + ";";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /**
     * Removes value-type uniform declarations the pipeline can already
     * serve or safely ignore: every declarator is either served through
     * the shared pack uniform block (a fragment-list member) or never
     * referenced outside declarations. Initializers do not protect a
     * declaration. Referenced unknowns stay visible so rejection names
     * the first one instead of failing at shaderc.
     */
    private static String stripUnusableUniformDeclarations(
            String source, List<UniformRegistry.UniformDeclaration> fragmentUniforms) {
        if (source == null || source.isBlank()) return source;
        Set<String> served = new TreeSet<>();
        if (fragmentUniforms != null) {
            for (UniformRegistry.UniformDeclaration uniform : fragmentUniforms) {
                served.add(uniform.name());
            }
        }
        Pattern declarations = Pattern.compile(
                "(?m)^\\s*uniform\\s+(?:highp|mediump|lowp\\s+)?"
                        + "(?:bool|int|float|vec[234]|ivec[234]|mat[234])\\s+([^;]+);\\s*$");
        Matcher matcher = declarations.matcher(source);
        StringBuffer stripped = new StringBuffer();
        while (matcher.find()) {
            boolean removable = true;
            for (String name : declaratorNames(matcher.group(1))) {
                if (name == null || !served.contains(name)) {
                    removable = false;
                    break;
                }
            }
            matcher.appendReplacement(stripped, removable ? "" : Matcher.quoteReplacement(matcher.group(0)));
        }
        matcher.appendTail(stripped);
        String withoutServed = stripped.toString();
        String checkSource = declarations.matcher(withoutServed).replaceAll("");
        Matcher remaining = declarations.matcher(withoutServed);
        StringBuffer output = new StringBuffer();
        while (remaining.find()) {
            boolean referenced = false;
            for (String name : declaratorNames(remaining.group(1))) {
                if (name != null && GlslTokenRewriter.containsIdentifier(checkSource, name)) {
                    referenced = true;
                    break;
                }
            }
            remaining.appendReplacement(output,
                    referenced ? Matcher.quoteReplacement(remaining.group(0)) : "");
        }
        remaining.appendTail(output);
        return output.toString();
    }

    /**
     * Drops functions the vertex main never reaches so dead library code
     * cannot ghost-reference samplers or uniforms into the feature checks.
     * Best-effort: an unanalyzable source keeps its dead code and fails
     * loudly downstream instead of being rewritten on a guess.
     */
    private static String pruneUnreachableVertexFunctions(String src) {
        try {
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(src);
            if (!usage.successful()) {
                return src;
            }
            return GlslTokenRewriter.removeUnreachableFunctions(src, usage);
        } catch (RuntimeException e) {
            return src;
        }
    }

    /** Plain declarator names from a uniform declaration list, or null entries for exotic forms. */
    private static List<String> declaratorNames(String declarators) {
        List<String> names = new ArrayList<>();
        for (String declarator : declarators.split(",")) {
            String name = declarator.trim();
            int equals = name.indexOf('=');
            if (equals >= 0) name = name.substring(0, equals).trim();
            names.add(name.matches("[A-Za-z_]\\w*") ? name : null);
        }
        return names;
    }

    /**
     * Removes sampler declarations nothing reads. Vulkan requires
     * layout(binding) on every opaque uniform, so even a dead sampler
     * declaration fails compilation. Referenced samplers fail loudly in
     * rejection instead; layout-qualified lines are never touched.
     */
    private static String stripUnreferencedSamplerDeclarations(String source) {
        if (source == null || source.isBlank()) return source;
        Matcher matcher = Pattern.compile(
                "(?m)^\\s*uniform\\s+(?:[iu]?sampler\\w*|u?i?image\\w*)\\s+([^;]+);\\s*$")
                .matcher(source);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String checkSource = source.substring(0, matcher.start())
                    + source.substring(matcher.end());
            boolean referenced = false;
            for (String name : declaratorNames(matcher.group(1))) {
                if (name != null && GlslTokenRewriter.containsIdentifier(checkSource, name)) {
                    referenced = true;
                    break;
                }
            }
            matcher.appendReplacement(output, referenced
                    ? Matcher.quoteReplacement(matcher.group(0)) : "");
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String entityIdentifierType(String name, String vertexSource, String fragmentSource) {
        String vertex = vertexSource == null ? "" : vertexSource;
        String fragment = fragmentSource == null ? "" : stripComments(fragmentSource);
        String declared = declaredEntityIdType(vertex, name);
        if (declared == null) declared = declaredEntityIdType(fragment, name);
        if (declared != null) return declared;
        if (containsIdentifier(vertex, name) || containsIdentifier(fragment, name)) {
            return "int";
        }
        return null;
    }

    private static String declaredEntityIdType(String source, String name) {
        Matcher declaration = ENTITY_ID_DECLARATION.matcher(source);
        while (declaration.find()) {
            for (String declarator : declaration.group(3).split(",")) {
                String value = declarator.trim();
                int equals = value.indexOf('=');
                if ((equals < 0 ? value : value.substring(0, equals).trim()).equals(name)) {
                    return declaration.group(2);
                }
            }
        }
        return null;
    }

    /**
     * Mid-texture coordinates arrive as a host vec2. A vec4-authored
     * declaration (BSL advanced materials) keeps matrix-multiplication
     * shape through an explicit constructor; vec2 uses the value directly.
     */
    private static String midTexCoordValue(String strippedSource) {
        boolean vec4 = Pattern.compile("(?m)^\\s*(?:attribute|in)\\s+vec4\\s+mc_midTexCoord\\s*;")
                .matcher(strippedSource).find();
        return vec4 ? "vec4(MidTexCoord, 0.0, 1.0)" : "MidTexCoord";
    }

    private static String entityIdExpression(TerrainVaryingLayout layout, String name) {
        String varying = entityIdVarying(name);
        if (layout != null && layout.locations().containsKey(varying)) {
            return "float".equals(layout.types().get(varying))
                    ? "float(" + varying + ")"
                    : "int(" + varying + ")";
        }
        return "float(EntityIds." + entityIdComponent(name) + ")";
    }

    private static String replaceEntityIdReferences(
            String source,
            TerrainVaryingLayout layout
    ) {
        if (layout == null) return source;
        Map<String, String> replacements = new java.util.HashMap<>();
        for (String name : ENTITY_ID_NAMES) {
            if (layout.locations().containsKey(entityIdVarying(name))) {
                replacements.put(name, entityIdExpression(layout, name));
            }
        }
        return replacements.isEmpty() ? source : GlslTokenRewriter.replaceIdentifiers(source, replacements);
    }

    private static String chimeraFragmentVaryings(TerrainVaryingLayout layout) {
        if (layout == null) return "";
        String declarations = "";
        for (String name : ENTITY_ID_NAMES) {
            String varying = entityIdVarying(name);
            if (layout.locations().containsKey(varying)) {
                declarations += "layout(location = " + layout.location(varying)
                        + ") flat in uint " + varying + ";\n";
            }
        }
        if (layout.locations().containsKey(EntityOverlayColor.UV_VARYING)) {
            declarations += "layout(location = " + layout.location(EntityOverlayColor.UV_VARYING)
                    + ") flat in ivec2 " + EntityOverlayColor.UV_VARYING + ";\n";
        }
        return declarations;
    }

    private static String injectMainPrologue(String source, String statement) {
        Matcher main = Pattern.compile(
                "\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{")
                .matcher(source);
        if (!main.find()) {
            throw new IllegalArgumentException("entity vertex main body is missing");
        }
        return source.substring(0, main.end()) + "\n    " + statement + source.substring(main.end());
    }

    private static Map<String, String> parseTerrainVaryings(String source) {
        Map<String, String> result = new TreeMap<>();
        Matcher matcher = TERRAIN_VARYING_DECL.matcher(source);
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (!(type.equals("float") || type.equals("vec2")
                    || type.equals("vec3") || type.equals("vec4"))) {
                throw new IllegalArgumentException("unsupported terrain varying type: " + type);
            }
            String previous = result.putIfAbsent(name, type);
            if (previous != null && !previous.equals(type)) {
                throw new IllegalArgumentException("terrain varying declared with two types: " + name);
            }
        }
        return result;
    }

    private static Map<String, String> parseEntityVaryings(String source, boolean vertexStage) {
        return parseEntityVaryingDeclarations(source, vertexStage).types();
    }

    /** Varying types plus their authored interpolation qualifiers (flat/noperspective or empty). */
    private record EntityVaryingDeclarations(Map<String, String> types, Map<String, String> qualifiers) {}

    private static EntityVaryingDeclarations parseEntityVaryingDeclarations(
            String source, boolean vertexStage) {
        Map<String, String> result = new TreeMap<>();
        Map<String, String> qualifiers = new TreeMap<>();
        Matcher matcher = ENTITY_VARYING_DECL.matcher(source == null ? "" : source);
        while (matcher.find()) {
            String qualifier = matcher.group(2);
            boolean applies = vertexStage
                    ? qualifier.equals("varying") || qualifier.equals("out")
                    : qualifier.equals("varying") || qualifier.equals("in");
            if (!applies) {
                continue;
            }
            String type = matcher.group(3);
            String name = matcher.group(4);
            String interpolation = matcher.group(1) == null ? "" : matcher.group(1);
            if (!vertexStage && !containsIdentifier(
                    source.substring(0, matcher.start()) + source.substring(matcher.end()), name)) {
                continue;
            }
            String previous = result.putIfAbsent(name, type);
            if (previous != null && !previous.equals(type)) {
                throw new IllegalArgumentException("entity varying declared with two types: " + name);
            }
            String previousQualifier = qualifiers.putIfAbsent(name, interpolation);
            if (previousQualifier != null && !previousQualifier.equals(interpolation)) {
                throw new IllegalArgumentException(
                        "entity varying declared with two interpolation qualifiers: " + name);
            }
        }
        return new EntityVaryingDeclarations(result, qualifiers);
    }

    private static String terrainEntityType(String source) {
        Matcher matcher = TERRAIN_ATTRIBUTE_DECL.matcher(source);
        String entityType = null;
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (!name.equals("mc_Entity")) {
                throw new IllegalArgumentException("unsupported terrain attribute: " + name);
            }
            if (!type.equals("float") && !type.equals("vec2")) {
                throw new IllegalArgumentException("unsupported mc_Entity type: " + type);
            }
            if (entityType != null && !entityType.equals(type)) {
                throw new IllegalArgumentException("mc_Entity declared with two types");
            }
            entityType = type;
        }
        return entityType;
    }

    private static void rejectTerrainVertexFeatures(String source, boolean allowStorageBuffers) {
        if (source.matches("(?s).*\\b(?:uniform|gl_Normal|gl_NormalMatrix|gl_ModelViewMatrix|"
                + "gl_ProjectionMatrix|gl_ModelViewProjectionMatrix|gl_TextureMatrix|"
                + "mc_midTexCoord|at_tangent|tangent|image\\w*)\\b.*")) {
            throw new IllegalArgumentException("unsupported terrain vertex feature");
        }
        if (!allowStorageBuffers && source.matches("(?s).*\\bbuffer\\b.*")) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        if (source.matches("(?s).*\\b(?:layout|in|out|flat|noperspective)\\b.*")) {
            throw new IllegalArgumentException("modern GLSL is not supported in terrain vertex");
        }
        Matcher attributes = TERRAIN_ATTRIBUTE_DECL.matcher(source);
        if (attributes.find()) {
            attributes.reset();
            while (attributes.find()) {
                if (!attributes.group(2).equals("mc_Entity")) {
                    throw new IllegalArgumentException("unsupported terrain attribute");
                }
            }
        }
        if (source.matches("(?s).*\\battribute\\b.*")
                && !source.matches("(?s).*\\battribute\\s+(?:float|vec2)\\s+mc_Entity\\s*;.*")) {
            throw new IllegalArgumentException("unsupported terrain attribute declaration");
        }
    }

    private static void rejectEntityVertexFeatures(
            String source, boolean allowStorageBuffers
    ) {
        // Served and dead uniform declarations are stripped before this
        // check, so a remaining value-type uniform names a referenced
        // custom with no adapter. Matrix and texture built-ins below have
        // token mappings in the inputs table.
        Matcher leftoverUniform = Pattern.compile(
                "(?m)^\\s*uniform\\s+(?:highp|mediump|lowp\\s+)?(?:bool|int|float|vec[234]|ivec[234]|mat[234])\\s+"
                        + "([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)\\s*;").matcher(source);
        while (leftoverUniform.find()) {
            String checkSource = source.substring(0, leftoverUniform.start())
                    + source.substring(leftoverUniform.end());
            for (String declarator : leftoverUniform.group(1).split(",")) {
                String name = declarator.trim();
                if (GlslTokenRewriter.containsIdentifier(checkSource, name)) {
                    throw new IllegalArgumentException(
                            "unsupported entity vertex uniform: " + name);
                }
            }
        }
        Matcher vertexSampler = Pattern.compile(
                "(?m)^\\s*uniform\\s+(?:[iu]?sampler\\w*|u?i?image\\w*)\\s+"
                        + "([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)\\s*;\\s*$").matcher(source);
        StringBuffer keptSamplers = new StringBuffer();
        while (vertexSampler.find()) {
            String checkSource = source.substring(0, vertexSampler.start())
                    + source.substring(vertexSampler.end());
            boolean referenced = false;
            for (String declarator : vertexSampler.group(1).split(",")) {
                if (GlslTokenRewriter.containsIdentifier(checkSource, declarator.trim())) {
                    referenced = true;
                    break;
                }
            }
            if (referenced) {
                String first = vertexSampler.group(1).split(",")[0].trim();
                throw new IllegalArgumentException(
                        "unsupported entity vertex sampler: " + first);
            }
        }
        Matcher builtin = Pattern.compile("\\bgl_[A-Za-z]\\w*\\b").matcher(source);
        while (builtin.find()) {
            if (!ENTITY_SERVED_BUILTINS.contains(builtin.group())) {
                throw new IllegalArgumentException(
                        "unsupported entity vertex builtin: " + builtin.group());
            }
        }
        if (source.matches("(?s).*\\b(?:image\\w*|geometry|tessellation|compute)\\b.*")) {
            throw new IllegalArgumentException("unsupported entity vertex feature");
        }
        if (!allowStorageBuffers && source.matches("(?s).*\\bbuffer\\b.*")) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        Matcher attributes = Pattern.compile(
                "(?m)\\b(attribute|in)\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;").matcher(source);
        while (attributes.find()) {
            String type = attributes.group(2);
            String name = attributes.group(3);
            boolean supported = (ENTITY_ID_NAMES.contains(name)
                    && (type.equals("float") || type.equals("int")))
                    || (name.equals("mc_midTexCoord") && (type.equals("vec2") || type.equals("vec4")))
                    || (name.equals("at_tangent") && type.equals("vec4"))
                    // Iris capital-T tangent names the same host input.
                    || (name.equals("Tangent") && type.equals("vec4"))
                    // Real packs declare standard Iris attributes the active
                    // body may not use; declared-but-unused is acceptable.
                    || name.equals("mc_Entity") && type.equals("vec4");
            if (!supported) {
                throw new IllegalArgumentException("unsupported entity attribute: " + name);
            }
        }
        if (source.matches("(?s).*\\b(attribute|in)\\b.*")
                && !source.matches("(?s).*\\b(attribute|in)\\s+(?:float|vec2|vec4)\\s+"
                + "(?:entityId|blockEntityId|mc_midTexCoord|at_tangent|mc_Entity)\\s*;.*")) {
            throw new IllegalArgumentException("unsupported entity attribute declaration");
        }
        // Simple version 130 is accepted, and flat/noperspective survive
        // on recognized varying declarations via the shared layout.
        // Pack-authored explicit layouts would conflict with the generated
        // locations, and interpolation qualifiers anywhere else have no
        // adapter path.
        if (ENTITY_VARYING_DECL.matcher(source).replaceAll(" ")
                .matches("(?s).*\\b(?:layout|flat|noperspective)\\b.*")) {
            throw new IllegalArgumentException("entity vertex layout is fixed by Chimera");
        }
    }

    private static String stripComments(String source) {
        return source == null ? "" : source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    private static boolean hasModernTerrainVersion(String source) {
        if (source == null) {
            return false;
        }
        Matcher versions = Pattern.compile("(?im)^\\s*#version\\s+(\\d+)(?:\\s+.*)?$")
                .matcher(source);
        boolean found = false;
        while (versions.find()) {
            found = true;
            int version = Integer.parseInt(versions.group(1));
            if (version != 120 && version != 130 && version != 330 && version != 400 && version != 460) {
                return false;
            }
        }
        return found;
    }

    private static int modernTerrainVersion(String source) {
        if (source == null) {
            return 0;
        }
        Matcher versions = Pattern.compile("(?im)^\\s*#version\\s+(\\d+)(?:\\s+.*)?$")
                .matcher(source);
        int highest = 0;
        while (versions.find()) {
            highest = Math.max(highest, Integer.parseInt(versions.group(1)));
        }
        return highest;
    }

    private static boolean hasVersionDirective(String source) {
        return source != null && Pattern.compile("(?im)^\\s*#version\\s+\\d+").matcher(source).find();
    }

    static String normalizeModernTerrain(String source) {
        if (!hasModernTerrainVersion(source)) {
            throw new IllegalArgumentException("modern terrain requires GLSL 120, 130, 330, 400, or prepared 460");
        }
        String result = VERSION_LINE.matcher(source).replaceAll("");
        result = KNOWN_LEGACY_EXTENSIONS.matcher(result).replaceAll("");
        result = PRECISION_DECL.matcher(result).replaceAll("");
        result = MODERN_LAYOUT_DECL.matcher(result).replaceAll("$1$2$3");
        result = GlslTokenRewriter.replaceIdentifiers(result, Map.of(
                "lowp", "", "mediump", "", "highp", ""));
        // Keep the normalized source self-describing for the shared program
        // plan. The converter also accepts this prepared 460 form, so the
        // authored version is removed exactly once and never duplicated.
        return "#version 460\n" + GlslTokenRewriter.relaxNonConstantGlobals(result);
    }

    private static boolean hasShadowTerrainVersion(String source) {
        if (source == null) return false;
        Matcher versions = Pattern.compile("(?im)^\\s*#version\\s+(\\d+)(?:e)?(?:\\s+.*)?$")
                .matcher(source);
        boolean found = false;
        while (versions.find()) {
            found = true;
            int version = Integer.parseInt(versions.group(1));
            if (version != 120 && version != 130 && version != 330
                    && version != 400 && version != 460) {
                return false;
            }
        }
        return found;
    }

    private static int shadowVersion(String source) {
        Matcher version = Pattern.compile("(?im)^\\s*#version\\s+(\\d+)(?:e)?(?:\\s+.*)?$")
                .matcher(source == null ? "" : source);
        return version.find() ? Integer.parseInt(version.group(1)) : 0;
    }

    private static boolean usesExtendedShadowInputs(String source) {
        String stripped = stripComments(source);
        return shadowVersion(source) >= 130
                || stripped.matches("(?s).*\\b(?:flat|noperspective|mc_midTexCoord|at_midBlock)\\b.*");
    }

    private static String stripShadowUniformDeclarations(String source) {
        return Pattern.compile("(?m)\\buniform\\s+[A-Za-z_]\\w*\\s+[^;]+;\\s*")
                .matcher(source).replaceAll("");
    }

    private static String normalizeShadowTerrain(String source) {
        if (!hasShadowTerrainVersion(source)) {
            throw new IllegalArgumentException("shadow requires GLSL 120, 120e, 130, 330, 400, or 460");
        }
        String result = VERSION_LINE.matcher(source).replaceAll("");
        result = KNOWN_LEGACY_EXTENSIONS.matcher(result).replaceAll("");
        result = PRECISION_DECL.matcher(result).replaceAll("");
        result = MODERN_LAYOUT_DECL.matcher(result).replaceAll("$1$2$3");
        result = GlslTokenRewriter.replaceIdentifiers(result,
                Map.of("lowp", "", "mediump", "", "highp", ""));
        return "#version 460\n" + GlslTokenRewriter.relaxNonConstantGlobals(result);
    }

    private static Map<String, String> modernTerrainVaryings(
            String source, boolean vertexStage, boolean allowInteger) {
        Map<String, String> result = new TreeMap<>();
        Matcher matcher = MODERN_TERRAIN_DECL.matcher(source == null ? "" : source);
        while (matcher.find()) {
            String qualifier = matcher.group(2);
            boolean applies = vertexStage
                    ? qualifier.equals("out") || qualifier.equals("varying")
                    : qualifier.equals("in") || qualifier.equals("varying");
            if (!applies) {
                continue;
            }
            String type = matcher.group(3);
            String name = matcher.group(4);
            if (!(type.equals("float") || type.equals("vec2")
                    || type.equals("vec3") || type.equals("vec4")
                    || (allowInteger && type.equals("int")))) {
                throw new IllegalArgumentException("unsupported modern terrain varying: " + name);
            }
            String withoutDeclaration = source.substring(0, matcher.start())
                    + source.substring(matcher.end());
            if (!vertexStage && !containsIdentifier(withoutDeclaration, name)) {
                continue;
            }
            String previous = result.putIfAbsent(name, type);
            if (previous != null && !previous.equals(type)) {
                throw new IllegalArgumentException("modern terrain varying has two types: " + name);
            }
        }
        return result;
    }

    private static String removeModernTerrainInputs(
            String source,
            TerrainVaryingLayout layout
    ) {
        Matcher matcher = MODERN_TERRAIN_DECL.matcher(source);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String qualifier = matcher.group(2);
            String type = matcher.group(3);
            String name = matcher.group(4);
            result.append(source, last, matcher.start());
            if (qualifier.equals("in")) {
                if (!modernTerrainInputExpression(type, name).isBlank()) {
                    // The declaration is synthesized by the fixed preamble.
                } else {
                    throw new IllegalArgumentException("modern terrain input is unsupported: " + name);
                }
            } else if (qualifier.equals("out") || qualifier.equals("varying")) {
                if (!layout.types().containsKey(name)) {
                    throw new IllegalArgumentException("modern terrain output is not in the shared layout: " + name);
                }
                result.append("layout(location = ").append(layout.location(name)).append(") ");
                if (type.equals("int") || type.startsWith("ivec")
                        || type.equals("uint") || type.startsWith("uvec")) {
                    result.append("flat ");
                }
                result.append("out ").append(type).append(' ').append(name).append(';');
            }
            last = matcher.end();
        }
        result.append(source, last, source.length());
        return result.toString();
    }

    private static String removeShadowAttributes(String source, boolean shadowStage) {
        if (!shadowStage) return source;
        Matcher matcher = SHADOW_ATTRIBUTE_DECL.matcher(source);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            boolean supported = switch (name) {
                case "mc_Entity" -> type.equals("float") || type.equals("vec2") || type.equals("vec4");
                case "mc_midTexCoord" -> type.equals("vec2") || type.equals("vec4");
                case "at_midBlock" -> type.equals("vec3") || type.equals("ivec3");
                default -> false;
            };
            if (!supported) {
                throw new IllegalArgumentException("unsupported shadow attribute: " + name);
            }
            result.append(source, last, matcher.start());
            last = matcher.end();
        }
        result.append(source, last, source.length());
        if (result.toString().matches("(?s).*\\battribute\\b.*")) {
            throw new IllegalArgumentException("unsupported shadow attribute declaration");
        }
        return result.toString();
    }

    /** Removes the small legacy attribute declarations backed by the extended terrain format. */
    private static String removeTerrainAttributes(String source, boolean shadowStage) {
        if (shadowStage) {
            return source;
        }
        Matcher matcher = TERRAIN_ATTRIBUTE_DECL.matcher(source == null ? "" : source);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (!name.equals("mc_Entity") && !name.equals("mc_midTexCoord")
                    && !name.equals("at_tangent")) {
                throw new IllegalArgumentException("unsupported terrain attribute: " + name);
            }
            if (name.equals("mc_Entity")
                    && !(type.equals("float") || type.equals("vec2") || type.equals("vec4"))) {
                throw new IllegalArgumentException("unsupported mc_Entity type: " + type);
            }
            if (name.equals("mc_midTexCoord")
                    && !(type.equals("vec2") || type.equals("vec4"))) {
                throw new IllegalArgumentException("unsupported mc_midTexCoord type: " + type);
            }
            if (name.equals("at_tangent") && !type.equals("vec4")) {
                throw new IllegalArgumentException("unsupported at_tangent type: " + type);
            }
            result.append(source, last, matcher.start());
            last = matcher.end();
        }
        result.append(source, last, source.length());
        if (result.toString().matches("(?s).*\\battribute\\b.*")) {
            throw new IllegalArgumentException("unsupported terrain attribute declaration");
        }
        return result.toString();
    }

    private static Map<String, String> modernTerrainInputReplacements(String source) {
        Map<String, String> replacements = new TreeMap<>();
        Matcher matcher = MODERN_TERRAIN_DECL.matcher(source);
        while (matcher.find()) {
            if (!matcher.group(2).equals("in")) {
                continue;
            }
            String type = matcher.group(3);
            String name = matcher.group(4);
            String expression = modernTerrainInputExpression(type, name);
            if (!expression.isBlank()) {
                replacements.put(name, expression);
            }
        }
        replacements.putIfAbsent("gl_Vertex", "chimeraVertexValue()");
        replacements.putIfAbsent("gl_Color", "chimeraColorValue()");
        replacements.putIfAbsent("gl_MultiTexCoord0", "chimeraTexCoord0Value()");
        replacements.putIfAbsent("gl_MultiTexCoord1", "chimeraTexCoord1Value()");
        replacements.putIfAbsent("gl_Normal", "chimeraNormalValue()");
        replacements.putIfAbsent("mc_Entity", "chimeraMcEntityValue()");
        replacements.putIfAbsent("mc_midTexCoord", "chimeraMidTexCoordValue()");
        replacements.putIfAbsent("at_midBlock", "chimeraMidBlockValue()");
        replacements.putIfAbsent("at_tangent", "chimeraTangentValue()");
        replacements.putIfAbsent("gl_ModelViewMatrix", "gbufferModelView");
        replacements.putIfAbsent("gl_NormalMatrix", "mat3(gbufferModelView)");
        replacements.putIfAbsent("gl_ProjectionMatrix", "(MVP * inverse(gbufferModelView))");
        replacements.putIfAbsent("gl_ModelViewProjectionMatrix", "MVP");
        Matcher legacyAttributes = TERRAIN_ATTRIBUTE_DECL.matcher(source == null ? "" : source);
        while (legacyAttributes.find()) {
            String type = legacyAttributes.group(1);
            String name = legacyAttributes.group(2);
            if (name.equals("mc_midTexCoord")) {
                replacements.put(name, type.equals("vec4")
                        ? "vec4(chimeraMidTexCoordValue(), 0.0, 1.0)"
                        : "chimeraMidTexCoordValue()");
            }
        }
        return replacements;
    }

    /**
     * Binds samplers declared by a real-pack vertex stage to the same descriptor
     * layout as its fragment stage. The old fixed bridge only had fragment
     * samplers, so leaving these declarations untouched produced glslang errors
     * even when the shared interface plan had already accepted the resource.
     */
    private static String rewriteModernTerrainSamplers(
            String source,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean shadowStage
    ) {
        if (interfacePlan == null || interfacePlan.samplers().isEmpty()) {
            return source;
        }
        String result = source;
        if (hasSamplerDeclaration(result, "texture")) {
            result = GlslTokenRewriter.renameSamplerIdentifier(result, "texture", "chimeraTexture");
        }
        int[] slots = shadowStage
                ? PackPipelines.shadowSamplerSlots(interfacePlan.samplerLayout().stream()
                .mapToInt(UniformRegistry.SamplerBinding::slot).toArray())
                : PackPipelines.interleaveLightmap(interfacePlan.samplerLayout().stream()
                .mapToInt(UniformRegistry.SamplerBinding::slot).toArray());
        int bindingBase = shadowStage
                ? GEOMETRY_SAMPLER_BINDING_BASE
                + (!interfacePlan.executableUniforms().isEmpty() ? 1 : 0)
                : GEOMETRY_SAMPLER_BINDING_BASE
                + (!interfacePlan.executableUniforms().isEmpty() ? 1 : 0);
        for (UniformRegistry.SamplerBinding sampler : interfacePlan.samplers()) {
            String sourceName = sampler.name().equals("texture")
                    ? "chimeraTexture" : sampler.name();
            if (!hasSamplerDeclaration(result, sourceName)) {
                continue;
            }
            int index = configIndexOf(sampler.name(), slots, interfacePlan.stage(), interfacePlan);
            if (index < 0) {
                throw new IllegalArgumentException("geometry sampler is missing from the generated config: "
                        + sampler.name());
            }
            result = rewriteSamplerDeclaration(result, sourceName, bindingBase + index);
        }
        return result;
    }

    private static Map<String, String> shadowAttributeInputReplacements(String source) {
        Map<String, String> replacements = new TreeMap<>();
        Matcher matcher = SHADOW_ATTRIBUTE_DECL.matcher(source == null ? "" : source);
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            String replacement = switch (name) {
                case "mc_Entity" -> switch (type) {
                    case "float" -> "chimeraMcEntityValue().x";
                    case "vec2" -> "chimeraMcEntityValue()";
                    case "vec4" -> "vec4(chimeraMcEntityValue(), 0.0, 1.0)";
                    default -> "";
                };
                case "mc_midTexCoord" -> switch (type) {
                    case "vec2" -> "chimeraMidTexCoordValue()";
                    case "vec4" -> "vec4(chimeraMidTexCoordValue(), 0.0, 1.0)";
                    default -> "";
                };
                case "at_midBlock" -> switch (type) {
                    case "vec3" -> "chimeraMidBlockValue()";
                    case "ivec3" -> "chimeraMidBlockRawValue().xyz";
                    default -> "";
                };
                default -> "";
            };
            if (!replacement.isBlank()) {
                replacements.put(name, replacement);
            }
        }
        return replacements;
    }

    private static String modernTerrainInputExpression(String type, String name) {
        String value;
        String baseType;
        switch (name) {
            case "Position", "vaPosition", "a_Position" -> {
                value = "chimeraVertexValue()";
                baseType = "vec4";
            }
            case "Color", "vaColor", "a_Color" -> {
                value = "chimeraColorValue()";
                baseType = "vec4";
            }
            case "UV0", "vaUV0", "a_TexCoord" -> {
                value = "chimeraTexCoord0Value()";
                baseType = "vec4";
            }
            case "UV2", "vaUV2", "a_Light" -> {
                if (type.equals("ivec2")) {
                    return "ivec2(chimeraTexCoord1Value().xy * 256.0)";
                }
                value = "chimeraTexCoord1Value()";
                baseType = "vec4";
            }
            case "Normal", "vaNormal", "a_Normal" -> {
                value = "chimeraNormalValue()";
                baseType = "vec3";
            }
            case "mc_Entity" -> {
                value = "chimeraMcEntityValue()";
                baseType = "vec2";
            }
            case "mc_midTexCoord" -> {
                if (type.equals("uvec2")) {
                    return "chimeraMidTexCoordRawValue()";
                }
                value = "chimeraMidTexCoordValue()";
                baseType = "vec2";
            }
            case "at_midBlock" -> {
                if (type.equals("ivec2") || type.equals("ivec3") || type.equals("ivec4")) {
                    return switch (type) {
                        case "ivec2" -> "chimeraMidBlockRawValue().xy";
                        case "ivec3" -> "chimeraMidBlockRawValue().xyz";
                        default -> "chimeraMidBlockRawValue()";
                    };
                }
                value = "chimeraMidBlockValue()";
                baseType = "vec3";
            }
            case "at_tangent" -> {
                value = "chimeraTangentValue()";
                baseType = "vec4";
            }
            default -> {
                return "";
            }
        }
        return adaptModernTerrainType(type, value, baseType);
    }

    private static String adaptModernTerrainType(String type, String value, String baseType) {
        if (type.equals(baseType)) {
            return value;
        }
        if (type.equals("float")) {
            return value + ".x";
        }
        if (type.equals("int")) {
            return "int(" + value + ".x)";
        }
        if (type.equals("uint")) {
            return "uint(" + value + ".x)";
        }
        if (type.equals("vec2")) {
            return baseType.equals("vec2") ? value : value + ".xy";
        }
        if (type.equals("vec3")) {
            return switch (baseType) {
                case "vec2" -> "vec3(" + value + ", 0.0)";
                case "vec3" -> value;
                default -> value + ".xyz";
            };
        }
        if (type.equals("vec4")) {
            return switch (baseType) {
                case "vec2" -> "vec4(" + value + ", 0.0, 1.0)";
                case "vec3" -> "vec4(" + value + ", 0.0)";
                default -> value;
            };
        }
        if (type.equals("ivec2")) {
            return "ivec2(" + value + ")";
        }
        if (type.equals("ivec3")) {
            return "ivec3(" + value + ")";
        }
        if (type.equals("ivec4")) {
            return "ivec4(" + value + ")";
        }
        if (type.equals("uvec2")) {
            return "uvec2(" + value + ")";
        }
        if (type.equals("uvec3")) {
            return "uvec3(" + value + ")";
        }
        if (type.equals("uvec4")) {
            return "uvec4(" + value + ")";
        }
        return "";
    }

    private static String convertModernGeometryOutputs(String source) {
        Matcher matcher = MODERN_GEOMETRY_OUTPUT_DECL.matcher(source);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        if (names.isEmpty()) {
            return source;
        }
        if (names.size() != 1) {
            throw new IllegalArgumentException("modern terrain MRT output is unsupported");
        }
        String result = matcher.replaceAll("");
        String name = names.get(0);
        if (!name.equals("fragColor")) {
            result = GlslTokenRewriter.replaceIdentifiers(result, Map.of(name, "fragColor"));
        }
        // Keep the version directive at the start of the source. The final
        // declaration pass inserts its other generated declarations after it.
        return insertAfterFirstLine(result, "layout(location = 0) out vec4 fragColor;\n");
    }

    private static final String TERRAIN_VERTEX_PREAMBLE = """
            layout(binding = 0) uniform ViewUBO {
                mat4 MVP;
                mat4 LightMVP;
            };

            layout(binding = 2) uniform SectionData {
                ivec4 SectionOffsets[128];
                vec4 SectionFadeFactors[128];
            };

            layout(push_constant) uniform Push {
                vec3 ModelOffset;
            };

            layout(location = 0) in ivec4 inPositionLight;
            layout(location = 1) in uvec2 inUV;
            layout(location = 2) in uint inPackedColor;
            layout(location = 3) in int inMaterialId;
            layout(location = 4) in int inRenderType;

            const float CHIMERA_UV_SCALE = 1.0 / 32768.0;
            const vec3 CHIMERA_POSITION_SCALE = vec3(1.0 / 2048.0);
            const vec3 CHIMERA_POSITION_BIAS = vec3(4.0);

            vec3 chimeraSectionOffset(int encoded) {
                return vec3(
                    float(bitfieldExtract(encoded, 0, 8)),
                    float(bitfieldExtract(encoded, 16, 8)),
                    float(bitfieldExtract(encoded, 8, 8)));
            }

            vec3 chimeraWorldPosition() {
                int encoded = SectionOffsets[gl_InstanceIndex >> 2][gl_InstanceIndex & 3];
                return fma(vec3(inPositionLight.xyz), CHIMERA_POSITION_SCALE,
                        ModelOffset + chimeraSectionOffset(encoded));
            }

            vec4 chimeraVertexValue() {
                return vec4(chimeraWorldPosition(), 1.0);
            }

            vec4 chimeraColorValue() {
                return unpackUnorm4x8(inPackedColor);
            }

            vec4 chimeraTexCoord0Value() {
                return vec4(vec2(inUV) * CHIMERA_UV_SCALE, 0.0, 1.0);
            }

            vec4 chimeraTexCoord1Value() {
                return vec4(
                        (vec2(float((uint(inPositionLight.w) >> 4u) & 0xFu),
                              float((uint(inPositionLight.w) >> 12u) & 0xFu)) + 0.5) / 16.0,
                        0.0, 1.0);
            }

            vec2 chimeraMcEntityValue() {
                return vec2(float(inMaterialId), float(inRenderType));
            }

            vec4 chimeraFtransform() {
                return MVP * chimeraVertexValue();
            }

            """;

    /** The legacy prefix plus the M8.1 append-only material attributes. */
    private static final String MODERN_TERRAIN_VERTEX_PREAMBLE = TERRAIN_VERTEX_PREAMBLE + """
            layout(location = 5) in uvec2 inMidTexCoord;
            layout(location = 6) in int inMidBlock;
            layout(location = 7) in vec4 inTangent;
            layout(location = 8) in vec4 inSeparateAo;

            vec2 chimeraMidTexCoordValue() {
                return vec2(inMidTexCoord) / 32768.0;
            }

            uvec2 chimeraMidTexCoordRawValue() {
                return inMidTexCoord;
            }

            ivec4 chimeraMidBlockRawValue() {
                return ivec4(
                        bitfieldExtract(inMidBlock, 0, 8),
                        bitfieldExtract(inMidBlock, 8, 8),
                        bitfieldExtract(inMidBlock, 16, 8),
                        bitfieldExtract(inMidBlock, 24, 8));
            }

            vec3 chimeraMidBlockValue() {
                return vec3(chimeraMidBlockRawValue().xyz) / 64.0;
            }

            // Iris at_tangent from the quad's UVs, stored as a turn around the
            // normal plus handedness in the normal word's spare byte
            // (TerrainTangentCodec); 0 means the plain reference tangent.
            vec4 chimeraTangentValue() {
                vec3 normal = normalize(inTangent.xyz);
                vec3 axis = abs(normal.y) < 0.999
                        ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                vec3 reference = normalize(cross(axis, normal));
                int code = int(round(inTangent.w * 127.0));
                if (code == 0) return vec4(reference, 1.0);
                float angle = 6.28318530718 * float(abs(code) - 1) / 64.0;
                vec3 tangent = cos(angle) * reference + sin(angle) * cross(normal, reference);
                return vec4(tangent, code < 0 ? -1.0 : 1.0);
            }

            vec3 chimeraNormalValue() {
                return normalize(inTangent.xyz);
            }

            vec4 chimeraSeparateAoValue() {
                return inSeparateAo;
            }

            """;

    /** Modern material inputs with the shadow pass's single MVP matrix. */
    private static final String MODERN_SHADOW_VERTEX_PREAMBLE =
            MODERN_TERRAIN_VERTEX_PREAMBLE.replace("mat4 LightMVP;\n", "");

    /** The shadow config exposes only the single host MVP matrix at binding 0. */
    private static final String SHADOW_VERTEX_PREAMBLE = TERRAIN_VERTEX_PREAMBLE
            .replace("mat4 LightMVP;\n", "");

    /**
     * Iris injects these scheduler constants before a shadow shader runs.
     * Production sources receive these from PackEngineDefines before
     * preprocessing. Direct converter fixtures still use the canonical ABI.
     */
    private static final String SHADOW_RENDER_STAGE_DEFINES = """
            #ifndef MC_RENDER_STAGE_TERRAIN_SOLID
            #define MC_RENDER_STAGE_TERRAIN_SOLID 8
            #endif
            #ifndef MC_RENDER_STAGE_TERRAIN_TRANSLUCENT
            #define MC_RENDER_STAGE_TERRAIN_TRANSLUCENT 17
            #endif
            #ifndef MC_RENDER_STAGE_TERRAIN_CUTOUT
            #define MC_RENDER_STAGE_TERRAIN_CUTOUT 10
            #endif
            #ifndef MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED
            #define MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED 9
            #endif
            """;

    /** Host entity transform blocks and the append-only EXTENDED_ENTITY inputs. */
    private static final String ENTITY_VERTEX_PREAMBLE = """
            layout(binding = 0) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
            };

            layout(binding = 1) uniform Projection {
                mat4 ProjMat;
            };

            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            layout(location = 2) in vec2 UV0;
            layout(location = 3) in ivec2 UV1;
            layout(location = 4) in ivec2 UV2;
            layout(location = 5) in vec3 Normal;
            layout(location = 6) in uvec4 EntityIds;
            layout(location = 7) in vec2 MidTexCoord;
            layout(location = 8) in vec4 ChimeraPackedTangent;

            vec4 chimeraEntityVertexValue() {
                return vec4(Position, 1.0);
            }

            vec4 chimeraEntityFtransform() {
                return ProjMat * ModelViewMat * chimeraEntityVertexValue();
            }

            mat4 chimeraEntityInverseModelView() {
                return inverse(ModelViewMat);
            }

            vec3 chimeraEntityNormalValue() {
                float lengthSquared = dot(Normal, Normal);
                return lengthSquared > 1.0e-8
                        ? Normal * inversesqrt(lengthSquared) : vec3(0.0, 1.0, 0.0);
            }

            vec4 chimeraEntityTangentValue() {
                vec3 normal = chimeraEntityNormalValue();
                vec3 candidate = ChimeraPackedTangent.xyz
                        - normal * dot(ChimeraPackedTangent.xyz, normal);
                float lengthSquared = dot(candidate, candidate);
                if (!(lengthSquared > 1.0e-8)) {
                    vec3 absoluteNormal = abs(normal);
                    vec3 axis = absoluteNormal.x <= absoluteNormal.y
                            && absoluteNormal.x <= absoluteNormal.z
                            ? vec3(1.0, 0.0, 0.0)
                            : (absoluteNormal.y <= absoluteNormal.z
                            ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0));
                    candidate = cross(axis, normal);
                    lengthSquared = dot(candidate, candidate);
                }
                float handedness = ChimeraPackedTangent.w < 0.0 ? -1.0 : 1.0;
                return vec4(candidate * inversesqrt(lengthSquared), handedness);
            }

            """;

    /** Block entities use the host block shader's camera-relative offset. */
    private static final String BLOCK_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("return vec4(Position, 1.0);", "return vec4(Position + ModelOffset, 1.0);");

    private static final String CRUMBLING_VERTEX_PREAMBLE = BLOCK_VERTEX_PREAMBLE
            .replace("layout(location = 3) in ivec2 UV1;\n", "const ivec2 UV1 = ivec2(0);\n")
            .replace("layout(location = 4) in ivec2 UV2;", "layout(location = 3) in ivec2 UV2;")
            .replace("layout(location = 5) in vec3 Normal;", "layout(location = 4) in vec3 Normal;")
            .replace("layout(location = 6) in uvec4 EntityIds;", "const uvec4 EntityIds = uvec4(0);")
            .replace("layout(location = 7) in vec2 MidTexCoord;", "#define MidTexCoord UV0")
            .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                    "const vec4 ChimeraPackedTangent = vec4(0.0, 0.0, 0.0, 1.0);");

    private static final String HAND_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("vec4 chimeraEntityVertexValue()", """
                    mat4 chimeraHandProjection() {
                        mat4 legacy = ProjMat;
                        legacy[0].z = 2.0 * ProjMat[0].z - ProjMat[0].w;
                        legacy[1].z = 2.0 * ProjMat[1].z - ProjMat[1].w;
                        legacy[2].z = 2.0 * ProjMat[2].z - ProjMat[2].w;
                        legacy[3].z = 2.0 * ProjMat[3].z - ProjMat[3].w;
                        return legacy;
                    }
                    vec4 chimeraEntityVertexValue()""")
            .replace("return ProjMat * ModelViewMat", "return chimeraHandProjection() * ModelViewMat");

    /** Same immutable hand program, adapted only to the host inputs of this draw. */
    public static String handVertexForFormat(String converted, com.mojang.blaze3d.vertex.VertexFormat host) {
        if (host == com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY) return converted;
        if (converted == null || !converted.contains(HAND_VERTEX_PREAMBLE)) {
            throw new IllegalArgumentException("hand program lacks the entity input contract");
        }
        if (!net.chimera.render.vertex.ChimeraVertexFormats.handFormats().containsKey(host)) {
            throw new IllegalArgumentException("unsupported hand host format");
        }
        // Input locations follow the native prefix. Iris VanillaTransformer
        // supplies +Z for formats without a normal, zero for a missing lightmap.
        String preamble = HAND_VERTEX_PREAMBLE;
        var names = host.getElementAttributeNames();
        String[] types = {"vec3", "vec4", "vec2", "ivec2", "ivec2", "vec3"};
        String[] attributes = {"Position", "Color", "UV0", "UV1", "UV2", "Normal"};
        String[] defaults = {null, "vec4(1.0)", null, "ivec2(0)", "ivec2(0)", "vec3(0.0, 0.0, 1.0)"};
        for (int i = 0; i < attributes.length; i++) {
            int location = names.indexOf(attributes[i]);
            String declaration = location < 0
                    ? "const " + types[i] + " " + attributes[i] + " = " + defaults[i] + ";"
                    : "layout(location = " + location + ") in " + types[i] + " " + attributes[i] + ";";
            preamble = preamble.replace("layout(location = " + i + ") in " + types[i] + " " + attributes[i] + ";", declaration);
        }
        preamble = preamble.replace("layout(location = 6) in uvec4 EntityIds;",
                        "layout(location = " + names.size() + ") in uvec4 EntityIds;")
                .replace("layout(location = 7) in vec2 MidTexCoord;",
                        "layout(location = " + (names.size() + 1) + ") in vec2 MidTexCoord;")
                .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                        "layout(location = " + (names.size() + 2) + ") in vec4 ChimeraPackedTangent;");
        return converted.replace(HAND_VERTEX_PREAMBLE, preamble);
    }

    /** Iris VanillaTransformer supplies +Z for a host format without a normal. */
    private static final String PARTICLE_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("layout(location = 1) in vec4 Color;", "layout(location = 2) in vec4 Color;")
            .replace("layout(location = 2) in vec2 UV0;", "layout(location = 1) in vec2 UV0;")
            .replace("layout(location = 3) in ivec2 UV1;", "const ivec2 UV1 = ivec2(0);")
            .replace("layout(location = 4) in ivec2 UV2;", "layout(location = 3) in ivec2 UV2;")
            .replace("layout(location = 5) in vec3 Normal;", "const vec3 Normal = vec3(0.0, 0.0, 1.0);")
            .replace("layout(location = 6) in uvec4 EntityIds;", "const uvec4 EntityIds = uvec4(0);")
            .replace("layout(location = 7) in vec2 MidTexCoord;", "#define MidTexCoord UV0")
            .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                    "const vec4 ChimeraPackedTangent = vec4(0.0, 0.0, 0.0, 1.0);");

    public static final List<FamilyAdapterPlan.VertexContract> SKY_CONTRACTS = List.of(
            FamilyAdapterPlan.VertexContract.SKY_POSITION,
            FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR,
            FamilyAdapterPlan.VertexContract.SKY_POSITION_UV,
            FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR_UV);

    private static boolean isSkyPreamble(String preamble) {
        return preamble.contains("mat4 chimeraSkyProjection()");
    }

    private static String skyPreamble(FamilyAdapterPlan.VertexContract contract) {
        var host = switch (contract) {
            case SKY_POSITION -> com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION;
            case SKY_POSITION_COLOR -> com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR;
            case SKY_POSITION_UV -> com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX;
            case SKY_POSITION_COLOR_UV -> com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR;
            default -> throw new IllegalArgumentException("unsupported sky vertex contract");
        };
        // Reuse the native transform and legacy clip-depth machinery. Sky has
        // no material attributes; Iris defaults missing colour/UV/light/normal.
        String preamble = HAND_VERTEX_PREAMBLE.replace("chimeraHandProjection", "chimeraSkyProjection");
        String[] types = {"vec4", "vec2", "ivec2", "ivec2", "vec3", "uvec4", "vec2", "vec4"};
        String[] names = {"Color", "UV0", "UV1", "UV2", "Normal", "EntityIds", "MidTexCoord", "ChimeraPackedTangent"};
        String[] defaults = {"vec4(1.0)", "vec2(0.0)", "ivec2(0)", "ivec2(0)",
                "vec3(0.0, 0.0, 1.0)", "uvec4(0)", "vec2(0.0)", "vec4(0.0, 0.0, 0.0, 1.0)"};
        for (int i = 0; i < names.length; i++) {
            int location = host.getElementAttributeNames().indexOf(names[i]);
            String declaration = location < 0 ? "const " + types[i] + " " + names[i] + " = " + defaults[i] + ";"
                    : "layout(location = " + location + ") in " + types[i] + " " + names[i] + ";";
            preamble = preamble.replace("layout(location = " + (i + 1) + ") in " + types[i] + " " + names[i] + ";", declaration);
        }
        return preamble;
    }

    /** Host VulkanMod clouds are ordinary position/color geometry. */
    private static final String CLOUD_VERTEX_PREAMBLE = """
            layout(binding = 0) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
            };
            layout(binding = 1) uniform Projection { mat4 ProjMat; };
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            vec4 chimeraVertexValue() { return vec4(Position, 1.0); }
            vec4 chimeraColorValue() { return Color; }
            vec4 chimeraTexCoord0Value() { return vec4(0.0); }
            vec4 chimeraTexCoord1Value() { return vec4(0.0); }
            vec4 chimeraFtransform() { return ProjMat * ModelViewMat * chimeraVertexValue(); }
            """;

    private static String convertTextureCalls(String src) {
        return GlslTokenRewriter.rewriteTextureCalls(GlslTokenRewriter.renameReservedSamplerParameters(src));
    }

    private static String removeLegacyShadowSamplerHelper(String source) {
        if (source == null || !source.contains("texture2DShadow")) return source;
        return source.replaceAll(
                "(?s)\\bfloat\\s+texture2DShadow\\s*\\(\\s*sampler2DShadow\\s+"
                        + "([A-Za-z_]\\w*)\\s*,\\s*vec3\\s+([A-Za-z_]\\w*)\\s*\\)\\s*"
                        + "\\{\\s*return\\s+shadow2D\\s*\\(\\s*\\1\\s*,\\s*\\2\\s*\\)"
                        + "\\s*\\.x\\s*;\\s*\\}", "");
    }

    private static String rewriteSamplerDeclaration(String source, String name, int binding) {
        Pattern declaration = Pattern.compile(
                "(?:layout\\s*\\([^;{}]*\\)\\s*)?uniform\\s+"
                        + "((?:[iu]?sampler3D|sampler2D(?:Shadow)?))\\s+"
                        + Pattern.quote(name) + "\\s*;");
        Matcher matcher = declaration.matcher(source);
        if (!matcher.find()) {
            throw new IllegalArgumentException("sampler declaration is missing: " + name);
        }
        String replacement = "layout(binding = " + binding + ") uniform "
                + matcher.group(1) + " " + name + ";";
        return source.substring(0, matcher.start()) + replacement + source.substring(matcher.end());
    }

    /** Expands comma-separated stage declarations before assigning locations. */
    private static String expandModernVaryingLists(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        Matcher matcher = MODERN_VARYING_LIST_DECL.matcher(source);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            result.append(source, last, matcher.start());
            String indent = matcher.group(1);
            String qualifier = matcher.group(2) == null ? "" : matcher.group(2) + " ";
            String type = matcher.group(4);
            for (String name : matcher.group(5).split(",")) {
                result.append(indent).append(qualifier).append(matcher.group(3)).append(' ')
                        .append(type).append(' ').append(name.trim()).append(';').append('\n');
            }
            last = matcher.end();
        }
        result.append(source, last, source.length());
        return result.toString();
    }

    /** Maps legacy matrix built-ins to the fixed terrain inputs used by the shadow adapter. */
    private static String normalizeShadowLegacyBuiltins(String source) {
        String result = source.replaceAll(
                "\\bgl_TextureMatrix\\s*\\[\\s*\\d+\\s*\\]", "mat4(1.0)");
        return GlslTokenRewriter.replaceIdentifiers(result,
                Map.of(
                        // Vulkan GLSL exposes the vertex invocation index as
                        // gl_VertexIndex.  Iris legacy shadow code commonly
                        // uses gl_VertexID for quad/voxel producer gating.
                        "gl_VertexID", "gl_VertexIndex"));
    }


    private static boolean hasSamplerDeclaration(String source, String name) {
        return Pattern.compile("(?:layout\\s*\\([^;{}]*\\)\\s*)?uniform\\s+"
                + "(?:[iu]?sampler3D|sampler2D(?:Shadow)?)\\s+"
                + Pattern.quote(name) + "\\s*;")
                .matcher(source).find();
    }
}
