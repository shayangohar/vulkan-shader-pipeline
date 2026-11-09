package net.chimera.render;

import net.chimera.shaderpack.PackFlipPlan;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.TargetSpec;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostTargetSides against Iris, frame by frame. An Iris model keeps main and alt per target and
 * takes each pass's, the world's and final's flipped set from the plan; PostTargetSides runs the
 * same frames with Chimera's images, the HDR image standing in for colortex0's world side. Every
 * image holds a content token, and every draw composes onto what its image held, so a read of the
 * wrong side, a draw over the wrong base, a missed write-back or copy-back all show as a token the
 * two disagree on.
 */
final class FlipSidesTest {
    private static final List<PackFrameSchedulePlan.PostWindow> GROUPS = List.of(
            PackFrameSchedulePlan.PostWindow.BEGIN, PackFrameSchedulePlan.PostWindow.PREPARE,
            PackFrameSchedulePlan.PostWindow.EARLY, PackFrameSchedulePlan.PostWindow.LATE);

    /**
     * What a frame does beyond the plan: steps Chimera does not run, the targets the world draws
     * into, and per pass the targets it stores to through colorimgN or builds mips of.
     */
    record Frame(Set<String> skipped, Set<Integer> world, Map<String, Set<Integer>> stores,
                 Map<String, Set<Integer>> mips) {
        static Frame plain(Set<Integer> world) {
            return new Frame(Set.of(), world, Map.of(), Map.of());
        }
    }

    /** One pack's frames, run by both. */
    static final class Harness {
        private final PackFlipPlan plan;
        private final int allocated;
        private final int cleared;
        private final PostTargetSides sides;
        private final String[][] iris = new String[2][16];
        private final Map<PostTargetSides.Image, String> chimera = new HashMap<>();
        final List<String> mismatches = new ArrayList<>();
        /** What each step read, per target, in the last frame, as Iris saw it. */
        final Map<String, Map<Integer, String>> reads = new LinkedHashMap<>();
        /** What final read, per target, in the last frame. */
        final Map<Integer, String> finalReads = new HashMap<>();
        private int frame;
        private int irisState;
        /** The copies Chimera recorded, and those at the last frame start. */
        int copies;
        int frameStartCopies;
        private final PostTargetSides.Ops ops = new PostTargetSides.Ops() {
            @Override
            public void copy(PostTargetSides.Image from, PostTargetSides.Image to) {
                chimera.put(to, chimera.get(from));
                copies++;
            }

            @Override
            public void clear(PostTargetSides.Image image) {
                chimera.put(image, "clear" + image.target());
            }
        };

        Harness(PackFlipPlan plan, int allocated, int cleared) {
            this.plan = plan;
            this.allocated = allocated;
            this.cleared = cleared;
            this.sides = new PostTargetSides(plan, allocated, cleared);
        }

        static Harness of(PackTargetGraphPlan graph) {
            int allocated = 0;
            int cleared = 0;
            for (TargetSpec target : graph.targets()) {
                allocated |= 1 << target.index();
                if (target.clear()) cleared |= 1 << target.index();
            }
            return new Harness(graph.flips(), allocated, cleared);
        }

        private boolean allocated(int target) {
            return (allocated & (1 << target)) != 0;
        }

        private boolean cleared(int target) {
            return (cleared & (1 << target)) != 0;
        }

        private static int side(int state, int target) {
            return PackFlipPlan.flipped(state, target) ? 1 : 0;
        }

        void run(Frame what) {
            openFrame();
            for (PackFrameSchedulePlan.PostWindow window : GROUPS) group(window, what);
            sides.enterFinal(ops);
            irisState = plan.finalState();
            check(irisState == sides.state(), "final state");
            for (int target = 0; target < 16; target++) {
                if (!allocated(target)) continue;
                String expected = iris[side(irisState, target)][target];
                finalReads.put(target, expected);
                check(expected.equals(chimera.get(sides.read(target))), "final reads colortex" + target
                        + ": iris " + expected + ", chimera " + chimera.get(sides.read(target)));
            }
            frame++;
        }

