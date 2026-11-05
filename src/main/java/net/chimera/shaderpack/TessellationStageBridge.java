package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A program's tessellation stages (.tcs and .tes), which Iris runs between the vertex and fragment
 * stages when the pack lists TESSELLATION_SHADERS (Shrimple's terrain, water and hand). The control
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
    private static final Pattern TEXTURE_MATRIX = Pattern.compile("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]");

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
        return convertStages(new Stage(normalized(control), GlslInterfaceScanner.Stage.TESS_CONTROL, controlPlan),
                new Stage(normalized(evaluation), GlslInterfaceScanner.Stage.TESS_EVALUATION, evaluationPlan),
                inputLayout, fragment, allowStorageBuffers, PassedOn.NONE, false);
    }

    /** {@link #convertHand} for a hand program ({@code hand}), else {@link #convertTerrain}. */
    static Conversion convert(boolean hand, String control, String evaluation,
                              LegacyGlslConverter.TerrainVaryingLayout inputLayout, String fragment,
                              UniformRegistry.ProgramInterface controlPlan,
                              UniformRegistry.ProgramInterface evaluationPlan, boolean allowStorageBuffers) {
        return hand ? convertHand(control, evaluation, inputLayout, fragment, controlPlan, evaluationPlan,
                allowStorageBuffers) : convertTerrain(control, evaluation, inputLayout, fragment, controlPlan,
                evaluationPlan, allowStorageBuffers);
    }

    /**
     * Converts both stages of a hand program (gbuffers_hand, gbuffers_hand_water), drawn by the
     * entity vertex bridge. The hand's projection is the draw's own, which the host serves to the
     * vertex stage alone, so neither stage may read a legacy matrix. The values that bridge adds
     * to the vertex stage's outputs for the fragment stage (the entity identities and the
     * forwarded host values) pass on through both stages ({@link PassedOn}). A stage that reads the
     * pack's uniforms declares the entity family's block, as the vertex stage does.
     *
     * @throws IllegalArgumentException as {@link #convertTerrain}, and when a stage reads a draw matrix
     */
    static Conversion convertHand(String control, String evaluation,
                                  LegacyGlslConverter.TerrainVaryingLayout inputLayout, String fragment,
                                  UniformRegistry.ProgramInterface controlPlan,
                                  UniformRegistry.ProgramInterface evaluationPlan, boolean allowStorageBuffers) {
        Stage controlStage = new Stage(normalized(control), GlslInterfaceScanner.Stage.TESS_CONTROL, controlPlan);
        Stage evaluationStage = new Stage(normalized(evaluation), GlslInterfaceScanner.Stage.TESS_EVALUATION,
                evaluationPlan);
        requireNoDrawMatrices(controlStage.source());
        requireNoDrawMatrices(evaluationStage.source());
        Matcher domain = INPUT_LAYOUT.matcher(GlslLexer.stripComments(evaluationStage.source()));
        if (!domain.find() || !domain.group(1).matches("(?s).*\\btriangles\\b.*")) {
            throw new IllegalArgumentException("hand tessellation domain is not triangles");
        }
        return convertStages(controlStage, evaluationStage, inputLayout, fragment, allowStorageBuffers,
                PassedOn.of(inputLayout), true);
    }

    private static Conversion convertStages(Stage controlStage, Stage evaluationStage,
                                            LegacyGlslConverter.TerrainVaryingLayout inputLayout, String fragment,
                                            boolean allowStorageBuffers, PassedOn passedOn, boolean entityBlock) {
        String viewBlock = GlslVertexPreambles.TERRAIN_VIEW_BLOCK;
        LegacyGlslConverter.TerrainVaryingLayout patchLayout = patchLayout(controlStage.source(), passedOn);
        String producer = producerView(evaluationStage.source());
        LegacyGlslConverter.TerrainVaryingLayout outputLayout = passedOn.appendedTo(
                TerrainVertexBridge.modernVaryingLayout(producer, LegacyGlslConverter.expandModernVaryingLists(
                        TerrainVertexBridge.normalizedFragment(fragment, false)), Map.of()),
                EntityVertexBridge.parseEntityVaryingDeclarations(producer, true).slots().values().stream()
                        .mapToInt(Integer::intValue).sum(), "");
        String convertedControl = WorldClipRange.GL.withHelpers(passedOn.control(
                converted(controlStage, inputLayout, patchLayout, allowStorageBuffers), inputLayout, patchLayout));
        String convertedEvaluation = WorldClipRange.GL.convert(swappedWinding(passedOn.evaluation(
                converted(evaluationStage, patchLayout, outputLayout, allowStorageBuffers), patchLayout,
                outputLayout)));
        return new Conversion(header(convertedControl, controlStage.plan(), viewBlock, entityBlock),
                header(convertedEvaluation, evaluationStage.plan(), viewBlock, entityBlock), outputLayout);
    }

    /** No legacy matrix of the draw, nor its texture matrix, which only the vertex stage is served. */
    private static void requireNoDrawMatrices(String source) {
        String stripped = GlslLexer.stripComments(source);
        String matrix = GlslTokenRewriter.firstIdentifier(stripped,
                GlslVertexPreambles.terrainMatrixBuiltins()::containsKey);
        if (matrix == null && TEXTURE_MATRIX.matcher(stripped).find()) matrix = "gl_TextureMatrix[0]";
        if (matrix != null) {
            throw new IllegalArgumentException("hand tessellation stage reads a draw matrix only the vertex stage "
                    + "is served: " + matrix);
        }
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

    /**
     * The control stage's per-vertex outputs, packed in name order, which the evaluation stage reads,
     * then the values it passes on.
     */
    private static LegacyGlslConverter.TerrainVaryingLayout patchLayout(String control, PassedOn passedOn) {
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
        return passedOn.appendedTo(new LegacyGlslConverter.TerrainVaryingLayout(types, locations, qualifiers),
                location, PassedOn.PATCH_SUFFIX);
    }

    /**
     * The values the entity vertex bridge adds to the vertex stage's outputs for the fragment stage,
     * each with its type and qualifier, which a hand's tessellation stages pass on. The control
     * stage copies each per vertex. The evaluation stage writes a smooth float as the pack's own
     * values are, at the evaluated point. It writes any other value as the patch's first vertex's,
     * as Iris reads iris_entityInfo[0].
     */
    private record PassedOn(Map<String, String> types, Map<String, String> qualifiers) {
        static final PassedOn NONE = new PassedOn(Map.of(), Map.of());
        /** The suffix of a value's per-vertex copy; the evaluation stage writes the vertex stage's name. */
        static final String PATCH_SUFFIX = "TC";

        static PassedOn of(LegacyGlslConverter.TerrainVaryingLayout vertexLayout) {
            List<String> names = new ArrayList<>();
            EntityVertexBridge.ENTITY_ID_NAMES.forEach(name -> names.add(EntityVertexBridge.entityIdVarying(name)));
            EntityVertexBridge.ForwardedVarying.ALL.forEach(varying -> names.add(varying.name()));
            Map<String, String> types = new LinkedHashMap<>();
            Map<String, String> qualifiers = new LinkedHashMap<>();
            for (String name : names) {
                if (!vertexLayout.locations().containsKey(name)) continue;
                String type = vertexLayout.types().get(name);
                types.put(name, type);
                // An integer is flat, as the fragment stage takes it.
                qualifiers.put(name, type.matches("u?int|[iu]vec[234]") ? "flat" : vertexLayout.qualifier(name));
            }
            return new PassedOn(types, qualifiers);
        }

        /** {@code layout} and each value after it from {@code location}, its name with {@code suffix}. */
        LegacyGlslConverter.TerrainVaryingLayout appendedTo(LegacyGlslConverter.TerrainVaryingLayout layout,
                                                            int location, String suffix) {
            if (types.isEmpty()) return layout;
            Map<String, String> allTypes = new TreeMap<>(layout.types());
            Map<String, Integer> locations = new TreeMap<>(layout.locations());
            Map<String, String> allQualifiers = new TreeMap<>(layout.qualifiers());
            int next = location;
            for (String name : types.keySet()) {
                allTypes.put(name + suffix, types.get(name));
                locations.put(name + suffix, next++);
                if (!qualifiers.get(name).isEmpty()) allQualifiers.put(name + suffix, qualifiers.get(name));
            }
            return new LegacyGlslConverter.TerrainVaryingLayout(allTypes, locations, allQualifiers);
        }

        /** The control stage reading each value from the vertex stage and copying it per vertex. */
        String control(String converted, LegacyGlslConverter.TerrainVaryingLayout inputs,
                       LegacyGlslConverter.TerrainVaryingLayout patch) {
            String result = converted;
            StringBuilder declarations = new StringBuilder();
            for (String name : types.keySet()) {
                declarations.append(declaration(inputs, name, "in", "[]"))
                        .append(declaration(patch, name + PATCH_SUFFIX, "out", "[]"));
                result = LegacyGlslConverter.injectMainPrologue(result,
                        name + PATCH_SUFFIX + "[gl_InvocationID] = " + name + "[gl_InvocationID];");
            }
            return beforeMain(result, declarations.toString());
        }

        /** The evaluation stage reading each value's copies and writing the value the fragment stage reads. */
        String evaluation(String converted, LegacyGlslConverter.TerrainVaryingLayout patch,
                          LegacyGlslConverter.TerrainVaryingLayout outputs) {
            String result = converted;
            StringBuilder declarations = new StringBuilder();
            for (String name : types.keySet()) {
                String copies = name + PATCH_SUFFIX;
                declarations.append(declaration(patch, copies, "in", "[]"))
                        .append(declaration(outputs, name, "out", ""));
                boolean smooth = qualifiers.get(name).isEmpty() && types.get(name).matches("float|vec[234]");
                String value = !smooth ? copies + "[0]" : "gl_TessCoord.x * " + copies + "[0] + gl_TessCoord.y * "
                        + copies + "[1] + gl_TessCoord.z * " + copies + "[2]";
                result = LegacyGlslConverter.injectMainPrologue(result, name + " = " + value + ";");
            }
            return beforeMain(result, declarations.toString());
        }

        private static String declaration(LegacyGlslConverter.TerrainVaryingLayout layout, String name,
                                          String direction, String array) {
            return "layout(location = " + layout.location(name) + ") " + qualified(layout.qualifier(name))
                    + direction + " " + layout.types().get(name) + " " + name + array + ";\n";
        }

        private static String beforeMain(String source, String declarations) {
            if (declarations.isEmpty()) return source;
            GlslTree tree = GlslTree.parse(source);
            int main = tree.offset(GlslDeclarations.mainDefinition(tree).range().start());
            return source.substring(0, main) + declarations + source.substring(main);
        }
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

    /**
     * The stage's version, render-stage defines, the host view block when it reads MVP, and the pack's
     * uniforms: the terrain lanes' block, or the entity family's ({@code entityBlock}) when the stage reads one.
     */
    private static String header(String converted, UniformRegistry.ProgramInterface plan, String viewBlock,
                                 boolean entityBlock) {
        String view = GlslTokenRewriter.containsIdentifier(converted, "MVP") ? viewBlock : "";
        String uniforms = !entityBlock ? TerrainVertexBridge.modernUniformBlock(plan, false)
                : GlslVertexPreambles.entityCameraUniformBlock(converted, plan == null ? null
                : plan.executableUniforms());
        return "#version 460\n" + FragmentConverter.SHADOW_RENDER_STAGE_DEFINES + view + uniforms + converted;
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
