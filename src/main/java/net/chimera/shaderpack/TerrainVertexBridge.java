package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * Converts terrain, water and shadow vertex stages, legacy and modern, onto Chimera's terrain
 * vertex contract: the host terrain attributes, the gbuffer and shadow matrices, and the varying
 * locations the fragment stage reads.
 */
final class TerrainVertexBridge {
    private TerrainVertexBridge() {}

    static final Pattern TERRAIN_VARYING_DECL =
            Pattern.compile("(?m)\\bvarying\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;");

    static final Pattern TERRAIN_ATTRIBUTE_DECL =
            Pattern.compile("(?m)\\battribute\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;");

    static final Pattern SHADOW_ATTRIBUTE_DECL = Pattern.compile(
            "(?m)^[ \\t]*attribute[ \\t]+([A-Za-z_]\\w*)[ \\t]+"
                    + "(mc_Entity|mc_midTexCoord|at_midBlock)[ \\t]*;[ \\t]*(?:\\r?\\n|$)");

    static final Pattern MODERN_TERRAIN_DECL = Pattern.compile(
            "(?m)^\\s*(?:(flat|noperspective|smooth|centroid|sample)\\s+)?"
                    + "(in|out|varying)\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;");

    static final Pattern MODERN_TERRAIN_INPUT_DECL = Pattern.compile(
            "(?m)^\\s*(?:(flat|noperspective|smooth|centroid|sample)\\s+)?"
                    + "(?:in|varying)\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;");

