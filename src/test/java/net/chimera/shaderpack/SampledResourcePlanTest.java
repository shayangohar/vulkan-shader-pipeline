package net.chimera.shaderpack;

import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sampled-resource plan and its fallbacks. */
final class SampledResourcePlanTest {
    @Test
    void sampledResources() throws Exception {
        Path root = TestPacks.fixtures();
        Path supportedPath = root.resolve("m7_5/resources");
        Path unsupportedPath = root.resolve("m7_5/unsupported_resources");

        PackProbe.Analysis supported = PackProbe.analyze(supportedPath);
        PackProbe.Analysis supportedAgain = PackProbe.analyze(supportedPath);
        assertEquals(supported.report().toJson(), supportedAgain.report().toJson(),
                "supported report stability");
        assertEquals(supported.plan().resources().snapshot(),
                supportedAgain.plan().resources().snapshot(),
                "supported resource snapshot stability");
        verifySupported(supported);
        verifyTargetAliasAllocation(supported);
        verifySnapshot(supported.plan().resources().snapshot());
        verifyZipEquivalence(supportedPath, supported.plan().resources().fingerprint());

        PackProbe.Analysis unsupported = PackProbe.analyze(unsupportedPath);
        verifyUnsupported(unsupported);
        assertTrue(unsupported.report().deviations().stream().anyMatch(value ->
                        value.startsWith("CUSTOM_IMAGE_UNSUPPORTED:")
                                || value.startsWith("STORAGE_RESOURCE_UNSUPPORTED:")),
                "unsupported storage declarations were not named");

        verifyBaseline(root.resolve("baselines/m7_5.json"), supported, unsupported);
    }

    private static void verifySupported(PackProbe.Analysis analysis) {
        PackPlan plan = analysis.plan();
        PackResourcePlan resources = plan.resources();
        PackResourceBinding marker = resources.binding("composite", "marker");
        PackResourceBinding globalMarker = resources.binding("composite1", "marker");
        PackResourceBinding noise = resources.binding("composite", "noisetex");
        PackResourceBinding aux = resources.binding("composite", "gaux1");
        PackResourceBinding depth = resources.binding("composite", "depthtex0");
        PackResourceBinding shadow = resources.binding("composite", "shadowtex0");
        PackResourceBinding scene = resources.binding("composite", "colortex0");

        assertTrue(marker != null && marker.status() == PackResourceStatus.PACK_FILE,
                "stage-specific marker was not loaded");
        assertEquals("tex/override.png", marker.source(),
                "stage-specific declaration did not win");
        // Iris texture stages cover numbered programs: composite1 is in the
        // composite stage, so texture.composite.marker also wins there.
        assertTrue(globalMarker != null && globalMarker.status() == PackResourceStatus.PACK_FILE,
                "composite1 marker was not loaded");
        assertEquals("tex/override.png", globalMarker.source(),
                "composite-stage declaration did not reach composite1");
        assertTrue(noise != null && noise.slot() == 7
                        && noise.status() == PackResourceStatus.PACK_FILE,
                "noisetex plan is not a pack file at slot 7");
        assertTrue(aux != null && aux.resourceKey().equals("colortex4") && aux.slot() == 8,
                "gaux1 alias did not resolve to colortex4 slot 8");
        assertTrue(depth != null && depth.status() == PackResourceStatus.HOST_ALIAS
                        && depth.slot() == 6,
                "depthtex0 alias changed");
        assertTrue(shadow != null && shadow.status() == PackResourceStatus.HOST_ALIAS
                        && shadow.slot() == 5,
                "shadowtex0 alias changed");
        assertTrue(scene != null && scene.dependentPrograms().equals(
                        List.of("composite", "composite1", "final")),
                "resource dependent-program inventory is not stable");
        assertTrue(plan.shouldAttempt("composite") && plan.shouldAttempt("composite1")
                        && plan.shouldAttempt("final"),
                "valid resource-dependent programs were rejected");
        assertEquals(plan.shouldAttempt("composite"),
                analysis.report().shouldAttempt("composite"),
                "supported report and plan eligibility disagree");
        assertTrue(resources.declarations().size() == 3,
                "declaration inventory is not deterministic");
    }

