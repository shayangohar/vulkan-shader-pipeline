package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one deterministic interpretation of a legacy post program's render
 * targets. The same plan is used by probing, conversion, pipeline creation,
 * and the runtime post chain.
 */
public final class PostTargetPlan {
    /** Highest logical colortex index supported by the measured target bridge. */
    public static final int MAX_TARGET = 8;
    public static final int DEFAULT_FORMAT = 97;

    private static final Pattern DRAWBUFFERS_DEFINE = Pattern.compile(
            "(?m)^\\s*#define\\s+DRAWBUFFERS([0-9]+)\\s*$");
    private static final Pattern TARGET_COMMENT = Pattern.compile(
            "(?is)/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([^*]*?)\\*/");
    private static final Pattern DIRECTIVE_WORD = Pattern.compile(
            "(?i)\\b(?:DRAWBUFFERS|RENDERTARGETS)\\b");
    private static final Pattern FRAG_DATA = Pattern.compile(
            "gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern MODERN_OUTPUT = Pattern.compile(
            "(?m)^\\s*out\\s+vec4\\s+[A-Za-z_]\\w*\\s*;");

    private final String programName;
    private final List<Integer> targetSlots;
    private final List<Integer> outputLocations;
    private final List<Integer> outputFormats;
    private final int[] outputFormatsArray;
    private final List<String> deviations;

    private PostTargetPlan(
            String programName,
            List<Integer> targetSlots,
            List<Integer> outputLocations,
            List<Integer> outputFormats,
            List<String> deviations
    ) {
        this.programName = programName;
        this.targetSlots = List.copyOf(targetSlots);
        this.outputLocations = List.copyOf(outputLocations);
        this.outputFormats = List.copyOf(outputFormats);
        this.outputFormatsArray = outputFormats.stream().mapToInt(Integer::intValue).toArray();
        this.deviations = deviations.stream().distinct().sorted().toList();
    }

    public static ParseResult parse(String programName, String source, Map<Integer, Integer> formats) {
        String text = source == null ? "" : source;
        Map<Integer, Integer> safeFormats = formats == null ? Map.of() : formats;
        List<String> deviations = new ArrayList<>();
        List<List<Integer>> directives = new ArrayList<>();
        boolean directiveSeen = false;

        Matcher define = DRAWBUFFERS_DEFINE.matcher(text);
        while (define.find()) {
            List<Integer> parsed = parseDigits(define.group(1), deviations);
            if (parsed.isEmpty()) {
                deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
            } else {
                directives.add(parsed);
            }
            directiveSeen = true;
        }

        Matcher comment = TARGET_COMMENT.matcher(text);
        while (comment.find()) {
            List<Integer> parsed = parseCommentTargets(comment.group(2), deviations);
            if (parsed.isEmpty()) {
                deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
            } else {
                directives.add(parsed);
            }
            directiveSeen = true;
        }

        if (DIRECTIVE_WORD.matcher(text).find() && !directiveSeen) {
            deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
        }

        List<Integer> targetSlots = selectDirective(directives, deviations);
        Set<Integer> outputSet = new TreeSet<>();
        Matcher fragData = FRAG_DATA.matcher(text);
        while (fragData.find()) {
            try {
                outputSet.add(Integer.parseInt(fragData.group(1)));
            } catch (NumberFormatException e) {
                deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
            }
        }
        if (text.matches("(?s).*\\bgl_FragColor\\b.*")) {
            outputSet.add(0);
        }
        Matcher modernOutput = MODERN_OUTPUT.matcher(text);
        int modernLocation = 0;
        while (modernOutput.find()) {
            outputSet.add(modernLocation++);
        }

        for (int target : targetSlots) {
            if (target < 0 || target > MAX_TARGET) {
                deviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + target);
            }
        }
        for (int output : outputSet) {
            if (output < 0 || output >= targetSlots.size()) {
                deviations.add("POST_OUTPUT_INDEX_UNMAPPED:" + output);
            }
        }
        if (programName.equals("final") && targetSlots.size() != 1) {
            deviations.add("FINAL_MRT_UNSUPPORTED");
        }

