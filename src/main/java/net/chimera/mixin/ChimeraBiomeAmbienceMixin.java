package net.chimera.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.chimera.render.shader.AmbientMoodAccess;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.BiomeAmbientSoundsHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.AmbientMoodSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Iris's constantMood beside the vanilla moodiness: the same step at the same position each
 * mood tick (the tick lambda that reads the sky light), clamped to [0, 1] instead of reset after the
 * mood sound.
 */
@Mixin(BiomeAmbientSoundsHandler.class)
public abstract class ChimeraBiomeAmbienceMixin implements AmbientMoodAccess {
    @Shadow
    @Final
    private LocalPlayer player;

    @Unique
    private float chimera$constantMood;

    @Inject(method = "lambda$tick$3", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I", ordinal = 0),
            require = 1)
    private void chimera$stepConstantMood(AmbientMoodSettings settings, CallbackInfo info,
                                          @Local BlockPos position) {
        Level level = this.player.level();
        int sky = level.getBrightness(LightLayer.SKY, position);
        float step = sky > 0 ? sky / (float) level.getMaxLightLevel() * 0.001F
                : (level.getBrightness(LightLayer.BLOCK, position) - 1) / (float) settings.getTickDelay();
        this.chimera$constantMood = Mth.clamp(this.chimera$constantMood - step, 0.0F, 1.0F);
    }

    @Override
    public float chimera$constantMood() {
        return this.chimera$constantMood;
    }
}
