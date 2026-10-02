package net.chimera.render.shader;

import net.chimera.shaderpack.BiomeIds;
import net.chimera.shaderpack.UniformRegistry;
import net.chimera.shaderpack.PackRuntimeSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.core.BlockPos;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

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
    private final IrisPlayerState irisPlayer = new IrisPlayerState();
    /** The shadow caster rotation for the same light, as Iris builds it; its Z is sunLightVector. */
    private final Matrix4f shadowLightRotation = new Matrix4f().rotateX((float) Math.toRadians(90.0));
    private final float[] fogColor = new float[4];
    private final float[] shaderColor = new float[4];
    private final float[] lightDirection0 = new float[3];
    private final float[] lightDirection1 = new float[3];
    private final float[] textureSize = new float[2];
    private final float[] texelSize = new float[2];

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
    private int isElytraFlying;
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
    private float fogEnvironmentalStart;
    private float fogEnvironmentalEnd;
    private float fogSkyEnd;
    private float fogCloudsEnd;
    private float alphaCutout;
    // Per draw: the host entity pipeline's ALPHA_CUTOUT, 0 where it has none.
    private float entityAlphaReference;
    private int currentTime;
    private int bedrockLevel;
    private float screenSizeWidth;
    private float screenSizeHeight;
    private int lastViewportWidth;
    private int lastViewportHeight;
    private int isEyeInWater;
    private int dimension;
    private int heightLimit;
    private int logicalHeightLimit;
    private int seaLevel;
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

    void begin(Minecraft minecraft, Camera camera, float partialTick,
               Matrix4f capturedModelView, Matrix4f capturedProjection, float deltaSeconds) {
        this.frameTime = clamp(deltaSeconds, 0.0f, 0.25f);
        this.frameTimeCounter = wrapped(this.frameTimeCounter + this.frameTime,
                FRAME_TIME_COUNTER_WRAP);
        this.frameCounter = (this.frameCounter + 1) % FRAME_COUNTER_WRAP;

        boolean viewportChanged = captureWindow(minecraft);
        int[] blockAtlas = AtlasSizes.blocks();
        setAtlasSize(blockAtlas[0], blockAtlas[1]);
        captureMatrices(capturedModelView, capturedProjection);
        captureHostState();

        if (camera == null || minecraft == null) {
            clearCameraState();
            irisPlayer.clear();
            clearWorldState();
            evaluateCustomValues();
            return;
        }

        var position = camera.position();
        advanceCamera(position.x, position.y, position.z, viewportChanged);
        irisPlayer.capture(minecraft, partialTick, position.x, position.y, position.z, frameTime);

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
                this.darknessFactor = living.getEffectBlendFactor(MobEffects.DARKNESS, partialTick);
                this.isElytraFlying = living.isFallFlying() ? 1 : 0;
            } else {
                this.blindness = 0.0f;
                this.darknessFactor = 0.0f;
                this.isElytraFlying = 0;
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

    /** World-space light direction the shadow matrix looks along, derived with the uniforms. */
    Vector3f sunLightVector() {
        return sunLightVector;
    }

    Matrix4f shadowLightRotation() {
        return shadowLightRotation;
    }

    void write(UniformRegistry.UniformDescriptor descriptor, String type, MappedBuffer target) {
        if (descriptor.sourceKey().startsWith("custom:")) {
            int index = runtimeSettings.indexOf(descriptor.name());
            int width = runtimeSettings.widthOf(descriptor.name());
            for (int lane = 0; lane < Math.max(width, 1); lane++) {
                float value = index < 0 ? 0.0f : session.values()[index + lane];
                if (type.startsWith("i") || type.equals("bool")) {
                    target.putInt(lane * 4, (int) value);
                } else {
                    target.putFloat(lane * 4, value);
                }
            }
            return;
        }
        if (IrisPlayerState.NAMES.contains(descriptor.sourceKey())) {
            irisPlayer.write(descriptor.sourceKey(), type, target);
            return;
        }
        switch (descriptor.sourceKey()) {
            case "cameraPosition" -> writeVec3(target, cameraOrigin.x, cameraOrigin.y, cameraOrigin.z);
            case "previousCameraPosition" -> writeVec3(target,
                    cameraOrigin.previousX, cameraOrigin.previousY, cameraOrigin.previousZ);
            case "cameraPositionInt" -> writeInts(target, cameraPositionInt);
            case "previousCameraPositionInt" -> writeInts(target, previousCameraPositionInt);
            case "cameraPositionFract" -> writeVec3(target, cameraPositionFract);
            case "previousCameraPositionFract" -> writeVec3(target, previousCameraPositionFract);
            case "eyePosition" -> writeVec3(target, eyePosition.x, eyePosition.y, eyePosition.z);
            case "relativeEyePosition" -> writeVec3(target,
                    relativeEyePosition.x, relativeEyePosition.y, relativeEyePosition.z);
            case "playerLookVector" -> writeVec3(target,
                    playerLookVector.x, playerLookVector.y, playerLookVector.z);
            case "worldTime" -> target.putInt(0, worldTime);
            case "worldDay" -> target.putInt(0, worldDay);
            case "moonPhase" -> target.putInt(0, moonPhase);
            case "dimension" -> target.putInt(0, dimension);
            case "heightLimit" -> target.putInt(0, heightLimit);
            case "logicalHeightLimit" -> target.putInt(0, logicalHeightLimit);
            case "seaLevel" -> target.putInt(0, seaLevel);
            case "hasCeiling" -> target.putInt(0, hasCeiling);
            case "hasSkylight" -> target.putInt(0, hasSkylight);
            case "ambientLight" -> target.putFloat(0, ambientLight);
            case "cloudHeight" -> target.putFloat(0, cloudHeight);
            case "temperature" -> target.putFloat(0, biomeTemperature);
            case "rainfall" -> target.putFloat(0, rainfall);
            case "frameCounter" -> target.putInt(0, frameCounter);
            case "CurrentTime" -> target.putInt(0, currentTime);
            case "isEyeInWater" -> target.putInt(0, isEyeInWater);
            case "isElytraFlying" -> target.putInt(0, isElytraFlying);
            case "eyeBrightness" -> writeInts(target, eyeBrightness);
            case "atlasSize" -> writeInts(target, atlasSize);
            case "eyeBrightnessSmooth" -> writeInts(target, eyeBrightnessSmooth);
            case "frameTime" -> target.putFloat(0, frameTime);
            case "frameTimeCounter" -> target.putFloat(0, frameTimeCounter);
            case "rainStrength" -> target.putFloat(0, rainStrength);
            case "thunderStrength" -> target.putFloat(0, thunderStrength);
            case "wetness" -> target.putFloat(0, wetness);
            case "sunAngle" -> target.putFloat(0, sunAngle01);
            case "shadowAngle" -> target.putFloat(0, shadowAngle01);
            case "sunPosition" -> writeVec3(target, sunPosition.x, sunPosition.y, sunPosition.z);
            case "moonPosition" -> writeVec3(target, moonPosition.x, moonPosition.y, moonPosition.z);
            case "upPosition" -> writeVec3(target, upPosition.x, upPosition.y, upPosition.z);
            case "skyColor" -> writeVec3(target, skyColor.x, skyColor.y, skyColor.z);
            case "shadowLightPosition" -> writeVec3(target,
                    shadowLightPosition.x, shadowLightPosition.y, shadowLightPosition.z);
            case "shadowModelView" -> writeMatrix(target, shadowModelView);
            case "shadowProjection" -> writeMatrix(target, shadowProjection);
            case "shadowModelViewInverse" -> writeMatrix(target, shadowModelViewInverse);
            case "shadowProjectionInverse" -> writeMatrix(target, shadowProjectionInverse);
            case "gbufferModelView", "ModelViewMat" -> writeMatrix(target, modelView);
            case "gbufferModelViewInverse" -> writeMatrix(target, modelViewInverse);
            case "gbufferPreviousModelView" -> writeMatrix(target, previousModelView);
            case "gbufferPreviousProjection" -> writeMatrix(target, previousProjection);
            case "gbufferProjection" -> writeMatrix(target, projection);
            // DHCompat without Distant Horizons: the gbuffer projection and its derivations.
            case "dhProjection" -> writeMatrix(target, projection);
            case "dhProjectionInverse" -> writeMatrix(target, projectionInverse);
            case "dhPreviousProjection" -> writeMatrix(target, previousProjection);
            case "ProjMat" -> writeMatrix(target, rasterProjection);
            case "gbufferProjectionInverse" -> writeMatrix(target, projectionInverse);
            case "MVP" -> writeMatrix(target, mvp);
            case "TextureMat" -> writeMatrix(target, textureMatrix);
            case "FogColor", "fogColor" -> writeFloatArray(target, fogColor,
                    type.equals("vec3") ? 3 : 4);
            case "FogStart", "fogStart" -> target.putFloat(0, fogStart);
            case "FogEnd", "fogEnd" -> target.putFloat(0, fogEnd);
            case "FogEnvironmentalStart" -> target.putFloat(0, fogEnvironmentalStart);
            case "FogEnvironmentalEnd" -> target.putFloat(0, fogEnvironmentalEnd);
            case "FogRenderDistanceStart" -> target.putFloat(0, fogStart);
            case "FogRenderDistanceEnd" -> target.putFloat(0, fogEnd);
            case "FogSkyEnd" -> target.putFloat(0, fogSkyEnd);
            case "FogCloudsEnd" -> target.putFloat(0, fogCloudsEnd);
            case "ScreenSize", "screenSize" -> {
                target.putFloat(0, screenSizeWidth);
                target.putFloat(4, screenSizeHeight);
            }
            case "TextureSize", "textureSize" -> {
                target.putInt(0, (int) textureSize[0]);
                target.putInt(4, (int) textureSize[1]);
            }
            case "TexelSize", "texelSize" -> {
                target.putFloat(0, texelSize[0]);
                target.putFloat(4, texelSize[1]);
            }
            case "viewWidth" -> target.putFloat(0, viewWidth);
            case "viewHeight" -> target.putFloat(0, viewHeight);
            case "aspectRatio" -> target.putFloat(0, aspectRatio);
            case "near" -> target.putFloat(0, nearPlane);
            case "far" -> target.putFloat(0, farPlane);
            case "blindFactor", "blindness" -> target.putFloat(0, blindness);
            case "darknessFactor" -> target.putFloat(0, darknessFactor);
            case "darknessLightFactor" -> target.putFloat(0, darknessLightFactor);
            case "nightVision" -> target.putFloat(0, nightVision);
            case "screenBrightness" -> target.putFloat(0, screenBrightness);
            case "bedrockLevel" -> target.putInt(0, bedrockLevel);
            case "eyeAltitude" -> target.putFloat(0, eyeAltitude);
            case "biome" -> target.putInt(0, biomeId);
            case "biome_category" -> target.putInt(0, biomeCategory);
            case "biome_precipitation" -> target.putInt(0, biomePrecipitation);
            case "AlphaCutout" -> target.putFloat(0, alphaCutout);
            case UniformRegistry.ENTITY_ALPHA_REFERENCE -> target.putFloat(0, entityAlphaReference);
            case "ColorModulator" -> writeFloatArray(target, shaderColor, 4);
            case "Light0_Direction" -> writeVec3(target, lightDirection0);
            case "Light1_Direction" -> writeVec3(target, lightDirection1);
            case "UseRgss" -> target.putInt(0, 0);
            default -> writeDefault(descriptor.defaultPolicy(), type, target);
        }
    }

    static int wrapFrameCounter(int value) {
        return Math.floorMod(value, FRAME_COUNTER_WRAP);
    }
    static float wrapFrameTimeCounter(float value) {
        return wrapped(value, FRAME_TIME_COUNTER_WRAP);
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

    Matrix4f modelViewForTest() {
        return new Matrix4f(modelView);
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
        MappedBuffer texture = VRenderSystem.getTextureSize();
        textureSize[0] = texture.getInt(0);
        textureSize[1] = texture.getInt(4);
        MappedBuffer texel = VRenderSystem.getTexelSize();
        texelSize[0] = texel.getFloat(0);
        texelSize[1] = texel.getFloat(4);
        MappedBuffer direction0 = VRenderSystem.lightDirection0;
        MappedBuffer direction1 = VRenderSystem.lightDirection1;
        for (int i = 0; i < 3; i++) {
            lightDirection0[i] = direction0.getFloat(i * 4);
            lightDirection1[i] = direction1.getFloat(i * 4);
        }
        alphaCutout = VRenderSystem.alphaCutout;
        currentTime = VRenderSystem.getCurrentTime();
        FogData fogData = VRenderSystem.getFogData();
        // Legacy pack fog is environmental fog, not the render-distance fade.
        fogStart = fogData == null ? 0.0f : fogData.environmentalStart;
        fogEnd = fogData == null ? 0.0f : fogData.environmentalEnd;
        fogEnvironmentalStart = fogData == null ? 0.0f : fogData.environmentalStart;
        fogEnvironmentalEnd = fogData == null ? 0.0f : fogData.environmentalEnd;
        fogSkyEnd = fogData == null ? 0.0f : fogData.skyEnd;
        fogCloudsEnd = fogData == null ? 0.0f : fogData.cloudEnd;
    }

    private void captureWorld(ClientLevel level, Minecraft minecraft, Camera camera,
                              float partialTick) {
        if (level == null) {
            clearWorldState();
            return;
        }
        long dayTime = level.getDayTime();
        boolean netherOrEnd = level.dimension() == net.minecraft.world.level.Level.NETHER
                || level.dimension() == net.minecraft.world.level.Level.END;
        boolean fixedTime = level.dimensionType().hasFixedTime();
        worldTime = irisWorldTimeForTest(dayTime, fixedTime, netherOrEnd);
        worldDay = irisWorldDayForTest(dayTime);
        moonPhase = (int) Math.floorMod(worldDay, 8);
        rainStrength = clamp(level.getRainLevel(partialTick), 0.0f, 1.0f);
        thunderStrength = clamp(level.getThunderLevel(partialTick), 0.0f, 1.0f);
        captureCelestial(camera, partialTick);
        dimension = dimensionOrdinal(level);
        bedrockLevel = level.dimensionType().minY();
        heightLimit = level.dimensionType().height();
        logicalHeightLimit = level.dimensionType().logicalHeight();
        seaLevel = level.getSeaLevel();
        hasCeiling = level.dimensionType().hasCeiling() ? 1 : 0;
        hasSkylight = level.dimensionType().hasSkyLight() ? 1 : 0;
        ambientLight = level.dimensionType().ambientLight();
        cloudHeight = level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                ? 192.0f : 0.0f;
        BlockPos biomePos = BlockPos.containing(cameraOrigin.rawX, cameraOrigin.rawY, cameraOrigin.rawZ);
        var biomeHolder = level.getBiome(biomePos);
        var biome = biomeHolder.value();
        biomeCategory = BiomeIds.category(biomeHolder).ordinal();
        biomeTemperature = biome.getBaseTemperature();
        rainfall = biome.hasPrecipitation() ? 1.0f : 0.0f;
        eyeAltitude = (float) cameraOrigin.y;
        biomeId = BiomeIds.id(biomeKey(level, biomePos));
        biomePrecipitation = precipitation(biome, biomePos, level.getSeaLevel());
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

    /**
     * Samples the host's own sky state for this frame and derives the celestial frame from it.
     * The angles come from the attribute probe the host ticks for its own sky and fog, so a
     * dimension, a datapack or an option that moves the sky moves the shadows with it.
     */
    private void captureCelestial(Camera camera, float partialTick) {
        var probe = camera.attributeProbe();
        deriveCelestial(probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick),
                probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTick),
                probe.getValue(EnvironmentAttributes.SKY_COLOR, partialTick));
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
    private static net.minecraft.resources.Identifier biomeKey(ClientLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.identifier()).orElse(null);
    }

    /** The pack's precipitation input: none, rain or snow, as the packs compare it. */
    private static int precipitation(net.minecraft.world.level.biome.Biome biome, BlockPos pos,
                                     int seaLevel) {
        return switch (biome.getPrecipitationAt(pos, seaLevel)) {
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
        runtimeSettings.evaluate(this::engineValue, session, frameTime);
    }

    /** Declarations whose expression produced a non-finite result this session, with context. */
    java.util.Map<String, String> valueFailures() {
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

    void clearShadowState() {
        shadowModelView.identity();
        shadowProjection.identity();
        shadowModelViewInverse.identity();
        shadowProjectionInverse.identity();
    }

    /**
     * The host's own answer for a name, or one component of it. Custom values are resolved before
     * this, so everything here is a canonical engine input -- which is exactly why a pack may not
     * author one.
     */
    private double engineValue(String name, int component) {
        if (IrisPlayerState.NAMES.contains(name)) {
            return irisPlayer.value(name, component);
        }
        if (component < 0) {
            return scalarValue(name);
        }
        return switch (name) {
            case "eyeBrightness" -> componentOf(eyeBrightness, component);
            case "atlasSize" -> componentOf(atlasSize, component);
            case "eyeBrightnessSmooth" -> componentOf(eyeBrightnessSmooth, component);
            case "cameraPosition" ->
                    componentOf(cameraOrigin.x, cameraOrigin.y, cameraOrigin.z, component);
            case "previousCameraPosition" -> componentOf(cameraOrigin.previousX,
                    cameraOrigin.previousY, cameraOrigin.previousZ, component);
            case "cameraPositionFract" -> componentOf(cameraPositionFract, component);
            case "previousCameraPositionFract" -> componentOf(previousCameraPositionFract, component);
            case "cameraPositionInt" -> componentOf(cameraPositionInt, component);
            case "previousCameraPositionInt" -> componentOf(previousCameraPositionInt, component);
            case "eyePosition" -> componentOf(eyePosition, component);
            case "relativeEyePosition" -> componentOf(relativeEyePosition, component);
            case "playerLookVector" -> componentOf(playerLookVector, component);
            case "sunPosition" -> componentOf(sunPosition, component);
            case "moonPosition" -> componentOf(moonPosition, component);
            case "shadowLightPosition" -> componentOf(shadowLightPosition, component);
            case "upPosition" -> componentOf(upPosition, component);
            case "skyColor" -> componentOf(skyColor, component);
            // Defaulted: no bolt, as the uniform itself serves.
            case "lightningBoltPosition" -> 0.0;
            case "gbufferModelView" -> elementOf(modelView, component);
            case "gbufferModelViewInverse" -> elementOf(modelViewInverse, component);
            case "gbufferPreviousModelView" -> elementOf(previousModelView, component);
            case "gbufferProjection" -> elementOf(projection, component);
            case "gbufferProjectionInverse" -> elementOf(projectionInverse, component);
            case "dhProjection" -> elementOf(projection, component);
            case "dhProjectionInverse" -> elementOf(projectionInverse, component);
            case "dhPreviousProjection" -> elementOf(previousProjection, component);
            case "gbufferPreviousProjection" -> elementOf(previousProjection, component);
            case "shadowModelView" -> elementOf(shadowModelView, component);
            case "shadowModelViewInverse" -> elementOf(shadowModelViewInverse, component);
            case "shadowProjection" -> elementOf(shadowProjection, component);
            case "shadowProjectionInverse" -> elementOf(shadowProjectionInverse, component);
            // Unreachable: the plan rejects a component of a name with no component source.
            default -> Double.NaN;
        };
    }

    /** One element of a matrix, numbered column-major as GLSL and Iris's accessors read it. */
    private static double elementOf(Matrix4f matrix, int component) {
        return component < 16 ? matrix.get(component / 4, component % 4) : Double.NaN;
    }

    private static double componentOf(int[] values, int component) {
        return component < values.length ? values[component] : Double.NaN;
    }

    private static double componentOf(float[] values, int component) {
        return component < values.length ? values[component] : Double.NaN;
    }

    private static double componentOf(Vector3f value, int component) {
        return componentOf(value.x, value.y, value.z, component);
    }

    private static double componentOf(float x, float y, float z, int component) {
        return component == 0 ? x : component == 1 ? y : z;
    }

    private double scalarValue(String name) {
        return switch (name) {
            case "frameTime" -> frameTime;
            case "frameTimeCounter" -> frameTimeCounter;
            case "frameCounter" -> frameCounter;
            case "worldTime" -> worldTime;
            case "worldDay" -> worldDay;
            case "moonPhase" -> moonPhase;
            case "rainStrength" -> rainStrength;
            case "thunderStrength" -> thunderStrength;
            case "wetness" -> wetness;
            case "sunAngle" -> sunAngle01;
            case "shadowAngle" -> shadowAngle01;
            case "dimension" -> dimension;
            case "bedrockLevel" -> bedrockLevel;
            case "heightLimit" -> heightLimit;
            case "logicalHeightLimit" -> logicalHeightLimit;
            case "seaLevel" -> seaLevel;
            case "hasCeiling" -> hasCeiling;
            case "hasSkylight" -> hasSkylight;
            case "ambientLight" -> ambientLight;
            case "cloudHeight" -> cloudHeight;
            case "temperature" -> biomeTemperature;
            case "rainfall" -> rainfall;
            case "viewWidth" -> viewWidth;
            case "viewHeight" -> viewHeight;
            case "aspectRatio" -> aspectRatio;
            case "near" -> nearPlane;
            case "far" -> farPlane;
            case "blindness" -> blindness;
            case "darknessFactor" -> darknessFactor;
            case "darknessLightFactor" -> darknessLightFactor;
            case "nightVision" -> nightVision;
            case "screenBrightness" -> screenBrightness;
            case "isEyeInWater" -> isEyeInWater;
            case "isElytraFlying" -> isElytraFlying;
            case "eyeAltitude" -> eyeAltitude;
            case "biome" -> biomeId;
            case "biome_category" -> biomeCategory;
            case "biome_precipitation" -> biomePrecipitation;
            case "eyeBrightness" -> eyeBrightness[0];
            default -> 0.0;
        };
    }

    private static int dimensionOrdinal(ClientLevel level) {
        if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            return 0;
        }
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) {
            return -1;
        }
        if (level.dimension() == net.minecraft.world.level.Level.END) {
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
        float gamma = minecraft.player.getEffectBlendFactor(MobEffects.DARKNESS, partialTick) * scale;
        float darkness = Math.max(0.0f,
                (float) Math.cos((minecraft.player.tickCount - partialTick)
                        * (float) Math.PI * 0.025f) * 0.45f * gamma);
        return darkness * scale;
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
        isElytraFlying = 0;
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
        seaLevel = 0;
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

    private static void writeDefault(UniformRegistry.DefaultPolicy policy,
                                     String type, MappedBuffer target) {
        if (type.equals("mat4")) {
            for (int i = 0; i < 16; i++) {
                target.putFloat(i * 4, i % 5 == 0 ? 1.0f : 0.0f);
            }
            return;
        }
        if (type.startsWith("i")) {
            for (int i = 0; i < UniformRegistry.pipelineCount(type); i++) {
                target.putInt(i * 4, 0);
            }
            return;
        }
        float value = switch (policy) {
            case ONE -> 1.0f;
            case CLOUD_HEIGHT -> 192.0f;
            case NEAR_CLIP -> 0.05f;
            default -> 0.0f;
        };
        for (int i = 0; i < UniformRegistry.pipelineCount(type); i++) {
            target.putFloat(i * 4, value);
        }
    }

    private void writeMatrix(MappedBuffer target, Matrix4f matrix) {
        matrix.get(matrixScratch);
        for (int i = 0; i < matrixScratch.length; i++) {
            target.putFloat(i * 4, matrixScratch[i]);
        }
    }

    private static void writeFloatArray(MappedBuffer target, float[] values, int count) {
        for (int i = 0; i < count; i++) {
            target.putFloat(i * 4, values[i]);
        }
    }

    private static void writeVec3(MappedBuffer target, float x, float y, float z) {
        target.putFloat(0, x);
        target.putFloat(4, y);
        target.putFloat(8, z);
    }

    private static void writeVec3(MappedBuffer target, float[] values) {
        writeVec3(target, values[0], values[1], values[2]);
    }

    private static void writeInts(MappedBuffer target, int[] values) {
        for (int i = 0; i < values.length; i++) {
            target.putInt(i * 4, values[i]);
        }
    }

    private static float wrapped(float value, float limit) {
        return value >= limit ? value % limit : value;
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

    private static final class CameraOrigin {
        private double rawX;
        private double rawY;
        private double rawZ;
        private double previousRawX;
        private double previousRawY;
        private double previousRawZ;
        private float x;
        private float y;
        private float z;
        private float previousX;
        private float previousY;
        private float previousZ;
        private double shiftX;
        private double shiftZ;
        private boolean seeded;

        private void advance(double nextX, double nextY, double nextZ) {
            previousRawX = rawX;
            previousRawY = rawY;
            previousRawZ = rawZ;
            rawX = nextX;
            rawY = nextY;
            rawZ = nextZ;
            double currentShiftedX = rawX + shiftX;
            double currentShiftedZ = rawZ + shiftZ;
            double previousShiftedX = previousRawX + shiftX;
            double previousShiftedZ = previousRawZ + shiftZ;
            if (!seeded) {
                previousRawX = rawX;
                previousRawY = rawY;
                previousRawZ = rawZ;
                previousShiftedX = currentShiftedX;
                previousShiftedZ = currentShiftedZ;
                seeded = true;
            }
            double deltaX = cameraShift(currentShiftedX, previousShiftedX);
            double deltaZ = cameraShift(currentShiftedZ, previousShiftedZ);
            shiftX += deltaX;
            shiftZ += deltaZ;
            x = (float) (rawX + shiftX);
            y = (float) rawY;
            z = (float) (rawZ + shiftZ);
            previousX = (float) (previousRawX + shiftX);
            previousY = (float) previousRawY;
            previousZ = (float) (previousRawZ + shiftZ);
        }

        private void reset() {
            rawX = rawY = rawZ = previousRawX = previousRawY = previousRawZ = 0.0;
            x = y = z = previousX = previousY = previousZ = 0.0f;
            shiftX = shiftZ = 0.0;
            seeded = false;
        }

        private void seedPreviousFromCurrent() {
            previousRawX = rawX;
            previousRawY = rawY;
            previousRawZ = rawZ;
            previousX = x;
            previousY = y;
            previousZ = z;
        }
    }
}
