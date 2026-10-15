package net.chimera.shaderpack.options;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A pack's discovered options and the source files that declare them.
 *
 * <p>Discovery runs per include group: a toggle candidate counts only when a file in its own group
 * tests it with {@code #ifdef} or {@code #ifndef}. Values are applied the way OptiFine and Iris apply
 * them, by editing the declaring lines, so a define and a const change exactly where the pack wrote
 * them and nowhere else.
 */
public final class OptionDiscovery {
    private final SourceGraph graph;
    private final OptionSet options;
    private final Set<String> ambiguous;

    private OptionDiscovery(SourceGraph graph, OptionSet options, Set<String> ambiguous) {
        this.graph = graph;
        this.options = options;
        this.ambiguous = ambiguous;
    }

    /** No sources and no options. */
    static OptionDiscovery none() {
        return new OptionDiscovery(SourceGraph.empty(), OptionSet.empty(), Set.of());
    }

    /** Reads every program source under {@code shadersRoot} and what it includes. */
    public static OptionDiscovery discover(Path shadersRoot) {
        SourceGraph graph = SourceGraph.read(shadersRoot);
        OptionSet.Builder builder = new OptionSet.Builder();
        for (List<String> component : graph.components()) {
            Set<String> tested = new HashSet<>();
            for (String file : component) tested.addAll(graph.files().get(file).testedNames());
            for (String file : component) {
                graph.files().get(file).options().forEach((line, option) -> {
                    if (option instanceof ShaderOption.Choice || tested.contains(option.name())) {
                        builder.add(new OptionSet.Location(file, line), option);
                    }
                });
            }
        }
        return new OptionDiscovery(graph, builder.build(), Set.copyOf(builder.ambiguous()));
    }

    public OptionSet options() {
        return options;
    }

    /** Names dropped because two declarations disagreed on their default. */
    public Set<String> ambiguous() {
        return ambiguous;
    }

    /** Includes that could not be read. */
    public List<String> failures() {
        return graph.failures();
    }

    /**
     * The source files {@code values} changes, by pack-relative path, with the new text. Every line
     * that declares a changed name is edited, confirmed in its own group or not, as in Iris. A file
     * is written back with {@code \n} line ends, as the pack loader joins edited lines.
     */
    public Map<String, String> editedFiles(OptionValues values) {
        Map<String, String> edited = new TreeMap<>();
        graph.files().forEach((path, source) -> {
            List<String> lines = source.apply(values);
            if (lines != source.lines()) edited.put(path, String.join("\n", lines) + "\n");
        });
        return Collections.unmodifiableMap(edited);
    }
}
