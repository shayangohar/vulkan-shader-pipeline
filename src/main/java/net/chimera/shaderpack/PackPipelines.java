package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.mixin.ChimeraDeviceAccessor;
import net.chimera.mixin.ChimeraPipelineBuilderAccessor;
import net.chimera.render.PackStorageBufferDescriptor;
import net.chimera.render.PackStorageBufferOwner;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.shaderpack.PackAdvancedResourcePlan.ProgramBindingLayout;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/**
 * Builds GraphicsPipelines from pack programs, mirroring
 * ChimeraPostPipelines.create / ChimeraTerrainPipelines.buildPipeline: a
 * PipelineConfig JSON parsed by PipelineConfig.fromJson (sampler names and
 * order define the descriptor bindings, sequentially after any UBO blocks),
 * vertex stage = a chimera fixed source or the narrow terrain bridge, fragment stage = the converted
 * legacy GLSL. Shader compilation failures return null for per-program
 * fallback; typed descriptor/preparation/native failures abort the install.
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
    static final Pattern STORAGE_IMAGE_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?"
                    + "(?:(?:uniform|writeonly|readonly|coherent|volatile|restrict)\\s+)*"
                    + "(u?i?image3D)\\s+([A-Za-z_]\\w*)\\s*;");
    static final Pattern STORAGE_SAMPLER_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+"
                    + "(u?sampler3D)\\s+([A-Za-z_]\\w*)\\s*;");
    static final Pattern SAMPLER_DECLARATION = Pattern.compile(
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
        return contract != null ? contract : PackDescriptorContracts.ordinaryDescriptorContract(plan, Set.of());
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
                PackDescriptorContracts.bindPackShader(vertex, plan.name(), resources, layout,
                        plan.interfacePlan().project("vertex", stage), stage, VK_SHADER_STAGE_VERTEX_BIT),
                PackDescriptorContracts.bindPackShader(plan.convertedFragment(), plan.name(), resources, layout,
                        plan.interfacePlan().project("fragment", stage), stage, VK_SHADER_STAGE_FRAGMENT_BIT), layout,
                ProgramImageBindingManifest.from(layout, ordinary));
    }

    static Pipeline.Builder prepareBuilder(PreparedPipeline prepared, String program,
            PackAdvancedResourcePlan resources, VertexFormat format,
            Function<Uniform.Info,
                    Supplier<MappedBuffer>> suppliers) {
        validateSelectorLayout(prepared.layout());
        try {
            Pipeline.Builder builder = new MetadataBuilder(format, "pack_" + program);
            builder.setUniformSupplierGetter(suppliers);
            prepared.ordinary().apply(builder);
            PackDescriptorContracts.addStorageBufferDescriptors(builder, program, resources);
            PackDescriptorContracts.addStorageImageDescriptors(builder, program, resources);
            PackDescriptorContracts.addAdvancedSamplerDescriptors(builder, program, resources);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, prepared.vertex());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, prepared.fragment());
            String mismatch = PackAdvancedResourcePlan.layoutMismatch(prepared.layout(),
                    prepared.vertex(), prepared.fragment(), PackDescriptorContracts.builderDescriptors(builder));
            if (mismatch != null) throw new PreparationFailure("descriptor-contract", mismatch);
            prepared.imageBindings().verify(PackDescriptorContracts.builderDescriptors(builder));
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

    static void requireSelector(int slot) {
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
        imageBindings.verify(PackDescriptorContracts.builderDescriptors(builder));
        try {
            return SpirvLocalInitializer.forPackCompile(builder::createGraphicsPipeline);
        } catch (RuntimeException failure) {
            boolean compilation = Arrays.stream(failure.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals(SPIRVUtils.class.getName())
                            && frame.getMethodName().equals("compileShader"));
            throw new PreparationFailure(compilation ? "shader-compilation" : "native-create",
                    compilation ? "SHADER_COMPILATION_FAILED" : "NATIVE_PIPELINE_CREATION_FAILED", failure);
        }
    }

    /** All build families share the same fallback boundary. Contract defects remain fatal. */
    static void handleBuildFailure(String program, Exception failure) {
        if (failure instanceof PreparationFailure typed
                && !(typed.phase.equals("shader-compilation") && typed.reason.equals("SHADER_COMPILATION_FAILED"))) {
            throw typed;
        }
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
            Objects.requireNonNull(imageBindings);
            samplerNames = samplerNames == null ? List.of() : List.copyOf(samplerNames);
            requiredColorInputs = requiredColorInputs == null
                    ? List.of()
                    : requiredColorInputs.stream().distinct().sorted().toList();
        }
    }

    /** A built pipeline whose authored output route may leave the host attachment [0]. */
    public interface RoutedPipeline {
        GraphicsPipeline pipeline();

        GeometryOutputPlan outputPlan();

        /** True when the authored route differs from the host attachment [0]. */
        default boolean requiresDynamicAttachments() {
            return outputPlan() != null && outputPlan().requiresDynamicAttachments();
        }
    }

    /** One authored program built once per native vertex layout it draws. */
    public interface PipelineVariants {
        Map<VertexFormat, ? extends RoutedPipeline> variants();

        default void cleanUp() { variants().values().forEach(value -> value.pipeline().cleanUp()); }
    }

    /** A successfully built pack geometry (terrain) pipeline plus its sampler slots. */
    public record PackTerrain(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String convertedFragment,
            GeometryOutputPlan outputPlan,
            ProgramImageBindingManifest imageBindings
    ) implements RoutedPipeline {
        public PackTerrain { Objects.requireNonNull(imageBindings); }
    }

    /** A successfully built pack shadow pipeline plus its sampler slots. */
    public record PackShadow(GraphicsPipeline pipeline, int[] samplerSlots, String convertedFragment,
            ProgramImageBindingManifest imageBindings) {
        public PackShadow { Objects.requireNonNull(imageBindings); }
    }

    /** A successfully built world-entity pipeline plus its sampler slots. */
    public record PackEntity(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String convertedVertex,
            String convertedFragment,
            GeometryOutputPlan outputPlan,
            ProgramImageBindingManifest imageBindings
    ) implements RoutedPipeline {
        public PackEntity { Objects.requireNonNull(imageBindings); }
    }

    /** One authored hand program owns a pipeline for each native hand mesh shape. */
    public record PackHand(Map<VertexFormat, PackEntity> variants) implements PipelineVariants {
        public PackHand { variants = Map.copyOf(variants); }
        public PackEntity primary() { return variants.get(DefaultVertexFormat.NEW_ENTITY); }
        /** The variant for a native hand mesh shape, or null for any other layout (glint keeps its host draw). */
        public PackEntity forFormat(VertexFormat format) {
            VertexFormat host = ChimeraVertexFormats.handHostFormat(format);
            return host == null ? null : variants.get(host);
        }
    }

    /** A successfully built host particle pipeline plus its sampler slots. */
    public record PackParticle(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String convertedVertex,
            String convertedFragment,
            GeometryOutputPlan outputPlan,
            ProgramImageBindingManifest imageBindings
    ) implements RoutedPipeline {
        public PackParticle { Objects.requireNonNull(imageBindings); }
    }

    /** A sky or cloud pipeline that inherits the host pass state. */
    public record PackSky(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String convertedVertex,
            String convertedFragment,
            VertexFormat vertexFormat,
            GeometryOutputPlan outputPlan,
            ProgramImageBindingManifest imageBindings
    ) implements RoutedPipeline {
        public PackSky { Objects.requireNonNull(imageBindings); }
    }

    public record PackSkyFamily(Map<VertexFormat, PackSky> variants) implements PipelineVariants {
        public PackSkyFamily { variants = Map.copyOf(variants); }
        public PackSky forFormat(VertexFormat format) { return variants.get(format); }
    }

    public static PackSkyFamily buildSky(PackProgramPlan plan) {
        return buildSky(plan, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackSkyFamily buildSky(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        if (plan == null || !plan.executable()) return null;
        Map<VertexFormat, PackSky> variants = new LinkedHashMap<>();
        boolean complete = false;
        try {
            for (var contract : HostLayoutVertexBridge.SKY_CONTRACTS) {
                PackSky sky = buildSkyLike(plan, UniformRegistry.Stage.SKY, skyVertexFormat(contract),
                        "pack_" + plan.name() + "_" + variants.size(), advancedResources, storageOwner,
                        HostLayoutVertexBridge.skyVertexForContract(plan.convertedVertex(), contract));
                if (sky == null) return null;
                variants.put(sky.vertexFormat(), sky);
            }
            complete = true;
            return new PackSkyFamily(variants);
        } finally {
            if (!complete) variants.values().forEach(value -> value.pipeline().cleanUp());
        }
    }

    public static PackSky buildCloud(PackProgramPlan plan) {
        return buildSkyLike(plan, UniformRegistry.Stage.CLOUD,
                DefaultVertexFormat.POSITION_COLOR,
                "pack_" + (plan == null ? "clouds" : plan.name()),
                PackAdvancedResourcePlan.empty(), null, plan == null ? null : plan.convertedVertex());
    }

    public static PackSky buildCloud(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildSkyLike(plan, UniformRegistry.Stage.CLOUD,
                DefaultVertexFormat.POSITION_COLOR,
                "pack_" + (plan == null ? "clouds" : plan.name()), advancedResources, storageOwner,
                plan == null ? null : plan.convertedVertex());
    }

    public static VertexFormat skyVertexFormat(FamilyAdapterPlan.VertexContract contract) {
        return switch (contract) {
            case SKY_POSITION -> DefaultVertexFormat.POSITION;
            case SKY_POSITION_COLOR -> DefaultVertexFormat.POSITION_COLOR;
            case SKY_POSITION_UV -> DefaultVertexFormat.POSITION_TEX;
            case SKY_POSITION_COLOR_UV ->
                    DefaultVertexFormat.POSITION_TEX_COLOR;
            default -> null;
        };
    }

    private static PackSky buildSkyLike(
            PackProgramPlan plan, UniformRegistry.Stage stage, VertexFormat vertexFormat,
            String pipelineName, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner,
            String convertedVertex
    ) {
        BuiltGeometry built = buildGeometry(plan, stage, pipelineName, vertexFormat, convertedVertex,
                advancedResources, storageOwner);
        return built == null ? null : new PackSky(built.pipeline(), built.samplerSlots(), built.vertexSource(),
                built.fragmentSource(), vertexFormat, built.outputPlan(), built.imageBindings());
    }

    /** What every sky, entity, particle and host-layout pipeline is built into. */
    record BuiltGeometry(
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            String vertexSource,
            String fragmentSource,
            GeometryOutputPlan outputPlan,
            ProgramImageBindingManifest imageBindings
    ) {}

    /**
     * Builds one geometry-family pipeline: the converted shaders bound to the plan's interface
     * for {@code stage}, its advanced resources, and its authored output route. A shadow-lane
     * program renders into the shadow map's colour attachments with blending off (Iris shadow
     * programs: BlendModeOverride.OFF), never the gbuffers. Returns null when the plan cannot
     * serve {@code stage} or the build fails.
     */
    static BuiltGeometry buildGeometry(
            PackProgramPlan plan, UniformRegistry.Stage stage, String pipelineName, VertexFormat format,
            String convertedVertex, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        if (plan == null || !plan.executable() || plan.convertedVertex() == null || convertedVertex == null
                || plan.convertedFragment() == null || plan.interfacePlan() == null
                || plan.interfacePlan().effective(stage).stage() != stage) {
            return null;
        }
        try {
            UniformRegistry.ProgramInterface interfacePlan = plan.interfacePlan().effective(stage);
            int[] declaredSlots = interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot).toArray();
            int[] slots = stage.shadowPass() ? shadowSamplerSlots(declaredSlots) : entitySamplerSlots(declaredSlots);
            OrdinaryDescriptorContract ordinary = ordinaryContract(plan, advancedResources);
            Pipeline.Builder builder = new MetadataBuilder(format, pipelineName);
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            ordinary.apply(builder);
            advancedResources = advancedResources == null
                    ? PackAdvancedResourcePlan.empty() : advancedResources;
            ProgramBindingLayout layout = advancedResources.bindingLayout(plan.name());
            String vertexSource = PackDescriptorContracts.bindPackShader(
                    convertedVertex, plan.name(), advancedResources, layout,
                    interfacePlan, stage, VK_SHADER_STAGE_VERTEX_BIT);
            String fragmentSource = PackDescriptorContracts.bindPackShader(
                    plan.convertedFragment(), plan.name(), advancedResources, layout,
                    interfacePlan, stage, VK_SHADER_STAGE_FRAGMENT_BIT);
            if (vertexSource == null || fragmentSource == null) {
                throw new IllegalStateException("advanced " + stage + " image declaration rejected");
            }
            PackDescriptorContracts.addStorageBufferDescriptors(builder, plan.name(), advancedResources);
            PackDescriptorContracts.addStorageImageDescriptors(builder, plan.name(), advancedResources);
            PackDescriptorContracts.addAdvancedSamplerDescriptors(builder, plan.name(), advancedResources);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, fragmentSource);
            PackDescriptorContracts.verifyAdvancedLayout(builder, plan.name(), advancedResources, vertexSource,
                    fragmentSource, storageOwner);
            ProgramImageBindingManifest imageBindings = ProgramImageBindingManifest.from(layout, ordinary);
            GeometryOutputPlan outputPlan = stage.shadowPass() ? null : plan.geometryOutputPlan();
            int[] attachmentFormats = stage.shadowPass() ? shadowAttachmentFormats()
                    : outputPlan != null && outputPlan.requiresDynamicAttachments()
                    ? outputPlan.outputFormatsArray() : null;
            PackBlendPlan.Mode[] attachmentBlends = stage.shadowPass()
                    ? new PackBlendPlan.Mode[] {PackBlendPlan.Mode.OFF, PackBlendPlan.Mode.OFF}
                    : attachmentFormats == null ? null : plan.blendPlan().attachments(outputPlan.targetSlots());
            GraphicsPipeline pipeline = createRouted(builder, imageBindings, attachmentFormats, attachmentBlends);
            return new BuiltGeometry(pipeline, slots, vertexSource, fragmentSource, outputPlan, imageBindings);
        } catch (Exception e) {
            handleBuildFailure(pipelineName, e);
            return null;
        }
    }

    /**
     * Creates the pipeline under its attachment contract, when it has one, and registers that
     * contract for the variants VulkanMod creates lazily. Pack-owned storage buffers keep their
     * own descriptors; every other uniform buffer is the shared global one.
     */
    private static GraphicsPipeline createRouted(
            Pipeline.Builder builder, ProgramImageBindingManifest imageBindings,
            int[] attachmentFormats, PackBlendPlan.Mode[] attachmentBlends
    ) {
        if (attachmentFormats != null) {
            MrtPipelineContext.begin(attachmentFormats, deviceMaxColorAttachments(), attachmentBlends);
        }
        GraphicsPipeline pipeline;
        try {
            pipeline = createNative(builder, imageBindings);
        } finally {
            if (attachmentFormats != null) MrtPipelineContext.end();
        }
        if (attachmentFormats != null) {
            MrtPipelineContext.register(pipeline, attachmentFormats, attachmentBlends);
        }
        useGlobalUniformBuffers(pipeline);
        return pipeline;
    }

    private static void useGlobalUniformBuffers(GraphicsPipeline pipeline) {
        for (var buffer : pipeline.getBuffers()) {
            if (!(buffer instanceof PackStorageBufferDescriptor)) {
                buffer.setUseGlobalBuffer(true);
            }
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
                    CustomVertexFormat.NONE, PackUniformProvider.shared()::supplier);
            PackDescriptorContracts.verifyAdvancedLayout(builder, plan.name(), advancedResources, vertexSource,
                    fragmentSource, storageOwner);
            GraphicsPipeline pipeline = createNative(builder, prepared.imageBindings());
            return new PackPost(plan.name(), pipeline, slots, samplerNames,
                    colorInputTargets(samplerNames), fragmentSource, plan.targetPlan(), prepared.imageBindings());
        } catch (Exception e) {
            handleBuildFailure(plan.name(), e);
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
            PackDescriptorContracts.verifyAdvancedLayout(builder, plan.name(), advancedResources, vertexSource,
                    fragmentSource, storageOwner);
            GraphicsPipeline pipeline = createRouted(builder, prepared.imageBindings(), shadowAttachmentFormats(),
                    null);
            return new PackShadow(pipeline, slots, fragmentSource, prepared.imageBindings());
        } catch (Exception e) {
            handleBuildFailure(plan.name(), e);
            return null;
        }
    }

    /** ChimeraShadowMap's colour attachments, shadowcolor0 and shadowcolor1: both VK_FORMAT_R8G8B8A8_UNORM. */
    private static int[] shadowAttachmentFormats() {
        return new int[] {37, 37};
    }

    /**
     * Builds shadow_entities on EXTENDED_ENTITY for the shadow pass's entity lane, as Iris draws
     * every entity and block-entity pipeline in the shadow pass with SHADOW_ENTITIES_CUTOUT.
     */
    public static PackEntity buildShadowEntities(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return plan == null ? null
                : PackFamilyPipelines.buildEntityLike(plan, UniformRegistry.Stage.SHADOW_ENTITY, "pack_" + plan.name(),
                ChimeraVertexFormats.EXTENDED_ENTITY, advancedResources, storageOwner);
    }

    /** Builds the guarded world entity pipeline on the append-only entity format. */
    public static PackEntity buildEntity(PackProgramPlan plan) {
        return PackFamilyPipelines.buildEntityLike(plan, UniformRegistry.Stage.ENTITY, "pack_gbuffers_entities",
                ChimeraVertexFormats.EXTENDED_ENTITY, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackEntity buildEntity(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return PackFamilyPipelines.buildEntityLike(plan, UniformRegistry.Stage.ENTITY, "pack_gbuffers_entities",
                ChimeraVertexFormats.EXTENDED_ENTITY, advancedResources, storageOwner);
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
            String converted = FragmentConverter.convertPostFragment(
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
            Pipeline.Builder builder = new MetadataBuilder(CustomVertexFormat.NONE, "pack_" + program.name());
            // Pipeline.Builder resolves uniform suppliers during applyConfig.
            // Install the provider first so the generated UBO never falls
            // through to VulkanMod's global uniform maps.
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            builder.applyConfig(config);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, fixedVertexSource);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, converted);
            ProgramImageBindingManifest imageBindings = PackDescriptorContracts.legacyImageBindings(config,
                    interfacePlan);
            GraphicsPipeline pipeline = createNative(builder, imageBindings);

            return new PackPost(program.name(), pipeline, slots, samplerNames,
                    requiredColorInputs, converted, targetPlan, imageBindings);
        } catch (Exception e) {
            handleBuildFailure(program.name(), e);
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
                    ? FragmentConverter.withCoverageOutput(plan.convertedFragment())
                    : plan.convertedFragment();
            fragment = PackDescriptorContracts.bindPackShader(fragment, plan.name(), advancedResources, layout,
                    interfacePlan, stage, VK_SHADER_STAGE_FRAGMENT_BIT);
            if (fragment == null) {
                throw new IllegalStateException("advanced terrain image declaration rejected");
            }
            String vertex = plan.convertedVertex() == null
                    ? fixedVertexSource : plan.convertedVertex();
            vertex = PackDescriptorContracts.bindPackShader(vertex, plan.name(), advancedResources, layout,
                    interfacePlan, stage, VK_SHADER_STAGE_VERTEX_BIT);
            if (vertex == null) {
                throw new IllegalStateException("advanced terrain vertex image declaration rejected");
            }
            Pipeline.Builder builder = new MetadataBuilder(ChimeraVertexFormats.terrainFormat(materialPlan),
                    "pack_" + plan.name());
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            ordinary.apply(builder);
            PackDescriptorContracts.addStorageBufferDescriptors(builder, plan.name(), advancedResources);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertex);
            PackDescriptorContracts.addStorageImageDescriptors(builder, plan.name(), advancedResources);
            PackDescriptorContracts.addAdvancedSamplerDescriptors(builder, plan.name(), advancedResources);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, fragment);
            PackDescriptorContracts.verifyAdvancedLayout(builder, plan.name(), advancedResources, vertex, fragment,
                    storageOwner);
            ProgramImageBindingManifest imageBindings = ProgramImageBindingManifest.from(layout, ordinary);
            // An authored route takes its own attachments; otherwise a coverage build writes the
            // pack colour plus a coverage depth.
            GeometryOutputPlan outputPlan = plan.geometryOutputPlan();
            boolean dynamicAttachments = outputPlan != null && outputPlan.requiresDynamicAttachments();
            int[] attachmentFormats = dynamicAttachments ? outputPlan.outputFormatsArray()
                    : coverage ? new int[] {targetFormat, VK10.VK_FORMAT_R32_SFLOAT} : null;
            PackBlendPlan.Mode[] attachmentBlends = dynamicAttachments
                    ? plan.blendPlan().attachments(outputPlan.targetSlots()) : null;
            GraphicsPipeline pipeline = createRouted(builder, imageBindings, attachmentFormats, attachmentBlends);
            return new PackTerrain(pipeline, slots, fragment, outputPlan, imageBindings);
        } catch (Exception e) {
            handleBuildFailure(plan.name(), e);
            return null;
        }
    }

    private static int deviceMaxColorAttachments() {
        if (DeviceManager.device == null) return 8;
        return ((ChimeraDeviceAccessor) DeviceManager.device)
                .chimera$properties().limits().maxColorAttachments();
    }

    /** One generated fragment UBO, or an empty array for a program without uniforms. */
    static JsonArray uniformUboArray(UniformRegistry.ProgramInterface interfacePlan) {
        return uniformUboArray(interfacePlan, 0, "fragment");
    }

    static JsonArray uniformUboArray(
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
                    TerrainVertexBridge.convertShadowVertex(
                        vertexSourceText, prepared ? null : program.vertexPath(), fragmentSource,
                        interfacePlan);
            if (vertex == null) {
                throw new IllegalStateException("legacy shadow vertex bridge rejected the source");
            }

            int[] declaredSlots = interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot)
                    .toArray();
            int[] slots = shadowSamplerSlots(declaredSlots);
            String converted = FragmentConverter.FragmentConversionRequest
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
            Pipeline.Builder builder = new MetadataBuilder(ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN,
                    "pack_" + program.name());
            builder.setUniformSupplierGetter(PackUniformProvider.shared()::supplier);
            builder.applyConfig(config);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertex.source());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, converted);
            ProgramImageBindingManifest imageBindings = PackDescriptorContracts.legacyImageBindings(config,
                    interfacePlan);
            GraphicsPipeline pipeline = createNative(builder, imageBindings);
            useGlobalUniformBuffers(pipeline);
            return new PackShadow(pipeline, slots, converted, imageBindings);
        } catch (Exception e) {
            handleBuildFailure(program.name(), e);
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
        int[] withoutLightmap = Arrays.stream(slots)
                .filter(slot -> slot != 2)
                .distinct()
                .toArray();
        boolean hasAtlas = Arrays.stream(withoutLightmap).anyMatch(slot -> slot == 0);
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
        boolean hasLightmap = Arrays.stream(declaredSlots).anyMatch(slot -> slot == 2);
        TreeSet<Integer> slots = new TreeSet<>();
        for (int slot : declaredSlots) slots.add(slot);
        if (hasLightmap) slots.add(0);
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Preserve the registry slots used by the host entity draw. */
    public static int[] entitySamplerSlots(int[] declaredSlots) {
        return Arrays.stream(declaredSlots).distinct().sorted().toArray();
    }

    /** Return an isolated copy so pack sampler and fragment fields cannot alter the host config. */
    static JsonObject shadowPipelineJson() {
        return ChimeraShaderLoader.loadJson("chimera_shadow.json").deepCopy();
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
