package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tessellation stages between the vertex and fragment stages (TASK-1066), in the shape Shrimple's
 * terrain and water take once their VertexData blocks are flattened: the control stage copies each
 * vertex through and sets its levels, the evaluation stage interpolates and projects.
 */
final class TessellationStageBridgeTest {
    /**
     * A plan holds its between stages as one BetweenStages, checked once when it is made (it refuses
     * a lone tessellation stage, audit FD2), and reads each stage through it; a plan without them
     * has none (proposal 4).
     */
    @Test
    void aPlanHoldsItsBetweenStagesAsOne() {
        PackProgram program = new PackProgram("gbuffers_terrain", "", null);
        BetweenStages pair = new BetweenStages("control", "evaluation", "geometry");
        PackProgramPlan tessellated = new PackProgramPlan(program, Map.of(), null, Map.of(), Map.of(), null, null,
                null, null, List.of(), true, null, null, null, null, null, null, pair);
        assertSame(pair, tessellated.betweenStages());
        assertEquals(List.of("control", "evaluation", "geometry"), List.of(tessellated.convertedTessControl(),
                tessellated.convertedTessEvaluation(), tessellated.convertedGeometry()));
        PackProgramPlan plain = new PackProgramPlan(program, Map.of(), null, Map.of(), Map.of(), null, null, null,
                null, List.of(), true);
        assertEquals(BetweenStages.NONE, plain.betweenStages());
        assertNull(plain.convertedTessControl());
        // The check itself: a lone control or evaluation stage cannot be made.
        assertThrows(IllegalArgumentException.class, () -> new BetweenStages("control", null, null));
        assertThrows(IllegalArgumentException.class, () -> new BetweenStages(null, "evaluation", null));
    }

    /**
     * Per-patch varyings are not carried between the tessellation stages, so the scan refuses a
     * patch in or patch out, array or not. Read per vertex, an array one took the wrong shape.
     */
    @Test
    void aPatchVaryingIsRefusedArrayOrNot() {
        for (String declaration : List.of("patch out vec4 tilt;", "patch out vec4 tilts[2];")) {
            String control = "#version 430 compatibility\nlayout (vertices=3) out;\nin vec2 uv[];\nout vec2 uvOut[];\n"
                    + declaration + "\nvoid main() { uvOut[gl_InvocationID] = uv[gl_InvocationID]; }\n";
            GlslInterfaceScanner.StageInterface scanned =
                    GlslInterfaceScanner.scan(control, GlslInterfaceScanner.Stage.TESS_CONTROL);
            assertTrue(scanned.deviations().contains("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED")
                    && scanned.outputs().stream().noneMatch(output -> output.name().startsWith("tilt")),
                    declaration + ": " + scanned);
        }
        String evaluation = "#version 430 compatibility\nlayout (triangles) in;\npatch in vec4 tilts[2];\n"
                + "void main() { gl_Position = tilts[0]; }\n";
        assertTrue(GlslInterfaceScanner.scan(evaluation, GlslInterfaceScanner.Stage.TESS_EVALUATION).deviations()
                .contains("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED"));
    }

