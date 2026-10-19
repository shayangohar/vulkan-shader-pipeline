package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The translation defects the option sweep found: each a pack option a player can set in the settings screen. */
final class OptionSweepFixesTest {
    /** Complementary WATER_FOG_MULT: the macro is defined inside a function body, behind an #if. */
    @Test
    void aDefineInsideAFunctionReachesTheCompiler(@TempDir Path dir) {
        String source = """
                #version 120
                #define MULT 25
                float fog(float d) {
                    #if MULT != 100
                        #define MULT_M MULT * 0.01;
                        d *= MULT_M;
                    #endif
                    return d;
                }
                void main() { gl_FragColor = vec4(fog(1.0)); }
                """;
        var result = ShaderSourcePreprocessor.prepare(dir, dir.resolve("composite1.fsh"), source,
                PackEngineDefines.forPack(Map.of()), Set.of());
        assertTrue(result.successful(), result.deviations().toString());
        assertTrue(result.source().contains("d *= 25 * 0.01;"), result.source());
    }

    /**
     * Bliss LPV_VL_FOG_ILLUMINATION with LPV off: composite2.fsh defines IS_LPV_ENABLED itself, so
     * composite3 samples texLpv1, which no image backs. Iris assigns no unit to a name it does not
     * serve, so the sampler3D stays on unit 0 beside the first colortex sampler2D. GL refuses a draw
     * with two sampler types on one unit (INVALID_OPERATION), so Iris draws nothing for composite3.
     * A pack bug Iris shares: Chimera refuses the program and the post chain skips the stage.
     */
    @Test
    void aSamplerIrisServesNothingStillRefusesTheProgram() {
        UniformRegistry.ProgramInterface plan = UniformRegistry.plan("""
                uniform sampler2D colortex0;
                uniform sampler3D texLpv1;
                void main() { gl_FragColor = texture2D(colortex0, vec2(0.0)) + texture3D(texLpv1, vec3(0.0)); }
                """, UniformRegistry.Stage.POST);
        assertTrue(!plan.executable(), plan.deviations().toString());
    }

    /**
     * A post program's fragment reads an input its vertex never writes (Bliss composite3 reads an
     * exposure its vertex writes only under another option). Iris adds a zeroed output; the input
     * reads as zero.
     */
    @Test
    void aPostInputNoVertexWritesReadsAsZero() {
        String vertex = """
                #version 120
                varying vec2 texcoord;
                void main() { texcoord = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """;
        String fragment = """
                #version 120
                varying vec2 texcoord;
                flat varying float exposure;
                void main() { gl_FragData[0] = vec4(texcoord, exposure, 1.0); }
                """;
        var match = GlslInterfaceScanner.match(GlslInterfaceScanner.scan(vertex, true),
                GlslInterfaceScanner.scan(fragment, false), true);
        assertTrue(match.executable(), match.deviations().toString());
        assertEquals(List.of("VARYING_UNWRITTEN_ZEROED:exposure"), match.deviations());
        String converted = PostVertexBridge.convertPostVaryings(fragment,
                new PostVertexBridge.PostVaryingLayout(match.locations(), Map.of("texcoord", "vec2")));
        assertTrue(converted.contains("float exposure = 0.0;"), converted);
        assertTrue(!GlslInterfaceScanner.match(GlslInterfaceScanner.scan(vertex, true),
                GlslInterfaceScanner.scan(fragment, false)).executable(), "other stages still refuse it");
    }

    /**
     * Photon's sampler debug view declares {@code uniform sampler2D DEBUG_SAMPLER} with the macro
     * defined as colortex1: the preprocessor expands the declaration, so it is colortex1.
     */
    @Test
    void aSamplerDeclaredThroughAMacroIsTheSamplerItNames() {
        String source = """
                #define DEBUG_SAMPLER colortex1
                uniform sampler2D DEBUG_SAMPLER;
                void main() { gl_FragColor = texelFetch(DEBUG_SAMPLER, ivec2(0), 0); }
                """;
        String expanded = GlslMacroAliases.expandSamplerAliases(source);
        assertTrue(expanded.contains("uniform sampler2D colortex1;"), expanded);
        assertTrue(expanded.contains("texelFetch(colortex1,"), expanded);
    }

