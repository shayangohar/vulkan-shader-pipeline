package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * Converts terrain, water and shadow vertex stages, legacy and modern, onto Chimera's terrain
 * vertex contract: the host terrain attributes, the gbuffer and shadow matrices, and the varying
 * locations the fragment stage reads.
 */
final class TerrainVertexBridge {
    private TerrainVertexBridge() {}

    /** The whitespace after a removed declaration, up to the next text. */
    private static final Pattern WHITESPACE_TAIL = Pattern.compile("\\s*");
    /** A modern terrain vertex may declare only these uniforms: samplers and images. */
    private static final Pattern OPAQUE_PREFIX =
            Pattern.compile("(?:sampler|isampler|usampler|u?i?image(?:1D|2D|3D|Cube)).*");

    /** Stage names a terrain vertex may not mention: it runs as a plain vertex stage. */
    private static final Set<String> OTHER_STAGES = Set.of("geometry", "tessellation", "compute");
    /** Modern interface syntax, and inputs only the extended terrain format carries. */
    private static final Set<String> EXTENDED_TERRAIN_WORDS = Set.of("in", "out", "flat", "noperspective",
            "mc_midTexCoord", "at_tangent", "gl_NormalMatrix", "gl_ModelViewMatrix", "gl_ProjectionMatrix",
            "gl_TextureMatrix");
    /** What the 24-byte legacy terrain bridge cannot serve; any {@code image*} name is refused too. */
    private static final Set<String> LEGACY_TERRAIN_REFUSED = Set.of("uniform", "gl_Normal", "gl_NormalMatrix",
            "gl_ModelViewMatrix", "gl_ProjectionMatrix", "gl_ModelViewProjectionMatrix", "gl_TextureMatrix",
            "mc_midTexCoord", "at_tangent", "tangent");
    private static final Set<String> MODERN_SYNTAX = Set.of("layout", "in", "out", "flat", "noperspective");
    private static final Set<String> EXTENDED_SHADOW_WORDS = Set.of("flat", "noperspective", "mc_midTexCoord",
            "at_midBlock");

    /** Whether the stage still declares or names {@code attribute} anywhere outside comments. */
    private static boolean namesAttribute(String source) {
        return GlslTokenRewriter.containsIdentifier(source, "attribute");
    }

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
        String prepared = EntityDeclarations.defaultUnboundIdentityAttributes(
                LegacyGlslConverter.prepareSource(source, sourceFile, true));
        String vertex = shadowStage ? normalizeShadowTerrain(prepared) : normalizeModernTerrain(prepared);
        String fragment = normalizedFragment(fragmentSource, shadowStage);
        vertex = LegacyGlslConverter.expandModernVaryingLists(vertex);
        fragment = LegacyGlslConverter.expandModernVaryingLists(fragment);
        vertex = withoutUnreachableFunctions(vertex);
        // On the entity layout both stages read the entity identities from EntityIds.
        Map<String, String> entityIdTypes = entityInputs
                ? EntityVertexBridge.entityIdTypes(stripComments(vertex), fragment) : Map.of();
        vertex = interfacePlan == null
                ? stripShadowUniformDeclarations(vertex)
                : UniformRegistry.removeUniformDeclarations(vertex, interfacePlan);
        if (entityInputs) {
            vertex = EntityDeclarations.removeEntityIdDeclarations(vertex, interfacePlan);
        }
        requireSupportedModernVertex(stripComments(vertex), allowStorageBuffers);
        LegacyGlslConverter.TerrainVaryingLayout layout = modernVaryingLayout(vertex, fragment, entityIdTypes);

