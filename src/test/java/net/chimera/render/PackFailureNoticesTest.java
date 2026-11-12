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
        assertEquals("[Chimera] RedHat v34.3.1: Chimera skipped final (compile error: 'lenscolor' : redefinition);"
                + " the rest of the pack runs. ", held.get(0).text());
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
    void aFallbackToVanillaIsItsOwnFailure() {
        PackFailureNotices.beginLoad("Insanity", null);
        PackFailureNotices.record(skipped("Insanity", "composite3", "runtime"));
        PackFailureNotices.record(new PackFailureNotices.Failure("Insanity", "composite3", "runtime",
                PackFailureNotices.Outcome.VANILLA, "the device failed (VK_ERROR_DEVICE_LOST)", "info"));
        PackFailureNotices.publish();
        List<PackFailureNotices.Line> held = PackFailureNotices.held();
        assertEquals(2, held.size());
        assertEquals("[Chimera] Insanity fell back to vanilla after composite3 failed: the device failed"
                + " (VK_ERROR_DEVICE_LOST) ", held.get(1).text());
        assertTrue(held.get(1).severe());

        // The pack's own fallback names no program; a failed load names what runs instead.
        PackFailureNotices.record(new PackFailureNotices.Failure("Insanity", "pack", "load",
                PackFailureNotices.Outcome.VANILLA, "it needs features Chimera does not support (X).", "info"));
        PackFailureNotices.record(new PackFailureNotices.Failure("Other", "dimension minecraft:the_nether",
                "dimension change", PackFailureNotices.Outcome.LOAD_FAILED, "boom. The overworld variant still runs.",
                "info"));
        PackFailureNotices.publish();
        held = PackFailureNotices.held();
        assertEquals("[Chimera] Insanity fell back to vanilla: it needs features Chimera does not support (X). ",
                held.get(2).text());
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
    }

    @Test
    void aReasonIsOneShortLine() {
        assertEquals("first", PackFailureNotices.oneLine(" first\nsecond"));
        assertEquals("unknown", PackFailureNotices.oneLine(null));
        assertEquals(163, PackFailureNotices.oneLine("x".repeat(500)).length());
    }
}
