package net.chimera.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failure's report says what failed, on what, with which pack, and names no user (TASK-922). */
final class PackFailureReportTest {
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 11, 12, 19, 5, 3);
    private static final PackFailureReport.Environment ENVIRONMENT = new PackFailureReport.Environment(
            "0.1.0+mvp", "ae96b9c6", "0.3.5-ALPHA+1.21.1", "21.1.262", "1.21.1", "NVIDIA GeForce RTX 5080",
            "621.14", "1.4.312", "Windows 11 10.0 amd64", "21.0.7 Microsoft");

    @Test
    void aReportHasEverySectionTheTaskNames() {
        PackFailureNotices.Failure failure = new PackFailureNotices.Failure("RedHat v34.3.1", "final",
                "shader-compilation", PackFailureNotices.Outcome.SKIPPED, "SHADER_COMPILATION_FAILED",
                "pack_final.fsh:518: error: 'lenscolor' : redefinition");
        PackFailureReport.Pack pack = new PackFailureReport.Pack("RedHat v34.3.1.zip", "ab12", "HIGH",
                Map.of("DIRTY_LENS", "true"), "{\"formatVersion\":1}");
        String text = PackFailureReport.text(failure, new IllegalStateException("boom"), ENVIRONMENT, pack, WHEN);
        for (String expected : List.of("Chimera failure report, 2026-11-12 19:05:03", "Program or pass: final",
                "Phase: shader-compilation", "Outcome: SKIPPED", "'lenscolor' : redefinition",
                "java.lang.IllegalStateException: boom", "Chimera: 0.1.0+mvp (commit ae96b9c6)",
                "VulkanMod: 0.3.5-ALPHA+1.21.1", "NeoForge: 21.1.262", "Minecraft: 1.21.1",
                "GPU: NVIDIA GeForce RTX 5080", "Driver: 621.14", "Vulkan: 1.4.312", "OS: Windows 11",
                "Name: RedHat v34.3.1.zip", "SHA-256: ab12", "Profile: HIGH", "DIRTY_LENS=true",
                "{\"formatVersion\":1}")) {
            assertTrue(text.contains(expected), expected + " in\n" + text);
        }
    }

    @Test
    void aReportNamesNoUser() {
        String text = "at C:\\Users\\Alex\\AppData\\Roaming\\.minecraft and c:/users/alex/shaderpacks, "
                + "player Alex_Plays joined; alexander stays; user alex.";
        String redacted = PackFailureReport.redact(text, List.of("C:\\Users\\Alex"), List.of("alex", "Alex_Plays"));
        assertEquals("at ~\\AppData\\Roaming\\.minecraft and ~/shaderpacks, player <user> joined; alexander stays;"
                + " user <user>.", redacted);
        assertFalse(redacted.toLowerCase().contains("users\\alex"));
    }

    /** A user folder that is not the home folder as Java names it still names no one. */
    @Test
    void anyUserFolderNamesNoOne() {
        String text = "C:\\Users\\ALEXAN~1\\AppData\\Local\\Temp\\chimera-pack-1\\shaders; "
                + "{\"path\":\"D:\\\\Users\\\\alex.work\\\\x\"}; /home/alex/.minecraft; /Users/Alex Smith/x; "
                + "C:/Documents and Settings/alex/y; shaders/Users/lib.glsl";
        assertEquals("C:\\Users\\<user>\\AppData\\Local\\Temp\\chimera-pack-1\\shaders; "
                + "{\"path\":\"D:\\\\Users\\\\<user>\\\\x\"}; /home/<user>/.minecraft; /Users/<user>/x; "
                + "C:/Documents and Settings/<user>/y; shaders/Users/lib.glsl",
                PackFailureReport.redact(text, List.of(), List.of()));
    }

    /** A deviation keeps a path's word characters only (PackPlanBuilder.failureReason). */
    @Test
    void aPathADeviationSpelledNamesNoOne() {
        String text = "PROGRAM_PLANNING_FAILED:UncheckedIOException_C_Users_Alex_Documents_pack_x.glsl; "
                + "PROGRAM_REPORT_FAILED:X_D_Users_bob.smith_y";
        assertEquals("PROGRAM_PLANNING_FAILED:UncheckedIOException_~_Documents_pack_x.glsl; "
                + "PROGRAM_REPORT_FAILED:X_D_Users_<user>_y",
                PackFailureReport.redact(text, List.of("C:\\Users\\Alex"), List.of()));
    }

    @Test
    void reportsGetTheirOwnFilesAndTheFolderKeepsTheNewest(@TempDir Path game) throws IOException {
        Path first = PackFailureReport.write(game, "RedHat v34.3.1.zip", "one", WHEN);
        Path second = PackFailureReport.write(game, "RedHat v34.3.1.zip", "two", WHEN);
        assertEquals(game.resolve("chimera/reports/2026-11-12_19.05.03-RedHat_v34.3.1.zip.txt"), first);
        assertEquals("2026-11-12_19.05.03-RedHat_v34.3.1.zip-2.txt", second.getFileName().toString());
        assertEquals("one", Files.readString(first));
        for (int minute = 0; minute < PackFailureReport.KEPT_REPORTS; minute++) {
            PackFailureReport.write(game, "Pack", "later", WHEN.plusMinutes(minute + 1));
        }
        try (var reports = Files.list(game.resolve("chimera/reports"))) {
            assertEquals(PackFailureReport.KEPT_REPORTS, reports.count());
        }
        assertFalse(Files.exists(first), "the oldest go first");
    }

    @Test
    void aPackFileIsHashedAndAFolderIsNot(@TempDir Path root) throws IOException {
        Path zip = Files.writeString(root.resolve("pack.zip"), "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PackFailureReport.hash(zip));
        assertTrue(PackFailureReport.hash(root).startsWith("folder"));
        assertEquals("none", PackFailureReport.hash(null));
    }
}
