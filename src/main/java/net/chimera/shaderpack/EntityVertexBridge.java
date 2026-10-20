package net.chimera.shaderpack;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.vertex.ChimeraVertexFormats;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * Converts entity, block, hand, crumbling and particle vertex stages onto the extended entity
 * vertex contract, and rewrites Iris's per-vertex entity identities (entityId, blockEntityId,
 * currentRenderedItemId).
 */
public final class EntityVertexBridge {
    private EntityVertexBridge() {}

    /** The storage keywords of a declaration the conversion must have consumed. */
    private static final Set<String> INTERFACE_STORAGES = Set.of("attribute", "varying", "in", "out");
    /** A leftover declaration's text: from its keyword through the rest of its line. */
    private static final Pattern REST_OF_LINE = Pattern.compile("\\s+.*$", Pattern.MULTILINE);
    private static final Pattern SAMPLER_TYPE = Pattern.compile("[iu]?sampler\\w*");

    /**
     * Iris EntityPatcher identities: each name reads one component of the
     * per-vertex EntityIds input (Iris iris_Entity), passed flat to fragments.
     */
    static final List<String> ENTITY_ID_NAMES = List.of("entityId", "blockEntityId", "currentRenderedItemId");

    static final String ENTITY_ID_VARYING = "chimeraEntityId";

    /**
     * A host value the entity vertex bridge forwards to a fragment that reads it: the overlay
     * coordinates entityColor fetches with (EntityOverlayColor), and the vertex colour's alpha
     * Iris's vertex-alpha test compares against (PackAlphaTestPlan).
     */
    record ForwardedVarying(String name, String type, String qualifier, String value) {
        static final List<ForwardedVarying> ALL = List.of(
                new ForwardedVarying(EntityOverlayColor.UV_VARYING, "ivec2", "flat ", "UV1"),
                new ForwardedVarying(PackAlphaTestPlan.VERTEX_ALPHA_VARYING, "float", "", "Color.a"));

        /** The forwarded varyings {@code fragment} reads, in a fixed order. */
        static List<ForwardedVarying> readBy(String fragment) {
            String stripped = stripComments(fragment == null ? "" : fragment);
            return ALL.stream().filter(varying -> LegacyGlslConverter.containsIdentifier(stripped, varying.name()))
                    .toList();
        }

        /** The varying's {@code in} or {@code out} declaration at its location in {@code layout}. */
        String declaration(LegacyGlslConverter.TerrainVaryingLayout layout, String direction) {
            return "layout(location = " + layout.location(name) + ") " + qualifier + direction + " " + type + " "
                    + name + ";\n";
        }
    }

    static String entityIdVarying(String name) {
        return switch (name) {
            case "entityId" -> ENTITY_ID_VARYING;
            case "blockEntityId" -> "chimeraBlockEntityId";
            default -> "chimeraRenderedItemId";
        };
    }

    static String entityIdComponent(String name) {
        return switch (name) {
            case "entityId" -> "x";
            case "blockEntityId" -> "y";
            default -> "z";
        };
    }

    /**
     * Converts the shadow program for the shadow pass's entity lane (Iris shadow_entities,
     * ShaderKey.SHADOW_ENTITIES_CUTOUT): the shadow bridge reading EXTENDED_ENTITY. The legacy
     * matrix built-ins name the draw's host blocks, as Iris's iris_ModelViewMat and iris_ProjMat
     * do, and the pass loads the light view and projection into them. entityId and
     * blockEntityId are per-vertex, as Iris's EntityPatcher makes them on the entity layout.
     * Reports the first concrete failure.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertShadowEntityVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean allowStorageBuffers
    ) {
        return TerrainVertexBridge.convertModernTerrainVertexOrThrow(source, sourceFile, fragmentSource,
                GlslVertexPreambles.ENTITY_SHADOW_VERTEX_PREAMBLE, interfacePlan, true, true, allowStorageBuffers);
    }

    /**
     * On the shadow pass's entity lane the legacy built-ins are the draw's own host matrices,
     * as Iris's iris_ModelViewMat and iris_ProjMat are; a render type's layering transform
     * stays in them. The camera's gbuffer matrices remain the pack uniforms.
     */
    static Map<String, String> entityShadowMatrixBuiltinReplacements() {
        return Map.of(
                "gl_ModelViewMatrix", "ModelViewMat",
                "gl_NormalMatrix", GlslVertexPreambles.normalMatrix("ModelViewMat"),
                "gl_ProjectionMatrix", "ProjMat",
                "gl_ModelViewProjectionMatrix", "(ProjMat * ModelViewMat)");
    }

