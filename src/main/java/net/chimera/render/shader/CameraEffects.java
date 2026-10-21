package net.chimera.render.shader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;

/** The camera entity's effects as Iris's CommonUniforms reads them: night vision, darkness, blindness, fluid. */
final class CameraEffects {
    private CameraEffects() {}

    static float nightVision(Minecraft minecraft, Entity cameraEntity, float partialTick) {
        if (cameraEntity instanceof LivingEntity living
                && living.hasEffect(MobEffects.NIGHT_VISION)) {
            return clamp(GameRenderer.getNightVisionScale(living, partialTick), 0.0f, 1.0f);
        }
        if (minecraft.player != null && minecraft.player.getWaterVision() > 0.0f
                && minecraft.player.hasEffect(MobEffects.CONDUIT_POWER)) {
            return clamp(minecraft.player.getWaterVision(), 0.0f, 1.0f);
        }
        return 0.0f;
    }

    static float darknessLightFactor(Minecraft minecraft, float partialTick) {
        if (minecraft.player == null) {
            return 0.0f;
        }
        float scale = minecraft.options.darknessEffectScale().get().floatValue();
        float gamma = darknessFactor(minecraft.player, partialTick) * scale;
        float darkness = Math.max(0.0f,
                (float) Math.cos((minecraft.player.tickCount - partialTick)
                        * (float) Math.PI * 0.025f) * 0.45f * gamma);
        return darkness * scale;
    }

    /** The darkness effect's blend, as Iris CommonUniforms reads it: 0 without the effect. */
    static float darknessFactor(LivingEntity living, float partialTick) {
        var effect = living.getEffect(MobEffects.DARKNESS);
        return effect == null ? 0.0F : effect.getBlendFactor(living, partialTick);
    }

    static float blindness(LivingEntity living) {
        var effect = living.getEffect(MobEffects.BLINDNESS);
        if (effect == null) {
            return 0.0f;
        }
        return effect.isInfiniteDuration() ? 1.0f
                : clamp(effect.getDuration() / 20.0f, 0.0f, 1.0f);
    }

    static int eyeInWater(FogType type, boolean spectator) {
        if (type == FogType.WATER) {
            return 1;
        }
        if (!spectator && type == FogType.LAVA) {
            return 2;
        }
        return type == FogType.POWDER_SNOW ? 3 : 0;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