    private static final String CONTROL = """
            #version 430 compatibility
            layout (vertices=3) out;
            in vec2 VertexData_uv[];
            flat in int VertexData_blockId[];
            in vec3 VertexData_unused[];
            out vec2 VertexData_uv_tc[];
            flat out int VertexData_blockId_tc[];
            void main() {
                gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
                VertexData_uv_tc[gl_InvocationID] = VertexData_uv[gl_InvocationID];
                VertexData_blockId_tc[gl_InvocationID] = VertexData_blockId[gl_InvocationID];
                if (gl_InvocationID == 0) {
                    gl_TessLevelOuter[0] = 1.0;
                    gl_TessLevelOuter[1] = 1.0;
                    gl_TessLevelOuter[2] = 1.0;
                    gl_TessLevelInner[0] = 1.0;
                }
            }
            """;
    private static final String EVALUATION = """
            #version 430 compatibility
            layout (triangles, fractional_odd_spacing, ccw) in;
            in vec2 VertexData_uv_tc[];
            flat in int VertexData_blockId_tc[];
            out vec2 VertexData_uv_te;
            flat out int VertexData_blockId_te;
            void main() {
                vec4 view = gl_TessCoord.x * gl_in[0].gl_Position + gl_TessCoord.y * gl_in[1].gl_Position
                        + gl_TessCoord.z * gl_in[2].gl_Position;
                VertexData_uv_te = gl_TessCoord.x * VertexData_uv_tc[0] + gl_TessCoord.y * VertexData_uv_tc[1]
                        + gl_TessCoord.z * VertexData_uv_tc[2];
                VertexData_blockId_te = VertexData_blockId_tc[0];
                gl_Position = gl_ProjectionMatrix * view;
            }
            """;
    private static final String FRAGMENT = """
            #version 430 compatibility
            in vec2 VertexData_uv_te;
            flat in int VertexData_blockId_te;
            void main() { gl_FragData[0] = vec4(VertexData_uv_te, float(VertexData_blockId_te), 1.0); }
            """;

    /** The control stage's inputs and outputs, and the evaluation stage's inputs, read one value per vertex. */
    @Test
    void theStagesPairPerVertexAsIrisLinksThem() {
        var control = GlslInterfaceScanner.scan(CONTROL, GlslInterfaceScanner.Stage.TESS_CONTROL);
        var evaluation = GlslInterfaceScanner.scan(EVALUATION, GlslInterfaceScanner.Stage.TESS_EVALUATION);
        assertTrue(control.deviations().isEmpty() && evaluation.deviations().isEmpty(),
                control.deviations() + " " + evaluation.deviations());
        assertTrue(control.output("VertexData_uv_tc").perVertex() && control.input("VertexData_uv").perVertex());
        assertEquals("VertexData_uv_tc[]", control.output("VertexData_uv_tc").declarator());
        assertFalse(evaluation.output("VertexData_uv_te").perVertex());
        var vertex = GlslInterfaceScanner.scan("""
                out vec2 VertexData_uv;
                flat out int VertexData_blockId;
                void main() { VertexData_uv = vec2(0.0); VertexData_blockId = 1; gl_Position = vec4(0.0); }
                """, true);
        var match = GlslInterfaceScanner.match(List.of(vertex, control, evaluation,
                GlslInterfaceScanner.scan(FRAGMENT, false)));
        assertTrue(match.executable(), match.deviations().toString());
        assertEquals(Map.of("VertexData_blockId_te", 0, "VertexData_uv_te", 1), match.locations());
    }

    /** The control stage as the vertex stage's consumer: single-valued inputs, no outputs, the body kept. */
    @Test
    void theConsumerViewReadsLikeAFragmentStage() {
        String view = TessellationStageBridge.consumerView(CONTROL);
        assertTrue(view.contains("in vec2 VertexData_uv;\n") && view.contains("flat in int VertexData_blockId;\n"),
                view);
        assertFalse(view.contains("out vec2 VertexData_uv_tc"), view);
        assertTrue(view.contains("VertexData_uv_tc[gl_InvocationID] = VertexData_uv[gl_InvocationID];"), view);
    }

