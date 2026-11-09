package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris's BufferFlipper rules as PackFlipPlan runs them at load (CompositeRenderer,
 * IrisRenderingPipeline): every pass's flipped set, the world's and final's.
 */
final class FlipPlanTest {
    private static final PackFrameSchedulePlan.PostWindow PREPARE = PackFrameSchedulePlan.PostWindow.PREPARE;
    private static final PackFrameSchedulePlan.PostWindow EARLY = PackFrameSchedulePlan.PostWindow.EARLY;
    private static final PackFrameSchedulePlan.PostWindow LATE = PackFrameSchedulePlan.PostWindow.LATE;

    /**
     * A {@code false} entry keeps a draw buffer from flipping after its pass (CompositeRenderer skips
     * it): the pass still writes the other side, and later passes keep reading the old one. It is
     * not marked written either. Chimera dropped false entries, so the buffer flipped.
     */
    @Test
    void aFalseEntryHoldsItsDrawBuffer(@TempDir Path root) throws Exception {
        PackProbe.Analysis analysis = pack(root, Map.of(
                        "composite1", "/* RENDERTARGETS: 0,2 */\n" + writes(0, 2),
                        "composite2", "/* RENDERTARGETS: 0 */\n" + writes(0)),
                "flip.composite1.colortex2=false\n");
        assertEquals(List.of(2), analysis.config().cancelledFlips().get("composite1"));
        PackFlipPlan plan = PackFlipPlan.of(analysis.plan().activePrograms(), analysis.config());
        PackFlipPlan.Step held = plan.step("composite1");
        assertEquals(List.of(0, 2), held.drawBuffers());
        assertFalse(PackFlipPlan.flipped(held.after(), 2), "the false entry must hold colortex2's side");
        assertTrue(PackFlipPlan.flipped(held.after(), 0));
        assertEquals(1, held.writeSide(2), "the held draw buffer is still written on its other side");
        assertEquals(Set.of(0), plan.writtenBefore("composite2"), "a held draw buffer is not marked written");
        assertTrue(plan.sided(2), "the held write needs an alt side");
    }

    /**
     * Photon's flip.composite1.colortex6: deferred3 writes its AO to colortex6's alt side, composite
     * writes fog transmittance to main for composite1, and the flip after composite1 makes the AO
     * side current again. The frame ends with colortex6 flipped, so the copy-back hands the next
     * frame's deferred3 its AO history in main.
     */
    @Test
    void aTrueEntryOnAnotherTargetFlipsItBack(@TempDir Path root) throws Exception {
        PackProbe.Analysis analysis = pack(root, Map.of(
                        "deferred3", "uniform sampler2D colortex6;\n/* RENDERTARGETS: 0,6 */\n" + writes(0, 6),
                        "composite", "/* RENDERTARGETS: 6 */\n" + writes(6),
                        "composite1", "uniform sampler2D colortex6;\n/* RENDERTARGETS: 0 */\n" + writes(0)),
                "flip.composite1.colortex6=true\n");
        PackFlipPlan plan = PackFlipPlan.of(analysis.plan().activePrograms(), analysis.config());
        assertFalse(plan.step("deferred3").readsAlt(6), "deferred3 reads its history from main");
        assertEquals(1, plan.step("deferred3").writeSide(6), "deferred3 writes this frame's AO to alt");
        assertEquals(0, plan.step("composite").writeSide(6), "composite writes fog to main");
        assertFalse(plan.step("composite1").readsAlt(6), "composite1 reads the fog");
        assertTrue(PackFlipPlan.flipped(plan.finalState(), 6), "the flip makes the AO side current");
        assertEquals(Set.of(0, 6), plan.writtenBefore("final"), "a true entry marks its target written");
    }

    /** A true entry on a draw buffer flips it a second time, so later passes read the side it read. */
    @Test
    void aTrueEntryOnADrawBufferCancelsItsFlip(@TempDir Path root) throws Exception {
        PackProbe.Analysis analysis = pack(root, Map.of(
                        "composite1", "/* RENDERTARGETS: 0,2 */\n" + writes(0, 2),
                        "composite2", "/* RENDERTARGETS: 0 */\n" + writes(0)),
                "flip.composite1.colortex2=true\n");
        PackFlipPlan plan = PackFlipPlan.of(analysis.plan().activePrograms(), analysis.config());
        PackFlipPlan.Step composite1 = plan.step("composite1");
        assertFalse(PackFlipPlan.flipped(composite1.after(), 2), "the two flips must cancel");
        assertEquals(1, composite1.writeSide(2), "the pass still writes the side it does not read");
        assertEquals(Set.of(0, 2), plan.writtenBefore("composite2"), "the draw buffer still counts as written");
    }

    /** Legacy buffer names name targets in flip keys, as in Iris's PackDirectives; the last value wins. */
    @Test
    void flipKeysTakeLegacyNamesAndTheLastValue(@TempDir Path root) throws Exception {
        PackProbe.Analysis analysis = pack(root, Map.of("composite1", "/* RENDERTARGETS: 0 */\n" + writes(0)),
                "flip.composite1.gaux1=true\nflip.composite1.gaux1=false\nflip.composite1.gnormal=true\n"
                        + "flip.composite_pre.gdepth=true\nflip.deferred_pre.colortex5=false\n");
        assertEquals(List.of(4), analysis.config().cancelledFlips().get("composite1"));
        assertEquals(List.of(2), analysis.config().flips().get("composite1"));
        assertEquals(List.of(1), analysis.config().preFlips().get("composite_pre"));
        assertTrue(analysis.config().preFlips().getOrDefault("deferred_pre", List.of()).isEmpty(),
                "a false pre-flip flips nothing");
    }

