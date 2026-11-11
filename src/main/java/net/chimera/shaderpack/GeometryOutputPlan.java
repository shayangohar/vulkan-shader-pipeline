package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable color-output contract for a geometry family.  Terrain and water
 * use the same legacy DRAWBUFFERS/RENDERTARGETS vocabulary as post programs,
 * but they do not participate in the post execution order.
 *
 * <p>{@code snapshotTargets} are outputs the program also samples. Iris lets a gbuffer program
 * read the target it draws into; Vulkan cannot sample an attachment inside its render pass, so
 * the program reads a copy of each taken as its window opens.
 */
public record GeometryOutputPlan(
        String programName,
        PostTargetPlan targetPlan,
        List<String> deviations,
        boolean executable,
        List<Integer> snapshotTargets
) {
    private static final Pattern TARGET_COMMENT = Pattern.compile(
            "(?is)/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([^*]*?)\\*/");

    public GeometryOutputPlan {
        programName = programName == null ? "" : programName;
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        snapshotTargets = snapshotTargets == null ? List.of() : List.copyOf(snapshotTargets);
    }

    public static GeometryOutputPlan empty(String programName) {
        PostTargetPlan plan = PostTargetPlan.parse(programName, "", Map.of()).plan();
        return new GeometryOutputPlan(programName, plan, List.of(), true, List.of());
    }

    /** This plan, with the outputs the program also samples. */
    public GeometryOutputPlan withSnapshotTargets(List<Integer> targets) {
        return new GeometryOutputPlan(programName, targetPlan, deviations, executable, targets);
    }

    public static GeometryOutputPlan parse(
            String programName,
            String source,
            Map<Integer, Integer> formats
    ) {
        String text = source == null ? "" : source;
        List<List<Integer>> routes = routes(text);
        if (routes.size() > 1 && routes.stream().allMatch(route -> route != null && !route.isEmpty())) {
            // The analyzed source is already branch-resolved. Iris takes the last route whatever
            // the earlier ones say (CommentDirectiveParser, ProgramDirectives), and so does
            // PostTargetPlan: route length and shape never decide.
            List<Integer> selected = routes.get(routes.size() - 1);
            String canonical = selected.stream().map(String::valueOf)
                    .reduce((left, right) -> left + "," + right).orElse("0");
            text = TARGET_COMMENT.matcher(text).replaceAll("");
            text = "/* RENDERTARGETS: " + canonical + " */\n" + text;
            PostTargetPlan.ParseResult parsed = PostTargetPlan.parse(programName, text, formats);
            return from(parsed, parsed.deviations());
        }
        PostTargetPlan.ParseResult parsed = PostTargetPlan.parse(programName, text, formats);
        return from(parsed, parsed.deviations());
    }

    private static GeometryOutputPlan from(
            PostTargetPlan.ParseResult parsed,
            List<String> sourceDeviations
    ) {
        List<String> deviations = sourceDeviations.stream()
                .map(value -> value.startsWith("FINAL_")
                        ? value : value.replaceFirst("^POST_", "GEOMETRY_"))
                .distinct().sorted().toList();
        boolean executable = parsed.plan().executable()
                && deviations.stream().noneMatch(value -> value.startsWith("GEOMETRY_"));
        return new GeometryOutputPlan(parsed.plan().programName(), parsed.plan(), deviations, executable, List.of());
    }

    private static List<List<Integer>> routes(String source) {
        List<List<Integer>> result = new ArrayList<>();
        Matcher comment = TARGET_COMMENT.matcher(source);
        while (comment.find()) {
            List<String> deviations = new ArrayList<>();
            List<Integer> route = PostTargetPlan.parseCommentTargets(comment.group(1), comment.group(2), deviations);
            result.add(deviations.isEmpty() ? route : List.of());
        }
        return result;
    }

    public List<Integer> targetSlots() {
        return targetPlan == null ? List.of(0) : targetPlan.targetSlots();
    }

    public List<Integer> outputLocations() {
        return targetPlan == null ? List.of() : targetPlan.outputLocations();
    }

    public List<Integer> outputFormats() {
        return targetPlan == null ? List.of(PostTargetPlan.DEFAULT_FORMAT) : targetPlan.outputFormats();
    }

    public int[] outputFormatsArray() {
        return targetPlan == null ? new int[] {PostTargetPlan.DEFAULT_FORMAT}
                : targetPlan.outputFormatsArray();
    }

    public boolean requiresMrt() {
        return targetSlots().size() > 1;
    }

    /** The host attachment is sufficient only for the identity route [0]. */
    public boolean requiresDynamicAttachments() {
        return executable && !targetSlots().equals(List.of(0));
    }

    public int targetForOutput(int location) {
        return targetPlan.targetForOutput(location);
    }
}
