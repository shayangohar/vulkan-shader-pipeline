package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pack geometry stage between the vertex and fragment stages (TASK-648): Alpha Piscium's shadow
 * stage reads unsized per-vertex arrays and Rethinking Voxels' sized ones; both emit what their
 * fragment stage reads.
 */
final class GeometryStageBridgeTest {
    private static final String GEOMETRY = """
            #version 460 compatibility
            layout(triangles) in;
            layout(triangle_strip, max_vertices = 3) out;
            in vec2 vert_texcoord[];
            in uint vert_survived[3];
            in vec3 vert_unused[];
            out vec2 frag_texcoord;
            out uint frag_material;
            void main() {
                if (bool(vert_survived[0] & vert_survived[1] & vert_survived[2])) {
                    for (int i = 0; i < 3; i++) {
                        gl_Position = gl_in[i].gl_Position;
                        frag_texcoord = vert_texcoord[i];
                        frag_material = vert_survived[i];
                        EmitVertex();
                    }
                    EndPrimitive();
                }
            }
            """;

    /** Each per-vertex input reads as one vertex's value; its outer dimension, sized or not, is not its size. */
    @Test
    void perVertexInputsPairWithVertexOutputs() {
        GlslInterfaceScanner.StageInterface geometry =
                GlslInterfaceScanner.scan(GEOMETRY, GlslInterfaceScanner.Stage.GEOMETRY);
        assertTrue(geometry.deviations().isEmpty(), geometry.deviations().toString());
        assertEquals("vec2", geometry.input("vert_texcoord").typeWithArray());
        assertEquals("uint", geometry.input("vert_survived").typeWithArray());
        assertTrue(geometry.input("vert_survived").perVertex());
        assertFalse(geometry.input("vert_unused").referenced());
        GlslInterfaceScanner.StageInterface vertex = GlslInterfaceScanner.scan("""
                out vec2 vert_texcoord;
                out uint vert_survived;
                void main() { vert_texcoord = vec2(0.0); vert_survived = 1u; gl_Position = vec4(0.0); }
                """, true);
        GlslInterfaceScanner.StageInterface fragment = GlslInterfaceScanner.scan("""
                in vec2 frag_texcoord;
                flat in uint frag_material;
                void main() { gl_FragData[0] = vec4(frag_texcoord, float(frag_material), 1.0); }
                """, false);
        GlslInterfaceScanner.ProgramMatch match = GlslInterfaceScanner.match(vertex, geometry, fragment);
        assertTrue(match.executable(), match.deviations().toString());
        assertEquals(Map.of("frag_material", 0, "frag_texcoord", 1), match.locations());
    }

    /** The consumer view declares each input as the vertex stage's consumer reads it, and leaves the body. */
    @Test
    void theConsumerViewReadsLikeAFragmentStage() {
        String view = GeometryStageBridge.consumerView(GEOMETRY, null);
        assertTrue(view.contains("in vec2 vert_texcoord;\n") && view.contains("in uint vert_survived;\n"), view);
        assertTrue(view.contains("frag_texcoord = vert_texcoord[i];"), view);
        GlslInterfaceScanner.StageInterface consumer = GlslInterfaceScanner.scan(view, false);
        assertTrue(consumer.deviations().isEmpty() && consumer.input("vert_survived").referenced(),
                consumer.deviations().toString());
    }

