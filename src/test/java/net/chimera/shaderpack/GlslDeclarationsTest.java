package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    /** Pegasus comments out a sampler declaration above the real one; only the real one takes the binding. */
    @Test
    void samplerBindingsGoOnRealDeclarations() {
        String source = "//uniform sampler2D depthtex0;\nlayout(location = 0) out vec4 color;\n"
                + "uniform sampler2D depthtex0; // depth\nvoid main() { color = texture(depthtex0, vec2(0.0)); }\n";
        assertTrue(FragmentConverter.hasSamplerDeclaration(source, "depthtex0"));
        assertFalse(FragmentConverter.hasSamplerDeclaration("// uniform sampler2D other;\n", "other"));
        assertEquals("//uniform sampler2D depthtex0;\nlayout(location = 0) out vec4 color;\n"
                        + "layout(binding = 3) uniform sampler2D depthtex0; // depth\n"
                        + "void main() { color = texture(depthtex0, vec2(0.0)); }\n",
                FragmentConverter.rewriteSamplerDeclaration(source, "depthtex0", 3));
        assertTrue(FragmentConverter.withCoverageOutput("#version 460\n" + source)
                .contains("layout(location = 1) out float chimeraCoverage;"));
    }

    /** A modern terrain fragment's one output goes with the blank lines before it and the space after it. */
    @Test
    void modernGeometryOutputKeepsThePatternsSpan() {
        assertEquals("#version 330\nlayout(location = 0) out vec4 fragColor;\nuniform float a;\n"
                        + "void main() { fragColor = vec4(a); }\n",
                FragmentConverter.convertModernGeometryOutputs(
                        "#version 330\nuniform float a;\n\n  out vec4 color;\n\nvoid main() { color = vec4(a); }\n"));
    }

    /** Mipmap directives are const bool declarations; a later false removes an earlier true, in source order. */
    @Test
    void mipmapDirectivesAreConstDeclarations() {
        PostTargetPlan plan = PostTargetPlan.parse("composite1", """
                /* RENDERTARGETS: 0 */
                const bool colortex3MipmapEnabled = true; // bloom
                const bool gaux1MipmapEnabled = true;
                const bool colortex3MipmapEnabled = false;
                void main() { gl_FragColor = vec4(0.0); }
                """).plan();
        assertEquals(List.of(4), plan.mipmappedTargets());
    }

    /**
     * Consumed constants go with the blank lines their old pattern took, each span starting where
     * the previous one ended; a format directive inside a comment stays as written.
     */
    @Test
    void consumedConstantsChainTheirSpansAndLeaveComments() {
        String source = "/*\nconst int colortex0Format = RGBA16;\n*/\n    const float sunPathRotation = 0.0;\n\n\n"
                + "            const int shadowMapResolution = 2048;\n\n\nvoid main() {}\n";
        assertEquals("/*\nconst int colortex0Format = RGBA16;\n*/\n\nvoid main() {}\n",
                LegacyGlslConverter.stripUnusedConsumedConsts(source, Map.of()));
    }

    /**
     * A modern terrain interface declaration starts a line and takes the blank lines before it; a
     * second declaration on the line, a layout, an array or a commented one is not one.
     */
    @Test
    void modernTerrainDeclarationsStartALine() {
        String source = "#version 460\nuniform float a;\n\n  flat out int id;\nin vec3 vaPosition; in vec2 skipped;\n"
                + "layout(location = 1) in vec2 laidOut;\nout vec2 pair[2];\n/*\nin vec2 commented;\n*/\n"
                + "void main() {}\n";
        List<TerrainDeclarations.Declaration> declarations =
                TerrainDeclarations.modern(source, TerrainDeclarations.MODERN_STORAGES);
        assertEquals(List.of("id", "vaPosition"),
                declarations.stream().map(TerrainDeclarations.Declaration::name).toList());
        TerrainDeclarations.Declaration id = declarations.get(0);
        assertEquals("\n  flat out int id;", source.substring(id.start(), id.end()));
        assertEquals("flat", id.interpolation());
    }

    /**
     * A shadow attribute goes with its line only when nothing follows it there; uniforms go from
     * their keyword through the whitespace after, leaving a layout before the keyword.
     */
    @Test
    void shadowAttributesTakeTheirLineAndUniformsTheirKeyword() {
        String source = "attribute vec4 mc_Entity;  \r\n  attribute vec2 mc_midTexCoord; // uv\n"
                + "layout(binding = 0) uniform sampler2D tex;\n\nuniform float a, b;\nvoid main() {}\n";
        assertEquals(List.of("mc_Entity"), TerrainDeclarations.shadowAttributes(source).stream()
                .map(TerrainDeclarations.Declaration::name).toList());
        IllegalArgumentException left = assertThrows(IllegalArgumentException.class,
                () -> TerrainVertexBridge.removeShadowAttributes(source, true));
        assertEquals("unsupported shadow attribute declaration", left.getMessage());
        assertEquals("attribute vec4 mc_Entity;  \r\n  attribute vec2 mc_midTexCoord; // uv\n"
                + "layout(binding = 0) void main() {}\n", TerrainVertexBridge.stripShadowUniformDeclarations(source));
    }

    /** A legacy terrain vertex loses its mc_Entity line with the blank lines after it; varyings get locations. */
    @Test
    void legacyTerrainAttributesAndVaryingsAreDeclarations() {
        LegacyGlslConverter.TerrainVertexConversion conversion = TerrainVertexBridge.convertTerrainVertex("""
                #version 120
                attribute float mc_Entity;

                varying vec2 texcoord; // uv
                void main() { texcoord = vec2(mc_Entity); gl_Position = ftransform(); }
                """, null, """
                #version 120
                varying vec2 texcoord;
                void main() { gl_FragColor = vec4(texcoord, 0.0, 1.0); }
                """);
        String converted = conversion.source();
        assertFalse(converted.contains("attribute"), converted);
        assertTrue(converted.contains("}\n\nlayout(location = 0) out vec2 texcoord; // uv\nvoid main() {"), converted);
    }

    /**
     * An entity identity leaves its declaration from the storage keyword through the whitespace
     * after it, other declarators staying; a commented declaration is not one.
     */
    @Test
    void entityIdentityDeclarationsKeepTheirOtherDeclarators() {
        String source = "uniform int entityId, frame = 2;\n\nin float blockEntityId;\n// uniform int entityId;\n"
                + "void main() {}\n";
        assertEquals("int", EntityDeclarations.declaredEntityIdType(source, "entityId"));
        assertEquals("float", EntityDeclarations.declaredEntityIdType(source, "blockEntityId"));
        assertEquals("uniform int frame = 2;// uniform int entityId;\nvoid main() {}\n",
                EntityDeclarations.removeEntityIdDeclarations(source));
    }

    /**
     * Unbound identity attributes become zero constants in place of their line; terrain attributes
     * go with the blank lines before them, mc_Entity and at_midBlock becoming GL's unbound default.
     */
    @Test
    void entityAttributesBecomeTheirUnboundDefaults() {
        assertEquals("const int blockEntityId = 0; // id\nin float entityId = 1.0;\n",
                EntityDeclarations.defaultUnboundIdentityAttributes(
                        "  attribute int blockEntityId; // id\nin float entityId = 1.0;\n"));
        assertEquals("\n\nconst vec2 mc_Entity = vec2(0.0);\nin vec3 vaNormal;\n",
                EntityDeclarations.removeEntityAttributes(
                        "in vec4 at_tangent; \n\n  attribute vec2 mc_Entity;\nin vec3 vaNormal;\n"));
        assertEquals("vec4(MidTexCoord, 0.0, 1.0)", EntityDeclarations.midTexCoordValue("in vec4 mc_midTexCoord;\n"));
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
