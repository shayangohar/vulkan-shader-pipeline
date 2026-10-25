package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        String view = GeometryStageBridge.consumerView(GEOMETRY);
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
                """, null, false);
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
                () -> GeometryStageBridge.convertShadow(GEOMETRY, vertex, fragment, null, false));
        assertTrue(failure.getMessage().contains("vert_survived"), failure.getMessage());
    }
}
