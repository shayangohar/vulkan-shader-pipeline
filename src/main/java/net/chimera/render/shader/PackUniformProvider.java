package net.chimera.render.shader;

import net.chimera.ChimeraMod;
import net.chimera.shaderpack.PackBlendPlan;
import net.chimera.shaderpack.PackDirectives;
import net.chimera.shaderpack.PackItemIdResolver;
import net.chimera.shaderpack.PackRenderingPhase;
import net.chimera.shaderpack.PackRuntimeSettings;
import net.chimera.shaderpack.UniformRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** The game time the PER_TICK bindings were last written for. */
    private long lastTick = Long.MIN_VALUE;
    private boolean catalogLogged;

    private PackUniformProvider() {}

    public static PackUniformProvider shared() {
        return INSTANCE;
    }

    /**
     * The stable value buffer of pack uniform {@code name} of type {@code type}, for a pipeline
     * being built (HostPipelineLayout), or null when the Chimera catalog does not serve it.
     */
    public Supplier<MappedBuffer> supplier(String name, String type) {
        UniformRegistry.UniformDescriptor descriptor = resolveUniform(name, type);
        if (descriptor == null) return null;
        Binding binding = binding(descriptor, name, type);
        logCatalogOnce();
        return () -> binding.buffer;
    }

    /**
     * The shared binding of a uniform, made with its current value when first asked for. An
     * existing one keeps what it holds: a PER_TICK value changes only when the tick does, however
     * many pipelines are built in between (Iris's ProgramUniforms update by tick).
     */
    private Binding binding(UniformRegistry.UniformDescriptor descriptor, String name, String type) {
        return bindings.computeIfAbsent(new UniformKey(name, type), key -> {
            Binding created = new Binding(descriptor, type, createBuffer(type));
            frameState.write(descriptor, type, created.buffer);
            return created;
        });
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
        setRenderingPhase(PackRenderingPhase.NONE);
        INSTANCE.updateFrame(camera, partialTick, modelView, projection);
    }

    /** The frame's celestial frame, shared with the shadow matrix builder. */
    public static Vector3f currentSunLightVector() {
        return INSTANCE.frameState.sunLightVector();
    }

    /**
     * Debug: every bound float uniform whose current value holds NaN or infinity, as
     * {@code name[component]=value}, sorted. These are the exact values the pack programs read.
     */
    public static List<String> nonFiniteUniforms() {
        List<String> result = new ArrayList<>();
        for (Binding binding : INSTANCE.bindings.values()) {
            if (!"float".equals(UniformRegistry.pipelineType(binding.type))
                    && !"mat4".equals(binding.type)) {
                continue;
            }
            int count = UniformRegistry.pipelineCount(binding.type);
            for (int i = 0; i < count; i++) {
                float value = binding.buffer.getFloat(i * 4);
                if (!Float.isFinite(value)) {
                    result.add(binding.descriptor.name() + "[" + i + "]=" + value);
                    break;
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    /** The shadow caster rotation derived with the celestial uniforms this frame. */
    public static Matrix4f currentShadowLightRotation() {
        return INSTANCE.frameState.shadowLightRotation();
    }

    /** Installs the pack's sun path for the celestial frame and the shadow matrix. */
    public static void installSunPath(float rotationDegrees, float offsetDegrees) {
        INSTANCE.frameState.installSunPath(rotationDegrees, offsetDegrees);
    }

    public static float currentSunPathRotation() { return INSTANCE.frameState.sunPathRotation(); }

    /** Iris SystemTimeUniforms.TIMER: the pack clock, which keeps running while paused. */
    public static float currentFrameTimeCounter() { return INSTANCE.frameState.frameTimeCounter(); }

    /**
     * The camera's gbufferProjection x gbufferModelView (GL clip range, camera-relative), written
     * to dest: the view shadow culling keeps casters for, as Iris's frustum reads it.
     */
    public static Matrix4f currentCameraViewProjection(Matrix4f dest) {
        return INSTANCE.frameState.cameraViewProjection(dest);
    }

    /**
     * Records the density of the fog FogRenderer is setting up (Iris fogMode, fogDensity) and
     * serves it to the draws that follow, as Iris's dynamic fog uniforms read it at each bind.
     */
    public static void captureFogDensity(Camera camera) {
        IrisWorldState.captureFogDensity(camera);
        INSTANCE.rewrite(FOG_DENSITY_KEYS);
    }

    /** Records the shape of the fog FogRenderer has set up (Iris fogShape) for the draws that follow. */
    public static void captureFogShape() {
        IrisWorldState.captureFogShape();
        INSTANCE.rewrite(FOG_SHAPE_KEYS);
    }

    /**
     * Serves the fog range RenderSystem now holds to the draws that follow, as Iris's fogStart and
     * fogEnd follow each RenderSystem.setShaderFogStart and setShaderFogEnd (ChimeraFogRangeMixin).
     * Read once a frame, a pack's terrain saw the Float.MAX_VALUE start FogRenderer.setupNoFog left
     * at the previous frame's end, and its linear fog came out zero in the Nether and underwater.
     */
    public static void updateFogRange(float start, float end) {
        if (INSTANCE.frameState.setFogRange(start, end)) INSTANCE.rewrite(FOG_RANGE_KEYS);
    }

    /**
     * Serves this frame's fog colour, as FogRenderer.setupColor left it, to every pack draw from the
     * world clear on: Iris reads its per-frame fogColor at a program's first use in the frame, which
     * follows setupColor. Read at renderLevel's head, it was the previous frame's colour.
     */
    public static void latchFogColor(float[] color) {
        if (INSTANCE.frameState.setFogColor(color)) INSTANCE.rewrite(FOG_COLOR_KEYS);
    }

    private static final List<UniformKey> FOG_COLOR_KEYS = List.of(new UniformKey("fogColor", "vec3"),
            new UniformKey("fogColor", "vec4"), new UniformKey("FogColor", "vec4"));
    private static final List<UniformKey> FOG_DENSITY_KEYS = List.of(
            new UniformKey("fogMode", "int"), new UniformKey("fogDensity", "float"));
    private static final List<UniformKey> FOG_SHAPE_KEYS = List.of(new UniformKey("fogShape", "int"));
    private static final List<UniformKey> FOG_RANGE_KEYS = List.of(new UniformKey("fogStart", "float"),
            new UniformKey("FogStart", "float"), new UniformKey("fogEnd", "float"), new UniformKey("FogEnd", "float"));

    /** Rewrites the bindings of the given names from the frame state. */
    private void rewrite(List<UniformKey> keys) {
        for (UniformKey key : keys) {
            Binding binding = bindings.get(key);
            if (binding != null) frameState.write(binding.descriptor, binding.type, binding.buffer);
        }
    }

    /** Publishes the exact shadow state used by the shadow render. */
    public static void updateShadowState(Matrix4f modelView, Matrix4f projection) {
        INSTANCE.frameState.updateShadow(modelView, projection);
        INSTANCE.refreshBindings(false);
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

    /** Sets the entityId the next host-layout lane draw reads, rewriting its binding only on change. */
    public static void updateDrawEntityId(int entityId) {
        if (!INSTANCE.frameState.setHostEntityId(entityId)) return;
        Binding binding = INSTANCE.bindings.get(new UniformKey(UniformRegistry.HOST_ENTITY_ID, "int"));
        if (binding != null) {
            INSTANCE.frameState.write(binding.descriptor, binding.type, binding.buffer);
        }
    }

    /** Returns the enclosing phase for an exception-safe draw-scope restore. */
    public static PackRenderingPhase setRenderingPhase(PackRenderingPhase phase) {
        var previous = INSTANCE.frameState.setRenderingPhase(phase);
        Binding binding = INSTANCE.bindings.get(new UniformKey("renderStage", "int"));
        if (binding != null && previous != phase) {
            INSTANCE.frameState.write(binding.descriptor, binding.type, binding.buffer);
        }
        return previous;
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

    /**
     * Serves the next draw Iris's per-draw texture and blend values (IrisDrawState): the size of
     * the texture on unit 0 and the draw's blend, the blend the program reports in place of the
     * host's (PackPipelines.blendFuncOverride) or the host draw's. Only changed bindings are rewritten.
     */
    public static void updateDrawState(Pipeline pipeline) {
        // Without a pack session no binding reads them.
        if (INSTANCE.bindings.isEmpty()) return;
        VulkanImage unit0 = VTextureSelector.getBoundTexture(0);
        if (!INSTANCE.frameState.setDrawState(unit0 == null ? 0 : unit0.width, unit0 == null ? 0 : unit0.height,
                IrisDrawState.programBlend(pipeline), PipelineState.blendInfo)) return;
        INSTANCE.rewrite(DRAW_STATE_KEYS);
    }

    private static final List<UniformKey> DRAW_STATE_KEYS = List.of(
            new UniformKey("gtextureSize", "ivec2"), new UniformKey("blendFunc", "ivec4"));

    /** Records a pack pipeline's {@code blend.<program>} directive for its draws' blendFunc. */
    public static void registerProgramBlend(Pipeline pipeline, PackBlendPlan.Mode programBlend) {
        IrisDrawState.registerProgramBlend(pipeline, programBlend);
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
        INSTANCE.refreshBindings(false);
    }

    /** Installs the immutable runtime settings for the active pack session. */
    /**
     * The pack's item.properties ids and oldHandLight for the held-item uniforms; an empty
     * resolver when no pack is installed.
     */
    public static void installHeldItems(PackItemIdResolver items, boolean oldHandLight) {
        INSTANCE.frameState.installHeldItems(items, oldHandLight);
    }

    public static void installRuntimeSettings(PackRuntimeSettings settings) {
        INSTANCE.runtimeSettings = settings == null ? PackRuntimeSettings.empty() : settings;
        INSTANCE.loggedValueFailures.clear();
        INSTANCE.frameState.installRuntimeSettings(INSTANCE.runtimeSettings);
        INSTANCE.refreshBindings(true);
    }

    /** The pack's scalar directives: the wetness and eye-brightness smoothing half-lives. */
    public static void installDirectives(PackDirectives directives) {
        INSTANCE.frameState.installDirectives(directives);
    }

    /** Drops frame history when the pack session is destroyed or reloaded. */
    public static void resetSession() {
        setRenderingPhase(PackRenderingPhase.NONE);
        INSTANCE.lastFrameNanos = 0L;
        INSTANCE.lastTick = Long.MIN_VALUE;
        INSTANCE.runtimeSettings = PackRuntimeSettings.empty();
        INSTANCE.frameState.installRuntimeSettings(INSTANCE.runtimeSettings);
        INSTANCE.frameState.installDirectives(PackDirectives.DEFAULTS);
        INSTANCE.frameState.resetSession();
        INSTANCE.refreshBindings(true);
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
            // A PER_TICK value reads what its binding holds this tick, as a compute program's
            // ProgramUniforms refresh it; the binding is made the first time a program asks.
            Binding held = UniformRegistry.perTick(descriptor)
                    ? INSTANCE.binding(descriptor, declaration.name(), declaration.glslType()) : null;
            if (held != null) {
                MemoryUtil.memCopy(held.buffer.ptr, mapped.ptr, Math.min(sizes[index], held.buffer.buffer.capacity()));
            } else {
                INSTANCE.frameState.write(descriptor, declaration.glslType(), mapped);
            }
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
                : PackFrameState.quantizedFrameSecondsForTest(now - lastFrameNanos);
        lastFrameNanos = now;
        frameState.begin(minecraft, camera, partialTick, modelView, projection, deltaSeconds);
        logDerivedValueFailures();
        // Iris ProgramUniforms.getCurrentTick: the level's game time, 0 without a level.
        long tick = level == null ? 0L : level.getGameTime();
        refreshBindings(tick != lastTick);
        lastTick = tick;
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

    /**
     * Rewrites every binding from the frame state; a PER_TICK value only with {@code newTick}, so it
     * holds the value Iris last refreshed it to (UniformRegistry.perTick).
     */
    private void refreshBindings(boolean newTick) {
        for (Binding binding : bindings.values()) {
            if (refreshes(binding.descriptor, newTick)) {
                frameState.write(binding.descriptor, binding.type, binding.buffer);
            }
        }
    }

    /** Whether a frame-state refresh rewrites a binding: every binding but a PER_TICK one between ticks. */
    static boolean refreshes(UniformRegistry.UniformDescriptor descriptor, boolean newTick) {
        return newTick || !UniformRegistry.perTick(descriptor);
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
            case "float", "int", "bool" -> 4;
            case "vec2", "ivec2" -> 8;
            case "vec3", "ivec3" -> 16;
            case "vec4", "ivec4" -> 16;
            case "mat4" -> 64;
            default -> throw new IllegalArgumentException("unsupported pack uniform type: " + type);
        };
    }
}
