package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a pack draws particles against its deferred passes, read from shaders.properties as Iris
 * ShaderProperties reads it and resolved as IrisRenderingPipeline resolves it:
 * <ul>
 *   <li>A key starting {@code particles.ordering} names {@code before}, {@code mixed} or
 *       {@code after}; any other value leaves the ordering unset.</li>
 *   <li>{@code separateEntityDraws}, true or false, sets mixed.</li>
 *   <li>{@code particles.before.deferred = true} sets before while nothing else has.</li>
 *   <li>Left unset, particles draw after, or mixed with separateEntityDraws. Iris tests for deferred
 *       programs there, but its deferred array always has 100 slots, so every pack passes.</li>
 * </ul>
 * Iris applies the keys in the order they first appear, each with its last value
 * (OrderBackedProperties). superDuperVanilla's {@code particles.ordering = after} is undone by the
 * {@code separateEntityDraws} below it.
 */
public record ParticleDirectives(Ordering ordering, boolean separateEntityDraws, List<String> deviations) {
    /** A pack's particles with no ordering keys: all after the deferred passes. */
    public static final ParticleDirectives DEFAULTS = new ParticleDirectives(Ordering.AFTER, false, List.of());
    private static final String ORDERING = "particles.ordering";
    private static final String BEFORE_DEFERRED = "particles.before.deferred";
    private static final String SEPARATE_ENTITY_DRAWS = "separateEntityDraws";

    /** Iris ParticleRenderingSettings, without UNSET, which resolves before a frame draws. */
    public enum Ordering {
        BEFORE, MIXED, AFTER;

        /**
         * Whether a particle draws before translucent terrain and the deferred passes, in
         * NeoForge's first particle call; otherwise it draws in the call after translucent terrain.
         */
        public boolean early(boolean translucent) {
            return this == BEFORE || this == MIXED && !translucent;
        }

        /** Whether NeoForge's early or late particle call draws anything; Iris skips the call that doesn't. */
        public boolean drawsIn(boolean earlyCall) {
            return this == MIXED || (this == BEFORE) == earlyCall;
        }
    }

    public ParticleDirectives {
        deviations = deviations == null ? List.of() : List.copyOf(deviations);
    }

    /**
     * The directives of shaders.properties entries in Iris's order: each key where it first
     * appears, with its last value, as a LinkedHashMap keeps them.
     */
    static ParticleDirectives fromOrderedProperties(Map<String, String> properties) {
        List<String> deviations = new ArrayList<>();
        Ordering ordering = null;
        boolean separate = false;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue().trim();
            if (key.startsWith(ORDERING)) {
                ordering = named(value, deviations);
                continue;
            }
            if (!key.equals(BEFORE_DEFERRED) && !key.equals(SEPARATE_ENTITY_DRAWS)) continue;
            Boolean on = flag(value);
            if (on == null) {
                deviations.add("PARTICLE_DIRECTIVE_MALFORMED:" + key);
            } else if (key.equals(SEPARATE_ENTITY_DRAWS)) {
                separate = on;
                ordering = Ordering.MIXED;
            } else if (on && ordering == null) {
                ordering = Ordering.BEFORE;
            }
        }
        if (ordering == null) ordering = separate ? Ordering.MIXED : Ordering.AFTER;
        return new ParticleDirectives(ordering, separate, deviations);
    }

    /** Iris's UNSET is a valid name too; it leaves the ordering to the default. */
    private static Ordering named(String value, List<String> deviations) {
        String name = value.toUpperCase(Locale.ROOT);
        if (name.equals("UNSET")) return null;
        try {
            return Ordering.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            deviations.add("PARTICLE_ORDERING_UNKNOWN:" + value);
            return null;
        }
    }

    private static Boolean flag(String value) {
        return switch (value) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> null;
        };
    }
}
