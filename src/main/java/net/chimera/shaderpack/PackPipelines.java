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
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.render.PackStorageBufferDescriptor;
import net.chimera.render.PackStorageBufferOwner;
import net.chimera.shaderpack.PackAdvancedResourcePlan.ProgramBindingLayout;
import net.chimera.mixin.ChimeraPipelineBuilderAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
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
    private static final Pattern STORAGE_SAMPLER_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+"
                    + "(u?sampler3D)\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern SAMPLER_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+"
                    + "((?:[iu]?sampler3D|sampler2D(?:Shadow)?))\\s+"
                    + "([A-Za-z_]\\w*)\\s*;");

    private PackPipelines() {}

    /** Own the real native builder lists so metadata is equally accessible without mixins. */
    private static final class MetadataBuilder extends Pipeline.Builder implements ChimeraPipelineBuilderAccessor {
        private final List<ImageDescriptor> images = new ArrayList<>();
        MetadataBuilder(VertexFormat format, String name) {
            super(format, name);
            setUniforms(new ArrayList<>(), images);
        }
        @Override public List<ImageDescriptor> chimera$imageDescriptors() { return images; }
    }

    private static OrdinaryDescriptorContract ordinaryContract(
            PackProgramPlan plan, PackAdvancedResourcePlan resources) {
        OrdinaryDescriptorContract contract = resources == null ? null : resources.ordinaryContract(plan.name());
        return contract != null ? contract : ordinaryDescriptorContract(plan, Set.of());
    }

    record PreparedPipeline(OrdinaryDescriptorContract ordinary, String vertex, String fragment,
            ProgramBindingLayout layout, ProgramImageBindingManifest imageBindings) {}

    static PreparedPipeline prepareShadow(PackProgramPlan plan, PackAdvancedResourcePlan resources) {
        return prepare(plan, resources, UniformRegistry.Stage.SHADOW, plan.convertedVertex());
    }

    static PreparedPipeline preparePost(PackProgramPlan plan, String fixedVertex,
            PackAdvancedResourcePlan resources) {
        return prepare(plan, resources, UniformRegistry.Stage.POST,
                plan.convertedVertex() == null ? fixedVertex : plan.convertedVertex());
    }

    /** Package-visible preparation seam: builds sources, descriptors, and the
     * image manifest without allocating a native pipeline, so headless gates
     * can exercise the exact descriptor-contract layer runtime installs. */
    static PreparedPipeline prepare(PackProgramPlan plan, PackAdvancedResourcePlan resources,
            UniformRegistry.Stage stage, String vertex) {
        if (plan.interfacePlan() == null || vertex == null || plan.convertedFragment() == null) {
            throw new PreparationFailure("source-projection", "STAGE_SOURCE_MISSING");
        }
        OrdinaryDescriptorContract ordinary = ordinaryContract(plan, resources);
        ProgramBindingLayout layout = resources.bindingLayout(plan.name());
        return new PreparedPipeline(ordinary,
                bindPackShader(vertex, plan.name(), resources, layout,
                        plan.interfacePlan().project("vertex", stage), stage, VK_SHADER_STAGE_VERTEX_BIT),
                bindPackShader(plan.convertedFragment(), plan.name(), resources, layout,
                        plan.interfacePlan().project("fragment", stage), stage, VK_SHADER_STAGE_FRAGMENT_BIT), layout,
                ProgramImageBindingManifest.from(layout, ordinary));
    }

    static Pipeline.Builder prepareBuilder(PreparedPipeline prepared, String program,
            PackAdvancedResourcePlan resources, VertexFormat format,
            java.util.function.Function<net.vulkanmod.vulkan.shader.layout.Uniform.Info,
                    java.util.function.Supplier<net.vulkanmod.vulkan.util.MappedBuffer>> suppliers) {
        validateSelectorLayout(prepared.layout());
        try {
            Pipeline.Builder builder = new MetadataBuilder(format, "pack_" + program);
            builder.setUniformSupplierGetter(suppliers);
            prepared.ordinary().apply(builder);
            addStorageBufferDescriptors(builder, program, resources);
            addStorageImageDescriptors(builder, program, resources);
            addAdvancedSamplerDescriptors(builder, program, resources);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, prepared.vertex());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, prepared.fragment());
            String mismatch = PackAdvancedResourcePlan.layoutMismatch(prepared.layout(),
                    prepared.vertex(), prepared.fragment(), builderDescriptors(builder));
            if (mismatch != null) throw new PreparationFailure("descriptor-contract", mismatch);
            prepared.imageBindings().verify(builderDescriptors(builder));
            return builder;
        } catch (PreparationFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new PreparationFailure("descriptor-contract", "BUILDER_CONFIG_REJECTED", failure);
        }
    }

    static void validateSelectorLayout(ProgramBindingLayout layout) {
        if (layout == null) throw new PreparationFailure("descriptor-contract", "DESCRIPTOR_LAYOUT_MISSING");
        for (var descriptor : layout.ordinaryDescriptors()) {
            if (!descriptor.identity().startsWith("resource:")) continue;
            int end = descriptor.identity().indexOf(':', "resource:".length());
            requireSelector(Integer.parseInt(descriptor.identity().substring("resource:".length(), end)));
        }
        for (var image : layout.storageImages()) requireSelector(image.selectorSlot());
        for (var sampler : layout.advancedSamplers()) requireSelector(sampler.selectorSlot());
    }

    private static void requireSelector(int slot) {
        if (!SelectorNamespace.isAddressable(slot)) {
            throw new PreparationFailure("descriptor-contract", "SELECTOR_SLOT_UNSUPPORTED:" + slot);
        }
    }

    static final class PreparationFailure extends RuntimeException {
        final String phase;
        final String reason;
        PreparationFailure(String phase, String reason) { this(phase, reason, null); }
        PreparationFailure(String phase, String reason, Throwable cause) {
            super(reason, cause);
            this.phase = phase;
            this.reason = reason;
        }
    }


    static GraphicsPipeline createNative(Pipeline.Builder builder, ProgramImageBindingManifest imageBindings) {
        imageBindings.verify(builderDescriptors(builder));
        try {
            return SpirvLocalInitializer.forPackCompile(builder::createGraphicsPipeline);
        } catch (RuntimeException failure) {
            boolean compilation = java.util.Arrays.stream(failure.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals(SPIRVUtils.class.getName())
                            && frame.getMethodName().equals("compileShader"));
            throw new PreparationFailure(compilation ? "shader-compilation" : "native-create",
                    compilation ? "SHADER_COMPILATION_FAILED" : "NATIVE_PIPELINE_CREATION_FAILED", failure);
        }
    }

    private static void logBuildFailure(String program, Exception failure) {
        String phase = failure instanceof PreparationFailure typed ? typed.phase : "source-projection";
        String reason = failure instanceof PreparationFailure typed ? typed.reason
                : failure.getMessage() == null || failure.getMessage().isBlank()
                ? "UNEXPECTED_BUILD_FAILURE" : failure.getMessage();
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        LOGGER.warn("[chimera] pack {}: build failed phase={} exception={} reason={}",
                program, phase, cause.getClass().getName(), reason, cause);
    }
    /** A successfully built pack post pipeline plus the slots its samplers occupy. */
    public record PackPost(
            String name,
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            List<String> samplerNames,
            List<Integer> requiredColorInputs,
            String convertedFragment,
            PostTargetPlan targetPlan,
            ProgramImageBindingManifest imageBindings
    ) {
        public PackPost {
            java.util.Objects.requireNonNull(imageBindings);
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
            GeometryOutputPlan outputPlan,
            ProgramImageBindingManifest imageBindings
    ) {
        public PackTerrain { java.util.Objects.requireNonNull(imageBindings); }

        public boolean requiresDynamicAttachments() {
            return outputPlan != null && outputPlan.requiresMrt();
        }
    }

    /** A successfully built pack shadow pipeline plus its sampler slots. */
    public record PackShadow(GraphicsPipeline pipeline, int[] samplerSlots, String convertedFragment,
            ProgramImageBindingManifest imageBindings) {
        public PackShadow { java.util.Objects.requireNonNull(imageBindings); }
    }

    /** A successfully built world-entity pipeline plus its sampler slots. */
    public record PackEntity(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String convertedVertex,
            String convertedFragment,
            GeometryOutputPlan outputPlan,
            ProgramImageBindingManifest imageBindings
    ) {
        public PackEntity {
            java.util.Objects.requireNonNull(imageBindings);
        }

        /** True when the pipeline writes multiple authored color targets. */
        public boolean requiresDynamicAttachments() {
            return outputPlan != null && outputPlan.executable() && outputPlan.requiresMrt();
        }
    }

    /** A successfully built host particle pipeline plus its sampler slots. */
    public record PackParticle(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String convertedVertex,
            String convertedFragment,
            ProgramImageBindingManifest imageBindings
    ) {
        public PackParticle { java.util.Objects.requireNonNull(imageBindings); }
    }

    /** A sky or cloud pipeline that inherits the host pass state. */
    public record PackSky(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String convertedVertex,
            String convertedFragment,
            VertexFormat vertexFormat,
            ProgramImageBindingManifest imageBindings
    ) {
        public PackSky { java.util.Objects.requireNonNull(imageBindings); }
    }

    public static PackSky buildSky(PackProgramPlan plan) {
        return buildSkyLike(plan, UniformRegistry.Stage.SKY,
                skyVertexFormat(plan),
                "pack_" + (plan == null ? "sky" : plan.name()),
                PackAdvancedResourcePlan.empty(), null);
    }

    public static PackSky buildSky(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildSkyLike(plan, UniformRegistry.Stage.SKY,
                skyVertexFormat(plan),
                "pack_" + (plan == null ? "sky" : plan.name()), advancedResources, storageOwner);
    }

    public static PackSky buildCloud(PackProgramPlan plan) {
        return buildSkyLike(plan, UniformRegistry.Stage.CLOUD,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
                "pack_" + (plan == null ? "clouds" : plan.name()),
                PackAdvancedResourcePlan.empty(), null);
    }

    public static PackSky buildCloud(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildSkyLike(plan, UniformRegistry.Stage.CLOUD,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
                "pack_" + (plan == null ? "clouds" : plan.name()), advancedResources, storageOwner);
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
            String pipelineName, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
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
            OrdinaryDescriptorContract ordinary = ordinaryContract(plan, advancedResources);
            Pipeline.Builder builder = new MetadataBuilder(vertexFormat, pipelineName);
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            ordinary.apply(builder);
            advancedResources = advancedResources == null
                    ? PackAdvancedResourcePlan.empty() : advancedResources;
            ProgramBindingLayout layout = advancedResources.bindingLayout(plan.name());
            String vertexSource = bindPackShader(
                    plan.convertedVertex(), plan.name(), advancedResources, layout,
                    interfacePlan, stage, VK_SHADER_STAGE_VERTEX_BIT);
            String fragmentSource = bindPackShader(
                    plan.convertedFragment(), plan.name(), advancedResources, layout,
                    interfacePlan, stage, VK_SHADER_STAGE_FRAGMENT_BIT);
            if (vertexSource == null || fragmentSource == null) {
                throw new IllegalStateException("advanced sky/cloud image declaration rejected");
            }
            addStorageBufferDescriptors(builder, plan.name(), advancedResources);
            addStorageImageDescriptors(builder, plan.name(), advancedResources);
            addAdvancedSamplerDescriptors(builder, plan.name(), advancedResources);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, fragmentSource);
            verifyAdvancedLayout(builder, plan.name(), advancedResources, vertexSource, fragmentSource, storageOwner);
            ProgramImageBindingManifest imageBindings = ProgramImageBindingManifest.from(layout, ordinary);
            GraphicsPipeline pipeline = createNative(builder, imageBindings);
            for (var buffer : pipeline.getBuffers()) {
                if (!(buffer instanceof PackStorageBufferDescriptor)) {
                    buffer.setUseGlobalBuffer(true);
                }
            }
            return new PackSky(pipeline, slots, vertexSource, fragmentSource,
                    vertexFormat, imageBindings);
        } catch (Exception e) {
            logBuildFailure(pipelineName, e);
            return null;
        }
    }

    /** Builds a post pipeline from the already prepared and translated plan. */
    public static PackPost buildPost(PackProgramPlan plan, String fixedVertexSource) {
        return buildPost(plan, fixedVertexSource, PackAdvancedResourcePlan.empty(), null);
    }

    /** Builds a post pipeline with the session's advanced-resource contract. */
    public static PackPost buildPost(
            PackProgramPlan plan,
            String fixedVertexSource,
            PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
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
                    .map(UniformRegistry.SamplerBinding::name).toList();
            PreparedPipeline prepared = preparePost(plan, fixedVertexSource, advancedResources);
            String vertexSource = prepared.vertex();
            String fragmentSource = prepared.fragment();
            Pipeline.Builder builder = prepareBuilder(prepared, plan.name(), advancedResources,
                    (VertexFormat) CustomVertexFormat.NONE, PackUniformProvider.shared()::supplier);
            verifyAdvancedLayout(builder, plan.name(), advancedResources, vertexSource, fragmentSource, storageOwner);
            GraphicsPipeline pipeline = createNative(builder, prepared.imageBindings());
            return new PackPost(plan.name(), pipeline, slots, samplerNames,
                    colorInputTargets(samplerNames), fragmentSource, plan.targetPlan(), prepared.imageBindings());
        } catch (PreparationFailure failure) {
            throw failure;
        } catch (Exception e) {
            logBuildFailure(plan.name(), e);
            return null;
        }
    }

    /** Builds the extended terrain pipeline from the shared program plan. */
    public static PackTerrain buildTerrain(PackProgramPlan plan, String fixedVertexSource) {
        return buildTerrainLikePlan(plan, fixedVertexSource,
                plan == null ? TerrainMaterialPlan.legacy() : plan.terrainMaterial(), false, 0,
                PackAdvancedResourcePlan.empty(), null);
    }

    /** Builds terrain with the pack-wide union format selected at load time. */
    public static PackTerrain buildTerrain(
            PackProgramPlan plan,
            String fixedVertexSource,
            TerrainMaterialPlan materialPlan
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, false, 0,
                PackAdvancedResourcePlan.empty(), null);
    }

    public static PackTerrain buildTerrain(
            PackProgramPlan plan, String fixedVertexSource, TerrainMaterialPlan materialPlan,
            boolean coverage, int targetFormat
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, coverage, targetFormat,
                PackAdvancedResourcePlan.empty(), null);
    }

    public static PackTerrain buildTerrain(
            PackProgramPlan plan, String fixedVertexSource, TerrainMaterialPlan materialPlan,
            boolean coverage, int targetFormat, PackAdvancedResourcePlan advancedResources,
            PackStorageBufferOwner storageOwner
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, coverage, targetFormat,
                advancedResources, storageOwner);
    }

    /** Builds the translucent terrain pipeline from the shared program plan. */
    public static PackTerrain buildTranslucent(PackProgramPlan plan, String fixedVertexSource) {
        return buildTerrainLikePlan(plan, fixedVertexSource,
                plan == null ? TerrainMaterialPlan.legacy() : plan.terrainMaterial(), false, 0,
                PackAdvancedResourcePlan.empty(), null);
    }

    /** Builds water with the pack-wide union format selected at load time. */
    public static PackTerrain buildTranslucent(
            PackProgramPlan plan,
            String fixedVertexSource,
            TerrainMaterialPlan materialPlan
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, false, 0,
                PackAdvancedResourcePlan.empty(), null);
    }

    public static PackTerrain buildTranslucent(
            PackProgramPlan plan, String fixedVertexSource, TerrainMaterialPlan materialPlan,
            boolean coverage, int targetFormat
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, coverage, targetFormat,
                PackAdvancedResourcePlan.empty(), null);
    }

    public static PackTerrain buildTranslucent(
            PackProgramPlan plan, String fixedVertexSource, TerrainMaterialPlan materialPlan,
            boolean coverage, int targetFormat, PackAdvancedResourcePlan advancedResources,
            PackStorageBufferOwner storageOwner
    ) {
        return buildTerrainLikePlan(plan, fixedVertexSource, materialPlan, coverage, targetFormat,
                advancedResources, storageOwner);
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
        return buildShadow(plan, materialPlan, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackShadow buildShadow(
            PackProgramPlan plan,
            TerrainMaterialPlan materialPlan,
            PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
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
            PreparedPipeline prepared = prepareShadow(plan, advancedResources);
            String vertexSource = prepared.vertex();
            String fragmentSource = prepared.fragment();
            Pipeline.Builder builder = prepareBuilder(prepared, plan.name(), advancedResources,
                    ChimeraVertexFormats.terrainFormat(materialPlan), PackUniformProvider.shared()::supplier);
            verifyAdvancedLayout(builder, plan.name(), advancedResources, vertexSource, fragmentSource, storageOwner);
            MrtPipelineContext.begin(new int[] {37, 37}, deviceMaxColorAttachments());
            GraphicsPipeline pipeline = createNative(builder, prepared.imageBindings());
            MrtPipelineContext.register(pipeline, new int[] {37, 37});
            MrtPipelineContext.end();
            for (var buffer : pipeline.getBuffers()) {
                if (!(buffer instanceof PackStorageBufferDescriptor)) {
                    buffer.setUseGlobalBuffer(true);
                }
            }
            return new PackShadow(pipeline, slots, fragmentSource, prepared.imageBindings());
        } catch (PreparationFailure failure) {
            MrtPipelineContext.end();
            throw failure;
        } catch (Exception e) {
            MrtPipelineContext.end();
            logBuildFailure(plan.name(), e);
            return null;
        }
    }

    /** Builds the guarded world entity pipeline on the append-only entity format. */
    public static PackEntity buildEntity(PackProgramPlan plan) {
        return buildEntityLike(plan, UniformRegistry.Stage.ENTITY, "pack_gbuffers_entities",
                ChimeraVertexFormats.EXTENDED_ENTITY, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackEntity buildEntity(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildEntityLike(plan, UniformRegistry.Stage.ENTITY, "pack_gbuffers_entities",
                ChimeraVertexFormats.EXTENDED_ENTITY, advancedResources, storageOwner);
    }

    /** Builds any world entity-family adapter with the family-specific host contract. */
    public static PackEntity buildEntityFamily(PackProgramPlan plan) {
        return buildEntityFamily(plan, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackEntity buildEntityFamily(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
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
        return buildEntityLike(plan, stage, "pack_" + plan.name(), format, advancedResources, storageOwner);
    }

    /** Builds the block-entity adapter on the same append-only host format. */
    public static PackEntity buildBlock(PackProgramPlan plan) {
        return buildEntityLike(plan, UniformRegistry.Stage.BLOCK, "pack_gbuffers_block",
                ChimeraVertexFormats.EXTENDED_ENTITY, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackEntity buildBlock(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildEntityLike(plan, UniformRegistry.Stage.BLOCK, "pack_gbuffers_block",
                ChimeraVertexFormats.EXTENDED_ENTITY, advancedResources, storageOwner);
    }

    /** Builds the first-person hand adapter on the same append-only host format. */
    public static PackEntity buildHand(PackProgramPlan plan) {
        return buildEntityLike(plan, UniformRegistry.Stage.HAND, "pack_gbuffers_hand",
                ChimeraVertexFormats.EXTENDED_PARTICLE, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackEntity buildHand(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildEntityLike(plan, UniformRegistry.Stage.HAND, "pack_gbuffers_hand",
                ChimeraVertexFormats.EXTENDED_PARTICLE, advancedResources, storageOwner);
    }

    private static PackEntity buildEntityLike(
            PackProgramPlan plan,
            UniformRegistry.Stage stage,
            String pipelineName,
            VertexFormat vertexFormat,
            PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
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
            OrdinaryDescriptorContract ordinary = ordinaryContract(plan, advancedResources);
            Pipeline.Builder builder = new MetadataBuilder(vertexFormat,
            pipelineName);
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            ordinary.apply(builder);
            advancedResources = advancedResources == null
                    ? PackAdvancedResourcePlan.empty() : advancedResources;
            ProgramBindingLayout layout = advancedResources.bindingLayout(plan.name());
            String vertexSource = bindPackShader(
                    plan.convertedVertex(), plan.name(), advancedResources, layout,
                    interfacePlan, stage, VK_SHADER_STAGE_VERTEX_BIT);
            String fragmentSource = bindPackShader(
                    plan.convertedFragment(), plan.name(), advancedResources, layout,
                    interfacePlan, stage, VK_SHADER_STAGE_FRAGMENT_BIT);
            if (vertexSource == null || fragmentSource == null) {
                throw new IllegalStateException("advanced entity image declaration rejected");
            }
            addStorageBufferDescriptors(builder, plan.name(), advancedResources);
            addStorageImageDescriptors(builder, plan.name(), advancedResources);
            addAdvancedSamplerDescriptors(builder, plan.name(), advancedResources);
            GeometryOutputPlan outputPlan = plan.geometryOutputPlan();
            boolean dynamicMrt = outputPlan != null && outputPlan.executable()
                    && outputPlan.requiresMrt();
            if (dynamicMrt) {
                MrtPipelineContext.begin(outputPlan.outputFormatsArray(), deviceMaxColorAttachments(),
                        plan.blendPlan().attachments(outputPlan.targetSlots()));
            }
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, fragmentSource);
            verifyAdvancedLayout(builder, plan.name(), advancedResources, vertexSource, fragmentSource, storageOwner);
            ProgramImageBindingManifest imageBindings = ProgramImageBindingManifest.from(layout, ordinary);
            GraphicsPipeline pipeline;
            try {
                pipeline = createNative(builder, imageBindings);
            } finally {
                if (dynamicMrt) MrtPipelineContext.end();
            }
            if (dynamicMrt) {
                MrtPipelineContext.register(pipeline, outputPlan.outputFormatsArray(),
                        plan.blendPlan().attachments(outputPlan.targetSlots()));
            }
            for (var buffer : pipeline.getBuffers()) {
                if (!(buffer instanceof PackStorageBufferDescriptor)) {
                    buffer.setUseGlobalBuffer(true);
                }
            }
            return new PackEntity(pipeline, slots, vertexSource, fragmentSource,
                    outputPlan, imageBindings);
        } catch (Exception e) {
            logBuildFailure(pipelineName, e);
            return null;
        }
    }

    /** Builds the particle family on the host DefaultVertexFormat.PARTICLE path. */
    public static PackParticle buildParticle(PackProgramPlan plan) {
        return buildParticle(plan, "pack_gbuffers_particles", PackAdvancedResourcePlan.empty(), null);
    }

    public static PackParticle buildParticle(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildParticle(plan, "pack_gbuffers_particles", advancedResources, storageOwner);
    }

    /** Builds an independent opaque or translucent particle-family pipeline. */
    public static PackParticle buildParticleFamily(PackProgramPlan plan) {
        return buildParticleFamily(plan, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackParticle buildParticleFamily(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return plan == null ? null : buildParticle(plan, "pack_" + plan.name(), advancedResources, storageOwner);
    }

    private static PackParticle buildParticle(
            PackProgramPlan plan, String pipelineName, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
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
            OrdinaryDescriptorContract ordinary = ordinaryContract(plan, advancedResources);
            Pipeline.Builder builder = new MetadataBuilder(com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE,
            pipelineName);
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            ordinary.apply(builder);
            advancedResources = advancedResources == null
                    ? PackAdvancedResourcePlan.empty() : advancedResources;
            ProgramBindingLayout layout = advancedResources.bindingLayout(plan.name());
            String vertexSource = bindPackShader(
                    plan.convertedVertex(), plan.name(), advancedResources, layout,
                    interfacePlan, UniformRegistry.Stage.PARTICLE, VK_SHADER_STAGE_VERTEX_BIT);
            String fragmentSource = bindPackShader(
                    plan.convertedFragment(), plan.name(), advancedResources, layout,
                    interfacePlan, UniformRegistry.Stage.PARTICLE, VK_SHADER_STAGE_FRAGMENT_BIT);
            if (vertexSource == null || fragmentSource == null) {
                throw new IllegalStateException("advanced particle image declaration rejected");
            }
            addStorageBufferDescriptors(builder, plan.name(), advancedResources);
            addStorageImageDescriptors(builder, plan.name(), advancedResources);
            addAdvancedSamplerDescriptors(builder, plan.name(), advancedResources);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, fragmentSource);
            verifyAdvancedLayout(builder, plan.name(), advancedResources, vertexSource, fragmentSource, storageOwner);
            ProgramImageBindingManifest imageBindings = ProgramImageBindingManifest.from(layout, ordinary);
            GraphicsPipeline pipeline = createNative(builder, imageBindings);
            for (var buffer : pipeline.getBuffers()) {
                if (!(buffer instanceof PackStorageBufferDescriptor)) {
                    buffer.setUseGlobalBuffer(true);
                }
            }
            return new PackParticle(pipeline, slots, vertexSource, fragmentSource, imageBindings);
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
            Pipeline.Builder builder = new MetadataBuilder((VertexFormat) CustomVertexFormat.NONE, "pack_" + program.name());
            // Pipeline.Builder resolves uniform suppliers during applyConfig.
            // Install the provider first so the generated UBO never falls
            // through to VulkanMod's global uniform maps.
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            builder.applyConfig(config);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, fixedVertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, converted);
            ProgramImageBindingManifest imageBindings = legacyImageBindings(config, interfacePlan);
            GraphicsPipeline pipeline = createNative(builder, imageBindings);

            return new PackPost(program.name(), pipeline, slots, samplerNames,
                    requiredColorInputs, converted, targetPlan, imageBindings);
        } catch (Exception e) {
            logBuildFailure(program.name(), e);
            return null;
        }
    }

    private static PackTerrain buildTerrainLikePlan(
            PackProgramPlan plan,
            String fixedVertexSource,
            TerrainMaterialPlan materialPlan,
            boolean coverage,
            int targetFormat,
            PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
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
            OrdinaryDescriptorContract ordinary = ordinaryContract(plan, advancedResources);
            ProgramBindingLayout layout = advancedResources.bindingLayout(plan.name());
            if (layout == null) {
                throw new IllegalStateException("advanced terrain image declaration rejected");
            }
            String fragment = coverage
                    ? LegacyGlslConverter.withCoverageOutput(plan.convertedFragment())
                    : plan.convertedFragment();
            fragment = bindPackShader(fragment, plan.name(), advancedResources, layout,
                    interfacePlan, stage, VK_SHADER_STAGE_FRAGMENT_BIT);
            if (fragment == null) {
                throw new IllegalStateException("advanced terrain image declaration rejected");
            }
            String vertex = plan.convertedVertex() == null
                    ? fixedVertexSource : plan.convertedVertex();
            vertex = bindPackShader(vertex, plan.name(), advancedResources, layout,
                    interfacePlan, stage, VK_SHADER_STAGE_VERTEX_BIT);
            if (vertex == null) {
                throw new IllegalStateException("advanced terrain vertex image declaration rejected");
            }
            Pipeline.Builder builder = new MetadataBuilder(ChimeraVertexFormats.terrainFormat(materialPlan), "pack_" + plan.name());
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            ordinary.apply(builder);
            addStorageBufferDescriptors(builder, plan.name(), advancedResources);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertex);
            GeometryOutputPlan outputPlan = plan.geometryOutputPlan();
            boolean dynamicMrt = outputPlan != null && outputPlan.requiresMrt();
            if (dynamicMrt) {
                MrtPipelineContext.begin(outputPlan.outputFormatsArray(), deviceMaxColorAttachments(),
                        plan.blendPlan().attachments(outputPlan.targetSlots()));
            } else if (coverage) {
                MrtPipelineContext.beginGeometry(targetFormat,
                        org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT, deviceMaxColorAttachments());
            }
            addStorageImageDescriptors(builder, plan.name(), advancedResources);
            addAdvancedSamplerDescriptors(builder, plan.name(), advancedResources);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, fragment);
            verifyAdvancedLayout(builder, plan.name(), advancedResources, vertex, fragment, storageOwner);
            ProgramImageBindingManifest imageBindings = ProgramImageBindingManifest.from(layout, ordinary);
            GraphicsPipeline pipeline = createNative(builder, imageBindings);
            if (dynamicMrt) {
                MrtPipelineContext.register(pipeline, outputPlan.outputFormatsArray(),
                        plan.blendPlan().attachments(outputPlan.targetSlots()));
            } else if (coverage) {
                MrtPipelineContext.register(pipeline, new int[] {
                        targetFormat, org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT
                });
            }
            if (dynamicMrt || coverage) MrtPipelineContext.end();
            for (var buffer : pipeline.getBuffers()) {
                if (!(buffer instanceof PackStorageBufferDescriptor)) {
                    buffer.setUseGlobalBuffer(true);
                }
            }
            return new PackTerrain(pipeline, slots, fragment, outputPlan, imageBindings);
        } catch (Exception e) {
            MrtPipelineContext.end();
            logBuildFailure(plan.name(), e);
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
            String converted = LegacyGlslConverter.FragmentConversionRequest
                    .of(fragmentSource, prepared ? null : program.fragmentPath(), true, slots)
                    .withTerrainLayout(vertex.layout())
                    .withInterfacePlan(interfacePlan)
                    .convert();
            if (converted == null) {
                throw new IllegalStateException("legacy shadow fragment conversion failed");
            }

            JsonObject json = shadowPipelineJson();
            json.addProperty("fragment", "pack_" + program.name());
            json.add("samplers", samplerArray(slots));

            PipelineConfig config = PipelineConfig.fromJson("pack_" + program.name(), json);
            Pipeline.Builder builder = new MetadataBuilder(ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN, "pack_" + program.name());
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            builder.applyConfig(config);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertex.source());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, converted);
            ProgramImageBindingManifest imageBindings = legacyImageBindings(config, interfacePlan);
            GraphicsPipeline pipeline = createNative(builder, imageBindings);
            for (var buffer : pipeline.getBuffers()) {
                if (!(buffer instanceof PackStorageBufferDescriptor)) {
                    buffer.setUseGlobalBuffer(true);
                }
            }
            return new PackShadow(pipeline, slots, converted, imageBindings);
        } catch (Exception e) {
            logBuildFailure(program.name(), e);
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

    /** Descriptor binding immediately after a program's ordinary samplers. */
    static int storageBufferBindingBase(PackProgramPlan plan) {
        return storageBufferBindingBase(plan, Set.of());
    }

    /** Descriptor binding immediately after the filtered ordinary descriptors. */
    static int storageBufferBindingBase(PackProgramPlan plan, Set<String> advancedSamplerNames) {
        return ordinaryDescriptorContract(plan, advancedSamplerNames).nextBinding();
    }

    static OrdinaryDescriptorContract ordinaryDescriptorContract(
            PackProgramPlan plan, Set<String> advancedSamplerNames
    ) {
        if (plan == null || plan.interfacePlan() == null) {
            return new OrdinaryDescriptorContract(PipelineConfig.builder().build());
        }
        UniformRegistry.Stage stage = stageForProgram(plan.name());
        var iface = plan.interfacePlan().effective(stage);
        int[] slots = ordinarySamplerSlots(iface, stage, advancedSamplerNames);
        for (int slot : slots) requireSelector(slot);
        PipelineConfig config;
        if (stage == UniformRegistry.Stage.SHADOW || stage == UniformRegistry.Stage.GEOMETRY
                || stage == UniformRegistry.Stage.TRANSLUCENT || stage == UniformRegistry.Stage.POST) {
            JsonObject json = stage == UniformRegistry.Stage.SHADOW ? shadowPipelineJson()
                    : stage == UniformRegistry.Stage.POST ? new JsonObject()
                    : ChimeraShaderLoader.loadJson("chimera_terrain.json").deepCopy();
            json.addProperty("fragment", "pack_" + plan.name());
            json.add("samplers", new JsonArray());
            if (stage == UniformRegistry.Stage.POST) {
                json.add("UBOs", uniformUboArray(iface, 0, "all"));
                json.add("PushConstants", new JsonArray());
            } else {
                json.getAsJsonArray("UBOs").addAll(uniformUboArray(iface, 3, "all"));
            }
            PipelineConfig parsed = PipelineConfig.fromJson("pack_" + plan.name(), json);
            int base = parsed.ubs.stream().mapToInt(ub -> ub.binding + 1).max().orElse(0);
            List<PipelineConfig.ImageDescriptorInfo> images = new ArrayList<>();
            for (int index = 0; index < slots.length; index++) images.add(
                    new PipelineConfig.ImageDescriptorInfo(base + index, "sampler2D",
                            "Sampler" + slots[index], slots[index]));
            config = new PipelineConfig(parsed.shaderPaths, parsed.ubs, images, parsed.pushConstantsInfo);
        } else {
            PipelineConfig.Builder builder = PipelineConfig.builder()
                    .addUB(PipelineConfig.UB.builder(UniformRegistry.DYNAMIC_TRANSFORMS_BINDING,
                                    VK_SHADER_STAGE_VERTEX_BIT)
                            .addUniform("mat4", "ModelViewMat").addUniform("vec4", "ColorModulator")
                            .addUniform("vec3", "ModelOffset").addUniform("mat4", "TextureMat").build())
                    .addUB(PipelineConfig.UB.builder(UniformRegistry.PROJECTION_BINDING,
                            VK_SHADER_STAGE_VERTEX_BIT)
                            .addUniform("mat4", "ProjMat").build());
            boolean sky = stage == UniformRegistry.Stage.SKY || stage == UniformRegistry.Stage.CLOUD;
            if (!iface.executableUniforms().isEmpty()) {
                // Entity-family vertex shaders may declare the same pack
                // uniform block the fragment uses (live cameraPosition). The
                // buffer is shared, so the binding must be visible to both
                // stages whenever the converted vertex actually declares it.
                boolean vertexUniforms = plan.convertedVertex() != null && plan.convertedVertex()
                        .contains("layout(binding = 2) uniform ChimeraEntityUniforms");
                int uniformStages = vertexUniforms
                        ? VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT
                        : VK_SHADER_STAGE_FRAGMENT_BIT;
                var uniforms = PipelineConfig.UB.builder(2, uniformStages);
                for (var uniform : iface.executableUniforms()) uniforms.addUniform(uniform.glslType(), uniform.name());
                builder.addUB(uniforms.build());
            } else if (sky) {
                builder.addUB(PipelineConfig.UB.builder(2, VK_SHADER_STAGE_FRAGMENT_BIT).setSize(16).build());
            }
            int base = sky || !iface.executableUniforms().isEmpty() ? 3 : 2;
            for (int index = 0; index < slots.length; index++) builder.addImageDescriptor(
                    base + index, "sampler2D", "Sampler" + slots[index], slots[index]);
            config = builder.build();
        }
        // A guarded family contract owns the host transform blocks at bindings
        // 0 and 1, so those two blocks must carry the host semantic names for
        // the bridge's name-based lookups.
        boolean familyContract = stage != UniformRegistry.Stage.SHADOW
                && stage != UniformRegistry.Stage.GEOMETRY
                && stage != UniformRegistry.Stage.TRANSLUCENT
                && stage != UniformRegistry.Stage.POST;
        return new OrdinaryDescriptorContract(config, ordinaryResources(config, iface), familyContract);
    }

    private static ProgramImageBindingManifest legacyImageBindings(PipelineConfig config,
            UniformRegistry.ProgramInterface iface) {
        return ProgramImageBindingManifest.from(null,
                new OrdinaryDescriptorContract(config, ordinaryResources(config, iface)));
    }

    private static List<PackResourceBinding> ordinaryResources(PipelineConfig config,
            UniformRegistry.ProgramInterface iface) {
        List<PackResourceBinding> resources = new ArrayList<>();
        for (var image : config.imageDescriptors) {
            String name = iface.samplers().stream().filter(value -> value.slot() == image.imageIdx())
                    .map(UniformRegistry.SamplerBinding::name).findFirst().orElse(null);
            if (name == null && iface.stage() != UniformRegistry.Stage.POST) {
                name = image.imageIdx() == 0 ? "texture" : image.imageIdx() == 2 ? "lightmap" : null;
            }
            if (name == null) continue;
            String key = PackResourcePlan.canonicalResource(name);
            PackResourceKind kind = key.startsWith("colortex") || PackResourcePlan.isHostTexture(key)
                    ? PackResourceKind.TARGET : key.startsWith("depthtex") ? PackResourceKind.DEPTH
                    : key.startsWith("shadowtex") ? PackResourceKind.SHADOW_DEPTH
                    : key.startsWith("shadowcolor") ? PackResourceKind.SHADOW_COLOR
                    : key.equals("noisetex") ? PackResourceKind.NOISE
                    : key.equals("normals") || key.equals("specular") ? PackResourceKind.MATERIAL_MAP
                    : PackResourceKind.UNSERVED;
            resources.add(new PackResourceBinding("", name, key, kind, "", image.imageIdx(),
                    "nearest", "repeat", PackResourceStatus.HOST_ALIAS, List.of()));
        }
        return List.copyOf(resources);
    }

    private static int[] ordinarySamplerSlots(
            UniformRegistry.ProgramInterface interfacePlan,
            UniformRegistry.Stage stage,
            Set<String> advancedSamplerNames
    ) {
        if (interfacePlan == null) return new int[0];
        int[] declared = interfacePlan.samplers().stream()
                .filter(value -> advancedSamplerNames == null
                        || !advancedSamplerNames.contains(value.name()))
                .mapToInt(UniformRegistry.SamplerBinding::slot)
                // Multiple Iris names can intentionally alias one host
                // selector. Keep the source aliases, but give the Vulkan
                // layout one descriptor for the shared resource identity.
                .distinct()
                .toArray();
        if (stage == UniformRegistry.Stage.POST) return declared;
        if (stage == UniformRegistry.Stage.SHADOW) return shadowSamplerSlots(declared);
        if (stage == UniformRegistry.Stage.GEOMETRY
                || stage == UniformRegistry.Stage.TRANSLUCENT
                || stage == UniformRegistry.Stage.SKY
                || stage == UniformRegistry.Stage.CLOUD) {
            return interleaveLightmap(declared);
        }
        return entitySamplerSlots(declared);
    }



    private static UniformRegistry.Stage stageForProgram(String name) {
        if (name != null && PostTargetPlan.isPostProgramName(name)) return UniformRegistry.Stage.POST;
        if ("shadow".equals(name)) return UniformRegistry.Stage.SHADOW;
        if ("gbuffers_terrain".equals(name)) return UniformRegistry.Stage.GEOMETRY;
        if ("gbuffers_water".equals(name)) return UniformRegistry.Stage.TRANSLUCENT;
        if (name != null && FamilyAdapterRegistry.isParticleLike(name)) return UniformRegistry.Stage.PARTICLE;
        if (name != null && FamilyAdapterRegistry.isSkyFamily(name)) return UniformRegistry.Stage.SKY;
        if (name != null && FamilyAdapterRegistry.isCloudFamily(name)) return UniformRegistry.Stage.CLOUD;
        if (name != null && FamilyAdapterRegistry.isBlockFamily(name)) return UniformRegistry.Stage.BLOCK;
        if (name != null && FamilyAdapterRegistry.isHandFamily(name)) return UniformRegistry.Stage.HAND;
        return UniformRegistry.Stage.ENTITY;
    }

    private static void addStorageBufferDescriptors(
            Pipeline.Builder builder,
            String program,
            PackAdvancedResourcePlan advancedResources
    ) {
        if (builder == null || advancedResources == null) return;
        for (PackAdvancedResourcePlan.StorageBufferBinding binding
                : advancedResources.bindingLayout(program).storageBuffers()) {
            if (!binding.supported() || binding.rewrittenBinding() < 0
                    || binding.size() <= 0 || binding.size() > Integer.MAX_VALUE) {
                continue;
            }
            builder.addUBO(new PackStorageBufferDescriptor(
                    "PackStorageBuffer" + binding.logicalIndex(),
                    binding.rewrittenBinding(), binding.stageMask(), (int) binding.size()));
        }
    }

    private static void addStorageImageDescriptors(
            Pipeline.Builder builder,
            String program,
            PackAdvancedResourcePlan advancedResources
    ) {
        if (builder == null || advancedResources == null) return;
        int index = 0;
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding
                : advancedResources.bindingLayout(program).storageImages()) {
            builder.addImageDescriptor(new ImageDescriptor(
                    advancedResources.bindingLayout(program).imageBase() + index++, "image3D", "Sampler" + binding.selectorSlot(),
                    binding.selectorSlot(), VK_DESCRIPTOR_TYPE_STORAGE_IMAGE));
        }
    }

    private static void addAdvancedSamplerDescriptors(
            Pipeline.Builder builder,
            String program,
            PackAdvancedResourcePlan advancedResources
    ) {
        if (builder == null || advancedResources == null) return;
        int index = 0;
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding
                : advancedResources.bindingLayout(program).advancedSamplers()) {
            ImageDescriptor descriptor = new ImageDescriptor(
                    advancedResources.bindingLayout(program).samplerBase() + index++,
                    advancedSamplerType(advancedResources, binding),
                    "Sampler" + binding.selectorSlot(), binding.selectorSlot(),
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            // PackStorageImage deliberately stays in GENERAL because the same
            // image may also be written by a graphics or compute stage.
            descriptor.setLayout(VK_IMAGE_LAYOUT_GENERAL);
            builder.addImageDescriptor(descriptor);
        }
    }


    static String bindStorageImages(String source, String program,
            PackAdvancedResourcePlan advancedResources, ProgramBindingLayout layout) {
        return bindStorageImages(source, program, advancedResources, layout, 0);
    }

    static String bindStorageImages(String source, String program,
            PackAdvancedResourcePlan advancedResources, ProgramBindingLayout layout,
            int sourceStage) {
        if (source == null) throw new PreparationFailure("source-projection", "STAGE_SOURCE_MISSING");
        if (layout == null) throw new PreparationFailure("descriptor-contract", "DESCRIPTOR_LAYOUT_MISSING");
        List<PackAdvancedResourcePlan.GraphicsImageBinding> bindings = layout.storageImages();
        List<PackAdvancedResourcePlan.GraphicsImageBinding> readers = layout.advancedSamplers();
        if (bindings.isEmpty() && readers.isEmpty()) {
            if (STORAGE_IMAGE_DECLARATION.matcher(source).find()) {
                throw new PreparationFailure("resource-rewrite", "STORAGE_IMAGE_UNPLANNED");
            }
            return source;
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
                throw new PreparationFailure("resource-rewrite", "STORAGE_IMAGE_UNPLANNED:" + matcher.group(2));
            }
            if (sourceStage != 0 && (binding.stageMask() & sourceStage) != sourceStage) {
                throw new PreparationFailure("resource-rewrite", "RESOURCE_STAGE_MISMATCH:" + binding.symbol());
            }
            PackAdvancedResourcePlan.ImageSpec spec = advancedResources.images()
                    .get(binding.imageName());
            if (spec == null || !spec.supported()) throw new PreparationFailure(
                    "resource-rewrite", "STORAGE_IMAGE_UNSUPPORTED:" + binding.imageName());
            int index = bindings.indexOf(binding);
            String replacement = "layout(" + spec.internalFormat() + ", binding = "
                    + (layout.imageBase() + index) + ") uniform " + binding.glslType()
                    + " " + matcher.group(2) + ";";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            emitted.add(binding.symbol());
        }
        matcher.appendTail(result);
        String rewritten = result.toString();
        Map<String, PackAdvancedResourcePlan.GraphicsImageBinding> readerBySampler = new java.util.TreeMap<>();
        for (PackAdvancedResourcePlan.GraphicsImageBinding binding : readers) {
            readerBySampler.put(binding.sampler(), binding);
        }
        java.util.Set<String> emittedSamplers = new java.util.TreeSet<>();
        Matcher samplerMatcher = STORAGE_SAMPLER_DECLARATION.matcher(rewritten);
        StringBuffer samplerResult = new StringBuffer();
        while (samplerMatcher.find()) {
            PackAdvancedResourcePlan.GraphicsImageBinding binding =
                    readerBySampler.get(samplerMatcher.group(2));
            if (binding == null) {
                samplerMatcher.appendReplacement(samplerResult,
                        Matcher.quoteReplacement(samplerMatcher.group()));
                continue;
            }
            if (sourceStage != 0 && (binding.stageMask() & sourceStage) != sourceStage) {
                throw new PreparationFailure("resource-rewrite", "RESOURCE_STAGE_MISMATCH:" + binding.sampler());
            }
            int index = readers.indexOf(binding);
            String replacement = "layout(binding = "
                    + (layout.samplerBase() + index) + ") uniform "
                    + advancedSamplerType(advancedResources, binding) + " "
                    + binding.sampler() + ";";
            samplerMatcher.appendReplacement(samplerResult, Matcher.quoteReplacement(replacement));
            emittedSamplers.add(binding.sampler());
        }
        samplerMatcher.appendTail(samplerResult);
        rewritten = samplerResult.toString();
        StringBuilder generated = new StringBuilder();
        for (int index = 0; index < bindings.size(); index++) {
            PackAdvancedResourcePlan.GraphicsImageBinding binding = bindings.get(index);
            if (sourceStage != 0 && (binding.stageMask() & sourceStage) != sourceStage) continue;
            if (emitted.contains(binding.symbol())) continue;
            PackAdvancedResourcePlan.ImageSpec spec = advancedResources.images()
                    .get(binding.imageName());
            if (spec == null || !spec.supported()) throw new PreparationFailure(
                    "resource-rewrite", "STORAGE_IMAGE_UNSUPPORTED:" + binding.imageName());
            generated.append("layout(").append(spec.internalFormat())
                    .append(", binding = ").append(layout.imageBase() + index)
                    .append(") uniform ").append(binding.glslType()).append(' ')
                    .append(binding.symbol()).append(";\n");
        }
        for (int index = 0; index < readers.size(); index++) {
            PackAdvancedResourcePlan.GraphicsImageBinding binding = readers.get(index);
            if (sourceStage != 0 && (binding.stageMask() & sourceStage) != sourceStage) continue;
            if (emittedSamplers.contains(binding.sampler())) continue;
            generated.append("layout(binding = ")
                    .append(layout.samplerBase() + index)
                    .append(") uniform ")
                    .append(advancedSamplerType(advancedResources, binding)).append(' ')
                    .append(binding.sampler()).append(";\n");
        }
        if (generated.isEmpty()) return rewritten;
        int versionEnd = rewritten.indexOf('\n');
        int insertAt = versionEnd < 0 ? 0 : versionEnd + 1;
        return rewritten.substring(0, insertAt) + generated + rewritten.substring(insertAt);
    }

    private static String bindPackShader(
            String source,
            String program,
            PackAdvancedResourcePlan advancedResources,
            ProgramBindingLayout layout,
            UniformRegistry.ProgramInterface interfacePlan,
            UniformRegistry.Stage stage,
            int sourceStage
    ) {
        validateSelectorLayout(layout);
        for (var sampler : interfacePlan.samplers()) requireSelector(sampler.slot());
        String rewritten = bindStorageImages(source, program, advancedResources, layout, sourceStage);
        Set<String> advancedNames = advancedSamplerNames(advancedResources, program);
        for (UniformRegistry.SamplerBinding sampler : interfacePlan.samplers()) {
            if (advancedNames.contains(sampler.name())) continue;
            String sourceName = sourceSamplerName(stage, sampler.name());
            PackAdvancedResourcePlan.DescriptorBinding descriptor = layout.ordinaryDescriptors().stream()
                    .filter(value -> value.identity().equals("resource:" + sampler.slot() + ":1"))
                    .findFirst().orElse(null);
            if (descriptor == null || !hasSamplerDeclaration(rewritten, sourceName)) continue;
            rewritten = rewriteSamplerDeclaration(rewritten, sourceName, descriptor.binding());
        }
        return rewritten;
    }

    private static String sourceSamplerName(UniformRegistry.Stage stage, String name) {
        return stage != UniformRegistry.Stage.POST && "texture".equals(name)
                ? "chimeraTexture" : name;
    }

    private static boolean hasSamplerDeclaration(String source, String name) {
        Matcher matcher = SAMPLER_DECLARATION.matcher(source == null ? "" : source);
        while (matcher.find()) {
            if (matcher.group(2).equals(name)) return true;
        }
        return false;
    }

    private static String rewriteSamplerDeclaration(String source, String name, int binding) {
        Matcher matcher = SAMPLER_DECLARATION.matcher(source == null ? "" : source);
        while (matcher.find()) {
            if (!matcher.group(2).equals(name)) continue;
            String replacement = "layout(binding = " + binding + ") uniform "
                    + matcher.group(1) + " " + name + ";";
            return source.substring(0, matcher.start()) + replacement + source.substring(matcher.end());
        }
        return source;
    }

    private static Set<String> advancedSamplerNames(
            PackAdvancedResourcePlan advancedResources, String program
    ) {
        if (advancedResources == null) return Set.of();
        return advancedResources.bindingLayout(program).advancedSamplers().stream()
                .map(PackAdvancedResourcePlan.GraphicsImageBinding::sampler)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static void verifyAdvancedLayout(Pipeline.Builder builder, String program,
            PackAdvancedResourcePlan resources, String vertex, String fragment,
            PackStorageBufferOwner owner) {
        List<PackAdvancedResourcePlan.DescriptorBinding> descriptors = builderDescriptors(builder);
        String mismatch = owner == null ? PackAdvancedResourcePlan.layoutMismatch(
                resources.bindingLayout(program), vertex, fragment, descriptors)
                : owner.verifyLayout(program, vertex, fragment, descriptors);
        if (mismatch != null) throw new PreparationFailure("descriptor-contract", mismatch);
    }

    static List<PackAdvancedResourcePlan.DescriptorBinding> builderDescriptors(Pipeline.Builder builder) {
        List<PackAdvancedResourcePlan.DescriptorBinding> descriptors = new ArrayList<>();
        for (UBO descriptor : builder.getUBOs()) descriptors.add(new PackAdvancedResourcePlan.DescriptorBinding(
                descriptor.name, 0, descriptor.getBinding(), descriptor.getType(), descriptor.getStages(),
                nativeDescriptorIdentity(descriptor.name, descriptor.getType(), descriptor.getBinding())));
        for (ImageDescriptor descriptor : ((ChimeraPipelineBuilderAccessor) (Object) builder)
                .chimera$imageDescriptors()) descriptors.add(new PackAdvancedResourcePlan.DescriptorBinding(
                descriptor.name, 0, descriptor.getBinding(), descriptor.getType(), descriptor.getStages(),
                nativeDescriptorIdentity(descriptor.name, descriptor.getType(), descriptor.getBinding())));
        return List.copyOf(descriptors);
    }

    private static String nativeDescriptorIdentity(String name, int type, int binding) {
        Matcher sampler = Pattern.compile("Sampler(\\d+)").matcher(name == null ? "" : name);
        if (sampler.matches() && (type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                || type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)) {
            return "resource:" + sampler.group(1) + ":" + type;
        }
        if (type == 9 && name != null && name.startsWith("PackStorageBuffer")) {
            return "storage:" + name.substring("PackStorageBuffer".length());
        }
        if (type == 8) return "ubo:" + binding;
        return name == null ? "" : name;
    }

    private static String advancedSamplerType(
            PackAdvancedResourcePlan advancedResources,
            PackAdvancedResourcePlan.GraphicsImageBinding binding
    ) {
        PackAdvancedResourcePlan.ImageSpec spec = advancedResources.images()
                .get(binding.imageName());
        if (spec != null && (spec.internalFormat().equalsIgnoreCase("r8ui")
                || spec.internalFormat().equalsIgnoreCase("r16ui"))) {
            return "usampler3D";
        }
        return "sampler3D";
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
