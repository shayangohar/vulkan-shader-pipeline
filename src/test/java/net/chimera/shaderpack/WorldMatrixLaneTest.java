package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gbufferModelView and gbufferModelViewInverse are the pack's uniforms in every world lane, as in
 * Iris. Ebin rebuilds positions through them, and an enchanted item in a frame draws its glint
 * with depth test EQUAL over the item: the two lanes must place each vertex bit for bit, which a
 * GPU inverse of ModelViewMat in one lane and the uniform in the other did not. The same expression
 * must also evaluate the same in both programs, which GLSL promises only for an invariant position.
 */
final class WorldMatrixLaneTest {
    private static final String POSITION = "vec3 world = mat3(gbufferModelViewInverse) * (ModelViewMat * ";
    private static final String VERTEX = """
            #version 330 compatibility
            uniform mat4 gbufferModelView;
            uniform mat4 gbufferModelViewInverse;
            uniform mat4 gbufferProjection;
            out vec4 color;
            void main() {
                color = gl_Color;
                vec3 world = mat3(gbufferModelViewInverse) * (gl_ModelViewMatrix * gl_Vertex).xyz;
                gl_Position = gbufferProjection * vec4(mat3(gbufferModelView) * world, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 330 compatibility
            /* DRAWBUFFERS:0 */
            in vec4 color;
            void main() {
                gl_FragData[0] = color;
            }
            """;
    /** Insanity's (BSL's) entity and glint vertex: the position is plain ftransform(). */
    private static final String FTRANSFORM_VERTEX = """
            #version 120
            varying vec4 color;
            void main() {
                color = gl_Color;
                gl_Position = ftransform();
            }
            """;
    private static final String LEGACY_FRAGMENT = """
            #version 120
            /* DRAWBUFFERS:0 */
            varying vec4 color;
            void main() {
                gl_FragData[0] = color;
            }
            """;

    /**
     * Under Insanity an enchanted item in a frame and its glint both wrote ftransform() on the same
     * inputs, yet NVIDIA placed them one ulp apart and the glint speckled. Every lane an item or its
     * glint draws on declares its position invariant ahead of any use, the hand's glint included.
     */
    @Test
    void itemAndGlintPositionsAreInvariant(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String[] programs = {"gbuffers_entities", "gbuffers_block", "gbuffers_hand", "gbuffers_armor_glint"};
        for (String program : programs) {
            Files.writeString(shaders.resolve(program + ".vsh"), FTRANSFORM_VERTEX);
            Files.writeString(shaders.resolve(program + ".fsh"), LEGACY_FRAGMENT);
        }
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        for (String program : programs) {
            assertInvariantPosition(program, convertedVertex(analysis, program));
        }
        assertInvariantPosition("hand glint",
                EntityVertexBridge.handGlintVertex(convertedVertex(analysis, "gbuffers_armor_glint")));
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "compilation: " + compiled.failures());
    }

    @Test
    void itemGlintAndCloudsKeepThePackMatrices(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String[] programs = {"gbuffers_entities", "gbuffers_armor_glint", "gbuffers_clouds"};
        for (String program : programs) {
            Files.writeString(shaders.resolve(program + ".vsh"), VERTEX);
            Files.writeString(shaders.resolve(program + ".fsh"), FRAGMENT);
        }
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        String item = positionLine(analysis, "gbuffers_entities");
        assertEquals(item, positionLine(analysis, "gbuffers_armor_glint"),
                "the glint must place the item's vertices with the same expression");
        assertEquals(item, positionLine(analysis, "gbuffers_clouds"),
                "clouds' ModelViewMat carries their scale, never gbufferModelView");
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "compilation: " + compiled.failures());
    }

    private static String convertedVertex(PackProbe.Analysis analysis, String name) {
        PackProgramPlan plan = analysis.plan().program(name);
        assertTrue(plan != null && plan.executable() && plan.convertedVertex() != null,
                name + " rejected: " + (plan == null ? "missing" : plan.deviations()));
        return plan.convertedVertex();
    }

    /** The stage declares gl_Position invariant, and the declaration is its first mention. */
    private static void assertInvariantPosition(String name, String vertex) {
        int declaration = vertex.indexOf(EntityVertexBridge.INVARIANT_POSITION);
        assertTrue(declaration >= 0 && vertex.indexOf("gl_Position") == declaration + "invariant ".length(),
                name + " must declare its position invariant before using it: " + vertex);
    }

    private static String positionLine(PackProbe.Analysis analysis, String name) {
        String vertex = convertedVertex(analysis, name);
        assertTrue(!vertex.contains("inverse(ModelViewMat)") && vertex.contains("mat3(gbufferModelView) * world"),
                name + " must read the pack's world matrices: " + vertex);
        int start = vertex.indexOf(POSITION);
        assertTrue(start >= 0, name + " lost the authored position: " + vertex);
        return vertex.substring(start, vertex.indexOf(';', start));
    }
}
