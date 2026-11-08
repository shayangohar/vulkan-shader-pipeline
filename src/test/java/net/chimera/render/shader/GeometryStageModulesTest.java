package net.chimera.render.shader;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_GEOMETRY_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

class GeometryStageModulesTest {
    /** The geometry stage runs between the vertex and fragment stages, entering at their main. */
    @Test
    void theGeometryStageGoesBeforeTheFragmentStage() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer main = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = stages(stack, main,
                    VK_SHADER_STAGE_VERTEX_BIT, VK_SHADER_STAGE_FRAGMENT_BIT);
            VkPipelineShaderStageCreateInfo.Buffer out = GeometryStageModules.insert(stages, 77L, stack);
            assertEquals(3, out.remaining());
            assertEquals(VK_SHADER_STAGE_VERTEX_BIT, out.get(0).stage());
            assertEquals(1L, out.get(0).module(), "an existing stage keeps its module");
            assertEquals(VK_SHADER_STAGE_GEOMETRY_BIT, out.get(1).stage());
            assertEquals(77L, out.get(1).module());
            assertEquals("main", out.get(1).pNameString());
            assertEquals(VK_SHADER_STAGE_FRAGMENT_BIT, out.get(2).stage());
        }
    }

    /** With tessellation, geometry follows the evaluation stage, as in GL's stage order. */
    @Test
    void tessellationStagesComeFirst() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer out = GeometryStageModules.insert(stages(stack, stack.UTF8("main"),
                    VK_SHADER_STAGE_VERTEX_BIT, VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT,
                    VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT, VK_SHADER_STAGE_FRAGMENT_BIT), 9L, stack);
            int[] order = new int[out.remaining()];
            for (int index = 0; index < order.length; index++) order[index] = out.get(index).stage();
            assertEquals(List.of(VK_SHADER_STAGE_VERTEX_BIT, VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT,
                    VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT, VK_SHADER_STAGE_GEOMETRY_BIT,
                    VK_SHADER_STAGE_FRAGMENT_BIT), Arrays.stream(order).boxed().toList());
        }
    }

    private static VkPipelineShaderStageCreateInfo.Buffer stages(MemoryStack stack, ByteBuffer main, int... kinds) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(kinds.length, stack);
        for (int index = 0; index < kinds.length; index++) {
            stages.get(index).sType$Default().stage(kinds[index]).module(index + 1L).pName(main);
        }
        return stages;
    }
}
