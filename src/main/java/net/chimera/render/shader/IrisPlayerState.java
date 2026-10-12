package net.chimera.render.shader;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Iris's player, HUD, vehicle, clock and Distant Horizons uniforms, captured once per frame. Each
 * value follows the Iris 1.21.11 supplier it is named after ({@code CommonUniforms},
 * {@code IrisExclusiveUniforms}, {@code IrisTimeUniforms}, {@code DHCompat}), including the -1
 * Iris reports for player stats outside survival. Distant Horizons is not supported, so the
 * {@code dh*} values are the ones Iris serves when DH is absent.
 */
final class IrisPlayerState {
    /** Every scalar or vector name this snapshot answers; matrices stay with the frame state. */
    static final Set<String> NAMES = Set.of(
            "hideGUI", "isRightHanded", "feetInWater", "inSwimmingAnimation", "isRiding",
            "vehicleInWater", "firstPersonCamera", "isSpectator", "heavyFog",
            "currentPlayerHealth", "maxPlayerHealth", "currentPlayerHunger", "maxPlayerHunger",
            "currentPlayerArmor", "maxPlayerArmor", "currentPlayerAir", "maxPlayerAir",
            "playerMood", "pi", "lastFrameTime", "chunkFadeTimeInv",
            "textureFilteringMode", "anisotropicFiltering", "currentColorSpace",
            "vehicleLookVector", "relativeVehiclePosition", "playerBodyVector",
            "currentDate", "currentTime", "currentYearTime",
            "dhNearPlane", "dhFarPlane", "dhRenderDistance");

    /** DHCompat's near and far planes when Distant Horizons is absent. */
    static final float DH_ABSENT_PLANE = 0.01f;

    private boolean hideGui;
    private boolean rightHanded = true;
    private boolean feetInWater;
    private boolean swimming;
    private boolean riding;
    private boolean vehicleInWater;
    private boolean firstPerson = true;
    private boolean spectator;
    private boolean heavyFog;
    private float health = -1.0f;
    private float maxHealth = -1.0f;
    private float hunger = -1.0f;
    private float armor = -1.0f;
    private float air = -1.0f;
    private float maxAir = -1.0f;
    private float mood;
    private float lastFrameTime;
    private float chunkFadeTimeInv;
    private int textureFilteringMode;
    private int anisotropicFiltering;
    private int renderDistance;
    private final float[] vehicleLook = new float[3];
    private final float[] relativeVehicle = new float[3];
    private final float[] bodyVector = new float[3];
    private final int[] date = new int[3];
    private final int[] time = new int[3];
    private final int[] yearTime = new int[2];

    void capture(Minecraft minecraft, float partialTick, double cameraX, double cameraY, double cameraZ,
                 float frameTime) {
        this.lastFrameTime = frameTime;
        this.hideGui = minecraft.options.hideGui;
        this.rightHanded = minecraft.options.mainHand().get() == HumanoidArm.RIGHT;
        CameraType camera = minecraft.options.getCameraType();
        this.firstPerson = camera != CameraType.THIRD_PERSON_BACK && camera != CameraType.THIRD_PERSON_FRONT;
        this.chunkFadeTimeInv = (float) (1.0 / (minecraft.options.chunkSectionFadeInTime().get() * 1000.0));
        TextureFilteringMethod filtering = minecraft.options.textureFiltering().get();
        this.textureFilteringMode = switch (filtering) {
            case NONE -> 0;
            case RGSS -> 1;
            case ANISOTROPIC -> 2;
        };
        this.anisotropicFiltering = filtering == TextureFilteringMethod.ANISOTROPIC
                ? minecraft.options.maxAnisotropyValue() : 0;
        this.renderDistance = minecraft.options.getEffectiveRenderDistance();
        this.heavyFog = minecraft.level != null && minecraft.gui != null
                && minecraft.gui.getBossOverlay().shouldCreateWorldFog();
        this.spectator = minecraft.gameMode != null
                && minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR;

        LocalPlayer player = minecraft.player;
        boolean survival = player != null && minecraft.gameMode != null
                && minecraft.gameMode.getPlayerMode().isSurvival();
        this.health = survival ? player.getHealth() / player.getMaxHealth() : -1.0f;
        this.maxHealth = survival ? player.getMaxHealth() : -1.0f;
        this.hunger = survival ? player.getFoodData().getFoodLevel() / 20.0f : -1.0f;
        this.armor = survival ? player.getArmorValue() / 50.0f : -1.0f;
        this.air = survival ? (float) player.getAirSupply() / (float) player.getMaxAirSupply() : -1.0f;
        this.maxAir = survival ? player.getMaxAirSupply() : -1.0f;
        this.feetInWater = player != null && player.isInShallowWater();
        this.swimming = player != null && player.isSwimming();
        this.riding = player != null && player.isPassenger();

        Entity vehicle = player == null ? null : player.getVehicle();
        this.vehicleInWater = vehicle != null && vehicle.isInShallowWater();
        set(this.vehicleLook, vehicle == null ? null : vehicle.getForward());
        if (vehicle == null) {
            set(this.relativeVehicle, null);
        } else {
            Vec3 position = vehicle.getPosition(partialTick);
            this.relativeVehicle[0] = (float) (cameraX - position.x);
            this.relativeVehicle[1] = (float) (cameraY - position.y);
            this.relativeVehicle[2] = (float) (cameraZ - position.z);
        }
        Entity cameraEntity = minecraft.getCameraEntity();
        set(this.bodyVector, cameraEntity == null ? null : cameraEntity.getForward());
        this.mood = cameraEntity instanceof LocalPlayer local
                ? Math.clamp(local.getCurrentMood(), 0.0f, 1.0f) : 0.0f;

        LocalDateTime now = LocalDateTime.now();
        this.date[0] = now.getYear();
        this.date[1] = now.getMonthValue();
        this.date[2] = now.getDayOfMonth();
        this.time[0] = now.getHour();
        this.time[1] = now.getMinute();
        this.time[2] = now.getSecond();
        int elapsed = (now.getDayOfYear() - 1) * 86400 + now.getHour() * 3600 + now.getMinute() * 60
                + now.getSecond();
        this.yearTime[0] = elapsed;
        this.yearTime[1] = now.toLocalDate().lengthOfYear() * 86400 - elapsed;
    }

