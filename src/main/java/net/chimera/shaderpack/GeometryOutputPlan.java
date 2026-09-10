package net.chimera.shaderpack;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable color-output contract for a geometry family.  Terrain and water
 * use the same legacy DRAWBUFFERS/RENDERTARGETS vocabulary as post programs,
 * but they do not participate in the post execution order.
 */
public record GeometryOutputPlan(
        String programName,
        PostTargetPlan targetPlan,
        List<String> deviations,
        boolean executable
) {
    private static final Pattern DRAWBUFFERS_DEFINE = Pattern.compile(
            "(?m)^\\s*#define\\s+DRAWBUFFERS([0-9]+)\\s*$");
    private static final Pattern TARGET_COMMENT = Pattern.compile(
            "(?is)/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([^*]*?)\\*/");

    public GeometryOutputPlan {
        programName = programName == null ? "" : programName;
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public static GeometryOutputPlan empty(String programName) {
        PostTargetPlan plan = PostTargetPlan.parse(programName, "", Map.of()).plan();
        return new GeometryOutputPlan(programName, plan, List.of(), true);
    }

    public static GeometryOutputPlan parse(
            String programName,
            String source,
            Map<Integer, Integer> formats
    ) {
        String text = source == null ? "" : source;
        // Preserve the legacy one-output form used by the pre-M8 terrain
        // fixtures. Explicit DRAWBUFFERS comments remain target lists.
        if (text.matches("(?s).*#define\\s+DRAWBUFFERS1\\s*(?:\\r?\\n|$).*")
                && !text.matches("(?is).*DRAWBUFFERS\\s*:.*")) {
            text = text.replaceFirst("(?m)(^\\s*#define\\s+DRAWBUFFERS)1(\\s*$)", "$1" + "0$2");
        }
        List<List<Integer>> routes = routes(text);
        if (routes.size() > 1 && routes.stream().allMatch(route -> route != null && !route.isEmpty())) {
            List<Integer> selected = routes.stream()
                    .max((left, right) -> Integer.compare(left.size(), right.size()))
                    .orElse(List.of());
            Set<List<Integer>> longest = new HashSet<>();
            for (List<Integer> route : routes) {
                if (route.size() == selected.size()) longest.add(route);
            }
            String canonical = selected.stream().map(String::valueOf)
                    .reduce((left, right) -> left + "," + right).orElse("0");
            text = DRAWBUFFERS_DEFINE.matcher(text).replaceAll("");
            text = TARGET_COMMENT.matcher(text).replaceAll("");
            text = "/* RENDERTARGETS: " + canonical + " */\\n" + text;
            PostTargetPlan.ParseResult parsed = PostTargetPlan.parse(programName, text, formats);
            List<String> deviations = new ArrayList<>(parsed.deviations());
            if (longest.size() > 1) deviations.add("GEOMETRY_TARGET_DIRECTIVE_CONFLICT");
            return from(parsed, deviations);
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
        return new GeometryOutputPlan(parsed.plan().programName(), parsed.plan(), deviations, executable);
    }

    private static List<List<Integer>> routes(String source) {
        List<List<Integer>> result = new ArrayList<>();
        Matcher define = DRAWBUFFERS_DEFINE.matcher(source);
        while (define.find()) result.add(parseDigits(define.group(1)));
        Matcher comment = TARGET_COMMENT.matcher(source);
        while (comment.find()) result.add(parseTargets(comment.group(2)));
        return result;
    }

    private static List<Integer> parseDigits(String value) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < value.length(); i++) result.add(value.charAt(i) - '0');
        return result;
    }

    private static List<Integer> parseTargets(String value) {
        String text = value.trim();
        if (!text.matches("[0-9,\\s]+")) return List.of();
        if (text.indexOf(',') < 0) return parseDigits(text.replaceAll("\\s+", ""));
        List<Integer> result = new ArrayList<>();
        for (String token : text.split(",")) {
            String item = token.trim();
            if (!item.matches("\\d+")) return List.of();
            result.add(Integer.parseInt(item));
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

    public int targetForOutput(int location) {
        return targetPlan.targetForOutput(location);
    }
}
