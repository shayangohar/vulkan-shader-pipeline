package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * Prepares pack sources the way Iris's TransformPatcher does before any conversion: the
 * core-profile names, widened lines, the glint layer and the host-layout identities.
 */
final class IrisCoreProfile {
    private IrisCoreProfile() {}

    /** Iris core-profile vertex inputs. */
    private static final Set<String> CORE_INPUTS = Set.of("vaPosition", "vaColor", "vaNormal", "vaUV0", "vaUV1",
            "vaUV2");

    /** Iris core-profile matrices. */
    private static final Set<String> CORE_MATRICES = Set.of("modelViewMatrix", "modelViewMatrixInverse",
            "projectionMatrix", "projectionMatrixInverse", "normalMatrix", "textureMatrix");

    /** Iris core-profile uniforms: the matrices and chunkOffset. */
    private static final Set<String> CORE_UNIFORMS = Set.of("modelViewMatrix", "modelViewMatrixInverse",
            "projectionMatrix", "projectionMatrixInverse", "normalMatrix", "textureMatrix", "chunkOffset");

    /** The uniforms SodiumCoreTransformer renames outside the vertex stage: all but textureMatrix. */
    private static final Set<String> SODIUM_FRAGMENT_UNIFORMS = Set.of("modelViewMatrix",
            "modelViewMatrixInverse", "projectionMatrix", "projectionMatrixInverse", "normalMatrix", "chunkOffset");

    /** The matrices CompositeCoreTransformer gives a composite vertex stage. */
    private static final Set<String> COMPOSITE_VERTEX_UNIFORMS = Set.of("modelViewMatrix",
            "modelViewMatrixInverse", "projectionMatrix", "projectionMatrixInverse", "textureMatrix");

    /** The Iris transformer family that compiles a program (TransformPatcher's Patch). */
    enum Patch {
        /** Vanilla draws: entities, block entities, particles, sky, lines and the rest. */
        VANILLA,
        /** Sodium's terrain and shadow terrain passes (SodiumPrograms.Pass). */
        SODIUM,
        /** Fullscreen passes: shadowcomp, prepare, deferred, composite and final. */
        COMPOSITE,
        /**
         * A pack's own gbuffers_line: VANILLA in core mode whatever its stages' versions
         * (TransformPatcher's isLine). A compatibility stage still keeps its own variables of
         * the core names it does not declare as uniforms (PackPlanBuilder.irisCoreMode).
         */
        LINES
    }

    /**
     * The Iris patch that compiles a program: COMPOSITE for a fullscreen pass, LINES for a pack's
     * own gbuffers_line, SODIUM for terrain and shadow terrain (SodiumPrograms.Pass), and VANILLA
     * for the rest, shadow entities and host-layout shadow lanes included.
     */
    static Patch patchFor(String program, UniformRegistry.Stage stage, boolean packLine) {
        if (stage == UniformRegistry.Stage.POST) return Patch.COMPOSITE;
        if (packLine) return Patch.LINES;
        FamilyAdapterPlan.Family family = FamilyAdapterRegistry.forProgram(program).family();
        return family == FamilyAdapterPlan.Family.TERRAIN || family == FamilyAdapterPlan.Family.WATER
                || family == FamilyAdapterPlan.Family.SHADOW ? Patch.SODIUM : Patch.VANILLA;
    }

    /**
     * Iris TransformPatcher compiles a stage in core-profile mode for a core profile and for 150
     * and above with no profile, serving the core names whether or not the stage declares them.
     * Iris also uses that mode for every stage of a pack's own gbuffers_line ({@link Patch#LINES});
     * there a compatibility stage only gets the core names it declares, so its own variables of
     * those names stay its own. Read from the source as written: the modern post and terrain
     * normalizations drop the version line.
     */
    static boolean coreMode(String source) {
        GlslVersion version = GlslVersion.first(source);
        return version != null && version.irisCore();
    }

