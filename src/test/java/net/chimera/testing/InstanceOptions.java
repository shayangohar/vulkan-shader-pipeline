package net.chimera.testing;

import java.util.Map;

/**
 * The shader options of the CHIMERA instance (its instance.cfg JvmArgs). A plan resolves pack
 * branches from them, so a test that compares against runtime must plan with the same set.
 */
public enum InstanceOptions {
    /** The options that decide Complementary's target routes. */
    ROUTES(Map.of("RP_MODE", "3", "ADVANCED_MATERIALS", "1", "BLOCK_REFLECT_QUALITY", "3")),
    /** The complete set; a subset silently drops samplers such as the MCBL lighttex and noisetex. */
    FULL(Map.of("MULTICOLORED_BLOCKLIGHT", "1", "MCBL_DISTANCE", "128", "MCBL_HALF_HEIGHT", "1",
            "COLORED_LIGHTING", "128", "WORLD_SPACE_REFLECTIONS", "1", "RP_MODE", "3",
            "ADVANCED_MATERIALS", "1", "MATERIAL_FORMAT", "1", "BLOCK_REFLECT_QUALITY", "3"));

    private final Map<String, String> values;

    InstanceOptions(Map<String, String> values) {
        this.values = values;
    }

    /** Sets each option as the {@code chimera.option.<name>} override the pack settings read. */
    public void apply() {
        values.forEach((name, value) -> System.setProperty("chimera.option." + name, value));
    }

    public void clear() {
        values.keySet().forEach(name -> System.clearProperty("chimera.option." + name));
    }
}
