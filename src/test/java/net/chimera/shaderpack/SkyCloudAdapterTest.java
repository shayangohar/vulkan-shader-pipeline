package net.chimera.shaderpack;

import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic M8.5b sky and cloud adapter checks. */
final class SkyCloudAdapterTest {
    @Test
    void skyCloudAdapters() throws Exception {
        Path root = TestPacks.fixtures();
        Path fixture = root.resolve("m8_5/sky_clouds");
        if (!Files.isDirectory(fixture)) {
            throw new AssertionError("M8.5b fixture is missing: " + fixture);
        }
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "M8.5b report is not deterministic");

        assertProgram(first.plan(), "gbuffers_skybasic", UniformRegistry.Stage.SKY,
                "SKY_VERTEX_BRIDGE", FamilyAdapterPlan.VertexContract.SKY_POSITION);
        assertProgram(first.plan(), "gbuffers_skytextured", UniformRegistry.Stage.SKY,
                "SKY_VERTEX_BRIDGE", FamilyAdapterPlan.VertexContract.SKY_POSITION_UV);
        assertProgram(first.plan(), "gbuffers_clouds", UniformRegistry.Stage.CLOUD,
                "CLOUD_VERTEX_BRIDGE", FamilyAdapterPlan.VertexContract.CLOUD_POSITION_COLOR);
        assertTrue(first.report().shouldAttempt("gbuffers_skybasic"),
                "skybasic report rejected an executable plan");
        assertTrue(first.report().shouldAttempt("gbuffers_clouds"),
                "cloud report rejected an executable plan");
        assertTrue(first.plan().program("gbuffers_skytextured").convertedFragment()
                        .contains("chimeraTexture"),
                "textured sky did not use the canonical atlas sampler");
        assertTrue(first.plan().program("gbuffers_skytextured").convertedFragment()
                        .contains("layout(binding = 3)"),
                "textured sky sampler did not use the geometry binding lane");
        assertNoUnstable(first.report().toJson(), fixture);
        assertTrue(Files.isRegularFile(root.resolve("baselines/m8_5b.json")),
                "M8.5b baseline is missing");
    }

    private static void assertProgram(PackPlan plan, String name, UniformRegistry.Stage stage,
                                      String deviation, FamilyAdapterPlan.VertexContract contract) {
        PackProgramPlan program = plan == null ? null : plan.program(name);
        assertTrue(program != null, "M8.5b program is missing: " + name);
        assertTrue(program.executable(), name + " is not executable: " + program.deviations());
        assertEquals(stage, program.interfacePlan().effective(stage).stage(),
                name + " stage");
        assertTrue(program.convertedVertex() != null && program.convertedFragment() != null,
                name + " conversion is incomplete");
        assertTrue(program.deviations().contains(deviation),
                name + " missing bridge deviation: " + deviation);
        assertEquals(contract, program.familyAdapter().vertexContract(),
                name + " host vertex contract");
    }

    private static void assertNoUnstable(String text, Path fixture) {
        assertTrue(!text.contains(fixture.toAbsolutePath().toString()),
                "M8.5b report contains an absolute path");
        assertTrue(!text.matches("(?s).*\\b(?:timestamp|generatedAt)\\b.*"),
                "M8.5b report contains a timestamp");
    }
}
