package net.chimera.shaderpack;

import net.chimera.shaderpack.WorldBetweenStages.PassedOn;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.chimera.shaderpack.WorldBetweenStages.arraySuffix;
import static net.chimera.shaderpack.WorldBetweenStages.qualified;

/**
 * A pack program's geometry stage (.gsh), which Iris runs between the vertex and fragment stages.
 * Its per-vertex inputs pair with the vertex stage's outputs and its outputs with the fragment
 * stage's inputs, each pair by name at one shared location (GlslInterfaceScanner.Stage.GEOMETRY).
 * A terrain, water or hand program's stage is converted as {@link #convertWorld} says; on a shadow lane
 * the stage reads the program's uniforms through the shadow block the other stages declare, binds
 * its samplers as they do, and converts GL's clip depth for each vertex it emits, as the vertex
 * stage does when nothing follows it (ShadowStageBuiltins). On a lane drawn with the draw's own
 * matrices (the entity casters and the host-layout keys) it also passes on the entity identities
 * the vertex stage reads from EntityIds, each from the primitive's first vertex, as Iris's
 * EntityPatcher passes iris_entityInfo through.
 */
final class GeometryStageBridge {
    private GeometryStageBridge() {}

    /** The suffix of a passed-on identity's output; its input keeps the vertex stage's name. */
    private static final String PASSED_ON_SUFFIX = "GS";
    /**
     * The legacy matrices of the entity and host-layout shadow lanes: the draw's own, which the
     * host serves to the vertex stage alone (EntityVertexBridge.entityShadowMatrixBuiltinReplacements).
     */
    private static final Set<String> DRAW_MATRICES = EntityVertexBridge.entityShadowMatrixBuiltinReplacements()
            .keySet();
    private static final Pattern DRAW_TEXTURE_MATRIX = Pattern.compile("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]");
    /** The stage's input primitive declaration, {@code layout(triangles) in;}. */
    private static final Pattern INPUT_PRIMITIVE = Pattern.compile("\\blayout\\s*\\(([^)]*)\\)\\s*in\\s*;");

    /** The converted stage, and the locations of its outputs, which the fragment stage's inputs take. */
    record Conversion(String source, LegacyGlslConverter.TerrainVaryingLayout outputLayout) {}

    /**
     * The stage as the vertex stage's consumer: each per-vertex input declared as the one vertex's
     * value it reads, the rest unchanged, so the vertex bridge pairs with it as with a fragment
     * stage. It also names each entity identity that only {@code fragment} reads, which the stage
     * passes on.
     */
    static String consumerView(String geometry, String fragment) {
        String view = GlslInterfaceScanner.rewrite(geometry, GlslInterfaceScanner.Stage.GEOMETRY, declaration ->
                declaration.perVertex() ? qualified(declaration.qualifier()) + "in " + declaration.type() + " "
                        + declaration.name() + arraySuffix(declaration) + ";\n" : null);
        String stripped = GlslLexer.stripComments(view);
        StringBuilder passedOn = new StringBuilder();
        for (String name : EntityVertexBridge.ENTITY_ID_NAMES) {
            String type = EntityVertexBridge.entityIdentifierType(name, "", fragment);
            if (type != null && EntityVertexBridge.entityIdentifierType(name, stripped, null) == null) {
                passedOn.append("uniform ").append(type).append(' ').append(name).append(";\n");
            }
        }
        return view + passedOn;
    }

    /** The stage as the fragment stage's producer: its outputs alone. */
    static String producerView(String geometry) {
        return GlslInterfaceScanner.rewrite(geometry, GlslInterfaceScanner.Stage.GEOMETRY,
                declaration -> declaration.input() ? "" : null);
    }

