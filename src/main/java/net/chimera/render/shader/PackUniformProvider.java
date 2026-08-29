package net.chimera.render.shader;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Stable storage for the small set of pack uniforms accepted by M5.3.
 *
 * <p>The provider owns only Chimera buffers. It does not edit VulkanMod's
 * global uniform maps. The render-level hook updates the values once per
 * frame, and the supplier returns the same native buffer on every draw.</p>
 */
public final class PackUniformProvider {
    private static final PackUniformProvider INSTANCE = new PackUniformProvider();

    private final Map<String, MappedBuffer> buffers = new HashMap<>();
    private final Matrix4f shadowModelView = new Matrix4f();
    private final Matrix4f shadowProjection = new Matrix4f();
    private final Vector3f shadowLightPosition = new Vector3f(0.0f, 1.0f, 0.0f);
    private final float[] matrixScratch = new float[16];
    private long lastFrameNanos;
    private float frameTimeCounter;
    private float celestialAngle;

    private PackUniformProvider() {}

    public static PackUniformProvider shared() {
        return INSTANCE;
    }

    /** Called by Pipeline.Builder while it applies the generated UBO config. */
    public Supplier<MappedBuffer> supplier(Uniform.Info info) {
        MappedBuffer buffer = buffers.computeIfAbsent(info.name, ignored -> createBuffer(info.type));
        return () -> {
            refreshHostAlias(info.name, buffer);
            return buffer;
        };
    }

    /** Captures the current camera, world state, and render delta on the render thread. */
    public static void beginFrame(Camera camera, float partialTick) {
        INSTANCE.updateFrame(camera, partialTick);
    }

    /** The frame's celestial angle, shared with the shadow matrix builder. */
    public static float currentCelestialAngle() {
        return INSTANCE.celestialAngle;
    }

    /** Publishes the exact shadow state used by the shadow render. */
    public static void updateShadowState(Matrix4f modelView, Matrix4f projection, Vector3f lightPosition) {
        INSTANCE.shadowModelView.set(modelView);
        INSTANCE.shadowProjection.set(projection);
        INSTANCE.shadowLightPosition.set(lightPosition);
        INSTANCE.writeMatrix("shadowModelView", INSTANCE.shadowModelView);
        INSTANCE.writeMatrix("shadowProjection", INSTANCE.shadowProjection);
        INSTANCE.putVec3("shadowLightPosition", lightPosition.x, lightPosition.y, lightPosition.z);
    }

    private void updateFrame(Camera camera, float partialTick) {
        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos == 0L
                ? 0.0f
                : Math.min(Math.max((now - lastFrameNanos) / 1_000_000_000.0f, 0.0f), 0.25f);
        lastFrameNanos = now;
        frameTimeCounter += deltaSeconds;

        Minecraft minecraft = Minecraft.getInstance();
        updateWindowValues(minecraft);

        if (camera != null) {
            var position = camera.position();
            putVec3("cameraPosition", (float) position.x, (float) position.y, (float) position.z);
            putInt("isEyeInWater", camera.getFluidInCamera() == FogType.WATER ? 1 : 0);
        } else {
            putVec3("cameraPosition", 0.0f, 0.0f, 0.0f);
            putInt("isEyeInWater", 0);
        }

        ClientLevel level = minecraft.level;
        if (level == null) {
            this.celestialAngle = 0.0f;
            putInt("worldTime", 0);
            putFloat("rainStrength", 0.0f);
            putInt("moonPhase", 0);
            putVec3("sunPosition", 0.0f, 1.0f, 0.0f);
            putVec3("moonPosition", 0.0f, -1.0f, 0.0f);
            putVec3("shadowLightPosition", 0.0f, 1.0f, 0.0f);
        } else {
            putInt("worldTime", (int) Math.floorMod(level.getGameTime(), 24000L));
            putFloat("rainStrength", level.getRainLevel(partialTick));
            putInt("moonPhase", (int) Math.floorMod(level.getDayTime() / 24000L, 8L));
            float timeOfDay = (float) (Math.floorMod(level.getDayTime(), 24000L) + partialTick) / 24000.0f;
            this.celestialAngle = timeOfDay;
            updateCelestialValues(timeOfDay);
        }
        putFloat("frameTimeCounter", frameTimeCounter);
        putFloat("near", 0.05f);
        putFloat("far", minecraft.options.getEffectiveRenderDistance() * 16.0f);
    }

