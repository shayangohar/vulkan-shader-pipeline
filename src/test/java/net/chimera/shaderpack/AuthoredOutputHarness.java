package net.chimera.shaderpack;

/** Fixture checks for the load-time "program draws nothing" proof. */
public final class AuthoredOutputHarness {
    private AuthoredOutputHarness() {}

    private static final String VERTEX = """
            #version 120
            varying vec4 color;
            void main() { color = gl_Color; gl_Position = ftransform(); }
            """;

    public static void main(String[] args) {
        expect(true, VERTEX, """
                #version 120
                varying vec4 color;
                // discard is the whole selected branch
                void main() {
                    discard;
                }
                """, "leading discard");
        expect(true, VERTEX, "void main(void){/* off */discard;}", "void parameter and comment");
        expect(false, VERTEX, """
                void main() {
                    if (color.a < 0.1) discard;
                    gl_FragData[0] = color;
                }
                """, "conditional discard");
        expect(false, VERTEX, """
                void main() {
                    gl_FragData[0] = vec4(1.0);
                    discard;
                }
                """, "write before discard");
        expect(false, VERTEX, """
                layout(r32ui) uniform uimage2D counter;
                void main() { discard; imageStore(counter, ivec2(0), uvec4(1)); }
                """, "fragment image write");
        expect(false, """
                layout(std430) buffer Voxels { uint cells[]; };
                void main() { cells[0] = 1u; gl_Position = vec4(0.0); }
                """, "void main() { discard; }", "vertex storage write");
        expect(false, VERTEX, null, "missing fragment");
        System.out.println("[chimera] authored-output conformance: PASS");
    }

    private static void expect(boolean expected, String vertex, String fragment, String what) {
        if (AuthoredOutput.producesNothing(vertex, fragment) != expected) {
            throw new AssertionError(what + ": expected producesNothing=" + expected);
        }
    }
}
