package net.chimera.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each distinct failure is told once, with Copy Info, and waits for a player in a world; the
 * status command keeps the current load's (TASK-922). The chat component itself (its Copy Info
 * click and hover) needs Minecraft's registries, so the game shows it, not this test.
 */
final class PackFailureNoticesTest {
    @AfterEach
    void forget() {
        PackFailureNotices.reset();
    }

    private static PackFailureNotices.Failure skipped(String pack, String program, String phase) {
        return new PackFailureNotices.Failure(pack, program, phase, PackFailureNotices.Outcome.SKIPPED,
                "compile error: 'lenscolor' : redefinition", "pack_" + program + ".fsh:518: error: 'lenscolor' : "
                + "redefinition");
    }

    @Test
    void aFailureIsToldOnceWithCopyInfo() {
        PackFailureNotices.beginLoad("RedHat v34.3.1", null);
        PackFailureNotices.record(skipped("RedHat v34.3.1", "final", "shader-compilation"));
        PackFailureNotices.publish();
        List<PackFailureNotices.Line> held = PackFailureNotices.held();
        assertEquals(1, held.size());
        assertEquals("[Chimera] RedHat v34.3.1: Chimera skipped final (compile error: 'lenscolor' : redefinition). ",
                held.get(0).text());
        assertFalse(held.get(0).severe(), "a skip is a warning; the pack still runs");
        assertTrue(held.get(0).info().contains("'lenscolor' : redefinition"), held.get(0).info());

        // The same program again, found by another phase or on the next load, is not told again.
        PackFailureNotices.record(skipped("RedHat v34.3.1", "final", "load"));
        PackFailureNotices.publish();
        assertEquals(1, PackFailureNotices.held().size());
        assertEquals(1, PackFailureNotices.current().size());
        assertEquals(List.of("  final: skipped (shader-compilation: compile error: 'lenscolor' : redefinition)"),
                PackFailureNotices.statusLines());
    }

    @Test
    void aFallbackToVanillaTakesInTheSkipsItsLoadHasNotTold() {
        PackFailureNotices.beginLoad("Insanity", null);
        PackFailureNotices.record(skipped("Insanity", "gbuffers_entities", "shader-compilation"));
        PackFailureNotices.record(new PackFailureNotices.Failure("Insanity", "pack", "load",
                PackFailureNotices.Outcome.VANILLA, "it is not supported yet (entity could not load).", "info"));
        PackFailureNotices.publish();
        List<PackFailureNotices.Line> held = PackFailureNotices.held();
        assertEquals(1, held.size(), "no \"skipped\" line beside the fallback");
        assertEquals("[Chimera] Insanity fell back to vanilla: it is not supported yet (entity could not load). ",
                held.get(0).text());
        assertTrue(held.get(0).severe());
        assertTrue(held.get(0).info().contains("Skipped before it:\n- gbuffers_entities: pack_gbuffers_entities.fsh"),
                held.get(0).info());
        assertEquals(" (fallback)", PackFailureNotices.label("Insanity"));
        assertEquals(2, PackFailureNotices.statusLines().size(), "the status still lists the skip");
    }

    @Test
    void aRepeatedFallbackStillCarriesItsRootCause() {
        PackFailureNotices.beginLoad("Insanity", null);
        PackFailureNotices.record(skipped("Insanity", "gbuffers_entities", "shader-compilation"));
        PackFailureNotices.publish();
        // The player changes an option and applies again: the skip was told, the fallback is new.
        PackFailureNotices.beginLoad("Insanity", null);
        PackFailureNotices.record(skipped("Insanity", "gbuffers_entities", "shader-compilation"));
        PackFailureNotices.record(new PackFailureNotices.Failure("Insanity", "pack", "load",
                PackFailureNotices.Outcome.VANILLA, "it is not supported yet (entity could not load).", "info"));
        PackFailureNotices.publish();
        List<PackFailureNotices.Line> held = PackFailureNotices.held();
        assertEquals(2, held.size());
        assertTrue(held.get(1).info().contains("Skipped before it:\n- gbuffers_entities: pack_gbuffers_entities.fsh"),
                held.get(1).info());
    }