    /**
     * The control stage reads the vertex outputs' locations and writes its own, packed by name; the
     * evaluation stage reads those and writes the fragment inputs' locations, an integer flat. Only
     * the evaluation stage, the last before the rasterizer, converts GL's clip depth, and only it
     * reads the host's projection, so only it declares the view block. Its winding is reversed.
     */
    @Test
    void bothStagesConvert() {
        LegacyGlslConverter.TerrainVaryingLayout vertex = new LegacyGlslConverter.TerrainVaryingLayout(
                Map.of("VertexData_blockId", "int", "VertexData_uv", "vec2"),
                Map.of("VertexData_blockId", 3, "VertexData_uv", 5));
        TessellationStageBridge.Conversion conversion = TessellationStageBridge.convertTerrain(CONTROL, EVALUATION,
                vertex, FRAGMENT, null, null, false);
        String control = conversion.control();
        assertTrue(control.startsWith("#version 460\n") && !control.contains("compatibility"), control);
        assertTrue(control.contains("layout(location = 5) in vec2 VertexData_uv[];")
                && control.contains("layout(location = 3) flat in int VertexData_blockId[];")
                && !control.contains("VertexData_unused"), control);
        assertTrue(control.contains("layout(location = 1) out vec2 VertexData_uv_tc[];")
                && control.contains("layout(location = 0) flat out int VertexData_blockId_tc[];")
                && control.contains("layout (vertices=3) out;"), control);
        assertFalse(control.contains(WorldClipRange.AUTHORED_ENTRY) || control.contains("ViewUBO"), control);

        String evaluation = conversion.evaluation();
        assertTrue(evaluation.contains("layout(location = 1) in vec2 VertexData_uv_tc[];")
                && evaluation.contains("layout(location = 0) flat in int VertexData_blockId_tc[];"), evaluation);
        assertTrue(evaluation.contains("layout(location = 1) out vec2 VertexData_uv_te;")
                && evaluation.contains("layout(location = 0) flat out int VertexData_blockId_te;"), evaluation);
        assertTrue(evaluation.contains("layout (triangles, fractional_odd_spacing, cw) in;"), evaluation);
        assertTrue(evaluation.contains("gl_Position = chimeraGlClip((MVP * inverse(gbufferModelView))) * view;")
                && evaluation.contains("uniform ViewUBO")
                && evaluation.contains("gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);"), evaluation);
        assertEquals(1, conversion.outputLayout().location("VertexData_uv_te"));
    }

    /** Shrimple's hand evaluation stage at its default DISPLACE_MODE: it interpolates the projected position. */
    private static final String HAND_EVALUATION = EVALUATION.replace("vec4 view =", "gl_Position =")
            .replace("gl_Position = gl_ProjectionMatrix * view;", "");

    /**
     * The entity bridge adds the identities and host values the hand fragment reads to the vertex
     * stage's outputs; both stages pass them on after the pack's own. The control stage copies each
     * per vertex. The evaluation stage writes an integer as the first vertex's and a float at the
     * evaluated point, and converts the clip depth the vertex stage left in GL's range.
     */
    @Test
    void aHandPassesTheBridgeValuesOnThroughBothStages() {
        LegacyGlslConverter.TerrainVaryingLayout vertex = new LegacyGlslConverter.TerrainVaryingLayout(
                Map.of("VertexData_blockId", "int", "VertexData_uv", "vec2", "chimeraRenderedItemId", "uint",
                        "chimeraVertexAlpha", "float"),
                Map.of("VertexData_blockId", 3, "VertexData_uv", 5, "chimeraRenderedItemId", 6,
                        "chimeraVertexAlpha", 7),
                Map.of("chimeraRenderedItemId", "flat"));
        TessellationStageBridge.Conversion conversion = TessellationStageBridge.convert(true, CONTROL,
                HAND_EVALUATION, vertex, FRAGMENT, null, null, false);
        String control = conversion.control();
        assertTrue(control.contains("layout(location = 6) flat in uint chimeraRenderedItemId[];")
                && control.contains("layout(location = 2) flat out uint chimeraRenderedItemIdTC[];")
                && control.contains("layout(location = 3) out float chimeraVertexAlphaTC[];")
                && control.contains("chimeraRenderedItemIdTC[gl_InvocationID] = "
                        + "chimeraRenderedItemId[gl_InvocationID];"), control);
        String evaluation = conversion.evaluation();
        assertTrue(evaluation.contains("layout(location = 2) flat out uint chimeraRenderedItemId;")
                && evaluation.contains("chimeraRenderedItemId = chimeraRenderedItemIdTC[0];")
                && evaluation.contains("chimeraVertexAlpha = gl_TessCoord.x * chimeraVertexAlphaTC[0]"
                        + " + gl_TessCoord.y * chimeraVertexAlphaTC[1] + gl_TessCoord.z * chimeraVertexAlphaTC[2];")
                && evaluation.contains("gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);")
                && !evaluation.contains("ViewUBO"), evaluation);
        assertEquals(List.of(2, 3), List.of(conversion.outputLayout().location("chimeraRenderedItemId"),
                conversion.outputLayout().location("chimeraVertexAlpha")));
        assertNull(PackCompileTest.compileBetween(control, "tess_control"), control);
        assertNull(PackCompileTest.compileBetween(evaluation, "tess_evaluation"), evaluation);
    }

