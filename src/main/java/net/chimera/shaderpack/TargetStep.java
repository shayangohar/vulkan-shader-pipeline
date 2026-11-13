package net.chimera.shaderpack;

import java.util.List;

/**
 * Immutable scheduled read/write information for one post program.
 * {@code mipmapTargets} are regenerated before the step runs, as Iris's
 * CompositeRenderer.setupMipmapping does for the program's directives.
 * {@code unwrittenTargets} are the output targets the program's directive names and its shader
 * never writes: the step flips them all the same. Which side of each target the step reads and
 * writes is the flip plan's (PackFlipPlan).
 */
public record TargetStep(
        String programName,
        List<Integer> readTargets,
        List<Integer> outputTargets,
        List<Integer> mipmapTargets,
        int width,
        int height,
        boolean finalStage,
        boolean executable,
        List<String> deviations,
        List<Integer> unwrittenTargets
) {
    public TargetStep {
        programName = programName == null ? "" : programName;
        readTargets = readTargets == null ? List.of() : readTargets.stream().distinct().sorted().toList();
        outputTargets = outputTargets == null ? List.of() : List.copyOf(outputTargets);
        mipmapTargets = mipmapTargets == null ? List.of() : mipmapTargets.stream().distinct().sorted().toList();
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        unwrittenTargets = unwrittenTargets == null ? List.of() : List.copyOf(unwrittenTargets);
    }

    public boolean reads(int target) {
        return readTargets.contains(target);
    }

    public boolean writes(int target) {
        return outputTargets.contains(target);
    }
}
