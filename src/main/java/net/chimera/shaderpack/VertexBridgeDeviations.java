package net.chimera.shaderpack;

/**
 * The deviation names a program's vertex bridge reports. The generic {@code _BRIDGE_UNSUPPORTED}
 * code drives severity routing; the {@code _CONVERSION_FAILED:<reason>} code beside it names the
 * first concrete failure and never matches a severe prefix.
 */
final class VertexBridgeDeviations {
    private VertexBridgeDeviations() {}

    /** The generic refusal of a terrain-like or family program's vertex bridge. */
    static String unsupported(String name) {
        return switch (name) {
            case "shadow" -> "SHADOW_VERTEX_BRIDGE_UNSUPPORTED";
            case "shadow_entities", "shadow_block" -> "SHADOW_ENTITY_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_water" -> "TRANSLUCENT_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_particles", "gbuffers_particles_translucent"
                    -> "PARTICLE_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_block", "gbuffers_block_translucent", "gbuffers_damagedblock"
                    -> "BLOCK_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_hand", "gbuffers_hand_water"
                    -> "HAND_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_entities", "gbuffers_entities_translucent",
                    "gbuffers_spidereyes" -> "ENTITY_VERTEX_BRIDGE_UNSUPPORTED";
            default -> "TERRAIN_VERTEX_BRIDGE_UNSUPPORTED";
        };
    }

    /** An entity-family program's bridge, converted or refused. */
    static String entity(String name, boolean unsupported) {
        return switch (name) {
            case "gbuffers_block", "gbuffers_block_translucent", "gbuffers_damagedblock" -> unsupported
                    ? "BLOCK_VERTEX_BRIDGE_UNSUPPORTED" : "BLOCK_VERTEX_BRIDGE";
            case "gbuffers_hand", "gbuffers_hand_water" -> unsupported
                    ? "HAND_VERTEX_BRIDGE_UNSUPPORTED" : "HAND_VERTEX_BRIDGE";
            default -> unsupported
                    ? "ENTITY_VERTEX_BRIDGE_UNSUPPORTED" : "ENTITY_VERTEX_BRIDGE";
        };
    }

    /** An entity-family conversion's first failure, its message cut at 120 characters. */
    static String entityConversionFailure(String name, IllegalArgumentException failure) {
        String reason = failure.getMessage() == null ? "unknown" : failure.getMessage();
        int cut = Math.min(reason.length(), 120);
        return switch (name) {
            case "gbuffers_block", "gbuffers_block_translucent", "gbuffers_damagedblock" ->
                    "BLOCK_VERTEX_CONVERSION_FAILED:" + reason.substring(0, cut);
            case "gbuffers_hand", "gbuffers_hand_water" ->
                    "HAND_VERTEX_CONVERSION_FAILED:" + reason.substring(0, cut);
            default -> "ENTITY_VERTEX_CONVERSION_FAILED:" + reason.substring(0, cut);
        };
    }

    /** The failure beside {@code <LANE>_VERTEX_BRIDGE_UNSUPPORTED}: {@code <LANE>_VERTEX_CONVERSION_FAILED}. */
    static String conversionFailure(String unsupported, RuntimeException failure) {
        return unsupported.replace("_BRIDGE_UNSUPPORTED", "_CONVERSION_FAILED:") + safeReason(failure);
    }

    /** A failure message as a deviation argument: word characters, dots and dashes. */
    static String safeReason(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "source";
        }
        return message.replaceAll("[^A-Za-z0-9_.-]+", "_");
    }
}