    /**
     * Inputs take the vertex outputs' locations and outputs the fragment inputs', an integer
     * output goes flat as the fragment takes it, an unread input goes, and each emitted vertex's
     * clip depth moves from GL's [-w, w] to Vulkan's [0, w].
     */
    @Test
    void aShadowGeometryStageConverts() {
        LegacyGlslConverter.TerrainVaryingLayout vertex = new LegacyGlslConverter.TerrainVaryingLayout(
                Map.of("vert_survived", "uint", "vert_texcoord", "vec2"),
                Map.of("vert_survived", 0, "vert_texcoord", 1));
        GeometryStageBridge.Conversion conversion = GeometryStageBridge.convertShadow(GEOMETRY, vertex, """
                #version 460 compatibility
                in vec2 frag_texcoord;
                flat in uint frag_material;
                void main() { gl_FragData[0] = vec4(frag_texcoord, float(frag_material), 1.0); }
                """, null, false, false);
        String source = conversion.source();
        assertTrue(source.startsWith("#version 460\n") && !source.contains("compatibility"), source);
        assertTrue(source.contains("layout(location = 1) in vec2 vert_texcoord[];")
                && source.contains("layout(location = 0) in uint vert_survived[];")
                && !source.contains("vert_unused"), source);
        assertTrue(source.contains("layout(location = 1) out vec2 frag_texcoord;")
                && source.contains("layout(location = 0) flat out uint frag_material;"), source);
        assertTrue(source.contains("gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);\n    EmitVertex();")
                && source.contains(ShadowStageBuiltins.SHADOW_EMIT + "();\n")
                && source.indexOf("void " + ShadowStageBuiltins.SHADOW_EMIT) < source.indexOf("void main"), source);
        assertEquals(1, conversion.outputLayout().location("frag_texcoord"));
    }

    /** The consumer view names each entity identity only the fragment stage reads: the stage passes it on. */
    @Test
    void theConsumerViewNamesTheIdentitiesItPassesOn() {
        String view = GeometryStageBridge.consumerView(GEOMETRY, """
                uniform int blockEntityId;
                void main() { gl_FragData[0] = vec4(float(blockEntityId)); }
                """);
        assertTrue(view.endsWith("uniform int blockEntityId;\n") && !view.contains(" entityId;"), view);
    }

    /**
     * On the shadow entity lane the identities the vertex stage reads from EntityIds pass on to
     * the fragment stage, written before each emit, and the stage reads them as the first
     * vertex's, as Iris's EntityPatcher reads iris_entityInfo[0].
     */
    @Test
    void anEntityShadowGeometryStagePassesTheIdentitiesOn() {
        LegacyGlslConverter.TerrainVaryingLayout vertex = new LegacyGlslConverter.TerrainVaryingLayout(
                Map.of("vert_texcoord", "vec2", "chimeraEntityId", "int", "chimeraBlockEntityId", "int"),
                Map.of("vert_texcoord", 0, "chimeraEntityId", 1, "chimeraBlockEntityId", 2));
        GeometryStageBridge.Conversion conversion = GeometryStageBridge.convertShadow("""
                #version 460 compatibility
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                uniform int entityId;
                in vec2 vert_texcoord[];
                out vec2 frag_texcoord;
                out float frag_entity;
                void main() {
                    for (int i = 0; i < 3; i++) {
                        gl_Position = gl_in[i].gl_Position;
                        frag_texcoord = vert_texcoord[i];
                        frag_entity = float(entityId);
                        EmitVertex();
                    }
                    EndPrimitive();
                }
                """, vertex, """
                #version 460
                uniform int blockEntityId;
                in vec2 frag_texcoord;
                in float frag_entity;
                void main() { gl_FragData[0] = vec4(frag_texcoord, frag_entity, float(blockEntityId)); }
                """, null, false, true);
        String source = conversion.source();
        assertTrue(source.contains("layout(location = 1) flat in uint chimeraEntityId[];\n"
                + "layout(location = 2) flat out uint chimeraEntityIdGS;\n")
                && source.contains("layout(location = 2) flat in uint chimeraBlockEntityId[];\n"
                + "layout(location = 3) flat out uint chimeraBlockEntityIdGS;\n"), source);
        assertTrue(source.contains("frag_entity = float(int(chimeraEntityId[0]));"), source);
        assertTrue(source.contains("    chimeraEntityIdGS = chimeraEntityId[0];\n"
                + "    chimeraBlockEntityIdGS = chimeraBlockEntityId[0];\n"
                + "    gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);\n    EmitVertex();"), source);
        assertEquals(3, conversion.outputLayout().location("chimeraBlockEntityId"));
    }