    /**
     * The core uniforms Iris gives one stage. Only its core-profile transformers name them:
     * VanillaCoreTransformer renames every one in every stage; SodiumCoreTransformer renames all
     * but textureMatrix, which it replaces with the identity in the vertex stage alone;
     * CompositeCoreTransformer replaces the vertex stage's matrices other than normalMatrix. Outside
     * core mode no transformer touches them, so a declared one is an ordinary uniform Iris never
     * sets: it reads its initializer, or zero.
     */
    static Set<String> servedUniforms(Patch patch, boolean vertexStage, boolean coreMode) {
        if (!coreMode && patch != Patch.LINES) return Set.of();
        return switch (patch) {
            case VANILLA, LINES -> CORE_UNIFORMS;
            case SODIUM -> vertexStage ? CORE_UNIFORMS : SODIUM_FRAGMENT_UNIFORMS;
            case COMPOSITE -> vertexStage ? COMPOSITE_VERTEX_UNIFORMS : Set.of();
        };
    }

    /** Iris SodiumCoreTransformer's overlay for a format without one: no hurt flash, no white flash. */
    public static final String NO_OVERLAY = "ivec2(0, 10)";

    /** The type Iris gives a core name a stage reads without declaring it (VanillaCoreTransformer). */
    static final Map<String, String> CORE_NAME_TYPES = Map.ofEntries(
            Map.entry("vaPosition", "vec3"), Map.entry("vaColor", "vec4"), Map.entry("vaNormal", "vec3"),
            Map.entry("vaUV0", "vec2"), Map.entry("vaUV1", "vec2"), Map.entry("vaUV2", "vec2"),
            Map.entry("modelViewMatrix", "mat4"), Map.entry("modelViewMatrixInverse", "mat4"),
            Map.entry("projectionMatrix", "mat4"), Map.entry("projectionMatrixInverse", "mat4"),
            Map.entry("normalMatrix", "mat3"), Map.entry("textureMatrix", "mat4"), Map.entry("chunkOffset", "vec3"));

