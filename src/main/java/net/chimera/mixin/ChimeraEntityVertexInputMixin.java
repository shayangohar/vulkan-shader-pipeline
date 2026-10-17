package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.vertex.ChimeraVertexFormats;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Supplies Vulkan input descriptions for Chimera's family formats in place of Reforged's
 * translator, which rejects their generic float (MidTexCoord) with "Unknown format: Generic".
 */
@Mixin(targets = "net.vulkanmod.vulkan.shader.GraphicsPipeline$VertexInputDescription", remap = false)
public abstract class ChimeraEntityVertexInputMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/vulkanmod/vulkan/shader/GraphicsPipeline;getAttributeDescriptions"
                    + "(Lcom/mojang/blaze3d/vertex/VertexFormat;)"
                    + "Lorg/lwjgl/vulkan/VkVertexInputAttributeDescription$Buffer;"),
            require = 1)
    private VkVertexInputAttributeDescription.Buffer chimera$describeFamilyFormat(VertexFormat format,
            Operation<VkVertexInputAttributeDescription.Buffer> original) {
        if (!ChimeraVertexFormats.isFamilyFormat(format)) return original.call(format);
        int[] inputs = ChimeraVertexFormats.vulkanInputFormats(format);
        var elements = format.getElements();
        // Heap memory, as Reforged's own descriptions: VertexInputDescription frees it with memFree.
        VkVertexInputAttributeDescription.Buffer descriptions = VkVertexInputAttributeDescription.calloc(inputs.length);
        for (int location = 0; location < inputs.length; location++) {
            descriptions.get(location).binding(0).location(location).format(inputs[location])
                    .offset(format.getOffset(elements.get(location)));
        }
        return descriptions;
    }
}
