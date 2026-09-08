package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;
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
    private static final Pattern TERRAIN_ATTRIBUTE_DECL =
            Pattern.compile("(?m)\\battribute\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;");
    private static final Pattern SHADOW_ATTRIBUTE_DECL = Pattern.compile(
            "(?m)^\\s*attribute\\s+([A-Za-z_]\\w*)\\s+(mc_Entity|mc_midTexCoord|at_midBlock)\\s*;\\s*");
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
                    + "([^;{}]*\\bentityId\\b[^;{}]*);\\s*");
    private static final String ENTITY_ID_VARYING = "chimeraEntityId";
    private static final Pattern POST_DRAWBUFFERS_DEFINE = Pattern.compile(
            "(?m)^\\s*#define\\s+DRAWBUFFERS[0-9]+\\s*$");
    /** Pack metadata declarations consumed by PackConfig, not executable GLSL. */
    private static final Pattern CONSUMED_CONSTS =
            Pattern.compile("(?m)^\\s*const\\s+(?:int|float|bool|vec4)\\s+(?:colortex\\d+Format|gaux\\d+Format|colortex\\d+(?:Clear|ClearColor|MipmapEnabled)|shadowMapResolution|shadowDistance|shadowMapDistance|shadowMapSize|shadowMapFov|shadowDistanceRenderMul|sunPathRotation|sunPathOffset)\\s*=\\s*[A-Za-z0-9+_.(), -]+\\s*;\\s*(?://.*)?$");
    private static final Pattern KNOWN_LEGACY_EXTENSIONS = Pattern.compile(
            "(?im)^\\s*#extension\\s+GL_ARB_shader_texture_lod\\s*:\\s*(?:enable|require|disable)\\s*$\\r?\\n?");
    private static final Pattern MODERN_LAYOUT_DECL = Pattern.compile(
            "(?m)^([ \\t]*)layout\\s*\\([^;{}\\r\\n]*\\)\\s*((?:(?:flat|noperspective|smooth|centroid|sample)\\s+)?)"
                    + "(in|out|varying)\\b");
    private static final Pattern PRECISION_DECL = Pattern.compile(
            "(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+(?:float|int)\\s*;\\s*$\\r?\\n?");
    private static final Pattern GLOBAL_NONCONST = Pattern.compile(
            "(?m)\\bconst\\s+((?:float|int|bool|vec[234]|mat[234]))\\s+([A-Za-z_]\\w*)\\s*=");

    /** Geometry fragments receive the fixed chimera terrain vertex's outputs by name. */
    private static final Map<String, Integer> GEOMETRY_VARYING_LOCATIONS = Map.of(
            "color", 0,
            "texcoord", 1,
            "lightSpacePos", 5
    );
    /** Three UBO blocks precede samplers in the terrain pipeline config. */
    private static final int GEOMETRY_SAMPLER_BINDING_BASE = 3;

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
        result = MODERN_LAYOUT_DECL.matcher(result).replaceAll("$1$2$3");
        result = GlslTokenRewriter.replaceIdentifiers(result, Map.of(
                "lowp", "", "mediump", "", "highp", ""));
        return GLOBAL_NONCONST.matcher(result).replaceAll("$1 $2 =");
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
    public record TerrainVaryingLayout(Map<String, String> types, Map<String, Integer> locations) {
        public TerrainVaryingLayout {
            types = Collections.unmodifiableMap(new TreeMap<>(types));
            locations = Collections.unmodifiableMap(new TreeMap<>(locations));
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

    /** Converted fullscreen vertex adapter plus the explicit values it synthesizes. */
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

    public static String convertFragment(String source, Path sourceFile, boolean geometryStage, int[] geometrySamplerSlots) {
        return convertFragment(source, sourceFile, geometryStage, geometrySamplerSlots, null,
                UniformRegistry.plan(source, stageOf(geometryStage)));
    }

    public static String convertFragment(
            String source,
            Path sourceFile,
            boolean geometryStage,
            int[] geometrySamplerSlots,
            TerrainVaryingLayout terrainLayout
    ) {
        return convertFragment(source, sourceFile, geometryStage, geometrySamplerSlots, terrainLayout,
                UniformRegistry.plan(source, stageOf(geometryStage)));
    }

    /** Converts a fragment with the interface plan already used by the caller. */
    public static String convertFragment(
            String source,
            Path sourceFile,
            boolean geometryStage,
            int[] geometrySamplerSlots,
            TerrainVaryingLayout terrainLayout,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertFragment(source, sourceFile, geometryStage, geometrySamplerSlots,
                terrainLayout, interfacePlan, null, Map.of(), null);
    }

    /** Converts a geometry fragment with the pack constants already parsed at load time. */
    public static String convertFragment(
            String source,
            Path sourceFile,
            boolean geometryStage,
            int[] geometrySamplerSlots,
            TerrainVaryingLayout terrainLayout,
            UniformRegistry.ProgramInterface interfacePlan,
            Map<String, String> packConstants
    ) {
        return convertFragment(source, sourceFile, geometryStage, geometrySamplerSlots,
                terrainLayout, interfacePlan, null,
                packConstants == null ? Map.of() : packConstants, null);
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
        return convertFragment(source, sourceFile, false, null, null, interfacePlan, targetPlan,
                packConstants == null ? Map.of() : packConstants, null);
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
        return convertFragment(source, sourceFile, false, null, null, interfacePlan, targetPlan,
                packConstants == null ? Map.of() : packConstants, layout);
    }

    /**
     * Builds the small fixed fullscreen adapter used for a paired post vertex
     * source. The adapter preserves the fullscreen position and UV contract,
     * and synthesizes only the common legacy lighting varyings that have no
     * equivalent input in CustomVertexFormat.NONE.
     */
    public static PostVertexConversion convertPostVertex(
            String source,
            GlslInterfaceScanner.StageInterface vertexInterface,
            GlslInterfaceScanner.StageInterface fragmentInterface,
            GlslInterfaceScanner.ProgramMatch match
    ) {
        try {
            if (source == null || vertexInterface == null || fragmentInterface == null
                    || match == null || !match.executable()) {
                throw new IllegalArgumentException("post vertex interface is not matched");
            }
            validatePostVersion(source);
            if (!source.contains("void") || !source.contains("main")) {
                throw new IllegalArgumentException("post vertex main is missing");
            }

            StringBuilder declarations = new StringBuilder();
            StringBuilder assignments = new StringBuilder();
            List<String> deviations = new ArrayList<>();
            for (GlslInterfaceScanner.Declaration input : fragmentInterface.inputs()) {
                if (!input.referenced()) {
                    continue;
                }
                GlslInterfaceScanner.Declaration output = vertexInterface.output(input.name());
                Integer location = match.locations().get(input.name());
                if (output == null || location == null || !output.type().equals(input.type())) {
                    throw new IllegalArgumentException("post vertex varying is not matched: " + input.name());
                }
                String expression = postVertexExpression(input.name(), input.type());
                if (expression == null) {
                    throw new IllegalArgumentException("post vertex varying is not synthesized: " + input.name());
                }
                declarations.append("layout(location = ").append(location).append(") ");
                if (output.qualifier() != null) {
                    declarations.append(output.qualifier()).append(' ');
                }
                declarations.append("out ").append(input.type()).append(' ')
                        .append(input.name()).append(";\n");
                assignments.append("    ").append(input.name()).append(" = ")
                        .append(expression).append(";\n");
                deviations.add("POST_VARYING_ADAPTER:" + input.name());
                if (input.type().equals("vec3")) {
                    deviations.add("POST_VARYING_SYNTHESIZED_ZERO:" + input.name());
                }
            }
            String generated = "#version 460\n"
                    + declarations
                    + "void main() {\n"
                    + "    vec2 chimeraUv = vec2(float((gl_VertexIndex << 1) & 2), "
                    + "-(float(gl_VertexIndex & 2)) + 1.0);\n"
                    + "    gl_Position = vec4(chimeraUv * vec2(2.0, -2.0) + vec2(-1.0, 1.0), 0.0, 1.0);\n"
                    + assignments
                    + "}\n";
            return new PostVertexConversion(generated, deviations);
        } catch (Exception e) {
            return null;
        }
    }

    private static String postVertexExpression(String name, String type) {
        if (type.equals("vec2") && (name.equals("texCoord")
                || name.equals("texcoord") || name.equals("uv"))) {
            return "chimeraUv";
        }
        if (type.equals("vec3") && (name.equals("upVec") || name.equals("sunVec"))) {
            return "vec3(0.0, 1.0, 0.0)";
        }
        return null;
    }

    private static String convertFragment(
            String source,
            Path sourceFile,
            boolean geometryStage,
            int[] geometrySamplerSlots,
            TerrainVaryingLayout terrainLayout,
            UniformRegistry.ProgramInterface interfacePlan,
            PostTargetPlan targetPlan,
            Map<String, String> packConstants,
            PostVaryingLayout postVaryingLayout
    ) {
        try {
            boolean geometryInterface = interfacePlan != null
                    && (interfacePlan.stage() == UniformRegistry.Stage.GEOMETRY
                    || interfacePlan.stage() == UniformRegistry.Stage.SHADOW
                    || interfacePlan.stage() == UniformRegistry.Stage.TRANSLUCENT
                    || interfacePlan.stage() == UniformRegistry.Stage.ENTITY
                    || interfacePlan.stage() == UniformRegistry.Stage.BLOCK
                    || interfacePlan.stage() == UniformRegistry.Stage.HAND
                    || interfacePlan.stage() == UniformRegistry.Stage.PARTICLE);
            if (interfacePlan == null || !interfacePlan.executable()
                    || geometryInterface != geometryStage) {
                throw new IllegalArgumentException("pack interface is outside the executable contract");
            }
            if (targetPlan != null && (geometryStage || interfacePlan.stage() != UniformRegistry.Stage.POST
                    || !targetPlan.executable())) {
                throw new IllegalArgumentException("post target plan is outside the executable contract");
            }
            String src = source;
            if (src == null) {
                throw new IllegalArgumentException("pack fragment source is missing");
            }
            src = prepareSource(src, sourceFile);

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
            src = CONSUMED_CONSTS.matcher(src).replaceAll("");
            src = KNOWN_LEGACY_EXTENSIONS.matcher(src).replaceAll("");
            src = UniformRegistry.removeUniformDeclarations(src, interfacePlan);
            if (interfacePlan.stage() == UniformRegistry.Stage.TRANSLUCENT) {
                src = replaceTranslucentUniformNames(src, interfacePlan);
            } else if (interfacePlan.stage() == UniformRegistry.Stage.ENTITY
                    || interfacePlan.stage() == UniformRegistry.Stage.BLOCK
                    || interfacePlan.stage() == UniformRegistry.Stage.HAND) {
                src = removeEntityIdDeclarations(src, interfacePlan);
                src = replaceEntityIdReferences(src, terrainLayout);
            }
            if (geometryStage && modern) {
                src = convertModernGeometryOutputs(src);
            }
            src = convertVaryings(src, geometryStage, terrainLayout, postVaryingLayout,
                    interfacePlan.stage());
            src = injectPackConstants(src, packConstants);

            // Samplers in ascending slot order -> bindings base,base+1,... in config order.
            // The interface plan is also the source of the generated config,
            // so conversion cannot drift from descriptor order.
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
                    : GEOMETRY_SAMPLER_BINDING_BASE)
                    : (interfacePlan.executableUniforms().isEmpty() ? 0 : 1);
            if (geometryStage) {
                // GLSL 460: 'texture' is the sampling function name, so a pack
                // sampler declared as 'uniform sampler2D texture;' (the OptiFine
                // atlas convention) is renamed uniformly. Runs BEFORE
                // convertTextureCalls so the function-name rewrite below
                // creates texture(chimeraTexture, ...) instead of renaming
                // the function into a variable call.
                src = GlslTokenRewriter.replaceIdentifiers(src, Map.of("texture", "chimeraTexture"));
            }
            src = convertTextureCalls(src);
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
                        ? bindingBase + configIndexOf(name, geometrySamplerSlots, interfacePlan.stage())
                        : bindingBase + i;
                if (binding < bindingBase) {
                    throw new IllegalArgumentException("sampler is missing from the generated config: " + name);
                }
                src = rewriteSamplerDeclaration(src, srcName, binding);
            }

            String outDecl = null;
            if (targetPlan != null) {
                src = convertPostOutputs(src, targetPlan);
                outDecl = postOutputDeclarations(targetPlan);
            } else if (src.contains("gl_FragColor") || src.contains("gl_FragData")) {
                src = GlslTokenRewriter.rewriteSingleOutput(src);
                // Targets beyond 0 are consumed by nothing in M4's single-attachment passes.
                src = src.replaceAll("gl_FragData\\s*\\[\\s*[1-9][0-9]*\\s*\\]\\s*=\\s*[^;]+;\\s*", "");
                outDecl = "layout(location = 0) out vec4 fragColor;";
            }

            // GLSL requires global declarations to precede their first use;
            // an output declared at the end of the file is a forward reference
            // and glslang rejects it ("'fragColor' : undeclared identifier").
            // Emit the declaration directly after the version line.
            String uniformBlock;
            if (interfacePlan.stage() == UniformRegistry.Stage.SHADOW
                    && !interfacePlan.executableUniforms().isEmpty()) {
                uniformBlock = generatedShadowUniformBlock(interfacePlan.executableUniforms());
            } else if (interfacePlan.stage() == UniformRegistry.Stage.TRANSLUCENT
                    && !interfacePlan.executableUniforms().isEmpty()) {
                uniformBlock = terrainUniformBlock();
            } else if ((interfacePlan.stage() == UniformRegistry.Stage.ENTITY
                    || interfacePlan.stage() == UniformRegistry.Stage.BLOCK
                    || interfacePlan.stage() == UniformRegistry.Stage.HAND
                    || interfacePlan.stage() == UniformRegistry.Stage.PARTICLE)
                    && !interfacePlan.executableUniforms().isEmpty()) {
                uniformBlock = entityUniformBlock(interfacePlan.executableUniforms());
            } else if (!geometryStage && !interfacePlan.executableUniforms().isEmpty()) {
                uniformBlock = generatedUniformBlock(interfacePlan.executableUniforms());
            } else {
                uniformBlock = "";
            }
            String entityIdDeclaration = (interfacePlan.stage() == UniformRegistry.Stage.ENTITY
                    || interfacePlan.stage() == UniformRegistry.Stage.BLOCK
                    || interfacePlan.stage() == UniformRegistry.Stage.HAND)
                    ? entityIdFragmentDeclaration(terrainLayout) : "";
            String declarations = entityIdDeclaration
                    + (outDecl != null ? outDecl + "\n" : "") + uniformBlock;
            if (!geometryStage || !modern) {
                src = "#version 460\n" + declarations + src;
            } else if (!declarations.isEmpty()) {
                src = insertAfterFirstLine(src, declarations);
            }
            return src;
        } catch (Exception e) {
            return null;
        }
    }

    private static String convertPostOutputs(String source, PostTargetPlan targetPlan) {
        String result = POST_DRAWBUFFERS_DEFINE.matcher(source).replaceAll("");
        result = rewriteModernOutputs(result, targetPlan);
        return GlslTokenRewriter.rewritePostOutputs(result, targetPlan);
    }

    private static String rewriteModernOutputs(String source, PostTargetPlan targetPlan) {
        if (targetPlan == null) {
            return source;
        }
        Matcher matcher = Pattern.compile("(?m)^\\s*out\\s+vec4\\s+([A-Za-z_]\\w*)\\s*;\\s*$")
                .matcher(source);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        if (names.isEmpty()) {
            return source;
        }
        List<Integer> locations = targetPlan.outputLocations();
        if (names.size() > locations.size()) {
            throw new IllegalArgumentException("modern fragment outputs exceed target route");
        }
        Map<String, String> replacements = new TreeMap<>();
        for (int index = 0; index < names.size(); index++) {
            replacements.put(names.get(index), "chimeraFragColor" + locations.get(index));
        }
        String result = matcher.replaceAll("");
        return GlslTokenRewriter.replaceIdentifiers(result, replacements);
    }

    private static String postOutputDeclarations(PostTargetPlan targetPlan) {
        StringBuilder declarations = new StringBuilder();
        for (int location : targetPlan.outputLocations()) {
            declarations.append("layout(location = ").append(location)
                    .append(") out vec4 chimeraFragColor").append(location).append(";\n");
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
        return convertLegacyVertex(source, sourceFile, fragmentSource, TERRAIN_VERTEX_PREAMBLE);
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
                MODERN_TERRAIN_VERTEX_PREAMBLE, null, false);
    }

    /** Converts the measured modern shadow vertex subset onto the shadow inputs. */
    public static TerrainVertexConversion convertModernShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                MODERN_SHADOW_VERTEX_PREAMBLE, null, true);
    }

    /** Converts a shadow vertex with the shared cross-stage uniform plan. */
    public static TerrainVertexConversion convertModernShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                MODERN_SHADOW_VERTEX_PREAMBLE, interfacePlan, true);
    }

    private static TerrainVertexConversion convertModernTerrainVertexInternal(
            String source,
            Path sourceFile,
            String fragmentSource,
            String vertexPreamble,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean shadowStage
    ) {
        try {
            boolean extendedShadow = shadowStage && usesExtendedShadowInputs(source);
            String vertex = shadowStage
                    ? normalizeShadowTerrain(prepareSource(source, sourceFile))
                    : normalizeModernTerrain(prepareSource(source, sourceFile));
            String fragment = fragmentSource == null ? "" : fragmentSource;
            if (shadowStage && hasShadowTerrainVersion(fragment)) {
                fragment = normalizeShadowTerrain(fragment);
            } else if (hasModernTerrainVersion(fragment)) {
                fragment = normalizeModernTerrain(fragment);
            }
            vertex = expandModernVaryingLists(vertex);
            fragment = expandModernVaryingLists(fragment);
            vertex = interfacePlan == null
                    ? stripShadowUniformDeclarations(vertex)
                    : UniformRegistry.removeUniformDeclarations(vertex, interfacePlan);
            String stripped = stripComments(vertex);
            if (!stripped.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")
                    || !stripped.matches("(?s).*\\bgl_Position\\b.*")) {
                throw new IllegalArgumentException("modern terrain vertex main or position is missing");
            }
            if (stripped.matches("(?s).*\\b(?:buffer|image\\w*|geometry|tessellation|compute)\\b.*")
                    || stripped.matches("(?s).*\\buniform\\s+(?!(?:sampler|isampler|usampler))\\w+.*")) {
                throw new IllegalArgumentException("modern terrain resource or stage is unsupported");
            }

            Map<String, String> vertexTypes = modernTerrainVaryings(vertex, true, shadowStage);
            Map<String, String> fragmentTypes = modernTerrainVaryings(fragment, false, shadowStage);
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
                            removeModernTerrainInputs(vertex, layout), shadowStage))
                    .replaceAll("");
            Map<String, String> inputReplacements = modernTerrainInputReplacements(vertex);
            if (shadowStage) {
                inputReplacements.putAll(shadowAttributeInputReplacements(vertex));
            }
            converted = GlslTokenRewriter.replaceIdentifiers(converted, inputReplacements);
            converted = converted.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                    "chimeraFtransform()");
            if (shadowStage) {
                converted = normalizeShadowLegacyBuiltins(converted);
                converted = rewriteShadowVertexSamplers(converted, interfacePlan);
                converted = convertTextureCalls(converted);
            }
            if (MODERN_TERRAIN_INPUT_DECL.matcher(stripComments(converted)).find()) {
                throw new IllegalArgumentException("modern terrain input was not consumed");
            }
            String uniformBlock = shadowStage && interfacePlan != null
                    && !interfacePlan.executableUniforms().isEmpty()
                    ? generatedShadowUniformBlock(interfacePlan.executableUniforms()) : "";
            String effectivePreamble = shadowStage && !extendedShadow
                    ? SHADOW_VERTEX_PREAMBLE : vertexPreamble;
            return new TerrainVertexConversion("#version 460\n"
                    + effectivePreamble + uniformBlock + converted, layout);
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
                MODERN_SHADOW_VERTEX_PREAMBLE, null, true);
    }

    /** Converts legacy and simple compatibility shadow vertices with one plan. */
    public static TerrainVertexConversion convertShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                MODERN_SHADOW_VERTEX_PREAMBLE, interfacePlan, true);
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
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                ENTITY_VERTEX_PREAMBLE, false);
    }

    /** Converts the block-entity variant, which uses the host ModelOffset field. */
    public static TerrainVertexConversion convertBlockVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                BLOCK_VERTEX_PREAMBLE, false);
    }

    /** Converts the first-person hand contract onto EXTENDED_PARTICLE. */
    public static TerrainVertexConversion convertHandVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                HAND_VERTEX_PREAMBLE, true);
    }

    private static TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            String vertexPreamble,
            boolean particleInputs
    ) {
        try {
            String src = prepareSource(source, sourceFile);
            String stripped = stripComments(src);
            if (!ENTITY_VERSION.matcher(src).find()
                    || stripped.matches("(?s).*#version\\s+(?!120(?:e)?|130\\b)\\d+.*")) {
                throw new IllegalArgumentException("entity vertex requires #version 120, 120e, or 130");
            }
            if (!stripped.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")
                    || !stripped.matches("(?s).*\\bgl_Position\\b.*")) {
                throw new IllegalArgumentException("entity vertex requires main and gl_Position");
            }
            String entityIdType = entityIdentifierType(stripped, fragmentSource);
            rejectEntityVertexFeatures(removeEntityIdDeclarations(stripped), particleInputs);

            Map<String, String> vertexTypes = parseEntityVaryings(stripped, true);
            Map<String, String> fragmentTypes = parseEntityVaryings(stripComments(fragmentSource), false);
            for (Map.Entry<String, String> entry : fragmentTypes.entrySet()) {
                String vertexType = vertexTypes.get(entry.getKey());
                if (!entry.getValue().equals(vertexType)) {
                    throw new IllegalArgumentException("entity varying mismatch: " + entry.getKey());
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
            if (entityIdType != null) {
                locations.put(ENTITY_ID_VARYING, location++);
                vertexTypes.put(ENTITY_ID_VARYING, entityIdType);
            }
            TerrainVaryingLayout layout = new TerrainVaryingLayout(vertexTypes, locations);

            String converted = VERSION_LINE.matcher(src).replaceAll("");
            converted = removeEntityIdDeclarations(converted);
            converted = removeEntityAttributes(converted);
            converted = replaceEntityVaryings(converted, layout, "out", true);
            Map<String, String> inputs = particleInputs
                    ? Map.ofEntries(
                    Map.entry("gl_Vertex", "chimeraEntityVertexValue()"),
                    Map.entry("gl_Color", "Color"),
                    Map.entry("gl_MultiTexCoord0", "vec4(UV0, 0.0, 1.0)"),
                    Map.entry("gl_MultiTexCoord1", "vec4(vec2(UV2) / 256.0, 0.0, 1.0)"),
                    Map.entry("mc_midTexCoord", "MidTexCoord"),
                    Map.entry("at_tangent", "Tangent"),
                    Map.entry("entityId", entityIdExpression(layout)))
                    : Map.ofEntries(
                    Map.entry("gl_Vertex", "chimeraEntityVertexValue()"),
                    Map.entry("gl_Color", "Color"),
                    Map.entry("gl_MultiTexCoord0", "vec4(UV0, 0.0, 1.0)"),
                    Map.entry("gl_MultiTexCoord1", "vec4(vec2(UV1), 0.0, 1.0)"),
                    Map.entry("gl_MultiTexCoord2", "vec4(vec2(UV2), 0.0, 1.0)"),
                    Map.entry("gl_Normal", "Normal.xyz"),
                    Map.entry("mc_midTexCoord", "MidTexCoord"),
                    Map.entry("at_tangent", "Tangent"),
                    Map.entry("entityId", entityIdExpression(layout)));
            converted = GlslTokenRewriter.replaceIdentifiers(converted, inputs);
            converted = converted.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                    "chimeraEntityFtransform()");
            if (entityIdType != null) {
                converted = injectEntityIdAssignment(converted);
            }
            if (Pattern.compile("(?m)^\\s*(?:attribute|varying|in|out)\\s+")
                    .matcher(stripComments(converted)).find()) {
                throw new IllegalArgumentException("entity declaration was not consumed");
            }
            String entityIdVarying = entityIdType == null ? ""
                    : "layout(location = " + layout.location(ENTITY_ID_VARYING)
                    + ") flat out uint " + ENTITY_ID_VARYING + ";\n";
            return new TerrainVertexConversion("#version 460\n" + vertexPreamble
                    + entityIdVarying + converted,
                    layout);
        } catch (Exception e) {
            return null;
        }
    }

    /** Converts the legacy particle vertex contract onto DefaultVertexFormat.PARTICLE. */
    public static TerrainVertexConversion convertParticleVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertLegacyVertex(source, sourceFile, fragmentSource, PARTICLE_VERTEX_PREAMBLE);
    }

    /** Converts a particle fragment with the shared host sampler and varying rules. */
    public static String convertParticleFragment(
            String source,
            Path sourceFile,
            int[] samplerSlots,
            TerrainVaryingLayout particleLayout,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        try {
            String prepared = prepareSource(source, sourceFile);
            return convertFragment(prepared, null, true, samplerSlots, particleLayout,
                    interfacePlan, null, Map.of(), null);
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
        try {
            String prepared = prepareSource(source, sourceFile);
            validateEntityFragmentVersion(prepared);
            return convertFragment(prepared, null, true, samplerSlots, entityLayout, interfacePlan,
                    null, Map.of(), null);
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
        try {
            String src = prepareSource(source, sourceFile);
            String stripped = stripComments(src);
            if (!TERRAIN_VERSION.matcher(src).find()
                    || stripped.matches("(?s).*#version\\s+(?!120(?:e)?\\b)\\d+.*")) {
                throw new IllegalArgumentException("legacy vertex requires #version 120 or #version 120e");
            }
            if (!stripped.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")
                    || !stripped.matches("(?s).*\\bgl_Position\\b.*")) {
                throw new IllegalArgumentException("legacy vertex requires main and gl_Position");
            }
            rejectTerrainVertexFeatures(stripped);

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

    public static boolean supportsModernTerrain(String source, String fragmentSource) {
        if (!hasModernTerrainVersion(source)) {
            return false;
        }
        return convertModernTerrainVertex(source, null, fragmentSource) != null;
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
        int closing = result.lastIndexOf('}');
        if (closing < 0) throw new IllegalArgumentException("coverage fragment has no main body");
        return result.substring(0, closing)
                + "\n    chimeraCoverage = gl_FragCoord.z;\n"
                + result.substring(closing);
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

    private static String replaceTranslucentUniformNames(
            String source,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        String result = source;
        for (UniformRegistry.UniformDeclaration uniform : interfacePlan.executableUniforms()) {
            String fixedField = UniformRegistry.translucentUniformField(uniform.name());
            if (fixedField == null || fixedField.equals(uniform.name())) {
                continue;
            }
            result = result.replaceAll("\\b" + Pattern.quote(uniform.name()) + "\\b", fixedField);
        }
        return result;
    }
    /** Position of the sampler's registry slot in the emitted geometry config array. */
    private static int configIndexOf(
            String name,
            int[] slots,
            UniformRegistry.Stage stage
    ) {
        if (stage == UniformRegistry.Stage.SHADOW) {
            Integer slotValue = UniformRegistry.SHADOW_NAME_TO_SLOT.get(name);
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
                ? UniformRegistry.ENTITY_NAME_TO_SLOT : UniformRegistry.GEOMETRY_NAME_TO_SLOT;
        Integer slotValue = mapping.get(name);
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
        return block.append("};\n").toString();
    }

    /** Exact layout of the host terrain UBO at binding 1. */
    private static String terrainUniformBlock() {
        return """
                layout(binding = 1) uniform ChimeraTerrainUniforms {
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
                };
                """;
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
    private static String prepareSource(String source, Path sourceFile) {
        if (sourceFile == null) {
            return source;
        }
        Path root = sourceFile.getParent();
        ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepare(
                root, sourceFile, source);
        if (!prepared.successful()) {
            throw new IllegalArgumentException(prepared.deviations().toString());
        }
        return prepared.source();
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
                || stage == UniformRegistry.Stage.PARTICLE) {
            return replaceEntityVaryings(src, terrainLayout, "in", false);
        }
        if (src.contains("#version 460")) {
            Matcher modern = MODERN_TERRAIN_INPUT_DECL.matcher(src);
            StringBuilder modernOut = new StringBuilder();
            int modernLast = 0;
            while (modern.find()) {
                String qualifier = modern.group(1);
                String type = modern.group(2);
                String name = modern.group(3);
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

    private static String convertPostVaryings(String source, PostVaryingLayout layout) {
        List<PostVaryingDeclaration> declarations = postVaryingDeclarations(source);
        StringBuilder converted = new StringBuilder();
        int last = 0;
        for (PostVaryingDeclaration declaration : declarations) {
            converted.append(source, last, declaration.start());
            for (String name : declaration.names()) {
                if (!containsIdentifier(source.substring(0, declaration.start())
                        + source.substring(declaration.end()), name)) {
                    continue;
                }
                Integer location = layout.locations().get(name);
                String type = layout.types().get(name);
                if (location == null || type == null || !type.equals(declaration.type())) {
                    throw new IllegalArgumentException("post varying is not matched: " + name);
                }
                converted.append("layout(location = ").append(location).append(") ");
                if (declaration.qualifier() != null) {
                    converted.append(declaration.qualifier()).append(' ');
                }
                converted.append("in ").append(type).append(' ').append(name).append(';');
            }
            last = declaration.end();
        }
        converted.append(source, last, source.length());
        return converted.toString();
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
            String qualifier = matcher.group(2);
            String type = matcher.group(3);
            String name = matcher.group(4);
            boolean declarationApplies = vertexStage
                    ? qualifier.equals("varying") || qualifier.equals("out")
                    : qualifier.equals("varying") || qualifier.equals("in");
            if (!declarationApplies) {
                continue;
            }
            if (!layout.types().containsKey(name) || !layout.types().get(name).equals(type)) {
                throw new IllegalArgumentException("entity varying is not in the shared layout: " + name);
            }
            String withoutDeclaration = source.substring(0, matcher.start())
                    + source.substring(matcher.end());
            if (!containsIdentifier(withoutDeclaration, name)) {
                out.append(source, last, matcher.start());
                last = matcher.end();
                continue;
            }
            out.append(source, last, matcher.start());
            out.append("layout(location = ").append(layout.location(name)).append(") ")
                    .append(direction).append(' ').append(type).append(' ').append(name).append(';');
            last = matcher.end();
        }
        out.append(source, last, source.length());
        return out.toString();
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
                if (!name.equals("entityId") && !executable) {
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

    private static String removeEntityAttributes(String source) {
        Matcher matcher = Pattern.compile(
                "(?m)^\\s*(?:attribute|in)\\s+(?:float|vec2|vec4)\\s+"
                        + "(?:mc_midTexCoord|at_tangent)\\s*;\\s*")
                .matcher(source == null ? "" : source);
        return matcher.replaceAll("");
    }

    private static String entityIdentifierType(String vertexSource, String fragmentSource) {
        String vertex = vertexSource == null ? "" : vertexSource;
        String fragment = fragmentSource == null ? "" : stripComments(fragmentSource);
        Matcher declaration = ENTITY_ID_DECLARATION.matcher(vertex);
        if (declaration.find()) {
            return declaration.group(2);
        }
        declaration = ENTITY_ID_DECLARATION.matcher(fragment);
        if (declaration.find()) {
            return declaration.group(2);
        }
        if (containsIdentifier(vertex, "entityId") || containsIdentifier(fragment, "entityId")) {
            return "int";
        }
        return null;
    }

    private static String entityIdExpression(TerrainVaryingLayout layout) {
        if (layout != null && layout.locations().containsKey(ENTITY_ID_VARYING)) {
            return "float".equals(layout.types().get(ENTITY_ID_VARYING))
                    ? "float(" + ENTITY_ID_VARYING + ")"
                    : "int(" + ENTITY_ID_VARYING + ")";
        }
        return "float(EntityIds.x)";
    }

    private static String replaceEntityIdReferences(
            String source,
            TerrainVaryingLayout layout
    ) {
        if (layout == null || !layout.locations().containsKey(ENTITY_ID_VARYING)) {
            return source;
        }
        return GlslTokenRewriter.replaceIdentifiers(source,
                Map.of("entityId", entityIdExpression(layout)));
    }

    private static String entityIdFragmentDeclaration(TerrainVaryingLayout layout) {
        if (layout == null || !layout.locations().containsKey(ENTITY_ID_VARYING)) {
            return "";
        }
        return "layout(location = " + layout.location(ENTITY_ID_VARYING)
                + ") flat in uint " + ENTITY_ID_VARYING + ";\n";
    }

    private static String injectEntityIdAssignment(String source) {
        Matcher main = Pattern.compile(
                "\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{")
                .matcher(source);
        if (!main.find()) {
            throw new IllegalArgumentException("entity vertex main body is missing");
        }
        return source.substring(0, main.end())
                + "\n    " + ENTITY_ID_VARYING + " = EntityIds.x;"
                + source.substring(main.end());
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
        Map<String, String> result = new TreeMap<>();
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
            if (!vertexStage && !containsIdentifier(
                    source.substring(0, matcher.start()) + source.substring(matcher.end()), name)) {
                continue;
            }
            String previous = result.putIfAbsent(name, type);
            if (previous != null && !previous.equals(type)) {
                throw new IllegalArgumentException("entity varying declared with two types: " + name);
            }
        }
        return result;
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

    private static void rejectTerrainVertexFeatures(String source) {
        if (source.matches("(?s).*\\b(?:uniform|gl_Normal|gl_NormalMatrix|gl_ModelViewMatrix|"
                + "gl_ProjectionMatrix|gl_ModelViewProjectionMatrix|gl_TextureMatrix|"
                + "mc_midTexCoord|at_tangent|tangent|image\\w*|buffer)\\b.*")) {
            throw new IllegalArgumentException("unsupported terrain vertex feature");
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

    private static void rejectEntityVertexFeatures(String source, boolean particleInputs) {
        if (source.matches("(?s).*\\b(?:uniform|gl_NormalMatrix|gl_ModelViewMatrix|"
                + "gl_ProjectionMatrix|gl_ModelViewProjectionMatrix|gl_TextureMatrix|"
                + "image\\w*|buffer|geometry|tessellation|compute)\\b.*")) {
            throw new IllegalArgumentException("unsupported entity vertex feature");
        }
        Matcher attributes = Pattern.compile(
                "(?m)\\b(attribute|in)\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;").matcher(source);
        while (attributes.find()) {
            String type = attributes.group(2);
            String name = attributes.group(3);
            boolean supported = (name.equals("entityId")
                    && (type.equals("float") || type.equals("int")))
                    || (name.equals("mc_midTexCoord") && type.equals("vec2"))
                    || (name.equals("at_tangent") && type.equals("vec4"));
            if (!supported) {
                throw new IllegalArgumentException("unsupported entity attribute: " + name);
            }
        }
        if (source.matches("(?s).*\\b(attribute|in)\\b.*")
                && !source.matches("(?s).*\\b(attribute|in)\\s+(?:float|vec2|vec4)\\s+"
                + "(?:entityId|mc_midTexCoord|at_tangent)\\s*;.*")) {
            throw new IllegalArgumentException("unsupported entity attribute declaration");
        }
        if (source.matches("(?s).*\\b(?:layout|flat|noperspective)\\b.*")) {
            // Simple version 130 is accepted, but pack-authored explicit layouts
            // would conflict with the generated locations.
            throw new IllegalArgumentException("entity vertex layout is fixed by Chimera");
        }
        if (particleInputs && source.matches("(?s).*\\b(?:gl_Normal|gl_MultiTexCoord2)\\b.*")) {
            throw new IllegalArgumentException("hand vertex input is not present in the particle format");
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
            if (version != 330 && version != 400 && version != 460) {
                return false;
            }
        }
        return found;
    }

    static String normalizeModernTerrain(String source) {
        if (!hasModernTerrainVersion(source)) {
            throw new IllegalArgumentException("modern terrain requires GLSL 330, 400, or prepared 460");
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
        return "#version 460\n" + GLOBAL_NONCONST.matcher(result).replaceAll("$1 $2 =");
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
        return "#version 460\n" + GLOBAL_NONCONST.matcher(result).replaceAll("$1 $2 =");
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
        return replacements;
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

            vec4 chimeraTangentValue() {
                vec3 normal = normalize(inTangent.xyz);
                vec3 axis = abs(normal.y) < 0.999
                        ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                return vec4(normalize(cross(axis, normal)), 1.0);
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
            layout(location = 8) in vec4 Tangent;

            vec4 chimeraEntityVertexValue() {
                return vec4(Position, 1.0);
            }

            vec4 chimeraEntityFtransform() {
                return ProjMat * ModelViewMat * chimeraEntityVertexValue();
            }

            """;

    /** Block entities use the host block shader's camera-relative offset. */
    private static final String BLOCK_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("return vec4(Position, 1.0);", "return vec4(Position + ModelOffset, 1.0);");

    /** First-person hand inputs use the host particle layout, not NEW_ENTITY. */
    private static final String HAND_VERTEX_PREAMBLE = """
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
            layout(location = 1) in vec2 UV0;
            layout(location = 2) in vec4 Color;
            layout(location = 3) in ivec2 UV2;
            layout(location = 4) in uvec4 EntityIds;
            layout(location = 5) in vec2 MidTexCoord;
            layout(location = 6) in vec4 Tangent;

            vec4 chimeraEntityVertexValue() {
                return vec4(Position, 1.0);
            }

            vec4 chimeraEntityFtransform() {
                return ProjMat * ModelViewMat * chimeraEntityVertexValue();
            }

            """;

    /** Host particle transform blocks and DefaultVertexFormat.PARTICLE inputs. */
    private static final String PARTICLE_VERTEX_PREAMBLE = """
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
            layout(location = 1) in vec2 UV0;
            layout(location = 2) in vec4 Color;
            layout(location = 3) in ivec2 UV2;

            vec4 chimeraVertexValue() {
                return vec4(Position, 1.0);
            }

            vec4 chimeraColorValue() {
                return Color;
            }

            vec4 chimeraTexCoord0Value() {
                return vec4(UV0, 0.0, 1.0);
            }

            vec4 chimeraTexCoord1Value() {
                return vec4(vec2(UV2) / 256.0, 0.0, 1.0);
            }

            vec4 chimeraFtransform() {
                return ProjMat * ModelViewMat * chimeraVertexValue();
            }

            """;

    private static String convertTextureCalls(String src) {
        return GlslTokenRewriter.rewriteTextureCalls(src);
    }

    private static String rewriteSamplerDeclaration(String source, String name, int binding) {
        Pattern declaration = Pattern.compile(
                "(?:layout\\s*\\([^;{}]*\\)\\s*)?uniform\\s+(sampler2D(?:Shadow)?)\\s+"
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
                Map.of("gl_NormalMatrix", "mat3(1.0)"));
    }

    /** Emits vertex-stage shadow samplers with the same bindings as the fragment stage. */
    private static String rewriteShadowVertexSamplers(
            String source,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        if (interfacePlan == null || interfacePlan.samplers().isEmpty()) {
            return source;
        }
        TreeSet<Integer> slots = new TreeSet<>();
        for (UniformRegistry.SamplerBinding sampler : interfacePlan.samplers()) {
            slots.add(sampler.slot());
        }
        if (interfacePlan.samplers().stream().anyMatch(sampler -> sampler.slot() == 2)) {
            slots.add(0);
        }
        String result = source;
        int bindingBase = interfacePlan.stage() == UniformRegistry.Stage.SHADOW
                && !interfacePlan.executableUniforms().isEmpty()
                ? GEOMETRY_SAMPLER_BINDING_BASE + 1
                : GEOMETRY_SAMPLER_BINDING_BASE;
        if (interfacePlan.samplers().stream().anyMatch(sampler -> sampler.name().equals("texture"))) {
            result = GlslTokenRewriter.replaceIdentifiers(result,
                    Map.of("texture", "chimeraTexture"));
        }
        for (UniformRegistry.SamplerBinding sampler : interfacePlan.samplers()) {
            int slotIndex = 0;
            for (Integer slot : slots) {
                if (slot == sampler.slot()) {
                    break;
                }
                slotIndex++;
            }
            if (slotIndex >= slots.size()) {
                throw new IllegalArgumentException("shadow sampler is missing from the generated config: "
                        + sampler.name());
            }
            String sourceName = sampler.name().equals("texture")
                    ? "chimeraTexture" : sampler.name();
            if (hasSamplerDeclaration(result, sourceName)) {
                result = rewriteSamplerDeclaration(result, sourceName,
                        bindingBase + slotIndex);
            }
        }
        return result;
    }

    private static boolean hasSamplerDeclaration(String source, String name) {
        return Pattern.compile("(?:layout\\s*\\([^;{}]*\\)\\s*)?uniform\\s+"
                + "sampler2D(?:Shadow)?\\s+" + Pattern.quote(name) + "\\s*;")
                .matcher(source).find();
    }
}
