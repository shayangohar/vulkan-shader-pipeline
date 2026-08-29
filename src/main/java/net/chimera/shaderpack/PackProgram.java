package net.chimera.shaderpack;

import java.nio.file.Path;

/**
 * A single OptiFine-style shader program loaded from a pack directory.
 *
 * @param name           program name as declared in shaders.json (e.g. "composite")
 * @param fragmentSource legacy fragment GLSL, never null (drives the seam)
 * @param fragmentPath   on-disk path of the fragment source, used to resolve
 *                       relative #include files during conversion; null when the
 *                       source was provided without a backing file (never in practice)
 * @param vertexSource   optional legacy terrain vertex source
 * @param vertexPath     on-disk path of the optional vertex source
 */
public record PackProgram(
        String name,
        String fragmentSource,
        Path fragmentPath,
        String vertexSource,
        Path vertexPath
) {
    public PackProgram(String name, String fragmentSource, Path fragmentPath) {
        this(name, fragmentSource, fragmentPath, null, null);
    }
}
