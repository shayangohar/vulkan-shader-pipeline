package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core-profile uniforms Iris serves per stage (VanillaCoreTransformer, SodiumCoreTransformer,
 * CompositeCoreTransformer), and the initializer an unset uniform reads. Alpha Piscium declares
 * {@code uniform mat4 textureMatrix = mat4(1.0)} in its compatibility passes, where Iris never
 * sets it; Chimera refused the name in 137 programs.
 */
final class IrisCoreUniformsTest {
    @Test
    void eachCoreTransformerServesItsOwnNames() {
        assertTrue(served(IrisCoreProfile.Patch.VANILLA, false, true).contains("textureMatrix"));
        assertFalse(served(IrisCoreProfile.Patch.SODIUM, false, true).contains("textureMatrix"));
        assertTrue(served(IrisCoreProfile.Patch.SODIUM, true, true).contains("textureMatrix"));
        assertEquals(Set.of("modelViewMatrix", "modelViewMatrixInverse", "projectionMatrix",
                "projectionMatrixInverse", "textureMatrix"), served(IrisCoreProfile.Patch.COMPOSITE, true, true));
        assertEquals(Set.of(), served(IrisCoreProfile.Patch.COMPOSITE, false, true));
        assertEquals(Set.of(), served(IrisCoreProfile.Patch.VANILLA, true, false));
        assertTrue(served(IrisCoreProfile.Patch.LINES, false, false).contains("chunkOffset"));
    }

    private static Set<String> served(IrisCoreProfile.Patch patch, boolean vertexStage, boolean coreMode) {
        return IrisCoreProfile.servedUniforms(patch, vertexStage, coreMode);
    }

    /** Sodium draws terrain, water and shadow terrain; every other world program is vanilla. */
    @Test
    void irisPatchesFollowTheProgramFamily() {
        UniformRegistry.Stage geometry = UniformRegistry.Stage.GEOMETRY;
        for (String sodium : List.of("gbuffers_terrain", "gbuffers_water", "shadow")) {
            assertEquals(IrisCoreProfile.Patch.SODIUM, IrisCoreProfile.patchFor(sodium, geometry, false), sodium);
        }
        for (String vanilla : List.of("shadow_entities", "shadow_block", "shadow_lines", "gbuffers_block")) {
            assertEquals(IrisCoreProfile.Patch.VANILLA, IrisCoreProfile.patchFor(vanilla, geometry, false), vanilla);
        }
        assertEquals(IrisCoreProfile.Patch.COMPOSITE,
                IrisCoreProfile.patchFor("composite", UniformRegistry.Stage.POST, false));
    }

    /** A pack's own line program serves its declared core uniforms, initialized or not (VanillaCoreTransformer). */
    @Test
    void aLineVertexLosesAnInitializedCoreUniform() {
        String line = IrisCoreProfile.prepareLineVertex("""
                #version 330 core
                uniform mat4 textureMatrix = mat4(1.0);
                uniform vec3 chunkOffset;
                in vec3 vaPosition;
                void main() { gl_Position = textureMatrix * vec4(vaPosition + chunkOffset, 1.0); }
                """, null, true);
        assertFalse(line.contains("uniform mat4") || line.contains("uniform vec3"), line);
        assertTrue(line.contains("gl_TextureMatrix[0] * vec4(gl_Vertex.xyz + vec3(0.0), 1.0)"), line);
    }

    /**
     * A constant a pass or compute stage reads follows Iris's types: a declaration of another
     * type is one Iris disables, reading its initializer or zero, and Iris sets an int declared
     * bool. Every compute program is built with FogMode.OFF.
     */
    @Test
    void passAndComputeConstantsFollowIrisTypes() {
        String post = ServedUniforms.servePostValues("""
                uniform float fogShape = 2.0;
                uniform bool fogMode;
                uniform ivec4 blendFunc;
                void main() { float x = fogShape + float(fogMode) + float(blendFunc.x); }
                """, null);
        assertTrue(post.contains("float x = (2.0) + float(bool(0)) + float(ivec4(0, 0, 0, 0).x);"), post);
        String compute = ServedUniforms.computeSource("""
                #version 450
                uniform int fogMode, fogShape;
                void main() { int x = fogMode + fogShape; }
                """, name -> UniformRegistry.irisSets(name, Map.of()));
        assertTrue(compute.contains("int x = 0 + -1;"), compute);
    }

    @Test
    void anUnsetUniformReadsItsInitializerAndASetOneKeepsItsDeclaration() {
        ServedUniforms.Initialized initialized = ServedUniforms.readInitializers("""
                #version 460 compatibility
                uniform mat4 textureMatrix = mat4(1.0);
                uniform float viewWidth = 1.0, unsetScale = 2.0, unsetPlain;
                void main() {}
                """, name -> UniformRegistry.irisSets(name, Map.of()));
        assertEquals(List.of("textureMatrix", "unsetScale"), initialized.names());
        assertTrue(initialized.source().contains("const mat4 textureMatrix = mat4(1.0);"), initialized.source());
        assertTrue(initialized.source().contains("const float unsetScale = 2.0;\nuniform float viewWidth = 1.0, "
                + "unsetPlain;"), initialized.source());
    }

    /** A compatibility pass, and a core one's fragment, read the initializer; a core vertex reads the identity. */
    @Test
    void aCompositeReadsTheIdentityOnlyWhereCompositeCoreTransformerServesIt(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String fragment = """
                uniform sampler2D colortex0;
                uniform mat4 textureMatrix = mat4(1.0);
                in vec2 uv;
                layout(location = 0) out vec4 color;
                void main() { color = textureMatrix * texture(colortex0, uv); }
                """;
        Files.writeString(shaders.resolve("composite.vsh"), """
                #version 330 core
                in vec3 vaPosition;
                in vec2 vaUV0;
                uniform mat4 textureMatrix;
                out vec2 uv;
                void main() { uv = (textureMatrix * vec4(vaUV0, 0.0, 1.0)).xy; gl_Position = vec4(vaPosition, 1.0); }
                """);
        Files.writeString(shaders.resolve("composite.fsh"), "#version 330 core\n" + fragment);
        Files.writeString(shaders.resolve("composite1.fsh"), "#version 460 compatibility\n" + fragment);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        for (String name : List.of("composite", "composite1")) {
            PackProgramPlan post = analysis.plan().program(name);
            assertTrue(post.executable() && post.deviations().contains("UNIFORM_UNSET_INITIALIZER:textureMatrix")
                    && post.convertedFragment().contains("const mat4 textureMatrix = mat4(1.0);"),
                    name + " " + post.deviations());
        }
        PackProgramPlan core = analysis.plan().program("composite");
        assertFalse(core.convertedVertex().contains("textureMatrix"), core.convertedVertex());
    }

    /** Outside core mode no Iris transformer names modelViewMatrix: a declared one is unset, so zero. */
    @Test
    void aCompatibilityWorldStageReadsADeclaredCoreMatrixAsZero(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_basic.vsh"), """
                #version 120
                uniform mat4 modelViewMatrix;
                void main() { gl_Position = gl_ProjectionMatrix * modelViewMatrix * gl_Vertex; }
                """);
        Files.writeString(shaders.resolve("gbuffers_basic.fsh"), """
                #version 120
                void main() { gl_FragData[0] = vec4(1.0); }
                """);
        PackProgramPlan basic = PackProbe.analyze(root).plan().program("gbuffers_basic");
        assertTrue(basic.executable() && basic.deviations().contains("UNIFORM_UNSET_ZERO:modelViewMatrix"),
                basic.deviations().toString());
    }
}