        /** Iris copies the last frame's flipped, uncleared targets back from alt, then clears; Chimera opens. */
        private void openFrame() {
            for (int target = 0; target < 16; target++) {
                if (!allocated(target)) continue;
                if (frame == 0 || cleared(target)) {
                    iris[0][target] = iris[1][target] = "clear" + target;
                } else if (PackFlipPlan.flipped(plan.finalState(), target)) {
                    iris[0][target] = iris[1][target];
                }
            }
            int before = copies;
            sides.beginFrame(ops);
            frameStartCopies = copies - before;
            if (frame == 0 || cleared(0)) chimera.put(new PostTargetSides.Image(0, PostTargetSides.HDR), "clear0");
            reads.clear();
            finalReads.clear();
        }

        /**
         * One pass group, then the world it hands over to. Chimera opens a group as PackPostChain
         * does: one with passes or pre-flips (windowDue), and always composite's.
         */
        private void group(PackFrameSchedulePlan.PostWindow window, Frame what) {
            List<PackFlipPlan.Step> steps = plan.steps().stream().filter(step -> step.window() == window).toList();
            boolean late = window == PackFrameSchedulePlan.PostWindow.LATE;
            boolean runs = late || PackPostChain.windowDue(!steps.isEmpty(), plan, window);
            irisState = plan.open(window);
            if (runs) sides.openWindow(window, ops);
            for (PackFlipPlan.Step step : steps) {
                boolean skipped = what.skipped().contains(step.program());
                Map<Integer, String> irisReads = irisStep(step, skipped, what);
                reads.put(step.program(), irisReads);
                if (!skipped) chimeraStep(step, irisReads, what);
            }
            if (late) return;
            irisState = plan.close(window);
            if (runs) sides.enterWorld(window, ops);
            if (window == PackFrameSchedulePlan.PostWindow.BEGIN) {
                horizon(what.world());
            } else {
                world(window, what.world());
            }
        }

        /**
         * Iris draws its horizon after the begin group, ahead of the shadow and prepare passes,
         * through the gbuffer framebuffers, which write flippedAfterPrepare's sides from the frame's
         * start. Chimera draws it into the HDR image, which must stand in for that colortex0 side.
         */
        private void horizon(Set<Integer> targets) {
            if (!targets.contains(0) || !allocated(0)) return;
            String draw = ">horizon@" + frame;
            int side = side(plan.opaqueWorld(), 0);
            PostTargetSides.Image hdr = new PostTargetSides.Image(0, PostTargetSides.HDR);
            check(iris[side][0].equals(chimera.get(hdr)), "the horizon draws on colortex0's opaque-world side: iris "
                    + iris[side][0] + ", chimera " + chimera.get(hdr));
            iris[side][0] += draw;
            chimera.put(hdr, chimera.get(hdr) + draw);
        }

        /** Iris runs the pass; one Chimera does not run passes its input through. */
        private Map<Integer, String> irisStep(PackFlipPlan.Step step, boolean skipped, Frame what) {
            check(irisState == step.before(), step.program() + " opens on its plan state");
            Map<Integer, String> seen = new HashMap<>();
            for (int target = 0; target < 16; target++) {
                if (allocated(target)) seen.put(target, iris[side(irisState, target)][target]);
            }
            for (int target : step.drawBuffers()) {
                if (!allocated(target)) continue;
                int write = 1 - side(irisState, target);
                iris[write][target] = skipped ? iris[1 - write][target]
                        : iris[write][target] + ">" + step.program() + "@" + frame;
            }
            if (!skipped) {
                for (int target : what.stores().getOrDefault(step.program(), Set.of())) {
                    iris[side(irisState, target)][target] += ">store:" + step.program() + "@" + frame;
                }
            }
            irisState = step.after();
            return seen;
        }

