package net.chimera.render;

import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.TargetStep;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The target and depth graph a pack's post programs plan. */
final class TargetGraphTest {
    @Test
    void targetGraph() throws Exception {
        Path root = TestPacks.fixtures();
        Path supported = root.resolve("m7_4/target_graph");
        Path unsupported = root.resolve("m7_4/unsupported_graph");
        PackProbe.Analysis first = PackProbe.analyze(supported);
        PackProbe.Analysis second = PackProbe.analyze(supported);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                first.plan().programs(), first.config(), 1920, 1080, 8, 16384);
        PackTargetGraphPlan graphAgain = PackTargetGraphPlan.build(
                second.plan().programs(), second.config(), 1920, 1080, 8, 16384);

        assertEquals(graph.fingerprint(), graphAgain.fingerprint(), "graph fingerprint stability");
        assertTrue(graph.target(0) != null, "target 0 missing");
        assertTrue(graph.target(1) != null, "target 1 missing");
        assertTrue(graph.target(1).width() == 960 && graph.target(1).height() == 540,
                "relative target size was not resolved");
        assertTrue(graph.target(1).format() == 37, "target format was not applied");
        assertTrue(graph.target(1).doubled(), "flip did not require a doubled target");
        assertTrue(graph.target(2) != null && graph.target(2).persistent(),
                "persistent target was not planned");
        assertTrue(graph.depth().depthtex0(), "depthtex0 was not planned");
        assertTrue(graph.depth().deviations().contains("DEPTH_COPY_FORWARD_Z_PRESERVED"),
                "forward-Z depth semantics were not recorded");
        assertTrue(!graph.depth().deviations().contains("DEPTH_COPY_REVERSED_Z_CONVERTED"),
                "stale reversed-Z depth claim remains active");
        assertTrue(graph.depth().slot("depthtex0") == 6,
                "depthtex0 slot changed");
        assertTrue(graph.depth().slot("depthtex1") == 12,
                "depthtex1 slot was not separated");
        assertTrue(graph.maxAttachments() == 8, "logical MRT limit was not retained");
        MrtPipelineContext.begin(new int[] {37, 97, 97, 37, 109}, 8);
        assertTrue(MrtPipelineContext.attachmentCount(1) == 5,
                "dynamic MRT bridge still uses the old four-attachment cap");
        MrtPipelineContext.end();
        boolean rejectedNine = false;
        try {
            MrtPipelineContext.begin(new int[9], 8);
        } catch (IllegalArgumentException expected) {
            rejectedNine = true;
        } finally {
            MrtPipelineContext.end();
        }
        assertTrue(rejectedNine, "logical MRT bridge accepted more than eight outputs");

        TargetStep composite = graph.step("composite");
        TargetStep composite1 = graph.step("composite1");
        TargetStep finalStep = graph.step("final");
        assertTrue(composite != null && composite.executable(), "composite step rejected");
        assertTrue(composite1 != null && composite1.outputTargets().equals(List.of(1)),
                "sparse target route was not preserved");
        assertTrue(finalStep != null && finalStep.finalStage() && finalStep.outputTargets().equals(List.of(0)),
                "final route was not constrained to target 0");
        assertTrue(first.report().toJson().equals(second.report().toJson()),
                "report is not deterministic");

        PackProbe.Analysis rejected = PackProbe.analyze(unsupported);
        PackTargetGraphPlan rejectedGraph = PackTargetGraphPlan.build(
                rejected.plan().programs(), rejected.config(), 1920, 1080, 4, 16384);
        assertTrue(rejectedGraph.steps().stream().anyMatch(step -> !step.executable()),
                "unsupported graph did not produce a rejected step");
        assertTrue(rejectedGraph.deviations().stream().anyMatch(value ->
                        value.startsWith("POST_TARGET_SIZE_CONFLICT")
                                || value.startsWith("POST_TARGET_ATTACHMENT_LIMIT")
                                || value.equals("FINAL_MRT_UNSUPPORTED")),
                "unsupported graph did not name its unsafe layout");

        Path baseline = TestPacks.baseline("m7_4");
        String expected = Files.readString(baseline, StandardCharsets.UTF_8).trim();
        if (expected.contains("TO_BE_FILLED")) {
            System.out.println("[chimera] M7.4 reportSha256=" + first.report().sha256());
            System.out.println("[chimera] M7.4 graphFingerprint=" + graph.fingerprint());
            System.out.println("[chimera] M7.4 graphSnapshot=" + graph.snapshot());
        } else {
            assertTrue(expected.contains(first.report().sha256()), "report baseline mismatch");
            assertTrue(expected.contains(graph.fingerprint()), "graph baseline mismatch");
        }
    }

    @Test
    void targetEightAndUnsupportedNine(@TempDir Path fixture) throws Exception {
        try {
            Path shaders = Files.createDirectories(fixture.resolve("shaders"));
            Files.writeString(shaders.resolve("composite.vsh"),
                    "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
            Files.writeString(shaders.resolve("composite.fsh"),
                    "#version 120\nuniform sampler2D colortex8;\n"
                            + "void main() { gl_FragColor = texture2D(colortex8, vec2(0.5)); }\n");
            Files.writeString(shaders.resolve("composite9.fsh"),
                    "#version 120\nuniform sampler2D colortex16;\n"
                            + "void main() { gl_FragColor = texture2D(colortex16, vec2(0.5)); }\n");
            Files.writeString(shaders.resolve("final.vsh"),
                    "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
            Files.writeString(shaders.resolve("final.fsh"),
                    "#version 120\nuniform sampler2D colortex0;\n"
                            + "void main() { gl_FragColor = texture2D(colortex0, vec2(0.5)); }\n");
            Files.writeString(shaders.resolve("shaders.json"),
                    "{\"programs\":[{\"name\":\"composite\",\"fragment\":\"composite.fsh\"},"
                            + "{\"name\":\"composite9\",\"fragment\":\"composite9.fsh\"},"
                            + "{\"name\":\"final\",\"fragment\":\"final.fsh\"}]}\n");
            PackProbe.Analysis analysis = PackProbe.analyze(fixture);
            PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                    analysis.plan().programs(), analysis.config(), 16, 16, 8, 16384);
            assertTrue(graph.target(8) != null,
                    "supported sampler target did not enter runtime allocation graph");
            TargetStep supported = graph.step("composite");
            assertTrue(supported != null && supported.executable(),
                    "colortex8 consumer was rejected");
            TargetStep rejected = graph.step("composite9");
            assertTrue(rejected != null && !rejected.executable()
                            && rejected.deviations().contains("POST_TARGET_INDEX_UNSUPPORTED:16"),
                    "unsupported sampler target must reject its consumer by name: steps="
                            + graph.steps() + ", programs=" + analysis.plan().programs());
            assertTrue(graph.step("final").executable() && graph.target(0) != null,
                    "unsupported consumer disabled unrelated supported targets");
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
