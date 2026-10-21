package net.chimera.shaderpack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A single OptiFine-style shader program loaded from a pack. */
public record PackProgram(
        String name,
        String fragmentSource,
        Path fragmentPath,
        String vertexSource,
        Path vertexPath,
        Path sourceRoot,
        String preparedFragmentSource,
        String preparedVertexSource,
        List<String> preparationDeviations,
        String variantFolder,
        Map<String, PreparedShaderSource> preparedSources
) {
    public PackProgram(String name, String fragmentSource, Path fragmentPath,
                       String vertexSource, Path vertexPath) {
        this(name, fragmentSource, fragmentPath, vertexSource, vertexPath,
                null, null, null, List.of(), "", Map.of());
    }

    public PackProgram(String name, String fragmentSource, Path fragmentPath) {
        this(name, fragmentSource, fragmentPath, null, null);
    }

    public PackProgram {
        preparationDeviations = preparationDeviations == null
                ? List.of()
                : preparationDeviations.stream().distinct().sorted().toList();
        variantFolder = variantFolder == null ? "" : variantFolder;
        preparedSources = preparedSources == null
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(preparedSources));
    }

    /** Prepared source is used by probing and runtime conversion when present. */
    public String executableFragmentSource() {
        return preparedFragmentSource != null ? preparedFragmentSource : fragmentSource;
    }

    public String executableVertexSource() {
        return preparedVertexSource != null ? preparedVertexSource : vertexSource;
    }

    /**
     * The geometry and tessellation stages whose files sit beside the fragment stage. Iris runs
     * each one whose file exists (ProgramSet.readProgramSource), between the vertex and fragment
     * stages; Chimera runs neither (TASK-648). A program another resolves to takes them along.
     */
    public List<String> geometryStages() {
        if (fragmentPath == null || fragmentPath.getFileName() == null) return List.of();
        String file = fragmentPath.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String base = dot < 0 ? file : file.substring(0, dot);
        return GEOMETRY_STAGE_FILES.entrySet().stream()
                .filter(stage -> Files.isRegularFile(fragmentPath.resolveSibling(base + stage.getKey())))
                .map(Map.Entry::getValue)
                .toList();
    }

    private static final Map<String, String> GEOMETRY_STAGE_FILES = new TreeMap<>(Map.of(
            ".gsh", "geometry", ".tcs", "tess_control", ".tes", "tess_evaluation"));
}
