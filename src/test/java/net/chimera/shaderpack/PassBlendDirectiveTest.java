package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * blend.<program> outside the world programs, as Iris 1.21.1 applies it: CompositeRenderer gives
 * a begin, prepare, deferred or composite pass its program-level mode on every buffer and ignores
 * per-buffer directives; FinalPassRenderer always draws unblended; shadow programs blend each
 * buffer as directed over their ProgramId default, blending off.
 */
final class PassBlendDirectiveTest {
    private static final PackBlendPlan.Mode ADD = PackBlendPlan.parse("ONE ONE ONE ONE");

    @Test
    void compositeBlendsEveryBufferAndIgnoresBufferDirectives(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                blend.composite=ONE ONE ONE ONE
                blend.composite.colortex1=off
                blend.composite1.colortex2=off
                blend.final=ONE ONE ONE ONE
                """);
        writePost(shaders, "composite", "/* DRAWBUFFERS:01 */");
        writePost(shaders, "composite1", "/* DRAWBUFFERS:2 */");
        writePost(shaders, "final", "");
        PackPlan plan = PackProbe.analyze(root).plan();

        PackProgramPlan composite = plan.program("composite");
        PackBlendPlan.Mode[] modes = composite.blendPlan().attachments(composite.targetPlan().targetSlots());
        assertTrue(modes != null && modes.length == 2 && modes[0].equals(ADD) && modes[1].equals(ADD),
                "composite did not blend every buffer with its program mode: " + Arrays.toString(modes));
        assertTrue(composite.deviations().contains("BLEND_DIRECTIVE_IGNORED_AS_IRIS:composite")
                        && composite.deviations().stream().noneMatch(d -> d.startsWith("BLEND_DIRECTIVE_UNSUPPORTED")),
                composite.deviations().toString());

        PackProgramPlan bufferOnly = plan.program("composite1");
        assertTrue(!bufferOnly.blendPlan().overridesAnything()
                        && bufferOnly.deviations().contains("BLEND_DIRECTIVE_IGNORED_AS_IRIS:composite1"),
                "a per-buffer composite directive must be ignored: " + bufferOnly.deviations());

        PackProgramPlan finalPass = plan.program("final");
        assertTrue(!finalPass.blendPlan().overridesAnything()
                        && finalPass.deviations().contains("BLEND_DIRECTIVE_IGNORED_AS_IRIS:final")
                        && finalPass.deviations().stream().noneMatch(d -> d.startsWith("BLEND_DIRECTIVE_APPLIED")),
                "final must draw unblended: " + finalPass.deviations());
    }

    @Test
    void shadowBlendsEachBufferOverIrisOff() {
        PackSettingsPlan settings = properties("blend.shadow", "SRC_COLOR ZERO ONE ZERO",
                "blend.shadow.colortex1", "off", "blend.shadow.colortex2", "ONE ONE ONE ONE");
        PackBlendPlan.Mode multiply = PackBlendPlan.parse("SRC_COLOR ZERO ONE ZERO");
        PackBlendPlan.Mode[] modes = PackBlendPlan.forProgram("shadow", settings).shadowAttachments(3);
        assertTrue(modes[0].equals(multiply) && modes[1] == PackBlendPlan.Mode.OFF && modes[2].equals(ADD),
                "shadow attachments did not take their directives: " + Arrays.toString(modes));

        PackBlendPlan.Mode[] bufferOnly = PackBlendPlan.forProgram("shadow",
                properties("blend.shadow.colortex1", "ONE ONE ONE ONE")).shadowAttachments(2);
        assertTrue(bufferOnly[0] == PackBlendPlan.Mode.OFF && bufferOnly[1].equals(ADD),
                "a shadow buffer without a directive must keep Iris's default, off: " + Arrays.toString(bufferOnly));

        PackBlendPlan.Mode[] none = PackBlendPlan.empty().shadowAttachments(2);
        assertTrue(Arrays.stream(none).allMatch(mode -> mode == PackBlendPlan.Mode.OFF), Arrays.toString(none));
    }

    /**
     * Without independentBlend every attachment's blend state must be identical: a blend all of them
     * resolve to is kept on each, and differing ones (an integer attachment counts as off) fall to off.
     */
    @Test
    void sharedAttachmentsWithoutIndependentBlend() {
        PackBlendPlan.Mode add = PackBlendPlan.parse("ONE ONE ONE ONE");
        PackBlendPlan.Mode over = PackBlendPlan.parse("SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO");
        PackBlendPlan.Mode off = PackBlendPlan.Mode.OFF;
        boolean[] floats = {false, false, false};
        assertTrue(Arrays.equals(new PackBlendPlan.Mode[3], PackBlendPlan.sharedAttachments(null, floats)),
                "the host blend everywhere");
        assertTrue(Arrays.equals(new PackBlendPlan.Mode[] {add, add, add},
                PackBlendPlan.sharedAttachments(new PackBlendPlan.Mode[] {add, add, add}, floats)),
                "one program blend");
        assertTrue(Arrays.equals(new PackBlendPlan.Mode[] {off, off, off},
                PackBlendPlan.sharedAttachments(new PackBlendPlan.Mode[] {add, over, null}, floats)), "mixed blends");
        assertTrue(Arrays.equals(new PackBlendPlan.Mode[] {off, off, off}, PackBlendPlan.sharedAttachments(
                new PackBlendPlan.Mode[] {add, add, add}, new boolean[] {false, true, false})), "an integer target");
    }

    /**
     * A pipeline drawn into VulkanMod's render pass (no attachment contract) takes its program's
     * directive for that pass's one attachment, colortex0, as Iris's BlendModeOverride does. Kappa's
     * glint draws SRC_ALPHA ONE ZERO ONE there, not vanilla's SRC_COLOR, ONE.
     */
    @Test
    void aHostPassPipelineTakesItsDirectiveForColortex0() {
        PackBlendPlan.Mode glint = PackBlendPlan.parse("SRC_ALPHA ONE ZERO ONE");
        PackBlendPlan.Mode[] program = PackPipelines.hostPassBlends(PackBlendPlan.forProgram("gbuffers_armor_glint",
                properties("blend.gbuffers_armor_glint", "SRC_ALPHA ONE ZERO ONE")));
        assertTrue(program != null && program.length == 1 && program[0].equals(glint),
                "the program directive did not reach the host pass: " + Arrays.toString(program));

        PackBlendPlan.Mode[] buffer = PackPipelines.hostPassBlends(PackBlendPlan.forProgram("gbuffers_weather",
                properties("blend.gbuffers_weather", "SRC_ALPHA ONE ZERO ONE", "blend.gbuffers_weather.colortex0",
                        "off")));
        assertTrue(buffer != null && buffer[0] == PackBlendPlan.Mode.OFF,
                "colortex0's own directive must win: " + Arrays.toString(buffer));

        assertTrue(PackPipelines.hostPassBlends(PackBlendPlan.forProgram("gbuffers_line",
                properties("blend.gbuffers_line.colortex2", "off")))[0] == null,
                "a directive for another buffer must leave colortex0 on the host blend");
        assertTrue(PackPipelines.hostPassBlends(PackBlendPlan.empty()) == null, "no directive, no override");
    }

    @Test
    void shadowProgramKeepsItsDirectives(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "blend.shadow=SRC_COLOR ZERO ONE ZERO\n");
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 120
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 120
                uniform sampler2D tex;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(tex, uv); }
                """);
        PackProgramPlan shadow = PackProbe.analyze(root).plan().program("shadow");
        assertTrue(shadow.blendPlan().overridesAnything()
                        && shadow.deviations().contains("BLEND_DIRECTIVE_APPLIED:shadow")
                        && shadow.deviations().stream().noneMatch(d -> d.startsWith("BLEND_DIRECTIVE_UNSUPPORTED")),
                shadow.deviations().toString());
    }

    private static void writePost(Path shaders, String program, String drawBuffers) throws Exception {
        Files.writeString(shaders.resolve(program + ".vsh"), """
                #version 120
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve(program + ".fsh"), """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 uv;
                void main() {
                    %s
                    gl_FragData[0] = texture2D(colortex0, uv);
                    gl_FragData[1] = vec4(uv, 0.0, 1.0);
                }
                """.formatted(drawBuffers));
    }

    private static PackSettingsPlan properties(String... keyValues) {
        Map<String, String> values = new TreeMap<>();
        for (int i = 0; i < keyValues.length; i += 2) values.put(keyValues[i], keyValues[i + 1]);
        return new PackSettingsPlan(Map.of(), Map.of(), Map.of(), values, Map.of(),
                Set.of(), Set.of(), Set.of(), List.of(), "default",
                PackRuntimeSettings.empty(), Map.of(), Map.of(), false, Map.of(), null);
    }
}
