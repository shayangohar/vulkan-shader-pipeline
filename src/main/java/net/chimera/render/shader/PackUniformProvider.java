package net.chimera.render.shader;

import net.chimera.ChimeraMod;
import net.chimera.shaderpack.UniformRegistry;
import net.chimera.shaderpack.PackRuntimeSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.ByteBuffer;
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
    private final Set<String> loggedValueFailures = new HashSet<>();
    private final PackFrameState frameState = new PackFrameState();
    private PackRuntimeSettings runtimeSettings = PackRuntimeSettings.empty();
    private long lastFrameNanos;
    private final PackFrameClockLog frameClockLog = new PackFrameClockLog();
    private boolean catalogLogged;

    private PackUniformProvider() {}

    public static PackUniformProvider shared() {
        return INSTANCE;
    }

    /** Called by Pipeline.Builder while it applies the generated UBO config. */
    public Supplier<MappedBuffer> supplier(Uniform.Info info) {
        UniformRegistry.UniformDescriptor descriptor = resolveUniform(info.name, info.type);
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

    /**
     * The catalog entry a declared name resolves to, the session's pack-authored values included.
     * A refused declaration resolves to nothing, so nothing can serve a substitute value.
     */
    public static UniformRegistry.UniformDescriptor resolveUniform(String name, String glslType) {
        return UniformRegistry.resolve(name, glslType, INSTANCE.runtimeSettings.customDescriptors());
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

    /** The frame's celestial frame, shared with the shadow matrix builder. */
    public static Vector3f currentSunLightVector() {
        return INSTANCE.frameState.sunLightVector();
    }

    /** Installs the pack's sun path for the celestial frame and the shadow matrix. */
    public static void installSunPath(float rotationDegrees, float offsetDegrees) {
        INSTANCE.frameState.installSunPath(rotationDegrees, offsetDegrees);
    }

    /** Publishes the exact shadow state used by the shadow render. */
    public static void updateShadowState(Matrix4f modelView, Matrix4f projection) {
        INSTANCE.frameState.updateShadow(modelView, projection);
        INSTANCE.refreshBindings();
    }

    /**
     * Sets the alpha-test reference for the next entity-family draw or shadow
     * terrain layer. The pipeline copies its uniform buffers per draw, so only
     * this one binding is rewritten, and only when the reference changes.
     */
    public static void updateDrawAlphaReference(float reference) {
        if (!INSTANCE.frameState.setEntityAlphaReference(reference)) return;
        Binding binding = INSTANCE.bindings.get(
                new UniformKey(UniformRegistry.ENTITY_ALPHA_REFERENCE, "float"));
        if (binding != null) {
            INSTANCE.frameState.write(binding.descriptor, binding.type, binding.buffer);
        }
    }

    /**
     * Serves the next host-routed draw the size of the atlas it samples, or
     * zero for a plain texture, as Iris answers {@code atlasSize} from
     * texture unit 0. {@link #restoreFrameAtlasSize} puts the block atlas
     * back once that draw's uniforms are uploaded.
     */
    public static void updateDrawAtlasSize(VulkanImage sampled) {
        int[] size = AtlasSizes.of(sampled);
        INSTANCE.writeAtlasSize(size[0], size[1]);
    }

    /** Restores the block atlas size that terrain and shadow draws read. */
    public static void restoreFrameAtlasSize() {
        int[] size = AtlasSizes.blocks();
        INSTANCE.writeAtlasSize(size[0], size[1]);
    }

    private void writeAtlasSize(int width, int height) {
        if (!frameState.setAtlasSize(width, height)) return;
        Binding binding = bindings.get(new UniformKey("atlasSize", "ivec2"));
        if (binding != null) {
            frameState.write(binding.descriptor, binding.type, binding.buffer);
        }
    }

    /** Prevents stale pack matrices from describing a host-produced or invalid shadow image. */
    public static void clearShadowState() {
        INSTANCE.frameState.clearShadowState();
        INSTANCE.refreshBindings();
    }

    /** Installs the immutable runtime settings for the active pack session. */
    public static void installRuntimeSettings(PackRuntimeSettings settings) {
        INSTANCE.runtimeSettings = settings == null ? PackRuntimeSettings.empty() : settings;
        INSTANCE.loggedValueFailures.clear();
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

    /** Writes the same captured frame values into a compute uniform block. */
    public static void writeComputeUniforms(
            List<UniformRegistry.UniformDeclaration> declarations,
            int[] offsets,
            int[] sizes,
            ByteBuffer target
    ) {
        if (declarations == null || target == null) return;
        for (int index = 0; index < declarations.size(); index++) {
            if (offsets == null || sizes == null || index >= offsets.length || index >= sizes.length) {
                break;
            }
            UniformRegistry.UniformDeclaration declaration = declarations.get(index);
            UniformRegistry.UniformDescriptor descriptor =
                    resolveUniform(declaration.name(), declaration.glslType());
            if (descriptor == null) continue;
            ByteBuffer duplicate = target.duplicate();
            duplicate.position(offsets[index]);
            duplicate.limit(offsets[index] + sizes[index]);
            MappedBuffer mapped = MappedBuffer.createFromBuffer(duplicate.slice());
            INSTANCE.frameState.write(descriptor, declaration.glslType(), mapped);
        }
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
                : Math.min(Math.max(PackFrameState.frameSeconds(
                now - lastFrameNanos), 0.0f), 0.25f);
        long elapsedNanos = lastFrameNanos == 0L ? 0L : now - lastFrameNanos;
        lastFrameNanos = now;
        frameState.begin(minecraft, camera, partialTick, modelView, projection, deltaSeconds);
        if (PackFrameClockLog.ENABLED && elapsedNanos > 0L) {
            frameClockLog.record(now, elapsedNanos, deltaSeconds, frameState.frameTimeCounter());
        }
        logDerivedValueFailures();
        refreshBindings();
    }

    /**
     * Reports each declaration whose expression produced a non-finite result, once per session,
     * with the expression and the inputs that produced it. The value itself holds its last finite
     * result, so the frame is diagnosable rather than silently substituted.
     */
    private void logDerivedValueFailures() {
        for (Map.Entry<String, String> failure : frameState.valueFailures().entrySet()) {
            if (!loggedValueFailures.add(failure.getKey())) {
                continue;
            }
            ChimeraMod.LOGGER.warn("[chimera] pack value {} produced a non-finite result; "
                    + "holding its last finite value. {}", failure.getKey(), failure.getValue());
        }
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
