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
        String renamed = FullscreenDefaultSampler.apply("""
                uniform sampler2D tex;
                uniform sampler2D colortex1;
                uniform sampler3D volume;
                uniform sampler2D spare;
                vec3 blur(sampler2D tex, vec2 uv) { return texture2D(tex, uv).rgb; }
                void main() { gl_FragColor = texture2D(tex, uv)
                        + vec4(blur(colortex1, uv), texture3D(volume, vec3(0.0)).r); }
                """, PackSettingsPlan.empty(), deviations);
        assertTrue(renamed.contains("uniform sampler2D colortex0;") && !renamed.contains("uniform sampler2D tex;"),
                renamed);
        assertTrue(renamed.contains("void main() { gl_FragColor = texture2D(colortex0, uv)"), renamed);
        assertTrue(renamed.contains("vec3 blur(sampler2D tex, vec2 uv) { return texture2D(tex, uv).rgb; }"),
                "a parameter named tex hides the global: " + renamed);
        assertTrue(renamed.contains("uniform sampler3D volume;"), "another sampler type clashes on unit 0");
        assertTrue(renamed.contains("uniform sampler2D spare;"), "an unread declaration stays as written");
        assertEquals(List.of("SAMPLER_UNIT0_COLORTEX0:tex"), deviations);

        List<String> second = new ArrayList<>();
        String declared = FullscreenDefaultSampler.apply("""
                uniform sampler2D colortex0;
                uniform sampler2D texture;
                uniform sampler2D depthtex0;
                void main() { gl_FragColor = texture2D(texture, uv) * texture(colortex0, uv)
                        + texture2D(depthtex0, uv); }
                """, PackSettingsPlan.empty(), second);
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
        assertThrows(IllegalArgumentException.class, () -> PackConditionals.evaluate("SIZE >", macros));

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
