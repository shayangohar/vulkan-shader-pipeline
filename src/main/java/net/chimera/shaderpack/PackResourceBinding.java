package net.chimera.shaderpack;

import java.util.List;

/** One resolved sampler binding in one executable pack program. */
public record PackResourceBinding(
        String program,
        String sampler,
        String resourceKey,
        PackResourceKind kind,
        String source,
        int slot,
        String filter,
        String wrap,
        PackResourceStatus status,
        List<String> dependentPrograms,
        List<String> deviations
) {
    public PackResourceBinding(
            String program,
            String sampler,
            String resourceKey,
            PackResourceKind kind,
            String source,
            int slot,
            String filter,
            String wrap,
            PackResourceStatus status,
            List<String> deviations
    ) {
        this(program, sampler, resourceKey, kind, source, slot, filter, wrap,
                status, program == null ? List.of() : List.of(program), deviations);
    }

    public PackResourceBinding {
        program = program == null ? "" : program;
        sampler = sampler == null ? "" : sampler;
        resourceKey = resourceKey == null ? sampler : resourceKey;
        kind = kind == null ? PackResourceKind.UNSERVED : kind;
        source = source == null ? "" : source;
        filter = filter == null ? "nearest" : filter;
        wrap = wrap == null ? "repeat" : wrap;
        status = status == null ? PackResourceStatus.UNSUPPORTED : status;
        dependentPrograms = dependentPrograms == null
                ? List.of() : dependentPrograms.stream().filter(value -> value != null && !value.isBlank())
                .distinct().sorted().toList();
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public PackResourceBinding withDependentPrograms(List<String> programs) {
        return new PackResourceBinding(program, sampler, resourceKey, kind, source, slot,
                filter, wrap, status, programs, deviations);
    }

    public boolean available() {
        return status == PackResourceStatus.HOST_ALIAS
                || status == PackResourceStatus.PACK_FILE
                || status == PackResourceStatus.GAME_RESOURCE
                || status == PackResourceStatus.MATERIAL_MAP;
    }
}