    @Test
    void aFailedPackChangeTakesInTheSkipsItsRollbackLeftUntold() {
        PackFailureNotices.beginLoad("B.zip", null);
        PackFailureNotices.record(skipped("B.zip", "final", "shader-compilation"));
        // A contract defect fails B's load; the rollback loads A, which publishes as it ends.
        PackFailureNotices.beginLoad("A.zip", null);
        PackFailureNotices.publish();
        assertTrue(PackFailureNotices.held().isEmpty(), "B's skip waits for B's own notice");
        PackFailureNotices.record(new PackFailureNotices.Failure("B.zip", "pack", "load",
                PackFailureNotices.Outcome.LOAD_FAILED, "contract defect. Previous pack restored.", "info"));
        PackFailureNotices.publish();
        List<PackFailureNotices.Line> held = PackFailureNotices.held();
        assertEquals(1, held.size());
        assertTrue(held.get(0).info().contains("Skipped before it:\n- final:"), held.get(0).info());
    }

    @Test
    void aFailedDimensionVariantDoesNotLabelThePackThatStillRuns() {
        PackFailureNotices.beginLoad("Pack", null);
        PackFailureNotices.record(new PackFailureNotices.Failure("Pack", "dimension minecraft:the_nether",
                "dimension change", PackFailureNotices.Outcome.LOAD_FAILED, "boom. The overworld variant still runs.",
                "info"));
        assertEquals("", PackFailureNotices.label("Pack"));
    }

    @Test
    void aWholePackFailureIsToldEachTimeItHappens() {
        for (int load = 0; load < 2; load++) {
            PackFailureNotices.beginLoad("Previous", null);
            PackFailureNotices.record(new PackFailureNotices.Failure("Broken.zip", "pack", "load",
                    PackFailureNotices.Outcome.LOAD_FAILED, "PACK_ARCHIVE_INVALID. Previous pack restored.", "info"));
            PackFailureNotices.publish();
        }
        assertEquals(2, PackFailureNotices.held().size(), "each failed apply says so, as Iris's does");
        assertEquals("[Chimera] Failed to load Broken.zip: PACK_ARCHIVE_INVALID. Previous pack restored. ",
                PackFailureNotices.held().get(0).text());

        PackFailureNotices.beginLoad("Insanity", null);
        PackFailureNotices.record(new PackFailureNotices.Failure("Insanity", "composite3", "runtime",
                PackFailureNotices.Outcome.VANILLA, "the device failed (VK_ERROR_DEVICE_LOST)", "info"));
        PackFailureNotices.record(new PackFailureNotices.Failure("Other", "dimension minecraft:the_nether",
                "dimension change", PackFailureNotices.Outcome.LOAD_FAILED, "boom. The overworld variant still runs.",
                "info"));
        PackFailureNotices.publish();
        List<PackFailureNotices.Line> held = PackFailureNotices.held();
        assertEquals("[Chimera] Insanity fell back to vanilla after composite3 failed: the device failed"
                + " (VK_ERROR_DEVICE_LOST) ", held.get(2).text());
        assertEquals("[Chimera] Failed to load Other (dimension minecraft:the_nether): boom. The overworld variant "
                + "still runs. ", held.get(3).text());
    }

    @Test
    void manyFailuresFoldIntoOneLine() {
        PackFailureNotices.beginLoad("Pack", null);
        for (int index = 0; index < 5; index++) PackFailureNotices.record(skipped("Pack", "composite" + index, "x"));
        PackFailureNotices.publish();
        List<PackFailureNotices.Line> held = PackFailureNotices.held();
        assertEquals(PackFailureNotices.LINES_PER_PUBLISH, held.size());
        PackFailureNotices.Line folded = held.get(held.size() - 1);
        assertEquals("[Chimera] Pack: 3 more failures; /chimera pack status lists them.", folded.text());
        assertNull(folded.info(), "the folded line has no Copy Info of its own");
        assertEquals(5, PackFailureNotices.statusLines().size());
    }

    @Test
    void eachLoadListsItsOwnFailuresAndTellsOnlyNewOnes() {
        PackFailureNotices.beginLoad("A", null);
        PackFailureNotices.record(skipped("A", "final", "x"));
        PackFailureNotices.publish();
        PackFailureNotices.beginLoad("A", null);
        assertTrue(PackFailureNotices.current().isEmpty(), "a reload lists what it finds itself");
        PackFailureNotices.record(skipped("A", "final", "x"));
        PackFailureNotices.publish();
        assertEquals(1, PackFailureNotices.current().size());
        assertEquals(1, PackFailureNotices.held().size(), "told once a game");

        // Within a load the first record wins: the build failure, not the load's report of it.
        PackFailureNotices.record(new PackFailureNotices.Failure("A", "final", "load",
                PackFailureNotices.Outcome.SKIPPED, "PACK_PIPELINE_BUILD_FAILED:final", "row"));
        PackFailureNotices.publish();
        assertEquals(1, PackFailureNotices.held().size());
        assertEquals("compile error: 'lenscolor' : redefinition", PackFailureNotices.current().get(0).reason());

        // A later load whose final fails for a new reason tells that one.
        PackFailureNotices.beginLoad("A", null);
        PackFailureNotices.record(new PackFailureNotices.Failure("A", "final", "shader-compilation",
                PackFailureNotices.Outcome.SKIPPED, "compile error: 'bloom' : undeclared identifier", "info"));
        PackFailureNotices.publish();
        assertEquals(2, PackFailureNotices.held().size(), "a new failure of the same program is told");
    }