    /**
     * Converts a shadow program's geometry stage. {@code inputLayout} holds the vertex stage's
     * output locations; the fragment stage's inputs decide the output layout. On a lane drawn with
     * the draw's own matrices ({@code drawMatrices}) the entity identities in {@code inputLayout}
     * pass on to the fragment stage, and the stage reads them as the first vertex's.
     *
     * @throws IllegalArgumentException when the stage reads an input no vertex output feeds, an
     *         output the layout cannot place, a storage buffer the plan did not admit, an
     *         unplanned value uniform, or a draw matrix only the vertex stage is served
     */
    static Conversion convertShadow(String geometry, LegacyGlslConverter.TerrainVaryingLayout inputLayout,
                                    String fragment, UniformRegistry.ProgramInterface interfacePlan,
                                    boolean allowStorageBuffers, boolean drawMatrices) {
        String source = LegacyGlslConverter.expandModernVaryingLists(TerrainVertexBridge.normalizeShadowTerrain(
                geometry));
        Map<String, String> identities = passedOnIdentities(inputLayout);
        LegacyGlslConverter.TerrainVaryingLayout outputLayout = TerrainVertexBridge.modernVaryingLayout(
                producerView(source), LegacyGlslConverter.expandModernVaryingLists(
                        TerrainVertexBridge.normalizedFragment(fragment, true)), identities);
        String converted = TerrainVertexBridge.withoutUnreachableFunctions(
                LegacyGlslConverter.withoutVersionLines(source));
        converted = interfacePlan == null ? TerrainVertexBridge.stripShadowUniformDeclarations(converted)
                : UniformRegistry.removeUniformDeclarations(converted, interfacePlan);
        if (drawMatrices) converted = EntityDeclarations.removeEntityIdDeclarations(converted, interfacePlan);
        requireSupported(converted, allowStorageBuffers);
        if (drawMatrices) requireServedMatrices(converted);
        converted = located(converted, inputLayout, outputLayout);
        converted = GlslScopedReferences.replace(converted, firstVertexIdentities(identities.keySet(), inputLayout));
        if (!drawMatrices) {
            converted = GlslTokenRewriter.replaceIdentifiers(converted, ShadowStageBuiltins.matrixReplacements());
        }
        converted = ShadowStageBuiltins.legacyBuiltins(converted);
        converted = TerrainVertexBridge.rewriteModernTerrainSamplers(converted, interfacePlan, true);
        converted = LegacyGlslConverter.convertTextureCalls(converted);
        converted = TerrainVertexBridge.qualifyPlannedShadowUniforms(converted, interfacePlan);
        converted = ShadowStageBuiltins.emitClipRange(converted, passOnStatements(identities.keySet()));
        return new Conversion("#version 460\n" + FragmentConverter.SHADOW_RENDER_STAGE_DEFINES
                + TerrainVertexBridge.modernUniformBlock(interfacePlan, true)
                + identityDeclarations(identities.keySet(), inputLayout, outputLayout) + converted, outputLayout);
    }

    /**
     * Converts a terrain, water or hand program's geometry stage (Spring's gbuffers_terrain and
     * gbuffers_hand, which pass each triangle through). {@code inputLayout} holds the vertex stage's
     * output locations; the fragment stage's inputs decide the output layout. The vertex stage hands
     * it GL's clip position, as under Iris, and the stage reads the legacy matrices, its samplers and
     * the pack's uniforms as the program's tessellation stages do (WorldBetweenStages): a terrain
     * stage the host's matrices in GL's clip range, a {@code hand} stage its draw's own and the entity
     * family's block. It converts the clip depth of each vertex it emits. A hand's stage passes on the
     * values the entity vertex bridge adds for the fragment stage (WorldBetweenStages.PassedOn), each
     * from the primitive's first vertex, as Iris's EntityPatcher passes iris_entityInfo[0] through.
     *
     * @throws IllegalArgumentException as {@link #convertShadow}, but for the draw matrices
     */
    static Conversion convertWorld(String geometry, LegacyGlslConverter.TerrainVaryingLayout inputLayout,
                                   String fragment, UniformRegistry.ProgramInterface interfacePlan,
                                   boolean allowStorageBuffers, boolean hand) {
        String source = LegacyGlslConverter.expandModernVaryingLists(TerrainVertexBridge.normalizeModernTerrain(
                geometry));
        PassedOn passedOn = hand ? PassedOn.of(inputLayout) : PassedOn.NONE;
        LegacyGlslConverter.TerrainVaryingLayout outputLayout = passedOn.outputLayout(producerView(source), fragment);
        String converted = TerrainVertexBridge.withoutUnreachableFunctions(
                LegacyGlslConverter.withoutVersionLines(source));
        converted = interfacePlan == null ? TerrainVertexBridge.stripShadowUniformDeclarations(converted)
                : UniformRegistry.removeUniformDeclarations(converted, interfacePlan);
        requireSupported(converted, allowStorageBuffers);
        converted = located(converted, inputLayout, outputLayout);
        converted = WorldBetweenStages.builtins(converted, interfacePlan, hand);
        converted = ShadowStageBuiltins.emitClipRange(converted, passedOn.geometryStatements(PASSED_ON_SUFFIX));
        if (GlslTokenRewriter.containsIdentifier(converted, "chimeraGlClip")) {
            converted = WorldClipRange.GL.withHelpers(converted);
        }
        return new Conversion(WorldBetweenStages.preamble(converted, interfacePlan, hand)
                + passedOn.geometryDeclarations(inputLayout, outputLayout, PASSED_ON_SUFFIX) + converted, outputLayout);
    }

