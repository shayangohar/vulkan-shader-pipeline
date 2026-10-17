package net.chimera.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import net.chimera.shaderpack.BiomeIds;
import net.chimera.shaderpack.PackItemIdResolver;
import net.chimera.shaderpack.PackRenderingPhase;
import net.chimera.shaderpack.PackRuntimeSettings;
import net.chimera.shaderpack.UniformRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntToDoubleFunction;

import static net.chimera.render.shader.FrameValues.componentOf;
import static net.chimera.render.shader.FrameValues.floats;
import static net.chimera.render.shader.FrameValues.ints;
import static net.chimera.render.shader.FrameValues.matrix;
import static net.chimera.render.shader.FrameValues.put;
import static net.chimera.render.shader.FrameValues.scalar;
import static net.chimera.render.shader.FrameValues.vector;
import static net.chimera.render.shader.FrameValues.writeDefault;

/**
 * One render-thread snapshot shared by every pack supplier in a frame.
 *
 * <p>This class owns no Vulkan resources. It only owns stable scalar, vector,
 * matrix, and smoothing storage. The provider writes that storage to stable
 * MappedBuffers after the snapshot is complete.</p>
 */
final class PackFrameState {
    static final int FRAME_COUNTER_WRAP = 720720;
    static final float FRAME_TIME_COUNTER_WRAP = 3600.0f;
    static final double CAMERA_WALK_RANGE = 30000.0;
    static final double CAMERA_TELEPORT_RANGE = 1000.0;

    private final Matrix4f modelView = new Matrix4f();
    private final Matrix4f rasterProjection = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f previousModelView = new Matrix4f();
    private final Matrix4f previousProjection = new Matrix4f();
    private final Matrix4f modelViewInverse = new Matrix4f();
    private final Matrix4f projectionInverse = new Matrix4f();
    private final Matrix4f previousModelViewInverse = new Matrix4f();
    private final Matrix4f previousProjectionInverse = new Matrix4f();
    private final Matrix4f mvp = new Matrix4f();
    private final Matrix4f textureMatrix = new Matrix4f();
    private final Matrix4f shadowModelView = new Matrix4f();
    private final Matrix4f shadowProjection = new Matrix4f();
    private final Matrix4f shadowModelViewInverse = new Matrix4f();
    private final Matrix4f shadowProjectionInverse = new Matrix4f();
    private final float[] matrixScratch = new float[16];

    private final CameraOrigin cameraOrigin = new CameraOrigin();
    private final Vector3f eyePosition = new Vector3f();
    private final Vector3f relativeEyePosition = new Vector3f();
    private final Vector3f playerLookVector = new Vector3f();
    private final Vector3f sunPosition = new Vector3f(0.0f, 1.0f, 0.0f);
    private final Vector3f moonPosition = new Vector3f(0.0f, -1.0f, 0.0f);
    private final Vector3f shadowLightPosition = new Vector3f(0.0f, 1.0f, 0.0f);
    private final Vector3f upPosition = new Vector3f(0.0f, 1.0f, 0.0f);
    private final Vector3f skyColor = new Vector3f(0.0f, 0.0f, 0.0f);
    private final Vector3f sunLightVector = new Vector3f(0.0f, 1.0f, 0.0f);
    /** A lightning bolt the level renders, from the camera; w is 1 while there is one. */
    private final float[] lightningBoltPosition = new float[4];
    private final IrisPlayerState irisPlayer = new IrisPlayerState();
    private final HeldItemState heldItems = new HeldItemState();
    /** The shadow caster rotation for the same light, as Iris builds it; its Z is sunLightVector. */
    private final Matrix4f shadowLightRotation = new Matrix4f().rotateX((float) Math.toRadians(90.0));
    private final float[] fogColor = new float[4];
    private final float[] shaderColor = new float[4];
    private final float[] lightDirection0 = new float[3];
    private final float[] lightDirection1 = new float[3];

    private final int[] eyeBrightness = new int[2];
    // The atlas the current draw samples: the block atlas per frame, per draw for entities.
    private final int[] atlasSize = new int[2];
    private final int[] eyeBrightnessSmooth = new int[2];
    private final int[] cameraPositionInt = new int[3];
    private final int[] previousCameraPositionInt = new int[3];
    private final float[] cameraPositionFract = new float[3];
    private final float[] previousCameraPositionFract = new float[3];
    private PackRuntimeSettings runtimeSettings = PackRuntimeSettings.empty();
    private PackRuntimeSettings.Session session = runtimeSettings.newSession();