        List<Integer> outputLocations = outputSet.stream().toList();
        List<Integer> outputFormats = targetSlots.stream()
                .map(slot -> safeFormats.getOrDefault(slot, DEFAULT_FORMAT))
                .toList();
        PostTargetPlan plan = new PostTargetPlan(
                programName, targetSlots, outputLocations, outputFormats, deviations);
        return new ParseResult(plan, plan.deviations());
    }

    public static ParseResult parse(String programName, String source) {
        return parse(programName, source, Map.of());
    }

    public static boolean isPostProgramName(String name) {
        return name != null && name.matches("(?:deferred|composite)\\d*|final");
    }

    public static Comparator<String> programComparator() {
        return Comparator.comparingInt(PostTargetPlan::familyRank)
                .thenComparingInt(PostTargetPlan::numericSuffix)
                .thenComparing(Comparator.naturalOrder());
    }

    private static int familyRank(String name) {
        if (name.startsWith("deferred")) {
            return 0;
        }
        if (name.startsWith("composite")) {
            return 1;
        }
        return 2;
    }

    private static int numericSuffix(String name) {
        int index = name.startsWith("deferred")
                ? "deferred".length()
                : name.startsWith("composite") ? "composite".length() : name.length();
        if (index == name.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(name.substring(index));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Selects the active output route from the surviving directives. The
     * analyzed source is already branch-resolved, so surviving directives
     * describe cumulative writes in file order and the pack author places
     * the overriding route last (Iris last-directive-wins). Earlier routes
     * are dormant remnants only when they differ in shape, which stays a
     * hard conflict rather than a guess. Malformed parses never proxy for
     * a real route.
     */
    private static List<Integer> selectDirective(
            List<List<Integer>> directives,
            List<String> deviations
    ) {
        if (directives == null || directives.isEmpty()) {
            return List.of(0);
        }
        List<Integer> selected = directives.get(directives.size() - 1);
        if (selected == null || selected.isEmpty()) {
            return List.of(0);
        }
        if (selected.size() == 1 && selected.get(0) == 0) {
            return selected;
        }
        Set<List<Integer>> distinct = new HashSet<>(directives);
        boolean hasUsable = distinct.stream().anyMatch(route ->
                route != null && !route.isEmpty());
        if (!hasUsable) {
            return List.of(0);
        }
        Set<List<Integer>> latestShape = new HashSet<>();
        for (int index = directives.size() - 1; index >= 0; index--) {
            List<Integer> route = directives.get(index);
            if (route == null || route.isEmpty()) {
                continue;
            }
            if (route.size() != selected.size()) {
                break;
            }
            latestShape.add(route);
        }
        if (latestShape.size() > 1) {
            deviations.add("POST_TARGET_DIRECTIVE_CONFLICT");
        }
        return selected;
    }

    private static List<Integer> parseDigits(String digits, List<String> deviations) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < digits.length(); i++) {
            result.add(digits.charAt(i) - '0');
        }
        validateDuplicates(result, deviations);
        return result;
    }

    private static List<Integer> parseCommentTargets(String body, List<String> deviations) {
        String trimmed = body.trim();
        if (trimmed.isEmpty() || !trimmed.matches("[0-9,\\s]+")) {
            deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        if (trimmed.indexOf(',') < 0) {
            return parseDigits(trimmed.replaceAll("\\s+", ""), deviations);
        }
        for (String token : trimmed.split(",")) {
            String value = token.trim();
            if (!value.matches("\\d+")) {
                deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
                continue;
            }
            try {
                result.add(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
            }
        }
        validateDuplicates(result, deviations);
        return result;
    }

    private static void validateDuplicates(List<Integer> values, List<String> deviations) {
        Set<Integer> seen = new HashSet<>();
        for (int value : values) {
            if (!seen.add(value)) {
                deviations.add("POST_TARGET_DIRECTIVE_DUPLICATE:" + value);
            }
        }
    }

    public String programName() {
        return programName;
    }

    /** Logical colortex slot for each fragment output location. */
    public List<Integer> targetSlots() {
        return targetSlots;
    }

    /** Fragment output locations used by the source. */
    public List<Integer> outputLocations() {
        return outputLocations;
    }

    /** Vulkan format for each output location, in output location order. */
    public List<Integer> outputFormats() {
        return outputFormats;
    }

    /** Internal render-thread view. Callers must not modify the returned array. */
    public int[] outputFormatsArray() {
        return outputFormatsArray;
    }

    public List<String> deviations() {
        return deviations;
    }

    public boolean executable() {
        return deviations.stream().noneMatch(value -> value.startsWith("POST_")
                || value.startsWith("FINAL_MRT_"));
    }

    public boolean requiresMrt() {
        return targetSlots.size() > 1;
    }

    public boolean isFinal() {
        return programName.equals("final");
    }

    public int targetForOutput(int outputLocation) {
        return targetSlots.get(outputLocation);
    }

    public record ParseResult(PostTargetPlan plan, List<String> deviations) {
        public ParseResult {
            deviations = deviations.stream().distinct().sorted().toList();
        }

        public boolean executable() {
            return plan.executable();
        }
    }
}