    /** The entity lane's draw matrices are served to the vertex stage alone; a geometry stage's read is refused. */
    @Test
    void aDrawMatrixOnTheEntityLaneIsRefused() {
        LegacyGlslConverter.TerrainVaryingLayout vertex = new LegacyGlslConverter.TerrainVaryingLayout(
                Map.of("vert_survived", "uint", "vert_texcoord", "vec2"),
                Map.of("vert_survived", 0, "vert_texcoord", 1));
        String fragment = """
                #version 460
                in vec2 frag_texcoord;
                flat in uint frag_material;
                void main() { gl_FragData[0] = vec4(frag_texcoord, float(frag_material), 1.0); }
                """;
        String geometry = GEOMETRY.replace("gl_Position = gl_in[i].gl_Position;",
                "gl_Position = gl_ProjectionMatrix * gl_in[i].gl_Position;");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> GeometryStageBridge.convertShadow(geometry, vertex, fragment, null, false, true));
        assertTrue(failure.getMessage().contains("gl_ProjectionMatrix"), failure.getMessage());
    }

    /** Every shadow lane draws triangles; a stage that takes another primitive is refused. */
    @Test
    void aStageTakingLinesIsRefused() {
        LegacyGlslConverter.TerrainVaryingLayout vertex = new LegacyGlslConverter.TerrainVaryingLayout(
                Map.of("vert_survived", "uint", "vert_texcoord", "vec2"),
                Map.of("vert_survived", 0, "vert_texcoord", 1));
        String fragment = """
                #version 460
                in vec2 frag_texcoord;
                flat in uint frag_material;
                void main() { gl_FragData[0] = vec4(frag_texcoord, float(frag_material), 1.0); }
                """;
        String geometry = GEOMETRY.replace("layout(triangles) in;", "layout(lines) in;");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> GeometryStageBridge.convertShadow(geometry, vertex, fragment, null, false, false));
        assertTrue(failure.getMessage().contains("not triangles: lines"), failure.getMessage());
    }

    /** A read input the vertex stage does not feed is refused, as GL's link refuses it. */
    @Test
    void anUnfedInputIsRefused() {
        LegacyGlslConverter.TerrainVaryingLayout vertex = new LegacyGlslConverter.TerrainVaryingLayout(
                Map.of("vert_texcoord", "vec2"), Map.of("vert_texcoord", 0));
        String fragment = """
                #version 460
                in vec2 frag_texcoord;
                void main() { gl_FragData[0] = vec4(frag_texcoord, 0.0, 1.0); }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> GeometryStageBridge.convertShadow(GEOMETRY, vertex, fragment, null, false, false));
        assertTrue(failure.getMessage().contains("vert_survived"), failure.getMessage());
    }

    /**
     * Shrimple's Nether and End shadow pass only voxelizes: shadow.vsh writes no gl_Position there,
     * and shadow.gsh emits nothing. GL runs a vertex stage that leaves the position to the stage after
     * it, as Iris does; Chimera refused the program ("modern terrain vertex main or position is
     * missing"). The vertex stage now writes a zero position for the geometry stage's gl_in.
     */
    @Test
    void aShadowVertexStageMayLeaveThePositionToItsGeometryStage(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 430 compatibility
                out vec2 vert_texcoord;
                void main() { vert_texcoord = gl_MultiTexCoord0.xy; }
                """);
        Files.writeString(shaders.resolve("shadow.gsh"), GEOMETRY.replace("in uint vert_survived[3];\n", "")
                .replace("in vec3 vert_unused[];\n", "")
                .replace("bool(vert_survived[0] & vert_survived[1] & vert_survived[2])", "vert_texcoord[0].x > 2.0")
                .replace("frag_material = vert_survived[i];", "frag_material = 0u;"));
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 430 compatibility
                in vec2 frag_texcoord;
                flat in uint frag_material;
                void main() { gl_FragData[0] = vec4(frag_texcoord, float(frag_material), 1.0); }
                """);
        PackProgramPlan shadow = PackProbe.analyze(root).plan().program("shadow");
        assertTrue(shadow.executable(), shadow.deviations().toString());
        assertTrue(shadow.convertedVertex().contains("gl_Position = vec4(0.0);"), shadow.convertedVertex());
    }

    /**
     * Spring's gbuffers_terrain passes each triangle through a geometry stage that writes flat
     * per-triangle values from atlasSize. Chimera refused the program ("modern terrain varying
     * mismatch: blockID": the vertex stage was paired with the fragment), and the pack's lighting
     * read no normals or lightmap for any block: the terrain drew black. The vertex stage now feeds
     * the geometry stage GL's clip position, which converts what it emits; a geometry stage that
     * reads the host's matrices declares the view block, and the layout serves it there.
     */
    @Test
    void aTerrainGeometryStageRuns(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), """
                #version 450 compatibility
                attribute vec4 mc_Entity;
                out vec2 v_texcoord;
                out vec4 v_viewPos;
                flat out float v_blockID;
                void main() {
                    v_blockID = mc_Entity.x;
                    v_texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    v_viewPos = gl_ModelViewMatrix * gl_Vertex;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_terrain.gsh"), """
                #version 450 compatibility
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                uniform ivec2 atlasSize;
                in vec2 v_texcoord[];
                in vec4 v_viewPos[];
                flat in float v_blockID[];
                out vec2 texcoord;
                flat out float blockID;
                flat out int textureResolution;
                void main() {
                    vec2 size = abs(v_texcoord[0] - v_texcoord[1]) / distance(v_viewPos[0], v_viewPos[1]);
                    textureResolution = int(max(atlasSize.x * size.x, atlasSize.y * size.y));
                    for (int i = 0; i < 3; ++i) {
                        texcoord = v_texcoord[i];
                        blockID = v_blockID[i];
                        gl_Position = i == 3 ? gl_ModelViewProjectionMatrix * v_viewPos[i] : gl_in[i].gl_Position;
                        EmitVertex();
                    }
                    EndPrimitive();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
                #version 450 compatibility
                uniform sampler2D tex;
                in vec2 texcoord;
                flat in float blockID;
                flat in int textureResolution;
                void main() {
                    gl_FragData[0] = texture(tex, texcoord) * blockID * float(textureResolution);
                }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackProgramPlan terrain = analysis.plan().program("gbuffers_terrain");
        assertTrue(terrain.executable(), terrain.deviations().toString());
        String geometry = terrain.convertedGeometry();
        assertTrue(geometry.contains("uniform ViewUBO") && geometry.contains("chimeraGlClip(MVP)")
                && geometry.contains("vec4 chimeraGlClip(vec4 clip)")
                && geometry.contains("gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);\n    EmitVertex();")
                && geometry.contains("ChimeraTerrainPackUniforms"), geometry);
        assertTrue(terrain.convertedVertex().contains("vec4 chimeraGlClip(vec4 clip)")
                && !terrain.convertedVertex().contains(WorldClipRange.AUTHORED_ENTRY), terrain.convertedVertex());
        PackPipelines.PreparedPipeline prepared = PackPipelines.prepare(terrain, analysis.plan().advancedResources(),
                UniformRegistry.Stage.GEOMETRY, terrain.convertedVertex());
        PackPipelines.prepareLayout(prepared, terrain.name(), analysis.plan().advancedResources());
        assertNull(PackCompileTest.compile(prepared.vertex(), true), prepared.vertex());
        assertNull(PackCompileTest.compileBetween(prepared.between().geometry(), "geometry"),
                prepared.between().geometry());
        assertNull(PackCompileTest.compile(prepared.fragment(), false), prepared.fragment());
    }
}
