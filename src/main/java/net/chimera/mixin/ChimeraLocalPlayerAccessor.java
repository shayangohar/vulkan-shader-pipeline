package net.chimera.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AmbientSoundHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The player's ambient sound handlers, the biome ambience among them (constantMood). */
@Mixin(LocalPlayer.class)
public interface ChimeraLocalPlayerAccessor {
    @Accessor("ambientSoundHandlers")
    List<AmbientSoundHandler> chimera$ambientSoundHandlers();
}
