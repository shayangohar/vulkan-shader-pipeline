package net.chimera.shaderpack;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable plan for the active pack variant. */
public record PackPlan(
        PackConfig.PackConfigData config,
        List<PackProgramPlan> programs,
        PackEntityIdResolver entityIds
) {
    public PackPlan(PackConfig.PackConfigData config, List<PackProgramPlan> programs) {
        this(config, programs, PackEntityIdResolver.empty());
    }

    public PackPlan {
        programs = programs == null ? List.of() : programs.stream()
                .filter(value -> value != null)
                .sorted(java.util.Comparator.comparing(PackProgramPlan::name))
                .toList();
        entityIds = entityIds == null ? PackEntityIdResolver.empty() : entityIds;
    }

    public Map<String, PackProgramPlan> byName() {
        Map<String, PackProgramPlan> result = new TreeMap<>();
        for (PackProgramPlan program : programs) {
            result.putIfAbsent(program.name(), program);
        }
        return Collections.unmodifiableMap(result);
    }

    public PackProgramPlan program(String name) {
        for (PackProgramPlan program : programs) {
            if (program.name().equals(name)) {
                return program;
            }
        }
        return null;
    }
}
