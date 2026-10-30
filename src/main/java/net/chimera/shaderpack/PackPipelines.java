package net.chimera.shaderpack;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.mixin.ChimeraDeviceAccessor;
import net.chimera.render.PackStorageBufferOwner;
import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.shader.HostPipelineLayout;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.shaderpack.PackAdvancedResourcePlan.ProgramBindingLayout;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_GEOMETRY_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/**
 * Builds GraphicsPipelines from pack programs: a {@link PackDescriptorLayout}
 * (samplers take the bindings after the uniform blocks, in slot order) applied
 * through {@link HostPipelineLayout},
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
    /**
     * An image declaration's qualifiers, a layout among them in any order (GLSL 4.20): RenderPearl
     * writes {@code uniform layout(rg8) restrict writeonly image2D edge}.
     */
    static final String IMAGE_QUALIFIERS =
            "(?:layout\\s*\\([^)]*\\)\\s*|(?:uniform|writeonly|readonly|coherent|volatile|restrict)\\s+)*";
    /** A sampler declaration's {@code uniform}, with a layout before or after it. */
    static final String UNIFORM_QUALIFIERS = "(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(?:layout\\s*\\([^)]*\\)\\s*)?";
    static final Pattern STORAGE_IMAGE_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*" + IMAGE_QUALIFIERS
                    + "(u?i?image(?:1D|2D|3D)(?:Array)?)\\s+([A-Za-z_]\\w*)\\s*;");
    static final Pattern STORAGE_SAMPLER_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*" + UNIFORM_QUALIFIERS
                    + "([iu]?sampler(?:1D|2D|3D))\\s+([A-Za-z_]\\w*)\\s*;");

    private PackPipelines() {}

    private static OrdinaryDescriptorContract ordinaryContract(
            PackProgramPlan plan, PackAdvancedResourcePlan resources) {
        OrdinaryDescriptorContract contract = resources == null ? null : resources.ordinaryContract(plan.name());
        return contract != null ? contract : PackDescriptorContracts.ordinaryDescriptorContract(plan, Set.of());
    }

    /** A program's bound stage sources and descriptors; {@code geometry} is null without a geometry stage. */
    record PreparedPipeline(OrdinaryDescriptorContract ordinary, String vertex, String geometry, String fragment,
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
        return prepare(plan, resources, stage, vertex, plan.convertedFragment());
    }

    /** The same preparation for a layout variant, whose fragment can differ from the plan's. */
    static PreparedPipeline prepare(PackProgramPlan plan, PackAdvancedResourcePlan resources,
            UniformRegistry.Stage stage, String vertex, String fragment) {
        if (plan.interfacePlan() == null || vertex == null || fragment == null) {
            throw new PreparationFailure("source-projection", "STAGE_SOURCE_MISSING");
        }
        OrdinaryDescriptorContract ordinary = ordinaryContract(plan, resources);
        ProgramBindingLayout layout = resources.bindingLayout(plan.name());
        String geometry = plan.convertedGeometry() == null ? null
                : PackDescriptorContracts.bindPackShader(plan.convertedGeometry(), plan.name(), resources, layout,
                        plan.interfacePlan().project("geometry", stage), stage, VK_SHADER_STAGE_GEOMETRY_BIT);
        return new PreparedPipeline(ordinary,
                PackDescriptorContracts.bindPackShader(vertex, plan.name(), resources, layout,
                        plan.interfacePlan().project("vertex", stage), stage, VK_SHADER_STAGE_VERTEX_BIT),
                geometry,
                PackDescriptorContracts.bindPackShader(fragment, plan.name(), resources, layout,
                        plan.interfacePlan().project("fragment", stage), stage, VK_SHADER_STAGE_FRAGMENT_BIT), layout,
                ProgramImageBindingManifest.from(layout, ordinary));
    }

    /**
     * The whole descriptor set of a prepared program, checked against its shaders, its binding plan
     * and its image manifest before any native object exists.
     */
    static PackDescriptorLayout prepareLayout(PreparedPipeline prepared, String program,
            PackAdvancedResourcePlan resources) {
        validateSelectorLayout(prepared.layout());
        PackDescriptorLayout layout = PackDescriptorContracts.withAdvancedDescriptors(
                prepared.ordinary().layout(), program, resources);
        String mismatch = PackAdvancedResourcePlan.layoutMismatch(prepared.layout(),
                prepared.vertex(), prepared.geometry(), prepared.fragment(), layout.descriptorBindings());
        if (mismatch != null) throw new PreparationFailure("descriptor-contract", mismatch);
        prepared.imageBindings().verify(layout.descriptorBindings());
        return layout;
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

    /** Compiles a pack program's stages and creates its pipeline; pack uniforms come from the frame state. */
    static GraphicsPipeline createNative(String name, VertexFormat format, PackDescriptorLayout layout,
            String vertex, String fragment, ProgramImageBindingManifest imageBindings) {
        return createNative(name, format, layout, vertex, null, fragment, imageBindings);
    }

    /** The same with a geometry stage between the vertex and fragment stages, when {@code geometry} is not null. */
    static GraphicsPipeline createNative(String name, VertexFormat format, PackDescriptorLayout layout,
            String vertex, String geometry, String fragment, ProgramImageBindingManifest imageBindings) {
        imageBindings.verify(layout.descriptorBindings());
        try {
            return HostPipelineLayout.create(name, format, layout, vertex, geometry, fragment,
                    PackUniformProvider.shared()::supplier, true);
        } catch (HostPipelineLayout.LayoutFailure failure) {
            throw new PreparationFailure("descriptor-contract", "BUILDER_CONFIG_REJECTED", failure);
        } catch (HostPipelineLayout.ShaderCompilationFailure failure) {
            throw new PreparationFailure("shader-compilation", "SHADER_COMPILATION_FAILED", failure);
        } catch (RuntimeException failure) {
            throw new PreparationFailure("native-create", "NATIVE_PIPELINE_CREATION_FAILED", failure);
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
    /**
     * A successfully built pack post pipeline plus the slots its samplers occupy, the frame
     * resources they read (PackResourcePlan.frameResource), and the blend its pass draws with
     * (PackBlendPlan.forFullscreenPass).
     */
    public record PackPost(
            String name,
            GraphicsPipeline pipeline,
            int[] samplerSlots,
            List<String> samplerNames,
            List<String> frameInputs,
            String convertedFragment,
            PostTargetPlan targetPlan,
            ProgramImageBindingManifest imageBindings,
            PackBlendPlan blendPlan
    ) {
        public PackPost {
            Objects.requireNonNull(imageBindings);
            blendPlan = blendPlan == null ? PackBlendPlan.empty() : blendPlan;
            samplerNames = samplerNames == null ? List.of() : List.copyOf(samplerNames);
            frameInputs = frameInputs == null ? List.of() : frameInputs.stream().distinct().sorted().toList();
        }

        /** The colour targets the pass reads from the frame, ascending. */
        public List<Integer> requiredColorInputs() {
            return colorInputTargets(frameInputs);
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
        public PackHand { variants = Collections.unmodifiableMap(new LinkedHashMap<>(variants)); }
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
        public PackSkyFamily { variants = Collections.unmodifiableMap(new LinkedHashMap<>(variants)); }
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
        return buildCloud(plan, PackAdvancedResourcePlan.empty(), null);
    }

    /** The cloud program over vanilla's cloud mesh. */
    public static PackSky buildCloud(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildSkyLike(plan, UniformRegistry.Stage.CLOUD,
                DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
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
        if (!buildsGeometry(plan, stage, convertedVertex)) {
            return null;
        }
        try {
            UniformRegistry.ProgramInterface interfacePlan = plan.interfacePlan().effective(stage);
            int[] declaredSlots = interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot).toArray();
            int[] slots = stage.shadowPass() ? shadowSamplerSlots(declaredSlots) : entitySamplerSlots(declaredSlots);
            OrdinaryDescriptorContract ordinary = ordinaryContract(plan, advancedResources);
            advancedResources = advancedResources == null
                    ? PackAdvancedResourcePlan.empty() : advancedResources;
            ProgramBindingLayout layout = advancedResources.bindingLayout(plan.name());
            String vertexSource = PackDescriptorContracts.bindPackShader(
                    convertedVertex, plan.name(), advancedResources, layout,
                    interfacePlan, stage, VK_SHADER_STAGE_VERTEX_BIT);
            String geometrySource = plan.convertedGeometry() == null ? null : PackDescriptorContracts.bindPackShader(
                    plan.convertedGeometry(), plan.name(), advancedResources, layout, interfacePlan, stage,
                    VK_SHADER_STAGE_GEOMETRY_BIT);
            String fragmentSource = PackDescriptorContracts.bindPackShader(
                    EntityVertexBridge.fragmentForFormat(plan.convertedFragment(), format), plan.name(),
                    advancedResources, layout, interfacePlan, stage, VK_SHADER_STAGE_FRAGMENT_BIT);
            if (vertexSource == null || fragmentSource == null) {
                throw new IllegalStateException("advanced " + stage + " image declaration rejected");
            }
            PackDescriptorLayout descriptors = PackDescriptorContracts.withAdvancedDescriptors(ordinary.layout(),
                    plan.name(), advancedResources);
            PackDescriptorContracts.verifyAdvancedLayout(descriptors, plan.name(), advancedResources, vertexSource,
                    geometrySource, fragmentSource, storageOwner);
            ProgramImageBindingManifest imageBindings = ProgramImageBindingManifest.from(layout, ordinary);
            GeometryOutputPlan outputPlan = stage.shadowPass() ? null : plan.geometryOutputPlan();
            int[] attachmentFormats = stage.shadowPass() ? shadowAttachmentFormats(plan)
                    : outputPlan != null && outputPlan.requiresDynamicAttachments()
                    ? outputPlan.outputFormatsArray() : null;
            PackBlendPlan.Mode[] attachmentBlends = stage.shadowPass()
                    ? plan.blendPlan().shadowAttachments(attachmentFormats.length)
                    : routedBlends(plan.blendPlan(), outputPlan, attachmentFormats);
            GraphicsPipeline pipeline = createRouted(pipelineName, format, descriptors, vertexSource, geometrySource,
                    fragmentSource, imageBindings, attachmentFormats, attachmentBlends, blendFuncOverride(plan, stage));
            return new BuiltGeometry(pipeline, slots, vertexSource, fragmentSource, outputPlan, imageBindings);
        } catch (Exception e) {
            handleBuildFailure(pipelineName, e);
            return null;
        }
    }

    /** Whether an executable plan has both converted stages and an interface planned for {@code stage}. */
    private static boolean buildsGeometry(PackProgramPlan plan, UniformRegistry.Stage stage, String convertedVertex) {
        return plan != null && plan.executable() && plan.convertedVertex() != null && convertedVertex != null
                && plan.convertedFragment() != null && plan.interfacePlan() != null
                && plan.interfacePlan().effective(stage).stage() == stage;
    }

    /**
     * The blend a pipeline with no attachment contract draws with in VulkanMod's render pass, one
     * mode for that pass's single attachment (colortex0): the program's {@code blend.<program>}, or
     * its {@code blend.<program>.colortex0}, as Iris's BlendModeOverride replaces the host blend for
     * every draw of the program. Null keeps the host blend. Kappa's glint (SRC_ALPHA ONE ZERO ONE)
     * otherwise drew with vanilla's SRC_COLOR, ONE, which squares its dim colour.
     */
    static PackBlendPlan.Mode[] hostPassBlends(PackBlendPlan blendPlan) {
        return blendPlan == null ? null : blendPlan.attachments(List.of(0));
    }

    /**
     * The blend modes for a world pipeline's attachments: the authored route's when it takes its own
     * attachments, none for a coverage build's colour-plus-depth pair, and the host pass's otherwise.
     */
    private static PackBlendPlan.Mode[] routedBlends(PackBlendPlan blendPlan, GeometryOutputPlan outputPlan,
                                                     int[] attachmentFormats) {
        if (attachmentFormats == null) return hostPassBlends(blendPlan);
        return outputPlan != null && outputPlan.requiresDynamicAttachments()
                ? blendPlan.attachments(outputPlan.targetSlots()) : null;
    }

    /**
     * Creates the pipeline under its attachment contract, when it has one, and registers that
     * contract for the variants VulkanMod creates lazily. Without a contract, {@code attachmentBlends}
     * holds the modes for VulkanMod's render pass (hostPassBlends). The program's
     * {@code blend.<program>} directive is registered for its draws' blendFunc.
     */
    private static GraphicsPipeline createRouted(
            String name, VertexFormat format, PackDescriptorLayout layout, String vertex, String geometry,
            String fragment, ProgramImageBindingManifest imageBindings, int[] attachmentFormats,
            PackBlendPlan.Mode[] attachmentBlends, PackBlendPlan.Mode programBlend
    ) {
        if (attachmentFormats != null) {
            MrtPipelineContext.begin(attachmentFormats, deviceMaxColorAttachments(), attachmentBlends);
        }
        GraphicsPipeline pipeline;
        try {
            pipeline = createNative(name, format, layout, vertex, geometry, fragment, imageBindings);
        } finally {
            if (attachmentFormats != null) MrtPipelineContext.end();
        }
        if (attachmentFormats != null) {
            MrtPipelineContext.register(pipeline, attachmentFormats, attachmentBlends);
        } else {
            MrtPipelineContext.registerHostPassBlends(pipeline, attachmentBlends);
        }
        PackUniformProvider.registerProgramBlend(pipeline, programBlend);
        return pipeline;
    }

    /**
     * The blend a program's draws report as blendFunc in place of the host's, or null to report
     * the host blend. SodiumShader applies a {@code blend.<program>} directive before its uniforms
     * update, so an off directive reads zero there. ExtendedShader updates its uniforms first, and
     * only an enabled directive's glBlendFuncSeparate notifies the uniform.
     */
    private static PackBlendPlan.Mode blendFuncOverride(PackProgramPlan plan, UniformRegistry.Stage stage) {
        PackBlendPlan.Mode directive = plan.blendPlan().program();
        if (directive == null) return null;
        boolean sodium = IrisCoreProfile.patchFor(plan.name(), stage, false) == IrisCoreProfile.Patch.SODIUM;
        return sodium || directive.enabled() ? directive : null;
    }

    /** Builds a post pipeline from the already prepared and translated plan. */
    public static PackPost buildPost(PackProgramPlan plan, String fixedVertexSource) {
        return buildPost(plan, fixedVertexSource, PackResourcePlan.empty(), PackAdvancedResourcePlan.empty(), null);
    }

    /** Builds a post pipeline with the session's resource and advanced-resource contracts. */
    public static PackPost buildPost(
            PackProgramPlan plan,
            String fixedVertexSource,
            PackResourcePlan resources,
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
            PackResourcePlan frame = resources == null ? PackResourcePlan.empty() : resources;
            List<String> frameInputs = samplerNames.stream().map(name -> frame.frameResource(plan.name(), name))
                    .filter(Objects::nonNull).toList();
            PreparedPipeline prepared = preparePost(plan, fixedVertexSource, advancedResources);
            String vertexSource = prepared.vertex();
            String fragmentSource = prepared.fragment();
            PackDescriptorLayout layout = prepareLayout(prepared, plan.name(), advancedResources);
            PackDescriptorContracts.verifyAdvancedLayout(layout, plan.name(), advancedResources, vertexSource,
                    fragmentSource, storageOwner);
            GraphicsPipeline pipeline = createNative("pack_" + plan.name(), CustomVertexFormat.NONE, layout,
                    vertexSource, fragmentSource, prepared.imageBindings());
            return new PackPost(plan.name(), pipeline, slots, samplerNames, frameInputs,
                    fragmentSource, plan.targetPlan(), prepared.imageBindings(), plan.blendPlan());
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
            PackDescriptorLayout layout = prepareLayout(prepared, plan.name(), advancedResources);
            PackDescriptorContracts.verifyAdvancedLayout(layout, plan.name(), advancedResources, vertexSource,
                    prepared.geometry(), fragmentSource, storageOwner);
            int[] attachmentFormats = shadowAttachmentFormats(plan);
            GraphicsPipeline pipeline = createRouted("pack_" + plan.name(),
                    ChimeraVertexFormats.terrainFormat(materialPlan), layout, vertexSource, prepared.geometry(),
                    fragmentSource, prepared.imageBindings(), attachmentFormats,
                    plan.blendPlan().shadowAttachments(attachmentFormats.length),
                    blendFuncOverride(plan, UniformRegistry.Stage.SHADOW));
            return new PackShadow(pipeline, slots, fragmentSource, prepared.imageBindings());
        } catch (Exception e) {
            handleBuildFailure(plan.name(), e);
            return null;
        }
    }

    /**
     * ChimeraShadowMap's colour attachments in the pack's shadowcolor formats (PackProgramPlan);
     * a plan made without pack settings gets Iris's default, two RGBA8 targets.
     */
    private static int[] shadowAttachmentFormats(PackProgramPlan plan) {
        List<Integer> formats = plan.shadowAttachmentFormats();
        if (formats.isEmpty()) return new int[] {37, 37};
        return formats.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Builds shadow_entities, or shadow_block, on EXTENDED_ENTITY for the shadow pass's caster
     * lanes (Iris SHADOW_ENTITIES_CUTOUT and SHADOW_BLOCK).
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
                    fragmentSource, sourcePath, interfacePlan, targetPlan);
            if (converted == null) {
                throw new IllegalStateException("legacy GLSL conversion failed");
            }

            int[] slots = interfacePlan.samplers().stream()
                    .mapToInt(UniformRegistry.SamplerBinding::slot)
                    .toArray();
            List<String> samplerNames = interfacePlan.samplers().stream()
                    .map(UniformRegistry.SamplerBinding::name)
                    .toList();
            List<String> frameInputs = samplerNames.stream().map(PackResourcePlan::canonicalResource).toList();
            // One fragment block for the pack's uniforms at binding 0, its samplers after it.
            var blocks = PackDescriptorContracts.packUniformBlock(interfacePlan, 0, VK_SHADER_STAGE_FRAGMENT_BIT);
            PackDescriptorLayout layout = new PackDescriptorLayout(blocks,
                    PackDescriptorContracts.samplerImages(blocks.size(), slots),
                    PackDescriptorLayout.EMPTY_PUSH_CONSTANTS);
            ProgramImageBindingManifest imageBindings = PackDescriptorContracts.legacyImageBindings(layout,
                    interfacePlan);
            GraphicsPipeline pipeline = createNative("pack_" + program.name(), CustomVertexFormat.NONE, layout,
                    fixedVertexSource, converted, imageBindings);

            return new PackPost(program.name(), pipeline, slots, samplerNames,
                    frameInputs, converted, targetPlan, imageBindings, PackBlendPlan.empty());
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
            PackDescriptorLayout descriptors = PackDescriptorContracts.withAdvancedDescriptors(ordinary.layout(),
                    plan.name(), advancedResources);
            PackDescriptorContracts.verifyAdvancedLayout(descriptors, plan.name(), advancedResources, vertex, fragment,
                    storageOwner);
            ProgramImageBindingManifest imageBindings = ProgramImageBindingManifest.from(layout, ordinary);
            // An authored route takes its own attachments; otherwise a coverage build writes the
            // pack colour plus a coverage depth.
            GeometryOutputPlan outputPlan = plan.geometryOutputPlan();
            boolean dynamicAttachments = outputPlan != null && outputPlan.requiresDynamicAttachments();
            int[] attachmentFormats = dynamicAttachments ? outputPlan.outputFormatsArray()
                    : coverage ? new int[] {targetFormat, VK10.VK_FORMAT_R32_SFLOAT} : null;
            PackBlendPlan.Mode[] attachmentBlends = routedBlends(plan.blendPlan(), outputPlan, attachmentFormats);
            GraphicsPipeline pipeline = createRouted("pack_" + plan.name(),
                    ChimeraVertexFormats.terrainFormat(materialPlan), descriptors, vertex, null, fragment,
                    imageBindings, attachmentFormats, attachmentBlends, blendFuncOverride(plan, stage));
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

            PackDescriptorLayout host = shadowLayout();
            PackDescriptorLayout layout = host.withImages(
                    PackDescriptorContracts.samplerImages(host.bindingAfterBlocks(), slots));
            ProgramImageBindingManifest imageBindings = PackDescriptorContracts.legacyImageBindings(layout,
                    interfacePlan);
            GraphicsPipeline pipeline = createNative("pack_" + program.name(),
                    ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN, layout, vertex.source(), converted,
                    imageBindings);
            return new PackShadow(pipeline, slots, converted, imageBindings);
        } catch (Exception e) {
            handleBuildFailure(program.name(), e);
            return null;
        }
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

    /** The fixed shadow pipeline's layout: its terrain blocks and push constants, with its own samplers. */
    static PackDescriptorLayout shadowLayout() {
        return PackDescriptorLayout.fromJson(ChimeraShaderLoader.loadJson("chimera_shadow.json"));
    }

    /** The colour targets a post pass's frame inputs name, legacy aliases included (gaux3 is 6), ascending. */
    static List<Integer> colorInputTargets(List<String> frameInputs) {
        return frameInputs.stream().map(PackResourcePlan::targetIndex).filter(Objects::nonNull)
                .filter(target -> target <= PostTargetPlan.MAX_TARGET).distinct().sorted().toList();
    }

    private static int[] prepend(int value, int[] values) {
        int[] result = new int[values.length + 1];
        result[0] = value;
        System.arraycopy(values, 0, result, 1, values.length);
        return result;
    }
}
