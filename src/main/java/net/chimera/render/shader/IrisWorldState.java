package net.chimera.render.shader;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.chimera.mixin.ChimeraLevelRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.Set;

/**
 * Iris's fog uniforms in a program with fog (FogUniforms with FogMode.PER_VERTEX) and its cloud
 * clock (IrisExclusiveUniforms.cloudTime). A post program reads FogMode.OFF's constant fogMode and
 * fogShape instead, served into its source at plan time (PackPlanBuilder).
 *
 * <p>Each FogRenderer.setupFog sets them as Iris reads them at the next program bind: the density
 * as setup begins (Iris MixinFogRenderer: water fog's, and -1 for any other fog; 0 before any
 * setup, CapturedRenderingState's default) and the shape the setup leaves (RenderSystem's shader
 * fog shape). fogMode reads GL_LINEAR for a negative density and GL_EXP2 otherwise, and
 * fogDensity never goes below 0. fogShape is 1 for cylindrical fog and 0 for spherical, and
 * cloudTime counts the level renderer's ticks at 0.03 a tick.</p>
 */
final class IrisWorldState implements FrameValueSource {
    static final Set<String> NAMES = Set.of("fogMode", "fogShape", "fogDensity", "cloudTime");

    private static final int GL_LINEAR = 0x2601;
    private static final int GL_EXP2 = 0x0801;

    private static volatile float fogDensity;
    private static volatile int fogShape;
    private float cloudTime;

    /**
     * Records the density of the fog FogRenderer is setting up. Water fog starts at 0.05, thins
     * with the player's water vision, and thickens by 0.005 in a biome with closer water fog.
     */
    static void captureFogDensity(Camera camera) {
        if (camera.getFluidInCamera() != FogType.WATER) {
            fogDensity = -1.0F;
            return;
        }
        float density = 0.05F;
        if (camera.getEntity() instanceof LocalPlayer player) {
            float vision = player.getWaterVision();
            density -= vision * vision * 0.03F;
            if (player.level().getBiome(player.blockPosition()).is(BiomeTags.HAS_CLOSER_WATER_FOG)) {
                density += 0.005F;
            }
        }
        fogDensity = density;
    }

    /** Records the shape of the fog FogRenderer has set up. */
    static void captureFogShape() {
        fogShape = RenderSystem.getShaderFogShape() == FogShape.CYLINDER ? 1 : 0;
    }

    /** Iris's state before any fog setup, for a fresh session. */
    static void resetFog() {
        fogDensity = 0.0F;
        fogShape = 0;
    }

    void capture(Minecraft minecraft, float partialTick) {
        int ticks = minecraft.levelRenderer == null ? 0
                : ((ChimeraLevelRendererAccessor) minecraft.levelRenderer).chimera$ticks();
        this.cloudTime = (ticks + partialTick) * 0.03F;
    }

    void clear() {
        this.cloudTime = 0.0F;
    }

    @Override
    public Set<String> names() {
        return NAMES;
    }

    @Override
    public double value(String name, int component) {
        if (component > 0) return Double.NaN;
        return switch (name) {
            case "fogMode" -> fogDensity < 0.0F ? GL_LINEAR : GL_EXP2;
            case "fogShape" -> fogShape;
            case "fogDensity" -> Math.max(0.0F, fogDensity);
            case "cloudTime" -> this.cloudTime;
            default -> Double.NaN;
        };
    }

    @Override
    public void write(String name, String type, MappedBuffer target) {
        double value = value(name, -1);
        if (type.startsWith("i") || type.equals("bool")) {
            target.putInt(0, (int) value);
        } else {
            target.putFloat(0, (float) value);
        }
    }
}
