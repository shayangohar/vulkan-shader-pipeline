package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris repairs every vertex/fragment pair before it links them
 * (CompatibilityTransformer.transformGrouped): a read input the vertex never declares gets a
 * zeroed output, an output never written starts at zero, and an output of another type of the
 * same kind is converted at the end of main. Bloop's overworld shadow writes lmtexcoord without
 * declaring it, so every shadow lane fell back.
 */
final class CrossStageRepairTest {
    @Test
    void aReadInputNoOutputDeclaresGetsAZeroedOutput() {
        CrossStageRepair.Result repaired = CrossStageRepair.repair("""
                #version 150 compatibility
                out float isflower;
                void main() {
                    lmtexcoord.xy = gl_MultiTexCoord0.xy;
                    isflower = 0.0;
                    gl_Position = ftransform();
                }
                """, """
                #version 150 compatibility
                in vec2 lmtexcoord;
                in float isflower;
                in vec3 neverRead;
                void main() { gl_FragData[0] = vec4(lmtexcoord, isflower, 1.0); }
                """);
        assertEquals(List.of("VARYING_UNWRITTEN_ZEROED:lmtexcoord"), repaired.deviations());
        assertTrue(repaired.earlier().contains("out vec2 lmtexcoord;\nout float isflower;"), repaired.earlier());
        assertTrue(repaired.earlier().contains("{\nlmtexcoord = vec2(0);\n"), repaired.earlier());
        assertTrue(!repaired.earlier().contains("neverRead"), "an input the fragment never reads gets nothing");
    }

    @Test
    void anOutputTheVertexNeverWritesStartsAtZero() {
        CrossStageRepair.Result repaired = CrossStageRepair.repair("""
                #version 330
                flat out int material;
                void main() { gl_Position = vec4(0.0); }
                """, """
                #version 330
                flat in int material;
                out vec4 color;
                void main() { color = vec4(material); }
                """);
        assertEquals(List.of("VARYING_UNASSIGNED_ZEROED:material"), repaired.deviations());
        assertTrue(repaired.earlier().contains("{\nmaterial = int(0);\n"), repaired.earlier());
    }

    /**
     * An output of another vector size takes the input's type: the vertex writes a global of the
     * old type, widened with zeros or cut at the end of main. A local of the same name stays.
     */
    @Test
    void anOutputOfAnotherSizeIsConverted() {
        CrossStageRepair.Result repaired = CrossStageRepair.repair("""
                #version 330
                out vec2 uv, other;
                out vec4 tint;
                float helper() { float uv = 1.0; return uv; }
                void main() {
                    uv = vec2(helper());
                    other = uv;
                    tint = vec4(1.0);
                    gl_Position = vec4(uv, 0.0, 1.0);
                }
                """, """
                #version 330
                in vec4 uv;
                in vec2 other;
                in vec3 tint;
                out vec4 color;
                void main() { color = uv + vec4(other, tint.xy); }
                """);
        assertEquals(List.of("VARYING_TYPE_CONVERTED:uv", "VARYING_TYPE_CONVERTED:tint"), repaired.deviations());
        String vertex = repaired.earlier();
        assertTrue(vertex.contains("vec2 chimeraVarying_uv;\nout vec4 uv;\nout vec2 other;"), vertex);
        assertTrue(vertex.contains("float uv = 1.0; return uv;"), "a local of the same name stays: " + vertex);
        assertTrue(vertex.contains("chimeraVarying_uv = vec2(helper());")
                && vertex.contains("other = chimeraVarying_uv;"), vertex);
        assertTrue(vertex.contains("uv = vec4(chimeraVarying_uv, vec4(0));\ntint = vec3(chimeraVarying_tint);\n}"),
                vertex);
        var match = GlslInterfaceScanner.match(GlslInterfaceScanner.scan(vertex, true),
                GlslInterfaceScanner.scan("""
                        #version 330
                        in vec4 uv;
                        in vec2 other;
                        in vec3 tint;
                        void main() { gl_FragColor = uv + vec4(other, tint.xy); }
                        """, false));
        assertTrue(match.executable(), match.deviations().toString());
    }

