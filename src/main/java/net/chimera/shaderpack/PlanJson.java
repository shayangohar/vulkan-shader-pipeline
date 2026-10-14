package net.chimera.shaderpack;

import com.google.gson.JsonArray;

import java.util.List;
import java.util.TreeSet;

/** Order-independent pieces of the plan snapshots, so equal plans serialize and hash equally. */
final class PlanJson {
    private PlanJson() {}

    /** The distinct non-blank values, in natural order. */
    static List<String> sorted(Iterable<String> values) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) result.add(value);
            }
        }
        return List.copyOf(result);
    }

    /** {@link #sorted} as a JSON array. */
    static JsonArray strings(Iterable<String> values) {
        JsonArray result = new JsonArray();
        sorted(values).forEach(result::add);
        return result;
    }
}
