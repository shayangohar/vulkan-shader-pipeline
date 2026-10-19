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

/** The translation defects the widened pack corpus found, each in the pack that showed it. */
final class CorpusCompileFixesTest {
    /** Kappa names a parameter after its lightDir uniform; the parameter and a local hide the uniform. */
    @Test
    void aParameterOrLocalNamedLikeAUniformHidesIt() {
        String source = """
                float f(vec3 lightDir) { return lightDir.x; }
                void main() { vec3 a = lightDir; { vec3 lightDir = a; a = lightDir; } a += lightDir + a.lightDir; }
                """;
        assertEquals("""
                float f(vec3 lightDir) { return lightDir.x; }
                void main() { vec3 a = u.lightDir; { vec3 lightDir = a; a = lightDir; } a += u.lightDir + a.lightDir; }
                """, GlslScopedReferences.replace(source, Map.of("lightDir", "u.lightDir")));
    }

    /** RedHat writes gl_FrontColor and gl_TexCoord[0]; Vulkan has no such built-in varyings. */
    @Test
    void legacyColourAndTexCoordVaryingsBecomeOrdinaryOnes() {
        String vertex = LegacyShaderNormalizer.normalize("""
                #version 120
                void main() { gl_FrontColor = gl_Color; gl_TexCoord[0] = gl_MultiTexCoord0; }
                """, true).source();
        assertTrue(vertex.contains("varying vec4 chimera_FrontColor;"), vertex);
        assertTrue(vertex.contains("chimera_FrontColor = gl_Color") && vertex.contains("chimera_TexCoord0 ="),
                vertex);
        String fragment = LegacyShaderNormalizer.normalize("""
                #version 120
                void main() { gl_FragColor = gl_Color * gl_TexCoord[0]; }
                """, false).source();
        assertTrue(fragment.contains("chimera_FrontColor * chimera_TexCoord0"), fragment);
    }

    /** I Like Vanilla declares varyings through #define in_out in; Bloop defines packSnorm4x8 at 1.40. */
    @Test
    void qualifierMacrosExpandAndLateBuiltinsAreRenamedByVersion() {
        String fragment = LegacyShaderNormalizer.normalize("""
                #version 330
                #define in_out in
                in_out vec2 texcoord;
                void main() {}
                """, false).source();
        assertTrue(fragment.contains("in vec2 texcoord;") && !fragment.contains("in_out"), fragment);
        String bloop = LegacyShaderNormalizer.normalize("""
                #version 140
                uint packSnorm4x8(vec4 x) { return 0u; }
                void main() { uint p = packSnorm4x8(vec4(0.0)); }
                """, false).source();
        assertTrue(bloop.contains("uint chimera_packSnorm4x8(vec4 x)")
                && bloop.contains("= chimera_packSnorm4x8(vec4(0.0))"), bloop);
        String tanh = "#version 130\nfloat tanh(float x) { return x; }\nvoid main() {}\n";
        assertFalse(LegacyShaderNormalizer.normalize(tanh, false).source().contains("chimera_tanh"),
                "a built-in the authored version already has stays as written");
    }

    /** Mellow writes its own Normal and Tangent, which the entity preamble also declares or serves. */
    @Test
    void packOutputsNamedLikeHostInputsAreRenamed() {
        String renamed = GlslVertexPreambles.renameHostNameCollisions(
                "out vec3 Normal;\nflat out vec4 Tangent;\nvoid main() { Normal = vec3(0.0); Tangent = vec4(0.0); }");
        assertTrue(renamed.contains("out vec3 chimera_pack_Normal;")
                && renamed.contains("flat out vec4 chimera_pack_Tangent;")
                && renamed.contains("chimera_pack_Tangent = vec4(0.0)"), renamed);
        String read = "in vec4 Tangent;\nvoid main() { vec4 t = Tangent; }";
        assertEquals(read, GlslVertexPreambles.renameHostNameCollisions(read), "an input Tangent stays the host's");
    }

