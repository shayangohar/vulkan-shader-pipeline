package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris's const directives ({@code ConstDirectiveParser}): a line that starts, after indentation,
 * with {@code const <type> <name> = <value>;}. The value is everything up to the line's first
 * semicolon, trimmed; whoever reads the directive parses it. Iris reads them from the fragment
 * sources of the programs {@code ProgramSet.locateDirectives} visits, in its order: the
 * shadowcomp, begin and prepare passes, every ProgramId (shadow, gbuffers, dh and final), then
 * the deferred and composite passes. A later program's directive replaces an earlier one's.
 */
final class IrisConstDirectives {
    /** One directive as written: its declared type, name and unparsed value. */
    record Directive(String type, String name, String value) {}

    private static final Pattern LINE = Pattern.compile(
            "(?m)^[ \\t]*const[ \\t]+(int|float|vec2|ivec3|vec4|bool)[ \\t]+(\\w+)[ \\t]*=([^;\\r\\n]*);");
    private static final int PASSES_PER_ARRAY = 100;
    private static final Map<String, Integer> ORDER = readOrder();

    private IrisConstDirectives() {}

    private static Map<String, Integer> readOrder() {
        List<String> names = new ArrayList<>();
        for (String array : List.of("shadowcomp", "begin", "prepare")) addPasses(names, array);
        names.addAll(PackResolutionPlan.programIds());
        for (String array : List.of("deferred", "composite")) addPasses(names, array);
        Map<String, Integer> order = new HashMap<>();
        for (String name : names) order.putIfAbsent(name, order.size());
        return Map.copyOf(order);
    }

    private static void addPasses(List<String> names, String array) {
        for (int index = 0; index < PASSES_PER_ARRAY; index++) names.add(index == 0 ? array : array + index);
    }

    /** The directives {@code source} declares, in source order. */
    static List<Directive> find(String source) {
        List<Directive> directives = new ArrayList<>();
        if (source == null) return directives;
        Matcher line = LINE.matcher(source);
        while (line.find()) directives.add(new Directive(line.group(1), line.group(2), line.group(3).trim()));
        return directives;
    }

    /**
     * The programs whose fragment sources Iris reads directives from, in its order; a program Iris
     * never loads is left out.
     */
    static List<PackProgram> programs(List<PackProgram> programs) {
        return programs.stream()
                .filter(program -> ORDER.containsKey(program.name()))
                .sorted(Comparator.comparingInt(program -> ORDER.get(program.name())))
                .toList();
    }
}