    private ClientLevel lastLevel;
    private boolean viewInitialized;
    private int frameCounter;
    private float frameTime;
    private float frameTimeCounter;
    private int worldTime;
    private int worldDay;
    private int moonPhase;
    private float rainStrength;
    private float thunderStrength;
    private float sunAngle01;
    private float shadowAngle01;
    private float sunPathRotation;
    private float sunPathOffset;
    private float screenBrightness;
    private float blindness;
    private float darknessLightFactor;
    private float darknessFactor;
    private float nightVision;
    private float wetness;
    private float eyeAltitude;
    private int biomeId;
    private int biomeCategory;
    private int biomePrecipitation;
    private float viewWidth;
    private float viewHeight;
    private float aspectRatio;
    private float farPlane;
    private float fogStart;
    private float fogEnd;
    private float alphaCutout;
    // Per draw: the host entity pipeline's ALPHA_CUTOUT, 0 where it has none.
    private float entityAlphaReference;
    // Per frame: the glint strength option, vanilla Globals.GlintAlpha (Iris isGlint gl_Color).
    private float glintAlpha = 1.0F;
    private PackRenderingPhase renderingPhase = PackRenderingPhase.NONE;
    private int bedrockLevel;
    private float screenSizeWidth;
    private float screenSizeHeight;
    private int lastViewportWidth;
    private int lastViewportHeight;
    private int isEyeInWater;
    private int dimension;
    private int heightLimit;
    private int logicalHeightLimit;
    private int hasCeiling;
    private int hasSkylight;
    private float ambientLight;
    private float cloudHeight;
    private float biomeTemperature;
    private float rainfall;
    private float nearPlane = 0.05f;
    private boolean smoothingStarted;
    private float wetnessValue;
    private float eyeBrightnessBlockSmooth;
    private float eyeBrightnessSkySmooth;

    /**
     * One frame value as both readers see it: a uniform buffer receives its components in the
     * declared GLSL type, and a custom-value expression reads them one at a time (a matrix's
     * elements column-major, as GLSL and Iris number them).
     */
    record FrameValue(int width, IntToDoubleFunction component) {}

    /** Every engine value the frame serves, by uniform name; aliases share one entry. */
    private final Map<String, FrameValue> values = buildValues();

    private Map<String, FrameValue> buildValues() {
        Map<String, FrameValue> table = new HashMap<>();
        scalar(table, () -> frameTime, "frameTime");
        scalar(table, () -> frameTimeCounter, "frameTimeCounter");
        scalar(table, () -> frameCounter, "frameCounter");
        scalar(table, () -> renderingPhase.ordinal(), "renderStage");
        scalar(table, () -> worldTime, "worldTime");
        scalar(table, () -> worldDay, "worldDay");
        scalar(table, () -> moonPhase, "moonPhase");
        scalar(table, () -> rainStrength, "rainStrength");
        scalar(table, () -> thunderStrength, "thunderStrength");
        scalar(table, () -> wetness, "wetness");
        scalar(table, () -> sunAngle01, "sunAngle");
        scalar(table, () -> shadowAngle01, "shadowAngle");
        scalar(table, () -> dimension, "dimension");
        scalar(table, () -> bedrockLevel, "bedrockLevel");
        scalar(table, () -> heightLimit, "heightLimit");
        scalar(table, () -> logicalHeightLimit, "logicalHeightLimit");
        scalar(table, () -> hasCeiling, "hasCeiling");
        scalar(table, () -> hasSkylight, "hasSkylight");
        scalar(table, () -> ambientLight, "ambientLight");
        scalar(table, () -> cloudHeight, "cloudHeight");
        scalar(table, () -> biomeTemperature, "temperature");
        scalar(table, () -> rainfall, "rainfall");
        scalar(table, () -> viewWidth, "viewWidth");
        scalar(table, () -> viewHeight, "viewHeight");
        scalar(table, () -> aspectRatio, "aspectRatio");
        scalar(table, () -> nearPlane, "near");
        scalar(table, () -> farPlane, "far");
        scalar(table, () -> blindness, "blindness", "blindFactor");
        scalar(table, () -> darknessFactor, "darknessFactor");
        scalar(table, () -> darknessLightFactor, "darknessLightFactor");
        scalar(table, () -> nightVision, "nightVision");
        scalar(table, () -> screenBrightness, "screenBrightness");
        scalar(table, () -> isEyeInWater, "isEyeInWater");
        scalar(table, () -> eyeAltitude, "eyeAltitude");
        scalar(table, () -> biomeId, "biome");
        scalar(table, () -> biomeCategory, "biome_category");
        scalar(table, () -> biomePrecipitation, "biome_precipitation");
        scalar(table, () -> fogStart, "fogStart", "FogStart");
        scalar(table, () -> fogEnd, "fogEnd", "FogEnd");
        scalar(table, () -> alphaCutout, "AlphaCutout");
        scalar(table, () -> entityAlphaReference, UniformRegistry.ENTITY_ALPHA_REFERENCE);
        scalar(table, () -> glintAlpha, UniformRegistry.GLINT_ALPHA);

        vector(table, 3, component -> componentOf(cameraOrigin.x, cameraOrigin.y, cameraOrigin.z, component),
                "cameraPosition");
        vector(table, 3, component -> componentOf(cameraOrigin.previousX, cameraOrigin.previousY,
                cameraOrigin.previousZ, component), "previousCameraPosition");
        ints(table, cameraPositionInt, "cameraPositionInt");
        ints(table, previousCameraPositionInt, "previousCameraPositionInt");
        floats(table, cameraPositionFract, "cameraPositionFract");
        floats(table, previousCameraPositionFract, "previousCameraPositionFract");
        vector(table, eyePosition, "eyePosition");
        vector(table, relativeEyePosition, "relativeEyePosition");
        vector(table, playerLookVector, "playerLookVector");
        vector(table, sunPosition, "sunPosition");
        vector(table, moonPosition, "moonPosition");
        vector(table, shadowLightPosition, "shadowLightPosition");
        vector(table, upPosition, "upPosition");
        vector(table, skyColor, "skyColor");
        floats(table, lightningBoltPosition, "lightningBoltPosition");
        ints(table, eyeBrightness, "eyeBrightness");
        ints(table, eyeBrightnessSmooth, "eyeBrightnessSmooth");
        ints(table, atlasSize, "atlasSize");
        floats(table, fogColor, "fogColor", "FogColor");
        vector(table, 2, component -> component == 0 ? screenSizeWidth : screenSizeHeight,
                "screenSize", "ScreenSize");
        floats(table, shaderColor, "ColorModulator");
        floats(table, lightDirection0, "Light0_Direction");
        floats(table, lightDirection1, "Light1_Direction");

        matrix(table, modelView, "gbufferModelView", "ModelViewMat");
        matrix(table, modelViewInverse, "gbufferModelViewInverse");
        matrix(table, previousModelView, "gbufferPreviousModelView");
        // DHCompat without Distant Horizons: the gbuffer projection and its derivations.
        matrix(table, projection, "gbufferProjection", "dhProjection");
        matrix(table, projectionInverse, "gbufferProjectionInverse", "dhProjectionInverse");
        matrix(table, previousProjection, "gbufferPreviousProjection", "dhPreviousProjection");
        matrix(table, rasterProjection, "ProjMat");
        matrix(table, mvp, "MVP");
        matrix(table, textureMatrix, "TextureMat");
        matrix(table, shadowModelView, "shadowModelView");
        matrix(table, shadowModelViewInverse, "shadowModelViewInverse");
        matrix(table, shadowProjection, "shadowProjection");
        matrix(table, shadowProjectionInverse, "shadowProjectionInverse");
        return Map.copyOf(table);
    }

