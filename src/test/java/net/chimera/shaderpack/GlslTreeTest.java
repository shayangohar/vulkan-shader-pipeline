package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lossless GLSL tree: the declaration shapes the corpus showed, scopes, and edits by range. */
final class GlslTreeTest {
    private static final String SOURCE = """
            #version 450 compatibility
            #extension GL_ARB_shader_texture_lod : enable
            // a leading comment
            precision highp float;
            layout(location = 0) flat out highp vec3 normal, tint[2] = vec3[](vec3(1.0), vec3(0.0)), last;
            uniform float /* inline */ strength; // Bloop ends a declaration with a comment
            uniform sampler2D colortex0;
            struct Light { vec3 position; float radius[2]; } lights[4];
            layout(std140, binding = 0) uniform Frame { mat4 view; layout(offset = 64) vec4 sun; } frame;
            uniform Loose { float loose; };
            layout(local_size_x = 8, local_size_y = 8) in;
            invariant gl_Position;
            float lit(vec3 n, const in float[2] weights);
            float lit(vec3 n, const in float[2] weights) {
                float total = 0.0, k;
                for (int i = 0; i < 2; i++) { total += weights[i] * dot(n, lights[i].position); }
                switch (int(total)) { case 0: k = 1.0; break; default: k = 2.0; }
                do { k *= 0.5; } while (k > 1.0);
                if (total > 1.0) return total; else { Light l; return l.radius[0] * k; }
            }
            void main() {
                #line 40
                normal = vec3(strength) * frame.view[0].xyz + loose;
            }
            """;

    @Test
    void everyShapePrintsAsWritten() {
        GlslTree tree = GlslTree.parse(SOURCE);
        assertEquals(SOURCE, tree.print());
        List<GlslTree.External> externals = tree.externals();
        assertEquals(14, externals.size(), externals.toString());
        assertInstanceOf(GlslTree.Directive.class, externals.get(0));
        assertEquals("#extension GL_ARB_shader_texture_lod : enable", tree.text(externals.get(1).range()));
    }

    @Test
    void qualifiersKeepTheirOrderAndDeclaratorListsSplit() {
        GlslTree tree = GlslTree.parse(SOURCE);
        GlslTree.Declaration outputs = (GlslTree.Declaration) tree.externals().get(3);
        assertEquals(List.of("layout(location = 0)", "flat", "out", "highp"),
                outputs.qualifiers().stream().map(value -> tree.text(value.range())).toList());
        assertEquals("vec3", tree.text(outputs.type().name()));
        assertEquals(List.of("normal", "tint", "last"),
                outputs.declarators().stream().map(value -> tree.text(value.name())).toList());
        GlslTree.Declarator tint = outputs.declarators().get(1);
        assertEquals("[2]", tree.text(tint.arrays().get(0)));
        assertEquals("vec3[](vec3(1.0), vec3(0.0))", tree.text(tint.initializer()));

        GlslTree.Declaration strength = (GlslTree.Declaration) tree.externals().get(4);
        assertEquals("uniform float /* inline */ strength;", tree.text(strength.range()),
                "a declaration ends at its semicolon; the trailing comment is trivia");
        GlslTree.Declaration precision = (GlslTree.Declaration) tree.externals().get(2);
        assertTrue(precision.declarators().isEmpty());
    }

    @Test
    void structsBlocksAndQualifierOnlyLines() {
        GlslTree tree = GlslTree.parse(SOURCE);
        GlslTree.Declaration lights = (GlslTree.Declaration) tree.externals().get(6);
        assertEquals("Light", tree.text(lights.type().struct().name()));
        assertEquals(2, lights.type().struct().members().size());
        assertEquals("lights", tree.text(lights.declarators().get(0).name()));

        GlslTree.InterfaceBlock frame = (GlslTree.InterfaceBlock) tree.externals().get(7);
        assertEquals("Frame", tree.text(frame.name()));
        assertEquals("layout(offset = 64)", tree.text(frame.members().get(1).qualifiers().get(0).range()));
        assertEquals("frame", tree.text(frame.instances().get(0).name()));
        assertTrue(((GlslTree.InterfaceBlock) tree.externals().get(8)).instances().isEmpty());

        GlslTree.Declaration workGroup = (GlslTree.Declaration) tree.externals().get(9);
        assertNull(workGroup.type());
        GlslTree.Declaration invariant = (GlslTree.Declaration) tree.externals().get(10);
        assertNull(invariant.type());
        assertEquals("gl_Position", tree.text(invariant.declarators().get(0).name()));
    }

