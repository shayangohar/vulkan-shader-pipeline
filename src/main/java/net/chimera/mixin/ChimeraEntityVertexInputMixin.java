package net.chimera.mixin;

import net.chimera.render.vertex.ChimeraVertexFormats;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.VertexFormat;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX;

/** Supplies Vulkan input descriptions for Chimera append-only family formats. */
@Mixin(targets = "net.vulkanmod.vulkan.shader.GraphicsPipeline$VertexInputDescription", remap = false)
public abstract class ChimeraEntityVertexInputMixin {
    @Shadow
    @Final
    @Mutable
    private VkVertexInputAttributeDescription.Buffer attributeDescriptions;

    @Shadow
    @Final
    @Mutable
    private VkVertexInputBindingDescription.Buffer bindingDescriptions;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void chimera$describeEntityFormat(VertexFormat format, CallbackInfo callback) {
        if (format != ChimeraVertexFormats.EXTENDED_ENTITY
                && !ChimeraVertexFormats.handFormats().containsValue(format)) {
            return;
        }

        this.bindingDescriptions = VkVertexInputBindingDescription.calloc(1);
        VkVertexInputBindingDescription binding = this.bindingDescriptions.get(0);
        binding.binding(0);
        binding.stride(format.getVertexSize());
        binding.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        if (format == ChimeraVertexFormats.EXTENDED_ENTITY) {
            this.attributeDescriptions = VkVertexInputAttributeDescription.calloc(9);
            describe(0, VK_FORMAT_R32G32B32_SFLOAT, 0);
            describe(1, VK_FORMAT_R8G8B8A8_UNORM, 12);
            describe(2, VK_FORMAT_R32G32_SFLOAT, 16);
            describe(3, VK_FORMAT_R16G16_SINT, 24);
            describe(4, VK_FORMAT_R16G16_SINT, 28);
            describe(5, VK_FORMAT_R8G8B8A8_SNORM, 32);
            describe(6, VK_FORMAT_R16G16B16A16_UINT, 36);
            describe(7, VK_FORMAT_R32G32_SFLOAT, 44);
            describe(8, VK_FORMAT_R8G8B8A8_SNORM, 52);
        } else {
            var names = format.getElementAttributeNames();
            var elements = format.getElements();
            this.attributeDescriptions = VkVertexInputAttributeDescription.calloc(elements.size());
            for (int i = 0; i < names.size(); i++) {
                int nativeFormat = switch (names.get(i)) {
                    case "Position" -> VK_FORMAT_R32G32B32_SFLOAT;
                    case "Color" -> VK_FORMAT_R8G8B8A8_UNORM;
                    case "UV0", "MidTexCoord" -> VK_FORMAT_R32G32_SFLOAT;
                    case "UV2" -> VK_FORMAT_R16G16_SINT;
                    case "EntityIds" -> VK_FORMAT_R16G16B16A16_UINT;
                    case "Tangent" -> VK_FORMAT_R8G8B8A8_SNORM;
                    default -> throw new IllegalArgumentException("unsupported hand attribute " + names.get(i));
                };
                describe(i, nativeFormat, format.getOffset(elements.get(i)));
            }
        }
    }

    private void describe(int location, int format, int offset) {
        VkVertexInputAttributeDescription description = this.attributeDescriptions.get(location);
        description.binding(0).location(location).format(format).offset(offset);
    }
}
