package net.chimera.render.shader;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.mixin.ChimeraPipelineBuilderAccessor;
import net.chimera.render.ChimeraDevice;
import net.chimera.render.PackImageDescriptor;
import net.chimera.render.PackStorageBufferDescriptor;
import net.chimera.render.PackStorageViewDescriptor;
import net.chimera.shaderpack.BetweenStages;
import net.chimera.shaderpack.PackDescriptorLayout;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PackWorkers;
import net.chimera.shaderpack.SpirvLocalInitializer;
import net.chimera.shaderpack.SpirvOutputWrites;
import net.chimera.shaderpack.SpirvTrigPrecision;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;

/**
 * Builds the host renderer's pipelines from a Chimera {@link PackDescriptorLayout}: the one place a
 * pipeline layout meets VulkanMod's pipeline builder. Each layout entry becomes one host
 * descriptor at the same binding, so what the shaders were checked against is what the host binds.
 *
 * <p>A uniform field the host knows by name (its global uniform maps: ModelViewMat, FogColor...)
 * keeps the host's value; every other field takes its value from the pipeline's
 * {@link UniformSource}, and a field neither supplies fails the build, as VulkanMod's own builder
 * does.</p>
 */
public final class HostPipelineLayout {
    private HostPipelineLayout() {}

    /** Values for the uniform fields the host does not supply. */
    @FunctionalInterface
    public interface UniformSource {
        /** The value buffer for field {@code name} of type {@code type}, or null when this source has none. */
        Supplier<MappedBuffer> supplier(String name, String type);

        /** A source with no values: every field must be the host's. */
        UniformSource HOST_ONLY = (name, type) -> null;
    }

    /** A pipeline stage's GLSL that did not compile, with the source shaderc's message counts lines of. */
    public static final class ShaderCompilationFailure extends RuntimeException {
        private final transient String source;

        ShaderCompilationFailure(RuntimeException cause, String source) {
            super(cause.getMessage(), cause);
            this.source = source;
        }

        /** The stage's GLSL as compiled: the translation, which the message's line numbers count. */
        public String source() {
            return this.source;
        }
    }

    /** A layout the host cannot bind: a binding gap, or a uniform field nothing supplies. */
    public static final class LayoutFailure extends RuntimeException {
        LayoutFailure(String message) {
            super(message);
        }
    }

    /**
     * Compiles the two stages and creates a pipeline named {@code name} with {@code layout}. Pack
     * modules ({@code packModules}) get zero-initialized locals and GL-order trigonometric arithmetic
     * (SpirvLocalInitializer, SpirvTrigPrecision); Chimera's own shaders keep their bytes. A pack
     * pipeline records the output locations its fragment stage writes (SpirvOutputWrites).
     *
     * @throws LayoutFailure when the host cannot bind the layout
     * @throws ShaderCompilationFailure when a stage does not compile
     */
    public static GraphicsPipeline create(String name, VertexFormat format, PackDescriptorLayout layout,
                                          String vertexSource, String fragmentSource, UniformSource uniforms,
                                          boolean packModules) {
        return create(name, format, layout, vertexSource, BetweenStages.NONE, fragmentSource, uniforms, packModules);
    }

    /**
     * As {@link #create(String, VertexFormat, PackDescriptorLayout, String, String, UniformSource, boolean)},
     * with the stages {@code between} the vertex and fragment stages: VulkanMod's tessellation
     * stages, drawn as patches of BetweenStages.PATCH_VERTICES, and a geometry stage
     * (GeometryStageModules).
     *
     * @throws LayoutFailure when the host cannot bind the layout, or the device lacks a stage's feature
     * @throws ShaderCompilationFailure when a stage does not compile
     */
    public static GraphicsPipeline create(String name, VertexFormat format, PackDescriptorLayout layout,
                                          String vertexSource, BetweenStages between, String fragmentSource,
                                          UniformSource uniforms, boolean packModules) {
        requireDeviceStages(name, between);
        Pipeline.Builder builder = new Pipeline.Builder(format, name);
        builder.setUniforms(uniformBuffers(layout, uniforms), images(layout, packModules));
        ((ChimeraPipelineBuilderAccessor) builder).chimera$setPushConstants(pushConstants(layout, uniforms));
        List<Stage> stages = new ArrayList<>(5);
        stages.add(new Stage(name + ".vsh", vertexSource, Shaderc.shaderc_glsl_vertex_shader));
        between.present().forEach(stage -> stages.add(betweenStage(name, stage)));
        stages.add(new Stage(name + ".fsh", fragmentSource, Shaderc.shaderc_glsl_fragment_shader));
        // The stages compile apart (PackWorkers); the pipeline is created here, on the calling thread.
        List<SPIRVUtils.SPIRV> compiled = PackWorkers.map(stages, stage -> compile(stage, packModules));
        SPIRVUtils.SPIRV fragment = compiled.get(compiled.size() - 1);
        builder.setSPIRVs(compiled.get(0), fragment);
        if (between.tessellated()) {
            builder.setTessellationSPIRVs(compiled.get(1), compiled.get(2), BetweenStages.PATCH_VERTICES);
        }
        GraphicsPipeline pipeline = between.geometry() == null ? builder.createGraphicsPipeline()
                : GeometryStageModules.construct(compiled.get(compiled.size() - 2).bytecode(),
                        builder::createGraphicsPipeline);
        if (packModules) {
            MrtPipelineContext.recordWrittenOutputs(pipeline, SpirvOutputWrites.writtenLocations(fragment.bytecode()));
        }
        return pipeline;
    }