    private void updateWindowValues(Minecraft minecraft) {
        float width = Math.max(minecraft.getWindow().getWidth(), 1);
        float height = Math.max(minecraft.getWindow().getHeight(), 1);
        putFloat("viewWidth", width);
        putFloat("viewHeight", height);
        putFloat("aspectRatio", width / height);
    }

    private void updateCelestialValues(float timeOfDay) {
        double angle = timeOfDay * Math.PI * 2.0;
        float x = (float) Math.sin(angle);
        float y = (float) Math.cos(angle);
        putVec3("sunPosition", x, y, 0.0f);
        putVec3("moonPosition", -x, -y, 0.0f);
        putVec3("shadowLightPosition", x, y, 0.0f);
    }

    private void refreshHostAlias(String name, MappedBuffer buffer) {
        switch (name) {
            case "fogColor" -> copyVec4(VRenderSystem.getShaderFogColor(), buffer);
            case "fogStart", "fogEnd" -> {
                FogData fog = VRenderSystem.getFogData();
                float value = fog == null ? 0.0f
                        : name.equals("fogStart") ? fog.renderDistanceStart : fog.renderDistanceEnd;
                buffer.putFloat(0, value);
            }
            case "screenSize" -> copyVec2(VRenderSystem.getScreenSize(), buffer);
            case "textureSize" -> copyInts(VRenderSystem.getTextureSize(), buffer);
            case "texelSize" -> copyVec2(VRenderSystem.getTexelSize(), buffer);
            case "shadowModelView" -> writeMatrix(name, shadowModelView);
            case "shadowProjection" -> writeMatrix(name, shadowProjection);
            case "shadowLightPosition" -> putVec3(name, shadowLightPosition.x,
                    shadowLightPosition.y, shadowLightPosition.z);
            default -> {
                // Values captured by beginFrame already live in their buffer.
            }
        }
    }

    private void putFloat(String name, float value) {
        MappedBuffer buffer = buffers.get(name);
        if (buffer != null) {
            buffer.putFloat(0, value);
        }
    }

    private void putInt(String name, int value) {
        MappedBuffer buffer = buffers.get(name);
        if (buffer != null) {
            buffer.putInt(0, value);
        }
    }

    private void putVec3(String name, float x, float y, float z) {
        MappedBuffer buffer = buffers.get(name);
        if (buffer != null) {
            buffer.putFloat(0, x);
            buffer.putFloat(4, y);
            buffer.putFloat(8, z);
        }
    }

    private void writeMatrix(String name, Matrix4f matrix) {
        MappedBuffer buffer = buffers.get(name);
        if (buffer == null) {
            return;
        }
        matrix.get(matrixScratch);
        for (int i = 0; i < matrixScratch.length; i++) {
            buffer.putFloat(i * 4, matrixScratch[i]);
        }
    }

    private static void copyVec2(MappedBuffer source, MappedBuffer target) {
        target.putFloat(0, source.getFloat(0));
        target.putFloat(4, source.getFloat(4));
    }

    private static void copyVec4(MappedBuffer source, MappedBuffer target) {
        for (int i = 0; i < 4; i++) {
            target.putFloat(i * 4, source.getFloat(i * 4));
        }
    }

    private static void copyInts(MappedBuffer source, MappedBuffer target) {
        target.putInt(0, source.getInt(0));
        target.putInt(4, source.getInt(4));
    }

    private static MappedBuffer createBuffer(String type) {
        MappedBuffer buffer = new MappedBuffer(bufferSize(type));
        int count = componentCount(type);
        if (type.startsWith("i")) {
            for (int i = 0; i < count; i++) {
                buffer.putInt(i * 4, 0);
            }
        } else {
            for (int i = 0; i < count; i++) {
                buffer.putFloat(i * 4, type.equals("mat4") && i % 5 == 0 ? 1.0f : 0.0f);
            }
        }
        return buffer;
    }

    private static int bufferSize(String type) {
        return switch (type) {
            case "float", "int" -> 4;
            case "vec2", "ivec2" -> 8;
            case "vec3", "ivec3" -> 16;
            case "vec4", "ivec4" -> 16;
            case "mat4" -> 64;
            default -> throw new IllegalArgumentException("unsupported pack uniform type: " + type);
        };
    }

    private static int componentCount(String type) {
        return switch (type) {
            case "float", "int" -> 1;
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            case "mat4" -> 16;
            default -> throw new IllegalArgumentException("unsupported pack uniform type: " + type);
        };
    }
}