    /**
     * Iris builds every pass group, passes or none, so a group's pre-flip applies even when it has
     * no pass, and later groups and the world read through it (audit FD4). The opaque world reads
     * and writes the state after prepare, the translucent world the state after deferred.
     */
    @Test
    void aPreFlipOfAGroupWithoutPassesStillApplies(@TempDir Path root) throws Exception {
        PackProbe.Analysis analysis = pack(root, Map.of(
                        "deferred1", "/* RENDERTARGETS: 0 */\n" + writes(0),
                        "composite1", "/* RENDERTARGETS: 0 */\n" + writes(0)),
                "flip.prepare_pre.colortex3=true\nflip.begin_pre.colortex5=true\n");
        PackFlipPlan plan = PackFlipPlan.of(analysis.plan().activePrograms(), analysis.config());
        assertTrue(PackFlipPlan.flipped(plan.close(PackFrameSchedulePlan.PostWindow.BEGIN), 5));
        assertTrue(PackFlipPlan.flipped(plan.opaqueWorld(), 3), "the opaque world must read colortex3's alt side");
        assertTrue(plan.step("deferred1").readsAlt(3));
        assertFalse(PackFlipPlan.flipped(plan.opaqueWorld(), 0));
        assertTrue(PackFlipPlan.flipped(plan.translucentWorld(), 0), "deferred1's write is what translucents draw on");
        assertFalse(PackFlipPlan.flipped(plan.finalState(), 0));
        assertTrue(plan.sided(3) && plan.sided(5));
        assertEquals(Set.of(), plan.writtenBefore("deferred1"), "a pre-flip is not a write");
    }

    /**
     * Shrimple's colortex0 path: flip.deferred_pre.colortex0 makes deferred read alt and write main,
     * and flip.composite21.colortex0 points composite38's blended bloom at the scene composite21 read.
     */
    @Test
    void groupAndPassFlipsOfColortex0(@TempDir Path root) throws Exception {
        PackProbe.Analysis analysis = pack(root, Map.of(
                        "deferred14", "/* RENDERTARGETS: 0 */\n" + writes(0),
                        "composite21", "uniform sampler2D colortex0;\n/* RENDERTARGETS: 15 */\n" + writes(15),
                        "composite38", "/* RENDERTARGETS: 0 */\n" + writes(0)),
                "flip.deferred_pre.colortex0=true\nflip.composite21.colortex0=true\n");
        PackFlipPlan plan = PackFlipPlan.of(analysis.plan().activePrograms(), analysis.config());
        PackFlipPlan.Step deferred = plan.step("deferred14");
        assertTrue(deferred.readsAlt(0) && deferred.writeSide(0) == 0, "deferred_pre must point deferred14 at main");
        assertEquals(0, plan.translucentWorld() & 1, "translucents draw on main, deferred14's output");
        PackFlipPlan.Step bloom = plan.step("composite38");
        assertTrue(bloom.readsAlt(0), "composite21's flip makes colortex0's alt side current");
        assertEquals(0, bloom.writeSide(0), "composite38 blends onto main, the scene composite21 read");
        assertEquals(Set.of(15, 0), plan.writtenBefore("composite38"));
    }

    /** A pass with no draw-buffer directive writes colortex0, as Iris defaults it, and so flips it. */
    @Test
    void aPassWithoutADirectiveFlipsColortex0(@TempDir Path root) throws Exception {
        PackProbe.Analysis analysis = pack(root, Map.of("composite", writes(0),
                "composite1", "/* RENDERTARGETS: 0 */\n" + writes(0)), "");
        PackFlipPlan plan = PackFlipPlan.of(analysis.plan().activePrograms(), analysis.config());
        assertTrue(PackFlipPlan.flipped(plan.step("composite").after(), 0));
        assertEquals(Set.of(0), plan.writtenBefore("composite1"));
        assertEquals(Set.of(0), plan.writtenBefore("final"), "final sees the composite group's whole set");
        assertEquals(Set.of(), plan.writtenBefore("deferred"), "a group's set starts empty");
    }

    /** A pack's own flips and the empty plan: nothing flips without a post pass or a directive. */
    @Test
    void anEmptyPlanFlipsNothing() {
        PackFlipPlan plan = PackFlipPlan.empty();
        assertEquals(0, plan.finalState());
        assertEquals(0, plan.sided());
        assertTrue(plan.steps().isEmpty());
    }

    private static String writes(int... targets) {
        StringBuilder body = new StringBuilder("void main() {\n");
        for (int index = 0; index < targets.length; index++) {
            body.append("    gl_FragData[").append(index).append("] = vec4(").append(targets[index]).append(".0);\n");
        }
        return body.append("}\n").toString();
    }

    private static PackProbe.Analysis pack(Path root, Map<String, String> programs, String properties)
            throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        for (Map.Entry<String, String> program : programs.entrySet()) {
            Files.writeString(shaders.resolve(program.getKey() + ".fsh"), "#version 120\n" + program.getValue());
        }
        Files.writeString(shaders.resolve("final.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "varying vec2 texcoord;\n\nvoid main() {\n    gl_FragColor = texture2D(colortex0, texcoord);\n}\n");
        Files.writeString(shaders.resolve("shaders.properties"), properties);
        return PackProbe.analyze(root);
    }
}
