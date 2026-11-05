package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_GEOMETRY_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT;

/**
 * The stages a program runs between its vertex and fragment stages, in the order they run: the
 * tessellation control and evaluation stages, then the geometry stage. Each is null when the
 * program has none; the two tessellation stages come together or not at all.
 */
public record BetweenStages(String tessControl, String tessEvaluation, String geometry) {
    /** Vertices per patch of a tessellated draw: terrain draws triangle lists, as Iris draws GL_PATCHES of 3. */
    public static final int PATCH_VERTICES = 3;
    public static final BetweenStages NONE = new BetweenStages(null, null, null);

    public BetweenStages {
        if ((tessControl == null) != (tessEvaluation == null)) {
            throw new IllegalArgumentException("tessellation control and evaluation stages must be paired");
        }
    }

    /** A geometry stage alone, or none when {@code geometry} is null. */
    public static BetweenStages geometry(String geometry) {
        return new BetweenStages(null, null, geometry);
    }

    public boolean tessellated() {
        return tessControl != null;
    }

    /** One present stage: its program stage name (PackProbe's), its Vulkan stage bit and its source. */
    public record Stage(String name, int stageBit, String source) {}

    /** The present stages, in the order they run. */
    public List<Stage> present() {
        List<Stage> stages = new ArrayList<>(3);
        if (tessControl != null) {
            stages.add(new Stage("tess_control", VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT, tessControl));
            stages.add(new Stage("tess_evaluation", VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT, tessEvaluation));
        }
        if (geometry != null) stages.add(new Stage("geometry", VK_SHADER_STAGE_GEOMETRY_BIT, geometry));
        return stages;
    }

    /** What one present stage's source becomes. */
    @FunctionalInterface
    public interface Mapping {
        String apply(Stage stage);
    }

    /** Each present stage's source mapped through {@code mapping}; an absent stage stays absent. */
    public BetweenStages map(Mapping mapping) {
        String[] mapped = new String[3];
        for (Stage stage : present()) {
            int index = switch (stage.name()) {
                case "tess_control" -> 0;
                case "tess_evaluation" -> 1;
                default -> 2;
            };
            mapped[index] = mapping.apply(stage);
        }
        return new BetweenStages(mapped[0], mapped[1], mapped[2]);
    }
}