    /** No level: Iris's values with no player and no vehicle. */
    void clear() {
        this.feetInWater = this.swimming = this.riding = this.vehicleInWater = false;
        this.spectator = this.heavyFog = false;
        this.health = this.maxHealth = this.hunger = this.armor = this.air = this.maxAir = -1.0f;
        this.mood = 0.0f;
        set(this.vehicleLook, null);
        set(this.relativeVehicle, null);
        set(this.bodyVector, null);
    }

    /** The scalar, or one component of the vector, Iris serves under a name. */
    double value(String name, int component) {
        int c = Math.max(component, 0);
        return switch (name) {
            case "hideGUI" -> flag(this.hideGui);
            case "isRightHanded" -> flag(this.rightHanded);
            case "feetInWater" -> flag(this.feetInWater);
            case "inSwimmingAnimation" -> flag(this.swimming);
            case "isRiding" -> flag(this.riding);
            case "vehicleInWater" -> flag(this.vehicleInWater);
            case "firstPersonCamera" -> flag(this.firstPerson);
            case "isSpectator" -> flag(this.spectator);
            case "heavyFog" -> flag(this.heavyFog);
            case "currentPlayerHealth" -> this.health;
            case "maxPlayerHealth" -> this.maxHealth;
            case "currentPlayerHunger" -> this.hunger;
            case "maxPlayerHunger" -> 20.0;
            case "currentPlayerArmor" -> this.armor;
            case "maxPlayerArmor" -> 50.0;
            case "currentPlayerAir" -> this.air;
            case "maxPlayerAir" -> this.maxAir;
            case "playerMood" -> this.mood;
            case "pi" -> (float) Math.PI;
            case "lastFrameTime" -> this.lastFrameTime;
            case "chunkFadeTimeInv" -> this.chunkFadeTimeInv;
            case "textureFilteringMode" -> this.textureFilteringMode;
            case "anisotropicFiltering" -> this.anisotropicFiltering;
            // IrisVideoSettings.colorSpace defaults to SRGB, ordinal 0; Chimera has no colour-space setting.
            case "currentColorSpace" -> 0;
            case "vehicleLookVector" -> c < 3 ? this.vehicleLook[c] : Double.NaN;
            case "relativeVehiclePosition" -> c < 3 ? this.relativeVehicle[c] : Double.NaN;
            case "playerBodyVector" -> c < 3 ? this.bodyVector[c] : Double.NaN;
            case "currentDate" -> c < 3 ? this.date[c] : Double.NaN;
            case "currentTime" -> c < 3 ? this.time[c] : Double.NaN;
            case "currentYearTime" -> c < 2 ? this.yearTime[c] : Double.NaN;
            case "dhNearPlane", "dhFarPlane" -> DH_ABSENT_PLANE;
            case "dhRenderDistance" -> this.renderDistance;
            default -> Double.NaN;
        };
    }

    /** Writes a name in its declared type: integer types as ints, the rest as floats. */
    void write(String name, String type, MappedBuffer target) {
        int count = componentCount(type);
        boolean integral = type.startsWith("i") || type.equals("bool");
        for (int component = 0; component < count; component++) {
            double value = value(name, count == 1 ? -1 : component);
            if (integral) {
                target.putInt(component * 4, (int) value);
            } else {
                target.putFloat(component * 4, (float) value);
            }
        }
    }

    private static int componentCount(String type) {
        return switch (type) {
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            default -> 1;
        };
    }

    private static double flag(boolean value) {
        return value ? 1.0 : 0.0;
    }

    private static void set(float[] destination, Vec3 value) {
        destination[0] = value == null ? 0.0f : (float) value.x;
        destination[1] = value == null ? 0.0f : (float) value.y;
        destination[2] = value == null ? 0.0f : (float) value.z;
    }
}
