package net.chimera.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.TargetSpec;
import net.chimera.shaderpack.TargetStep;
import net.chimera.shaderpack.UniformRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Focused logical-colortex8 and Complementary producer-chain checks. */
public final class PostTarget8ConformanceHarness {
    private PostTarget8ConformanceHarness() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path fixture = root.resolve("post_target8/colortex8");
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        verifyFixture(first, second);
        verifyTargetBoundary();
        verifyOptionalRealPack(System.getProperty("chimera.postTarget8.complementary"),
                "Complementary", true);
        verifyOptionalRealPack(System.getProperty("chimera.postTarget8.bsl"), "BSL", false);
        verifyBaseline(root.resolve("baselines/post_target8.json"), first);
        System.out.println("[chimera] Post-target-8 colortex8 conformance: PASS");
    }

    private static void verifyFixture(PackProbe.Analysis first, PackProbe.Analysis second) {
        PackProgramPlan composite = first.plan().program("composite");
        assertTrue(composite != null && composite.executable(),
                "Post-target-8 fixture composite is not executable: "
                        + (composite == null ? "missing" : composite.deviations()));
        UniformRegistry.SamplerBinding colortex8 = composite.interfacePlan()
                .effective(UniformRegistry.Stage.POST).samplers().stream()
                .filter(value -> value.name().equals("colortex8"))
                .findFirst().orElseThrow(() -> new AssertionError("colortex8 sampler is missing"));
        assertEquals(22, colortex8.slot(), "colortex8 selector slot changed");

        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                first.plan().programs(), first.config(), first.plan().resources(),
                1920, 1080, 8, 16384);
        TargetSpec target = graph.target(8);
        assertTrue(target != null && target.format() == 97 && target.clear()
                        && target.width() == 1920 && target.height() == 1080,
                "colortex8 was not planned as a cleared RGBA16F frame target");
        TargetStep step = graph.step("composite");
        assertTrue(step != null && step.executable() && step.readTargets().contains(8)
                        && step.outputTargets().equals(List.of(0, 5)),
                "colortex8 producer-chain step was not preserved");
        assertEquals(8, PackResourcePlan.targetIndex("colortex8"),
                "colortex8 logical identity changed");
        assertEquals(9, PackResourcePlan.targetIndex("colortex9"),
                "colortex9 parser identity changed");

        PackTemporalState temporal = new PackTemporalState();
        temporal.beginFrame(true);
        assertTrue(!temporal.currentAvailable(8),
                "unwritten colortex8 became available before a producer");
        temporal.seedCurrent(8);
        assertTrue(temporal.currentAvailable(8),
                "logical colortex8 validity state is not addressable");
        temporal.abort();

        boolean[] written = new boolean[PostTargetPlan.MAX_TARGET + 1];
        written[8] = true;
        assertTrue(PackPostTargets.isTargetAvailable(8, true, written),
                "colortex8 availability helper rejected a committed target");
        assertEquals(first.report().toJson(), second.report().toJson(),
                "Post-target-8 fixture report is not deterministic");
        assertEquals(graph.fingerprint(), PackTargetGraphPlan.build(
                second.plan().programs(), second.config(), second.plan().resources(),
                1920, 1080, 8, 16384).fingerprint(),
                "Post-target-8 target graph is not deterministic");
        assertTrue(!graph.snapshot().contains("C:\\") && !graph.snapshot().contains("/Users/"),
                "Post-target-8 graph snapshot contains an absolute path");
    }

    private static void verifyTargetBoundary() {
        PostTargetPlan supported = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0,8 */").plan();
        assertTrue(supported.executable() && supported.targetSlots().equals(List.of(0, 8)),
                "logical target 8 was rejected");
        PostTargetPlan rejected = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0,16 */").plan();
        assertTrue(!rejected.executable()
                        && rejected.deviations().contains("POST_TARGET_INDEX_UNSUPPORTED:16"),
                "logical target 16 was not rejected");
        PostTargetPlan high = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 9,13,15 */").plan();
        assertTrue(high.executable() && high.targetSlots().equals(List.of(9, 13, 15)),
                "Iris targets colortex9..15 were rejected: " + high.deviations());
        // Every Iris colour target owns one addressable selector, distinct from
        // every other target and from the fixed depth, shadow and material slots.
        java.util.Set<Integer> reserved = java.util.Set.of(5, 6, 7, 12, 13,
                net.chimera.shaderpack.SelectorNamespace.COVERAGE_SLOT,
                net.chimera.shaderpack.SelectorNamespace.SHADOW_TEX1_SLOT,
                net.chimera.shaderpack.SelectorNamespace.SHADOW_COLOR0_SLOT,
                net.chimera.shaderpack.SelectorNamespace.SHADOW_COLOR1_SLOT,
                net.chimera.shaderpack.SelectorNamespace.NORMALS_SLOT,
                net.chimera.shaderpack.SelectorNamespace.SPECULAR_SLOT);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int target = 0; target <= PostTargetPlan.MAX_TARGET; target++) {
            int slot = net.chimera.shaderpack.SelectorNamespace.colorTargetSlot(target);
            assertTrue(net.chimera.shaderpack.SelectorNamespace.isAddressable(slot)
                            && !reserved.contains(slot) && seen.add(slot),
                    "colortex" + target + " selector " + slot + " is shared or unaddressable");
        }
        assertEquals(-1, net.chimera.shaderpack.SelectorNamespace.colorTargetSlot(16),
                "colortex16 gained a selector");
        // Post targets are built through VulkanMod's image builder, which
        // throws for any format it cannot size (BSL's RGB10_A2 gaux2 did).
        net.chimera.shaderpack.PackConfig.FMT_TO_VK.forEach((token, format) ->
                assertTrue(net.chimera.shaderpack.PackConfig.formatBytes(format) > 0,
                        "exact target format " + token + " has no texel size"));

        MrtPipelineContext.begin(new int[8], 8);
        MrtPipelineContext.end();
        boolean rejectedNine = false;
        try {
            MrtPipelineContext.begin(new int[9], 8);
        } catch (IllegalArgumentException expected) {
            rejectedNine = true;
        } finally {
            MrtPipelineContext.end();
        }
        assertTrue(rejectedNine, "logical target growth changed the eight-attachment cap");
    }

    private static void verifyOptionalRealPack(String rawPath, String label, boolean requireComposite1)
            throws Exception {
        if (rawPath == null || rawPath.isBlank()) return;
        Path path = Path.of(rawPath);
        assertTrue(Files.isDirectory(path) || Files.isRegularFile(path),
                "Post-target-8 " + label + " path is missing");
        String previousLighting = System.getProperty("chimera.option.COLORED_LIGHTING");
        String previousReflections = System.getProperty("chimera.option.WORLD_SPACE_REFLECTIONS");
        System.setProperty("chimera.option.COLORED_LIGHTING", "128");
        System.setProperty("chimera.option.WORLD_SPACE_REFLECTIONS", "1");
        try {
            PackProbe.Analysis analysis = PackProbe.analyze(path);
            PackProgramPlan composite1 = analysis.plan().program("composite1");
            if (!requireComposite1) {
                assertTrue(composite1 == null || !analysis.report().deviations().contains(
                                "POST_TARGET_INDEX_UNSUPPORTED:8"),
                        "Post-target-8 BSL gained an unexpected target-8 rejection");
                verifyBslMipmaps(analysis);
                return;
            }
            assertTrue(composite1 != null && composite1.executable()
                            && analysis.plan().shouldAttempt("composite1"),
                    "Post-target-8 " + label + " composite1 is not executable: "
                            + (composite1 == null ? "missing" : composite1.deviations()));
            PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                    analysis.plan().programs(), analysis.config(), analysis.plan().resources(),
                    2560, 1440, 8, 16384);
            TargetStep step = graph.step("composite1");
            assertTrue(step != null && step.executable() && step.readTargets().contains(8)
                            && step.outputTargets().equals(List.of(0, 5)),
                    "Post-target-8 " + label + " composite1 graph step is incorrect: " + step);
            assertTrue(composite1.interfacePlan().effective(UniformRegistry.Stage.POST).samplers()
                            .stream().anyMatch(value -> value.name().equals("colortex8")
                                    && value.slot() == 22),
                    "Post-target-8 " + label + " composite1 colortex8 descriptor is missing");
            assertTrue(analysis.report().program("composite1") != null
                            && analysis.report().program("composite1").support()
                            != ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                    "Post-target-8 " + label + " composite1 remains a static identity fallback");
            verifyHighTargets(label, analysis, graph, step);
            System.out.println("[chimera] Post-target-8 " + label + " composite1: PASS");
        } finally {
            restoreProperty("chimera.option.COLORED_LIGHTING", previousLighting);
            restoreProperty("chimera.option.WORLD_SPACE_REFLECTIONS", previousReflections);
        }
    }

    /**
     * BSL's bloom (composite4) reads colortex0 through its mips; without them
     * it point-samples sun sparkles on water and flashes (TASK-478).
     */
    private static void verifyBslMipmaps(PackProbe.Analysis analysis) {
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), analysis.plan().resources(),
                2560, 1440, 8, 16384);
        TargetStep bloom = graph.step("composite4");
        assertTrue(bloom != null && bloom.executable() && bloom.mipmapTargets().contains(0),
                "Post-target-8 BSL composite4 must regenerate colortex0 mips: " + bloom);
        assertTrue(graph.target(0) != null && graph.target(0).mipLevels() == 12,
                "Post-target-8 BSL colortex0 needs a full 2560x1440 chain: " + graph.target(0));
        assertTrue(graph.deviations().stream().noneMatch(value -> value.contains("MIPMAP")),
                "Post-target-8 BSL must not report a mipmap fallback");
        System.out.println("[chimera] Post-target-8 BSL mipmaps: " + graph.steps().stream()
                .filter(step -> !step.mipmapTargets().isEmpty())
                .map(step -> step.programName() + step.mipmapTargets()).toList());
    }

    /**
     * Complementary r5.9 blends volumetric light with an entity linear depth
     * in colortex13 (declared R8): translucent entities write it and
     * composite1 reads it. Iris exposes colortex0..15, so both must run, on
     * an exact R8 target. Packs that do not use colortex13 skip the check.
     */
    private static void verifyHighTargets(String label, PackProbe.Analysis analysis,
            PackTargetGraphPlan graph, TargetStep composite1) {
        if (!composite1.readTargets().contains(13)) return;
        var target = graph.target(13);
        assertTrue(target != null && target.format() == 9,
                "Post-target-8 " + label + " colortex13 is not an exact R8 target: " + target);
        PackProgramPlan translucent = analysis.plan().program("gbuffers_entities_translucent");
        assertTrue(translucent != null && translucent.executable()
                        && translucent.geometryOutputPlan().targetSlots().contains(13),
                "Post-target-8 " + label + " translucent entities do not write colortex13: "
                        + (translucent == null ? "missing" : translucent.deviations()));
        System.out.println("[chimera] Post-target-8 " + label + " colortex13: R8, written by "
                + "gbuffers_entities_translucent " + translucent.geometryOutputPlan().targetSlots());
    }

    private static void verifyBaseline(Path path, PackProbe.Analysis analysis) throws Exception {
        JsonObject baseline = JsonParser.parseString(
                Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        if ("TO_BE_FILLED".equals(baseline.get("reportSha256").getAsString())) {
            PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                    analysis.plan().programs(), analysis.config(), analysis.plan().resources(),
                    1920, 1080, 8, 16384);
            System.out.println("[chimera] Post-target-8 reportSha256=" + analysis.report().sha256());
            System.out.println("[chimera] Post-target-8 graphFingerprint=" + graph.fingerprint());
            System.out.println("[chimera] Post-target-8 resourceFingerprint="
                    + analysis.plan().resources().fingerprint());
            return;
        }
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), analysis.plan().resources(),
                1920, 1080, 8, 16384);
        assertEquals(baseline.get("reportSha256").getAsString(), analysis.report().sha256(),
                "Post-target-8 report baseline");
        assertEquals(baseline.get("graphFingerprint").getAsString(), graph.fingerprint(),
                "Post-target-8 graph baseline");
        assertEquals(baseline.get("resourceFingerprint").getAsString(),
                analysis.plan().resources().fingerprint(), "Post-target-8 resource baseline");
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
