package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shadow culling Iris ShadowRenderer.createShadowFrustum chooses for a pack, and its
 * distances. The pack decides it once per session; the render distance, which Iris reads every
 * frame, is the only per-frame input.
 *
 * <p>{@code shadow.culling} in shaders.properties: {@code false} culls by distance only,
 * {@code true} uses the advanced frustum, {@code reversed} or {@code safe_zone} the safe-zone
 * frustum. Unset means advanced: Iris falls back to distance culling for an unset value only
 * when the shadow program has a geometry stage (voxelization), and Chimera never runs such a
 * program (UNSUPPORTED_PACK_STAGE).</p>
 *
 * <p>Distances are half-sizes in blocks of Iris's camera-centred BoxCuller.
 * {@code shadowDistanceRenderMul} defaults to -1, and a negative multiplier means the player's
 * shadow render distance, which Chimera does not offer, so Iris's default of 32 chunks stands
 * in for it.</p>
 */
public record ShadowCullingPlan(
        State state,
        float halfPlaneLength,
        float renderMultiplier,
        float entityMultiplier,
        float voxelDistance,
        List<String> deviations
) {
    /** Iris IrisVideoSettings.shadowDistance's default, in chunks. */
    static final int IRIS_SHADOW_RENDER_DISTANCE_CHUNKS = 32;

    /** Iris ShadowCullState. */
    public enum State { DEFAULT, ADVANCED, SAFE_ZONE, DISTANCE }

    /** The frustum Iris builds: NonCullingFrustum, BoxCullingFrustum, Advanced or SafeZone. */
    public enum Mode { NONE, DISTANCE, ADVANCED, SAFE_ZONE }

    /**
     * One frustum. {@code box} is the BoxCuller half-size, infinite when Iris has none; in
     * SAFE_ZONE it is the outer distance box. {@code safeZone} is the half-size of the box
     * SAFE_ZONE always draws, whatever the camera sees.
     */
    public record Culler(Mode mode, double box, double safeZone) {
        public static final Culler NONE = new Culler(Mode.NONE, Double.POSITIVE_INFINITY, 0.0);

        public String describe() {
            return switch (mode) {
                case NONE -> "none";
                case DISTANCE -> "distance box=" + box;
                case ADVANCED -> "advanced box=" + (Double.isInfinite(box) ? "none" : Double.toString(box));
                case SAFE_ZONE -> "safe zone=" + safeZone + " box=" + box;
            };
        }
    }

    public ShadowCullingPlan {
        state = state == null ? State.DEFAULT : state;
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    public static ShadowCullingPlan of(Map<String, String> properties, PackConfig.ShadowSettings settings) {
        List<String> deviations = new ArrayList<>();
        State state = State.DEFAULT;
        String value = properties == null ? null : properties.get("shadow.culling");
        if (value != null) {
            switch (value.trim()) {
                case "false" -> state = State.DISTANCE;
                case "true" -> state = State.ADVANCED;
                case "reversed", "safe_zone" -> state = State.SAFE_ZONE;
                // Iris logs an unrecognized value and keeps the default.
                default -> deviations.add("SHADOW_CULLING_DIRECTIVE_MALFORMED:" + value.trim());
            }
        }
        return new ShadowCullingPlan(state, settings.distance(), settings.cullingRenderMultiplier(),
                settings.entityDistanceMultiplier(), settings.voxelDistance(), deviations);
    }

    /** The terrain frustum: shadow sections, and the block entities inside them. */
    public Culler terrain(int renderDistanceBlocks) {
        return create(renderMultiplier, renderDistanceBlocks);
    }

    /**
     * The entity frustum. Iris reuses the terrain one unless entityShadowDistanceMul is set to
     * something other than 1 and not negative.
     */
    public Culler entities(int renderDistanceBlocks) {
        if (entityMultiplier == 1.0F || entityMultiplier < 0.0F) return terrain(renderDistanceBlocks);
        return create(renderMultiplier * entityMultiplier, renderDistanceBlocks);
    }

    private Culler create(float multiplier, int renderDistanceBlocks) {
        if (state == State.DISTANCE) {
            double distance = halfPlaneLength * multiplier;
            if (distance <= 0.0 || distance > renderDistanceBlocks) return Culler.NONE;
            return new Culler(Mode.DISTANCE, distance, 0.0);
        }
        boolean safeZone = state == State.SAFE_ZONE;
        // Iris assumes the multiplier is meant to be 1 when safe-zone culling is on.
        if (safeZone && multiplier < 0.0F) multiplier = 1.0F;
        double distance = (safeZone ? voxelDistance : halfPlaneLength) * multiplier;
        if (multiplier < 0.0F) distance = IRIS_SHADOW_RENDER_DISTANCE_CHUNKS * 16.0;
        if (safeZone) {
            return new Culler(Mode.SAFE_ZONE, halfPlaneLength * (double) multiplier, distance);
        }
        // At or past the render distance Iris builds no BoxCuller. A zero distance keeps its
        // BoxCuller(0) too: Iris's CullEverythingFrustum there is overwritten by the advanced one.
        double box = distance >= renderDistanceBlocks ? Double.POSITIVE_INFINITY : distance;
        return new Culler(Mode.ADVANCED, box, 0.0);
    }
}