    /**
     * Bliss RAYMARCH_CLOUDS_WITH_FOG: GetVolumetricFog samples the global {@code shadow} sampler,
     * then declares a {@code float shadow} in a later block. The local hides the sampler only
     * from its declaration to the end of that block.
     */
    @Test
    void aLocalHidesASamplerOnlyWhereItIsInScope() {
        String source = """
                #version 460
                uniform sampler2DShadow shadow;
                layout(location = 0) out vec4 color;
                void main() {
                    float lit = texture(shadow, vec3(0.5)).x;
                    {
                        float shadow = 2.0;
                        lit *= shadow;
                    }
                    color = vec4(lit);
                }
                """;
        assertTrue(GlslResourceUsage.analyze(source).referencedSamplers().contains("shadow"),
                "the read before the local's block is the sampler");
    }

    /**
     * Photon: its TAAU program ends in a stray #endif, and with cloud shadows off its prepare stage
     * reaches an #error no switch disables. Iris's preprocessor reports both and goes on; so does Chimera.
     */
    @Test
    void aStrayEndifAndAnErrorDirectiveAreReportedNotFatal(@TempDir Path dir) {
        String source = """
                #version 400 compatibility
                void main() { gl_FragColor = vec4(1.0); }
                #ifndef CLOUD_SHADOWS
                #error "This program should be disabled if Cloud Shadows are disabled"
                #endif
                #endif
                """;
        var result = ShaderSourcePreprocessor.prepare(dir, dir.resolve("prepare.fsh"), source,
                PackEngineDefines.forPack(Map.of()), Set.of());
        assertTrue(result.successful(), result.deviations().toString());
        assertEquals(List.of("PREPROCESSOR_DIRECTIVE_REPORTED:error", "PREPROCESSOR_UNMATCHED_CONDITIONAL:endif"),
                result.deviations());
        assertTrue(result.deviations().stream().allMatch(ShaderSourcePreprocessor::isWarning));
        assertTrue(!result.source().contains("#error"), result.source());
    }

    /**
     * Bliss SAND_STORMS off writes {@code uniform.float.sandStorm = 0.0;}. Iris cannot parse the
     * semicolon, logs it and declares nothing, so programs run reading it as zero; a value Chimera
     * cannot compile for want of a feature (an unknown function) still fails closed.
     */
    @Test
    void aCustomValueIrisCannotParseReadsAsUnset() {
        PackRuntimeSettings settings = PackRuntimeSettings.build(List.of(
                PackRuntimeSettings.declaration(true, "float", "sandStorm", "0.0;"),
                PackRuntimeSettings.declaration(true, "float", "wrong", "sinh(frameTimeCounter)")),
                Map.of(), List.of());
        assertTrue(settings.rejected().containsAll(List.of("sandStorm", "wrong")), settings.rejected().toString());
        assertTrue(!settings.customDescriptors().containsKey("sandStorm"),
                "a value that does not parse is left unset, as Iris leaves it");
        assertEquals(UniformRegistry.Availability.REJECTED, settings.customDescriptors().get("wrong").availability());
    }

    /**
     * Complementary WATER_FOG_MULT, the defect itself: the first definition sits in a function no
     * program calls; blanking that function must keep the directive the later code relies on.
     */
    @Test
    void blankingAnUnusedFunctionKeepsTheDefinesInside() {
        String source = """
                #version 460
                float unusedFog(float d) {
                    #define MULT_M 25 * 0.01;
                    return d * MULT_M;
                }
                layout(location = 0) out vec4 color;
                void main() {
                    float x = 1.0 / MULT_M
                    color = vec4(x);
                }
                """;
        String result = GlslTokenRewriter.removeUnreachableFunctions(source, GlslResourceUsage.analyze(source));
        assertTrue(!result.contains("unusedFog"), result);
        assertTrue(result.contains("#define MULT_M 25 * 0.01;"), result);
        assertTrue(PackCompileTest.compile(result, false) == null, PackCompileTest.compile(result, false));
    }
}
