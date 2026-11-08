package net.chimera.render.vertex;

import net.vulkanmod.render.chunk.build.light.data.LightDataAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A light-emitting block's light word under vanilla's ambient occlusion, read back through
 * VulkanMod's own unpacking: the shade brightness and the corners VulkanMod gives a non-emitter,
 * with every other field as VulkanMod computed it.
 */
final class EmitterOcclusionTest {
    /** Glowstone as VulkanMod words it: full, opaque, block light 15, ao 1 and no corners. */
    @Test
    void aFullEmitterDarkensAndOccludesEveryCorner() {
        int glowstone = word(true, true, true, 15, 6, 1.0F, 0);
        int occluding = EmitterOcclusion.occluding(glowstone, 0.2F);
        assertEquals(0.2F, LightDataAccess.unpackAO(occluding), 1.0F / 2048.0F);
        assertEquals(0xFF, LightDataAccess.unpackCO(occluding));
        int occlusionBits = 0xFFF << 8 | 0xFF << 20;
        assertEquals(glowstone & ~occlusionBits, occluding & ~occlusionBits, "other fields changed");
    }

    /** A shaped occluder (a lantern's shape) keeps the corners its shape gives it. */
    @Test
    void aShapedEmitterKeepsItsShapesCorners() {
        int shaped = word(false, false, true, 15, 0, 1.0F, 0x0F);
        int occluding = EmitterOcclusion.occluding(shaped, 1.0F);
        assertEquals(0x0F, LightDataAccess.unpackCO(occluding));
        assertEquals(1.0F, LightDataAccess.unpackAO(occluding));
    }

    /** An open emitter (a torch) does not darken, so it occludes no corner. */
    @Test
    void anOpenEmitterOccludesNothing() {
        int torch = word(false, false, false, 14, 15, 1.0F, 0);
        int occluding = EmitterOcclusion.occluding(torch, 1.0F);
        assertEquals(torch, occluding);
        assertTrue(LightDataAccess.unpackBL(occluding) == 14 && LightDataAccess.unpackSL(occluding) == 15);
    }

    private static int word(boolean fullOpaque, boolean fullCube, boolean opaque, int block, int sky, float ao,
                            int corners) {
        return LightDataAccess.packFC(fullCube) | LightDataAccess.packFO(fullOpaque) | LightDataAccess.packOP(opaque)
                | LightDataAccess.packEM(false) | LightDataAccess.packCO(corners) | LightDataAccess.packAO(ao)
                | LightDataAccess.packSL(sky) | LightDataAccess.packBL(block);
    }
}