    @Test
    void functionsParseSignaturesAndStatements() {
        GlslTree tree = GlslTree.parse(SOURCE);
        GlslTree.Function prototype = (GlslTree.Function) tree.externals().get(11);
        assertNull(prototype.body());
        GlslTree.Function lit = (GlslTree.Function) tree.externals().get(12);
        assertEquals("(vec3 n, const in float[2] weights)", tree.text(lit.parameterList()));
        assertEquals(List.of("const", "in"), lit.parameters().get(1).qualifiers().stream()
                .map(value -> tree.text(value.range())).toList());
        assertEquals("float[2]", tree.text(lit.parameters().get(1).type().range()), "a type spans its array sizes");
        assertEquals("const in float[2] weights", tree.text(lit.parameters().get(1).range()));
        List<GlslTree.Statement> body = lit.body().statements();
        assertEquals(5, body.size());
        assertInstanceOf(GlslTree.Declaration.class, body.get(0));
        GlslTree.Control loop = (GlslTree.Control) body.get(1);
        assertEquals("int i = 0;", tree.text(loop.init().range()));
        GlslTree.Control branch = (GlslTree.Control) body.get(4);
        assertEquals(2, branch.bodies().size(), "if keeps its else");
        GlslTree.Function main = (GlslTree.Function) tree.externals().get(13);
        assertInstanceOf(GlslTree.Directive.class, main.body().statements().get(0));
    }

    /** Kappa names a parameter after its lightDir uniform; a block's local hides it until the block closes. */
    @Test
    void namesResolveThroughScopes() {
        String source = """
                uniform vec3 lightDir;
                float f(vec3 lightDir) { return lightDir.x; }
                void main() { vec3 a = lightDir; { vec3 lightDir = a; a = lightDir; } a += lightDir + a.lightDir; }
                """;
        GlslTree tree = GlslTree.parse(source);
        List<Integer> uses = occurrences(tree, "lightDir");
        GlslTree.Binding uniform = tree.resolve(uses.get(0));
        assertInstanceOf(GlslTree.Declarator.class, uniform.node());
        assertInstanceOf(GlslTree.Parameter.class, tree.resolve(uses.get(2)).node(), "the parameter hides it");
        assertEquals(uniform, tree.resolve(uses.get(3)));
        assertEquals(uses.get(4), tree.resolve(uses.get(5)).name(), "the inner local");
        assertEquals(uniform, tree.resolve(uses.get(6)), "the block closed");
        assertNull(tree.resolve(uses.get(7)), "a field is no variable");
        assertNull(tree.resolve(occurrences(tree, "x").get(0)));

        GlslTree initialized = GlslTree.parse("int x = 1;\nvoid main() { { int x = x + 1, y = x; } }\n");
        List<Integer> xs = occurrences(initialized, "x");
        assertEquals(xs.get(0), initialized.resolve(xs.get(2)).name(), "an initializer reads the outer x");
        assertEquals(xs.get(1), initialized.resolve(xs.get(3)).name(), "the next declarator sees the inner x");
    }

    @Test
    void editsReplaceRanges() {
        GlslTree tree = GlslTree.parse("uniform float a = 1.0, b; // keep\nvoid main() { b = a; }\n");
        GlslTree.Declaration declaration = (GlslTree.Declaration) tree.externals().get(0);
        String edited = tree.print(List.of(
                new GlslTree.Edit(declaration.declarators().get(0).initializer(), "2.0"),
                new GlslTree.Edit(declaration.qualifiers().get(0).range(), "const")));
        assertEquals("const float a = 2.0, b; // keep\nvoid main() { b = a; }\n", edited);
        assertThrows(IllegalArgumentException.class, () -> tree.print(List.of(
                new GlslTree.Edit(declaration.range(), ""),
                new GlslTree.Edit(declaration.qualifiers().get(0).range(), ""))));
    }

    @Test
    void aFailureNamesItsLine() {
        GlslParser.ParseFailure failure = assertThrows(GlslParser.ParseFailure.class,
                () -> GlslTree.parse("float a;\nvoid main() {\n    a = (1.0;\n}\n"));
        assertTrue(failure.getMessage().contains("line 3"), failure.getMessage());
    }

    private static List<Integer> occurrences(GlslTree tree, String name) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < tree.tokens().size(); index++) {
            if (tree.tokens().get(index).identifier(name)) result.add(index);
        }
        return result;
    }
}
