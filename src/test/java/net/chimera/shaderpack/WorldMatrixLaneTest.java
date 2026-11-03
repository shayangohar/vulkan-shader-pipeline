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
 * A stage that projects with the pack's gbufferProjection writes GL's clip range.
 */
final class WorldMatrixLaneTest {
    private static final String CONVERSION = "gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);";
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

    /**
     * A world stage that projects with the pack's gbufferProjection (Ebin's and Glimmer's
     * gbuffers_main) writes GL's clip range and converts it once, after its authored entry point,
     * with the host built-ins it reads taken into GL's range too. A stage that reads no pack
     * projection keeps the host's matrices and depth.
     */
    @Test
    void packProjectionStages(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String projected = """
                #version 330 compatibility
                uniform mat4 gbufferProjection;
                out vec4 color;
                void main() {
                    color = gl_Color;
                    gl_Position = gbufferProjection * (gl_ModelViewMatrix * gl_Vertex);
                }
                """;
        for (String program : new String[] {"gbuffers_terrain", "gbuffers_entities", "gbuffers_armor_glint"}) {
            Files.writeString(shaders.resolve(program + ".vsh"), projected);
            Files.writeString(shaders.resolve(program + ".fsh"), FRAGMENT);
        }
        // Reads the pack projection beside ftransform(): the host built-ins take GL's range too.
        Files.writeString(shaders.resolve("gbuffers_block.vsh"), """
                #version 330 compatibility
                uniform mat4 gbufferProjection;
                out vec4 color;
                void main() {
                    color = gl_Color * gbufferProjection[1][1] + gl_ProjectionMatrix[2];
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_block.fsh"), FRAGMENT);
        // Declares the pack projection and never reads it: the host's range, unchanged.
        Files.writeString(shaders.resolve("gbuffers_textured.vsh"), """
                #version 330 compatibility
                uniform mat4 gbufferProjection;
                out vec4 color;
                void main() {
                    color = gl_Color;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_textured.fsh"), FRAGMENT);

        PackProbe.Analysis analysis = PackProbe.analyze(root);
        for (String program : new String[] {"gbuffers_terrain", "gbuffers_entities", "gbuffers_armor_glint"}) {
            String vertex = convertedVertex(analysis, program);
            assertTrue(vertex.contains("void " + WorldClipRange.AUTHORED_ENTRY + "()")
                            && vertex.contains(WorldClipRange.AUTHORED_ENTRY + "();\n    " + CONVERSION)
                            && occurrences(vertex, "gl_Position.z = ") == 1,
                    program + " must convert GL's clip depth once, after its entry point: " + vertex);
        }
        String block = convertedVertex(analysis, "gbuffers_block");
        assertTrue(block.contains("gl_Position = chimeraGlClip(chimeraEntityFtransform());")
                        && block.contains("chimeraGlClip(ProjMat)[2]"),
                "the host built-ins of a stage in GL's range must take GL's range: " + block);
        String textured = convertedVertex(analysis, "gbuffers_textured");
        assertTrue(!textured.contains("chimeraGlClip") && !textured.contains(WorldClipRange.AUTHORED_ENTRY),
                "a stage that never reads a pack projection keeps the host's range: " + textured);

        // Held glint keeps its one conversion and takes the hand family's ftransform().
        String glint = convertedVertex(analysis, "gbuffers_armor_glint");
        String hand = EntityVertexBridge.handGlintVertex(glint);
        assertTrue(hand.contains("chimeraHandProjection() * ModelViewMat")
                        && occurrences(hand, "gl_Position.z = ") == 1
                        && !hand.contains("chimeraGlClip(chimeraEntityFtransform())"),
                "hand glint in GL's range must not convert twice: " + hand);
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "compilation: " + compiled.failures());
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    /**
     * RenderPearl's terrain clamps gl_ProjectionMatrix * view to the frustum and unprojects it with
     * gbufferProjectionInverse alone: reading the inverse takes the stage into GL's range too.
     */
    @Test
    void anInverseProjectionStageRunsInGlRange(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_water.vsh"), """
                #version 330 compatibility
                uniform mat4 gbufferProjectionInverse;
                out vec4 color;
                void main() {
                    vec4 clip = gl_ProjectionMatrix * (gl_ModelViewMatrix * gl_Vertex);
                    color = gbufferProjectionInverse * clamp(clip / clip.w, vec4(-1.0, -1.0, 0.0, 1.0), vec4(1.0));
                    gl_Position = clip;
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_water.fsh"), FRAGMENT);
        String water = convertedVertex(PackProbe.analyze(root), "gbuffers_water");
        assertTrue(water.contains("chimeraGlClip(") && water.contains(WorldClipRange.AUTHORED_ENTRY + "();\n    "
                + CONVERSION) && occurrences(water, "gl_Position.z = ") == 1,
                "a stage that unprojects with gbufferProjectionInverse must run in GL's range: " + water);
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
