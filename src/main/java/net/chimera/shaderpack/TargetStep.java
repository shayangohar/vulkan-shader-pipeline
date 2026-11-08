package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable scheduled read/write information for one post program.
 * {@code mipmapTargets} are regenerated before the step runs, as Iris's
 * CompositeRenderer.setupMipmapping does for the program's directives.
 * {@code flipsAfter} are the targets a {@code flip.<program>} directive flips after the step, as
 * Iris's BufferFlipper does; a pass group's pre-flips are the graph's (PackTargetGraphPlan.preFlips).
 * {@code unwrittenTargets} are the output targets the program's directive names and its shader
 * never writes: the step flips them all the same, so their other side becomes current.
 */
public record TargetStep(
        String programName,
        List<Integer> readTargets,
        List<Integer> outputTargets,
        List<Integer> outputFormats,
        Map<Integer, Integer> readSides,
        Map<Integer, Integer> writeSides,
        List<Integer> mipmapTargets,
        int width,
        int height,
        boolean finalStage,
        boolean executable,
        List<String> deviations,
        List<Integer> flipsAfter,
        List<Integer> unwrittenTargets
) {
    /** A step no flip directive names. */
    public TargetStep(String programName, List<Integer> readTargets, List<Integer> outputTargets,
                      List<Integer> outputFormats, Map<Integer, Integer> readSides, Map<Integer, Integer> writeSides,
                      List<Integer> mipmapTargets, int width, int height, boolean finalStage, boolean executable,
                      List<String> deviations) {
        this(programName, readTargets, outputTargets, outputFormats, readSides, writeSides, mipmapTargets,
                width, height, finalStage, executable, deviations, List.of(), List.of());
    }

    public TargetStep {
        programName = programName == null ? "" : programName;
        readTargets = readTargets == null ? List.of() : readTargets.stream().distinct().sorted().toList();
        outputTargets = outputTargets == null ? List.of() : List.copyOf(outputTargets);
        outputFormats = outputFormats == null ? List.of() : List.copyOf(outputFormats);
        readSides = immutableMap(readSides);
        writeSides = immutableMap(writeSides);
        mipmapTargets = mipmapTargets == null ? List.of() : mipmapTargets.stream().distinct().sorted().toList();
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        flipsAfter = flipsAfter == null ? List.of() : List.copyOf(flipsAfter);
        unwrittenTargets = unwrittenTargets == null ? List.of() : List.copyOf(unwrittenTargets);
    }

    private static Map<Integer, Integer> immutableMap(Map<Integer, Integer> values) {
        return values == null ? Map.of() : Map.copyOf(new TreeMap<>(values));
    }

    public boolean reads(int target) {
        return readTargets.contains(target);
    }

    public boolean writes(int target) {
        return outputTargets.contains(target);
    }
}
