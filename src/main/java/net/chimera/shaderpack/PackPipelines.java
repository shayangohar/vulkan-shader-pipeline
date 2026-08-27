package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.chimera.render.shader.ChimeraShaderLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Builds GraphicsPipelines from pack programs, mirroring
 * ChimeraPostPipelines.create / ChimeraTerrainPipelines.buildPipeline: a
 * PipelineConfig JSON parsed by PipelineConfig.fromJson (sampler names and
 * order define the descriptor bindings, sequentially after any UBO blocks),
 * vertex stage = a chimera fixed source, fragment stage = the converted
 * legacy GLSL. Any failure (parse, conversion, shaderc, supplier) returns
 * null and the caller falls back to the identity pipeline.
 *
 * <p>Post build (buildPost): fullscreen triangle vertex, empty UBOs, sampler
 * bindings 0,1,... .
 * <p>Terrain build (buildTerrain): chimera's fixed terrain vertex
 * (COMPRESSED_TERRAIN inputs, outputs color/texcoord/lightSpacePos by
 * location), the terrain config's UBO blocks (bindings 0/1/2) and push
 * constants kept verbatim, sampler bindings 3,4,... — matching what
 * LegacyGlslConverter emits for the geometry stage.
 */
public final class PackPipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    private PackPipelines() {}

    /** A successfully built pack post pipeline plus the slots its samplers occupy. */
    public record PackPost(String name, GraphicsPipeline pipeline, int[] samplerSlots, String convertedFragment) {}

    /** A successfully built pack geometry (terrain) pipeline plus its sampler slots. */
    public record PackTerrain(GraphicsPipeline pipeline, int[] samplerSlots, String convertedFragment) {}

    public static PackPost buildPost(PackProgram program, String fixedVertexSource) {
        try {
            String converted = LegacyGlslConverter.convertFragment(program.fragmentSource(), program.fragmentPath(), false);
            if (converted == null) {
                throw new IllegalStateException("legacy GLSL conversion failed");
            }

            List<String> samplers = UniformRegistry.scanSamplerNames(program.fragmentSource(), UniformRegistry.Stage.POST);
            int[] slots = samplers.stream().mapToInt(UniformRegistry.NAME_TO_SLOT::get).toArray();
            JsonObject json = new JsonObject();
            json.addProperty("vertex", "chimera_composite/chimera_composite");
            json.addProperty("fragment", "pack/" + program.name());
            json.add("samplers", samplerArray(slots));
            json.add("UBOs", new JsonArray());
            json.add("PushConstants", new JsonArray());

            PipelineConfig config = PipelineConfig.fromJson("pack_" + program.name(), json);
            Pipeline.Builder builder = new Pipeline.Builder((VertexFormat) CustomVertexFormat.NONE, "pack_" + program.name());
            builder.applyConfig(config);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, fixedVertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, converted);
            GraphicsPipeline pipeline = builder.createGraphicsPipeline();

            return new PackPost(program.name(), pipeline, slots, converted);
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack {}: build failed: {}", program.name(), e.getMessage());
            return null;
        }
    }

    /**
     * Builds the gbuffers_terrain pipeline: chimera's fixed terrain vertex,
     * the pack's converted fragment, the terrain config's UBO blocks.
     */
    public static PackTerrain buildTerrain(PackProgram program, String fixedVertexSource) {
        try {
            String converted = LegacyGlslConverter.convertFragment(program.fragmentSource(), program.fragmentPath(), true);
            if (converted == null) {
                throw new IllegalStateException("legacy GLSL conversion failed");
            }

            List<String> samplers = UniformRegistry.scanSamplerNames(program.fragmentSource(), UniformRegistry.Stage.GEOMETRY);
            int[] slots = samplers.stream().mapToInt(UniformRegistry.GEOMETRY_NAME_TO_SLOT::get).toArray();

            JsonObject json = ChimeraShaderLoader.loadJson("chimera_terrain.json").deepCopy();
            json.addProperty("fragment", "pack_" + program.name());
            json.add("samplers", samplerArray(slots));

            PipelineConfig config = PipelineConfig.fromJson("pack_" + program.name(), json);
            Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.COMPRESSED_TERRAIN, "pack_" + program.name());
            builder.applyConfig(config);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, fixedVertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, converted);
            GraphicsPipeline pipeline = builder.createGraphicsPipeline();

            // Share the host's global UBO pool so per-instance section data flows in unchanged.
            for (var buffer : pipeline.getBuffers()) {
                buffer.setUseGlobalBuffer(true);
            }

            return new PackTerrain(pipeline, slots, converted);
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack {}: terrain build failed: {}", program.name(), e.getMessage());
            return null;
        }
    }

    /** Sampler JSON entries "Sampler<slot>" in the given order (config binding order). */
    private static JsonArray samplerArray(int[] slots) {
        JsonArray samplersJson = new JsonArray();
        for (int slot : slots) {
            JsonObject sampler = new JsonObject();
            sampler.addProperty("name", "Sampler" + slot);
            samplersJson.add(sampler);
        }
        return samplersJson;
    }
}