    /** Returns true and resets temporal state when the level identity changes. */
    boolean prepareLevel(ClientLevel level) {
        if (level == this.lastLevel) {
            return false;
        }
        this.lastLevel = level;
        resetTemporalState();
        return true;
    }

    void resetSession() {
        this.lastLevel = null;
        this.session.reset();
        resetTemporalState();
    }

    void installRuntimeSettings(PackRuntimeSettings settings) {
        this.runtimeSettings = settings == null ? PackRuntimeSettings.empty() : settings;
        this.session = this.runtimeSettings.newSession();
    }

    void installHeldItems(PackItemIdResolver items, boolean oldHandLight) {
        heldItems.install(items, oldHandLight);
    }

    void begin(Minecraft minecraft, Camera camera, float partialTick,
               Matrix4f capturedModelView, Matrix4f capturedProjection, float deltaSeconds) {
        advanceFrameTime(deltaSeconds);
        this.frameCounter = (this.frameCounter + 1) % FRAME_COUNTER_WRAP;

        boolean viewportChanged = captureWindow(minecraft);
        int[] blockAtlas = AtlasSizes.blocks();
        setAtlasSize(blockAtlas[0], blockAtlas[1]);
        captureMatrices(capturedModelView, capturedProjection);
        captureHostState();

        if (camera == null || minecraft == null) {
            clearCameraState();
            irisPlayer.clear();
            heldItems.clear();
            clearWorldState();
            evaluateCustomValues();
            return;
        }

        var position = camera.getPosition();
        advanceCamera(position.x, position.y, position.z, viewportChanged);
        irisPlayer.capture(minecraft, frameTime);
        heldItems.capture(minecraft);

        Entity cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity == null) {
            this.eyePosition.set((float) position.x, (float) position.y, (float) position.z);
            this.playerLookVector.zero();
            this.eyeBrightness[0] = 0;
            this.eyeBrightness[1] = 0;
            this.blindness = 0.0f;
            this.darknessFactor = 0.0f;
        } else {
            var eye = cameraEntity.getEyePosition(partialTick);
            this.eyePosition.set((float) eye.x, (float) eye.y, (float) eye.z);
            var look = cameraEntity instanceof LivingEntity living
                    ? living.getViewVector(partialTick) : cameraEntity.getForward();
            this.playerLookVector.set((float) look.x, (float) look.y, (float) look.z);
            if (cameraEntity instanceof LivingEntity living) {
                this.blindness = blindness(living);
                this.darknessFactor = darknessFactor(living, partialTick);
            } else {
                this.blindness = 0.0f;
                this.darknessFactor = 0.0f;
            }
            captureEyeBrightness(minecraft.level, eye.x, eye.y, eye.z);
        }
        this.relativeEyePosition.set((float) position.x - this.eyePosition.x,
                (float) position.y - this.eyePosition.y,
                (float) position.z - this.eyePosition.z);
        this.isEyeInWater = eyeInWater(camera.getFluidInCamera(),
                minecraft.player != null && minecraft.player.isSpectator());
        this.nightVision = nightVision(minecraft, cameraEntity, partialTick);
        this.darknessLightFactor = darknessLightFactor(minecraft, partialTick);
        this.screenBrightness = minecraft.options.gamma().get().floatValue();
        this.glintAlpha = minecraft.options.glintStrength().get().floatValue();

