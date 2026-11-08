package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shrimple's RTX profile chooses its ping-pong history sampler with ?:, which GLSL forbids for opaque types. */
final class OpaqueSelectionsTest {
    private static final String DECLARATIONS = """
            uniform sampler2D texDiffuseRT;
            uniform sampler2D texDiffuseRT_alt;
            uniform bool altFrame;
            """;

    @Test
    void theChoiceMovesOutOfTheCall() {
        String source = DECLARATIONS + """
                void main() {
                    vec4 old = textureLod(altFrame ? texDiffuseRT : texDiffuseRT_alt, uv, 0).rgba;
                    vec3 near = texelFetch(altFrame && near ? texDiffuseRT_alt : texDiffuseRT, ivec2(0), 0).rgb;
                }
                """;
        String hoisted = OpaqueSelections.hoist(source);
        assertTrue(hoisted.contains(
                "((altFrame) ? textureLod(texDiffuseRT, uv, 0) : textureLod(texDiffuseRT_alt, uv, 0)).rgba"), hoisted);
        assertTrue(hoisted.contains("((altFrame && near) ? texelFetch(texDiffuseRT_alt, ivec2(0), 0)"
                + " : texelFetch(texDiffuseRT, ivec2(0), 0)).rgb"), hoisted);
    }

    /** A conditional of values, or of opaque names inside a longer expression, is GLSL already. */
    @Test
    void anyOtherConditionalStays() {
        String source = DECLARATIONS + """
                void main() {
                    float a = max(altFrame ? 1.0 : 0.0, 0.5);
                    vec4 b = texture(texDiffuseRT, altFrame ? uv : uv2);
                }
                """;
        assertEquals(source, OpaqueSelections.hoist(source));
    }
}