    /** One stage of a pipeline: its file name for shaderc's reports, its source, and its shaderc kind. */
    private record Stage(String name, String source, int kind) {}

    /** A stage between the vertex and fragment stages, as shaderc compiles it. */
    private static Stage betweenStage(String name, BetweenStages.Stage stage) {
        return switch (stage.name()) {
            case "tess_control" -> new Stage(name + ".tcs", stage.source(), Shaderc.shaderc_glsl_tess_control_shader);
            case "tess_evaluation" -> new Stage(name + ".tes", stage.source(),
                    Shaderc.shaderc_glsl_tess_evaluation_shader);
            default -> new Stage(name + ".gsh", stage.source(), Shaderc.shaderc_glsl_geometry_shader);
        };
    }

    /** The device features the stages {@code between} need: tessellationShader, geometryShader. */
    private static void requireDeviceStages(String name, BetweenStages between) {
        if (between.geometry() != null && !ChimeraDevice.geometryShader()) {
            throw new LayoutFailure("the device has no geometryShader for " + name + ".gsh");
        }
        if (between.tessellated() && !DeviceManager.isTessellationEnabled()) {
            throw new LayoutFailure("the device has no tessellationShader for " + name + ".tcs");
        }
    }

    /**
     * Builds one of Chimera's own pipelines, named {@code name}, from a config in
     * assets/chimera/shaders: the config's layout and shader paths, the vertex source passed
     * through {@code vertexSource}. Every uniform field is the host's.
     */
    public static GraphicsPipeline create(String name, String configName, VertexFormat format,
                                          UnaryOperator<String> vertexSource) {
        JsonObject json = ChimeraShaderLoader.loadJson(configName + ".json");
        return create(name, format, PackDescriptorLayout.fromJson(json),
                vertexSource.apply(ChimeraShaderLoader.loadSource(json.get("vertex").getAsString() + ".vsh")),
                ChimeraShaderLoader.loadSource(json.get("fragment").getAsString() + ".fsh"),
                UniformSource.HOST_ONLY, false);
    }

    /**
     * The host buffer descriptors of a layout: its uniform blocks, then its storage buffers. The host
     * sizes a descriptor set by its descriptor count and places each at its binding, so the
     * bindings must run from 0 without a gap.
     */
    public static List<UBO> uniformBuffers(PackDescriptorLayout layout, UniformSource uniforms) {
        requireDenseBindings(layout);
        List<UBO> buffers = new ArrayList<>();
        for (PackDescriptorLayout.UniformBlock block : layout.uniformBlocks()) {
            if (block.fields().isEmpty()) {
                buffers.add(new UBO(block.binding(), block.stages(), block.size(), null));
                continue;
            }
            AlignedStruct.Builder struct = new AlignedStruct.Builder();
            for (PackDescriptorLayout.Field field : block.fields()) {
                struct.addUniformInfo(hostType(field.type()), field.name());
            }
            UBO buffer = struct.buildUBO(block.binding(), block.stages());
            supply(buffer.getUniforms(), block.fields(), uniforms);
            buffers.add(buffer);
        }
        for (PackDescriptorLayout.StorageBuffer storage : layout.storageBuffers()) {
            buffers.add(new PackStorageBufferDescriptor(storage.binding(), storage.stages(), storage.size(),
                    storage.logicalIndex()));
        }
        return buffers;
    }

    /**
     * The image descriptors of a layout. A pack program's image other than the albedo and lightmap,
     * which VulkanMod binds from the game's units, is Chimera's to bind (PackImageDescriptor);
     * Chimera's own shaders read the host's units.
     */
    static List<ImageDescriptor> images(PackDescriptorLayout layout, boolean packModules) {
        List<ImageDescriptor> images = new ArrayList<>();
        for (PackDescriptorLayout.ImageSlot slot : layout.images()) {
            boolean storage = slot.access() == PackDescriptorLayout.ImageAccess.STORAGE;
            ImageDescriptor image;
            if (slot.viewFormat() != 0) {
                image = new PackStorageViewDescriptor(slot.binding(), slot.type(), slot.name(), slot.textureSlot(),
                        slot.viewFormat());
            } else if (packModules && !PackResourcePlan.HOST_BOUND_SLOTS.contains(slot.textureSlot())) {
                image = new PackImageDescriptor(slot.binding(), slot.type(), slot.name(), slot.textureSlot(), storage);
            } else {
                image = new ImageDescriptor(slot.binding(), slot.type(), slot.name(), slot.textureSlot(), storage);
            }
            // Pack storage images stay in the general layout, as a graphics or compute stage may also write them.
            if (slot.access() == PackDescriptorLayout.ImageAccess.SAMPLED_GENERAL) {
                image.setLayout(VK_IMAGE_LAYOUT_GENERAL);
            }
            images.add(image);
        }
        return images;
    }

