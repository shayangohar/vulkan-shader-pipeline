package net.chimera.render.shader;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.OptionalLong;

/** Iris 1.8.14's worldTime and worldDay (WorldTimeUniforms). */
final class WorldClock {
    private WorldClock() {}

    /**
     * The level's worldTime: the day time in the Nether, the End or a dimension with their effects,
     * as Iris means it and OptiFine serves it, and elsewhere the dimension's fixed time when it has
     * one. Iris compares the dimension by reference, so where the pack maps the Nether it reads
     * 18000 instead; that is Iris's defect, and RedHat v34 lights its Nether as midnight there.
     */
    static int worldTime(ClientLevel level) {
        ResourceLocation effects = level.dimensionType().effectsLocation();
        boolean netherOrEnd = level.dimension() == Level.NETHER || level.dimension() == Level.END
                || Level.NETHER.location().equals(effects) || Level.END.location().equals(effects);
        return worldTime(level.getDayTime(), level.dimensionType().fixedTime(), netherOrEnd);
    }

    static int worldTime(long dayTime, OptionalLong fixedTime, boolean netherOrEnd) {
        long time = Math.floorMod(dayTime, 24000L);
        return (int) (netherOrEnd ? time : fixedTime.orElse(time));
    }

    static int worldDay(long dayTime) {
        return (int) Math.floorDiv(dayTime, 24000L);
    }
}
