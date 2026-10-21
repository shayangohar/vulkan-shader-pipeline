package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Immutable plan for pack-visible depth snapshots. */
public record DepthGraphPlan(
        boolean depthtex0,
        boolean depthtex1,
        boolean depthtex2,
        int format,
        List<String> deviations
) {
    public static final int R32_SFLOAT = 100;
    public static final int DEPTH_TEX0_SLOT = 6;
    public static final int DEPTH_TEX1_SLOT = 12;
    public static final int DEPTH_TEX2_SLOT = 13;

    public DepthGraphPlan {
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public static DepthGraphPlan empty() {
        return new DepthGraphPlan(false, false, false, R32_SFLOAT, List.of());
    }

    /** The snapshots {@code reads} names, with what copying them records. */
    public static DepthGraphPlan of(Set<String> reads) {
        boolean depth0 = reads.contains("depthtex0");
        boolean depth1 = reads.contains("depthtex1");
        boolean depth2 = reads.contains("depthtex2");
        List<String> deviations = new ArrayList<>();
        if (depth0 || depth1 || depth2) {
            deviations.add("DEPTH_COPY_GRAPH_APPLIED");
            deviations.add("DEPTH_COPY_FORWARD_Z_PRESERVED");
            if (depth2) deviations.add("DEPTH2_PREHAND_AT_LEVEL_END");
        }
        return new DepthGraphPlan(depth0, depth1, depth2, R32_SFLOAT, deviations);
    }

    /** This plan with the snapshots {@code reads} names kept too. */
    public DepthGraphPlan withReads(Set<String> reads) {
        Set<String> all = new TreeSet<>(names());
        all.addAll(reads);
        return all.equals(names()) ? this : of(all);
    }

    public boolean any() {
        return depthtex0 || depthtex1 || depthtex2;
    }

    public boolean requires(String name) {
        return switch (name) {
            case "depthtex0" -> depthtex0;
            case "depthtex1" -> depthtex1;
            case "depthtex2" -> depthtex2;
            default -> false;
        };
    }

    public int slot(String name) {
        return switch (name) {
            case "depthtex0" -> DEPTH_TEX0_SLOT;
            case "depthtex1" -> DEPTH_TEX1_SLOT;
            case "depthtex2" -> DEPTH_TEX2_SLOT;
            default -> -1;
        };
    }

    public Set<String> names() {
        Set<String> result = new TreeSet<>();
        if (depthtex0) result.add("depthtex0");
        if (depthtex1) result.add("depthtex1");
        if (depthtex2) result.add("depthtex2");
        return Set.copyOf(result);
    }
}
