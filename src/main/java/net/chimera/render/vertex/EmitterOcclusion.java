package net.chimera.render.vertex;

import net.vulkanmod.render.chunk.build.light.data.LightDataAccess;

/**
 * Vanilla's ambient occlusion for a light-emitting block, in VulkanMod's light word
 * (LightDataAccess): the block's shade brightness, and the corners VulkanMod gives any other block.
 * VulkanMod gives an emitter (glowstone, a lamp, a froglight) neither: ao 1 and no occluded corners,
 * old Sodium's "do not apply AO from blocks that emit light". Vanilla and Sodium 0.8.13, which Iris
 * draws with, read the shade brightness of every block.
 */
public final class EmitterOcclusion {
    /** The fields this rewrites: ambient occlusion (12 bits from bit 8) and corner occlusion (8 bits from bit 20). */
    private static final int OCCLUSION_BITS = 0xFFF << 8 | 0xFF << 20;

    private EmitterOcclusion() {}

    /**
     * {@code word} with {@code shade} as its ambient occlusion. A block that is not a full opaque
     * one but occludes keeps its shape's corners; otherwise a full block that darkens occludes
     * all of them, and any other none.
     */
    public static int occluding(int word, float shade) {
        boolean fullOpaque = LightDataAccess.unpackFO(word);
        int corners = !fullOpaque && LightDataAccess.unpackOP(word) ? LightDataAccess.unpackCO(word)
                : (fullOpaque || LightDataAccess.unpackFC(word)) && shade < 1.0F ? 0xFF : 0;
        return word & ~OCCLUSION_BITS | LightDataAccess.packAO(shade) | LightDataAccess.packCO(corners);
    }
}
