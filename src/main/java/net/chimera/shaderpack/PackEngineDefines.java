package net.chimera.shaderpack;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Engine-owned preprocessor capabilities shared by every pack source path.
 *
 * <p>These definitions describe the Chimera execution environment. Pack
 * settings can select shader options, but they cannot change the identity or
 * capability promises made by the engine.</p>
 */
public final class PackEngineDefines {
    public static final String IS_IRIS = "IS_IRIS";
    public static final String CUSTOM_IMAGES = "IRIS_FEATURE_CUSTOM_IMAGES";

    /**
     * Iris's fixed standard environment (StandardMacros) as Iris 1.8.14 defines it
     * on Minecraft 1.21.1, the one version Chimera targets. Packs branch on these
     * everywhere, {@code MC_VERSION} above all: undefined, it reads as 0 and
     * selects a pack's pre-1.13 paths. The device's macros (PackDeviceMacros) and
     * MC_MIPMAP_LEVEL join them in {@link #standard()}; texture-format macros are not
     * defined. IRIS_TAG_SUPPORT promises block.properties tags (PackMaterialResolver).
     * Iris's biome, category and precipitation symbols (IrisDefines) are macros too.
     */
    private static final Map<String, String> STANDARD = standardMacros();
    /** Minecraft's default mipmap levels, until a pack load reads the game's option. */
    private static volatile int mipmapLevel = 4;

    private PackEngineDefines() {}

    private static Map<String, String> standardMacros() {
        TreeMap<String, String> result = new TreeMap<>();
        result.put("MC_VERSION", "12101");
        result.put("IRIS_VERSION", "10814");
        result.put("MC_GL_VERSION", "460");
        result.put("MC_GLSL_VERSION", "460");
        result.put(osMacro(), "1");
        result.put("IRIS_HAS_TRANSLUCENCY_SORTING", "1");
        result.put("IRIS_TAG_SUPPORT", "2");
        result.put("MC_NORMAL_MAP", "1");
        result.put("MC_SPECULAR_MAP", "1");
        result.put("MC_RENDER_QUALITY", "1.0");
        result.put("MC_SHADOW_QUALITY", "1.0");
        result.put("MC_HAND_DEPTH", "0.125");
        for (var phase : PackRenderingPhase.values()) {
            result.put("MC_RENDER_STAGE_" + phase.name(), Integer.toString(phase.ordinal()));
        }
        // Iris's IrisDefines joins them: BIOME_*, CAT_* and PPT_*, from the table expressions read.
        result.putAll(BiomeIds.macros());
        return Collections.unmodifiableMap(result);
    }

    private static String osMacro() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "MC_OS_WINDOWS";
        if (os.contains("mac")) return "MC_OS_MAC";
        if (os.contains("nux") || os.contains("nix")) return "MC_OS_LINUX";
        return "MC_OS_UNKNOWN";
    }

    /**
     * The engine's standard macros with the device's and the mipmap level, alone, for sources
     * preprocessed outside a program.
     */
    public static Map<String, String> standard() {
        TreeMap<String, String> result = new TreeMap<>(STANDARD);
        result.put("MC_MIPMAP_LEVEL", Integer.toString(mipmapLevel));
        result.putAll(PackDeviceMacros.macros(PackDevice.current()));
        return Collections.unmodifiableMap(result);
    }

    /** The game's mipmap levels option, which Iris gives packs as MC_MIPMAP_LEVEL. */
    public static void setMipmapLevel(int levels) {
        mipmapLevel = levels;
    }

    /** The mipmap level the macros carry now; a pack load pins it (PackSource.LoadResult). */
    static int mipmapLevel() {
        return mipmapLevel;
    }

    public static Map<String, String> forPack(Map<String, String> packDefines) {
        return with(packDefines, false);
    }

    public static Map<String, String> forCustomImages(Map<String, String> packDefines) {
        return with(packDefines, true);
    }

    public static Set<String> lockedNames(Collection<String> packNames, boolean customImages) {
        TreeSet<String> result = new TreeSet<>();
        if (packNames != null) {
            packNames.stream()
                    .filter(value -> value != null && value.matches("[A-Za-z_]\\w*"))
                    .forEach(result::add);
        }
        result.add(IS_IRIS);
        result.addAll(standard().keySet());
        if (customImages) {
            result.add(CUSTOM_IMAGES);
        }
        return Collections.unmodifiableSet(result);
    }

    private static Map<String, String> with(Map<String, String> packDefines, boolean customImages) {
        TreeMap<String, String> result = new TreeMap<>();
        if (packDefines != null) {
            packDefines.forEach((name, value) -> {
                if (name != null && name.matches("[A-Za-z_]\\w*")
                        && value != null && !value.isBlank()) {
                    result.put(name, value.trim());
                }
            });
        }
        result.put(IS_IRIS, "1");
        result.putAll(standard());
        if (customImages) {
            result.put(CUSTOM_IMAGES, "1");
        } else {
            result.remove(CUSTOM_IMAGES);
        }
        return Collections.unmodifiableMap(result);
    }
}
