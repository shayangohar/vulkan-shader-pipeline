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
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.mixin.ChimeraPipelineBuilderAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;

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
    private static final Pattern STORAGE_IMAGE_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?"
                    + "(?:(?:uniform|writeonly|readonly|coherent|volatile|restrict)\\s+)*"
                    + "(u?i?image3D)\\s+([A-Za-z_]\\w*)\\s*;");

    private PackPipelines() {}

    /** A successfully built pack post pipeline plus the slots its samplers occupy. */
    public record PackPost(
            String name,
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            List<String> samplerNames,
            List<Integer> requiredColorInputs,
            String convertedFragment,
            PostTargetPlan targetPlan
    ) {
        public PackPost {
            samplerNames = samplerNames == null ? List.of() : List.copyOf(samplerNames);
            requiredColorInputs = requiredColorInputs == null
                    ? List.of()
                    : requiredColorInputs.stream().distinct().sorted().toList();
        }
    }

    /** A successfully built pack geometry (terrain) pipeline plus its sampler slots. */
    public record PackTerrain(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String convertedFragment,
            GeometryOutputPlan outputPlan
    ) {
        public PackTerrain(GraphicsPipeline pipeline, int[] samplerSlots, String convertedFragment) {
            this(pipeline, samplerSlots, convertedFragment, null);
        }

        public boolean requiresDynamicAttachments() {
            return outputPlan != null && outputPlan.requiresMrt();
        }
    }

    /** A successfully built pack shadow pipeline plus its sampler slots. */
    public record PackShadow(GraphicsPipeline pipeline, int[] samplerSlots, String convertedFragment) {}

    /** A successfully built world-entity pipeline plus its sampler slots. */
    public record PackEntity(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String convertedVertex,
            String convertedFragment
    ) {}

    /** A successfully built host particle pipeline plus its sampler slots. */
    public record PackParticle(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String convertedVertex,
            String convertedFragment
    ) {}

    /** A sky or cloud pipeline that inherits the host pass state. */
    public record PackSky(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String convertedVertex,
            String convertedFragment,
            VertexFormat vertexFormat
    ) {}

    public static PackSky buildSky(PackProgramPlan plan) {
        return buildSkyLike(plan, UniformRegistry.Stage.SKY,
                skyVertexFormat(plan),
                "pack_" + (plan == null ? "sky" : plan.name()));
    }

    public static PackSky buildCloud(PackProgramPlan plan) {
        return buildSkyLike(plan, UniformRegistry.Stage.CLOUD,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
                "pack_" + (plan == null ? "clouds" : plan.name()));
    }

    private static VertexFormat skyVertexFormat(PackProgramPlan plan) {
        if (plan == null || plan.familyAdapter() == null) {
            return null;
        }
        return switch (plan.familyAdapter().vertexContract()) {
            case SKY_POSITION -> com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION;
            case SKY_POSITION_COLOR -> com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR;
            case SKY_POSITION_UV -> com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX;
            case SKY_POSITION_COLOR_UV ->
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR;
            default -> null;
        };
    }

    private static PackSky buildSkyLike(
            PackProgramPlan plan, UniformRegistry.Stage stage, VertexFormat vertexFormat,
            String pipelineName
    ) {
        if (plan == null || !plan.executable() || plan.convertedVertex() == null
                || plan.convertedFragment() == null || plan.interfacePlan() == null
                || plan.interfacePlan().effective(stage).stage() != stage) {
            return null;
        }
        try {
            UniformRegistry.ProgramInterface interfacePlan = plan.interfacePlan().effective(stage);
            int[] slots = entitySamplerSlots(interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot).toArray());
            PipelineConfig.Builder configBuilder = PipelineConfig.builder()
                    .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_VERTEX_BIT)
                            .addUniform("mat4", "ModelViewMat")
                            .addUniform("vec4", "ColorModulator")
                            .addUniform("vec3", "ModelOffset")
                            .addUniform("mat4", "TextureMat")
                            .build())
                    .addUB(PipelineConfig.UB.builder(1, VK_SHADER_STAGE_VERTEX_BIT)
                            .addUniform("mat4", "ProjMat")
                            .build());
            if (!interfacePlan.executableUniforms().isEmpty()) {
                PipelineConfig.UB.Builder uniforms = PipelineConfig.UB.builder(
                        2, VK_SHADER_STAGE_FRAGMENT_BIT);
                for (UniformRegistry.UniformDeclaration uniform : interfacePlan.executableUniforms()) {
                    uniforms.addUniform(uniform.glslType(), uniform.name());
                }
                configBuilder.addUB(uniforms.build());
            } else {
                // VulkanMod's descriptor-layout builder indexes by binding,
                // not by a sparse binding list. Reserve binding 2 so the
                // geometry sampler lane remains at binding 3 even when the
                // pack declares no fragment UBO fields.
                configBuilder.addUB(PipelineConfig.UB.builder(2, VK_SHADER_STAGE_FRAGMENT_BIT)
                        .setSize(16)
                        .build());
            }
            // Sky and cloud conversion uses the same geometry descriptor lane
            // as the converter: bindings 0 and 1 are host transform UBOs,
            // binding 2 is the optional pack UBO, and sampled resources start
            // at the geometry sampler base.
            int samplerBase = LegacyGlslConverter.geometrySamplerBindingBase()
                    + (interfacePlan.executableUniforms().isEmpty() ? 0 : 1);
            for (int index = 0; index < slots.length; index++) {
                int slot = slots[index];
                configBuilder.addImageDescriptor(samplerBase + index, "sampler2D",
                        "Sampler" + slot, net.vulkanmod.vulkan.texture.VTextureSelector
                                .getTextureIdx("Sampler" + slot));
            }
            Pipeline.Builder builder = new Pipeline.Builder(vertexFormat, pipelineName);
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            builder.applyConfig(configBuilder.build());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, plan.convertedVertex());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, plan.convertedFragment());
            GraphicsPipeline pipeline = builder.createGraphicsPipeline();
            for (var buffer : pipeline.getBuffers()) {
                buffer.setUseGlobalBuffer(true);
            }
            return new PackSky(pipeline, slots, plan.convertedVertex(), plan.convertedFragment(),
                    vertexFormat);
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack {}: sky/cloud build failed: {} ({})", pipelineName,
                    e.getMessage(), e.getClass().getSimpleName());
            return null;
        }
    }

    /** Builds a post pipeline from the already prepared and translated plan. */
    public static PackPost buildPost(PackProgramPlan plan, String fixedVertexSource) {
        return buildPost(plan, fixedVertexSource, PackAdvancedResourcePlan.empty());
    }

    /** Builds a post pipeline with the session's advanced-resource contract. */
    public static PackPost buildPost(
            PackProgramPlan plan,
            String fixedVertexSource,
            PackAdvancedResourcePlan advancedResources
    ) {
        if (plan == null || !plan.executable() || plan.targetPlan() == null
                || plan.convertedFragment() == null) {
            return null;
        }
        try {
            advancedResources = advancedResources == null
                    ? PackAdvancedResourcePlan.empty() : advancedResources;
            UniformRegistry.ProgramInterface interfacePlan = plan.interfacePlan()
                    .effective(UniformRegistry.Stage.POST);
            int[] slots = interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot)
                    .toArray();
            List<String> samplerNames = interfacePlan.samplers().stream()
                    .map(UniformRegistry.SamplerBinding::name)
                    .toList();
            JsonObject json = new JsonObject();
            json.addProperty("vertex", "chimera_composite/chimera_composite");
            json.addProperty("fragment", "pack/" + plan.name());
            json.add("samplers", samplerArray(slots));
            json.add("UBOs", uniformUboArray(interfacePlan));
            json.add("PushConstants", new JsonArray());

            int storageBindingBase = nextBinding(json, slots.length);
            String vertexSource = bindStorageImages(
                    plan.convertedVertex() == null ? fixedVertexSource : plan.convertedVertex(),
                    plan.name(), advancedResources, storageBindingBase);
            String fragmentSource = bindStorageImages(
                    plan.convertedFragment(), plan.name(), advancedResources, storageBindingBase);
            if (vertexSource == null || fragmentSource == null) {
                throw new IllegalStateException("advanced post image declaration rejected");
            }

            PipelineConfig config = PipelineConfig.fromJson("pack_" + plan.name(), json);
            Pipeline.Builder builder = new Pipeline.Builder(
                    (VertexFormat) CustomVertexFormat.NONE, "pack_" + plan.name());
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            builder.applyConfig(config);
            setStorageBackedSamplerLayouts(builder, slots, interfacePlan, advancedResources,
                    plan.name(), nextBinding(json, 0));
            addStorageImageDescriptors(builder, plan.name(), advancedResources, storageBindingBase);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, fragmentSource);
            GraphicsPipeline pipeline = builder.createGraphicsPipeline();
            return new PackPost(plan.name(), pipeline, slots, samplerNames,
                    colorInputTargets(samplerNames), fragmentSource, plan.targetPlan());
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack {}: planned post build failed: {}", plan.name(), e.getMessage());
            return null;
        }
    }

    /** Builds the extended terrain pipeline from the shared program plan. */
    public static PackTerrain buildTerrain(PackProgramPlan plan, String fixedVertexSource) {
        return buildTerrainLikePlan(plan, fixedVertexSource,
                plan == null ? TerrainMaterialPlan.legacy() : plan.terrainMaterial(), false, 0,
                PackAdvancedResourcePlan.empty());
    }

    /** Builds terrain with the pack-wide union format selected at load time. */
    public static PackTerrain buildTerrain(
            PackProgramPlan plan,
            String fixedVertexSource,
            TerrainMaterialPlan materialPlan
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, false, 0,
                PackAdvancedResourcePlan.empty());
    }

    public static PackTerrain buildTerrain(
            PackProgramPlan plan, String fixedVertexSource, TerrainMaterialPlan materialPlan,
            boolean coverage, int targetFormat
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, coverage, targetFormat,
                PackAdvancedResourcePlan.empty());
    }

    public static PackTerrain buildTerrain(
            PackProgramPlan plan, String fixedVertexSource, TerrainMaterialPlan materialPlan,
            boolean coverage, int targetFormat, PackAdvancedResourcePlan advancedResources
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, coverage, targetFormat,
                advancedResources);
    }

    /** Builds the translucent terrain pipeline from the shared program plan. */
    public static PackTerrain buildTranslucent(PackProgramPlan plan, String fixedVertexSource) {
        return buildTerrainLikePlan(plan, fixedVertexSource,
                plan == null ? TerrainMaterialPlan.legacy() : plan.terrainMaterial(), false, 0,
                PackAdvancedResourcePlan.empty());
    }

    /** Builds water with the pack-wide union format selected at load time. */
    public static PackTerrain buildTranslucent(
            PackProgramPlan plan,
            String fixedVertexSource,
            TerrainMaterialPlan materialPlan
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, false, 0,
                PackAdvancedResourcePlan.empty());
    }

    public static PackTerrain buildTranslucent(
            PackProgramPlan plan, String fixedVertexSource, TerrainMaterialPlan materialPlan,
            boolean coverage, int targetFormat
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, coverage, targetFormat,
                PackAdvancedResourcePlan.empty());
    }

    public static PackTerrain buildTranslucent(
            PackProgramPlan plan, String fixedVertexSource, TerrainMaterialPlan materialPlan,
            boolean coverage, int targetFormat, PackAdvancedResourcePlan advancedResources
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, coverage, targetFormat,
                advancedResources);
    }

    /** Builds the shadow pipeline from the shared program plan. */
    public static PackShadow buildShadow(PackProgramPlan plan) {
        return buildShadow(plan, plan == null ? TerrainMaterialPlan.legacy() : plan.terrainMaterial());
    }

    /**
     * Builds the shadow pipeline using the pack-wide terrain format. Shadow
     * shaders remain on the legacy source contract, but their vertices must
     * match the active terrain builder when a modern terrain family is live.
     */
    public static PackShadow buildShadow(
            PackProgramPlan plan,
            TerrainMaterialPlan materialPlan
    ) {
        return buildShadow(plan, materialPlan, PackAdvancedResourcePlan.empty());
    }

    public static PackShadow buildShadow(
            PackProgramPlan plan,
            TerrainMaterialPlan materialPlan,
            PackAdvancedResourcePlan advancedResources
    ) {
        if (plan == null || !plan.executable() || plan.convertedFragment() == null
                || plan.convertedVertex() == null) {
            return null;
        }
        try {
            UniformRegistry.ProgramInterface interfacePlan = plan.interfacePlan()
                    .effective(UniformRegistry.Stage.SHADOW);
            int[] declaredSlots = interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot)
                    .toArray();
            int[] slots = shadowSamplerSlots(declaredSlots);
            JsonObject json = shadowPipelineJson();
            json.addProperty("fragment", "pack_" + plan.name());
            json.add("samplers", samplerArray(slots));
            JsonArray shadowUniforms = uniformUboArray(interfacePlan, 3, "all");
            if (shadowUniforms.size() > 0) {
                JsonArray ubos = json.getAsJsonArray("UBOs");
                for (var element : shadowUniforms) {
                    ubos.add(element);
                }
            }

            int storageBindingBase = nextBinding(json, slots.length);
            String fragmentSource = bindStorageImages(plan.convertedFragment(), plan.name(),
                    advancedResources, storageBindingBase);
            if (fragmentSource == null) {
                throw new IllegalStateException("advanced shadow image declaration rejected");
            }
            String vertexSource = bindStorageImages(plan.convertedVertex(), plan.name(),
                    advancedResources, storageBindingBase);
            if (vertexSource == null) {
                throw new IllegalStateException("advanced shadow vertex image declaration rejected");
            }

            PipelineConfig config = PipelineConfig.fromJson("pack_" + plan.name(), json);
            Pipeline.Builder builder = new Pipeline.Builder(
                    ChimeraVertexFormats.terrainFormat(materialPlan), "pack_" + plan.name());
            // Shadow UBOs use the same canonical provider as post and family pipelines.
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            builder.applyConfig(config);
            setStorageBackedSamplerLayouts(builder, slots, interfacePlan, advancedResources, plan.name(),
                    nextBinding(json, 0));
            addStorageImageDescriptors(builder, plan.name(), advancedResources, storageBindingBase);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, fragmentSource);
            GraphicsPipeline pipeline = builder.createGraphicsPipeline();
            for (var buffer : pipeline.getBuffers()) {
                buffer.setUseGlobalBuffer(true);
            }
            return new PackShadow(pipeline, slots, fragmentSource);
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack {}: planned shadow build failed: {}", plan.name(), e.getMessage());
            return null;
        }
    }

    /** Builds the guarded world entity pipeline on the append-only entity format. */
    public static PackEntity buildEntity(PackProgramPlan plan) {
        return buildEntityLike(plan, UniformRegistry.Stage.ENTITY, "pack_gbuffers_entities");
    }

    /** Builds any world entity-family adapter with the family-specific host contract. */
    public static PackEntity buildEntityFamily(PackProgramPlan plan) {
        if (plan == null) {
            return null;
        }
        FamilyAdapterPlan.Family family = plan.familyAdapter().family();
        UniformRegistry.Stage stage = switch (family) {
            case BLOCK, DAMAGED_BLOCK -> UniformRegistry.Stage.BLOCK;
            case HAND, HAND_WATER -> UniformRegistry.Stage.HAND;
            default -> UniformRegistry.Stage.ENTITY;
        };
        VertexFormat format = switch (family) {
            case HAND, HAND_WATER -> ChimeraVertexFormats.EXTENDED_PARTICLE;
            default -> ChimeraVertexFormats.EXTENDED_ENTITY;
        };
        return buildEntityLike(plan, stage, "pack_" + plan.name(), format);
    }

    /** Builds the block-entity adapter on the same append-only host format. */
    public static PackEntity buildBlock(PackProgramPlan plan) {
        return buildEntityLike(plan, UniformRegistry.Stage.BLOCK, "pack_gbuffers_block");
    }

    /** Builds the first-person hand adapter on the same append-only host format. */
    public static PackEntity buildHand(PackProgramPlan plan) {
        return buildEntityLike(plan, UniformRegistry.Stage.HAND, "pack_gbuffers_hand",
                ChimeraVertexFormats.EXTENDED_PARTICLE);
    }

    private static PackEntity buildEntityLike(
            PackProgramPlan plan,
            UniformRegistry.Stage stage,
            String pipelineName
    ) {
        return buildEntityLike(plan, stage, pipelineName, ChimeraVertexFormats.EXTENDED_ENTITY);
    }

    private static PackEntity buildEntityLike(
            PackProgramPlan plan,
            UniformRegistry.Stage stage,
            String pipelineName,
            VertexFormat vertexFormat
    ) {
        if (plan == null || !plan.executable() || plan.convertedVertex() == null
                || plan.convertedFragment() == null
                || plan.interfacePlan() == null
                || plan.interfacePlan().effective(stage).stage() != stage) {
            return null;
        }
        try {
            UniformRegistry.ProgramInterface interfacePlan = plan.interfacePlan()
                    .effective(stage);
            int[] slots = entitySamplerSlots(interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot).toArray());
            PipelineConfig.Builder configBuilder = PipelineConfig.builder()
                    .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_VERTEX_BIT)
                            .addUniform("mat4", "ModelViewMat")
                            .addUniform("vec4", "ColorModulator")
                            .addUniform("vec3", "ModelOffset")
                            .addUniform("mat4", "TextureMat")
                            .build())
                    .addUB(PipelineConfig.UB.builder(1, VK_SHADER_STAGE_VERTEX_BIT)
                            .addUniform("mat4", "ProjMat")
                            .build());
            if (!interfacePlan.executableUniforms().isEmpty()) {
                PipelineConfig.UB.Builder uniforms = PipelineConfig.UB.builder(
                        2, VK_SHADER_STAGE_FRAGMENT_BIT);
                for (UniformRegistry.UniformDeclaration uniform : interfacePlan.executableUniforms()) {
                    uniforms.addUniform(uniform.glslType(), uniform.name());
                }
                configBuilder.addUB(uniforms.build());
            }
            int samplerBase = interfacePlan.executableUniforms().isEmpty() ? 2 : 3;
            for (int index = 0; index < slots.length; index++) {
                int slot = slots[index];
                configBuilder.addImageDescriptor(samplerBase + index, "sampler2D",
                        "Sampler" + slot, net.vulkanmod.vulkan.texture.VTextureSelector
                                .getTextureIdx("Sampler" + slot));
            }
            PipelineConfig config = configBuilder.build();
            Pipeline.Builder builder = new Pipeline.Builder(
                    vertexFormat,
                    pipelineName);
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            builder.applyConfig(config);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, plan.convertedVertex());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, plan.convertedFragment());
            GraphicsPipeline pipeline = builder.createGraphicsPipeline();
            for (var buffer : pipeline.getBuffers()) {
                buffer.setUseGlobalBuffer(true);
            }
            return new PackEntity(pipeline, slots, plan.convertedVertex(), plan.convertedFragment());
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack {}: planned {} build failed: {}", plan.name(),
                    stage.name().toLowerCase(), e.getMessage());
            return null;
        }
    }

    /** Builds the particle family on the host DefaultVertexFormat.PARTICLE path. */
    public static PackParticle buildParticle(PackProgramPlan plan) {
        return buildParticle(plan, "pack_gbuffers_particles");
    }

    /** Builds an independent opaque or translucent particle-family pipeline. */
    public static PackParticle buildParticleFamily(PackProgramPlan plan) {
        return plan == null ? null : buildParticle(plan, "pack_" + plan.name());
    }

    private static PackParticle buildParticle(PackProgramPlan plan, String pipelineName) {
        if (plan == null || !plan.executable() || plan.convertedVertex() == null
                || plan.convertedFragment() == null || plan.interfacePlan() == null
                || plan.interfacePlan().effective(UniformRegistry.Stage.PARTICLE).stage()
                != UniformRegistry.Stage.PARTICLE) {
            return null;
        }
        try {
            UniformRegistry.ProgramInterface interfacePlan = plan.interfacePlan()
                    .effective(UniformRegistry.Stage.PARTICLE);
            int[] slots = entitySamplerSlots(interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot).toArray());
            PipelineConfig.Builder configBuilder = PipelineConfig.builder()
                    .addUB(PipelineConfig.UB.builder(0, VK_SHADER_STAGE_VERTEX_BIT)
                            .addUniform("mat4", "ModelViewMat")
                            .addUniform("vec4", "ColorModulator")
                            .addUniform("vec3", "ModelOffset")
                            .addUniform("mat4", "TextureMat")
                            .build())
                    .addUB(PipelineConfig.UB.builder(1, VK_SHADER_STAGE_VERTEX_BIT)
                            .addUniform("mat4", "ProjMat")
                            .build());
            if (!interfacePlan.executableUniforms().isEmpty()) {
                PipelineConfig.UB.Builder uniforms = PipelineConfig.UB.builder(
                        2, VK_SHADER_STAGE_FRAGMENT_BIT);
                for (UniformRegistry.UniformDeclaration uniform : interfacePlan.executableUniforms()) {
                    uniforms.addUniform(uniform.glslType(), uniform.name());
                }
                configBuilder.addUB(uniforms.build());
            }
            int samplerBase = interfacePlan.executableUniforms().isEmpty() ? 2 : 3;
            for (int index = 0; index < slots.length; index++) {
                int slot = slots[index];
                configBuilder.addImageDescriptor(samplerBase + index, "sampler2D",
                        "Sampler" + slot, net.vulkanmod.vulkan.texture.VTextureSelector
                                .getTextureIdx("Sampler" + slot));
            }
            Pipeline.Builder builder = new Pipeline.Builder(
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE,
                    pipelineName);
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            builder.applyConfig(configBuilder.build());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, plan.convertedVertex());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, plan.convertedFragment());
            GraphicsPipeline pipeline = builder.createGraphicsPipeline();
            for (var buffer : pipeline.getBuffers()) {
                buffer.setUseGlobalBuffer(true);
            }
            return new PackParticle(pipeline, slots, plan.convertedVertex(), plan.convertedFragment());
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack {}: planned particle build failed: {}", plan.name(), e.getMessage());
            return null;
        }
    }

    public static PackPost buildPost(PackProgram program, String fixedVertexSource) {
        return buildPost(program, fixedVertexSource, Map.of());
    }

    /** Builds a post pipeline using the pack's deterministic target formats. */
    public static PackPost buildPost(
            PackProgram program,
            String fixedVertexSource,
            Map<Integer, Integer> targetFormats
    ) {
        return buildPost(program, fixedVertexSource, targetFormats, Map.of());
    }

    /** Builds a post pipeline with the same parsed pack constants used by probing. */
    public static PackPost buildPost(
            PackProgram program,
            String fixedVertexSource,
            Map<Integer, Integer> targetFormats,
            Map<String, String> packConstants
    ) {
        try {
            String fragmentSource = program.executableFragmentSource();
            Path sourcePath = program.preparedFragmentSource() == null
                    ? program.fragmentPath() : null;
            PostTargetPlan targetPlan = PostTargetPlan.parse(
                    program.name(), fragmentSource, targetFormats).plan();
            UniformRegistry.ProgramInterface interfacePlan = program.preparedFragmentSource() == null
                    ? UniformRegistry.planPost(fragmentSource, targetPlan)
                    : UniformRegistry.planPreparedPost(fragmentSource, targetPlan);
            if (!interfacePlan.executable()) {
                throw new IllegalStateException("pack interface is unsupported: " + interfacePlan.deviations());
            }
            String converted = LegacyGlslConverter.convertPostFragment(
                    fragmentSource, sourcePath, interfacePlan, targetPlan, packConstants);
            if (converted == null) {
                throw new IllegalStateException("legacy GLSL conversion failed");
            }

            int[] slots = interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot)
                    .toArray();
            List<String> samplerNames = interfacePlan.samplers().stream()
                    .map(UniformRegistry.SamplerBinding::name)
                    .toList();
            List<Integer> requiredColorInputs = colorInputTargets(samplerNames);
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

            return new PackPost(program.name(), pipeline, slots, samplerNames,
                    requiredColorInputs, converted, targetPlan);
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
            String fragmentSource = program.executableFragmentSource();
            String vertexSourceText = program.executableVertexSource();
            boolean prepared = program.preparedFragmentSource() != null;
            UniformRegistry.ProgramInterface interfacePlan = prepared
                    ? UniformRegistry.planPrepared(fragmentSource, stage)
                    : UniformRegistry.plan(fragmentSource, stage);
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
            if (vertexSourceText != null) {
                LegacyGlslConverter.TerrainVertexConversion vertex =
                        LegacyGlslConverter.convertTerrainVertex(
                                vertexSourceText, prepared ? null : program.vertexPath(), fragmentSource);
                if (vertex == null) {
                    throw new IllegalStateException("legacy terrain vertex bridge rejected the source");
                }
                vertexSource = vertex.source();
                terrainLayout = vertex.layout();
            }
            String converted = LegacyGlslConverter.convertFragment(
                    fragmentSource, prepared ? null : program.fragmentPath(), true, slots, terrainLayout, interfacePlan);
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

            return new PackTerrain(pipeline, slots, converted, GeometryOutputPlan.empty(program.name()));
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack {}: {} build failed: {}", program.name(),
                    stage == UniformRegistry.Stage.TRANSLUCENT ? "translucent" : "terrain",
                    e.getMessage());
            return null;
        }
    }

    private static PackTerrain buildTerrainLikePlan(
            PackProgramPlan plan,
            String fixedVertexSource,
            TerrainMaterialPlan materialPlan,
            boolean coverage,
            int targetFormat,
            PackAdvancedResourcePlan advancedResources
    ) {
        if (plan == null || !plan.executable() || plan.convertedFragment() == null) {
            return null;
        }
        try {
            UniformRegistry.Stage stage = plan.name().equals("gbuffers_water")
                    ? UniformRegistry.Stage.TRANSLUCENT : UniformRegistry.Stage.GEOMETRY;
            UniformRegistry.ProgramInterface interfacePlan = plan.interfacePlan().effective(stage);
            int[] slots = interleaveLightmap(interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot).toArray());
            JsonObject json = ChimeraShaderLoader.loadJson("chimera_terrain.json").deepCopy();
            json.addProperty("fragment", "pack_" + plan.name());
            json.add("samplers", samplerArray(slots));
            if (stage != UniformRegistry.Stage.TRANSLUCENT
                    && !interfacePlan.executableUniforms().isEmpty()) {
                JsonArray ubos = json.getAsJsonArray("UBOs");
                if (ubos == null) {
                    ubos = new JsonArray();
                    json.add("UBOs", ubos);
                }
                ubos.addAll(uniformUboArray(interfacePlan, 3, "all"));
            }
            int storageBindingBase = nextBinding(json, slots.length);
            String fragment = coverage
                    ? LegacyGlslConverter.withCoverageOutput(plan.convertedFragment())
                    : plan.convertedFragment();
            fragment = bindStorageImages(fragment, plan.name(), advancedResources, storageBindingBase);
            if (fragment == null) {
                throw new IllegalStateException("advanced terrain image declaration rejected");
            }
            String vertex = plan.convertedVertex() == null
                    ? fixedVertexSource : plan.convertedVertex();
            vertex = bindStorageImages(vertex, plan.name(), advancedResources, storageBindingBase);
            if (vertex == null) {
                throw new IllegalStateException("advanced terrain vertex image declaration rejected");
            }
            PipelineConfig config = PipelineConfig.fromJson("pack_" + plan.name(), json);
            Pipeline.Builder builder = new Pipeline.Builder(
                    ChimeraVertexFormats.terrainFormat(materialPlan), "pack_" + plan.name());
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            builder.applyConfig(config);
            // A terrain shader may sample an image that it also writes through
            // an image3D descriptor. Keep the combined sampler in GENERAL for
            // that shared image. The shadow builder already applies this rule;
            // omitting it here leaves one descriptor read-only and the other
            // GENERAL for the same Vulkan image, which is invalid at draw time.
            setStorageBackedSamplerLayouts(builder, slots, interfacePlan, advancedResources, plan.name(),
                    nextBinding(json, 0));
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertex);
            GeometryOutputPlan outputPlan = plan.geometryOutputPlan();
            boolean dynamicMrt = outputPlan != null && outputPlan.requiresMrt();
            if (dynamicMrt) {
                MrtPipelineContext.begin(outputPlan.outputFormatsArray(), deviceMaxColorAttachments());
            } else if (coverage) {
                MrtPipelineContext.beginGeometry(targetFormat,
                        org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT, deviceMaxColorAttachments());
            }
            addStorageImageDescriptors(builder, plan.name(), advancedResources, storageBindingBase);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, fragment);
            GraphicsPipeline pipeline = builder.createGraphicsPipeline();
            if (dynamicMrt) {
                MrtPipelineContext.register(pipeline, outputPlan.outputFormatsArray());
            } else if (coverage) {
                MrtPipelineContext.register(pipeline, new int[] {
                        targetFormat, org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT
                });
            }
            if (dynamicMrt || coverage) MrtPipelineContext.end();
            for (var buffer : pipeline.getBuffers()) {
                buffer.setUseGlobalBuffer(true);
            }
            return new PackTerrain(pipeline, slots, fragment, outputPlan);
        } catch (Exception e) {
            MrtPipelineContext.end();
            LOGGER.warn("[chimera] pack {}: planned terrain build failed: {}", plan.name(), e.getMessage());
            return null;
        }
    }

    private static int deviceMaxColorAttachments() {
        if (net.vulkanmod.vulkan.device.DeviceManager.device == null) return 8;
        return ((net.chimera.mixin.ChimeraDeviceAccessor) net.vulkanmod.vulkan.device.DeviceManager.device)
                .chimera$properties().limits().maxColorAttachments();
    }

    /** One generated fragment UBO, or an empty array for the M4 no-uniform path. */
    private static JsonArray uniformUboArray(UniformRegistry.ProgramInterface interfacePlan) {
        return uniformUboArray(interfacePlan, 0, "fragment");
    }

    private static JsonArray uniformUboArray(
            UniformRegistry.ProgramInterface interfacePlan,
            int binding,
            String type
    ) {
        JsonArray ubos = new JsonArray();
        List<UniformRegistry.UniformDeclaration> uniforms = interfacePlan.executableUniforms();
        if (uniforms.isEmpty()) {
            return ubos;
        }

        JsonObject ubo = new JsonObject();
        ubo.addProperty("type", type);
        ubo.addProperty("binding", binding);
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
            if (program.executableVertexSource() == null) {
                throw new IllegalStateException("shadow program requires a vertex source");
            }
            String fragmentSource = program.executableFragmentSource();
            String vertexSourceText = program.executableVertexSource();
            boolean prepared = program.preparedFragmentSource() != null;
            UniformRegistry.ProgramInterface interfacePlan = prepared
                    ? UniformRegistry.planPrepared(fragmentSource, UniformRegistry.Stage.SHADOW)
                    : UniformRegistry.plan(fragmentSource, UniformRegistry.Stage.SHADOW);
            if (!interfacePlan.executable()) {
                throw new IllegalStateException("pack shadow interface is unsupported: " + interfacePlan.deviations());
            }
            LegacyGlslConverter.TerrainVertexConversion vertex =
                    LegacyGlslConverter.convertShadowVertex(
                        vertexSourceText, prepared ? null : program.vertexPath(), fragmentSource,
                        interfacePlan);
            if (vertex == null) {
                throw new IllegalStateException("legacy shadow vertex bridge rejected the source");
            }

            int[] declaredSlots = interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot)
                    .toArray();
            int[] slots = shadowSamplerSlots(declaredSlots);
            String converted = LegacyGlslConverter.convertFragment(
                    fragmentSource, prepared ? null : program.fragmentPath(), true, slots,
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
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
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
    static int[] interleaveLightmap(int[] slots) {
        int[] withoutLightmap = java.util.Arrays.stream(slots)
                .filter(slot -> slot != 2)
                .distinct()
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
        java.util.TreeSet<Integer> slots = new java.util.TreeSet<>();
        for (int slot : declaredSlots) slots.add(slot);
        if (hasLightmap) slots.add(0);
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Preserve the registry slots used by the host entity draw. */
    public static int[] entitySamplerSlots(int[] declaredSlots) {
        return java.util.Arrays.stream(declaredSlots).distinct().sorted().toArray();
    }

    /** Return an isolated copy so pack sampler and fragment fields cannot alter the host config. */
    static JsonObject shadowPipelineJson() {
        return ChimeraShaderLoader.loadJson("chimera_shadow.json").deepCopy();
    }

    private static int nextBinding(JsonObject json, int samplerCount) {
        int next = 0;
        JsonArray ubos = json == null ? null : json.getAsJsonArray("UBOs");
        if (ubos != null) {
            for (var element : ubos) {
                if (!element.isJsonObject()) continue;
                next = Math.max(next, element.getAsJsonObject().get("binding").getAsInt() + 1);
            }
        }
        return next + samplerCount;
    }

    private static void addStorageImageDescriptors(
            Pipeline.Builder builder,
            String program,
            PackAdvancedResourcePlan advancedResources,
            int bindingBase
    ) {
        if (builder == null || advancedResources == null) return;
        int index = 0;
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding
                : advancedResources.graphicsImages(program)) {
            builder.addImageDescriptor(new ImageDescriptor(
                    bindingBase + index++, "image3D", "Sampler" + binding.selectorSlot(),
                    binding.selectorSlot(), VK_DESCRIPTOR_TYPE_STORAGE_IMAGE));
        }
    }

    /**
     * Keep a combined sampler on GENERAL when it aliases a pack-owned storage
     * image. VulkanMod's default descriptor path assumes every sampler is
     * read-only and would otherwise issue an unsupported GENERAL ->
     * SHADER_READ_ONLY transition during descriptor binding.
     */
    private static void setStorageBackedSamplerLayouts(
            Pipeline.Builder builder,
            int[] slots,
            UniformRegistry.ProgramInterface interfacePlan,
            PackAdvancedResourcePlan advancedResources,
            String program,
            int samplerBase
    ) {
        if (builder == null || advancedResources == null) return;
        java.util.Set<Integer> storageSlots = advancedResources.graphicsImages(program).stream()
                .map(PackAdvancedResourcePlan.GraphicsImageBinding::selectorSlot)
                .collect(java.util.stream.Collectors.toSet());
        if (interfacePlan != null) {
            java.util.Set<String> advancedSamplers = advancedResources.images().values().stream()
                    .map(PackAdvancedResourcePlan.ImageSpec::sampler)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            interfacePlan.samplers().stream()
                    .filter(binding -> advancedSamplers.contains(binding.name()))
                    .map(UniformRegistry.SamplerBinding::slot)
                    .forEach(storageSlots::add);
        }
        if (storageSlots.isEmpty()) return;
        List<ImageDescriptor> descriptors = ((ChimeraPipelineBuilderAccessor) (Object) builder)
                .chimera$imageDescriptors();
        for (int index = 0; index < slots.length; index++) {
            if (!storageSlots.contains(slots[index])) continue;
            int binding = samplerBase + index;
            for (ImageDescriptor descriptor : descriptors) {
                if (descriptor.getBinding() == binding) {
                    descriptor.setLayout(VK_IMAGE_LAYOUT_GENERAL);
                    break;
                }
            }
        }
    }

    private static String bindStorageImages(
            String source,
            String program,
            PackAdvancedResourcePlan advancedResources,
            int bindingBase
    ) {
        if (source == null) return null;
        List<PackAdvancedResourcePlan.GraphicsImageBinding> bindings = advancedResources == null
                ? List.of() : advancedResources.graphicsImages(program);
        if (bindings.isEmpty()) {
            return STORAGE_IMAGE_DECLARATION.matcher(source).find() ? null : source;
        }
        Map<String, PackAdvancedResourcePlan.GraphicsImageBinding> bySymbol = new java.util.TreeMap<>();
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding : bindings) {
            bySymbol.put(binding.symbol(), binding);
        }
        Matcher matcher = STORAGE_IMAGE_DECLARATION.matcher(source);
        StringBuffer result = new StringBuffer();
        java.util.Set<String> emitted = new java.util.TreeSet<>();
        while (matcher.find()) {
            PackAdvancedResourcePlan.GraphicsImageBinding binding = bySymbol.get(matcher.group(2));
            if (binding == null) {
                return null;
            }
            PackAdvancedResourcePlan.ImageSpec spec = advancedResources.images()
                    .get(binding.imageName());
            if (spec == null || !spec.supported()) return null;
            int index = bindings.indexOf(binding);
            String replacement = "layout(" + spec.internalFormat() + ", binding = "
                    + (bindingBase + index) + ") uniform " + matcher.group(1)
                    + " " + matcher.group(2) + ";";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            emitted.add(binding.symbol());
        }
        matcher.appendTail(result);
        String rewritten = result.toString();
        StringBuilder generated = new StringBuilder();
        for (int index = 0; index < bindings.size(); index++) {
            PackAdvancedResourcePlan.GraphicsImageBinding binding = bindings.get(index);
            if (emitted.contains(binding.symbol())) continue;
            PackAdvancedResourcePlan.ImageSpec spec = advancedResources.images()
                    .get(binding.imageName());
            if (spec == null || !spec.supported()) return null;
            generated.append("layout(").append(spec.internalFormat())
                    .append(", binding = ").append(bindingBase + index)
                    .append(") uniform ").append(binding.glslType()).append(' ')
                    .append(binding.symbol()).append(";\n");
        }
        if (generated.isEmpty()) return rewritten;
        int versionEnd = rewritten.indexOf('\n');
        int insertAt = versionEnd < 0 ? 0 : versionEnd + 1;
        return rewritten.substring(0, insertAt) + generated + rewritten.substring(insertAt);
    }

    /** Derives the logical post color inputs once from the shared interface plan. */
    static List<Integer> colorInputTargets(List<String> samplerNames) {
        TreeSet<Integer> targets = new TreeSet<>();
        for (String sampler : samplerNames) {
            if (!sampler.startsWith("colortex")) {
                continue;
            }
            try {
                int target = Integer.parseInt(sampler.substring("colortex".length()));
                if (target >= 0 && target <= PostTargetPlan.MAX_TARGET) {
                    targets.add(target);
                }
            } catch (NumberFormatException ignored) {
                // The interface plan rejects malformed sampler names.
            }
        }
        return List.copyOf(targets);
    }

    private static int[] prepend(int value, int[] values) {
        int[] result = new int[values.length + 1];
        result[0] = value;
        System.arraycopy(values, 0, result, 1, values.length);
        return result;
    }
}
