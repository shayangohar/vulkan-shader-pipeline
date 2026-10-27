package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world stage that projects with the pack's gbufferProjection (Ebin's and Glimmer's
 * gbuffers_main) writes GL's clip range and converts it once, after its authored entry point,
 * with the host built-ins it reads taken into GL's range too. A stage that reads no pack
 * projection keeps the host's matrices and depth, and ftransform() keeps the host's depth exactly.
 */
final class WorldClipRangeTest {
    private static final String CONVERSION = "gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);";
    private static final String FRAGMENT = """
            #version 330 compatibility
            /* DRAWBUFFERS:0 */
            in vec4 color;
            void main() {
                gl_FragData[0] = color;
            }
            """;

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
            String vertex = executable(analysis, program).convertedVertex();
            assertTrue(vertex.contains("void " + WorldClipRange.AUTHORED_ENTRY + "()")
                            && vertex.contains(WorldClipRange.AUTHORED_ENTRY + "();\n    " + CONVERSION)
                            && occurrences(vertex, "gl_Position.z = ") == 1,
                    program + " must convert GL's clip depth once, after its entry point: " + vertex);
        }
        String block = executable(analysis, "gbuffers_block").convertedVertex();
        assertTrue(block.contains("gl_Position = chimeraGlClip(chimeraEntityFtransform());")
                        && block.contains("chimeraGlClip(ProjMat)[2]"),
                "the host built-ins of a stage in GL's range must take GL's range: " + block);
        String textured = executable(analysis, "gbuffers_textured").convertedVertex();
        assertTrue(!textured.contains("chimeraGlClip") && !textured.contains(WorldClipRange.AUTHORED_ENTRY),
                "a stage that never reads a pack projection keeps the host's range: " + textured);

        // Held glint keeps its one conversion and takes the hand family's ftransform().
        String glint = executable(analysis, "gbuffers_armor_glint").convertedVertex();
        String hand = EntityVertexBridge.handGlintVertex(glint);
        assertTrue(hand.contains("chimeraHandProjection() * ModelViewMat")
                        && occurrences(hand, "gl_Position.z = ") == 1
                        && !hand.contains("chimeraGlClip(chimeraEntityFtransform())"),
                "hand glint in GL's range must not convert twice: " + hand);
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "compilation: " + compiled.failures());
    }

    /**
     * ftransform() in GL's range is chimeraGlClip of the host position, then the final conversion:
     * 0.5 * ((2z - w) + w). Both sums are exact while 2z lies within a factor of two of w, so the
     * host's depth comes back unchanged for every window depth from 0.25 on.
     */
    @Test
    void ftransformKeepsHostDepth() {
        Random random = new Random(862);
        for (int i = 0; i < 1_000_000; i++) {
            float w = (float) Math.scalb(1.0 + random.nextDouble(), random.nextInt(24) - 8);
            float z = w * (0.25F + 0.75F * random.nextFloat());
            float gl = 2.0F * z - w;
            assertEquals(Float.floatToIntBits(z), Float.floatToIntBits(0.5F * (gl + w)),
                    "depth " + z / w + " at w " + w + " changed through GL's range");
        }
    }

    private static PackProgramPlan executable(PackProbe.Analysis analysis, String name) {
        PackProgramPlan plan = analysis.plan().program(name);
        assertTrue(plan != null && plan.executable() && plan.convertedVertex() != null,
                name + " rejected: " + (plan == null ? "missing" : plan.deviations()));
        return plan;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }
}