    /** Converts the strict legacy entity vertex bridge onto EXTENDED_ENTITY. */
    public static LegacyGlslConverter.TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, null);
    }

    /** Converts an entity vertex with the locations selected by the shared stage matcher. */
    public static LegacyGlslConverter.TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts an entity vertex with storage blocks admitted by the plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertEntityVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE, allowStorageBuffers, List.of());
    }

    /**
     * Entity vertex conversion that reports the first concrete failure.
     * PackPlanBuilder records the reason alongside the generic bridge
     * deviation instead of collapsing every failure into one code.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertEntityVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertEntityVertexChecked(source, sourceFile, fragmentSource, sharedLocations,
                allowStorageBuffers, fragmentUniforms, null);
    }

    /** Entity vertex conversion whose referenced samplers take the program's shared bindings. */
    public static LegacyGlslConverter.TerrainVertexConversion convertEntityVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms,
            VertexSamplerPlan samplerPlan
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms, samplerPlan);
    }

    /** Converts the block-entity variant: block entities draw posed, on the entity inputs. */
    public static LegacyGlslConverter.TerrainVertexConversion convertBlockVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertBlockVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts a block vertex with storage blocks admitted by the plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertBlockVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE, allowStorageBuffers, List.of());
    }

    /** Block vertex conversion that reports the first concrete failure. */
    public static LegacyGlslConverter.TerrainVertexConversion convertBlockVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertBlockVertexChecked(source, sourceFile, fragmentSource, sharedLocations,
                allowStorageBuffers, fragmentUniforms, null);
    }

    /** Block vertex conversion whose referenced samplers take the program's shared bindings. */
    public static LegacyGlslConverter.TerrainVertexConversion convertBlockVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms,
            VertexSamplerPlan samplerPlan
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms, samplerPlan);
    }

    /** Hand models use the real entity inputs; held items/maps get layout variants at build time. */
    public static LegacyGlslConverter.TerrainVertexConversion convertHandVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertHandVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts a hand vertex with storage blocks admitted by the plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertHandVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers
    ) {
        return convertEntityVertex(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.HAND_VERTEX_PREAMBLE, allowStorageBuffers, List.of());
    }

    /** Hand vertex conversion that reports the first concrete failure. */
    public static LegacyGlslConverter.TerrainVertexConversion convertHandVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertHandVertexChecked(source, sourceFile, fragmentSource, sharedLocations,
                allowStorageBuffers, fragmentUniforms, null);
    }

    /** Hand vertex conversion whose referenced samplers take the program's shared bindings. */
    public static LegacyGlslConverter.TerrainVertexConversion convertHandVertexChecked(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms,
            VertexSamplerPlan samplerPlan
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.HAND_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms, samplerPlan);
    }

    /** Host damage meshes keep BLOCK, including its lightmap and normal offsets. */
    public static LegacyGlslConverter.TerrainVertexConversion convertCrumblingVertexChecked(
            String source, Path sourceFile, String fragmentSource,
            Map<String, Integer> sharedLocations, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertCrumblingVertexChecked(source, sourceFile, fragmentSource, sharedLocations,
                allowStorageBuffers, fragmentUniforms, null);
    }

    /** Crumbling vertex conversion whose referenced samplers take the program's shared bindings. */
    public static LegacyGlslConverter.TerrainVertexConversion convertCrumblingVertexChecked(
            String source, Path sourceFile, String fragmentSource,
            Map<String, Integer> sharedLocations, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms,
            VertexSamplerPlan samplerPlan
    ) {
        String liveInputs = stripComments(EntityDeclarations.removeEntityAttributes(
                LegacyGlslConverter.pruneUnreachableVertexFunctions(
                        LegacyGlslConverter.prepareSource(source, sourceFile, true))));
        if (LegacyGlslConverter.containsIdentifier(liveInputs, "mc_midTexCoord")
                || LegacyGlslConverter.containsIdentifier(liveInputs, "at_tangent")) {
            throw new IllegalArgumentException("crumbling BLOCK has no material mid-coordinate or tangent attribute");
        }
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.CRUMBLING_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms, samplerPlan);
    }

    static LegacyGlslConverter.TerrainVertexConversion convertEntityVertex(
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
                    vertexPreamble, allowStorageBuffers, fragmentUniforms, null);
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
    static LegacyGlslConverter.TerrainVertexConversion convertEntityVertexOrThrow(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations,
            String vertexPreamble,
            boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms,
            VertexSamplerPlan samplerPlan
    ) {
        String src = LegacyGlslConverter.prepareSource(source, sourceFile, true);
        src = LegacyGlslConverter.expandModernVaryingLists(src);
        src = LegacyGlslConverter.pruneUnreachableVertexFunctions(src);
        String stripped = stripComments(src);
        GlslVersion.requireSupported(stripped, "entity vertex", true);
        if (GlslDeclarations.main(GlslTree.parse(stripped)) == null
                || !stripped.matches("(?s).*\\bgl_Position\\b.*")) {
            throw new IllegalArgumentException("entity vertex requires main and gl_Position");
        }
        Map<String, String> entityIdTypes = entityIdTypes(stripped, fragmentSource);
        // Catalog uniforms, legacy matrix built-ins, and stage-level
        // flat/layout qualifiers in real entity sources are translated
        // below; reject only what no adapter path can serve.
        String beforeFeatures = EntityDeclarations.removeEntityIdDeclarations(stripped);
        EntityVertexFeatureCheck.rejectEntityVertexFeatures(
                LegacyGlslConverter.stripUnusableUniformDeclarations(beforeFeatures, fragmentUniforms),
                allowStorageBuffers, samplerPlan != null);

        EntityVaryingDeclarations vertexVaryings = parseEntityVaryingDeclarations(stripped, true);
        requireMatchingFragmentVaryings(vertexVaryings, fragmentSource);
        List<ForwardedVarying> forwarded = ForwardedVarying.readBy(fragmentSource);
        // The bridge's varyings follow the pack's: the entity identities, then what the fragment reads.
        Map<String, String> addedVaryings = new LinkedHashMap<>();
        entityIdTypes.forEach((name, type) -> addedVaryings.put(entityIdVarying(name), type));
        forwarded.forEach(varying -> addedVaryings.put(varying.name(), varying.type()));
        LegacyGlslConverter.TerrainVaryingLayout layout =
                entityVaryingLayout(vertexVaryings, sharedLocations, addedVaryings);

        String converted = rewriteEntityDeclarations(src, layout, fragmentUniforms);
        if (samplerPlan != null) {
            converted = bindVertexSamplers(converted, samplerPlan);
        }
        converted = GlslTokenRewriter.replaceIdentifiers(GlslVertexPreambles.renameHostNameCollisions(converted),
                entityInputReplacements(vertexPreamble, stripped, layout));
        converted = converted.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]",
                "TextureMat");
        // Iris VanillaTransformer/VanillaCoreTransformer: raw UV2 for both
        // light-coordinate aliases, normalized once by the legacy matrix.
        converted = LegacyGlslConverter.replaceLightmapTextureMatrices(converted);
        converted = converted.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                "chimeraEntityFtransform()");
        converted = injectEntityIdPrologues(converted, entityIdTypes.keySet());
        for (ForwardedVarying varying : forwarded) {
            converted = LegacyGlslConverter.injectMainPrologue(converted,
                    varying.name() + " = " + varying.value() + ";");
        }
        requireConsumedDeclarations(converted);
        String chimeraVaryings = entityIdOutputDeclarations(entityIdTypes.keySet(), layout);
        for (ForwardedVarying varying : forwarded) {
            chimeraVaryings += varying.declaration(layout, "out");
        }
        String cameraBlock = GlslVertexPreambles.entityCameraUniformBlock(converted, fragmentUniforms);
        converted = wrapProjectedMain(converted, vertexPreamble);
        return new LegacyGlslConverter.TerrainVertexConversion("#version 460\n" + vertexPreamble
                + chimeraVaryings + cameraBlock + converted,
                layout);
    }

    /** Every input the fragment reads must be a vertex output of the same type and interpolation. */
    private static void requireMatchingFragmentVaryings(EntityVaryingDeclarations vertexVaryings,
                                                        String fragmentSource) {
        String expandedFragment = LegacyGlslConverter.expandModernVaryingLists(
                fragmentSource == null ? "" : stripComments(fragmentSource));
        EntityVaryingDeclarations fragmentVaryings = parseEntityVaryingDeclarations(expandedFragment, false);
        for (Map.Entry<String, String> entry : fragmentVaryings.types().entrySet()) {
            String vertexType = vertexVaryings.types().get(entry.getKey());
            if (!entry.getValue().equals(vertexType)) {
                throw new IllegalArgumentException("entity varying mismatch: " + entry.getKey());
            }
            String vertexQualifier = vertexVaryings.qualifiers().getOrDefault(entry.getKey(), "");
            String fragmentQualifier = fragmentVaryings.qualifiers().getOrDefault(entry.getKey(), "");
            if (!vertexQualifier.equals(fragmentQualifier)) {
                throw new IllegalArgumentException(
                        "entity varying qualifier mismatch: " + entry.getKey());
            }
        }
    }

    /**
     * Locations for the shared varyings as the stage matcher chose them, then the other vertex
     * outputs in name order, then the varyings the bridge adds, in their given order.
     */
    private static LegacyGlslConverter.TerrainVaryingLayout entityVaryingLayout(
            EntityVaryingDeclarations vertexVaryings,
            Map<String, Integer> sharedLocations,
            Map<String, String> addedVaryings
    ) {
        Map<String, String> vertexTypes = new TreeMap<>(vertexVaryings.types());
        Map<String, Integer> locations = new TreeMap<>();
        if (sharedLocations != null) {
            locations.putAll(sharedLocations);
        }
        // Each varying takes as many locations as its type occupies (a mat3 takes three).
        int location = 0;
        for (Map.Entry<String, Integer> shared : locations.entrySet()) {
            location = Math.max(location,
                    shared.getValue() + vertexVaryings.slots().getOrDefault(shared.getKey(), 1));
        }
        for (String name : vertexTypes.keySet().stream().sorted().toList()) {
            if (!locations.containsKey(name)) {
                locations.put(name, location);
                location += vertexVaryings.slots().get(name);
            }
        }
        for (Map.Entry<String, String> added : addedVaryings.entrySet()) {
            locations.put(added.getKey(), location++);
            vertexTypes.put(added.getKey(), added.getValue());
        }
        return new LegacyGlslConverter.TerrainVaryingLayout(vertexTypes, locations, vertexVaryings.qualifiers());
    }

    /**
     * Drops what the entity contract declares itself (the version line, entity ids and attributes,
     * served or dead uniforms, unread samplers) and gives the varyings their layout locations.
     */
    private static String rewriteEntityDeclarations(
            String src,
            LegacyGlslConverter.TerrainVaryingLayout layout,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        String converted = LegacyGlslConverter.VERSION_LINE.matcher(src).replaceAll("");
        converted = EntityDeclarations.removeEntityIdDeclarations(converted);
        converted = EntityDeclarations.removeEntityAttributes(converted);
        converted = replaceEntityVaryings(converted, layout, "out", true);
        // Served and dead uniform declarations leave before identifier
        // replacement: the replacement would otherwise rewrite names
        // inside their own declarations into garbage.
        converted = LegacyGlslConverter.stripUnusableUniformDeclarations(converted, fragmentUniforms);
        // Unused sampler declarations would fail Vulkan compilation:
        // opaque uniforms require layout(binding) even when unread.
        // Referenced samplers already failed loudly in rejection.
        return LegacyGlslConverter.stripUnreferencedSamplerDeclarations(converted);
    }

    /** The token replacements for the legacy inputs and matrices, as the hand, glint and sky draws adjust them. */
    private static Map<String, String> entityInputReplacements(
            String vertexPreamble,
            String stripped,
            LegacyGlslConverter.TerrainVaryingLayout layout
    ) {
        // Legacy MVP is a mat4; substituting vec4 ftransform() silently
        // turns authored MVP * gl_Vertex into component-wise multiplication.
        Map<String, String> inputs = Map.ofEntries(
                Map.entry("gl_Vertex", "chimeraEntityVertexValue()"),
                Map.entry("gl_Color", GlslVertexPreambles.nativeLayoutPreamble(vertexPreamble)
                        ? "(Color * ColorModulator)" : "Color"),
                Map.entry("gl_MultiTexCoord0", "vec4(UV0, 0.0, 1.0)"),
                Map.entry("gl_MultiTexCoord1", "vec4(vec2(UV2), 0.0, 1.0)"),
                Map.entry("gl_MultiTexCoord2", "vec4(vec2(UV2), 0.0, 1.0)"),
                Map.entry("gl_Normal", "Normal.xyz"),
                Map.entry("gl_NormalMatrix", GlslVertexPreambles.normalMatrix("ModelViewMat")),
                Map.entry("gl_ModelViewMatrix", "ModelViewMat"),
                Map.entry("gl_ModelViewProjectionMatrix", "(ProjMat * ModelViewMat)"),
                Map.entry("gl_ProjectionMatrix", "ProjMat"),
                Map.entry("gbufferModelView", "ModelViewMat"),
                Map.entry("gbufferModelViewInverse", "chimeraEntityInverseModelView()"),
                Map.entry("mc_midTexCoord", EntityDeclarations.midTexCoordValue(stripped)),
                Map.entry("at_tangent", "chimeraEntityTangentValue()"),
                Map.entry("Tangent", "chimeraEntityTangentValue()"),
                // Vulkan's index counts from the draw's first vertex, as GL's does.
                Map.entry("gl_VertexID", "gl_VertexIndex"),
                Map.entry("entityId", entityIdExpression(layout, "entityId")),
                Map.entry("blockEntityId", entityIdExpression(layout, "blockEntityId")),
                Map.entry("currentRenderedItemId", entityIdExpression(layout, "currentRenderedItemId")));
        // ProjMat and gl_ProjectionMatrix belong to the host raster path.
        // Iris gbufferProjection uniforms stay in the shared pack UBO so
        // they use the legacy clip-depth convention instead.
        if (vertexPreamble == GlslVertexPreambles.HAND_VERTEX_PREAMBLE) {
            inputs = new HashMap<>(inputs);
            inputs.put("gl_ProjectionMatrix", "chimeraHandProjection()");
            inputs.put("gl_ModelViewProjectionMatrix", "(chimeraHandProjection() * ModelViewMat)");
            inputs.remove("gbufferModelView");
            inputs.remove("gbufferModelViewInverse");
        }
        if (vertexPreamble == GlslVertexPreambles.GLINT_VERTEX_PREAMBLE) {
            // Glint also draws in the hand, where ModelViewMat is the hand pose: the
            // world matrices stay the pack's own uniforms, as in Iris.
            inputs = new HashMap<>(inputs);
            inputs.remove("gbufferModelView");
            inputs.remove("gbufferModelViewInverse");
        }
        if (GlslVertexPreambles.isSkyPreamble(vertexPreamble)) {
            inputs = new HashMap<>(inputs);
            inputs.put("gl_ProjectionMatrix", "chimeraSkyProjection()");
            inputs.put("gl_ModelViewProjectionMatrix", "(chimeraSkyProjection() * ModelViewMat)");
            // Celestial host transforms rotate the sun/moon mesh. Pack world
            // matrices must remain the frame's world matrices, as in Iris.
            inputs.remove("gbufferModelView");
            inputs.remove("gbufferModelViewInverse");
        }
        return inputs;
    }

    /** Fails on an attribute, varying or interface declaration the conversion did not consume. */
    private static void requireConsumedDeclarations(String converted) {
        String stripped = stripComments(converted);
        GlslTree tree = GlslTree.parse(stripped);
        for (GlslTree.External external : tree.externals()) {
            List<GlslTree.Qualifier> qualifiers = external instanceof GlslTree.Declaration declaration
                    ? declaration.qualifiers()
                    : external instanceof GlslTree.InterfaceBlock block ? block.qualifiers() : List.of();
            if (qualifiers.isEmpty()) continue;
            String storage = tree.text(qualifiers.get(0).keyword());
            int start = tree.offset(qualifiers.get(0).keyword());
            int end = GlslDeclarations.tailEnd(stripped, start + storage.length(), REST_OF_LINE);
            if (INTERFACE_STORAGES.contains(storage) && end >= 0
                    && GlslDeclarations.lineStartBefore(stripped, start) >= 0) {
                String line = stripped.substring(start, end).trim();
                throw new IllegalArgumentException("entity declaration was not consumed: "
                        + line.substring(0, Math.min(80, line.length())));
            }
        }
    }

    /**
     * The hand and sky draws run the pack's main from a wrapper that then maps the legacy clip
     * depth onto Vulkan's zero-to-one range.
     */
    private static String wrapProjectedMain(String converted, String vertexPreamble) {
        String wrapped = converted;
        if (vertexPreamble == GlslVertexPreambles.HAND_VERTEX_PREAMBLE) {
            wrapped = renamedMain(wrapped, "chimeraHandMain");
            wrapped += "\nvoid main() { chimeraHandMain();"
                    + " gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w); }\n";
        }
        if (GlslVertexPreambles.isSkyPreamble(vertexPreamble)) {
            wrapped = renamedMain(wrapped, "chimeraSkyMain");
            wrapped += "\nvoid main() { chimeraSkyMain(); gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);"
                    + " }\n";
        }
        return wrapped;
    }

    /** The entity identity types either stage declares, in {@code ENTITY_ID_NAMES} order. */
    static Map<String, String> entityIdTypes(String strippedVertex, String fragmentSource) {
        Map<String, String> types = new LinkedHashMap<>();
        for (String name : ENTITY_ID_NAMES) {
            String type = entityIdentifierType(name, strippedVertex, fragmentSource);
            if (type != null) types.put(name, type);
        }
        return types;
    }

    /** Copies each named identity from the EntityIds input into its flat varying at the start of main. */
    static String injectEntityIdPrologues(String source, Collection<String> names) {
        String result = source;
        for (String name : names) {
            result = LegacyGlslConverter.injectMainPrologue(result,
                    entityIdVarying(name) + " = EntityIds." + entityIdComponent(name) + ";");
        }
        return result;
    }

    /** The flat output declaration of each named identity's varying, at its layout location. */
    static String entityIdOutputDeclarations(Collection<String> names,
                                             LegacyGlslConverter.TerrainVaryingLayout layout) {
        StringBuilder declarations = new StringBuilder();
        for (String name : names) {
            declarations.append("layout(location = " + layout.location(entityIdVarying(name))
                    + ") flat out uint " + entityIdVarying(name) + ";\n");
        }
        return declarations.toString();
    }

    /** Each named identity read through its flat varying. */
    static Map<String, String> entityIdReplacements(Collection<String> names,
                                                    LegacyGlslConverter.TerrainVaryingLayout layout) {
        Map<String, String> replacements = new LinkedHashMap<>();
        for (String name : names) {
            replacements.put(name, entityIdExpression(layout, name));
        }
        return replacements;
    }

    /** Converts the legacy particle vertex contract onto DefaultVertexFormat.PARTICLE. */
    public static LegacyGlslConverter.TerrainVertexConversion convertParticleVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            Map<String, Integer> sharedLocations
    ) {
        return convertParticleVertex(source, sourceFile, fragmentSource, sharedLocations, false);
    }

    /** Converts a particle vertex with storage blocks admitted by the plan. */
    public static LegacyGlslConverter.TerrainVertexConversion convertParticleVertex(
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
    public static LegacyGlslConverter.TerrainVertexConversion convertParticleVertexChecked(
            String source, Path sourceFile, String fragmentSource,
            Map<String, Integer> sharedLocations, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        return convertParticleVertexChecked(source, sourceFile, fragmentSource, sharedLocations,
                allowStorageBuffers, fragmentUniforms, null);
    }

    /**
     * Particle and weather vertex conversion whose referenced samplers take the shared bindings.
     * DefaultVertexFormat.PARTICLE has no mid-coordinate or tangent; the particle preamble serves
     * them as it does for the other host layouts (Photon's gbuffers_textured, which its particles
     * fall back to, reads both).
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertParticleVertexChecked(
            String source, Path sourceFile, String fragmentSource,
            Map<String, Integer> sharedLocations, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms,
            VertexSamplerPlan samplerPlan
    ) {
        return convertEntityVertexOrThrow(source, sourceFile, fragmentSource, sharedLocations,
                GlslVertexPreambles.PARTICLE_VERTEX_PREAMBLE, allowStorageBuffers, fragmentUniforms, samplerPlan);
    }

    /** Static probe helper for the strict entity vertex bridge. */
    public static boolean supportsEntityVertex(String source, String fragmentSource) {
        return convertEntityVertex(source, null, fragmentSource) != null;
    }

    /** Static probe helper for the particle-layout hand bridge. */
    public static boolean supportsHandVertex(String source, String fragmentSource) {
        return convertHandVertex(source, null, fragmentSource, null) != null;
    }

    static String replaceEntityVaryings(
            String source,
            LegacyGlslConverter.TerrainVaryingLayout layout,
            String direction,
            boolean vertexStage
    ) {
        if (layout == null) {
            throw new IllegalArgumentException("entity varying layout is missing");
        }
        return GlslInterfaceScanner.rewrite(source, vertexStage, declaration -> {
            if (vertexStage ? !declaration.output() : !declaration.input()) {
                return null;
            }
            // An unread declaration is dropped before the layout is consulted: GL links it as
            // dead whether or not the other stage declares it.
            if (!declaration.referenced()) {
                return "";
            }
            String name = declaration.name();
            if (!declaration.typeWithArray().equals(layout.types().get(name))) {
                throw new IllegalArgumentException("entity varying is not in the shared layout: " + name);
            }
            String qualifier = layout.qualifier(name);
            return "layout(location = " + layout.location(name) + ") " + direction + " "
                    + (qualifier.isEmpty() ? "" : qualifier + " ")
                    + declaration.type() + " " + declaration.declarator() + ";";
        });
    }

    static String entityIdentifierType(String name, String vertexSource, String fragmentSource) {
        String vertex = vertexSource == null ? "" : vertexSource;
        String fragment = fragmentSource == null ? "" : stripComments(fragmentSource);
        String declared = EntityDeclarations.declaredEntityIdType(vertex, name);
        if (declared == null) declared = EntityDeclarations.declaredEntityIdType(fragment, name);
        if (declared != null) return declared;
        if (LegacyGlslConverter.containsIdentifier(vertex, name)
                || LegacyGlslConverter.containsIdentifier(fragment, name)) {
            return "int";
        }
        return null;
    }

    static String entityIdExpression(LegacyGlslConverter.TerrainVaryingLayout layout, String name) {
        String varying = entityIdVarying(name);
        if (layout != null && layout.locations().containsKey(varying)) {
            return "float".equals(layout.types().get(varying))
                    ? "float(" + varying + ")"
                    : "int(" + varying + ")";
        }
        return "float(EntityIds." + entityIdComponent(name) + ")";
    }

    static String replaceEntityIdReferences(
            String source,
            LegacyGlslConverter.TerrainVaryingLayout layout
    ) {
        if (layout == null) return source;
        Map<String, String> replacements = new HashMap<>();
        for (String name : ENTITY_ID_NAMES) {
            if (layout.locations().containsKey(entityIdVarying(name))) {
                replacements.put(name, entityIdExpression(layout, name));
            }
        }
        return replacements.isEmpty() ? source : GlslTokenRewriter.replaceIdentifiers(source, replacements);
    }

    /**
     * Varying types (with any array suffix), their authored interpolation qualifiers (or empty),
     * and the locations each occupies, from the shared typed interface scanner: integral, matrix,
     * array and struct varyings keep their GLSL meaning and width.
     */
    record EntityVaryingDeclarations(Map<String, String> types, Map<String, String> qualifiers,
                                             Map<String, Integer> slots) {}

    static EntityVaryingDeclarations parseEntityVaryingDeclarations(
            String source, boolean vertexStage) {
        Map<String, String> result = new TreeMap<>();
        Map<String, String> qualifiers = new TreeMap<>();
        Map<String, Integer> slots = new TreeMap<>();
        GlslInterfaceScanner.StageInterface stage = GlslInterfaceScanner.scan(source == null ? "" : source,
                vertexStage);
        if (!stage.deviations().isEmpty()) {
            throw new IllegalArgumentException("entity varying declarations unsupported: " + stage.deviations());
        }
        for (GlslInterfaceScanner.Declaration declaration : vertexStage ? stage.outputs() : stage.inputs()) {
            if (!vertexStage && !declaration.referenced()) {
                continue;
            }
            result.put(declaration.name(), declaration.typeWithArray());
            qualifiers.put(declaration.name(), declaration.qualifier() == null ? "" : declaration.qualifier());
            slots.put(declaration.name(), declaration.slots());
        }
        return new EntityVaryingDeclarations(result, qualifiers, slots);
    }

    /**
     * The program's samplers as the vertex stage sees them: the stage projection and the
     * program's ordered sampler slots, which fix each sampler's shared descriptor binding.
     */
    public record VertexSamplerPlan(UniformRegistry.ProgramInterface vertexInterface, int[] slots) {}

    /**
     * Declares the vertex stage's samplers at the bindings the fragment stage uses for
     * them. Iris binds the same textures to every stage of a program; Bliss reads its
     * light colours from colortex4 in particle, weather and emissive vertex shaders.
     */
    static String bindVertexSamplers(String source, VertexSamplerPlan plan) {
        UniformRegistry.ProgramInterface vertexInterface = plan.vertexInterface();
        Map<String, String> samplerTypes = new TreeMap<>();
        for (UniformRegistry.SamplerBinding sampler : vertexInterface.samplers()) {
            samplerTypes.put(sampler.name(), sampler.glslType());
        }
        // Same order as the fragment stage: rename an OptiFine 'texture' sampler before
        // the texture-call rewrite turns texture2D into texture.
        String converted = GlslTokenRewriter.renameSamplerIdentifier(source, "texture", "chimeraTexture");
        converted = LegacyGlslConverter.convertTextureCalls(converted);
        converted = GlslTokenRewriter.rewriteShadowCalls(converted, samplerTypes);
        for (UniformRegistry.SamplerBinding sampler : vertexInterface.samplers()) {
            String name = sampler.name().equals("texture") ? "chimeraTexture" : sampler.name();
            if (!FragmentConverter.hasSamplerDeclaration(converted, name)) continue;
            int binding = FragmentConverter.geometrySamplerBinding(sampler.name(), plan.slots(), vertexInterface);
            if (binding < 0) {
                throw new IllegalArgumentException("unsupported entity vertex sampler: " + sampler.name());
            }
            converted = FragmentConverter.rewriteSamplerDeclaration(converted, name, binding);
        }
        String unbound = unboundSampler(converted);
        if (unbound != null) {
            throw new IllegalArgumentException("unsupported entity vertex sampler: " + unbound);
        }
        return converted;
    }

    /** The first sampler a line-starting {@code uniform samplerX name} declares, without a binding; null if none. */
    private static String unboundSampler(String source) {
        GlslTree tree = GlslTree.parse(source);
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            int start = tree.offset(variable.declaration().range().start());
            if (variable.qualifiers().equals(List.of("uniform")) && SAMPLER_TYPE.matcher(variable.type()).matches()
                    && GlslDeclarations.lineStartBefore(source, start) >= 0) {
                return variable.name();
            }
        }
        return null;
    }

    /** The source with its main renamed {@code name}; unchanged without one. */
    private static String renamedMain(String source, String name) {
        String renamed = GlslDeclarations.renameMain(source, name);
        return renamed == null ? source : renamed;
    }

    /** Same immutable hand program, adapted only to the host inputs of this draw. */
    public static String handVertexForFormat(String converted, VertexFormat host) {
        return layoutVertexForFormat(converted, host);
    }

    /**
     * Same immutable entity, block or hand program, adapted only to the inputs of
     * the extended format its native host layout uploads as: held maps, item quads,
     * world or sign text and moving blocks keep their own prefixes, as in Iris.
     */
    public static String layoutVertexForFormat(String converted, VertexFormat host) {
        if (host == DefaultVertexFormat.NEW_ENTITY) return converted;
        String contract = converted == null ? null
                : converted.contains(GlslVertexPreambles.HAND_VERTEX_PREAMBLE)
                        ? GlslVertexPreambles.HAND_VERTEX_PREAMBLE
                : converted.contains(GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE)
                        ? GlslVertexPreambles.ENTITY_VERTEX_PREAMBLE : null;
        if (contract == null) {
            throw new IllegalArgumentException("program lacks the entity input contract");
        }
        var extended = ChimeraVertexFormats.extendedLayout(host);
        if (extended == null) {
            throw new IllegalArgumentException("unsupported entity-family host format");
        }
        // Input locations follow the uploaded format. Iris VanillaTransformer
        // supplies +Z for formats without a normal, zero for a missing lightmap.
        String preamble = contract;
        var names = extended.getElementAttributeNames();
        String[] types = {"vec3", "vec4", "vec2", "ivec2", "ivec2", "vec3"};
        String[] attributes = {"Position", "Color", "UV0", "UV1", "UV2", "Normal"};
        String[] defaults = {null, "vec4(1.0)", null, "ivec2(0)", "ivec2(0)", "vec3(0.0, 0.0, 1.0)"};
        for (int i = 0; i < attributes.length; i++) {
            int location = names.indexOf(attributes[i]);
            String declaration = location < 0
                    ? "const " + types[i] + " " + attributes[i] + " = " + defaults[i] + ";"
                    : "layout(location = " + location + ") in " + types[i] + " " + attributes[i] + ";";
            preamble = preamble.replace("layout(location = " + i + ") in " + types[i] + " " + attributes[i] + ";",
                    declaration);
        }
        preamble = preamble.replace("layout(location = 6) in uvec4 EntityIds;",
                        "layout(location = " + names.indexOf("EntityIds") + ") in uvec4 EntityIds;")
                .replace("layout(location = 7) in vec2 MidTexCoord;",
                        "layout(location = " + names.indexOf("MidTexCoord") + ") in vec2 MidTexCoord;")
                .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                        "layout(location = " + names.indexOf("Tangent") + ") in vec4 ChimeraPackedTangent;");
        String adapted = converted.replace(contract, preamble);
        return host == DefaultVertexFormat.BLOCK ? movingBlockEntity(adapted) : adapted;
    }

    /**
     * Iris writes mc_Entity as (-1, -1) for a block drawn outside a chunk mesh, no block having
     * begun in its builder; the entity layout reads the unbound default instead. A gbuffers
     * program declares it as a constant, a shadow program reads it through chimeraMcEntityValue.
     */
    static String movingBlockEntity(String source) {
        source = source.replaceFirst("(vec2 chimeraMcEntityValue\\(\\) \\{\\s*return )vec2\\(0\\.0\\);",
                "$1vec2(-1.0);");
        return EntityDeclarations.withEntityConstants(source, type -> switch (type) {
            case "float" -> "-1.0";
            case "vec2" -> "vec2(-1.0)";
            case "vec3" -> "vec3(-1.0, -1.0, 0.0)";
            default -> "vec4(-1.0, -1.0, 0.0, 1.0)";
        });
    }

    /**
     * The program's fragment for a pipeline on {@code format}: a layout without UV1 (text, a
     * held map's background, particles in the hand) has no overlay, so entityColor is zero
     * there, as under Iris.
     */
    public static String fragmentForFormat(String converted, VertexFormat format) {
        return format == null || format.getElementAttributeNames().contains("UV1")
                ? converted : EntityOverlayColor.withoutOverlay(converted);
    }

    /**
     * The converted glint program for first-person glint. Iris draws held glint with the
     * same ShaderKey.GLINT program under the hand projection; here it takes the hand
     * family's legacy projection and clip-depth remap, so its depth is computed exactly
     * as the held item's and the EQUAL depth test meets it.
     */
    public static String handGlintVertex(String converted) {
        int start = converted == null ? -1 : converted.indexOf(GlslVertexPreambles.GLINT_VERTEX_PREAMBLE);
        if (start < 0) throw new IllegalArgumentException("not a converted glint vertex");
        String body = converted.substring(start + GlslVertexPreambles.GLINT_VERTEX_PREAMBLE.length());
        body = GlslTokenRewriter.replaceIdentifiers(body, Map.of("ProjMat", "chimeraHandProjection()"));
        body = renamedMain(body, "chimeraHandMain");
        return converted.substring(0, start) + GlslVertexPreambles.GLINT_HAND_VERTEX_PREAMBLE + body
                + "\nvoid main() { chimeraHandMain(); gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w); }\n";
    }
}
