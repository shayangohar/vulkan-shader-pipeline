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
     * The geometry and tessellation stages whose files sit beside the fragment stage and that Iris
     * runs between the vertex and fragment stages. A program another resolves to takes them along.
     */
    public List<String> geometryStages(boolean tessellation) {
        return geometryStageFiles().keySet().stream()
                .filter(stage -> runsBetweenStages(stage, tessellation))
                .toList();
    }

    /** Every geometry and tessellation stage file beside the fragment stage, by stage name, run or not. */
    public Map<String, Path> geometryStageFiles() {
        if (fragmentPath == null || fragmentPath.getFileName() == null) return Map.of();
        String file = fragmentPath.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String base = dot < 0 ? file : file.substring(0, dot);
        Map<String, Path> files = new TreeMap<>();
        GEOMETRY_STAGE_FILES.forEach((extension, stage) -> {
            Path path = fragmentPath.resolveSibling(base + extension);
            if (Files.isRegularFile(path)) files.put(stage, path);
        });
        return files;
    }

    /**
     * The prepared sources of the stages Iris runs between the vertex and fragment stages, by stage
     * name: the geometry stage, and the tessellation stages when {@code tessellation}.
     */
    public Map<String, String> preparedGeometryStages(boolean tessellation) {
        Map<String, String> sources = new TreeMap<>();
        preparedSources.forEach((stage, prepared) -> {
            if (runsBetweenStages(stage, tessellation) && prepared.source() != null) {
                sources.put(stage, prepared.source());
            }
        });
        return sources;
    }

    /**
     * Whether Iris runs a stage of this name between the vertex and fragment stages
     * (ProgramSet.readProgramSource): the geometry stage whenever its file exists, the
     * tessellation stages only when the pack enables TESSELLATION_SHADERS.
     */
    public static boolean runsBetweenStages(String stage, boolean tessellation) {
        return stage.equals("geometry") || tessellation && (stage.equals("tess_control")
                || stage.equals("tess_evaluation"));
    }

    private static final Map<String, String> GEOMETRY_STAGE_FILES = new TreeMap<>(Map.of(
            ".gsh", "geometry", ".tcs", "tess_control", ".tes", "tess_evaluation"));
}
