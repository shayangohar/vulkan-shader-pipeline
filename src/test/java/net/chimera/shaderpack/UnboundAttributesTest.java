package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A vertex input Iris binds no data to reads GL's current generic attribute, (0, 0, 0, 1) by
 * default. I Like Vanilla and Spring declare at_velocity, which Iris 1.21.1 never supplies, and
 * every lane refused it, so their entity programs fell back and the frame went vanilla.
 */
final class UnboundAttributesTest {
    @Test
    void anUnboundInputBecomesItsDefaultCutToItsType() {
        String source = """
                attribute vec3 at_velocity;
                in ivec4 packIds;
                in uint packFlags;
                in vec2 packOffset;
                void main() { gl_Position = vec4(at_velocity, 1.0); }
                """;
        assertEquals("""
                const vec3 at_velocity = vec3(0.0);
                const ivec4 packIds = ivec4(0, 0, 0, 1);
                const uint packFlags = 0u;
                const vec2 packOffset = vec2(0.0);
                void main() { gl_Position = vec4(at_velocity, 1.0); }
                """, UnboundAttributes.substitute(source, UnboundAttributes::irisUnbound));
    }

    /** Iris binds these names, a layout fixes what an input reads, and a varying is no input. */
    @Test
    void boundLaidOutAndOutputDeclarationsStay() {
        String source = """
                attribute vec4 mc_Entity;
                in vec4 at_tangent;
                in int blockEntityId;
                layout(location = 3) in vec3 at_velocity;
                out vec3 velocity;
                varying vec2 uv;
                """;
        assertEquals(source, UnboundAttributes.substitute(source, UnboundAttributes::irisUnbound));
    }

    @Test
    void everyLaneServesTheDefault(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        String vertex = """
                #version 120
                attribute vec3 at_velocity;
                varying vec2 uv;
                varying vec3 motion;
                void main() {
                    uv = gl_MultiTexCoord0.xy;
                    motion = at_velocity;
                    gl_Position = ftransform();
                }
                """;
        String fragment = """
                #version 120
                uniform sampler2D texture;
                varying vec2 uv;
                varying vec3 motion;
                void main() { gl_FragData[0] = texture2D(texture, uv) + vec4(motion, 0.0); }
                """;
        for (String program : new String[] {"gbuffers_entities", "gbuffers_block", "gbuffers_terrain"}) {
            Files.writeString(shaders.resolve(program + ".vsh"), vertex);
            Files.writeString(shaders.resolve(program + ".fsh"), fragment);
        }
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                void main() { gl_FragColor = texture2D(colortex0, vec2(0.5)); }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        for (String program : new String[] {"gbuffers_entities", "gbuffers_block", "gbuffers_terrain"}) {
            PackProgramPlan planned = plan.program(program);
            assertTrue(planned != null && planned.executable() && planned.deviations().stream()
                            .noneMatch(value -> value.contains("at_velocity")),
                    program + " must convert with at_velocity unbound: "
                            + (planned == null ? "missing" : planned.deviations()));
        }
    }
}
