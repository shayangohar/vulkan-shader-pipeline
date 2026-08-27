package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Builds fullscreen post pipelines from pack programs, mirroring
 * ChimeraPostPipelines.create: a PipelineConfig JSON parsed by
 * PipelineConfig.fromJson (sampler names and order define the descriptor
 * bindings), vertex stage = chimera's fixed fullscreen triangle, fragment
 * stage = the converted legacy GLSL. Any failure (parse, conversion, shaderc,
 * supplier) returns null and the caller falls back to the identity pipeline.
 */
public final class PackPipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    private PackPipelines() {}

    /** A successfully built pack post pipeline plus the slots its samplers occupy. */
    public record PackPost(String name, GraphicsPipeline pipeline, int[] samplerSlots, String convertedFragment) {}

    public static PackPost buildPost(PackProgram program, String fixedVertexSource) {
        try {
            String converted = LegacyGlslConverter.convertFragment(program.fragmentSource(), program.fragmentPath());
            if (converted == null) {
                throw new IllegalStateException("legacy GLSL conversion failed");
            }

            List<String> samplers = UniformRegistry.scanSamplerNames(program.fragmentSource());
            JsonObject json = new JsonObject();
            json.addProperty("vertex", "chimera_composite/chimera_composite");
            json.addProperty("fragment", "pack/" + program.name());
            JsonArray samplersJson = new JsonArray();
            for (String name : samplers) {
                JsonObject sampler = new JsonObject();
                sampler.addProperty("name", "Sampler" + UniformRegistry.NAME_TO_SLOT.get(name));
                samplersJson.add(sampler);
            }
            json.add("samplers", samplersJson);
            json.add("UBOs", new JsonArray());
            json.add("PushConstants", new JsonArray());

            PipelineConfig config = PipelineConfig.fromJson("pack_" + program.name(), json);
            Pipeline.Builder builder = new Pipeline.Builder((VertexFormat) CustomVertexFormat.NONE, "pack_" + program.name());
            builder.applyConfig(config);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, fixedVertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, converted);
            GraphicsPipeline pipeline = builder.createGraphicsPipeline();

            int[] slots = samplers.stream().mapToInt(UniformRegistry.NAME_TO_SLOT::get).toArray();
            return new PackPost(program.name(), pipeline, slots, converted);
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack {}: build failed: {}", program.name(), e.getMessage());
            return null;
        }
    }
}