    /** The push-constant block, or null when the layout has none or it holds no field. */
    private static PushConstants pushConstants(PackDescriptorLayout layout, UniformSource uniforms) {
        PackDescriptorLayout.UniformBlock block = layout.pushConstants();
        if (block == null || block.fields().isEmpty()) return null;
        AlignedStruct.Builder struct = new AlignedStruct.Builder();
        for (PackDescriptorLayout.Field field : block.fields()) {
            struct.addUniformInfo(hostType(field.type()), field.name());
        }
        PushConstants constants = struct.buildPushConstant();
        supply(constants.getUniforms(), block.fields(), uniforms);
        return constants;
    }

    /** Gives each field the host's value or the source's, as the class comment says. */
    private static void supply(List<Uniform> built, List<PackDescriptorLayout.Field> fields, UniformSource uniforms) {
        for (int index = 0; index < fields.size(); index++) {
            PackDescriptorLayout.Field field = fields.get(index);
            if (hostSupplies(field)) continue;
            Supplier<MappedBuffer> supplier = uniforms.supplier(field.name(), field.type());
            if (supplier == null) {
                throw new LayoutFailure("No uniform supplier found for uniform: (%s:%s)"
                        .formatted(field.type(), field.name()));
            }
            built.get(index).setSupplier(supplier);
        }
    }

    /** Whether one of the host's global uniform maps serves this field by name. */
    private static boolean hostSupplies(PackDescriptorLayout.Field field) {
        return switch (field.type()) {
            case "mat4" -> Uniforms.mat4f_uniformMap.containsKey(field.name());
            case "vec4" -> Uniforms.vec4f_uniformMap.containsKey(field.name());
            case "vec3" -> Uniforms.vec3f_uniformMap.containsKey(field.name());
            case "vec2" -> Uniforms.vec2f_uniformMap.containsKey(field.name());
            case "float" -> Uniforms.vec1f_uniformMap.containsKey(field.name());
            case "int" -> Uniforms.vec1i_uniformMap.containsKey(field.name());
            default -> false;
        };
    }

    /**
     * The host layout type of a field. The host lays out vectors as floats; an integer vector has
     * the same std140 size and alignment, and its value is copied as bytes, so it takes the float
     * vector's layout.
     */
    static String hostType(String type) {
        return switch (type) {
            case "ivec2" -> "vec2";
            case "ivec3" -> "vec3";
            case "ivec4" -> "vec4";
            default -> type;
        };
    }

    private static void requireDenseBindings(PackDescriptorLayout layout) {
        BitSet bindings = new BitSet();
        int count = 0;
        for (var block : layout.uniformBlocks()) { bindings.set(block.binding()); count++; }
        for (var buffer : layout.storageBuffers()) { bindings.set(buffer.binding()); count++; }
        for (var image : layout.images()) { bindings.set(image.binding()); count++; }
        if (bindings.cardinality() != count || bindings.nextClearBit(0) != count) {
            throw new LayoutFailure("descriptor bindings must be distinct and run 0.." + (count - 1)
                    + " without a gap: " + bindings);
        }
    }

    /**
     * The stage's module in a garbage-collected direct buffer, so a pipeline whose other stage
     * fails leaves nothing to release. A source compiled earlier in the load is not compiled again
     * (CompiledStages).
     */
    private static SPIRVUtils.SPIRV compile(Stage stage, boolean packModule) {
        byte[] module = CompiledStages.module(stage.kind(), packModule, stage.source(),
                () -> compileModule(stage, packModule));
        ByteBuffer bytecode = ByteBuffer.allocateDirect(module.length).put(module).flip();
        return SPIRVUtils.SPIRV.ownedBuffer(bytecode, () -> {});
    }

    private static byte[] compileModule(Stage stage, boolean packModule) {
        ByteBuffer compiled;
        try {
            compiled = GlslCompiler.compile(stage.name(), stage.source(), stage.kind(), GlslCompiler::optimize);
        } catch (IllegalStateException failure) {
            throw new ShaderCompilationFailure(failure, stage.source());
        }
        try {
            ByteBuffer module = packModule ? SpirvTrigPrecision.apply(SpirvLocalInitializer.apply(compiled)) : compiled;
            byte[] bytes = new byte[module.remaining()];
            module.duplicate().get(bytes);
            return bytes;
        } finally {
            MemoryUtil.memFree(compiled);
        }
    }
}