        private void chimeraStep(PackFlipPlan.Step step, Map<Integer, String> irisReads, Frame what) {
            PackFlipPlan.Step current = sides.advanceTo(step.program(), ops);
            check(step.equals(current), step.program() + " is the step the frame is at");
            for (Map.Entry<Integer, String> read : irisReads.entrySet()) {
                String token = chimera.get(sides.read(read.getKey()));
                check(read.getValue().equals(token), step.program() + " reads colortex" + read.getKey()
                        + ": iris " + read.getValue() + ", chimera " + token);
            }
            for (int target : what.mips().getOrDefault(step.program(), Set.of())) {
                String token = chimera.get(sides.readGraph(target, false, ops));
                check(irisReads.get(target).equals(token), step.program() + " mips colortex" + target
                        + ": iris " + irisReads.get(target) + ", chimera " + token);
            }
            List<PostTargetSides.Image> stored = new ArrayList<>();
            for (int target : what.stores().getOrDefault(step.program(), Set.of())) {
                stored.add(sides.readGraph(target, true, ops));
            }
            for (int target : step.drawBuffers()) {
                if (!allocated(target)) continue;
                PostTargetSides.Image image = sides.write(step, target, ops);
                chimera.put(image, chimera.get(image) + ">" + step.program() + "@" + frame);
            }
            for (PostTargetSides.Image image : stored) {
                chimera.put(image, chimera.get(image) + ">store:" + step.program() + "@" + frame);
            }
            sides.finish(step);
        }

        /** The world after {@code window}'s group draws into its targets on the side its state names. */
        private void world(PackFrameSchedulePlan.PostWindow window, Set<Integer> targets) {
            check(irisState == sides.state(), "world after " + window + " state");
            for (int target = 1; target < 16; target++) {
                if (!allocated(target)) continue;
                String token = chimera.get(new PostTargetSides.Image(target, sides.readSide(target)));
                check(iris[side(irisState, target)][target].equals(token), "world after " + window
                        + " reads colortex" + target);
            }
            for (int target : targets) {
                if (!allocated(target)) continue;
                String draw = ">world-" + window + "@" + frame;
                iris[side(irisState, target)][target] += draw;
                PostTargetSides.Image image = target == 0 ? new PostTargetSides.Image(0, PostTargetSides.HDR)
                        : new PostTargetSides.Image(target, sides.readSide(target));
                if (target == 0) {
                    check(iris[side(irisState, 0)][0].equals(chimera.get(image) + draw),
                            "the HDR image holds colortex0's world side after " + window);
                }
                chimera.put(image, chimera.get(image) + draw);
            }
        }

        /**
         * A screen change between frames: the world attachments are made anew, the HDR image undefined.
         * What the retired image alone held is lost, the trade-off Chimera accepts there: the Iris
         * model's side it stood in for falls back to that side's graph image, so the frames after
         * still compare exactly.
         */
        void replaceHdr() {
            PostTargetSides.Image hdr = new PostTargetSides.Image(0, PostTargetSides.HDR);
            String held = chimera.get(hdr);
            for (int side = 0; side < 2; side++) {
                String graph = chimera.get(new PostTargetSides.Image(0, side));
                if (held != null && held.equals(iris[side][0]) && !held.equals(graph)) iris[side][0] = graph;
            }
            chimera.put(hdr, "undefined");
            sides.hdrReplaced();
        }

        boolean anyImageHolds(String token) {
            return chimera.values().stream().anyMatch(value -> value != null && value.contains(token));
        }

        private void check(boolean condition, String message) {
            if (!condition) mismatches.add("frame " + frame + ": " + message);
        }
    }

    // ---------------------------------------------------------------- regression scenarios

