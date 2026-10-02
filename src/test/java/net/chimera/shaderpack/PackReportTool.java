package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Developer tool: runs the offline pack analysis ({@link PackProbe}) on each
 * pack named by {@code -Dchimera.packReport.packs} (path-separator list) and
 * prints, per pack, every program's support status and its blocking reasons,
 * then the blocking reasons ranked by how many programs they touch. No GPU and
 * no game: this is the plan-only view a runtime load starts from.
 *
 * <p>A reason counts as blocking when its program falls back and the reason
 * names something rejected, unsupported, missing or unmapped. Informational
 * deviations (translations applied, constants injected) are left out.</p>
 */
public final class PackReportTool {
    private static final String BLOCKING =
            ".*(UNSUPPORTED|REJECTED|MISSING|NOT_MAPPED|MALFORMED|CONFLICT|FAILED).*";

    private PackReportTool() {}

    public static void main(String[] args) {
        String packs = System.getProperty("chimera.packReport.packs", "");
        if (packs.isBlank()) {
            throw new IllegalArgumentException("set -Dchimera.packReport.packs=<pack>[;<pack>...]");
        }
        boolean verbose = Boolean.getBoolean("chimera.packReport.verbose");
        for (String pack : packs.split(java.io.File.pathSeparator)) {
            report(Path.of(pack.trim()), verbose);
        }
    }

    private static void report(Path pack, boolean verbose) {
        PackProbe.Analysis analysis = PackProbe.analyze(pack);
        ConformanceReport report = analysis.report();
        Map<String, TreeSet<String>> byReason = new TreeMap<>();
        int fallback = 0;
        List<String> lines = new ArrayList<>();
        List<String> disabled = analysis.resolution().disabledPrograms();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            if (disabled.contains(program.name())) {
                // The pack switches it off for the selected options, as Iris does: not a fallback.
                lines.add(String.format("  %-34s %-26s", program.name(), "DISABLED_BY_PACK"));
                continue;
            }
            boolean falls = program.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK
                    || program.support() == ConformanceReport.SupportStatus.UNSUPPORTED;
            if (falls) fallback++;
            List<String> blocking = new ArrayList<>();
            for (String deviation : program.deviations()) {
                if (falls && deviation.matches(BLOCKING)) {
                    blocking.add(deviation);
                    byReason.computeIfAbsent(deviation, key -> new TreeSet<>()).add(program.name());
                }
            }
            lines.add(String.format("  %-34s %-26s %s", program.name(), program.support(),
                    verbose ? program.deviations() : blocking));
        }
        System.out.printf("== %s: programs=%d disabled=%d fallback=%d%n", pack.getFileName(),
                report.programs().size(), disabled.size(), fallback);
        lines.forEach(System.out::println);
        System.out.println("  -- custom value diagnostics");
        analysis.settings().runtimeSettings().deviations().stream()
                .filter(deviation -> deviation.startsWith("CUSTOM_") || deviation.startsWith("EXPRESSION_"))
                .forEach(deviation -> System.out.println("  " + deviation));
        System.out.println("  -- blocking reasons by program count");
        byReason.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .forEach(entry -> System.out.printf("  %3d %s%n", entry.getValue().size(), entry.getKey()));
        compileExecutablePrograms(pack, analysis);
    }

    private static void compileExecutablePrograms(Path pack, PackProbe.Analysis analysis) {
        PackCompileCheck.Result result = PackCompileCheck.run(pack, analysis);
        System.out.printf("  -- shader compile: %d programs compile, %d failures%n",
                result.compiled(), result.failures().size());
        result.failures().forEach(failure -> System.out.println("  COMPILE_FAILED " + failure));
    }
}
