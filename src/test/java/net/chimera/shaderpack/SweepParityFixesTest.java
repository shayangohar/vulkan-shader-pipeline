package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Iris-parity defects the 27-pack option sweep found (DOC-733): each runs in Iris, and Chimera refused it. */
final class SweepParityFixesTest {
    /** Mellow writes texture.composite.colortex3=/img/smaaArea.png; Iris drops the leading slash. */
    @Test
    void aLeadingSlashTexturePathIsPackRelative(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.createDirectories(shaders.resolve("img"));
        Files.write(shaders.resolve("img/area.png"), new byte[] {1});
        assertNotNull(PackResourcePlan.resolvePath(shaders, "/img/area.png"));
        assertNull(PackResourcePlan.resolvePath(shaders, "//img/area.png"), "only one slash is dropped");
        assertNull(PackResourcePlan.resolvePath(shaders, "/../area.png"), "the path stays inside the pack");

        Files.writeString(shaders.resolve("shaders.properties"), "texture.composite.colortex3=/img/area.png\n");
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D colortex3;
                varying vec2 texcoord;
                void main() { gl_FragColor = texture2D(colortex3, texcoord); }
                """);
        PackResourceBinding area = PackProbe.analyze(root).plan().resources().bindings("composite").stream()
                .filter(binding -> binding.sampler().equals("colortex3")).findFirst().orElseThrow();
        assertEquals(PackResourceStatus.PACK_FILE, area.status(), area.deviations().toString());
    }

    /** I Like Vanilla's composites read their scene through tex and declare no colortex0. */
    @Test
    void anUnknownSampler2DInAFullscreenPassReadsColortex0() {
        List<String> deviations = new ArrayList<>();
        String renamed = UnitZeroSampler.apply("""
                uniform sampler2D tex;
                uniform sampler2D colortex1;
                uniform sampler3D volume;
                uniform sampler2D spare;
                vec3 blur(sampler2D tex, vec2 uv) { return texture2D(tex, uv).rgb; }
                void main() { gl_FragColor = texture2D(tex, uv)
                        + vec4(blur(colortex1, uv), texture3D(volume, vec3(0.0)).r); }
                """, null, deviations, "composite");
        assertTrue(renamed.contains("uniform sampler2D colortex0;") && !renamed.contains("uniform sampler2D tex;"),
                renamed);
        assertTrue(renamed.contains("void main() { gl_FragColor = texture2D(colortex0, uv)"), renamed);
        assertTrue(renamed.contains("vec3 blur(sampler2D tex, vec2 uv) { return texture2D(tex, uv).rgb; }"),
                "a parameter named tex hides the global: " + renamed);
        assertTrue(renamed.contains("uniform sampler3D volume;"), "another sampler type clashes on unit 0");
        assertTrue(renamed.contains("uniform sampler2D spare;"), "an unread declaration stays as written");
        assertEquals(List.of("SAMPLER_UNIT0_COLORTEX0:tex"), deviations);

        List<String> second = new ArrayList<>();
        String declared = UnitZeroSampler.apply("""
                uniform sampler2D colortex0;
                uniform sampler2D texture;
                uniform sampler2D depthtex0;
                void main() { gl_FragColor = texture2D(texture, uv) * texture(colortex0, uv)
                        + texture2D(depthtex0, uv); }
                """, null, second, "composite");
        assertFalse(declared.contains("uniform sampler2D texture;"), declared);
        assertTrue(declared.contains("texture2D(colortex0, uv) * texture(colortex0, uv)"),
                "a call keeps its function name: " + declared);
        assertTrue(declared.contains("texture2D(depthtex0, uv)"), "a name Iris serves keeps its meaning");
    }

    @Test
    void anUnknownSampler2DInACompositeRunsAsColortex0(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D tex;
                varying vec2 texcoord;
                void main() { gl_FragColor = texture2D(tex, texcoord); }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        List<String> deviations = analysis.report().program("composite").deviations();
        assertTrue(analysis.plan().shouldAttempt("composite"), deviations.toString());
        assertTrue(deviations.contains("SAMPLER_UNIT0_COLORTEX0:tex"), deviations.toString());
    }

    /**
     * A gbuffers or shadow program registers colortex4 and up only (IrisSamplers.addRenderTargetSamplers,
     * startIndex 4), and depthtex0-2 without gdepthtex. Kappa's gbuffers_particles reads gcolor, which
     * therefore reads unit 0: the particle's albedo texture.
     */
    @Test
    void anUnregisteredSampler2DInAWorldProgramReadsTheAlbedo(@TempDir Path root) throws IOException {
        List<String> deviations = new ArrayList<>();
        String renamed = UnitZeroSampler.apply("""
                uniform sampler2D gcolor;
                uniform sampler2D gaux1;
                uniform sampler2D depthtex1;
                uniform sampler2D gdepthtex;
                uniform sampler2D noisetex;
                uniform sampler2D dhDepthTex1;
                uniform sampler2D shadowtex0;
                void main() { gl_FragColor = texture2D(gcolor, uv) + texture2D(gaux1, uv)
                        + texture2D(depthtex1, uv) + texture2D(gdepthtex, uv) + texture2D(noisetex, uv)
                        + texture2D(dhDepthTex1, uv) + texture2D(shadowtex0, uv); }
                """, null, deviations, "gbuffers_particles");
        assertTrue(renamed.contains("uniform sampler2D gtexture;") && !renamed.contains("gcolor")
                && !renamed.contains("gdepthtex"), renamed);
        assertTrue(renamed.contains("texture2D(gaux1, uv)") && renamed.contains("texture2D(depthtex1, uv)"), renamed);
        assertEquals(List.of("SAMPLER_UNIT0_ALBEDO:gcolor", "SAMPLER_UNIT0_ALBEDO:gdepthtex"), deviations);
        // The renderer picks the unit (IrisSamplerOrder.of): begin is a fullscreen pass, dh_* a world one.
        assertEquals(UnitZeroSampler.Unit.FULLSCREEN, UnitZeroSampler.Unit.of("begin1"));
        assertEquals(UnitZeroSampler.Unit.WORLD, UnitZeroSampler.Unit.of("dh_terrain"));
        // A parameter named gtexture would capture the rename, so the source stays as written.
        List<String> captured = new ArrayList<>();
        String kept = UnitZeroSampler.apply("""
                uniform sampler2D gcolor;
                vec4 shade(sampler2D gtexture, vec2 uv) { return texture2D(gcolor, uv); }
                void main() { gl_FragColor = shade(gcolor, uv); }
                """, null, captured, "gbuffers_particles");
        assertTrue(kept.contains("uniform sampler2D gcolor;") && captured.isEmpty(), kept);

        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_particles.vsh"), """
                #version 120
                varying vec2 uv;
                void main() { gl_Position = ftransform(); uv = gl_MultiTexCoord0.xy; }
                """);
        Files.writeString(shaders.resolve("gbuffers_particles.fsh"), """
                #version 120
                uniform sampler2D gcolor;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(gcolor, uv); }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        List<String> particles = analysis.report().program("gbuffers_particles").deviations();
        assertTrue(analysis.plan().shouldAttempt("gbuffers_particles"), particles.toString());
        assertTrue(particles.contains("SAMPLER_UNIT0_ALBEDO:gcolor"), particles.toString());
    }

    /**
     * Reverie's composite2 reads dhDepthTex1 without a DISTANT_HORIZONS guard. Iris registers the DH depth
     * names with the render targets and, without DH, binds texture 0 under all three (DHCompat.getDepthTex).
     */
    @Test
    void theDistantHorizonsDepthTexturesReadTheUnboundTexture(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.vsh"), """
                #version 120
                varying vec2 uv;
                void main() { gl_Position = ftransform(); uv = gl_MultiTexCoord0.xy; }
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D dhDepthTex0;
                uniform sampler2D dhDepthTex1;
                varying vec2 uv;
                void main() { gl_FragColor = texture2D(dhDepthTex0, uv) + texture2D(dhDepthTex1, uv); }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        List<PackResourceBinding> bindings = plan.resources().bindings("composite");
        assertEquals(2, bindings.size(), bindings.toString());
        for (PackResourceBinding binding : bindings) {
            assertEquals(SelectorNamespace.DH_DEPTH_SLOT, binding.slot(), binding.toString());
            assertEquals("dhDepthTex", binding.resourceKey());
            assertEquals(PackResourcePlan.GENERATED_UNBOUND, binding.source());
            assertEquals(PackResourceStatus.GENERATED, binding.status());
        }
        assertTrue(plan.shouldAttempt("composite"), plan.program("composite").deviations().toString());
        EntityRealPackTest.compileStage(plan.program("composite").convertedFragment(), false, "dh composite");
        assertFalse(SelectorNamespace.PACK_SELECTOR_SLOTS.contains(SelectorNamespace.DH_DEPTH_SLOT));
        assertEquals(SelectorNamespace.DH_DEPTH_SLOT, InterfacePlanner.samplerSlot("dhDepthTex", "dhDepthTex",
                UniformRegistry.Stage.SHADOW, Map.of()), "shadow programs have the render targets too");
    }

    /**
     * GLSL forms Iris passes through: a default-qualifier statement (Alpha Piscium's
     * layout(early_fragment_tests) in;) declares no variable, and an array size may sit on the type
     * (Noble's flat in vec3[9] skyIlluminanceCoefficients).
     */
    @Test
    void interfaceScanningReadsDefaultQualifiersAndTypeArrays(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.vsh"), """
                #version 330 compatibility
                out vec2 uv;
                flat out vec3[2] light;
                void main() {
                    gl_Position = ftransform();
                    uv = gl_MultiTexCoord0.xy;
                    light[0] = vec3(1);
                    light[1] = vec3(0);
                }
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 330 compatibility
                layout(early_fragment_tests) in;
                in vec2 uv;
                flat in vec3[2] light;
                void main() { gl_FragData[0] = vec4(light[0] + light[1], uv.x); }
                """);
        GlslInterfaceScanner.StageInterface fragment = GlslInterfaceScanner.scan(
                Files.readString(shaders.resolve("composite.fsh")), false);
        assertTrue(fragment.deviations().isEmpty(), fragment.deviations().toString());
        PackPlan plan = PackProbe.analyze(root).plan();
        PackProgramPlan composite = plan.program("composite");
        assertTrue(plan.shouldAttempt("composite"), composite.deviations().toString());
        assertTrue(composite.convertedFragment().contains("layout(early_fragment_tests) in;"),
                composite.convertedFragment());
        EntityRealPackTest.compileStage(composite.convertedVertex(), true, "type-array vertex");
        EntityRealPackTest.compileStage(composite.convertedFragment(), false, "type-array fragment");
    }

    /**
     * Mellow's and superDuperVanilla's core-profile gbuffers_clouds draw nothing at their defaults.
     * Iris runs them and no cloud shows; Chimera skips the host cloud draw. Nothing is left to
     * convert, so the report must not call the program unconverted modern GLSL and a fallback.
     */
    @Test
    void aCloudProgramThatDrawsNothingRuns(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_clouds.vsh"), """
                #version 330 compatibility
                out vec2 uv;
                void main() { gl_Position = ftransform(); uv = gl_MultiTexCoord0.xy; }
                """);
        Files.writeString(shaders.resolve("gbuffers_clouds.fsh"), """
                #version 330 compatibility
                in vec2 uv;
                void main() { discard; }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        assertTrue(analysis.plan().program("gbuffers_clouds").cloudDrawsNothing());
        ConformanceReport.ProgramReport clouds = analysis.report().program("gbuffers_clouds");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION, clouds.support(),
                clouds.deviations().toString());
        assertFalse(clouds.deviations().contains("MODERN_GLSL_UNSUPPORTED"), clouds.deviations().toString());
    }

    /** Shrimple sizes its light bins with #if arithmetic; JCPP evaluates conditions in C long arithmetic. */
    @Test
    void conditionsEvaluateAsJcppDoes() {
        Map<String, String> macros = Map.of("SIZE", "64", "BINS", "(SIZE/2)", "HALF", "0.7", "ONE", "1.5",
                "YES", "true", "ZERO", "0", "SELF", "SELF + 1", "F", "F(x) x");
        assertTrue(PackConditionals.evaluate("(SIZE*SIZE*BINS) < (2048*2048)", macros));
        assertTrue(PackConditionals.evaluate("BINS == 32", macros), "a macro's expression value expands");
        assertFalse(PackConditionals.evaluate("HALF > 0.5", macros), "a number keeps its integer part");
        assertTrue(PackConditionals.evaluate("ONE >= 1 && 1.5e1 == 10", macros));
        assertFalse(PackConditionals.evaluate("YES", macros), "true is a name no macro defines");
        assertTrue(PackConditionals.evaluate("defined YES && defined(ZERO)", macros));
        assertFalse(PackConditionals.evaluate("1 / ZERO || 1 % ZERO", macros), "division by zero reads 0");
        assertTrue(PackConditionals.evaluate("SIZE > 2 ? (1 << 4) & 16 : 0", macros));
        assertTrue(PackConditionals.evaluate("0x10 == 16 && 010 == 8 && -SIZE < ~0", macros));
        assertTrue(PackConditionals.evaluate("SELF", macros), "a macro does not expand inside itself");
        assertThrows(IllegalArgumentException.class, () -> PackConditionals.evaluate("F(1)", macros));
        // JCPP reads a missing operand as 0 at the expr() call that wanted it, and at the top the
        // directive is false: a macro defined with no body expands to nothing.
        Map<String, String> empty = Map.of("EMPTY", "", "SIZE", "64");
        assertTrue(PackConditionals.evaluate("SIZE >", empty), "SIZE > 0");
        assertFalse(PackConditionals.evaluate("EMPTY", empty));
        assertFalse(PackConditionals.evaluate("EMPTY != 1", empty), "the top-level operand is missing");
        assertTrue(PackConditionals.evaluate("!EMPTY", empty));
        assertTrue(PackConditionals.evaluate("1 || EMPTY", empty));
        assertFalse(PackConditionals.evaluate("(EMPTY)", empty));
        assertTrue(PackConditionals.evaluate("defined EMPTY", empty));

        PackConditionals.State state = new PackConditionals.State(macros);
        assertTrue(state.apply("if", "(SIZE*SIZE*BINS) < (8192*8192)"));
        assertTrue(state.active());
    }

    /** Noble's #define ANIMATIONS_ENABLED (WAVING_PLANTS == 1 || ...) is object-like: a space precedes "(". */
    @Test
    void aParenthesizedObjectMacroDecidesItsCondition(@TempDir Path dir) {
        var result = ShaderSourcePreprocessor.prepare(dir, dir.resolve("shadow.vsh"), """
                #version 120
                #define WAVING 1
                #define ANIMATED (WAVING == 1 || WAVING == 2)
                #define SCALE(x) (x * 2)
                #if ANIMATED
                float animated = SCALE(1.0);
                #else
                float still = 0.0;
                #endif
                """, PackEngineDefines.forPack(Map.of()), Set.of());
        assertTrue(result.successful(), result.deviations().toString());
        assertTrue(result.source().contains("float animated = (1.0 * 2);") && !result.source().contains("still"),
                result.source());
    }

    /** Insanity declares uniform float worldTime; Iris serves an int, disables it, and the program reads 0. */
    @Test
    void aWrongTypedIrisUniformReadsZero() {
        UniformRegistry.ProgramInterface plan = InterfacePlanner.planInternal("""
                uniform float worldTime;
                uniform bool frameCounter;
                void main() { gl_FragColor = vec4(worldTime) + vec4(float(frameCounter)); }
                """, UniformRegistry.Stage.POST, true, true);
        assertTrue(plan.deviations().contains("UNIFORM_TYPE_MISMATCH_ZERO:worldTime"), plan.deviations().toString());
        assertTrue(plan.deviations().stream().noneMatch(value -> value.startsWith("UNIFORM_TYPE_UNSUPPORTED")),
                plan.deviations().toString());
        UniformRegistry.UniformDescriptor disabled = UniformRegistry.resolve("worldTime", "float", Map.of());
        assertEquals("disabled:worldTime", disabled.sourceKey());
        assertEquals("frameCounter", UniformRegistry.resolve("frameCounter", "bool", Map.of()).sourceKey(),
                "Iris sets a bool declaration of an int value");
        assertTrue(InterfacePlanner.planInternal("uniform mat3 worldTime;\nvoid main() { mat3 m = worldTime; }",
                UniformRegistry.Stage.POST, true, true).deviations().contains("UNIFORM_TYPE_UNSUPPORTED:worldTime"),
                "a type Chimera cannot place stays unsupported");
    }
}
