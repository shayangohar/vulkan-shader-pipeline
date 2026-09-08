package net.chimera.shaderpack;

import java.util.List;

/** Immutable load-time decision for the M8.2 scene seed contract. */
public record PackCoveragePlan(
        boolean enabled,
        boolean windowSizedTarget,
        List<String> families,
        List<String> deviations
) {
    public PackCoveragePlan {
        families = families == null ? List.of() : families.stream().distinct().sorted().toList();
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public static PackCoveragePlan disabled() {
        return new PackCoveragePlan(false, false, List.of(), List.of());
    }

    public static PackCoveragePlan from(PackTargetGraphPlan graph, PackPlan plan,
                                        int width, int height) {
        if (graph == null || plan == null || graph.steps().isEmpty()) {
            return disabled();
        }
        TargetSpec target = graph.target(0);
        if (target == null) {
            return new PackCoveragePlan(false, false, List.of(), List.of("SCENE_SEED_TARGET_UNSUPPORTED"));
        }
        boolean sized = target.width() == width && target.height() == height;
        List<String> families = plan.programs().stream()
                .map(PackProgramPlan::name)
                .filter(name -> name.equals("gbuffers_terrain")
                        || name.equals("gbuffers_water")
                        || name.equals("gbuffers_entities")
                        || name.equals("gbuffers_hand"))
                .toList();
        if (families.isEmpty()) {
            return disabled();
        }
        if (!sized) {
            return new PackCoveragePlan(false, false, families,
                    List.of("SCENE_SEED_TARGET_UNSUPPORTED"));
        }
        return new PackCoveragePlan(true, true, families,
                List.of("SCENE_SEED_PLAN_APPLIED"));
    }
}
