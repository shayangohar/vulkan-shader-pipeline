package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.function.UnaryOperator;

/**
 * Builds chimera's fullscreen post-processing pipelines (composite, present)
 * from assets/chimera/shaders/. These use CustomVertexFormat.NONE: a
 * three-vertex triangle generated in the vertex stage, no vertex buffers.
 */
public final class ChimeraPostPipelines {
    /**
     * Vertices of every fullscreen draw: a two-triangle quad over UV 0..1, as Iris's
     * fullscreen quad. Packs that rescale the post geometry (Bliss draws clouds and
     * bloom tiles into a sub-rectangle) need its edges to stay on the screen edges.
     */
    public static final int FULLSCREEN_VERTICES = 6;

    private ChimeraPostPipelines() {}

    public static GraphicsPipeline create(String name) {
        return HostPipelineLayout.create(name, name, CustomVertexFormat.NONE, UnaryOperator.identity());
    }

    /** Creates the internal forward-Z depth copy pipeline used by pack snapshots. */
    public static GraphicsPipeline createDepthPipeline() {
        return create("chimera_depth");
    }

    /** Creates the host-to-pack scene seed pipeline. */
    public static GraphicsPipeline createSceneSeedPipeline() {
        return create("chimera_scene_seed");
    }

    /**
     * Creates a terrain-format pipeline for shadow rendering. It reads the section offset and the
     * ChunkOffset push constant VulkanMod's DrawBuffers sets per chunk area.
     */
    public static GraphicsPipeline createTerrainPipeline(String name, VertexFormat vertexFormat) {
        return HostPipelineLayout.create(name, name, vertexFormat, UnaryOperator.identity());
    }
}
