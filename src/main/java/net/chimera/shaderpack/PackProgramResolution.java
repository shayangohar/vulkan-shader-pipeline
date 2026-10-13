package net.chimera.shaderpack;

import java.util.List;

/** One deterministic answer for a requested standard program family. */
record PackProgramResolution(
        String requestedProgram,
        String selectedProgram,
        String sourcePath,
        int fallbackDepth,
        boolean enabled,
        boolean executable,
        List<String> deviations
) {
    PackProgramResolution {
        requestedProgram = requestedProgram == null ? "" : requestedProgram;
        selectedProgram = selectedProgram == null ? "" : selectedProgram;
        sourcePath = sourcePath == null ? "" : sourcePath.replace('\\', '/');
        fallbackDepth = Math.max(0, fallbackDepth);
        deviations = deviations == null ? List.of()
                : deviations.stream().distinct().sorted().toList();
    }

    boolean direct() {
        return !selectedProgram.isBlank() && fallbackDepth == 0
                && requestedProgram.equals(selectedProgram);
    }

    boolean alias() {
        return !direct() && !selectedProgram.isBlank();
    }
}
