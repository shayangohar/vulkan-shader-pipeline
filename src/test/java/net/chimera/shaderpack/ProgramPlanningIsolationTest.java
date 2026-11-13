package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One program whose planning throws falls back alone; the pack still loads (KNOW-142, TASK-1159).
 * A gbuffers_entities fragment without main() makes the alpha-test injection throw, as a real
 * Chimera planning defect would.
 */
final class ProgramPlanningIsolationTest {
    private static final String ENTITIES_VERTEX = """
            #version 120
            varying vec2 texCoord;
            void main() {
                texCoord = gl_MultiTexCoord0.xy;
                gl_Position = ftransform();
            }
            """;
    /**
     * It writes gl_FragData[0] but has no main(): the alpha test Iris appends to gbuffers_entities
     * (PackAlphaTestPlan.injectDrawTest) has nowhere to go.
     */
    private static final String ENTITIES_FRAGMENT_WITHOUT_MAIN = """
            #version 120
            uniform sampler2D texture;
            varying vec2 texCoord;
            void shade() { gl_FragData[0] = texture2D(texture, texCoord); }
            """;

    @Test
    void theInjectedFailureReallyThrowsInsidePlanning() {
        // Pins the trigger, so the isolation tests below exercise a throw and not a refusal.
        PackAlphaTestPlan entities = PackAlphaTestPlan.forProgram("gbuffers_entities", PackSettingsPlan.empty());
        assertThrows(RuntimeException.class, () -> entities.injectDrawTest(ENTITIES_FRAGMENT_WITHOUT_MAIN, true));
    }

    @Test
    void aThrowingProgramIsSkippedAndTheOthersPlan() {
        PackProgram bad = new PackProgram("gbuffers_entities", ENTITIES_FRAGMENT_WITHOUT_MAIN, null,
                ENTITIES_VERTEX, null);
        PackProgram good = new PackProgram("composite",
                "#version 120\nuniform sampler2D colortex0;\nvarying vec2 texcoord;\n"
                        + "void main() { gl_FragData[0] = texture2D(colortex0, texcoord); }\n", null);
        PackConfig.PackConfigData config = PackConfig.parse(List.of(bad, good), null);
        PackPlan plan = PackPlanBuilder.build(List.of(bad, good), config);

        PackProgramPlan entities = plan.program("gbuffers_entities");
        assertNotNull(entities, "the failing program keeps a plan");
        assertFalse(entities.executable());
        assertTrue(entities.deviations().stream().anyMatch(value ->
                value.startsWith(PackPlanBuilder.PLANNING_FAILED + ":")), entities.deviations().toString());
        assertTrue(plan.program("composite").executable(), plan.program("composite").deviations().toString());
    }

    @Test
    void aPackWithAThrowingProgramStillAnalyzesAndReportsTheSkip(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), ENTITIES_VERTEX);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), ENTITIES_FRAGMENT_WITHOUT_MAIN);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 texcoord;
                void main() { gl_FragData[0] = texture2D(colortex0, texcoord); }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);

        ConformanceReport.ProgramReport entities = analysis.report().program("gbuffers_entities");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK, entities.support());
        assertTrue(entities.deviations().stream().anyMatch(value ->
                value.startsWith(PackPlanBuilder.PLANNING_FAILED + ":")), entities.deviations().toString());
        assertFalse(analysis.report().shouldAttempt("gbuffers_entities"));
        assertTrue(analysis.report().shouldAttempt("composite"),
                analysis.report().program("composite").deviations().toString());
        // The corpus gate names the catch-all, so a real pack can never carry it quietly.
        var gate = PackCompileTest.run(root, analysis);
        assertTrue(gate.failures().stream().anyMatch(failure -> failure.startsWith("gbuffers_entities: "
                + PackPlanBuilder.PLANNING_FAILED)), gate.failures().toString());
    }

    /** A source that recurses the planner too deep falls back alone, as a throw does. */
    @Test
    void aStackOverflowInPlanningFallsBackAlone() {
        PackProgram program = new PackProgram("composite", "void main() {}", null);
        PackProgramPlan plan = PackPlanBuilder.planningFailed(program, null, new StackOverflowError());
        assertFalse(plan.executable());
        assertEquals(List.of(PackPlanBuilder.PLANNING_FAILED + ":StackOverflowError"), plan.deviations());
    }

    /**
     * A row whose report rules throw still names the program, and skips it: without the rules'
     * verdict nothing says the program may run. A source map the rules cannot sort (a null stage
     * name) stands in for a real defect in them.
     */
    @Test
    void aThrowingReportRowSkipsItsProgramAndNamesWhy() {
        PackProbe.Inventory inventory = new PackProbe.Inventory("composite");
        inventory.stages.add("fragment");
        inventory.sources.put("fragment", "void main() { gl_FragData[0] = vec4(1.0); }");
        inventory.sources.put(null, "");
        inventory.deviations.add("AUTHORED_DEVIATION");
        assertThrows(RuntimeException.class, () -> ProgramReportRules.toProgram(inventory, null, null, null, null,
                null), "the trigger must throw inside the rules");

        ConformanceReport.ProgramReport row = ProgramReportRules.reportRow(inventory, null, null, null, null, null);
        assertEquals("composite", row.name());
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK, row.support());
        assertTrue(row.deviations().contains("AUTHORED_DEVIATION"), row.deviations().toString());
        assertTrue(row.deviations().stream().anyMatch(value ->
                value.startsWith(ProgramReportRules.REPORT_FAILED + ":NullPointerException")),
                row.deviations().toString());
    }
}
