package net.chimera.shaderpack;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A program's tessellation stages (.tcs and .tes), which Iris runs between the vertex and fragment
 * stages when the pack lists TESSELLATION_SHADERS (Shrimple's terrain and water). The control
 * stage's per-vertex inputs pair with the vertex stage's outputs, its per-vertex outputs with the
 * evaluation stage's inputs, and the evaluation stage's outputs with the fragment stage's inputs,
 * each pair by name at one shared location. Both stages read the program's uniforms through the
 * block the other stages declare, bind their samplers as those do, and read the legacy matrices
 * as the vertex stage does (GlslVertexPreambles.terrainMatrixBuiltins), in GL's clip range: the
 * vertex stage hands the control stage GL's clip position, as under Iris, and the evaluation
 * stage, the last before the rasterizer, converts the depth it emits once (WorldClipRange).
 *
 * <p>Under Vulkan's default upper-left domain origin the tessellator orders a generated
 * triangle's vertices against GL's, which has a lower-left origin. The evaluation stage's winding
 * is swapped, so a triangle GL keeps front-facing stays front-facing.</p>
 */
final class TessellationStageBridge {
    private TessellationStageBridge() {}

    /** The evaluation stage's input layout, {@code layout(triangles, ccw) in;}. */
    private static final Pattern INPUT_LAYOUT = Pattern.compile("\\blayout\\s*\\(([^)]*)\\)\\s*in\\s*;");

    /** The converted stages, and the locations of the evaluation stage's outputs, which the fragment stage reads. */
    record Conversion(String control, String evaluation, LegacyGlslConverter.TerrainVaryingLayout outputLayout) {}

    /**
     * The control stage as the vertex stage's consumer: each per-vertex input declared as the one
     * vertex's value it reads, and no output, so the vertex bridge pairs with it as with a fragment.
     */
    static String consumerView(String control) {
        return GlslInterfaceScanner.rewrite(control, GlslInterfaceScanner.Stage.TESS_CONTROL, declaration ->
                !declaration.input() ? "" : qualified(declaration.qualifier()) + "in " + declaration.type() + " "
                        + declaration.name() + arraySuffix(declaration) + ";\n");
    }

    /**
     * Converts both stages of a terrain or water program. {@code inputLayout} holds the vertex
     * stage's output locations. A stage that reads the host's matrices declares the terrain view
     * block the vertex stage reads them from.
     *
     * @throws IllegalArgumentException when a stage reads an input nothing writes, a storage buffer
     *         the plan did not admit, or an unplanned value uniform
     */
    static Conversion convertTerrain(String control, String evaluation,
                                     LegacyGlslConverter.TerrainVaryingLayout inputLayout, String fragment,
                                     UniformRegistry.ProgramInterface controlPlan,
                                     UniformRegistry.ProgramInterface evaluationPlan, boolean allowStorageBuffers) {
        String viewBlock = GlslVertexPreambles.TERRAIN_VIEW_BLOCK;
        Stage controlStage = new Stage(normalized(control), GlslInterfaceScanner.Stage.TESS_CONTROL, controlPlan);
        Stage evaluationStage = new Stage(normalized(evaluation), GlslInterfaceScanner.Stage.TESS_EVALUATION,
                evaluationPlan);
        LegacyGlslConverter.TerrainVaryingLayout patchLayout = patchLayout(controlStage.source());
        LegacyGlslConverter.TerrainVaryingLayout outputLayout = TerrainVertexBridge.modernVaryingLayout(
                producerView(evaluationStage.source()), LegacyGlslConverter.expandModernVaryingLists(
                        TerrainVertexBridge.normalizedFragment(fragment, false)), Map.of());
        String convertedControl = WorldClipRange.GL.withHelpers(
                converted(controlStage, inputLayout, patchLayout, allowStorageBuffers));
        String convertedEvaluation = WorldClipRange.GL.convert(swappedWinding(
                converted(evaluationStage, patchLayout, outputLayout, allowStorageBuffers)));
        return new Conversion(header(convertedControl, controlPlan, viewBlock),
                header(convertedEvaluation, evaluationPlan, viewBlock), outputLayout);
    }

    /** One stage's normalized source, its kind and its projection of the program's interface. */
    private record Stage(String source, GlslInterfaceScanner.Stage kind, UniformRegistry.ProgramInterface plan) {}

    private static String normalized(String source) {
        return LegacyGlslConverter.expandModernVaryingLists(TerrainVertexBridge.normalizeModernTerrain(source));
    }

    /** The evaluation stage as the fragment stage's producer: its outputs alone. */
    private static String producerView(String evaluation) {
        return GlslInterfaceScanner.rewrite(evaluation, GlslInterfaceScanner.Stage.TESS_EVALUATION,
                declaration -> declaration.input() ? "" : null);
    }

    /** The control stage's per-vertex outputs, packed in name order, which the evaluation stage reads. */
    private static LegacyGlslConverter.TerrainVaryingLayout patchLayout(String control) {
        GlslInterfaceScanner.StageInterface scanned = GlslInterfaceScanner.scan(control,
                GlslInterfaceScanner.Stage.TESS_CONTROL);
        if (!scanned.deviations().isEmpty()) {
            throw new IllegalArgumentException("tessellation control interface unsupported: " + scanned.deviations());
        }
        Map<String, String> types = new TreeMap<>();
        Map<String, Integer> locations = new TreeMap<>();
        Map<String, String> qualifiers = new TreeMap<>();
        int location = 0;
        for (GlslInterfaceScanner.Declaration output : scanned.outputs()) {
            types.put(output.name(), output.typeWithArray());
            locations.put(output.name(), location);
            if (output.qualifier() != null) qualifiers.put(output.name(), output.qualifier());
            location += output.slots();
        }
        return new LegacyGlslConverter.TerrainVaryingLayout(types, locations, qualifiers);
    }

    /** The stage converted below its header: interface located, uniforms and samplers served, built-ins mapped. */
    private static String converted(Stage stage, LegacyGlslConverter.TerrainVaryingLayout inputs,
                                     LegacyGlslConverter.TerrainVaryingLayout outputs, boolean allowStorageBuffers) {
        String converted = TerrainVertexBridge.withoutUnreachableFunctions(
                LegacyGlslConverter.withoutVersionLines(stage.source()));
        converted = stage.plan() == null ? TerrainVertexBridge.stripShadowUniformDeclarations(converted)
                : UniformRegistry.removeUniformDeclarations(converted, stage.plan());
        requireSupported(converted, allowStorageBuffers);
        converted = located(converted, stage.kind(), inputs, outputs);
        converted = GlslTokenRewriter.replaceIdentifiers(converted,
                WorldClipRange.GL.builtins(GlslVertexPreambles.terrainMatrixBuiltins()));
        converted = LegacyGlslConverter.replaceLightmapTextureMatrices(converted)
                .replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]", "mat4(1.0)");
        converted = TerrainVertexBridge.rewriteModernTerrainSamplers(converted, stage.plan(), false);
        return LegacyGlslConverter.convertTextureCalls(converted);
    }

    /** The stage's version, render-stage defines, the host view block when it reads MVP, and the pack's uniforms. */
    private static String header(String converted, UniformRegistry.ProgramInterface plan, String viewBlock) {
        String view = GlslTokenRewriter.containsIdentifier(converted, "MVP") ? viewBlock : "";
        return "#version 460\n" + FragmentConverter.SHADOW_RENDER_STAGE_DEFINES + view
                + TerrainVertexBridge.modernUniformBlock(plan, false) + converted;
    }

    /** A main, no storage buffer the plan did not admit, and no value uniform the plan left out. */
    private static void requireSupported(String source, boolean allowStorageBuffers) {
        String stripped = GlslLexer.stripComments(source);
        GlslTree tree = GlslTree.parse(stripped);
        if (GlslDeclarations.main(tree) == null) {
            throw new IllegalArgumentException("tessellation stage main is missing");
        }
        if (!allowStorageBuffers && GlslTokenRewriter.containsIdentifier(stripped, "buffer")) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        String uniform = TerrainVertexBridge.valueUniform(tree);
        if (uniform != null) {
            throw new IllegalArgumentException("tessellation stage value uniform is unplanned: " + uniform);
        }
    }

    /**
     * Each input at the location of the output it reads and each output at its own location. An
     * input nothing writes goes when nothing reads it, as GL leaves it inactive. An integer output
     * of the evaluation stage is flat, as the fragment stage takes it.
     */
    private static String located(String source, GlslInterfaceScanner.Stage stage,
                                  LegacyGlslConverter.TerrainVaryingLayout inputs,
                                  LegacyGlslConverter.TerrainVaryingLayout outputs) {
        return GlslInterfaceScanner.rewrite(source, stage, declaration -> {
            LegacyGlslConverter.TerrainVaryingLayout layout = declaration.input() ? inputs : outputs;
            if (!layout.locations().containsKey(declaration.name())) {
                if (declaration.referenced() || declaration.output()) {
                    throw new IllegalArgumentException(stage + " varying has no location: " + declaration.name());
                }
                return "";
            }
            String interpolation = declaration.qualifier() == null ? "" : declaration.qualifier();
            if (declaration.output() && stage == GlslInterfaceScanner.Stage.TESS_EVALUATION) {
                interpolation = outputs.qualifier(declaration.name());
                if (interpolation.isEmpty() && declaration.integral()) interpolation = "flat";
            }
            return "layout(location = " + layout.location(declaration.name()) + ") " + qualified(interpolation)
                    + (declaration.input() ? "in " : "out ") + declaration.type() + " " + declaration.declarator()
                    + ";\n";
        });
    }

    /** The evaluation stage with its triangle winding reversed: GL's default ccw becomes cw. */
    static String swappedWinding(String evaluation) {
        Matcher layout = INPUT_LAYOUT.matcher(evaluation);
        if (!layout.find()) return evaluation;
        String qualifiers = layout.group(1);
        String swapped = qualifiers.matches("(?s).*\\bccw\\b.*") ? qualifiers.replaceAll("\\bccw\\b", "cw")
                : qualifiers.matches("(?s).*\\bcw\\b.*") ? qualifiers.replaceAll("\\bcw\\b", "ccw")
                : qualifiers + ", cw";
        return evaluation.substring(0, layout.start(1)) + swapped + evaluation.substring(layout.end(1));
    }

    private static String qualified(String qualifier) {
        return qualifier == null || qualifier.isEmpty() ? "" : qualifier + " ";
    }

    private static String arraySuffix(GlslInterfaceScanner.Declaration declaration) {
        return declaration.arraySize() == 0 ? "" : "[" + declaration.arraySize() + "]";
    }
}
