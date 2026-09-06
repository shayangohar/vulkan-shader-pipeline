package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable scheduled read/write information for one post program. */
public record TargetStep(
        String programName,
        List<Integer> readTargets,
        List<Integer> outputTargets,
        List<Integer> outputFormats,
        Map<Integer, Integer> readSides,
        Map<Integer, Integer> writeSides,
        int width,
        int height,
        boolean finalStage,
        boolean executable,
        List<String> deviations
) {
    public TargetStep {
        programName = programName == null ? "" : programName;
        readTargets = readTargets == null ? List.of() : readTargets.stream().distinct().sorted().toList();
        outputTargets = outputTargets == null ? List.of() : List.copyOf(outputTargets);
        outputFormats = outputFormats == null ? List.of() : List.copyOf(outputFormats);
        readSides = immutableMap(readSides);
        writeSides = immutableMap(writeSides);
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
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