    /** A scalar output read as a vector, or an output array, is one Iris leaves for its link to refuse. */
    @Test
    void anOutputOfAnotherKindStays() {
        String vertex = """
                #version 330
                out float fade;
                out vec3 corners[2];
                void main() { fade = 1.0; corners[0] = vec3(0.0); corners[1] = vec3(1.0); gl_Position = vec4(0.0); }
                """;
        CrossStageRepair.Result repaired = CrossStageRepair.repair(vertex, """
                #version 330
                in vec2 fade;
                in vec4 corners[2];
                out vec4 color;
                void main() { color = vec4(fade, corners[0].xy); }
                """);
        assertEquals(vertex, repaired.earlier());
        assertTrue(repaired.deviations().isEmpty(), repaired.deviations().toString());
    }

    /**
     * Spring's shadow writes v_glColor in its vertex stage and glColor in its geometry stage, and
     * its fragment reads glColor. Iris pairs the vertex with the geometry stage and the geometry
     * stage with the fragment. The shadow terrain lane runs the three, and so does the entity lane
     * resolved to the program (Iris ProgramId.ShadowEntities falls back to Shadow).
     */
    @Test
    void aShadowProgramRunsItsGeometryStage(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 150 compatibility
                out vec4 v_glColor;
                void main() { v_glColor = gl_Color; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("shadow.gsh"), """
                #version 150 compatibility
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                in vec4 v_glColor[];
                out vec4 glColor;
                void main() {
                    for (int i = 0; i < 3; i++) {
                        glColor = v_glColor[i];
                        gl_Position = gl_in[i].gl_Position;
                        EmitVertex();
                    }
                    EndPrimitive();
                }
                """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 150 compatibility
                in vec4 glColor;
                void main() { gl_FragData[0] = glColor; }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        PackProgramPlan shadow = plan.program("shadow");
        assertTrue(shadow.executable(), shadow.deviations().toString());
        assertTrue(shadow.convertedVertex().contains("layout(location = 0) out vec4 v_glColor;")
                && !shadow.convertedVertex().contains(ShadowStageBuiltins.AUTHORED_SHADOW_ENTRY),
                "the vertex feeds the geometry stage, which converts the clip depth: " + shadow.convertedVertex());
        assertTrue(shadow.convertedGeometry().contains("layout(location = 0) in vec4 v_glColor[];")
                && shadow.convertedGeometry().contains("layout(location = 0) out vec4 glColor;")
                && shadow.convertedGeometry().contains(GeometryStageBridge.EMIT + "();"),
                shadow.convertedGeometry());
        assertTrue(shadow.convertedFragment().contains("layout(location = 0) in vec4 glColor;"),
                shadow.convertedFragment());
        assertEquals(ShadowCullingPlan.State.DISTANCE, plan.shadowCulling().state(),
                "Iris assumes voxelization and culls an unset shadow.culling by distance");
        PackProgramPlan entities = plan.program("shadow_entities");
        assertTrue(entities.executable(), entities.deviations().toString());
        assertTrue(entities.convertedGeometry().contains("layout(location = 0) in vec4 v_glColor[];")
                && entities.convertedFragment().contains("layout(location = 0) in vec4 glColor;"),
                entities.convertedGeometry());
        PackProgramPlan lines = plan.program("shadow_lines");
        assertTrue(lines.executable() && lines.convertedGeometry() != null, lines.deviations().toString());
    }

    /**
     * Iris repairs each pair of adjacent stages, so a geometry stage that reads an input its vertex
     * never declares gets a zeroed vertex output, as a fragment's would. Chimera repaired only the
     * vertex/fragment pair and refused the program (PROGRAM_VARYING_UNMATCHED:v_tint).
     */
    @Test
    void aGeometryInputTheVertexNeverWritesGetsAZeroedOutput(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 150 compatibility
                out vec4 v_glColor;
                void main() { v_glColor = gl_Color; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("shadow.gsh"), """
                #version 150 compatibility
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                in vec4 v_glColor[];
                in vec4 v_tint[];
                out vec4 glColor;
                void main() {
                    for (int i = 0; i < 3; i++) {
                        glColor = v_glColor[i] + v_tint[i];
                        gl_Position = gl_in[i].gl_Position;
                        EmitVertex();
                    }
                    EndPrimitive();
                }
                """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 150 compatibility
                in vec4 glColor;
                void main() { gl_FragData[0] = glColor; }
                """);
        PackProgramPlan shadow = PackProbe.analyze(root).plan().program("shadow");
        assertTrue(shadow.executable(), shadow.deviations().toString());
        assertTrue(shadow.deviations().contains(CrossStageRepair.UNWRITTEN_ZEROED + "v_tint"),
                shadow.deviations().toString());
        assertTrue(shadow.convertedVertex().contains("out vec4 v_tint;"), shadow.convertedVertex());
    }

    /**
     * A geometry stage gains the outputs its fragment reads, zeroed before its emit loop, which keeps
     * them for every vertex it emits. It converts no retyped output: Iris would write it after the
     * last EmitVertex, where it reaches no vertex, so the stage match refuses it.
     */
    @Test
    void aGeometryStageGainsMissingOutputsAndConvertsNone() {
        String geometry = """
                #version 150 compatibility
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                out vec3 tint;
                void main() {
                    for (int i = 0; i < 3; i++) { tint = vec3(1.0); gl_Position = gl_in[i].gl_Position; EmitVertex(); }
                    EndPrimitive();
                }
                """;
        String fragment = """
                #version 150 compatibility
                in vec4 tint;
                flat in float fade;
                void main() { gl_FragData[0] = tint * fade; }
                """;
        CrossStageRepair.Result repaired = CrossStageRepair.repair(geometry, GlslInterfaceScanner.Stage.GEOMETRY,
                fragment, GlslInterfaceScanner.Stage.FRAGMENT);
        assertEquals(List.of(CrossStageRepair.UNWRITTEN_ZEROED + "fade"), repaired.deviations());
        assertTrue(repaired.earlier().contains("flat out float fade;")
                && repaired.earlier().contains("{\nfade = float(0);\n")
                && repaired.earlier().contains("out vec3 tint;"), repaired.earlier());
    }

    /**
     * A tessellation control stage's outputs are arrays, one element per output vertex. The outputs
     * Iris adds are not, so its edit would not compile; the pair stays as written.
     */
    @Test
    void aTessellationControlStageIsLeftAsWritten() {
        String control = """
                #version 400 compatibility
                layout(vertices = 3) out;
                void main() { gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position; }
                """;
        String evaluation = """
                #version 400 compatibility
                layout(triangles) in;
                in vec2 uvTc[];
                void main() { gl_Position = vec4(uvTc[0], 0.0, 1.0); }
                """;
        CrossStageRepair.Result repaired = CrossStageRepair.repair(control, GlslInterfaceScanner.Stage.TESS_CONTROL,
                evaluation, GlslInterfaceScanner.Stage.TESS_EVALUATION);
        assertEquals(control, repaired.earlier());
        assertEquals(List.of(), repaired.deviations());
    }

    /** A host-layout shadow lane reads the per-draw entityId in every stage, its geometry stage too. */
    @Test
    void aHostShadowLaneServesItsIdentitiesToTheGeometryStage(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 150 compatibility
                out vec4 v_glColor;
                void main() { v_glColor = gl_Color; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("shadow.gsh"), """
                #version 150 compatibility
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                uniform int entityId;
                in vec4 v_glColor[];
                out vec4 glColor;
                void main() {
                    for (int i = 0; i < 3; i++) {
                        glColor = v_glColor[i] * float(entityId);
                        gl_Position = gl_in[i].gl_Position;
                        EmitVertex();
                    }
                    EndPrimitive();
                }
                """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 150 compatibility
                in vec4 glColor;
                void main() { gl_FragData[0] = glColor; }
                """);
        PackProgramPlan lightning = PackProbe.analyze(root).plan().program("shadow_lightning");
        assertTrue(lightning.executable(), lightning.deviations().toString());
        assertTrue(lightning.convertedGeometry().contains(UniformRegistry.HOST_ENTITY_ID)
                && !lightning.convertedGeometry().contains("uniform int entityId"), lightning.convertedGeometry());
    }

    /**
     * Iris reads a program's tessellation stages only when the pack lists TESSELLATION_SHADERS
     * (ProgramSet.readProgramSource); without it the program runs vertex to fragment.
     */
    @Test
    void tessellationFilesCountOnlyWithTheFeature(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 150 compatibility
                out vec4 tint;
                void main() { tint = gl_Color; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 150 compatibility
                in vec4 tint;
                void main() { gl_FragData[0] = tint; }
                """);
        Files.writeString(shaders.resolve("shadow.tcs"), "#version 400\nlayout(vertices = 3) out;\nvoid main() {}\n");
        Files.writeString(shaders.resolve("shadow.tes"), "#version 400\nlayout(triangles) in;\nvoid main() {}\n");
        PackProgramPlan plain = PackProbe.analyze(root).plan().program("shadow");
        assertTrue(plain.executable(), "without the feature");
        assertEquals(List.of("fragment", "vertex"), List.copyOf(plain.stages().keySet()));
        Files.writeString(shaders.resolve("shaders.properties"), "iris.features.optional=TESSELATION_SHADERS\n");
        PackProgramPlan tessellated = PackProbe.analyze(root).plan().program("shadow");
        assertTrue(!tessellated.executable() && tessellated.deviations().contains("UNSUPPORTED_PACK_STAGE"),
                tessellated.deviations().toString());
        assertEquals(List.of("fragment", "tess_control", "tess_evaluation", "vertex"),
                List.copyOf(tessellated.stages().keySet()));
    }

    /** A vertex whose version line the post normalizer dropped keeps the storage of its own outputs. */
    @Test
    void anAddedOutputTakesTheVertexsOwnStorage() {
        CrossStageRepair.Result repaired = CrossStageRepair.repair("""
                out vec2 texcoord;
                void main() { texcoord = vec2(0.5); gl_Position = vec4(0.0); }
                """, """
                in vec2 texcoord;
                in float exposure;
                void main() { gl_FragColor = vec4(texcoord, exposure, 1.0); }
                """);
        assertTrue(repaired.earlier().contains("out float exposure;"), repaired.earlier());
    }

    /** In a pack, the repaired shadow is admitted and compiles. */
    @Test
    void bloopsShadowRunsRepaired(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 150 compatibility
                in vec4 mc_Entity;
                out float isflower;
                out vec2 tint;
                void main() {
                    lmtexcoord.xy = gl_MultiTexCoord0.xy;
                    isflower = float(int(mc_Entity.x) == 2001);
                    tint = vec2(0.5);
                    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                }
                """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 150 compatibility
                uniform sampler2D tex;
                in vec2 lmtexcoord;
                in float isflower;
                in vec4 tint;
                void main() { gl_FragData[0] = texture(tex, lmtexcoord) * (1.0 - isflower) * tint; }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackProgramPlan shadow = analysis.plan().program("shadow");
        assertTrue(shadow.executable() && shadow.deviations().contains("VARYING_UNWRITTEN_ZEROED:lmtexcoord")
                        && shadow.deviations().contains("VARYING_TYPE_CONVERTED:tint"),
                shadow.deviations().toString());
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), compiled.failures().toString());
    }
}