    /**
     * Iris's core-profile mode (TransformPatcher: a core profile, 150 and above with no
     * profile, or a pack's own gbuffers_line) names the host inputs and matrices itself:
     * vaPosition and the other core inputs, and the core uniforms its transformer for the stage
     * serves ({@link #servedUniforms}). Each converter already serves the compatibility built-ins
     * with the same values (VanillaCoreTransformer maps both spellings to one input), so the
     * stage is rewritten to those before its interface is planned. In core mode Iris serves the
     * names whether or not the stage declares them; elsewhere it leaves a declared core input
     * unbound, which only a driver's attribute order made meaningful (Photon's compatibility
     * gbuffers_basic reads vaColor), so a declared one gets its intended value. A core uniform
     * the stage's transformer does not serve stays declared: Iris never sets it.
     *
     * <p>chunkOffset is zero: Chimera's positions are already camera-relative, which Iris's
     * vaPosition plus chunkOffset is. overlay is the stage's overlay coordinates: the host
     * input on the entity layouts, {@link #NO_OVERLAY} elsewhere. On a format without UVs, Iris
     * leaves vaUV0 and vaUV2 unbound (zero) where the compatibility inputs read (0.5, 0.5) and
     * full light; a pack's own gbuffers_line keeps Iris's zeros ({@link #prepareLineVertex}).</p>
     */
    public static String prepareIrisCoreProfile(String source, Path sourceFile, boolean vertexStage,
                                                String overlay, boolean coreMode, Patch patch) {
        if (source == null) return null;
        Set<String> served = servedUniforms(patch, vertexStage, coreMode);
        Map<String, String> declared = new TreeMap<>();
        String result = withoutCoreDeclarations(expandIncludes(source, sourceFile), vertexStage, served, declared);
        if (coreMode) {
            // The source is macro-expanded, as Iris's is: a core name the pack defines as a macro
            // (Noble's #define projectionMatrix gbufferProjection) has expanded away.
            String stripped = stripComments(result);
            for (Map.Entry<String, String> name : CORE_NAME_TYPES.entrySet()) {
                boolean named = name.getKey().startsWith("va") ? vertexStage : served.contains(name.getKey());
                if (named && LegacyGlslConverter.containsIdentifier(stripped, name.getKey())) {
                    declared.putIfAbsent(name.getKey(), name.getValue());
                }
            }
        }
        if (declared.isEmpty()) return source;
        Map<String, String> replacements = new TreeMap<>();
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            replacements.put(entry.getKey(), compatibilityValue(entry.getKey(), entry.getValue(), overlay));
        }
        return GlslTokenRewriter.replaceIdentifiers(result, replacements);
    }

    /** The compatibility built-in that serves one core name, read as the type the stage gives it. */
    private static String compatibilityValue(String name, String type, String overlay) {
        return switch (name) {
            case "vaPosition" -> type.equals("vec4") ? "gl_Vertex"
                    : TerrainVertexBridge.adaptModernTerrainType(type, "gl_Vertex.xyz", "vec3");
            case "vaColor" -> TerrainVertexBridge.adaptModernTerrainType(type, "gl_Color", "vec4");
            case "vaNormal" -> TerrainVertexBridge.adaptModernTerrainType(type, "gl_Normal", "vec3");
            case "vaUV0" -> TerrainVertexBridge.adaptModernTerrainType(type, "gl_MultiTexCoord0.xy", "vec2");
            case "vaUV1" -> type.equals("ivec2") ? overlay
                    : TerrainVertexBridge.adaptModernTerrainType(type, "vec2(" + overlay + ")", "vec2");
            case "vaUV2" -> TerrainVertexBridge.adaptModernTerrainType(type, "gl_MultiTexCoord1.xy", "vec2");
            case "modelViewMatrix" -> "gl_ModelViewMatrix";
            case "modelViewMatrixInverse" -> "inverse(gl_ModelViewMatrix)";
            case "projectionMatrix" -> "gl_ProjectionMatrix";
            case "projectionMatrixInverse" -> "inverse(gl_ProjectionMatrix)";
            case "normalMatrix" -> "gl_NormalMatrix";
            case "textureMatrix" -> "gl_TextureMatrix[0]";
            default -> "vec3(0.0)"; // chunkOffset
        };
    }

    /**
     * The source without its plain declarations of the core inputs (a vertex stage's) and its
     * declarations of the core uniforms the stage is served, each recorded in {@code declared}
     * with the type the stage gave it. A served uniform's initializer goes with it: Iris's value
     * replaces it.
     */
    private static String withoutCoreDeclarations(String source, boolean vertexStage, Set<String> served,
                                                  Map<String, String> declared) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> removals = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            boolean input = vertexStage && CORE_INPUTS.contains(variable.name()) && isInput(variable)
                    && variable.plain();
            boolean uniform = variable.qualified("uniform") && served.contains(variable.name()) && variable.single();
            if (input || uniform) {
                declared.put(variable.name(), variable.type());
                removals.add(GlslDeclarations.remove(tree, variable.declaration()));
            }
        }
        return GlslDeclarations.apply(source, removals);
    }

    private static boolean isInput(GlslDeclarations.Variable variable) {
        return variable.qualified("in") || variable.qualified("attribute");
    }

    /**
     * The source without its plain declarations of the core inputs, of the core matrices as mat3
     * or mat4, and of chunkOffset as vec3.
     */
    private static String withoutLineCoreDeclarations(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> removals = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            boolean input = CORE_INPUTS.contains(variable.name()) && isInput(variable);
            String type = variable.type();
            boolean matrix = CORE_MATRICES.contains(variable.name()) && (type.equals("mat3") || type.equals("mat4"));
            boolean offset = variable.name().equals("chunkOffset") && type.equals("vec3");
            // A served uniform's initializer goes with it, as in withoutCoreDeclarations.
            boolean uniform = variable.qualified("uniform") && (matrix || offset) && variable.single();
            if (input && variable.plain() || uniform) {
                removals.add(GlslDeclarations.remove(tree, variable.declaration()));
            }
        }
        return GlslDeclarations.apply(source, removals);
    }

    /** The source with its first main renamed {@code chimeraLineMain}, signature normalized to {@code ()}. */
    private static String renameMain(String source) {
        String renamed = GlslDeclarations.renameMain(source, "chimeraLineMain");
        if (renamed == null) throw new IllegalArgumentException("line vertex requires main");
        return renamed;
    }

    /**
     * Applies Iris's line handling to an authored vertex source before its interface is
     * planned. Iris compiles the pack's own gbuffers_line through VanillaCoreTransformer
     * (IrisRenderingPipeline: isLines when the pack has the program): the core inputs
     * and matrices name the host values, and the program widens its own line. A fallback
     * program (gbuffers_basic) is widened by Iris instead (VanillaTransformer, new lines):
     * its main runs twice and gl_ModelViewMatrix carries VIEW_SHRINK.
     */
    public static String prepareLineVertex(String source, Path sourceFile, boolean packLineProgram) {
        if (source == null) return null;
        String src = expandIncludes(source, sourceFile);
        src = GlslTokenRewriter.replaceIdentifiers(src, Map.of("gl_VertexID", "chimeraLineVertexIndex"));
        if (packLineProgram) {
            src = withoutLineCoreDeclarations(src);
            return GlslTokenRewriter.replaceIdentifiers(src, Map.ofEntries(
                    Map.entry("vaPosition", "gl_Vertex.xyz"),
                    Map.entry("vaColor", "gl_Color"),
                    Map.entry("vaNormal", "gl_Normal"),
                    Map.entry("vaUV0", "vec2(0.0)"),
                    Map.entry("vaUV1", "vec2(0.0)"),
                    Map.entry("vaUV2", "vec2(0.0)"),
                    Map.entry("modelViewMatrix", "gl_ModelViewMatrix"),
                    Map.entry("modelViewMatrixInverse", "inverse(gl_ModelViewMatrix)"),
                    Map.entry("projectionMatrix", "gl_ProjectionMatrix"),
                    Map.entry("projectionMatrixInverse", "inverse(gl_ProjectionMatrix)"),
                    Map.entry("normalMatrix", "gl_NormalMatrix"),
                    Map.entry("textureMatrix", "gl_TextureMatrix[0]"),
                    Map.entry("chunkOffset", "vec3(0.0)")));
        }
        src = src.replaceAll("\\bftransform\\s*\\(\\s*\\)", "(gl_ModelViewProjectionMatrix * gl_Vertex)");
        src = GlslTokenRewriter.replaceIdentifiers(src, Map.of(
                "gl_Vertex", "(gl_Vertex + vec4(chimeraLineOffset, 0.0))",
                "gl_ModelViewMatrix", "(chimeraLineViewScale * gl_ModelViewMatrix)",
                "gl_ModelViewProjectionMatrix", "(gl_ProjectionMatrix * chimeraLineViewScale * gl_ModelViewMatrix)",
                // Iris feeds a widened program +Z; only the wrapper reads the line direction.
                "gl_Normal", "vec3(0.0, 0.0, 1.0)"));
        src = renameMain(src);
        String globals = GlslVertexPreambles.LINE_WIDEN_GLOBALS;
        Set<String> declared = new HashSet<>();
        UniformRegistry.scanUniformDeclarations(stripComments(src)).forEach(value -> declared.add(value.name()));
        if (!declared.contains("viewWidth")) globals += "uniform float viewWidth;\n";
        if (!declared.contains("viewHeight")) globals += "uniform float viewHeight;\n";
        return insertAfterHeader(src, globals) + GlslVertexPreambles.LINE_WIDEN_MAIN;
    }

    /**
     * Iris VanillaTransformer for GLINT (isGlint): gl_Color is
     * vec4(ColorModulator.rgb, ColorModulator.a * GlintAlpha). Applied to the authored vertex
     * before its interface is planned, so the glint strength is an ordinary planned uniform.
     */
    public static String prepareGlintVertex(String source, Path sourceFile) {
        if (source == null) return null;
        String src = expandIncludes(source, sourceFile);
        src = GlslTokenRewriter.replaceIdentifiers(src, Map.of("gl_Color",
                "vec4(gl_Color.rgb, gl_Color.a * " + UniformRegistry.GLINT_ALPHA + ")"));
        return insertAfterHeader(src, "uniform float " + UniformRegistry.GLINT_ALPHA + ";\n");
    }

    /**
     * Expands a host-layout stage's includes before its interface is planned; the line and
     * glint vertices do this in {@link #prepareLineVertex} and {@link #prepareGlintVertex}.
     */
    public static String prepareHostLayoutSource(String source, Path sourceFile) {
        return source == null ? null : expandIncludes(source, sourceFile);
    }

    /**
     * Iris applies EntityPatcher only to entity and text formats. On any other host layout
     * entityId and blockEntityId stay uniforms (CommonUniforms). blockEntityId is the constant
     * -1. entityId is Iris's fallback "for when entityId via attributes cannot be used
     * (lightning)", read from CapturedRenderingState when the batch draws: here the per-draw
     * {@link UniformRegistry#HOST_ENTITY_ID}, which is 0 except while a lightning bolt draws.
     * Applied to both stages of a host-layout program before planning.
     */
    public static String prepareHostLayoutIdentity(String source) {
        if (source == null) return null;
        Map<String, String> types = new HashMap<>();
        for (EntityDeclarations.IdentityDeclaration declaration
                : EntityDeclarations.identityDeclarations(stripComments(source))) {
            for (String name : declaration.names()) {
                if (EntityVertexBridge.ENTITY_ID_NAMES.contains(name)) types.put(name, declaration.type());
            }
        }
        if (types.isEmpty()) return source;
        String src = EntityDeclarations.removeEntityIdDeclarations(source);
        Map<String, String> replacements = new HashMap<>();
        String entityId = UniformRegistry.HOST_ENTITY_ID;
        if (types.containsKey("entityId")) {
            replacements.put("entityId", "float".equals(types.get("entityId")) ? "float(" + entityId + ")"
                    : "(" + entityId + ")");
        }
        if (types.containsKey("blockEntityId")) {
            replacements.put("blockEntityId", "float".equals(types.get("blockEntityId")) ? "(-1.0)" : "(-1)");
        }
        // CapturedRenderingState's item is 0 by the time a host-layout batch draws.
        if (types.containsKey("currentRenderedItemId")) {
            replacements.put("currentRenderedItemId",
                    "float".equals(types.get("currentRenderedItemId")) ? "(0.0)" : "(0)");
        }
        String folded = GlslTokenRewriter.replaceIdentifiers(src, replacements);
        return types.containsKey("entityId") ? insertAfterHeader(folded, "uniform int " + entityId + ";\n") : folded;
    }

    static String expandIncludes(String source, Path sourceFile) {
        if (sourceFile == null) return source;
        ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepare(
                sourceFile.getParent(), sourceFile, source,
                PackEngineDefines.forPack(Map.of()),
                PackEngineDefines.lockedNames(Set.of(), false, false));
        if (!prepared.successful()) {
            throw new IllegalArgumentException(prepared.deviations().toString());
        }
        return prepared.source();
    }

    /**
     * Inserts declarations after the #version and #extension lines that must lead the source, and
     * the blank lines among them: after the last of the leading lines that end in a line break.
     */
    static String insertAfterHeader(String source, String declarations) {
        Map<Integer, GlslDirectives.Line> directives = new HashMap<>();
        for (GlslDirectives.Line line : GlslDirectives.lines(source)) directives.put(line.hash(), line);
        int insert = 0;
        for (int lineEnd = source.indexOf('\n'); lineEnd >= 0; lineEnd = source.indexOf('\n', insert)) {
            int text = insert;
            while (text < lineEnd && (source.charAt(text) == ' ' || source.charAt(text) == '\t')) text++;
            GlslDirectives.Line directive = directives.get(text);
            boolean blank = text == lineEnd || text == lineEnd - 1 && source.charAt(text) == '\r';
            boolean header = directive != null && directive.adjacent()
                    && (directive.is("version") || directive.is("extension"));
            if (!blank && !header) break;
            insert = lineEnd + 1;
        }
        return source.substring(0, insert) + declarations + source.substring(insert);
    }
}
