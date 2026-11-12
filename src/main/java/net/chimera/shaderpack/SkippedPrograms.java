package net.chimera.shaderpack;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Which skipped programs change what renders (TASK-922): a chat line names those, and the log warns
 * of them. A program the pack switched off, one Iris would not run here either, or one only another
 * mod's renderer runs changes nothing, so its skip is expected and stays in debug.log.
 */
public final class SkippedPrograms {
    /** The report labels that say a skip changes nothing Iris would draw here. */
    private static final List<String> EXPECTED = List.of(
            "PROGRAM_DISABLED:",             // the pack switched the program off
            "IRIS_PROGRAM_UNUSED:",          // Iris never runs it here: another mod's (Colorwheel, Physics Mod)
            "IRIS_FALLBACK_SOURCE_ONLY",     // Iris reads it only as another program's fallback source
            "TERRAIN_LAYER_MERGED_BY_HOST:", // the host draws the layer with gbuffers_terrain (KNOW-811)
            "MISSING_FRAGMENT_SOURCE",       // no fragment stage, so Iris builds no program from it
            PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT); // a cloud program that draws nothing, in Iris too
    /**
     * The words of a deviation that says why a program could not run, the most telling first: a
     * failure, then a limit it met, then what Chimera cannot do.
     */
    private static final List<String> REFUSALS = List.of("FAILED", "CONFLICT", "LIMIT", "UNAVAILABLE",
            "MALFORMED", "UNSUPPORTED", "INELIGIBLE", "BOUNDARY");

    private SkippedPrograms() {}

    /** True when skipping {@code row}'s program changes nothing Iris would draw here. */
    public static boolean expected(ConformanceReport.ProgramReport row) {
        return expected(row.name(), row.deviations());
    }

    static boolean expected(String name, Collection<String> deviations) {
        // Distant Horizons' programs run only under Distant Horizons, which Chimera does not host.
        if (name.startsWith("dh_")) return true;
        return deviations.stream().anyMatch(deviation -> EXPECTED.stream().anyMatch(deviation::startsWith));
    }

    /** The deviations that say why a program could not run, the most telling first, for a notice. */
    public static List<String> refusals(Collection<String> deviations) {
        return deviations.stream()
                .filter(deviation -> rank(deviation) < REFUSALS.size())
                .sorted(Comparator.comparingInt(SkippedPrograms::rank))
                .toList();
    }

    /** The most telling refusal among {@code deviations}; "not installed" without one. */
    public static String reason(Collection<String> deviations) {
        List<String> refusals = refusals(deviations);
        return refusals.isEmpty() ? "not installed" : refusals.get(0);
    }

    /** Where {@code deviation} stands among the refusals; past the end when it is none. */
    private static int rank(String deviation) {
        for (int index = 0; index < REFUSALS.size(); index++) {
            if (deviation.contains(REFUSALS.get(index))) return index;
        }
        return REFUSALS.size();
    }
}