    /**
     * A main, triangles in, no storage buffer the plan did not admit, and no value uniform the
     * plan left out. Every shadow lane draws triangles (VulkanMod draws LINES, quads and strips as
     * triangle lists); GL fails the draw of a stage that takes another primitive, Vulkan leaves it
     * undefined.
     */
    private static void requireSupported(String source, boolean allowStorageBuffers) {
        String stripped = GlslLexer.stripComments(source);
        GlslTree tree = GlslTree.parse(stripped);
        if (GlslDeclarations.main(tree) == null) {
            throw new IllegalArgumentException("geometry stage main is missing");
        }
        Matcher primitive = INPUT_PRIMITIVE.matcher(stripped);
        if (primitive.find() && !List.of(primitive.group(1).split("\\s*,\\s*")).contains("triangles")) {
            throw new IllegalArgumentException("geometry stage input primitive is not triangles: "
                    + primitive.group(1).trim());
        }
        if (!allowStorageBuffers && GlslTokenRewriter.containsIdentifier(stripped, "buffer")) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        String uniform = TerrainVertexBridge.valueUniform(tree);
        if (uniform != null) {
            throw new IllegalArgumentException("geometry stage value uniform is unplanned: " + uniform);
        }
    }

    /** No legacy matrix of the draw, which the host serves to the vertex stage alone. */
    private static void requireServedMatrices(String source) {
        String stripped = GlslLexer.stripComments(source);
        String matrix = GlslTokenRewriter.firstIdentifier(stripped, DRAW_MATRICES::contains);
        if (matrix == null && DRAW_TEXTURE_MATRIX.matcher(stripped).find()) matrix = "gl_TextureMatrix[0]";
        if (matrix != null) {
            throw new IllegalArgumentException("geometry stage reads a draw matrix only the vertex stage is served: "
                    + matrix);
        }
    }

    /** The entity identities the vertex stage passes on, each with the type the stages read it as. */
    private static Map<String, String> passedOnIdentities(LegacyGlslConverter.TerrainVaryingLayout inputs) {
        Map<String, String> identities = new LinkedHashMap<>();
        for (String name : EntityVertexBridge.ENTITY_ID_NAMES) {
            String varying = EntityVertexBridge.entityIdVarying(name);
            if (inputs.locations().containsKey(varying)) identities.put(name, inputs.types().get(varying));
        }
        return identities;
    }

    /** Each identity the stage reads, as the first vertex's value: Iris's iris_entityInfo[0]. */
    private static Map<String, String> firstVertexIdentities(Collection<String> names,
                                                             LegacyGlslConverter.TerrainVaryingLayout inputs) {
        Map<String, String> reads = new LinkedHashMap<>();
        for (String name : names) reads.put(name, EntityVertexBridge.entityIdExpression(inputs, name, "[0]"));
        return reads;
    }

    /** Each identity's output written from the first vertex's input. */
    private static List<String> passOnStatements(Collection<String> names) {
        return names.stream().map(EntityVertexBridge::entityIdVarying)
                .map(varying -> varying + PASSED_ON_SUFFIX + " = " + varying + "[0];").toList();
    }

    /** Each identity's flat input at the vertex stage's location, and its output at the fragment stage's. */
    private static String identityDeclarations(Collection<String> names,
                                               LegacyGlslConverter.TerrainVaryingLayout inputs,
                                               LegacyGlslConverter.TerrainVaryingLayout outputs) {
        StringBuilder declarations = new StringBuilder();
        for (String name : names) {
            String varying = EntityVertexBridge.entityIdVarying(name);
            declarations.append("layout(location = ").append(inputs.location(varying)).append(") flat in uint ")
                    .append(varying).append("[];\n")
                    .append("layout(location = ").append(outputs.location(varying)).append(") flat out uint ")
                    .append(varying).append(PASSED_ON_SUFFIX).append(";\n");
        }
        return declarations.toString();
    }

    /**
     * Each input at the location of the vertex output it reads, and each output at the location
     * the fragment stage reads it from; integer outputs are flat, as the fragment stage takes
     * them. An input nothing reads goes, as GL leaves it inactive.
     */
    private static String located(String source, LegacyGlslConverter.TerrainVaryingLayout inputs,
                                  LegacyGlslConverter.TerrainVaryingLayout outputs) {
        return GlslInterfaceScanner.rewrite(source, GlslInterfaceScanner.Stage.GEOMETRY, declaration -> {
            if (declaration.input()) {
                if (!inputs.locations().containsKey(declaration.name())) {
                    if (declaration.referenced()) {
                        throw new IllegalArgumentException("geometry input has no vertex output: "
                                + declaration.name());
                    }
                    return "";
                }
                return "layout(location = " + inputs.location(declaration.name()) + ") "
                        + qualified(declaration.qualifier()) + "in " + declaration.type() + " "
                        + declaration.declarator() + ";\n";
            }
            String interpolation = outputs.qualifier(declaration.name());
            if (interpolation.isEmpty() && declaration.integral()) interpolation = "flat";
            return "layout(location = " + outputs.location(declaration.name()) + ") " + qualified(interpolation)
                    + "out " + declaration.type() + " " + declaration.declarator() + ";\n";
        });
    }
}
