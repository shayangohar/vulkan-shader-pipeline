package net.chimera.render.shader;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_GEOMETRY_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

/**
 * Pack geometry stages (.gsh), which VulkanMod's GraphicsShaderStages has no slot for. A pipeline
 * built with one gets its module while it is constructed; every variant it creates, now or lazily
 * at a later draw, runs the module between its last vertex-processing stage and its fragment stage
 * (GraphicsPipelineGeometryStageMixin); the module goes with the pipeline's cleanUp.
 */
public final class GeometryStageModules {
    /** The geometry SPIR-V of the pipeline this thread is constructing, and what was made from it. */
    private static final class Pending {
        private final ByteBuffer spirv;
        private GraphicsPipeline pipeline;
        private long module;

        private Pending(ByteBuffer spirv) {
            this.spirv = spirv;
        }
    }

    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();
    private static final Map<GraphicsPipeline, Long> MODULES = Collections.synchronizedMap(new WeakHashMap<>());

    private GeometryStageModules() {}

    /** Constructs a pipeline with {@code create}, giving it {@code spirv} as its geometry stage. */
    public static GraphicsPipeline construct(ByteBuffer spirv, Supplier<GraphicsPipeline> create) {
        Pending pending = new Pending(spirv);
        PENDING.set(pending);
        boolean constructed = false;
        try {
            GraphicsPipeline pipeline = create.get();
            constructed = true;
            return pipeline;
        } finally {
            PENDING.remove();
            if (!constructed && pending.module != 0L) {
                MODULES.remove(pending.pipeline);
                destroy(pending.module);
            }
        }
    }

    /** At construction: makes the geometry module of the pipeline this thread is constructing, if it has one. */
    public static void attach(GraphicsPipeline pipeline) {
        Pending pending = PENDING.get();
        if (pending == null || pending.module != 0L) return;
        pending.module = createModule(pending.spirv);
        pending.pipeline = pipeline;
        MODULES.put(pipeline, pending.module);
    }

    /** The stages one variant of {@code pipeline} is created with: its geometry stage added, when it has one. */
    public static VkPipelineShaderStageCreateInfo.Buffer withGeometry(GraphicsPipeline pipeline,
                                                                     VkPipelineShaderStageCreateInfo.Buffer stages) {
        Long module = MODULES.get(pipeline);
        return module == null ? stages : insert(stages, module, MemoryStack.stackGet());
    }

    /**
     * {@code stages} with a geometry stage running {@code module} placed before the fragment stage,
     * on {@code stack}, entering at the same entry point as the stage it precedes.
     */
    static VkPipelineShaderStageCreateInfo.Buffer insert(VkPipelineShaderStageCreateInfo.Buffer stages, long module,
                                                       MemoryStack stack) {
        int count = stages.remaining();
        VkPipelineShaderStageCreateInfo.Buffer out = VkPipelineShaderStageCreateInfo.calloc(count + 1, stack);
        int at = 0;
        for (int index = 0; index < count; index++) {
            VkPipelineShaderStageCreateInfo stage = stages.get(stages.position() + index);
            if (stage.stage() == VK_SHADER_STAGE_FRAGMENT_BIT) {
                // A copy keeps the entry point's terminated name; the geometry stage takes no specialization.
                out.get(at++).set(stage).stage(VK_SHADER_STAGE_GEOMETRY_BIT).module(module).pSpecializationInfo(null);
            }
            out.get(at++).set(stage);
        }
        return out;
    }

    /** At cleanUp: destroys the pipeline's geometry module. */
    public static void release(GraphicsPipeline pipeline) {
        Long module = MODULES.remove(pipeline);
        if (module != null) destroy(module);
    }

    private static long createModule(ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            LongBuffer handle = stack.mallocLong(1);
            int result = vkCreateShaderModule(DeviceManager.vkDevice, info, null, handle);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("geometry shader module creation failed: " + result);
            }
            return handle.get(0);
        }
    }

    private static void destroy(long module) {
        vkDestroyShaderModule(DeviceManager.vkDevice, module, null);
    }
}
