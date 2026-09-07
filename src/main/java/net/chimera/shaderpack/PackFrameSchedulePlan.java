package net.chimera.shaderpack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable load-time frame order for pack-visible stages.
 *
 * The plan describes seams. It does not own Vulkan resources and it does not
 * decide whether a pipeline compiled successfully. Runtime code uses the
 * existing target, depth, and family owners at these seams.
 */
public final class PackFrameSchedulePlan {
    public enum Phase {
        SHADOW,
        OPAQUE,
        DEPTH_TEX1,
        HAND,
        DEPTH_TEX2,
        EARLY_POST,
        TRANSLUCENT,
        PARTICLE,
        DEPTH_TEX0,
        LATE_POST,
        FINAL,
        GUI,
        PRESENT
    }

    public enum PostWindow {
        EARLY,
        LATE,
        FINAL
    }

    public record PostStage(String name, PostWindow window, boolean executable,
                             List<Integer> requiredColorInputs, List<Integer> outputTargets) {
        public PostStage {
            name = Objects.requireNonNull(name, "name");
            window = Objects.requireNonNull(window, "window");
            requiredColorInputs = requiredColorInputs == null
                    ? List.of() : requiredColorInputs.stream().distinct().sorted().toList();
            outputTargets = outputTargets == null
                    ? List.of() : outputTargets.stream().distinct().sorted().toList();
        }
    }

    private static final List<Phase> ORDER = List.of(Phase.values());
    private final List<Phase> phases;
    private final List<PostStage> postStages;
    private final boolean depthtex0;
    private final boolean depthtex1;
    private final boolean depthtex2;
    private final boolean handBeforeEarlyPost;
    private final boolean particlesBeforeLatePost;
    private final List<String> deviations;

    private PackFrameSchedulePlan(List<PostStage> postStages, boolean depthtex0,
                                  boolean depthtex1, boolean depthtex2,
                                  boolean handBeforeEarlyPost, boolean particlesBeforeLatePost,
                                  List<String> deviations) {
        this.phases = List.copyOf(ORDER);
        this.postStages = postStages == null ? List.of() : List.copyOf(postStages);
        this.depthtex0 = depthtex0;
        this.depthtex1 = depthtex1;
        this.depthtex2 = depthtex2;
        this.handBeforeEarlyPost = handBeforeEarlyPost;
        this.particlesBeforeLatePost = particlesBeforeLatePost;
        this.deviations = deviations == null ? List.of()
                : deviations.stream().filter(value -> value != null && !value.isBlank())
                .distinct().sorted().toList();
    }

    public static PackFrameSchedulePlan empty() {
        return new PackFrameSchedulePlan(List.of(), false, false, false, false, true, List.of());
    }

    public static PackFrameSchedulePlan build(List<PackProgramPlan> programs,
                                              PackTargetGraphPlan graph) {
        List<PostStage> stages = new ArrayList<>();
        List<String> deviations = new ArrayList<>();
        boolean depth0 = graph != null && graph.depth().depthtex0();
        boolean depth1 = graph != null && graph.depth().depthtex1();
        boolean depth2 = graph != null && graph.depth().depthtex2();

        if (programs != null) {
            programs.stream()
                    .filter(Objects::nonNull)
                    .filter(program -> program.targetPlan() != null
                            && PostTargetPlan.isPostProgramName(program.name()))
                    .sorted(Comparator.comparing(PackProgramPlan::name,
                            PostTargetPlan.programComparator()))
                    .forEach(program -> {
                        PostTargetPlan target = program.targetPlan();
                        PostWindow window = target.isFinal()
                                ? PostWindow.FINAL
                                : program.name().startsWith("deferred")
                                ? PostWindow.EARLY : PostWindow.LATE;
                        TargetStep step = graph == null ? null : graph.step(program.name());
                        List<Integer> inputs = step == null ? List.of() : step.readTargets();
                        List<Integer> outputs = step == null
                                ? target.targetSlots() : step.outputTargets();
                        boolean executable = program.executable() && target.executable()
                                && (step == null || step.executable());
                        stages.add(new PostStage(program.name(), window, executable, inputs, outputs));
                        if (!executable) {
                            deviations.add("SCHEDULE_STAGE_UNAVAILABLE:" + program.name());
                        }
                    });
        }

        if (depth1) deviations.add("SCHEDULE_DEPTH_TEX1_BEFORE_TRANSLUCENT");
        if (depth2) deviations.add("SCHEDULE_DEPTH_TEX2_BEFORE_HAND");
        if (depth0) deviations.add("SCHEDULE_DEPTH_TEX0_BEFORE_COMPOSITE");
        if (stages.stream().anyMatch(stage -> stage.window() == PostWindow.EARLY)) {
            deviations.add("SCHEDULE_EARLY_POST_BEFORE_TRANSLUCENT");
        }
        if (stages.stream().anyMatch(stage -> stage.window() == PostWindow.LATE)) {
            deviations.add("SCHEDULE_LATE_POST_AFTER_WORLD");
        }
        deviations.add("HAND_PHASE_HOST_BOUNDARY");
        return new PackFrameSchedulePlan(stages, depth0, depth1, depth2,
                false, true, deviations);
    }

    public List<Phase> phases() { return phases; }
    public List<PostStage> postStages() { return postStages; }
    public boolean requiresDepthtex0() { return depthtex0; }
    public boolean requiresDepthtex1() { return depthtex1; }
    public boolean requiresDepthtex2() { return depthtex2; }
    public boolean handBeforeEarlyPost() { return handBeforeEarlyPost; }
    public boolean particlesBeforeLatePost() { return particlesBeforeLatePost; }
    public List<String> deviations() { return deviations; }

    public List<PostStage> stages(PostWindow window) {
        return postStages.stream().filter(stage -> stage.window() == window).toList();
    }

    public boolean hasWindow(PostWindow window) {
        for (PostStage stage : postStages) {
            if (stage.window() == window) return true;
        }
        return false;
    }

    public PostStage postStage(String name) {
        for (PostStage stage : postStages) {
            if (stage.name().equals(name)) return stage;
        }
        return null;
    }

    public int phaseIndex(Phase phase) {
        return phases.indexOf(phase);
    }

    public boolean hasPhase(Phase phase) {
        return phases.contains(phase);
    }

    public String snapshot() {
        return "phases=" + phases + ";posts=" + postStages
                + ";depth=" + List.of(depthtex0, depthtex1, depthtex2)
                + ";handBeforeEarly=" + handBeforeEarlyPost
                + ";particlesBeforeLate=" + particlesBeforeLatePost
                + ";deviations=" + deviations;
    }

    public String fingerprint() {
        return ConformanceReport.sha256(snapshot().getBytes(StandardCharsets.UTF_8));
    }
}
