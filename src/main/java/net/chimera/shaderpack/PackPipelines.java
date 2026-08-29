package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.shader.PackUniformProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Builds GraphicsPipelines from pack programs, mirroring
 * ChimeraPostPipelines.create / ChimeraTerrainPipelines.buildPipeline: a
 * PipelineConfig JSON parsed by PipelineConfig.fromJson (sampler names and
 * order define the descriptor bindings, sequentially after any UBO blocks),
 * vertex stage = a chimera fixed source or the narrow terrain bridge, fragment stage = the converted
 * legacy GLSL. Any failure (parse, conversion, shaderc, supplier) returns
 * null and the caller falls back to the identity pipeline.
 *
 * <p>Post build (buildPost): fullscreen triangle vertex, one optional generated
 * fragment UBO at binding 0, and samplers after it. No-uniform programs keep
 * sampler bindings 0,1,... .
 * <p>Terrain-like builds (buildTerrain/buildTranslucent): the extended compressed terrain inputs,
 * the terrain config's UBO blocks (bindings 0/1/2) and push constants kept
 * verbatim, sampler bindings 3,4,... .
 */
public final class PackPipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    private PackPipelines() {}

    /** A successfully built pack post pipeline plus the slots its samplers occupy. */
    public record PackPost(String name, GraphicsPipeline pipeline, int[] samplerSlots, String convertedFragment) {}

    /** A successfully built pack geometry (terrain) pipeline plus its sampler slots. */
    public record PackTerrain(GraphicsPipeline pipeline, int[] samplerSlots, String convertedFragment) {}

    /** A successfully built pack shadow pipeline plus its sampler slots. */
    public record PackShadow(GraphicsPipeline pipeline, int[] samplerSlots, String convertedFragment) {}

    public static PackPost buildPost(PackProgram program, String fixedVertexSource) {
        try {
            UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                    program.fragmentSource(), UniformRegistry.Stage.POST);
            if (!interfacePlan.executable()) {
                throw new IllegalStateException("pack interface is unsupported: " + interfacePlan.deviations());
            }
            String converted = LegacyGlslConverter.convertFragment(
                    program.fragmentSource(), program.fragmentPath(), false, null, null, interfacePlan);
            if (converted == null) {
                throw new IllegalStateException("legacy GLSL conversion failed");
            }

            int[] slots = interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot)
                    .toArray();
            JsonObject json = new JsonObject();
            json.addProperty("vertex", "chimera_composite/chimera_composite");
            json.addProperty("fragment", "pack/" + program.name());
            json.add("samplers", samplerArray(slots));
            json.add("UBOs", uniformUboArray(interfacePlan));
            json.add("PushConstants", new JsonArray());

            PipelineConfig config = PipelineConfig.fromJson("pack_" + program.name(), json);
            Pipeline.Builder builder = new Pipeline.Builder((VertexFormat) CustomVertexFormat.NONE, "pack_" + program.name());
            // Pipeline.Builder resolves uniform suppliers during applyConfig.
            // Install the provider first so the generated UBO never falls
            // through to VulkanMod's global uniform maps.
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
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

    /** Builds gbuffers_terrain with either the fixed vertex or the M5.2 bridge. */
    public static PackTerrain buildTerrain(PackProgram program, String fixedVertexSource) {
        return buildTerrainLike(program, fixedVertexSource, UniformRegistry.Stage.GEOMETRY);
    }

    /** Builds gbuffers_water on the host translucent terrain lane. */
    public static PackTerrain buildTranslucent(PackProgram program, String fixedVertexSource) {
        return buildTerrainLike(program, fixedVertexSource, UniformRegistry.Stage.TRANSLUCENT);
    }

    /** Shared fixed-config builder for opaque and translucent terrain families. */
    private static PackTerrain buildTerrainLike(
            PackProgram program,
            String fixedVertexSource,
            UniformRegistry.Stage stage
    ) {
        try {
            UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                    program.fragmentSource(), stage);
            if (!interfacePlan.executable()) {
                throw new IllegalStateException("pack terrain interface is unsupported: "
                        + interfacePlan.deviations());
            }
            int[] declaredSlots = interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot)
                    .toArray();
            int[] slots = interleaveLightmap(declaredSlots);
            String vertexSource = fixedVertexSource;
            LegacyGlslConverter.TerrainVaryingLayout terrainLayout = null;
            if (program.vertexSource() != null) {
                LegacyGlslConverter.TerrainVertexConversion vertex =
                        LegacyGlslConverter.convertTerrainVertex(
                                program.vertexSource(), program.vertexPath(), program.fragmentSource());
                if (vertex == null) {
                    throw new IllegalStateException("legacy terrain vertex bridge rejected the source");
                }
                vertexSource = vertex.source();
                terrainLayout = vertex.layout();
            }
            String converted = LegacyGlslConverter.convertFragment(
                    program.fragmentSource(), program.fragmentPath(), true, slots, terrainLayout, interfacePlan);
            if (converted == null) {
                throw new IllegalStateException("legacy GLSL conversion failed");
            }

            JsonObject json = ChimeraShaderLoader.loadJson("chimera_terrain.json").deepCopy();
            json.addProperty("fragment", "pack_" + program.name());
            json.add("samplers", samplerArray(slots));

            PipelineConfig config = PipelineConfig.fromJson("pack_" + program.name(), json);
            Pipeline.Builder builder = new Pipeline.Builder(
                    ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN, "pack_" + program.name());
            builder.applyConfig(config);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, converted);
            GraphicsPipeline pipeline = builder.createGraphicsPipeline();

            // Share the host's global UBO pool so per-instance section data flows in unchanged.
            for (var buffer : pipeline.getBuffers()) {
                buffer.setUseGlobalBuffer(true);
            }

            return new PackTerrain(pipeline, slots, converted);
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack {}: {} build failed: {}", program.name(),
                    stage == UniformRegistry.Stage.TRANSLUCENT ? "translucent" : "terrain",
                    e.getMessage());
            return null;
        }
    }

    /** One generated fragment UBO, or an empty array for the M4 no-uniform path. */
    private static JsonArray uniformUboArray(UniformRegistry.ProgramInterface interfacePlan) {
        JsonArray ubos = new JsonArray();
        List<UniformRegistry.UniformDeclaration> uniforms = interfacePlan.executableUniforms();
        if (uniforms.isEmpty()) {
            return ubos;
        }

        JsonObject ubo = new JsonObject();
        ubo.addProperty("type", "fragment");
        ubo.addProperty("binding", 0);
        JsonArray fields = new JsonArray();
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            JsonObject field = new JsonObject();
            field.addProperty("name", uniform.name());
            field.addProperty("type", UniformRegistry.pipelineType(uniform.glslType()));
            field.addProperty("count", UniformRegistry.pipelineCount(uniform.glslType()));
            fields.add(field);
        }
        ubo.add("fields", fields);
        ubos.add(ubo);
        return ubos;
    }

    /** Builds the strict legacy shadow program on the extended terrain inputs. */
    public static PackShadow buildShadow(PackProgram program) {
        try {
            if (program.vertexSource() == null) {
                throw new IllegalStateException("shadow program requires a vertex source");
            }
            UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                    program.fragmentSource(), UniformRegistry.Stage.SHADOW);
            if (!interfacePlan.executable()) {
                throw new IllegalStateException("pack shadow interface is unsupported: " + interfacePlan.deviations());
            }
            LegacyGlslConverter.TerrainVertexConversion vertex =
                    LegacyGlslConverter.convertShadowVertex(
                            program.vertexSource(), program.vertexPath(), program.fragmentSource());
            if (vertex == null) {
                throw new IllegalStateException("legacy shadow vertex bridge rejected the source");
            }

            int[] declaredSlots = interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot)
                    .toArray();
            int[] slots = shadowSamplerSlots(declaredSlots);
            String converted = LegacyGlslConverter.convertFragment(
                    program.fragmentSource(), program.fragmentPath(), true, slots,
                    vertex.layout(), interfacePlan);
            if (converted == null) {
                throw new IllegalStateException("legacy shadow fragment conversion failed");
            }

            JsonObject json = shadowPipelineJson();
            json.addProperty("fragment", "pack_" + program.name());
            json.add("samplers", samplerArray(slots));

            PipelineConfig config = PipelineConfig.fromJson("pack_" + program.name(), json);
            Pipeline.Builder builder = new Pipeline.Builder(
                    ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN, "pack_" + program.name());
            builder.applyConfig(config);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertex.source());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, converted);
            GraphicsPipeline pipeline = builder.createGraphicsPipeline();
            for (var buffer : pipeline.getBuffers()) {
                buffer.setUseGlobalBuffer(true);
            }
            return new PackShadow(pipeline, slots, converted);
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack {}: shadow build failed: {}", program.name(), e.getMessage());
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
    /**
     * The fixed terrain vertex reads its LightMap at descriptor index 1
     * (layout(binding = 4), registry slot 2). Force registry slot 2 into the
     * pack's emitted sampler array at that position: without it the vertex's
     * lightmap fetch lands on the wrong descriptor (with the simplex fixture,
     * the shadow map) - torch/sky light never reaches geometry and the shadow
     * content renders as blotchy per-vertex darkening.
     */
    private static int[] interleaveLightmap(int[] slots) {
        int[] withoutLightmap = java.util.Arrays.stream(slots)
                .filter(slot -> slot != 2)
                .toArray();
        boolean hasAtlas = java.util.Arrays.stream(withoutLightmap).anyMatch(slot -> slot == 0);
        int[] withAtlas = hasAtlas
                ? withoutLightmap
                : prepend(0, withoutLightmap);
        int[] out = new int[withAtlas.length + 1];
        System.arraycopy(withAtlas, 0, out, 0, 1);
        out[1] = 2;
        System.arraycopy(withAtlas, 1, out, 2, withAtlas.length - 1);
        return out;
    }

    /** Keep the host's atlas/lightmap positions when a shadow shader asks for lightmap. */
    static int[] shadowSamplerSlots(int[] declaredSlots) {
        boolean hasLightmap = java.util.Arrays.stream(declaredSlots).anyMatch(slot -> slot == 2);
        if (!hasLightmap) {
            return declaredSlots;
        }
        return new int[] {0, 2};
    }

    /** Return an isolated copy so pack sampler and fragment fields cannot alter the host config. */
    static JsonObject shadowPipelineJson() {
        return ChimeraShaderLoader.loadJson("chimera_shadow.json").deepCopy();
    }

    private static int[] prepend(int value, int[] values) {
        int[] result = new int[values.length + 1];
        result[0] = value;
        System.arraycopy(values, 0, result, 1, values.length);
        return result;
    }
}