    /**
     * Converts the strict terrain vertex subset. A null result means that
     * the source must use the identity terrain pipeline.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertTerrainVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertTerrainVertex(source, sourceFile, fragmentSource, false);
    }

    /** Converts legacy terrain source with storage blocks admitted by the plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertTerrainVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            boolean allowStorageBuffers
    ) {
        return convertLegacyVertex(source, sourceFile, fragmentSource,
                GlslVertexPreambles.TERRAIN_VERTEX_PREAMBLE, allowStorageBuffers);
    }

    /**
     * Converts the bounded modern terrain/water contract. This is intentionally
     * separate from the legacy bridge: modern source must use the shared
     * append-only material inputs and cannot silently fall through to the
     * 24-byte identity layout.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertModernTerrainVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                GlslVertexPreambles.MODERN_TERRAIN_VERTEX_PREAMBLE, null, false, false);
    }

    /** Converts an extended terrain vertex with the shared ordinary-uniform plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertModernTerrainVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertModernTerrainVertex(source, sourceFile, fragmentSource,
                interfacePlan, false);
    }

    /** Converts modern terrain source with storage blocks admitted by the plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertModernTerrainVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean allowStorageBuffers
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                GlslVertexPreambles.MODERN_TERRAIN_VERTEX_PREAMBLE, interfacePlan, false, allowStorageBuffers);
    }

    /** Converts the measured modern shadow vertex subset onto the shadow inputs. */
    public static LegacyGlslConverter.TerrainVertexConversion convertModernShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                GlslVertexPreambles.MODERN_SHADOW_VERTEX_PREAMBLE, null, true, false);
    }

    /** Converts a shadow vertex with the shared cross-stage uniform plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertModernShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                GlslVertexPreambles.MODERN_SHADOW_VERTEX_PREAMBLE, interfacePlan, true, false);
    }

    static LegacyGlslConverter.TerrainVertexConversion convertModernTerrainVertexInternal(
            String source,
            Path sourceFile,
            String fragmentSource,
            String vertexPreamble,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean shadowStage,
            boolean allowStorageBuffers
    ) {
        try {
            return convertModernTerrainVertexOrThrow(source, sourceFile, fragmentSource, vertexPreamble,
                    interfacePlan, shadowStage, false, allowStorageBuffers);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    static LegacyGlslConverter.TerrainVertexConversion convertModernTerrainVertexOrThrow(
            String source,
            Path sourceFile,
            String fragmentSource,
            String vertexPreamble,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean shadowStage,
            boolean entityInputs,
            boolean allowStorageBuffers
    ) {
            boolean extendedShadow = shadowStage && usesExtendedShadowInputs(source);
            String vertex = shadowStage
                    ? normalizeShadowTerrain(
                            EntityVertexBridge.defaultUnboundIdentityAttributes(
                            LegacyGlslConverter.prepareSource(source, sourceFile, true)))
                    : normalizeModernTerrain(
                            EntityVertexBridge.defaultUnboundIdentityAttributes(
                            LegacyGlslConverter.prepareSource(source, sourceFile, true)));
            String fragment = fragmentSource == null ? "" : fragmentSource;
            if (shadowStage && hasShadowTerrainVersion(fragment)) {
                fragment = normalizeShadowTerrain(fragment);
            } else if (hasModernTerrainVersion(fragment)) {
                fragment = normalizeModernTerrain(fragment);
            }
            vertex = LegacyGlslConverter.expandModernVaryingLists(vertex);
            fragment = LegacyGlslConverter.expandModernVaryingLists(fragment);
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(vertex);
            if (!usage.successful()) {
                throw new IllegalArgumentException(usage.deviations().toString());
            }
            vertex = GlslTokenRewriter.removeUnreachableFunctions(vertex, usage);
            // On the entity layout both stages read entityId and blockEntityId from EntityIds.
            Map<String, String> entityIdTypes = new LinkedHashMap<>();
            if (entityInputs) {
                String authoredVertex = stripComments(vertex);
                for (String name : EntityVertexBridge.ENTITY_ID_NAMES) {
                    String type = EntityVertexBridge.entityIdentifierType(name, authoredVertex, fragment);
                    if (type != null) entityIdTypes.put(name, type);
                }
            }
            vertex = interfacePlan == null
                    ? stripShadowUniformDeclarations(vertex)
                    : UniformRegistry.removeUniformDeclarations(vertex, interfacePlan);
            if (entityInputs) {
                vertex = EntityVertexBridge.removeEntityIdDeclarations(vertex, interfacePlan);
            }
            String stripped = stripComments(vertex);
            if (!stripped.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")
                    || !stripped.matches("(?s).*\\bgl_Position\\b.*")) {
                throw new IllegalArgumentException("modern terrain vertex main or position is missing");
            }
            if ((!allowStorageBuffers && stripped.matches("(?s).*\\bbuffer\\b.*"))
                    || stripped.matches("(?s).*\\b(?:geometry|tessellation|compute)\\b.*")
                    || stripped.matches(
                            "(?s).*\\buniform\\s+(?!(?:sampler|isampler|usampler|u?i?image(?:1D|2D|3D|Cube)))\\w+.*")) {
                throw new IllegalArgumentException("modern terrain resource or stage is unsupported");
            }

            // The shared typed scanner keeps integral, matrix and array varyings (Photon:
            // flat uint material_mask, flat mat3 tbn) and their location widths.
            EntityVertexBridge.EntityVaryingDeclarations vertexVaryings =
                    EntityVertexBridge.parseEntityVaryingDeclarations(vertex, true);
            Map<String, String> vertexTypes = vertexVaryings.types();
            Map<String, String> fragmentTypes =
                    EntityVertexBridge.parseEntityVaryingDeclarations(fragment, false).types();
            for (Map.Entry<String, String> entry : fragmentTypes.entrySet()) {
                if (!entry.getValue().equals(vertexTypes.get(entry.getKey()))) {
                    throw new IllegalArgumentException("modern terrain varying mismatch: " + entry.getKey());
                }
            }
            Map<String, Integer> locations = new TreeMap<>();
            int location = 0;
            for (String name : vertexTypes.keySet().stream().sorted().toList()) {
                locations.put(name, location);
                location += vertexVaryings.slots().get(name);
            }
            if (!entityIdTypes.isEmpty()) {
                vertexTypes = new TreeMap<>(vertexTypes);
                for (Map.Entry<String, String> id : entityIdTypes.entrySet()) {
                    locations.put(EntityVertexBridge.entityIdVarying(id.getKey()), location++);
                    vertexTypes.put(EntityVertexBridge.entityIdVarying(id.getKey()), id.getValue());
                }
            }
            LegacyGlslConverter.TerrainVaryingLayout layout = new LegacyGlslConverter.TerrainVaryingLayout(vertexTypes,
                    locations, vertexVaryings.qualifiers());

            // The prepared source keeps one version directive so the shared
            // plan can inspect it. The generated bridge owns the final
            // directive, so remove the prepared copy before composing the
            // executable vertex shader.
            String converted = LegacyGlslConverter.VERSION_LINE.matcher(removeShadowAttributes(
                            removeTerrainAttributes(removeModernTerrainInputs(vertex, layout), shadowStage),
                                    shadowStage))
                    .replaceAll("");
            Map<String, String> inputReplacements = modernTerrainInputReplacements(vertex);
            if (shadowStage) {
                inputReplacements.putAll(shadowAttributeInputReplacements(vertex));
                inputReplacements.putAll(entityInputs
                        ? EntityVertexBridge.entityShadowMatrixBuiltinReplacements()
                                : shadowMatrixBuiltinReplacements());
            }
            for (String name : entityIdTypes.keySet()) {
                inputReplacements.put(name, EntityVertexBridge.entityIdExpression(layout, name));
            }
            converted = GlslTokenRewriter.replaceIdentifiers(converted, inputReplacements);
            // Iris gives entity programs the draw's texture matrix (iris_TextureMat).
            converted = converted.replaceAll(
                    "\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]", entityInputs ? "TextureMat" : "mat4(1.0)");
            converted = LegacyGlslConverter.replaceLightmapTextureMatrices(converted);
            converted = converted.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                    "chimeraFtransform()");
            if (shadowStage) {
                converted = normalizeShadowLegacyBuiltins(converted);
            }
            converted = rewriteModernTerrainSamplers(converted, interfacePlan, shadowStage);
            converted = LegacyGlslConverter.convertTextureCalls(converted);
            if (MODERN_TERRAIN_INPUT_DECL.matcher(stripComments(converted)).find()) {
                throw new IllegalArgumentException("modern terrain input was not consumed");
            }
            String uniformBlock = shadowStage && interfacePlan != null
                    && !interfacePlan.executableUniforms().isEmpty()
                    ? FragmentConverter.generatedShadowUniformBlock(interfacePlan.executableUniforms(),
                    FragmentConverter.shadowUniformBinding(interfacePlan.stage()))
                    : !shadowStage && interfacePlan != null
                    && !interfacePlan.executableUniforms().isEmpty()
                    ? FragmentConverter.generatedGeometryUniformBlock(interfacePlan.executableUniforms()) : "";
            if (shadowStage && interfacePlan != null
                    && !interfacePlan.executableUniforms().isEmpty()) {
                converted = FragmentConverter.qualifyShadowUniformReferences(converted,
                        interfacePlan.executableUniforms());
            }
            String chimeraVaryings = "";
            for (String name : entityIdTypes.keySet()) {
                converted = LegacyGlslConverter.injectMainPrologue(converted,
                        EntityVertexBridge.entityIdVarying(name) + " = EntityIds."
                                + EntityVertexBridge.entityIdComponent(name) + ";");
                chimeraVaryings += "layout(location = " + layout.location(EntityVertexBridge.entityIdVarying(name))
                        + ") flat out uint " + EntityVertexBridge.entityIdVarying(name) + ";\n";
            }
            String effectivePreamble = shadowStage && !extendedShadow && !entityInputs
                    ? GlslVertexPreambles.SHADOW_VERTEX_PREAMBLE : vertexPreamble;
            String body = shadowStage ? shadowClipRangeWrapper(converted) : converted;
            return new LegacyGlslConverter.TerrainVertexConversion("#version 460\n"
                    + effectivePreamble + FragmentConverter.SHADOW_RENDER_STAGE_DEFINES
                    + chimeraVaryings + uniformBlock + body, layout);
    }

    /** Converts the same strict bridge for the shadow pipeline's one-matrix UBO. */
    public static LegacyGlslConverter.TerrainVertexConversion convertShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                GlslVertexPreambles.MODERN_SHADOW_VERTEX_PREAMBLE, null, true, false);
    }

    /** Name the pack-authored shadow entry point keeps after the wrapper claims {@code main}. */
    static final String AUTHORED_SHADOW_ENTRY = "chimeraAuthoredShadowMain";

    /** Converts the final legacy OpenGL clip range after the pack's authored vertex code runs. */
    static String shadowClipRangeWrapper(String source) {
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
    static Map<String, String> shadowMatrixBuiltinReplacements() {
        return Map.of(
                "gl_ModelViewMatrix", "shadowModelView",
                "gl_NormalMatrix", "mat3(transpose(shadowModelViewInverse))",
                "gl_ProjectionMatrix", "shadowProjection",
                "gl_ModelViewProjectionMatrix", "MVP");
    }

    /** Converts legacy and simple compatibility shadow vertices with one plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        return convertShadowVertex(source, sourceFile, fragmentSource, interfacePlan, false);
    }

    /** Converts a shadow vertex with an admitted storage-buffer declaration. */
    public static LegacyGlslConverter.TerrainVertexConversion convertShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean allowStorageBuffers
    ) {
        return convertModernTerrainVertexInternal(source, sourceFile, fragmentSource,
                GlslVertexPreambles.MODERN_SHADOW_VERTEX_PREAMBLE, interfacePlan, true, allowStorageBuffers);
    }

    static LegacyGlslConverter.TerrainVertexConversion convertLegacyVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            String vertexPreamble
    ) {
        return convertLegacyVertex(source, sourceFile, fragmentSource, vertexPreamble, false);
    }

    static LegacyGlslConverter.TerrainVertexConversion convertLegacyVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            String vertexPreamble,
            boolean allowStorageBuffers
    ) {
        try {
            String src = EntityVertexBridge.defaultUnboundIdentityAttributes(LegacyGlslConverter.prepareSource(source,
                    sourceFile, true));
            String stripped = stripComments(src);
            GlslVersion.requireSupported(stripped, "legacy vertex", true);
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
            LegacyGlslConverter.TerrainVaryingLayout layout = new LegacyGlslConverter.TerrainVaryingLayout(vertexTypes,
                    locations);

            String converted = LegacyGlslConverter.VERSION_LINE.matcher(src).replaceFirst("");
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
            return new LegacyGlslConverter.TerrainVertexConversion(
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
        if (!GlslVersion.supportedIfDeclared(source)) return false;
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

    /** Static probe helper for the strict shadow vertex bridge. */
    public static boolean supportsShadowVertex(String source, String fragmentSource) {
        return convertShadowVertex(source, null, fragmentSource) != null;
    }

    static String replaceTerrainVaryings(
            String source,
            LegacyGlslConverter.TerrainVaryingLayout layout,
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

    static Map<String, String> parseTerrainVaryings(String source) {
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

    static String terrainEntityType(String source) {
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

    static void rejectTerrainVertexFeatures(String source, boolean allowStorageBuffers) {
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

    static boolean hasModernTerrainVersion(String source) {
        return GlslVersion.declaredAndSupported(source);
    }

    static int modernTerrainVersion(String source) {
        return GlslVersion.highest(source);
    }

    static String normalizeModernTerrain(String source) {
        return normalizeTo460(source, "modern terrain");
    }

    /**
     * Removes the authored version, known legacy extensions, precision statements and qualifiers,
     * and declares 460. The result stays self-describing for the shared program plan; the
     * converter also accepts this prepared form, so the authored version is removed exactly once.
     */
    static String normalizeTo460(String source, String stage) {
        GlslVersion.requireSupported(source, stage, true);
        String result = LegacyGlslConverter.VERSION_LINE.matcher(source).replaceAll("");
        result = LegacyGlslConverter.KNOWN_LEGACY_EXTENSIONS.matcher(result).replaceAll("");
        result = LegacyGlslConverter.PRECISION_DECL.matcher(result).replaceAll("");
        result = LegacyGlslConverter.MODERN_LAYOUT_DECL.matcher(result).replaceAll("$1$2$3");
        result = GlslTokenRewriter.replaceIdentifiers(result, Map.of("lowp", "", "mediump", "", "highp", ""));
        return "#version 460\n" + GlslTokenRewriter.relaxNonConstantGlobals(result);
    }

    static boolean hasShadowTerrainVersion(String source) {
        return GlslVersion.declaredAndSupported(source);
    }

    static int shadowVersion(String source) {
        GlslVersion version = GlslVersion.first(source);
        return version == null ? 0 : version.number();
    }

    static boolean usesExtendedShadowInputs(String source) {
        String stripped = stripComments(source);
        return shadowVersion(source) >= 130
                || stripped.matches("(?s).*\\b(?:flat|noperspective|mc_midTexCoord|at_midBlock)\\b.*");
    }

    static String stripShadowUniformDeclarations(String source) {
        return Pattern.compile("(?m)\\buniform\\s+[A-Za-z_]\\w*\\s+[^;]+;\\s*")
                .matcher(source).replaceAll("");
    }

    static String normalizeShadowTerrain(String source) {
        return normalizeTo460(source, "shadow");
    }

    static String removeModernTerrainInputs(
            String source,
            LegacyGlslConverter.TerrainVaryingLayout layout
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
                // Keep the authored interpolation so both stages agree (flat mat3 tbn);
                // integers are always flat.
                String interpolation = layout.qualifier(name);
                if (!interpolation.isEmpty()) {
                    result.append(interpolation).append(' ');
                } else if (type.equals("int") || type.startsWith("ivec")
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

    static String removeShadowAttributes(String source, boolean shadowStage) {
        if (!shadowStage) return source;
        Matcher matcher = SHADOW_ATTRIBUTE_DECL.matcher(source);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            boolean supported = switch (name) {
                case "mc_Entity" -> type.equals("float") || type.equals("vec2") || type.equals("vec3")
                        || type.equals("vec4");
                case "mc_midTexCoord" -> type.equals("vec2") || type.equals("vec4");
                case "at_midBlock" -> type.equals("vec3") || type.equals("vec4")
                        || type.equals("ivec3") || type.equals("ivec4");
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
    static String removeTerrainAttributes(String source, boolean shadowStage) {
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
                    && !(type.equals("float") || type.equals("vec2") || type.equals("vec3") || type.equals("vec4"))) {
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

    static Map<String, String> modernTerrainInputReplacements(String source) {
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
        replacements.putIfAbsent("gl_MultiTexCoord2", "chimeraTexCoord1Value()");
        replacements.putIfAbsent("gl_Normal", "chimeraNormalValue()");
        replacements.putIfAbsent("mc_Entity", "chimeraMcEntityValue()");
        replacements.putIfAbsent("mc_midTexCoord", "chimeraMidTexCoordValue()");
        replacements.putIfAbsent("at_midBlock", "chimeraMidBlockValue().xyz");
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
            if (name.equals("at_midBlock")) {
                String expression = modernTerrainInputExpression(type, name);
                if (!expression.isBlank()) replacements.put(name, expression);
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
    static String rewriteModernTerrainSamplers(
            String source,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean shadowStage
    ) {
        if (interfacePlan == null || interfacePlan.samplers().isEmpty()) {
            return source;
        }
        String result = source;
        if (FragmentConverter.hasSamplerDeclaration(result, "texture")) {
            result = GlslTokenRewriter.renameSamplerIdentifier(result, "texture", "chimeraTexture");
        }
        int[] slots = shadowStage
                ? PackPipelines.shadowSamplerSlots(interfacePlan.samplerLayout().stream()
                .mapToInt(UniformRegistry.SamplerBinding::slot).toArray())
                : PackPipelines.interleaveLightmap(interfacePlan.samplerLayout().stream()
                .mapToInt(UniformRegistry.SamplerBinding::slot).toArray());
        int bindingBase = FragmentConverter.geometrySamplerBindingBase(interfacePlan);
        for (UniformRegistry.SamplerBinding sampler : interfacePlan.samplers()) {
            String sourceName = sampler.name().equals("texture")
                    ? "chimeraTexture" : sampler.name();
            if (!FragmentConverter.hasSamplerDeclaration(result, sourceName)) {
                continue;
            }
            int index = LegacyGlslConverter.configIndexOf(sampler.name(), slots, interfacePlan.stage(), interfacePlan);
            if (index < 0) {
                throw new IllegalArgumentException("geometry sampler is missing from the generated config: "
                        + sampler.name());
            }
            result = FragmentConverter.rewriteSamplerDeclaration(result, sourceName, bindingBase + index);
        }
        return result;
    }

    static Map<String, String> shadowAttributeInputReplacements(String source) {
        Map<String, String> replacements = new TreeMap<>();
        Matcher matcher = SHADOW_ATTRIBUTE_DECL.matcher(source == null ? "" : source);
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            String replacement = switch (name) {
                case "mc_Entity" -> switch (type) {
                    case "float" -> "chimeraMcEntityValue().x";
                    case "vec2" -> "chimeraMcEntityValue()";
                    case "vec3" -> "vec3(chimeraMcEntityValue(), 0.0)";
                    case "vec4" -> "vec4(chimeraMcEntityValue(), 0.0, 1.0)";
                    default -> "";
                };
                case "mc_midTexCoord" -> switch (type) {
                    case "vec2" -> "chimeraMidTexCoordValue()";
                    case "vec4" -> "vec4(chimeraMidTexCoordValue(), 0.0, 1.0)";
                    default -> "";
                };
                case "at_midBlock" -> modernTerrainInputExpression(type, name);
                default -> "";
            };
            if (!replacement.isBlank()) {
                replacements.put(name, replacement);
            }
        }
        return replacements;
    }

    static String modernTerrainInputExpression(String type, String name) {
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
                    return "ivec2(chimeraTexCoord1Value().xy)";
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
                baseType = "vec4";
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

    static String adaptModernTerrainType(String type, String value, String baseType) {
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

    /** Maps legacy matrix built-ins to the fixed terrain inputs used by the shadow adapter. */
    static String normalizeShadowLegacyBuiltins(String source) {
        String result = LegacyGlslConverter.replaceLightmapTextureMatrices(source).replaceAll(
                "\\bgl_TextureMatrix\\s*\\[\\s*\\d+\\s*\\]", "mat4(1.0)");
        return GlslTokenRewriter.replaceIdentifiers(result,
                Map.of(
                        // Vulkan GLSL exposes the vertex invocation index as
                        // gl_VertexIndex.  Iris legacy shadow code commonly
                        // uses gl_VertexID for quad/voxel producer gating.
                        "gl_VertexID", "gl_VertexIndex"));
    }
}