        // The prepared source keeps one version directive so the shared
        // plan can inspect it. The generated bridge owns the final
        // directive, so remove the prepared copy before composing the
        // executable vertex shader.
        String converted = LegacyGlslConverter.withoutVersionLines(removeShadowAttributes(
                        removeTerrainAttributes(removeModernTerrainInputs(vertex, layout), shadowStage), shadowStage));
        Map<String, String> inputReplacements = modernTerrainInputReplacements(vertex);
        if (shadowStage) {
            inputReplacements.putAll(shadowInputReplacements(vertex, entityInputs));
        }
        inputReplacements.putAll(EntityVertexBridge.entityIdReplacements(entityIdTypes.keySet(), layout));
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
        if (!TerrainDeclarations.modern(stripComments(converted), TerrainDeclarations.MODERN_INPUT_STORAGES)
                .isEmpty()) {
            throw new IllegalArgumentException("modern terrain input was not consumed");
        }
        String uniformBlock = modernUniformBlock(interfacePlan, shadowStage);
        if (shadowStage) {
            converted = qualifyPlannedShadowUniforms(converted, interfacePlan);
        }
        converted = EntityVertexBridge.injectEntityIdPrologues(converted, entityIdTypes.keySet());
        String chimeraVaryings = EntityVertexBridge.entityIdOutputDeclarations(entityIdTypes.keySet(), layout);
        String effectivePreamble = shadowStage && !extendedShadow && !entityInputs
                ? GlslVertexPreambles.SHADOW_VERTEX_PREAMBLE : vertexPreamble;
        String body = shadowStage ? shadowClipRangeWrapper(converted) : converted;
        return new LegacyGlslConverter.TerrainVertexConversion("#version 460\n"
                + effectivePreamble + FragmentConverter.SHADOW_RENDER_STAGE_DEFINES
                + chimeraVaryings + uniformBlock + body, layout);
    }

    /** The fragment source normalized as its vertex stage is, when it declares a supported version. */
    private static String normalizedFragment(String fragmentSource, boolean shadowStage) {
        String fragment = fragmentSource == null ? "" : fragmentSource;
        if (shadowStage && hasShadowTerrainVersion(fragment)) {
            return normalizeShadowTerrain(fragment);
        }
        return hasModernTerrainVersion(fragment) ? normalizeModernTerrain(fragment) : fragment;
    }

    /** Drops the functions main never reaches; a source the resource analysis refuses fails with its reasons. */
    private static String withoutUnreachableFunctions(String vertex) {
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(vertex);
        if (!usage.successful()) {
            throw new IllegalArgumentException(usage.deviations().toString());
        }
        return GlslTokenRewriter.removeUnreachableFunctions(vertex, usage);
    }

    /**
     * The shadow stage's attribute inputs and legacy matrix built-ins: the draw's own host
     * matrices on the entity layout, the light's matrices otherwise.
     */
    private static Map<String, String> shadowInputReplacements(String vertex, boolean entityInputs) {
        Map<String, String> replacements = shadowAttributeInputReplacements(vertex);
        replacements.putAll(entityInputs
                ? EntityVertexBridge.entityShadowMatrixBuiltinReplacements()
                : shadowMatrixBuiltinReplacements());
        return replacements;
    }

    /** Reads the shadow stage's planned uniforms through the generated shadow block's instance. */
    private static String qualifyPlannedShadowUniforms(String source, UniformRegistry.ProgramInterface interfacePlan) {
        if (interfacePlan == null || interfacePlan.executableUniforms().isEmpty()) {
            return source;
        }
        return FragmentConverter.qualifyShadowUniformReferences(source, interfacePlan.executableUniforms());
    }

    /** The strict modern subset: a main that writes gl_Position, and no unsupported resource or stage. */
    private static void requireSupportedModernVertex(String stripped, boolean allowStorageBuffers) {
        GlslTree tree = GlslTree.parse(stripped);
        if (GlslDeclarations.main(tree) == null || !GlslTokenRewriter.containsIdentifier(stripped, "gl_Position")) {
            throw new IllegalArgumentException("modern terrain vertex main or position is missing");
        }
        if ((!allowStorageBuffers && GlslTokenRewriter.containsIdentifier(stripped, "buffer"))
                || GlslTokenRewriter.containsIdentifier(stripped, OTHER_STAGES::contains)
                || declaresValueUniform(tree)) {
            throw new IllegalArgumentException("modern terrain resource or stage is unsupported");
        }
    }

    /**
     * Whether a {@code uniform} qualifier is followed by a word that names no sampler or image:
     * the next qualifier, the type, or an interface block's name.
     */
    private static boolean declaresValueUniform(GlslTree tree) {
        for (GlslTree.External external : tree.externals()) {
            List<GlslTree.Qualifier> qualifiers = declarationQualifiers(external);
            for (int index = 0; index < qualifiers.size(); index++) {
                if (!tree.text(qualifiers.get(index).keyword()).equals("uniform")) continue;
                String next = index + 1 < qualifiers.size()
                        ? tree.text(qualifiers.get(index + 1).keyword()) : typeName(tree, external);
                if (!next.isEmpty() && !OPAQUE_PREFIX.matcher(next).matches()) return true;
            }
        }
        return false;
    }

    /** The qualifiers of a file-scope declaration or interface block; none for anything else. */
    private static List<GlslTree.Qualifier> declarationQualifiers(GlslTree.External external) {
        if (external instanceof GlslTree.Declaration declaration) return declaration.qualifiers();
        if (external instanceof GlslTree.InterfaceBlock block) return block.qualifiers();
        return List.of();
    }

    /** The word after a declaration's qualifiers: its type's name, or an interface block's name; empty otherwise. */
    private static String typeName(GlslTree tree, GlslTree.External external) {
        if (external instanceof GlslTree.InterfaceBlock block) return tree.text(block.name());
        if (!(external instanceof GlslTree.Declaration declaration) || declaration.type() == null) return "";
        return declaration.type().name() >= 0 ? tree.text(declaration.type().name()) : "struct";
    }

    /**
     * Locations for the vertex outputs in name order, then the entity id varyings. The shared typed
     * scanner keeps integral, matrix and array varyings (Photon: flat uint material_mask, flat mat3
     * tbn) and their location widths; a fragment input of another type is refused.
     */
    private static LegacyGlslConverter.TerrainVaryingLayout modernVaryingLayout(
            String vertex, String fragment, Map<String, String> entityIdTypes) {
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
        return new LegacyGlslConverter.TerrainVaryingLayout(vertexTypes, locations, vertexVaryings.qualifiers());
    }

    /** The generated uniform block: the shadow pipeline's own binding, or the geometry block. */
    private static String modernUniformBlock(UniformRegistry.ProgramInterface interfacePlan, boolean shadowStage) {
        if (interfacePlan == null || interfacePlan.executableUniforms().isEmpty()) return "";
        return shadowStage
                ? FragmentConverter.generatedShadowUniformBlock(interfacePlan.executableUniforms(),
                        FragmentConverter.shadowUniformBinding(interfacePlan.stage()))
                : FragmentConverter.generatedGeometryUniformBlock(interfacePlan.executableUniforms());
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
            String src = EntityDeclarations.defaultUnboundIdentityAttributes(LegacyGlslConverter.prepareSource(source,
                    sourceFile, true));
            String stripped = stripComments(src);
            GlslVersion.requireSupported(stripped, "legacy vertex", true);
            if (GlslDeclarations.main(GlslTree.parse(stripped)) == null
                    || !GlslTokenRewriter.containsIdentifier(stripped, "gl_Position")) {
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

            String converted = LegacyGlslConverter.withoutFirstVersionLine(src);
            converted = withoutEntityAttributes(converted);
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
            if (GlslTokenRewriter.containsIdentifier(converted, "mc_Entity")) {
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
                || GlslTokenRewriter.containsIdentifier(stripped, EXTENDED_TERRAIN_WORDS::contains);
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

    /** The types a legacy terrain varying may have, and the types the legacy mc_Entity attribute may have. */
    private static final Set<String> LEGACY_VARYING_TYPES = Set.of("float", "vec2", "vec3", "vec4");
    private static final Set<String> LEGACY_ENTITY_TYPES = Set.of("float", "vec2");

    static String replaceTerrainVaryings(
            String source,
            LegacyGlslConverter.TerrainVaryingLayout layout,
            String direction
    ) {
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        for (TerrainDeclarations.Declaration varying : TerrainDeclarations.keyword(source, "varying")) {
            String type = varying.type();
            String name = varying.name();
            if (!type.equals(layout.types().get(name)) || !LEGACY_VARYING_TYPES.contains(type)) {
                throw new IllegalArgumentException("unsupported terrain varying: " + name);
            }
            edits.add(new GlslDeclarations.TextEdit(varying.start(), varying.end(), "layout(location = "
                    + layout.location(name) + ") " + direction + ' ' + type + ' ' + name + ';'));
        }
        return GlslDeclarations.apply(source, edits);
    }

    static Map<String, String> parseTerrainVaryings(String source) {
        Map<String, String> result = new TreeMap<>();
        for (TerrainDeclarations.Declaration varying : TerrainDeclarations.keyword(source, "varying")) {
            String type = varying.type();
            if (!LEGACY_VARYING_TYPES.contains(type)) {
                throw new IllegalArgumentException("unsupported terrain varying type: " + type);
            }
            String previous = result.putIfAbsent(varying.name(), type);
            if (previous != null && !previous.equals(type)) {
                throw new IllegalArgumentException("terrain varying declared with two types: " + varying.name());
            }
        }
        return result;
    }

    static String terrainEntityType(String source) {
        String entityType = null;
        for (TerrainDeclarations.Declaration attribute : TerrainDeclarations.keyword(source, "attribute")) {
            String type = attribute.type();
            if (!attribute.name().equals("mc_Entity")) {
                throw new IllegalArgumentException("unsupported terrain attribute: " + attribute.name());
            }
            if (!LEGACY_ENTITY_TYPES.contains(type)) {
                throw new IllegalArgumentException("unsupported mc_Entity type: " + type);
            }
            if (entityType != null && !entityType.equals(type)) {
                throw new IllegalArgumentException("mc_Entity declared with two types");
            }
            entityType = type;
        }
        return entityType;
    }

    /**
     * Removes each line-starting {@code attribute float|vec2 mc_Entity;} with the blank lines
     * before it and the whitespace after it, each span starting where the previous one ended.
     */
    private static String withoutEntityAttributes(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        int previousEnd = 0;
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (!variable.qualifiers().equals(List.of("attribute")) || !variable.plain()
                    || !variable.name().equals("mc_Entity") || !LEGACY_ENTITY_TYPES.contains(variable.type())) {
                continue;
            }
            int start = GlslDeclarations.lineStartBefore(source,
                    tree.offset(variable.declaration().range().start()), previousEnd);
            if (start < 0) continue;
            previousEnd = GlslDeclarations.tailEnd(source, tree.offset(variable.declaration().range().end()),
                    WHITESPACE_TAIL);
            edits.add(new GlslDeclarations.TextEdit(start, previousEnd, ""));
        }
        return GlslDeclarations.apply(source, edits);
    }

    static void rejectTerrainVertexFeatures(String source, boolean allowStorageBuffers) {
        if (GlslTokenRewriter.containsIdentifier(source,
                name -> LEGACY_TERRAIN_REFUSED.contains(name) || name.startsWith("image"))) {
            throw new IllegalArgumentException("unsupported terrain vertex feature");
        }
        if (!allowStorageBuffers && GlslTokenRewriter.containsIdentifier(source, "buffer")) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        if (GlslTokenRewriter.containsIdentifier(source, MODERN_SYNTAX::contains)) {
            throw new IllegalArgumentException("modern GLSL is not supported in terrain vertex");
        }
        List<TerrainDeclarations.Declaration> attributes = TerrainDeclarations.keyword(source, "attribute");
        if (attributes.stream().anyMatch(attribute -> !attribute.name().equals("mc_Entity"))) {
            throw new IllegalArgumentException("unsupported terrain attribute");
        }
        if (namesAttribute(source)
                && attributes.stream().noneMatch(attribute -> LEGACY_ENTITY_TYPES.contains(attribute.type()))) {
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
        String result = LegacyGlslConverter.withoutVersionLines(source);
        result = LegacyGlslConverter.withoutKnownLegacyExtensions(result);
        result = LegacyGlslConverter.withoutPrecisionStatements(result);
        result = LegacyGlslConverter.withoutInterfaceLayouts(result);
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
                || GlslTokenRewriter.containsIdentifier(stripped, EXTENDED_SHADOW_WORDS::contains);
    }

    /**
     * Removes every uniform declaration and uniform block from its {@code uniform} keyword through
     * the whitespace after it; a layout before the keyword stays, as the old pattern left it.
     */
    static String stripShadowUniformDeclarations(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (!typeName(tree, external).isEmpty()) {
                for (GlslTree.Qualifier qualifier : declarationQualifiers(external)) {
                    if (!tree.text(qualifier.keyword()).equals("uniform")) continue;
                    edits.add(new GlslDeclarations.TextEdit(tree.offset(qualifier.keyword()), GlslDeclarations
                            .tailEnd(source, tree.offset(external.range().end()), WHITESPACE_TAIL), ""));
                    break;
                }
            }
        }
        return GlslDeclarations.apply(source, edits);
    }

    static String normalizeShadowTerrain(String source) {
        return normalizeTo460(source, "shadow");
    }

    static String removeModernTerrainInputs(
            String source,
            LegacyGlslConverter.TerrainVaryingLayout layout
    ) {
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        for (TerrainDeclarations.Declaration declaration
                : TerrainDeclarations.modern(source, TerrainDeclarations.MODERN_STORAGES)) {
            String name = declaration.name();
            String replacement = "";
            if (declaration.storage().equals("in")) {
                // The declaration is synthesized by the fixed preamble.
                if (modernTerrainInputExpression(declaration.type(), name).isBlank()) {
                    throw new IllegalArgumentException("modern terrain input is unsupported: " + name);
                }
            } else if (!layout.types().containsKey(name)) {
                throw new IllegalArgumentException("modern terrain output is not in the shared layout: " + name);
            } else {
                replacement = modernTerrainOutput(declaration, layout);
            }
            edits.add(new GlslDeclarations.TextEdit(declaration.start(), declaration.end(), replacement));
        }
        return GlslDeclarations.apply(source, edits);
    }

    /** An output at its shared-layout location with the layout's interpolation; integers are always flat. */
    private static String modernTerrainOutput(
            TerrainDeclarations.Declaration output, LegacyGlslConverter.TerrainVaryingLayout layout) {
        String type = output.type();
        StringBuilder result = new StringBuilder("layout(location = ")
                .append(layout.location(output.name())).append(") ");
        // Keep the authored interpolation so both stages agree (flat mat3 tbn).
        String interpolation = layout.qualifier(output.name());
        if (!interpolation.isEmpty()) {
            result.append(interpolation).append(' ');
        } else if (type.equals("int") || type.startsWith("ivec") || type.equals("uint") || type.startsWith("uvec")) {
            result.append("flat ");
        }
        return result.append("out ").append(type).append(' ').append(output.name()).append(';').toString();
    }

    static String removeShadowAttributes(String source, boolean shadowStage) {
        if (!shadowStage) return source;
        List<TerrainDeclarations.Declaration> attributes = TerrainDeclarations.shadowAttributes(source);
        for (TerrainDeclarations.Declaration attribute : attributes) {
            String type = attribute.type();
            boolean supported = switch (attribute.name()) {
                case "mc_Entity" -> type.equals("float") || type.equals("vec2") || type.equals("vec3")
                        || type.equals("vec4");
                case "mc_midTexCoord" -> type.equals("vec2") || type.equals("vec4");
                case "at_midBlock" -> type.equals("vec3") || type.equals("vec4")
                        || type.equals("ivec3") || type.equals("ivec4");
                default -> false;
            };
            if (!supported) {
                throw new IllegalArgumentException("unsupported shadow attribute: " + attribute.name());
            }
        }
        String result = TerrainDeclarations.without(source, attributes);
        if (namesAttribute(result)) {
            throw new IllegalArgumentException("unsupported shadow attribute declaration");
        }
        return result;
    }

    /** The legacy attributes the extended terrain format backs, with the types each may be declared as. */
    private static final Map<String, Set<String>> TERRAIN_ATTRIBUTE_TYPES = Map.of(
            "mc_Entity", Set.of("float", "vec2", "vec3", "vec4"),
            "mc_midTexCoord", Set.of("vec2", "vec4"),
            "at_tangent", Set.of("vec4"));

    /** Removes the small legacy attribute declarations backed by the extended terrain format. */
    static String removeTerrainAttributes(String source, boolean shadowStage) {
        if (shadowStage) {
            return source;
        }
        List<TerrainDeclarations.Declaration> attributes = TerrainDeclarations.keyword(source, "attribute");
        for (TerrainDeclarations.Declaration attribute : attributes) {
            Set<String> servedTypes = TERRAIN_ATTRIBUTE_TYPES.get(attribute.name());
            if (servedTypes == null) {
                throw new IllegalArgumentException("unsupported terrain attribute: " + attribute.name());
            }
            if (!servedTypes.contains(attribute.type())) {
                throw new IllegalArgumentException("unsupported " + attribute.name() + " type: " + attribute.type());
            }
        }
        String result = TerrainDeclarations.without(source, attributes);
        if (namesAttribute(result)) {
            throw new IllegalArgumentException("unsupported terrain attribute declaration");
        }
        return result;
    }

    static Map<String, String> modernTerrainInputReplacements(String source) {
        Map<String, String> replacements = new TreeMap<>();
        for (TerrainDeclarations.Declaration input : TerrainDeclarations.modern(source, Set.of("in"))) {
            String expression = modernTerrainInputExpression(input.type(), input.name());
            if (!expression.isBlank()) {
                replacements.put(input.name(), expression);
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
        replacements.putIfAbsent("gl_NormalMatrix", GlslVertexPreambles.normalMatrix("gbufferModelView"));
        replacements.putIfAbsent("gl_ProjectionMatrix", "(MVP * inverse(gbufferModelView))");
        replacements.putIfAbsent("gl_ModelViewProjectionMatrix", "MVP");
        for (TerrainDeclarations.Declaration attribute : TerrainDeclarations.keyword(source, "attribute")) {
            String type = attribute.type();
            String name = attribute.name();
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
        for (TerrainDeclarations.Declaration attribute
                : TerrainDeclarations.shadowAttributes(source == null ? "" : source)) {
            String type = attribute.type();
            String name = attribute.name();
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
        String raw = integralTerrainInputExpression(type, name);
        if (raw != null) {
            return raw;
        }
        TerrainInput input = switch (name) {
            case "Position", "vaPosition", "a_Position" -> new TerrainInput("chimeraVertexValue()", "vec4");
            case "Color", "vaColor", "a_Color" -> new TerrainInput("chimeraColorValue()", "vec4");
            case "UV0", "vaUV0", "a_TexCoord" -> new TerrainInput("chimeraTexCoord0Value()", "vec4");
            case "UV2", "vaUV2", "a_Light" -> new TerrainInput("chimeraTexCoord1Value()", "vec4");
            case "Normal", "vaNormal", "a_Normal" -> new TerrainInput("chimeraNormalValue()", "vec3");
            case "mc_Entity" -> new TerrainInput("chimeraMcEntityValue()", "vec2");
            case "mc_midTexCoord" -> new TerrainInput("chimeraMidTexCoordValue()", "vec2");
            case "at_midBlock" -> new TerrainInput("chimeraMidBlockValue()", "vec4");
            case "at_tangent" -> new TerrainInput("chimeraTangentValue()", "vec4");
            default -> null;
        };
        return input == null ? "" : adaptModernTerrainType(type, input.value(), input.baseType());
    }

    /** A host terrain input's value expression and the GLSL type that expression has. */
    private record TerrainInput(String value, String baseType) {}

    /**
     * Integer reads of the light, mid-texture and mid-block inputs, which have expressions of
     * their own; null for every other input and type.
     */
    private static String integralTerrainInputExpression(String type, String name) {
        return switch (name) {
            case "UV2", "vaUV2", "a_Light" -> type.equals("ivec2") ? "ivec2(chimeraTexCoord1Value().xy)" : null;
            case "mc_midTexCoord" -> type.equals("uvec2") ? "chimeraMidTexCoordRawValue()" : null;
            case "at_midBlock" -> switch (type) {
                case "ivec2" -> "chimeraMidBlockRawValue().xy";
                case "ivec3" -> "chimeraMidBlockRawValue().xyz";
                case "ivec4" -> "chimeraMidBlockRawValue()";
                default -> null;
            };
            default -> null;
        };
    }

    static String adaptModernTerrainType(String type, String value, String baseType) {
        if (type.equals(baseType)) {
            return value;
        }
        return switch (type) {
            case "float" -> value + ".x";
            case "int" -> "int(" + value + ".x)";
            case "uint" -> "uint(" + value + ".x)";
            case "vec2" -> baseType.equals("vec2") ? value : value + ".xy";
            case "vec3" -> switch (baseType) {
                case "vec2" -> "vec3(" + value + ", 0.0)";
                case "vec3" -> value;
                default -> value + ".xyz";
            };
            case "vec4" -> switch (baseType) {
                case "vec2" -> "vec4(" + value + ", 0.0, 1.0)";
                case "vec3" -> "vec4(" + value + ", 0.0)";
                default -> value;
            };
            case "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4" -> type + "(" + value + ")";
            default -> "";
        };
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
