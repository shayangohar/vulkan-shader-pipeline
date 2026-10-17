package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.vulkanmod.render.vertex.QuadSorter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sorts Chimera's terrain formats as VulkanMod sorts COMPRESSED_TERRAIN: they share its
 * compressed position prefix, and the sorter steps by the format's own stride.
 */
@Mixin(value = QuadSorter.class, remap = false)
public abstract class QuadSorterMixin {
    @ModifyExpressionValue(method = "setupQuadSortingPoints", at = @At(value = "FIELD",
            target = "Lnet/vulkanmod/render/vertex/CustomVertexFormat;COMPRESSED_TERRAIN:"
                    + "Lcom/mojang/blaze3d/vertex/VertexFormat;"))
    private VertexFormat chimera$compressedPositions(VertexFormat compressed,
                                                     @Local(argsOnly = true) VertexFormat format) {
        return ChimeraVertexFormats.isTerrainFormat(format) ? format : compressed;
    }
}
