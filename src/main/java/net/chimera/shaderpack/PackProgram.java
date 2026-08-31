package net.chimera.shaderpack;

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

    /** Compatibility constructor for callers that provide prepared text only. */
    public PackProgram(String name, String fragmentSource, Path fragmentPath,
                       String vertexSource, Path vertexPath, Path sourceRoot,
                       String preparedFragmentSource, String preparedVertexSource,
                       List<String> preparationDeviations, String variantFolder) {
        this(name, fragmentSource, fragmentPath, vertexSource, vertexPath, sourceRoot,
                preparedFragmentSource, preparedVertexSource, preparationDeviations,
                variantFolder, legacyPreparedSources(preparedFragmentSource, preparedVertexSource));
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

    private static Map<String, PreparedShaderSource> legacyPreparedSources(
            String fragment,
            String vertex
    ) {
        Map<String, PreparedShaderSource> result = new TreeMap<>();
        if (fragment != null) {
            result.put("fragment", new PreparedShaderSource("fragment", "", fragment,
                    List.of(), List.of()));
        }
        if (vertex != null) {
            result.put("vertex", new PreparedShaderSource("vertex", "", vertex,
                    List.of(), List.of()));
        }
        return result;
    }
}
