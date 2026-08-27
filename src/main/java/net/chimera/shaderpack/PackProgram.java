package net.chimera.shaderpack;

import java.nio.file.Path;

/**
 * A single OptiFine-style shader program loaded from a pack directory.
 *
 * @param name           program name as declared in shaders.json (e.g. "composite")
 * @param vertexSource   legacy vertex GLSL, may be null (pack VS is not used in the wedge)
 * @param fragmentSource legacy fragment GLSL, never null (drives the seam)
 * @param fragmentPath   on-disk path of the fragment source, used to resolve
 *                       relative #include files during conversion; null when the
 *                       source was provided without a backing file (never in practice)
 */
public record PackProgram(String name, String vertexSource, String fragmentSource, Path fragmentPath) {
}