    @Test
    void eachToldFailureGetsARedactedReportItsLineLinks(@TempDir Path reports) {
        List<PackFailureNotices.Failure> written = new ArrayList<>();
        PackFailureNotices.setReporter(new PackFailureNotices.Reporter() {
            @Override
            public Path write(PackFailureNotices.Failure failure) {
                written.add(failure);
                return reports.resolve("report-" + written.size() + ".txt");
            }

            @Override
            public String redact(String text) {
                return text.replace("C:\\Users\\Alex", "~");
            }
        });
        PackFailureNotices.beginLoad("Pack", null);
        PackFailureNotices.record(new PackFailureNotices.Failure("Pack", "final", "shader-compilation",
                PackFailureNotices.Outcome.SKIPPED, "compile error", "at C:\\Users\\Alex\\shaderpacks"));
        for (int index = 0; index < 4; index++) PackFailureNotices.record(skipped("Pack", "composite" + index, "x"));
        PackFailureNotices.publish();
        assertEquals(5, written.size(), "a folded failure still gets its report");
        PackFailureNotices.Line first = PackFailureNotices.held().get(0);
        assertEquals(reports.resolve("report-1.txt"), first.report());
        assertEquals("at ~\\shaderpacks\nReport: chimera/reports/report-1.txt", first.info());
        assertNull(PackFailureNotices.held().get(PackFailureNotices.LINES_PER_PUBLISH - 1).report());
    }

    @Test
    void aReporterThatThrowsDoesNotFailTheHandlerThatTells() {
        PackFailureNotices.setReporter(new PackFailureNotices.Reporter() {
            @Override
            public Path write(PackFailureNotices.Failure failure) {
                throw new IllegalStateException("no device yet");
            }

            @Override
            public String redact(String text) {
                return text;
            }
        });
        PackFailureNotices.beginLoad("Pack", null);
        PackFailureNotices.record(skipped("Pack", "composite", "runtime"));
        PackFailureNotices.record(skipped("Pack", "final", "runtime"));
        PackFailureNotices.publish();
        assertTrue(PackFailureNotices.held().isEmpty(), "both lines are dropped, and logged, not thrown");
        assertEquals(2, PackFailureNotices.current().size(), "the status still lists them");
    }

    @Test
    void theLabelSaysFallbackOrHowManyWereSkipped() {
        PackFailureNotices.beginLoad("Pack", null);
        assertEquals("", PackFailureNotices.label("Pack"));
        PackFailureNotices.record(skipped("Pack", "final", "x"));
        PackFailureNotices.record(skipped("Pack", "composite5", "x"));
        assertEquals(" (2 skipped)", PackFailureNotices.label("Pack"));
        assertEquals("", PackFailureNotices.label("Other"), "another pack's failures are not this one's");
        PackFailureNotices.record(new PackFailureNotices.Failure("Pack", "pack", "load",
                PackFailureNotices.Outcome.VANILLA, "it is not supported yet.", "info"));
        assertEquals(" (fallback)", PackFailureNotices.label("Pack"), "as Iris labels a pack that fell back");
        PackFailureNotices.beginLoad("Pack", null);
        assertEquals("", PackFailureNotices.label("Pack"), "a new load starts clean");
        // A saved pack whose source no longer loads renders vanilla as it starts.
        PackFailureNotices.record(new PackFailureNotices.Failure("Pack", "pack", "load",
                PackFailureNotices.Outcome.LOAD_FAILED, "PACK_ARCHIVE_INVALID. Rendering vanilla.", "info"));
        assertEquals(" (fallback)", PackFailureNotices.label("Pack"));
    }

    @Test
    void aReasonIsOneShortLine() {
        assertEquals("first", PackFailureNotices.oneLine(" first\nsecond"));
        assertEquals("unknown", PackFailureNotices.oneLine(null));
        assertEquals(163, PackFailureNotices.oneLine("x".repeat(500)).length());
    }
}
