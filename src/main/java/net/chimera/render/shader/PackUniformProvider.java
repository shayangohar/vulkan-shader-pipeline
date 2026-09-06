package net.chimera.render.shader;

import net.chimera.ChimeraMod;
import net.chimera.shaderpack.UniformRegistry;
import net.chimera.shaderpack.PackRuntimeSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Supplies stable generated-UBO buffers from one captured frame state.
 *
 * <p>The provider owns only Chimera buffers. VulkanMod's global uniform maps
 * remain authoritative for host fields. Pack suppliers never refresh game
 * state themselves, so two passes in one frame see the same values.</p>
 */
public final class PackUniformProvider {
    private static final PackUniformProvider INSTANCE = new PackUniformProvider();

    private final Map<UniformKey, Binding> bindings = new HashMap<>();
    private final PackFrameState frameState = new PackFrameState();
    private PackRuntimeSettings runtimeSettings = PackRuntimeSettings.empty();
    private long lastFrameNanos;
    private boolean catalogLogged;

    private PackUniformProvider() {}

    public static PackUniformProvider shared() {
        return INSTANCE;
    }

    /** Called by Pipeline.Builder while it applies the generated UBO config. */
    public Supplier<MappedBuffer> supplier(Uniform.Info info) {
        UniformRegistry.UniformDescriptor descriptor =
                UniformRegistry.descriptor(info.name, info.type);
        if (descriptor == null) {
            descriptor = runtimeSettings.customDescriptors().get(info.name);
            if (descriptor != null && !descriptor.accepts(info.type)) {
                descriptor = null;
            }
        }
        if (descriptor == null) {
            throw new IllegalArgumentException("uniform is not in the Chimera catalog: "
                    + info.name + " (" + info.type + ")");
        }
        UniformKey key = new UniformKey(info.name, info.type);
        Binding binding = bindings.get(key);
        if (binding == null) {
            binding = new Binding(descriptor, info.type, createBuffer(info.type));
            bindings.put(key, binding);
        }
        frameState.write(descriptor, info.type, binding.buffer);
        logCatalogOnce();
        Binding stable = binding;
        return () -> stable.buffer;
    }

    /** Captures the frame before any pack pipeline writes. */
    public static void beginFrame(Camera camera, float partialTick) {
        beginFrame(camera, partialTick, null, null);
    }

    /** Captures the matrices supplied to LevelRenderer.renderLevel as well as game state. */
    public static void beginFrame(Camera camera, float partialTick,
                                  Matrix4f modelView, Matrix4f projection) {
        INSTANCE.updateFrame(camera, partialTick, modelView, projection);
    }

    /** The frame's celestial angle, shared with the shadow matrix builder. */
    public static float currentCelestialAngle() {
        return INSTANCE.frameState.sunAngle();
    }

    /** Publishes the exact shadow state used by the shadow render. */
    public static void updateShadowState(Matrix4f modelView, Matrix4f projection,
                                         Vector3f lightPosition) {
        INSTANCE.frameState.updateShadow(modelView, projection, lightPosition);
        INSTANCE.refreshBindings();
    }

    /** Installs the immutable runtime settings for the active pack session. */
    public static void installRuntimeSettings(PackRuntimeSettings settings) {
        INSTANCE.runtimeSettings = settings == null ? PackRuntimeSettings.empty() : settings;
        INSTANCE.frameState.installRuntimeSettings(INSTANCE.runtimeSettings);
        INSTANCE.refreshBindings();
    }

    /** Drops frame history when the pack session is destroyed or reloaded. */
    public static void resetSession() {
        INSTANCE.lastFrameNanos = 0L;
        INSTANCE.runtimeSettings = PackRuntimeSettings.empty();
        INSTANCE.frameState.installRuntimeSettings(INSTANCE.runtimeSettings);
        INSTANCE.frameState.resetSession();
        INSTANCE.refreshBindings();
    }

    private void updateFrame(Camera camera, float partialTick,
                             Matrix4f modelView, Matrix4f projection) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (frameState.prepareLevel(level)) {
            lastFrameNanos = 0L;
        }

        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos == 0L
                ? 0.0f
                : Math.min(Math.max(PackFrameState.quantizedFrameSecondsForTest(
                now - lastFrameNanos), 0.0f), 0.25f);
        lastFrameNanos = now;
        frameState.begin(minecraft, camera, partialTick, modelView, projection, deltaSeconds);
        refreshBindings();
    }

    private void refreshBindings() {
        for (Binding binding : bindings.values()) {
            frameState.write(binding.descriptor, binding.type, binding.buffer);
        }
    }

    private void logCatalogOnce() {
        if (catalogLogged) {
            return;
        }
        catalogLogged = true;
        long live = UniformRegistry.catalog().stream()
                .filter(descriptor -> descriptor.availability() == UniformRegistry.Availability.LIVE)
                .count();
        long defaulted = UniformRegistry.catalog().size() - live;
        ChimeraMod.LOGGER.info("[chimera] uniform catalog active: live={}, defaulted={}",
                live, defaulted);
    }

    private record UniformKey(String name, String type) {}

    private record Binding(UniformRegistry.UniformDescriptor descriptor,
                           String type, MappedBuffer buffer) {}

    private static MappedBuffer createBuffer(String type) {
        MappedBuffer buffer = new MappedBuffer(bufferSize(type));
        int count = UniformRegistry.pipelineCount(type);
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
}