    /**
     * Shrimple's DISPLACE_TESSELATION projects the hand in its evaluation stage, which reads the
     * draw's own projection in GL's clip range from the host's Projection block, as the hand's vertex
     * stage does; a terrain stage reads the view block's MVP. Such a hand fell back before.
     */
    @Test
    void aHandEvaluationStageProjectsWithTheHandsOwnProjection() {
        LegacyGlslConverter.TerrainVaryingLayout vertex = new LegacyGlslConverter.TerrainVaryingLayout(
                Map.of("VertexData_blockId", "int", "VertexData_uv", "vec2"),
                Map.of("VertexData_blockId", 0, "VertexData_uv", 1));
        String evaluation = TessellationStageBridge.convert(true, CONTROL, EVALUATION, vertex, FRAGMENT, null,
                null, false).evaluation();
        assertTrue(evaluation.contains("gl_Position = chimeraHandProjection() * view;")
                && evaluation.contains("layout(binding = 1) uniform Projection")
                && evaluation.contains("mat4 chimeraHandProjection()")
                && !evaluation.contains("ViewUBO") && !evaluation.contains("DynamicTransforms"), evaluation);
        assertNull(PackCompileTest.compileBetween(evaluation, "tess_evaluation"), evaluation);
    }

    /**
     * A tessellated hand in a pack, projected in its evaluation stage as Shrimple's DISPLACE_TESSELATION
     * does: admitted, its evaluation stage declaring the entity family's uniform block where it reads a
     * uniform and the host's Projection block, and the whole descriptor set matching every stage as
     * the runtime checks it (PackPipelines.prepareLayout). Declaring the terrain lanes' block there
     * stopped the game at launch (DESCRIPTOR_LAYOUT_MISMATCH:ChimeraTerrainPackUniforms).
     */
    @Test
    void aTessellatedHandsDescriptorsServeEveryStage(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "iris.features.optional=TESSELATION_SHADERS\n");
        Files.writeString(shaders.resolve("gbuffers_hand.vsh"), """
                #version 430 compatibility
                out vec2 uv;
                void main() {
                    uv = gl_MultiTexCoord0.xy;
                    gl_Position = gl_ModelViewMatrix * gl_Vertex;
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_hand.tcs"), """
                #version 430 compatibility
                layout(vertices = 3) out;
                in vec2 uv[];
                out vec2 uvTc[];
                void main() {
                    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
                    uvTc[gl_InvocationID] = uv[gl_InvocationID];
                    gl_TessLevelOuter[0] = 1.0; gl_TessLevelOuter[1] = 1.0; gl_TessLevelOuter[2] = 1.0;
                    gl_TessLevelInner[0] = 1.0;
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_hand.tes"), """
                #version 430 compatibility
                layout(triangles) in;
                uniform float frameTimeCounter;
                in vec2 uvTc[];
                out vec2 uvTe;
                void main() {
                    gl_Position = gl_ProjectionMatrix * (gl_TessCoord.x * gl_in[0].gl_Position
                            + gl_TessCoord.y * gl_in[1].gl_Position + gl_TessCoord.z * gl_in[2].gl_Position);
                    uvTe = gl_TessCoord.x * uvTc[0] + gl_TessCoord.y * uvTc[1] + gl_TessCoord.z * uvTc[2]
                            + vec2(0.0, frameTimeCounter * 0.0);
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_hand.fsh"), """
                #version 430 compatibility
                uniform sampler2D texture;
                uniform float frameTimeCounter;
                uniform int currentRenderedItemId;
                in vec2 uvTe;
                void main() {
                    gl_FragData[0] = texture2D(texture, uvTe) * float(currentRenderedItemId) * frameTimeCounter;
                }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackProgramPlan hand = analysis.plan().program("gbuffers_hand");
        assertTrue(hand.executable(), hand.deviations().toString());
        assertTrue(hand.convertedTessEvaluation().contains("layout(binding = 2) uniform ChimeraEntityUniforms")
                && hand.convertedTessEvaluation().contains("layout(binding = 1) uniform Projection")
                && hand.convertedTessEvaluation().contains("chimeraRenderedItemId = chimeraRenderedItemIdTC[0];")
                && !hand.convertedTessControl().contains("ChimeraTerrainPackUniforms"), hand.convertedTessEvaluation());
        PackPipelines.PreparedPipeline prepared = PackPipelines.prepare(hand, analysis.plan().advancedResources(),
                UniformRegistry.Stage.HAND, PackFamilyPipelines.familyVertex(hand));
        PackPipelines.prepareLayout(prepared, hand.name(), analysis.plan().advancedResources());
        assertNull(PackCompileTest.compile(prepared.vertex(), true), prepared.vertex());
        assertNull(PackCompileTest.compileBetween(prepared.between().tessControl(), "tess_control"));
        assertNull(PackCompileTest.compileBetween(prepared.between().tessEvaluation(), "tess_evaluation"),
                prepared.between().tessEvaluation());
        assertNull(PackCompileTest.compile(prepared.fragment(), false), prepared.fragment());
    }

    /**
     * The hand's entity preamble declares the host's Normal, so a pack varying of that name is
     * renamed. The rename skipped the tessellation stages: the vertex wrote the renamed output
     * while the control stage still read Normal[], the pair did not match and the hand fell back.
     */
    @Test
    void aVaryingNamedLikeAHostInputPairsThroughTheTessellationStages(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "iris.features.optional=TESSELATION_SHADERS\n");
        Files.writeString(shaders.resolve("gbuffers_hand.vsh"), """
                #version 430 compatibility
                out vec3 Normal;
                void main() {
                    Normal = gl_Normal;
                    gl_Position = gl_ModelViewMatrix * gl_Vertex;
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_hand.tcs"), """
                #version 430 compatibility
                layout(vertices = 3) out;
                in vec3 Normal[];
                out vec3 normalTc[];
                void main() {
                    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
                    normalTc[gl_InvocationID] = Normal[gl_InvocationID];
                    gl_TessLevelOuter[0] = 1.0; gl_TessLevelOuter[1] = 1.0; gl_TessLevelOuter[2] = 1.0;
                    gl_TessLevelInner[0] = 1.0;
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_hand.tes"), """
                #version 430 compatibility
                layout(triangles) in;
                in vec3 normalTc[];
                out vec3 normalTe;
                void main() {
                    gl_Position = gl_ProjectionMatrix * (gl_TessCoord.x * gl_in[0].gl_Position
                            + gl_TessCoord.y * gl_in[1].gl_Position + gl_TessCoord.z * gl_in[2].gl_Position);
                    normalTe = gl_TessCoord.x * normalTc[0] + gl_TessCoord.y * normalTc[1]
                            + gl_TessCoord.z * normalTc[2];
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_hand.fsh"), """
                #version 430 compatibility
                in vec3 normalTe;
                void main() {
                    gl_FragData[0] = vec4(normalTe, 1.0);
                }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackProgramPlan hand = analysis.plan().program("gbuffers_hand");
        assertTrue(hand.executable(), hand.deviations().toString());
        PackPipelines.PreparedPipeline prepared = PackPipelines.prepare(hand, analysis.plan().advancedResources(),
                UniformRegistry.Stage.HAND, PackFamilyPipelines.familyVertex(hand));
        PackPipelines.prepareLayout(prepared, hand.name(), analysis.plan().advancedResources());
        assertNull(PackCompileTest.compile(prepared.vertex(), true), prepared.vertex());
        assertNull(PackCompileTest.compileBetween(prepared.between().tessControl(), "tess_control"),
                prepared.between().tessControl());
        assertNull(PackCompileTest.compileBetween(prepared.between().tessEvaluation(), "tess_evaluation"),
                prepared.between().tessEvaluation());
        assertNull(PackCompileTest.compile(prepared.fragment(), false), prepared.fragment());
    }

    /**
     * A commented layout above the real one took the swap, and the real one kept GL's ccw: every
     * tessellated triangle faced the other way. An order in a second layout statement kept GL's
     * beside the cw the first gained, and the stage failed to compile.
     */
    @Test
    void theWindingSwapReadsOnlyLiveLayoutsAndEveryOrderInThem() {
        assertEquals("// layout(triangles, ccw) in;\nlayout(triangles, cw) in;",
                TessellationStageBridge.swappedWinding("// layout(triangles, ccw) in;\nlayout(triangles, ccw) in;"));
        assertEquals("/* layout(triangles) in; */\nlayout(triangles, cw) in;",
                TessellationStageBridge.swappedWinding("/* layout(triangles) in; */\nlayout(triangles) in;"));
        assertEquals("layout(triangles, equal_spacing) in;\nlayout(cw) in;",
                TessellationStageBridge.swappedWinding("layout(triangles, equal_spacing) in;\nlayout(ccw) in;"));
        assertEquals("layout(triangles /* ccw */, cw) in;",
                TessellationStageBridge.swappedWinding("layout(triangles /* ccw */) in;"),
                "an order in a comment within the layout is not the layout's");
    }

    /** GL's ccw is Vulkan's cw under the upper-left domain origin, and GL's default is ccw. */
    @Test
    void theWindingIsReversed() {
        assertEquals("layout(triangles, cw) in;", TessellationStageBridge.swappedWinding("layout(triangles, ccw) in;"));
        assertEquals("layout(triangles, ccw) in;", TessellationStageBridge.swappedWinding("layout(triangles, cw) in;"));
        assertEquals("layout(triangles, cw) in;", TessellationStageBridge.swappedWinding("layout(triangles) in;"));
    }

    /** A world vertex stage ahead of tessellation runs in GL's range; the evaluation stage converts its depth. */
    @Test
    void aVertexStageAheadOfTessellationLeavesItsClipDepthInGlRange() {
        WorldClipRange clip = WorldClipRange.forVertex("void main() { gl_Position = vec4(0.0); }", false, true);
        assertEquals(WorldClipRange.GL, clip);
        String body = clip.vertexBody("void main() { gl_Position = vec4(0.0); }", false, true);
        assertTrue(body.contains("vec4 chimeraGlClip(vec4 clip)") && !body.contains(WorldClipRange.AUTHORED_ENTRY),
                body);
    }

    /**
     * A storage-buffer rewrite that fails on one tessellation stage leaves no pair: BetweenStages
     * refuses a lone stage, and the half pair threw from the reports and failed the whole pack.
     */
    @Test
    void aHalfConvertedTessellationPairIsNoPair() {
        assertNull(TessellationStageBridge.paired("control", null, null));
        assertNull(TessellationStageBridge.paired(null, "evaluation", null));
        TessellationStageBridge.Conversion both = TessellationStageBridge.paired("control", "evaluation", null);
        assertTrue(both != null && both.control().equals("control") && both.evaluation().equals("evaluation"));
    }
}
