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
 * GPU inverse of ModelViewMat in one lane and the uniform in the other did not.
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

    private static String positionLine(PackProbe.Analysis analysis, String name) {
        PackProgramPlan plan = analysis.plan().program(name);
        assertTrue(plan != null && plan.executable() && plan.convertedVertex() != null,
                name + " rejected: " + (plan == null ? "missing" : plan.deviations()));
        String vertex = plan.convertedVertex();
        assertTrue(!vertex.contains("inverse(ModelViewMat)") && vertex.contains("mat3(gbufferModelView) * world"),
                name + " must read the pack's world matrices: " + vertex);
        int start = vertex.indexOf(POSITION);
        assertTrue(start >= 0, name + " lost the authored position: " + vertex);
        return vertex.substring(start, vertex.indexOf(';', start));
    }
}
