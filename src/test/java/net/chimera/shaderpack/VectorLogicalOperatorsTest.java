package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shrimple's POM check joins boolean vectors with ||, which NVIDIA's GL compiler takes and GLSL does not. */
final class VectorLogicalOperatorsTest {
    private static final String HEADER = """
            #version 430
            layout(location = 0) flat in mat2 atlasBounds;
            layout(location = 0) out vec4 color;
            """;

    @Test
    void anyOfAnOrOfVectorsTakesEachVector() {
        String source = HEADER + """
                void main() {
                    bool a = any(
                        isnan(atlasBounds[0]) || isinf(atlasBounds[0]) ||
                        isnan(atlasBounds[1]) || isinf(atlasBounds[1])
                    );
                    bool b = all(lessThan(atlasBounds[0], vec2(1.0)) && greaterThan(atlasBounds[1], vec2(0.0)));
                    color = vec4(a || b ? 1.0 : 0.0);
                }
                """;
        String distributed = VectorLogicalOperators.distribute(source);
        assertTrue(distributed.contains("bool a = (any(isnan(atlasBounds[0])) || any(isinf(atlasBounds[0]))"
                + " || any(isnan(atlasBounds[1])) || any(isinf(atlasBounds[1])));"), distributed);
        assertTrue(distributed.contains("bool b = (all(lessThan(atlasBounds[0], vec2(1.0)))"
                + " && all(greaterThan(atlasBounds[1], vec2(0.0))));"), distributed);
        assertNull(PackCompileTest.compile(source, false), "the stage must compile as Iris's driver compiles it");
    }

    /** Scalars joined by ||, an and inside any(), and operands of unknown type are left to the compiler. */
    @Test
    void anythingElseStays() {
        String source = HEADER + """
                uniform bvec2 mask;
                void main() {
                    float x = atlasBounds[0].x;
                    bool a = isnan(x) || isinf(x);
                    bool b = any(lessThan(atlasBounds[0], vec2(1.0))) || any(isnan(atlasBounds[1]));
                    bool c = any(isnan(atlasBounds[0]) && isinf(atlasBounds[0]));
                    bool d = any(mask || isnan(atlasBounds[1]));
                    bool e = any(isnan(atlasBounds[0]));
                    color = vec4(a || b || c || d || e ? 1.0 : 0.0);
                }
                """;
        assertEquals(source, VectorLogicalOperators.distribute(source));
    }
}
