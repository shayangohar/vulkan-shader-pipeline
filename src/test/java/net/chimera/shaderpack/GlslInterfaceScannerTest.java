package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlslInterfaceScannerTest {
    /**
     * A statement that declares several names, where emit replaces some and leaves others: each
     * name left alone keeps a declaration of its own, with the statement's qualifiers and type.
     * The rewrite appended only the replaced names, so {@code out vec2 a, b;} lost b.
     */
    @Test
    void aNameEmitLeavesAloneKeepsItsDeclaration() {
        String vertex = """
                #version 330
                out vec2 a, b;
                flat out vec3[2] c, d;
                void main() { a = vec2(0.0); b = vec2(1.0); c[0] = vec3(0.0); d[1] = vec3(1.0); }
                """;
        String rewritten = GlslInterfaceScanner.rewrite(vertex, GlslInterfaceScanner.Stage.VERTEX,
                declaration -> switch (declaration.name()) {
                    case "a" -> "layout(location = 0) out vec2 a;\n";
                    case "c" -> "layout(location = 1) flat out vec3 c[2];\n";
                    default -> null;
                });
        assertTrue(rewritten.contains("layout(location = 0) out vec2 a;")
                && rewritten.contains("out vec2 b;"), rewritten);
        assertTrue(rewritten.contains("layout(location = 1) flat out vec3 c[2];")
                && rewritten.contains("flat out vec3[2] d;"), rewritten);

        String geometry = """
                #version 330
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                in vec2 e[], f[];
                void main() { gl_Position = vec4(e[0], f[0]); EmitVertex(); }
                """;
        String perVertex = GlslInterfaceScanner.rewrite(geometry, GlslInterfaceScanner.Stage.GEOMETRY,
                declaration -> declaration.name().equals("e") ? "layout(location = 0) in vec2 e[];\n" : null);
        assertTrue(perVertex.contains("layout(location = 0) in vec2 e[];") && perVertex.contains("in vec2 f[];"),
                perVertex);

        assertEquals(vertex, GlslInterfaceScanner.rewrite(vertex, GlslInterfaceScanner.Stage.VERTEX,
                declaration -> null), "a statement emit leaves alone entirely stays as written");
    }
}
