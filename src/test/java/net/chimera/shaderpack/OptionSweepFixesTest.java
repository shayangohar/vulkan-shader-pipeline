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
        assertTrue(result.source().contains("#define MULT_M"), result.source());
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
