package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** File-scope declarations read from the tree, and edits that keep the text around them. */
final class GlslDeclarationsTest {
    @Test
    void variablesNameEachDeclarator() {
        GlslTree tree = GlslTree.parse("""
                layout(location = 1) in highp vec2 mc_midTexCoord;
                uniform float /* fog */ fogStart, fogEnd[2];
                struct S { float a; } s;
                layout(local_size_x = 8) in;
                void main() { float local; }
                """);
        List<GlslDeclarations.Variable> variables = GlslDeclarations.variables(tree);
        assertEquals(List.of("mc_midTexCoord", "fogStart", "fogEnd", "s"),
                variables.stream().map(GlslDeclarations.Variable::name).toList());
        GlslDeclarations.Variable coord = variables.get(0);
        assertEquals("vec2", coord.type());
        assertTrue(coord.qualified("in") && coord.qualified("layout") && coord.plain());
        assertFalse(variables.get(1).plain(), "a declarator list is not plain");
        assertEquals("struct", variables.get(3).type());
    }

    @Test
    void removingALoneDeclarationTakesItsIndentationAndKeepsTheLineBreak() {
        String source = "uniform float a;\r\n\t  in vec2 b; \t\r\nfloat c; in vec3 d; // trailing\nvoid main() {}\n";
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.Variable> variables = GlslDeclarations.variables(tree);
        String edited = GlslDeclarations.apply(source, List.of(
                GlslDeclarations.remove(tree, variables.get(1).declaration()),
                GlslDeclarations.remove(tree, variables.get(3).declaration())));
        assertEquals("uniform float a;\r\n\r\nfloat c;  // trailing\nvoid main() {}\n", edited);
        assertThrows(IllegalArgumentException.class, () -> GlslDeclarations.apply(source, List.of(
                new GlslDeclarations.TextEdit(0, 5, ""), new GlslDeclarations.TextEdit(3, 8, ""))));
    }

    /** The entity check reads uniforms and inputs from the tree: a precision qualifier no longer hides one. */
    @Test
    void entityFeatureCheckSeesEveryDeclarationShape() {
        String main = "\nvoid main() { gl_Position = vec4(strength); }\n";
        IllegalArgumentException custom = assertThrows(IllegalArgumentException.class,
                () -> EntityVertexFeatureCheck.rejectEntityVertexFeatures(
                        "uniform highp float strength;" + main, false, false));
        assertEquals("unsupported entity vertex uniform: strength", custom.getMessage());
        IllegalArgumentException attribute = assertThrows(IllegalArgumentException.class,
                () -> EntityVertexFeatureCheck.rejectEntityVertexFeatures(
                        "in highp vec3 mc_midTexCoord;\nvoid main() { gl_Position = vec4(mc_midTexCoord, 1.0); }\n",
                        false, false));
        assertEquals("unsupported entity attribute: mc_midTexCoord", attribute.getMessage());
        EntityVertexFeatureCheck.rejectEntityVertexFeatures("uniform float unread;\nin vec2 mc_midTexCoord;\n"
                + "void main() { gl_Position = vec4(mc_midTexCoord, 0.0, 1.0); }\n", false, false);
    }

    /** Core-profile names are read from the tree: a precision qualifier or trailing comment no longer hides one. */
    @Test
    void coreProfileDeclarationsAreReadFromTheTree() {
        String prepared = IrisCoreProfile.prepareIrisCoreProfile("""
                #version 330 core
                layout(location = 0) in highp vec3 vaPosition;
                uniform mat4 modelViewMatrix; // host matrix
                void main() { gl_Position = modelViewMatrix * vec4(vaPosition, 1.0); }
                """, null, true, IrisCoreProfile.NO_OVERLAY, true);
        assertEquals("""
                #version 330 core

                 // host matrix
                void main() { gl_Position = gl_ModelViewMatrix * vec4(gl_Vertex.xyz, 1.0); }
                """, prepared);
    }

    /** Post inputs are read from the tree; a replaced one still takes the blank lines before it. */
    @Test
    void postInputsKeepTheirSpansAndLoseTheirLayouts() {
        String converted = PostVertexBridge.convertPostVaryings("""
                #version 120
                uniform sampler2D colortex0;

                  varying vec2 texcoord;
                varying vec4 unused;
                void main() { gl_FragColor = texture2D(colortex0, texcoord); }
                """);
        assertEquals("""
                #version 120
                uniform sampler2D colortex0;
                layout(location = 0) in vec2 texcoord;

                void main() { gl_FragColor = texture2D(colortex0, texcoord); }
                """, converted);
        String normalized = PostVertexBridge.normalizeModernPost("""
                #version 330
                layout(location = 0) flat in vec2 uv;
                layout(location = 0) out vec4 color;
                void main() { color = vec4(uv, 0.0, 1.0); }
                """);
        assertTrue(normalized.contains("\nflat in vec2 uv;") && normalized.contains("layout(location = 0) out vec4"),
                normalized);
    }

    /** A host layout leaves mid-coordinates and tangents unbound however their declarations are written. */
    @Test
    void unboundMaterialAttributesReadAsGlDefaults() {
        String converted = HostLayoutVertexBridge.unboundMaterialAttributes("""
                #version 150
                in vec2 mc_midTexCoord; // Iris attribute
                layout(location = 12) in vec4 at_tangent;
                in vec3 vaNormal;
                void main() { gl_Position = vec4(mc_midTexCoord, at_tangent.w, 1.0); }
                """);
        assertEquals("""
                #version 150
                 // Iris attribute

                in vec3 vaNormal;
                void main() { gl_Position = vec4(vec2(0.0), vec4(0.0, 0.0, 0.0, 1.0).w, 1.0); }
                """, converted);
    }
}
