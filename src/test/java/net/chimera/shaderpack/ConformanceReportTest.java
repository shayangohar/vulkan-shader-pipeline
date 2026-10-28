package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConformanceReportTest {
    @Test
    void passComputesLeaveADrawingProgramToItsInstall() {
        ConformanceReport report = new ConformanceReport("fixture", false, List.of(), Map.of(), List.of(), List.of());
        report.addProgram(program("composite4", List.of("compute", "fragment", "vertex")));
        report.addProgram(program("setup", List.of("compute")));

        report.markPassComputeInstalled("composite4");
        report.markPassComputeInstalled("setup");

        // Glimmer's composite4 runs its fog compute and then draws; the compute must not stand for the draw.
        assertTrue(report.shouldAttempt("composite4"));
        assertEquals(ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED, report.program("composite4").runtime());
        assertTrue(report.program("composite4").deviations().contains("PASS_COMPUTE_INSTALLED"));
        assertFalse(report.shouldAttempt("setup"));
        assertEquals(ConformanceReport.RuntimeDisposition.INSTALLED, report.program("setup").runtime());
    }

    private static ConformanceReport.ProgramReport program(String name, List<String> stages) {
        return new ConformanceReport.ProgramReport(name, "post", "glsl", stages, Map.of(), List.of(), List.of(),
                List.of(0), ConformanceReport.SupportStatus.SUPPORTED,
                ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED, List.of());
    }
}
