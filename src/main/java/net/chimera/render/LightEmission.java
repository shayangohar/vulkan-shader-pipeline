package net.chimera.render;

import net.minecraft.world.level.block.state.BlockState;

/**
 * The light a block state emits, as Iris 1.21.1 reads it for blockEntityId lights, the terrain
 * emission attribute and held items: the state's own value. NeoForge deprecates that getter in
 * favour of a level-aware one, which these callers either lack or Iris does not use.
 */
public final class LightEmission {
    private LightEmission() {}

    @SuppressWarnings("deprecation")
    public static int of(BlockState state) {
        return state.getLightEmission();
    }
}