        captureWorld(minecraft.level, minecraft, camera, partialTick);
        evaluateCustomValues();
    }

    void updateShadow(Matrix4f modelView, Matrix4f projection) {
        if (modelView != null) {
            this.shadowModelView.set(modelView);
            invertOrIdentity(this.shadowModelView, this.shadowModelViewInverse);
        }
        if (projection != null) {
            this.shadowProjection.set(projection);
            invertOrIdentity(this.shadowProjection, this.shadowProjectionInverse);
        }
    }

    /** The pack's sun path, which the shadow matrix and the celestial frame both follow. */
    void installSunPath(float rotationDegrees, float offsetDegrees) {
        this.sunPathRotation = rotationDegrees;
        this.sunPathOffset = offsetDegrees;
    }

    float sunPathRotation() { return sunPathRotation; }
    float frameTimeCounter() { return frameTimeCounter; }

    /** World-space light direction the shadow matrix looks along, derived with the uniforms. */
    Vector3f sunLightVector() {
        return sunLightVector;
    }

    Matrix4f shadowLightRotation() {
        return shadowLightRotation;
    }

    /** Writes the value a uniform names into its buffer slot, in the type the pack declared it. */
    void write(UniformRegistry.UniformDescriptor descriptor, String type, MappedBuffer target) {
        String name = descriptor.sourceKey();
        boolean integral = type.startsWith("i") || type.equals("bool");
        if (name.startsWith("custom:")) {
            int index = runtimeSettings.indexOf(descriptor.name());
            int width = runtimeSettings.widthOf(descriptor.name());
            for (int lane = 0; lane < Math.max(width, 1); lane++) {
                put(target, lane, index < 0 ? 0.0 : session.values()[index + lane], integral);
            }
            return;
        }
        if (IrisPlayerState.NAMES.contains(name)) {
            irisPlayer.write(name, type, target);
            return;
        }
        if (HeldItemState.NAMES.contains(name)) {
            heldItems.write(name, type, target);
            return;
        }
        FrameValue value = values.get(name);
        if (value == null) {
            writeDefault(descriptor.defaultPolicy(), type, target);
            return;
        }
        int count = Math.min(value.width(), UniformRegistry.pipelineCount(type));
        for (int component = 0; component < count; component++) {
            put(target, component, value.component().applyAsDouble(component), integral);
        }
    }

    /**
     * The value, or one component of it, the host serves under a name: the same table a uniform
     * buffer is written from. Custom values are resolved before this, so everything here is a
     * canonical engine input -- which is exactly why a pack may not author one.
     */
    double value(String name, int component) {
        if (IrisPlayerState.NAMES.contains(name)) {
            return irisPlayer.value(name, component);
        }
        if (HeldItemState.NAMES.contains(name)) {
            return heldItems.value(name, component);
        }
        FrameValue value = values.get(name);
        if (value == null) {
            return component < 0 ? 0.0 : Double.NaN;
        }
        int index = Math.max(component, 0);
        return index < value.width() ? value.component().applyAsDouble(index) : Double.NaN;
    }

    static int wrapFrameCounter(int value) {
        return Math.floorMod(value, FRAME_COUNTER_WRAP);
    }
    static float wrapFrameTimeCounter(float value) {
        return value >= FRAME_TIME_COUNTER_WRAP ? 0.0f : value;
    }

    void advanceFrameTime(float deltaSeconds) {
        // Iris exposes the whole elapsed frame, including stalls, and resets at one hour.
        this.frameTime = Math.max(deltaSeconds, 0.0f);
        this.frameTimeCounter = wrapFrameTimeCounter(this.frameTimeCounter + this.frameTime);
    }

    static int irisWorldTimeForTest(long dayTime, boolean fixedTime, boolean netherOrEnd) {
        return fixedTime && !netherOrEnd ? 0 : (int) Math.floorMod(dayTime, 24000L);
    }

    static int irisWorldDayForTest(long dayTime) {
        return (int) Math.floorDiv(dayTime, 24000L);
    }

    static float quantizedFrameSecondsForTest(long elapsedNanos) {
        return (float) (Math.max(elapsedNanos, 0L) / 1_000_000L) / 1000.0f;
    }

    static double cameraShift(double value, double previous) {
        if (Math.abs(value) > CAMERA_WALK_RANGE
                || Math.abs(value - previous) > CAMERA_TELEPORT_RANGE) {
            return -(value - (value % CAMERA_WALK_RANGE));
        }
        return 0.0;
    }

    static float smooth(float current, float target, float halfLifeUp,
                        float halfLifeDown, float deltaSeconds) {
        if (deltaSeconds <= 0.0f) {
            return current;
        }
        float halfLife = target > current ? halfLifeUp : halfLifeDown;
        if (halfLife <= 0.0f) {
            return target;
        }
        float factor = 1.0f - (float) Math.exp(-Math.log(2.0)
                * deltaSeconds / (halfLife * 0.1f));
        return current + (target - current) * factor;
    }

    static void invertOrIdentityForTest(Matrix4f source, Matrix4f destination) {
        invertOrIdentity(source, destination);
    }

    private void captureMatrices(Matrix4f capturedModelView, Matrix4f capturedProjection) {
        rotateView(capturedModelView, capturedProjection);
        updateViewMatrices();
        MappedBuffer textureMatrixBuffer = VRenderSystem.getTextureMatrix();
        for (int index = 0; index < 16; index++) {
            matrixScratch[index] = textureMatrixBuffer.getFloat(index * 4);
        }
        textureMatrix.set(matrixScratch);
    }

    private void updateViewMatrices() {
        invertOrIdentity(modelView, modelViewInverse);
        invertOrIdentity(projection, projectionInverse);
        invertOrIdentity(previousModelView, previousModelViewInverse);
        invertOrIdentity(previousProjection, previousProjectionInverse);
        mvp.set(rasterProjection).mul(modelView);
    }

    /**
     * Advances current matrices and rotates the previous pair. First frame
     * seeds previous from current; later frames shift current into
     * previous before capturing. Reloads and resizes restart the sequence
     * by clearing {@code viewInitialized} through the temporal reset.
     */
    private void rotateView(Matrix4f capturedModelView, Matrix4f capturedProjection) {
        if (capturedModelView == null || capturedProjection == null) {
            if (!viewInitialized) {
                modelView.identity();
                rasterProjection.identity();
                projection.identity();
                previousModelView.identity();
                previousProjection.identity();
            }
            return;
        }
        if (!viewInitialized) {
            modelView.set(capturedModelView);
            rasterProjection.set(capturedProjection);
            convertProjectionToLegacyClipRange(rasterProjection, projection);
            previousModelView.set(capturedModelView);
            previousProjection.set(projection);
        } else {
            previousModelView.set(modelView);
            previousProjection.set(projection);
            modelView.set(capturedModelView);
            rasterProjection.set(capturedProjection);
            convertProjectionToLegacyClipRange(rasterProjection, projection);
        }
        viewInitialized = true;
    }

    /** Maps host forward-Z [0,1] clip depth to the legacy pack [-1,1] clip range. */
    private static void convertProjectionToLegacyClipRange(Matrix4f rendered, Matrix4f destination) {
        destination.set(rendered);
        destination.m02(2.0f * rendered.m02() - rendered.m03());
        destination.m12(2.0f * rendered.m12() - rendered.m13());
        destination.m22(2.0f * rendered.m22() - rendered.m23());
        destination.m32(2.0f * rendered.m32() - rendered.m33());
    }

    /** Test-only entry for the previous-matrix rotation; skips host render state. */
    void rotateViewForTest(Matrix4f capturedModelView, Matrix4f capturedProjection) {
        rotateView(capturedModelView, capturedProjection);
        updateViewMatrices();
    }

    Matrix4f modelView() {
        return new Matrix4f(modelView);
    }

    /** gbufferProjection x gbufferModelView, in the legacy GL clip range packs read. */
    Matrix4f cameraViewProjection(Matrix4f dest) {
        return dest.set(projection).mul(modelView);
    }

    Matrix4f projectionForTest() {
        return new Matrix4f(projection);
    }

    Matrix4f rasterProjectionForTest() {
        return new Matrix4f(rasterProjection);
    }

    Matrix4f projectionInverseForTest() {
        return new Matrix4f(projectionInverse);
    }

    Matrix4f mvpForTest() {
        return new Matrix4f(mvp);
    }

    Matrix4f previousModelViewForTest() {
        return new Matrix4f(previousModelView);
    }

    Matrix4f previousProjectionForTest() {
        return new Matrix4f(previousProjection);
    }

    private boolean captureWindow(Minecraft minecraft) {
        int width = minecraft == null ? 1 : Math.max(minecraft.getWindow().getWidth(), 1);
        int height = minecraft == null ? 1 : Math.max(minecraft.getWindow().getHeight(), 1);
        return updateViewport(width, height);
    }

    /** Updates viewport values and reseeds reprojection history after a resize. */
    boolean updateViewport(int width, int height) {
        width = Math.max(width, 1);
        height = Math.max(height, 1);
        boolean resized = lastViewportWidth != 0
                && (lastViewportWidth != width || lastViewportHeight != height);
        lastViewportWidth = width;
        lastViewportHeight = height;
        if (resized) {
            viewInitialized = false;
        }
        screenSizeWidth = width;
        screenSizeHeight = height;
        viewWidth = screenSizeWidth;
        viewHeight = screenSizeHeight;
        aspectRatio = screenSizeWidth / screenSizeHeight;
        return resized;
    }

    private void captureHostState() {
        MappedBuffer fog = VRenderSystem.getShaderFogColor();
        for (int i = 0; i < 4; i++) {
            fogColor[i] = fog.getFloat(i * 4);
            shaderColor[i] = VRenderSystem.getShaderColor().getFloat(i * 4);
        }
        MappedBuffer direction0 = VRenderSystem.lightDirection0;
        MappedBuffer direction1 = VRenderSystem.lightDirection1;
        for (int i = 0; i < 3; i++) {
            lightDirection0[i] = direction0.getFloat(i * 4);
            lightDirection1[i] = direction1.getFloat(i * 4);
        }
        alphaCutout = VRenderSystem.alphaCutout;
        fogStart = RenderSystem.getShaderFogStart();
        fogEnd = RenderSystem.getShaderFogEnd();
    }

    private void captureWorld(ClientLevel level, Minecraft minecraft, Camera camera,
                              float partialTick) {
        if (level == null) {
            clearWorldState();
            return;
        }
        long dayTime = level.getDayTime();
        boolean netherOrEnd = level.dimension() == Level.NETHER
                || level.dimension() == Level.END;
        boolean fixedTime = level.dimensionType().hasFixedTime();
        worldTime = irisWorldTimeForTest(dayTime, fixedTime, netherOrEnd);
        worldDay = irisWorldDayForTest(dayTime);
        moonPhase = Math.floorMod(worldDay, 8);
        rainStrength = clamp(level.getRainLevel(partialTick), 0.0f, 1.0f);
        thunderStrength = clamp(level.getThunderLevel(partialTick), 0.0f, 1.0f);
        captureCelestial(level, camera, partialTick);
        captureLightningBolt(level, camera, partialTick);
        dimension = dimensionOrdinal(level);
        bedrockLevel = level.dimensionType().minY();
        heightLimit = level.dimensionType().height();
        logicalHeightLimit = level.dimensionType().logicalHeight();
        hasCeiling = level.dimensionType().hasCeiling() ? 1 : 0;
        hasSkylight = level.dimensionType().hasSkyLight() ? 1 : 0;
        ambientLight = level.dimensionType().ambientLight();
        cloudHeight = level.dimension() == Level.OVERWORLD
                ? 192.0f : 0.0f;
        BlockPos biomePos = BlockPos.containing(cameraOrigin.rawX, cameraOrigin.rawY, cameraOrigin.rawZ);
        var biomeHolder = level.getBiome(biomePos);
        var biome = biomeHolder.value();
        biomeCategory = BiomeIds.category(biomeHolder).ordinal();
        biomeTemperature = biome.getBaseTemperature();
        rainfall = biome.hasPrecipitation() ? 1.0f : 0.0f;
        eyeAltitude = cameraOrigin.y;
        biomeId = BiomeIds.id(biomeKey(level, biomePos));
        biomePrecipitation = precipitation(biome, biomePos);
        farPlane = Math.max(minecraft.options.getEffectiveRenderDistance(), 1) * 16.0f;
        if (!smoothingStarted) {
            wetnessValue = rainStrength;
            eyeBrightnessBlockSmooth = eyeBrightness[0];
            eyeBrightnessSkySmooth = eyeBrightness[1];
            smoothingStarted = true;
        } else {
            wetnessValue = smooth(wetnessValue, rainStrength,
                    runtimeSettings.wetnessRiseHalfLife(),
                    runtimeSettings.wetnessFallHalfLife(), frameTime);
        }
        wetness = wetnessValue;
        if (frameTime > 0.0f) {
            eyeBrightnessBlockSmooth = smooth(eyeBrightnessBlockSmooth,
                    eyeBrightness[0], runtimeSettings.eyeBrightnessHalfLife(),
                    runtimeSettings.eyeBrightnessHalfLife(), frameTime);
            eyeBrightnessSkySmooth = smooth(eyeBrightnessSkySmooth,
                    eyeBrightness[1], runtimeSettings.eyeBrightnessHalfLife(),
                    runtimeSettings.eyeBrightnessHalfLife(), frameTime);
        }
        eyeBrightnessSmooth[0] = (int) eyeBrightnessBlockSmooth;
        eyeBrightnessSmooth[1] = (int) eyeBrightnessSkySmooth;
    }

    /** Iris IrisExclusiveUniforms.lightningBoltPosition: the first bolt the level renders, or zero. */
    private void captureLightningBolt(ClientLevel level, Camera camera, float partialTick) {
        Arrays.fill(lightningBoltPosition, 0.0f);
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof LightningBolt bolt) {
                Vec3 at = bolt.getPosition(partialTick).subtract(camera.getPosition());
                lightningBoltPosition[0] = (float) at.x;
                lightningBoltPosition[1] = (float) at.y;
                lightningBoltPosition[2] = (float) at.z;
                lightningBoltPosition[3] = 1.0f;
                return;
            }
        }
    }

    /**
     * Samples the level's sky for this frame and derives the celestial frame from it: the sun
     * turns with the time of day as LevelRenderer.renderSky turns it, the moon opposite, and the
     * sky colour is the one the sky draws with.
     */
    private void captureCelestial(ClientLevel level, Camera camera, float partialTick) {
        float sunAngle = level.getTimeOfDay(partialTick) * 360.0F;
        var sky = level.getSkyColor(camera.getPosition(), partialTick);
        deriveCelestial(sunAngle, sunAngle + 180.0F, packedRgb(sky.x, sky.y, sky.z));
    }

    private static int packedRgb(double red, double green, double blue) {
        return (channel(red) << 16) | (channel(green) << 8) | channel(blue);
    }

    private static int channel(double value) {
        return Math.round(clamp((float) value, 0.0F, 1.0F) * 255.0F);
    }

    /** Test seam: the same derivation from explicit host samples. */
    void setCelestialForTest(float sunAngleDegrees, float moonAngleDegrees, int skyColorPacked) {
        deriveCelestial(sunAngleDegrees, moonAngleDegrees, skyColorPacked);
    }

    /**
     * One source for the pack-facing angles and positions, the up direction, and the shadow light
     * vector, so a caster and a receiver cannot disagree about where the light is.
     */
    private void deriveCelestial(float sunAngleDegrees, float moonAngleDegrees,
                                 int skyColorPacked) {
        float lightAngleDegrees = CelestialSnapshot.isDay(sunAngleDegrees)
                ? sunAngleDegrees : moonAngleDegrees;

        sunAngle01 = CelestialSnapshot.angle01(sunAngleDegrees);
        shadowAngle01 = CelestialSnapshot.angle01(lightAngleDegrees);
        CelestialSnapshot.position(modelView, sunPathRotation, sunPathOffset,
                sunAngleDegrees, sunPosition);
        CelestialSnapshot.position(modelView, sunPathRotation, sunPathOffset,
                moonAngleDegrees, moonPosition);
        CelestialSnapshot.position(modelView, sunPathRotation, sunPathOffset,
                lightAngleDegrees, shadowLightPosition);
        CelestialSnapshot.upPosition(modelView, upPosition);
        CelestialSnapshot.lightVector(sunPathRotation, sunPathOffset,
                lightAngleDegrees, sunLightVector);
        CelestialSnapshot.shadowRotation(sunPathRotation, sunPathOffset,
                lightAngleDegrees, shadowLightRotation);
        CelestialSnapshot.skyColor(skyColorPacked, skyColor);
    }

    /** The camera biome's key, which is the name the constant table numbers. */
    private static ResourceLocation biomeKey(ClientLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.location()).orElse(null);
    }

    /** The pack's precipitation input: none, rain or snow, as the packs compare it. */
    private static int precipitation(Biome biome, BlockPos pos) {
        return switch (biome.getPrecipitationAt(pos)) {
            case NONE -> 0;
            case RAIN -> 1;
            case SNOW -> 2;
        };
    }

    private void captureEyeBrightness(ClientLevel level, double x, double y, double z) {
        if (level == null) {
            eyeBrightness[0] = 0;
            eyeBrightness[1] = 0;
            return;
        }
        BlockPos block = BlockPos.containing(x, y, z);
        eyeBrightness[0] = level.getBrightness(LightLayer.BLOCK, block) * 16;
        eyeBrightness[1] = level.getBrightness(LightLayer.SKY, block) * 16;
    }

    private void evaluateCustomValues() {
        runtimeSettings.evaluate(this::value, session, frameTime);
    }

    /** Declarations whose expression produced a non-finite result this session, with context. */
    Map<String, String> valueFailures() {
        return session.failures();
    }

    /** Returns true when the atlas size served to the next draw changed. */
    boolean setAtlasSize(int width, int height) {
        if (atlasSize[0] == width && atlasSize[1] == height) return false;
        atlasSize[0] = width;
        atlasSize[1] = height;
        return true;
    }

    /** Returns true when the per-draw entity alpha reference changed. */
    boolean setEntityAlphaReference(float reference) {
        if (Float.compare(this.entityAlphaReference, reference) == 0) return false;
        this.entityAlphaReference = reference;
        return true;
    }

    PackRenderingPhase setRenderingPhase(PackRenderingPhase phase) {
        var previous = renderingPhase;
        renderingPhase = Objects.requireNonNull(phase);
        return previous;
    }

    void clearShadowState() {
        shadowModelView.identity();
        shadowProjection.identity();
        shadowModelViewInverse.identity();
        shadowProjectionInverse.identity();
    }

    private static int dimensionOrdinal(ClientLevel level) {
        if (level.dimension() == Level.OVERWORLD) {
            return 0;
        }
        if (level.dimension() == Level.NETHER) {
            return -1;
        }
        if (level.dimension() == Level.END) {
            return 1;
        }
        return 2;
    }

    private float nightVision(Minecraft minecraft, Entity cameraEntity, float partialTick) {
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

    private float darknessLightFactor(Minecraft minecraft, float partialTick) {
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
    private static float darknessFactor(LivingEntity living, float partialTick) {
        var effect = living.getEffect(MobEffects.DARKNESS);
        return effect == null ? 0.0F : effect.getBlendFactor(living, partialTick);
    }

    private static float blindness(LivingEntity living) {
        var effect = living.getEffect(MobEffects.BLINDNESS);
        if (effect == null) {
            return 0.0f;
        }
        return effect.isInfiniteDuration() ? 1.0f
                : clamp(effect.getDuration() / 20.0f, 0.0f, 1.0f);
    }

    private static int eyeInWater(FogType type, boolean spectator) {
        if (type == FogType.WATER) {
            return 1;
        }
        if (!spectator && type == FogType.LAVA) {
            return 2;
        }
        return type == FogType.POWDER_SNOW ? 3 : 0;
    }

    private void writeCameraPosition() {
        cameraPositionInt[0] = (int) Math.floor(cameraOrigin.rawX);
        cameraPositionInt[1] = (int) Math.floor(cameraOrigin.rawY);
        cameraPositionInt[2] = (int) Math.floor(cameraOrigin.rawZ);
        previousCameraPositionInt[0] = (int) Math.floor(cameraOrigin.previousRawX);
        previousCameraPositionInt[1] = (int) Math.floor(cameraOrigin.previousRawY);
        previousCameraPositionInt[2] = (int) Math.floor(cameraOrigin.previousRawZ);
        cameraPositionFract[0] = (float) (cameraOrigin.rawX - Math.floor(cameraOrigin.rawX));
        cameraPositionFract[1] = (float) (cameraOrigin.rawY - Math.floor(cameraOrigin.rawY));
        cameraPositionFract[2] = (float) (cameraOrigin.rawZ - Math.floor(cameraOrigin.rawZ));
        previousCameraPositionFract[0] = (float) (cameraOrigin.previousRawX
                - Math.floor(cameraOrigin.previousRawX));
        previousCameraPositionFract[1] = (float) (cameraOrigin.previousRawY
                - Math.floor(cameraOrigin.previousRawY));
        previousCameraPositionFract[2] = (float) (cameraOrigin.previousRawZ
                - Math.floor(cameraOrigin.previousRawZ));
    }

    private void advanceCamera(double x, double y, double z, boolean reseedHistory) {
        cameraOrigin.advance(x, y, z);
        if (reseedHistory) {
            cameraOrigin.seedPreviousFromCurrent();
        }
        writeCameraPosition();
    }

    void advanceCameraForTest(double x, double y, double z, boolean reseedHistory) {
        advanceCamera(x, y, z, reseedHistory);
    }

    private void clearCameraState() {
        cameraOrigin.reset();
        eyePosition.zero();
        relativeEyePosition.zero();
        playerLookVector.zero();
        cameraPositionInt[0] = cameraPositionInt[1] = cameraPositionInt[2] = 0;
        previousCameraPositionInt[0] = previousCameraPositionInt[1] = previousCameraPositionInt[2] = 0;
        for (int i = 0; i < 3; i++) {
            cameraPositionFract[i] = 0.0f;
            previousCameraPositionFract[i] = 0.0f;
        }
        eyeBrightness[0] = 0;
        eyeBrightness[1] = 0;
        eyeBrightnessSmooth[0] = 0;
        isEyeInWater = 0;
    }

    private void clearWorldState() {
        worldTime = 0;
        worldDay = 0;
        moonPhase = 0;
        rainStrength = 0.0f;
        thunderStrength = 0.0f;
        sunAngle01 = 0.0f;
        shadowAngle01 = 0.0f;
        farPlane = 0.0f;
        bedrockLevel = 0;
        dimension = 0;
        heightLimit = 0;
        logicalHeightLimit = 0;
        hasCeiling = 0;
        hasSkylight = 0;
        ambientLight = 0.0f;
        cloudHeight = 0.0f;
        biomeTemperature = 0.0f;
        rainfall = 0.0f;
        eyeAltitude = 0.0f;
        biomeId = 0;
        biomeCategory = 0;
        biomePrecipitation = 0;
        sunPosition.set(0.0f, 1.0f, 0.0f);
        moonPosition.set(0.0f, -1.0f, 0.0f);
        shadowLightPosition.set(0.0f, 1.0f, 0.0f);
        upPosition.set(0.0f, 1.0f, 0.0f);
        skyColor.set(0.0f, 0.0f, 0.0f);
        sunLightVector.set(0.0f, 1.0f, 0.0f);
        shadowLightRotation.identity().rotateX((float) Math.toRadians(90.0));
        Arrays.fill(lightningBoltPosition, 0.0f);
    }

    private void resetTemporalState() {
        cameraOrigin.reset();
        frameCounter = 0;
        frameTime = 0.0f;
        frameTimeCounter = 0.0f;
        viewInitialized = false;
        wetnessValue = 0.0f;
        eyeBrightnessBlockSmooth = 0.0f;
        eyeBrightnessSkySmooth = 0.0f;
        smoothingStarted = false;
        modelView.identity();
        rasterProjection.identity();
        projection.identity();
        previousModelView.identity();
        previousProjection.identity();
        modelViewInverse.identity();
        projectionInverse.identity();
        previousModelViewInverse.identity();
        previousProjectionInverse.identity();
        mvp.identity();
        textureMatrix.identity();
        lastViewportWidth = 0;
        lastViewportHeight = 0;
        shadowModelView.identity();
        shadowProjection.identity();
        shadowModelViewInverse.identity();
        shadowProjectionInverse.identity();
        clearCameraState();
        clearWorldState();
    }

    private static void invertOrIdentity(Matrix4f source, Matrix4f destination) {
        float determinant = source.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0e-7f) {
            destination.identity();
            return;
        }
        source.invert(destination);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