    private static void verifyUnsupported(PackProbe.Analysis analysis) {
        PackPlan plan = analysis.plan();
        PackResourcePlan resources = plan.resources();
        PackResourceBinding missing = resources.binding("composite", "missing");
        PackResourceBinding unsafe = resources.binding("composite", "unsafe");
        assertTrue(missing != null && missing.status() == PackResourceStatus.UNAVAILABLE,
                "missing resource was not unavailable");
        assertTrue(unsafe != null && unsafe.deviations().stream().anyMatch(value ->
                        value.startsWith("PACK_TEXTURE_PATH_UNSAFE:")),
                "unsafe path was not named");
        String reason = resources.unavailableReason("composite");
        assertTrue(resources.bindings("composite").stream()
                        .filter(binding -> !binding.available())
                        .anyMatch(binding -> binding.deviations().contains(reason)),
                "resource failure reason must identify an unavailable binding");
        assertTrue(!plan.shouldAttempt("composite"),
                "dependent program was not rejected");
        assertTrue(plan.shouldAttempt("composite1") && plan.shouldAttempt("final"),
                "unrelated valid program was rejected");
        assertEquals(plan.shouldAttempt("composite"),
                analysis.report().shouldAttempt("composite"),
                "unsupported composite report and plan eligibility disagree");
        assertEquals(plan.shouldAttempt("composite1"),
                analysis.report().shouldAttempt("composite1"),
                "unsupported report and plan eligibility disagree");
    }

    private static void verifyTargetAliasAllocation(PackProbe.Analysis analysis) {
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), analysis.plan().resources(),
                2560, 1440, 8, 16384);
        assertTrue(graph.target(4) != null,
                "gaux1 did not allocate canonical colortex4");
        TargetStep composite = graph.step("composite");
        assertTrue(composite != null && composite.readTargets().contains(4),
                "gaux1 did not schedule a colortex4 read");
    }

    @Test
    void aliases() {
        assertEquals("colortex0", PackResourcePlan.canonicalResource("gcolor"),
                "gcolor alias");
        assertEquals("colortex3", PackResourcePlan.canonicalResource("composite"),
                "composite alias");
        assertEquals("colortex7", PackResourcePlan.canonicalResource("gaux4"),
                "gaux4 alias");
        assertEquals(4, PackResourcePlan.targetIndex("gaux1"),
                "gaux target index");
        // Iris texture stages: texture.<stage>.<sampler> covers numbered programs.
        assertEquals("deferred", PackResourcePlan.textureStage("deferred1"), "deferred stage");
        assertEquals("composite", PackResourcePlan.textureStage("composite5"), "composite stage");
        assertEquals("composite", PackResourcePlan.textureStage("final"), "final stage");
        assertEquals("shadowcomp", PackResourcePlan.textureStage("shadowcomp1"), "shadowcomp stage");
        assertEquals("gbuffers", PackResourcePlan.textureStage("gbuffers_water"), "gbuffers stage");
        assertEquals("gbuffers", PackResourcePlan.textureStage("shadow"), "shadow stage");
        assertEquals(null, PackResourcePlan.textureStage("deferred_extra"), "unknown stage");
    }

    private static void verifySnapshot(String snapshot) {
        assertTrue(!snapshot.contains("C:\\") && !snapshot.contains("/\\"),
                "resource snapshot contains an absolute path");
        assertTrue(!snapshot.toLowerCase().contains("timestamp"),
                "resource snapshot contains unstable metadata");
    }

    private static void verifyZipEquivalence(Path directory, String expectedResourceFingerprint)
            throws IOException {
        Path archive = Files.createTempFile("chimera-m75-resources-", ".zip");
        try {
            try (OutputStream output = Files.newOutputStream(archive);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                List<Path> files;
                try (var stream = Files.walk(directory.resolve("shaders"))) {
                    files = stream.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(path ->
                                    directory.resolve("shaders").relativize(path).toString()))
                            .toList();
                }
                Path shaderRoot = directory.resolve("shaders");
                for (Path file : files) {
                    String name = "shaders/" + shaderRoot.relativize(file)
                            .toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                }
            }
            PackProbe.Analysis zipped = PackProbe.analyze(archive);
            assertEquals(expectedResourceFingerprint, zipped.plan().resources().fingerprint(),
                    "directory and ZIP resource fingerprints differ");
            assertTrue(zipped.plan().shouldAttempt("composite"),
                    "ZIP resource program was rejected");
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static void verifyBaseline(
            Path baseline,
            PackProbe.Analysis supported,
            PackProbe.Analysis unsupported
    ) throws IOException {
        String expected = Files.readString(baseline, StandardCharsets.UTF_8).trim();
        if (expected.contains("TO_BE_FILLED")) {
            System.out.println("[chimera] M7.5 supportedReport=" + supported.report().sha256());
            System.out.println("[chimera] M7.5 supportedResource="
                    + supported.plan().resources().fingerprint());
            System.out.println("[chimera] M7.5 unsupportedReport=" + unsupported.report().sha256());
            System.out.println("[chimera] M7.5 unsupportedResource="
                    + unsupported.plan().resources().fingerprint());
            return;
        }
        assertTrue(expected.contains(supported.report().sha256()),
                "supported report baseline mismatch");
        assertTrue(expected.contains(supported.plan().resources().fingerprint()),
                "supported resource baseline mismatch");
        assertTrue(expected.contains(unsupported.report().sha256()),
                "unsupported report baseline mismatch");
        assertTrue(expected.contains(unsupported.plan().resources().fingerprint()),
                "unsupported resource baseline mismatch");
    }
}