    /**
     * Glimmer's prepare2 names colortex0 and writes nothing. With colortex0 cleared, Iris's flip there
     * lands on alt, which its frame-start clear left at the clear colour and its horizon drew on, and
     * the world draws into it. Chimera once handed
     * back last frame's final image, which Glimmer's deferred sky adds under the sky, so the horizon
     * summed to a glow (KNOW-1112).
     */
    @Test
    void anUnwrittenColortex0InPrepareHandsTheWorldItsClearedSide(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "prepare2", "/* RENDERTARGETS: 0 */\nvoid main() { discard; }\n",
                "deferred", "uniform sampler2D colortex0;\n/* RENDERTARGETS: 0 */\n" + copies("colortex0")), "");
        for (int frame = 0; frame < 3; frame++) harness.run(Frame.plain(Set.of(0)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertEquals("clear0>horizon@2>prepare2@2>world-PREPARE@2", harness.reads.get("deferred").get(0),
                "the world must draw over the cleared side prepare2 left, not an older frame");
    }

    /**
     * Glimmer as it ships, with colortex0Clear=false. Iris draws its horizon before prepare2, onto
     * the alt side the world draws on after prepare2's flip, so every frame's horizon covers the
     * last one's there. Chimera drew it into the HDR image while that stood in for main, and the
     * world then took alt without it: the horizon summed frame on frame to a glow again.
     */
    @Test
    void theHorizonLandsOnTheSideAPrepareFlipHandsACarriedWorld(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "prepare2", "/* RENDERTARGETS: 0 */\nvoid main() { discard; }\n",
                "deferred", "const bool colortex0Clear = false;\nuniform sampler2D colortex0;\n"
                        + "/* RENDERTARGETS: 0 */\n" + copies("colortex0")), "");
        for (int frame = 0; frame < 3; frame++) harness.run(Frame.plain(Set.of(0)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertTrue(harness.reads.get("deferred").get(0).endsWith(">horizon@2>prepare2@2>world-PREPARE@2"),
                "this frame's horizon must lie under prepare2 and the world: " + harness.reads.get("deferred").get(0));
    }

    /**
     * Every way a carried colortex0 can end a frame when a prepare flip puts the world on alt: final
     * on alt with the HDR image standing in for it, final on main, final on alt with the HDR image on
     * main, and then a new HDR image from a screen change. Each next frame must open as Iris's does.
     */
    @Test
    void aCarriedColortex0OpensOnAltWhereverTheLastFrameEnded(@TempDir Path root) throws Exception {
        String prepare = "/* RENDERTARGETS: 0 */\nvoid main() { discard; }\n";
        String keeps = "const bool colortex0Clear = false;\nuniform sampler2D colortex0;\n";
        List<Map<String, String>> packs = List.of(
                Map.of("prepare2", prepare, "deferred1", keeps + "/* RENDERTARGETS: 3 */\n" + copies("colortex0")),
                Map.of("prepare2", prepare, "deferred", keeps + "/* RENDERTARGETS: 0 */\n" + copies("colortex0")),
                Map.of("prepare2", prepare, "deferred", keeps + "/* RENDERTARGETS: 0 */\n" + copies("colortex0"),
                        "composite", keeps + "/* RENDERTARGETS: 0 */\n" + copies("colortex0")));
        for (int index = 0; index < packs.size(); index++) {
            Harness harness = harness(root.resolve("pack" + index), packs.get(index), "");
            for (int frame = 0; frame < 3; frame++) harness.run(Frame.plain(Set.of(0)));
            harness.replaceHdr();
            for (int frame = 3; frame < 5; frame++) harness.run(Frame.plain(Set.of(0)));
            assertTrue(harness.mismatches.isEmpty(), "pack " + index + "\n" + String.join("\n", harness.mismatches));
            assertTrue(!harness.anyImageHolds("undefined"), "pack " + index + ": a new HDR image's content leaked");
        }
    }

    /**
     * A begin pre-flip of colortex0 that prepare's pre-flip undoes: the shadow pass reads alt, but
     * the horizon and the opaque world draw on main, where Iris's gbuffers write.
     */
    @Test
    void theHorizonFollowsTheOpaqueWorldPastABeginFlip(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "deferred1", "uniform sampler2D colortex0;\n/* RENDERTARGETS: 3 */\n" + copies("colortex0")),
                "flip.begin_pre.colortex0=true\nflip.prepare_pre.colortex0=true\n");
        assertTrue(PackPostChain.windowDue(false, harness.plan, PackFrameSchedulePlan.PostWindow.BEGIN));
        for (int frame = 0; frame < 3; frame++) harness.run(Frame.plain(Set.of(0)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertEquals("clear0>horizon@2>world-PREPARE@2", harness.reads.get("deferred1").get(0));
    }

    /**
     * Bloop's composite names colortex13 and writes it only with Distant Horizons; the flip still
     * makes alt current, which holds what the frame started with, and the copy-back at frame end makes
     * main that again: the refraction the water draws into main never outlives its frame.
     */
    @Test
    void anUnwrittenDrawBufferHandsBackWhatTheFrameStartedWith(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "composite", "const bool colortex13Clear = false;\nuniform sampler2D colortex13;\n"
                        + "/* RENDERTARGETS: 0,13 */\n"
                        + "void main() { gl_FragData[0] = texture2D(colortex13, vec2(0.5)); }\n",
                "composite1", "uniform sampler2D colortex13;\n/* RENDERTARGETS: 0 */\n" + copies("colortex13")),
                "");
        for (int frame = 0; frame < 4; frame++) harness.run(Frame.plain(Set.of(0, 13)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertTrue(harness.reads.get("composite").get(13).contains("world"), "composite reads this frame's water");
        String handedBack = harness.reads.get("composite1").get(13);
        assertTrue(!handedBack.contains("world"), "the unwritten flip must hand back what the frame started with: "
                + handedBack);
    }

    /**
     * Photon's flip.composite1.colortex6: deferred3 reads its AO history, writes this frame's to alt,
     * composite writes fog transmittance to main for composite1, and the flip after composite1
     * makes the AO current again; the copy-back hands it to the next frame's deferred3.
     */
    @Test
    void anExplicitFlipHandsTheNextFrameItsHistory(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "deferred3", "const bool colortex6Clear = false;\nuniform sampler2D colortex6;\n"
                        + "/* RENDERTARGETS: 0,6 */\n" + writes(2),
                "composite", "/* RENDERTARGETS: 6 */\n" + writes(1),
                "composite1", "uniform sampler2D colortex6;\n/* RENDERTARGETS: 0 */\n" + copies("colortex6")),
                "flip.composite1.colortex6=true\n");
        for (int frame = 0; frame < 4; frame++) harness.run(Frame.plain(Set.of(0)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertTrue(harness.reads.get("deferred3").get(6).endsWith(">deferred3@2"),
                "deferred3 must read last frame's AO: " + harness.reads.get("deferred3").get(6));
    }

    /**
     * Shrimple's bloom: flip.composite21.colortex0 points composite38's blend at the scene composite21
     * read, not at colortex0's other side, last frame's final image (KNOW-1082, the white VANILLA
     * profile). flip.deferred_pre.colortex0 does the same for deferred14's AO, which blends onto the
     * opaque world the HDR image holds.
     */
    @Test
    void blendsAfterColortex0FlipsLandOnTheScene(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "deferred14", "/* RENDERTARGETS: 0 */\n" + writes(1),
                "composite21", "uniform sampler2D colortex0;\n/* RENDERTARGETS: 15 */\n" + copies("colortex0"),
                "composite38", "uniform sampler2D colortex15;\n/* RENDERTARGETS: 0 */\n" + copies("colortex15")),
                "flip.deferred_pre.colortex0=true\nflip.composite21.colortex0=true\n"
                        + "blend.composite38=ONE ONE ONE ZERO\nblend.deferred14=ZERO SRC_ALPHA ONE ZERO\n");
        for (int frame = 0; frame < 3; frame++) harness.run(Frame.plain(Set.of(0)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertEquals("clear0>horizon@2>world-PREPARE@2>deferred14@2>world-EARLY@2>composite38@2",
                harness.finalReads.get(0),
                "the AO and the bloom must both land on this frame's scene");
    }

    /**
     * Shrimple keeps its opaque and translucent deferred data apart with flip.deferred1.colortex1, a
     * target only the world writes, and flip.composite_pre.colortex1 hands composite the opaque side
     * again (KNOW-1068, KNOW-1069: the world lost its shadows when the flip did nothing).
     */
    @Test
    void aFlipOfAWorldOnlyTargetSplitsOpaqueFromTranslucent(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "deferred1", "/* RENDERTARGETS: 6 */\n" + writes(1),
                "composite1", "uniform sampler2D colortex1;\n/* RENDERTARGETS: 0 */\n" + copies("colortex1")),
                "flip.deferred1.colortex1=true\nflip.composite_pre.colortex1=true\n");
        for (int frame = 0; frame < 3; frame++) harness.run(Frame.plain(Set.of(0, 1)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertEquals("clear1>world-PREPARE@2", harness.reads.get("composite1").get(1),
                "composite must read the opaque data alone");
    }

    /**
     * A step Chimera cannot run passes its input through, so later passes still read the sides Iris's
     * read and colortex0 keeps the last write (Glimmer's water when a later pass fell back).
     */
    @Test
    void aSkippedStepPassesItsInputThrough(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "composite3", "uniform sampler2D colortex0;\n/* RENDERTARGETS: 0,2 */\n" + writes(2),
                "composite4", "uniform sampler2D colortex0;\n/* RENDERTARGETS: 0,2 */\n" + writes(2),
                "composite5", "uniform sampler2D colortex0;\nuniform sampler2D colortex2;\n/* RENDERTARGETS: 0 */\n"
                        + copies("colortex2")), "");
        Frame skipping = new Frame(Set.of("composite4"), Set.of(0), Map.of(), Map.of());
        for (int frame = 0; frame < 3; frame++) harness.run(skipping);
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertTrue(harness.reads.get("composite5").get(0).endsWith(">composite3@2"),
                "composite3's output must survive the skipped composite4: " + harness.reads.get("composite5").get(0));
    }

    /**
     * Stores through colorimg0 and mips of colortex0 need it as a graph image: the HDR image's newer
     * content goes back to the side first, and a store leaves the HDR image behind it.
     */
    @Test
    void storesAndMipsOfColortex0SeeTheWorld(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "deferred1", "uniform sampler2D colortex0;\n/* RENDERTARGETS: 3 */\n" + copies("colortex0"),
                "deferred2", "uniform sampler2D colortex0;\n/* RENDERTARGETS: 0 */\n" + copies("colortex0"),
                "composite1", "uniform sampler2D colortex0;\n/* RENDERTARGETS: 3 */\n" + copies("colortex0")), "");
        Frame frame = new Frame(Set.of(), Set.of(0),
                Map.of("deferred1", Set.of(0), "composite1", Set.of(0)),
                Map.of("deferred2", Set.of(0), "composite1", Set.of(0)));
        for (int index = 0; index < 3; index++) harness.run(frame);
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
    }

    /**
     * colortex0Clear=false keeps colortex0 across frames, as Iris does: main holds the final state's
     * side when the frame starts, and the world draws over it (audit T-A3).
     */
    @Test
    void anUnclearedColortex0CarriesTheFinalImageIntoTheNextFrame(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "deferred1", "const bool colortex0Clear = false;\nuniform sampler2D colortex0;\n"
                        + "/* RENDERTARGETS: 0 */\n" + copies("colortex0"),
                "composite1", "uniform sampler2D colortex0;\n/* RENDERTARGETS: 0 */\n" + copies("colortex0")), "");
        for (int frame = 0; frame < 3; frame++) harness.run(Frame.plain(Set.of(0)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertTrue(harness.reads.get("deferred1").get(0).contains(">composite1@1>horizon@2>world-PREPARE@2"),
                "the world must draw over last frame's final image: " + harness.reads.get("deferred1").get(0));
        assertTrue(harness.frameStartCopies <= 2, "alt back from the HDR image, main into it: "
                + harness.frameStartCopies);
    }

    /**
     * Every screen opened or closed makes a new HDR image between frames, without touching the pack
     * targets. The frame that follows must take colortex0 from the graph sides; written back as the
     * side the old image stood in for, the new image's undefined content would land on colortex0's alt.
     */
    @Test
    void aNewHdrImageFromAScreenChangeIsNeverWrittenBack(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "deferred1", "const bool colortex0Clear = false;\nuniform sampler2D colortex0;\n"
                        + "/* RENDERTARGETS: 0 */\n" + copies("colortex0"),
                "composite1", "uniform sampler2D colortex0;\n/* RENDERTARGETS: 0 */\n" + copies("colortex0")), "");
        for (int frame = 0; frame < 2; frame++) harness.run(Frame.plain(Set.of(0)));
        harness.replaceHdr();
        for (int frame = 2; frame < 4; frame++) harness.run(Frame.plain(Set.of(0)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertTrue(!harness.anyImageHolds("undefined"), "a new HDR image's content reached a target");
    }

    /**
     * Alpha Piscium, Ebin and RenderPearl keep colortex0 and no pass writes it: the HDR image already
     * holds main when the frame ends, so carrying it over copies nothing, and the world draws on.
     */
    @Test
    void aColortex0NoPassWritesCarriesOverWithoutACopy(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "deferred1", "const bool colortex0Clear = false;\nuniform sampler2D colortex0;\n"
                        + "/* RENDERTARGETS: 3 */\n" + copies("colortex0")), "");
        for (int frame = 0; frame < 3; frame++) harness.run(Frame.plain(Set.of(0)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertEquals(0, harness.frameStartCopies);
        assertTrue(harness.reads.get("deferred1").get(0).startsWith("clear0>horizon@0>world-PREPARE@0>world-EARLY@0"),
                harness.reads.get("deferred1").get(0));
    }

    /**
     * Kappa's composite2 samples its reflection capture colortex12, whose only writer composite1 is off
     * by default. Iris creates the sampled target and clears it on its first frame (clearPassesFull),
     * so composite2 reads the clear colour, never undefined memory.
     */
    @Test
    void aTargetNoPassWritesReadsItsFirstFrameClear(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "composite1", "const bool colortex12Clear = false;\nuniform sampler2D colortex0;\n"
                        + "/* RENDERTARGETS: 12 */\n" + copies("colortex0"),
                "composite2", "uniform sampler2D colortex12;\n/* RENDERTARGETS: 0 */\n" + copies("colortex12")),
                "program.composite1.enabled=false\n");
        for (int frame = 0; frame < 2; frame++) harness.run(Frame.plain(Set.of(0)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertEquals("clear12", harness.reads.get("composite2").get(12));
    }

    /**
     * A prepare group with no pass and a flip.prepare_pre entry still opens, as Iris builds every
     * group (audit FD4): the opaque world draws colortex0 and colortex3 on alt, and the HDR image
     * moves to colortex0's alt side. A pre-flip set back to false opens nothing.
     */
    @Test
    void aGroupWhoseOnlyWorkIsAPreFlipStillOpens(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "deferred1", "uniform sampler2D colortex0;\nuniform sampler2D colortex3;\n/* RENDERTARGETS: 0 */\n"
                        + copies("colortex3")),
                "flip.prepare_pre.colortex0=true\nflip.prepare_pre.colortex3=true\n"
                        + "flip.begin_pre.colortex5=true\nflip.begin_pre.colortex5=false\n");
        assertTrue(PackPostChain.windowDue(false, harness.plan, PackFrameSchedulePlan.PostWindow.PREPARE));
        assertTrue(!PackPostChain.windowDue(false, harness.plan, PackFrameSchedulePlan.PostWindow.BEGIN),
                "a pre-flip set back to false flips nothing");
        for (int frame = 0; frame < 3; frame++) harness.run(Frame.plain(Set.of(0, 3)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertEquals("clear3>world-PREPARE@2", harness.reads.get("deferred1").get(3));
        assertEquals("clear0>horizon@2>world-PREPARE@2", harness.reads.get("deferred1").get(0));
    }

    /** A held draw buffer (flip=false) is written on its other side and stays unread until a later flip. */
    @Test
    void aHeldDrawBufferKeepsItsReadSide(@TempDir Path root) throws Exception {
        Harness harness = harness(root, Map.of(
                "composite1", "uniform sampler2D colortex2;\n/* RENDERTARGETS: 0,2 */\n" + writes(2),
                "composite2", "uniform sampler2D colortex2;\n/* RENDERTARGETS: 0 */\n" + copies("colortex2"),
                "composite3", "uniform sampler2D colortex2;\n/* RENDERTARGETS: 0 */\n" + copies("colortex2")),
                "flip.composite1.colortex2=false\nflip.composite2.colortex2=true\n");
        for (int frame = 0; frame < 2; frame++) harness.run(Frame.plain(Set.of(0)));
        assertTrue(harness.mismatches.isEmpty(), String.join("\n", harness.mismatches));
        assertEquals("clear2", harness.reads.get("composite2").get(2), "the held write must not be read yet");
        assertEquals("clear2>composite1@1", harness.reads.get("composite3").get(2), "the flip exposes it");
    }

    // ---------------------------------------------------------------- the corpus

    /**
     * Every corpus pack, over several frames: as planned, with every third step skipped, and with
     * every step storing to and mipping colortex0 and mipping its draw buffers, so the HDR image's
     * write-backs run everywhere. Chimera's sides must match Iris's on every read, base and frame.
     */
    @Test
    @Tag("packs")
    void everyCorpusPackReadsAndWritesTheSidesIrisDoes() throws Exception {
        List<Path> packs = new ArrayList<>(TestPacks.extra());
        packs.add(TestPacks.complementary());
        packs.add(TestPacks.bsl());
        List<String> failures = new ArrayList<>();
        int steps = 0;
        for (Path pack : packs) {
            PackProbe.Analysis analysis = PackProbe.analyze(pack);
            PackTargetGraphPlan graph = PackTargetGraphPlan.build(analysis.plan().activePrograms(), analysis.config(),
                    analysis.plan().resources(), 1920, 1080, 8, 16384);
            Set<Integer> world = worldTargets(analysis.plan().activePrograms());
            List<PackFlipPlan.Step> planned = graph.flips().steps();
            steps += planned.size();
            Set<String> skipped = new TreeSet<>();
            Map<String, Set<Integer>> stores = new HashMap<>();
            Map<String, Set<Integer>> mips = new HashMap<>();
            for (int index = 0; index < planned.size(); index++) {
                PackFlipPlan.Step step = planned.get(index);
                if (index % 3 == 2) skipped.add(step.program());
                stores.put(step.program(), Set.of(0));
                Set<Integer> mipped = new TreeSet<>(step.drawBuffers());
                mipped.add(0);
                mips.put(step.program(), mipped);
            }
            List<Frame> variants = List.of(Frame.plain(world), new Frame(skipped, world, Map.of(), Map.of()),
                    new Frame(Set.of(), world, stores, mips));
            for (Frame variant : variants) {
                Harness harness = Harness.of(graph);
                for (int frame = 0; frame < 3; frame++) harness.run(variant);
                harness.mismatches.stream().limit(5).forEach(failure -> failures.add(pack.getFileName() + " "
                        + (variant.skipped().isEmpty() ? variant.stores().isEmpty() ? "plain" : "stores" : "skips")
                        + ": " + failure));
            }
        }
        System.out.printf("[chimera] flip sides: %d packs, %d steps%n", packs.size(), steps);
        assertTrue(steps > 0 && failures.isEmpty(), String.join("\n", failures));
    }

    /** colortex0 and every target a world program writes. */
    private static Set<Integer> worldTargets(List<PackProgramPlan> programs) {
        Set<Integer> targets = new TreeSet<>(Set.of(0));
        for (PackProgramPlan program : programs) {
            if (program.geometryOutputPlan() != null && program.executable()) {
                targets.addAll(program.geometryOutputPlan().targetSlots());
            }
        }
        targets.removeIf(target -> target < 0 || target > 15);
        return targets;
    }

    // ---------------------------------------------------------------- fixtures

    private static String writes(int outputs) {
        StringBuilder body = new StringBuilder("void main() {\n");
        for (int index = 0; index < outputs; index++) {
            body.append("    gl_FragData[").append(index).append("] = vec4(").append(index).append(".0);\n");
        }
        return body.append("}\n").toString();
    }

    private static String copies(String sampler) {
        return "varying vec2 texcoord;\nvoid main() {\n    gl_FragColor = texture2D(" + sampler + ", texcoord);\n}\n";
    }

    private static Harness harness(Path root, Map<String, String> programs, String properties) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        for (Map.Entry<String, String> program : programs.entrySet()) {
            Files.writeString(shaders.resolve(program.getKey() + ".fsh"), "#version 120\n" + program.getValue());
        }
        Files.writeString(shaders.resolve("final.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "varying vec2 texcoord;\n\nvoid main() {\n    gl_FragColor = texture2D(colortex0, texcoord);\n}\n");
        Files.writeString(shaders.resolve("shaders.properties"), properties);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(analysis.plan().activePrograms(), analysis.config(),
                analysis.plan().resources(), 16, 16, 8, 16384);
        assertTrue(graph.steps().stream().allMatch(step -> step.executable()),
                "every fixture pass must plan: " + graph.deviations());
        return Harness.of(graph);
    }
}
