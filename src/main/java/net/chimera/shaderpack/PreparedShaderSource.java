package net.chimera.shaderpack;

import java.util.List;

/**
 * One immutable source snapshot produced by the shared pack preprocessor.
 * Paths are relative to the pack shader root so this value is safe to use in
 * deterministic reports and tests.
 */
public record PreparedShaderSource(
        String stage,
        String relativePath,
        String source,
        List<String> dependencies,
        List<String> deviations
) {
    public PreparedShaderSource {
        stage = stage == null ? "" : stage;
        relativePath = relativePath == null ? "" : relativePath.replace('\\', '/');
        dependencies = dependencies == null ? List.of() : dependencies.stream().distinct().sorted().toList();
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public boolean successful() {
        return source != null && deviations.stream().noneMatch(value ->
                value.startsWith("SOURCE_")
                        || (value.startsWith("PREPROCESSOR_")
                        && !value.startsWith("PREPROCESSOR_MACRO_REDEFINED:")));
    }
}
