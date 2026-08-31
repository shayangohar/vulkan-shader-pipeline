package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.List;

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
        String variantFolder
) {
    public PackProgram(String name, String fragmentSource, Path fragmentPath,
                       String vertexSource, Path vertexPath) {
        this(name, fragmentSource, fragmentPath, vertexSource, vertexPath,
                null, null, null, List.of(), "");
    }

    public PackProgram(String name, String fragmentSource, Path fragmentPath) {
        this(name, fragmentSource, fragmentPath, null, null);
    }

    public PackProgram {
        preparationDeviations = preparationDeviations == null
                ? List.of()
                : preparationDeviations.stream().distinct().sorted().toList();
        variantFolder = variantFolder == null ? "" : variantFolder;
    }

    /** Prepared source is used by probing and runtime conversion when present. */
    public String executableFragmentSource() {
        return preparedFragmentSource != null ? preparedFragmentSource : fragmentSource;
    }

    public String executableVertexSource() {
        return preparedVertexSource != null ? preparedVertexSource : vertexSource;
    }
}
