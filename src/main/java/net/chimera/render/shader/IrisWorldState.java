package net.chimera.render.shader;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Iris's fog uniforms in a program with fog (FogUniforms with FogMode.PER_VERTEX), the gl_Fog range
 * (IrisInternalUniforms iris_FogStart and iris_FogEnd) and its cloud clock
 * (IrisExclusiveUniforms.cloudTime). A post program reads FogMode.OFF's constant fogMode and
 * fogShape instead, served into its source at plan time (PackPlanBuilder).
 *
 * <p>fogMode and fogDensity follow each FogRenderer.setupFog, as Iris reads them at every program
 * bind: the density as setup begins (Iris MixinFogRenderer: water fog's, and -1 for any other fog;
 * 0 before any setup, CapturedRenderingState's default). fogMode reads GL_LINEAR for a negative
 * density and GL_EXP2 otherwise, and fogDensity never goes below 0. cloudTime counts the level
 * renderer's ticks at 0.03 a tick.</p>
 *
 * <p>fogShape (1 for cylindrical fog, 0 for spherical) and the gl_Fog range are PER_FRAME in Iris:
 * ProgramUniforms reads them at a program's first use in a frame, and its later draws keep them
 * through the fog setups that follow. So a program used across a fog change keeps the first: the
 * horizon is gbuffers_skybasic's first use, ahead of the sky's fog setup, and the sky disc it draws
 * next reads the Float.MAX_VALUE start that setupNoFog left at the last frame's end. That reaches a
 * pack that fogs its sky with gl_Fog.start, as packs written for OptiFine before 1.18.2 do (Sildurs
 * Vibrant's non-Iris branch). A pack pipeline stands for Iris's program here: Chimera builds one
 * per program and vertex format, as Iris builds one per shader key.</p>
 */
final class IrisWorldState implements FrameValueSource {
    static final Set<String> NAMES = Set.of("fogMode", "fogShape", "fogDensity", "iris_FogStart", "iris_FogEnd",
            "cloudTime");

    private static final int GL_LINEAR = 0x2601;
    private static final int GL_EXP2 = 0x0801;

    private volatile float fogDensity;
    private volatile int fogShape;
    /** Each pack pipeline's per-frame fog values, latched at its first draw in the frame. */
    private final Map<Object, FrameFog> programFog = new WeakHashMap<>();
    /** The per-frame fog values the next draw reads: its program's, as the bindings were last written. */
    private final FrameFog drawFog = new FrameFog();
    private long frame;
    private float cloudTime;

    /**
     * Records the density of the fog FogRenderer is setting up. Water fog starts at 0.05, thins
     * with the player's water vision, and thickens by 0.005 in a biome with closer water fog.
     */
    void captureFogDensity(Camera camera) {
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

    /** Records the shape of the fog FogRenderer has set up, which each program latches at its first draw. */
    void captureFogShape() {
        fogShape = RenderSystem.getShaderFogShape() == FogShape.CYLINDER ? 1 : 0;
    }

    /**
     * Drops every program's latched fog when the pack session ends: Iris builds its programs afresh
     * with a pack, and each latches at its first draw. The captured fog stays, as Iris's
     * CapturedRenderingState keeps it across a reload: it is the game's, and the next setup replaces
     * it. The draw's values stay too, since they are what the bindings hold.
     */
    void resetProgramFog() {
        programFog.clear();
    }

    /** Starts a frame: each program latches its per-frame fog values afresh at its next draw. */
    void beginFrame() {
        frame++;
    }

    /**
     * Serves the next draw its program's per-frame fog values: those {@code live} held (its fogStart
     * and fogEnd) and the fog shape, as they were at the program's first draw this frame. A null
     * program, a compute dispatch, reads the current ones: Iris runs each compute once a frame.
     * Returns true when the served values changed.
     */
    boolean latchProgramFog(Object program, PackFrameState live) {
        FrameFog latched = program == null ? new FrameFog()
                : programFog.computeIfAbsent(program, key -> new FrameFog());
        if (latched.frame != frame) {
            latched.set(frame, (float) live.value("fogStart", -1), (float) live.value("fogEnd", -1), fogShape);
        }
        return drawFog.set(latched.frame, latched.start, latched.end, latched.shape);
    }

    void capture(Minecraft minecraft, float partialTick) {
        int ticks = minecraft.levelRenderer == null ? 0 : minecraft.levelRenderer.getTicks();
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
            case "fogShape" -> drawFog.shape;
            case "fogDensity" -> Math.max(0.0F, fogDensity);
            case "iris_FogStart" -> drawFog.start;
            case "iris_FogEnd" -> drawFog.end;
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

    /** One program's per-frame fog values and the frame they were read in. */
    private static final class FrameFog {
        private long frame = -1L;
        private float start;
        private float end;
        private int shape;

        /** Sets the values read in {@code frame}; true when they changed. */
        boolean set(long frame, float start, float end, int shape) {
            boolean changed = Float.compare(this.start, start) != 0 || Float.compare(this.end, end) != 0
                    || this.shape != shape;
            this.frame = frame;
            this.start = start;
            this.end = end;
            this.shape = shape;
            return changed;
        }
    }
}
