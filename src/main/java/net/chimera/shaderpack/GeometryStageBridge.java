package net.chimera.shaderpack;

import java.util.Map;

/**
 * A pack program's geometry stage (.gsh), which Iris runs between the vertex and fragment stages.
 * Its per-vertex inputs pair with the vertex stage's outputs and its outputs with the fragment
 * stage's inputs, each pair by name at one shared location (GlslInterfaceScanner.Stage.GEOMETRY).
 * The stage reads the program's uniforms through the shadow block the other stages declare, binds
 * its samplers as they do, and converts GL's clip depth for each vertex it emits, as the vertex
 * stage does when nothing follows it (ShadowStageBuiltins).
 */
final class GeometryStageBridge {
    private GeometryStageBridge() {}

    /** The converted stage, and the locations of its outputs, which the fragment stage's inputs take. */
    record Conversion(String source, LegacyGlslConverter.TerrainVaryingLayout outputLayout) {}

    /**
     * The stage as the vertex stage's consumer: each per-vertex input declared as the one vertex's
     * value it reads, the rest unchanged, so the vertex bridge pairs with it as with a fragment stage.
     */
    static String consumerView(String geometry) {
        return GlslInterfaceScanner.rewrite(geometry, GlslInterfaceScanner.Stage.GEOMETRY, declaration ->
                declaration.perVertex() ? qualified(declaration.qualifier()) + "in " + declaration.type() + " "
                        + declaration.name() + arraySuffix(declaration) + ";\n" : null);
    }

    /** The stage as the fragment stage's producer: its outputs alone. */
    static String producerView(String geometry) {
        return GlslInterfaceScanner.rewrite(geometry, GlslInterfaceScanner.Stage.GEOMETRY,
                declaration -> declaration.input() ? "" : null);
    }

    /**
     * Converts a shadow program's geometry stage. {@code inputLayout} holds the vertex stage's
     * output locations; the fragment stage's inputs decide the output layout.
     *
     * @throws IllegalArgumentException when the stage reads an input no vertex output feeds, an
     *         output the layout cannot place, a storage buffer the plan did not admit, or an
     *         unplanned value uniform
     */
    static Conversion convertShadow(String geometry, LegacyGlslConverter.TerrainVaryingLayout inputLayout,
                                    String fragment, UniformRegistry.ProgramInterface interfacePlan,
                                    boolean allowStorageBuffers) {
        String source = LegacyGlslConverter.expandModernVaryingLists(TerrainVertexBridge.normalizeShadowTerrain(
                geometry));
        LegacyGlslConverter.TerrainVaryingLayout outputLayout = TerrainVertexBridge.modernVaryingLayout(
                producerView(source), LegacyGlslConverter.expandModernVaryingLists(
                        TerrainVertexBridge.normalizedFragment(fragment, true)), Map.of());
        String converted = TerrainVertexBridge.withoutUnreachableFunctions(
                LegacyGlslConverter.withoutVersionLines(source));
        converted = interfacePlan == null ? TerrainVertexBridge.stripShadowUniformDeclarations(converted)
                : UniformRegistry.removeUniformDeclarations(converted, interfacePlan);
        requireSupported(converted, allowStorageBuffers);
        converted = located(converted, inputLayout, outputLayout);
        converted = GlslTokenRewriter.replaceIdentifiers(converted, ShadowStageBuiltins.matrixReplacements());
        converted = ShadowStageBuiltins.legacyBuiltins(converted);
        converted = TerrainVertexBridge.rewriteModernTerrainSamplers(converted, interfacePlan, true);
        converted = LegacyGlslConverter.convertTextureCalls(converted);
        converted = TerrainVertexBridge.qualifyPlannedShadowUniforms(converted, interfacePlan);
        converted = ShadowStageBuiltins.emitClipRange(converted);
        return new Conversion("#version 460\n" + FragmentConverter.SHADOW_RENDER_STAGE_DEFINES
                + TerrainVertexBridge.modernUniformBlock(interfacePlan, true) + converted, outputLayout);
    }

    /** A main, no storage buffer the plan did not admit, and no value uniform the plan left out. */
    private static void requireSupported(String source, boolean allowStorageBuffers) {
        String stripped = GlslLexer.stripComments(source);
        GlslTree tree = GlslTree.parse(stripped);
        if (GlslDeclarations.main(tree) == null) {
            throw new IllegalArgumentException("geometry stage main is missing");
        }
        if (!allowStorageBuffers && GlslTokenRewriter.containsIdentifier(stripped, "buffer")) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        String uniform = TerrainVertexBridge.valueUniform(tree);
        if (uniform != null) {
            throw new IllegalArgumentException("geometry stage value uniform is unplanned: " + uniform);
        }
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

    private static String qualified(String qualifier) {
        return qualifier == null || qualifier.isEmpty() ? "" : qualifier + " ";
    }

    private static String arraySuffix(GlslInterfaceScanner.Declaration declaration) {
        return declaration.arraySize() == 0 ? "" : "[" + declaration.arraySize() + "]";
    }
}
