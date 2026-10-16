package net.chimera.shaderpack;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Iris's shader-pack feature flags ({@code iris.features.required} and {@code iris.features.optional}),
 * kept as Iris keeps them, over the features Chimera provides. Iris reads shaders.properties with
 * {@code IRIS_FEATURE_<flag>} defined for every flag it can provide, defines it in program sources for
 * each optional flag the pack lists that it provides (spelled as the pack wrote it), and refuses a pack
 * that requires one it cannot provide. A flag whose feature Chimera lacks stays undefined, so a pack
 * takes the path it takes without that feature instead of one Chimera cannot run.
 */
public final class PackFeatureFlags {
    /**
     * The features Chimera provides as Iris does. Not provided: separate hardware shadow samplers,
     * shadowcolor2-7, tessellation, the weather directive and {@code mc_chunkFade} (FADE_VARIABLE).
     */
    static final Set<String> PROVIDED = Set.of("PER_BUFFER_BLENDING", "COMPUTE_SHADERS", "ENTITY_TRANSLUCENT",
            "REVERSED_CULLING", "BLOCK_EMISSION_ATTRIBUTE", "SSBO", "TEXTURE_FILTERING");
    /**
     * Custom images run only where Chimera can produce them, so this flag is offered per pack once
     * that is known ({@link PackEngineDefines#forCustomImages}) rather than promised up front. A pack
     * that requires it still loads; the programs that need images fall back.
     */
    static final String CUSTOM_IMAGES = "CUSTOM_IMAGES";
    private static final String PREFIX = "IRIS_FEATURE_";
    private static final Map<String, String> PROPERTY_MACROS = propertyMacroTable();

    private PackFeatureFlags() {}

    private static Map<String, String> propertyMacroTable() {
        Map<String, String> macros = new TreeMap<>();
        PROVIDED.forEach(flag -> macros.put(PREFIX + flag, "1"));
        return Collections.unmodifiableMap(macros);
    }

    /** The feature macros shaders.properties is read with. */
    static Map<String, String> propertyMacros() {
        return PROPERTY_MACROS;
    }

    /** Program-source defines for the optional flags Chimera provides, spelled as the pack wrote them. */
    static Map<String, String> sourceDefines(Collection<String> optional) {
        Map<String, String> defines = new TreeMap<>();
        for (String flag : optional) {
            if (PROVIDED.contains(canonical(flag)) && flag.matches("\\w+")) defines.put(PREFIX + flag, "1");
        }
        return defines;
    }

    /** The required flags Chimera cannot provide, unknown names included, as the pack wrote them. */
    static Set<String> unprovided(Collection<String> required) {
        Set<String> missing = new TreeSet<>();
        for (String flag : required) {
            String name = canonical(flag);
            if (!PROVIDED.contains(name) && !CUSTOM_IMAGES.equals(name)) missing.add(flag);
        }
        return missing;
    }

    /** A flag as Iris names it to the player: {@code TESSELLATION_SHADERS} reads "Tessellation shaders". */
    public static String readableName(String flag) {
        String words = canonical(flag).replace('_', ' ').toLowerCase(Locale.ROOT);
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** The flag's Iris name; Iris also takes the old misspelling TESSELATION_SHADERS. */
    private static String canonical(String flag) {
        String name = flag.toUpperCase(Locale.ROOT);
        return name.equals("TESSELATION_SHADERS") ? "TESSELLATION_SHADERS" : name;
    }
}