    /** Bloop ends its unused uniform declarations with a comment; precision qualifiers are allowed. */
    @Test
    void unreadUniformsGoWhateverFollowsTheSemicolon() {
        String stripped = LegacyGlslConverter.stripUnusableUniformDeclarations("""
                uniform float maxLuminance;      // absolute
                uniform highp float spare;
                uniform float used;
                void main() { float x = used; }
                """, List.of());
        assertFalse(stripped.contains("maxLuminance") || stripped.contains("spare"), stripped);
        assertTrue(stripped.contains("uniform float used;"), stripped);
        assertTrue(UniformRegistry.isLocallyDeclared("vec3 a, lightDir = vec3(0.0);", "lightDir"),
                "a later name of a declarator list is declared");
    }

    /** Spring reads a sampler in a global initializer; Noble reads one inside a macro body. */
    @Test
    void globalInitializersAndUsedMacroBodiesReadSamplers() {
        Set<String> live = GlslResourceUsage.liveSamplers("""
                uniform sampler2D colortex4;
                uniform sampler2D colortex5;
                uniform sampler2D colortex6;
                #define SAMPLE(uv) texture(colortex5, uv)
                #define UNUSED texture(colortex6, vec2(0.0))
                vec2 texel = 1.0 / vec2(textureSize(colortex4, 0));
                void main() { vec4 c = SAMPLE(texel); }
                """);
        assertEquals(Set.of("colortex4", "colortex5"), live);
    }

    /** Noble defines projectionMatrix as a macro; Iris's core name never reaches the transformer. */
    @Test
    void aCoreNameDefinedAsAMacroIsThePacks() {
        String source = """
                #version 330 core
                #define projectionMatrix gbufferProjection
                void main() { vec4 p = projectionMatrix[0]; }
                """;
        String prepared = IrisCoreProfile.prepareIrisCoreProfile(source, null, false, "ivec2(0)", true);
        assertFalse(prepared.contains("gl_ProjectionMatrix"), prepared);
    }

    /** I Like Vanilla writes integer data through a modern uvec4 output. */
    @Test
    void integerModernOutputsAreRouted() {
        PostTargetPlan.ParseResult parsed = PostTargetPlan.parse("composite", """
                #version 330
                /* RENDERTARGETS: 3 */
                layout(location = 0) out uvec4 packedData;
                void main() { packedData = uvec4(1u); }
                """);
        assertEquals(List.of(3), parsed.plan().targetSlots(), parsed.deviations().toString());
    }

    /** Insanity's RGB16 and Bloop's R16 targets have Vulkan images: RGB16 pads its alpha. */
    @Test
    void sixteenBitNormalizedTargetsAllocate(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "colortex6Format=RGB16\n");
        PackConfig.PackConfigData config = PackConfig.parse(List.of(new PackProgram("deferred1",
                "#version 120\nconst int colortex3Format = R16;\nconst int colortex4Format = RG16;",
                shaders.resolve("deferred1.fsh"), null, null)), shaders);
        assertEquals(Map.of(3, 70, 4, 77, 6, 91), config.colortexFormats(), "R16, RG16 and RGBA16 UNORM");
        assertEquals(Set.of(6), config.paddedAlphaTargets());
        assertTrue(config.deviations().stream().noneMatch(value -> value.startsWith("POST_TARGET_FORMAT_UNSUPPORTED")),
                config.deviations().toString());
    }

    /** Noble's composite99.csh runs before composite99's draw; it is not a stage of that program. */
    @Test
    void passComputesShareTheirPassName() {
        assertTrue(PassComputePlan.isPassComputeProgram("composite99"));
        assertTrue(PassComputePlan.isPassComputeProgram("deferred1"));
        assertTrue(PassComputePlan.isPassComputeProgram("prepare"));
        assertFalse(PassComputePlan.isPassComputeProgram("gbuffers_terrain"));
        assertFalse(PassComputePlan.isPassComputeProgram("final"));
    }
}
