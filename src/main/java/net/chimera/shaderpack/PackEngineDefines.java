package net.chimera.shaderpack;

import java.util.Collection;
import java.util.Collections;
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
    public static final String COMPUTE_SHADER = "COMPUTE_SHADER";
    public static final String SHADOWCOMP = "SHADOWCOMP";

    /**
     * Iris 1.10's fixed standard environment (StandardMacros), for the one
     * Minecraft version Chimera targets. Packs branch on these everywhere,
     * {@code MC_VERSION} above all: undefined, it reads as 0 and selects a
     * pack's pre-1.13 paths. Vendor, mipmap-level and texture-format macros
     * depend on the live device and options and are not defined here.
     */
    private static final Map<String, String> STANDARD = standardMacros();

    private PackEngineDefines() {}

    private static Map<String, String> standardMacros() {
        TreeMap<String, String> result = new TreeMap<>();
        result.put("MC_VERSION", "12111");
        result.put("IRIS_VERSION", "11007");
        result.put("MC_GL_VERSION", "460");
        result.put("MC_GLSL_VERSION", "460");
        result.put(osMacro(), "1");
        result.put("MAX_COLOR_BUFFERS", "16");
        result.put("IRIS_HAS_TRANSLUCENCY_SORTING", "1");
        result.put("IRIS_TAG_SUPPORT", "2");
        result.put("MC_NORMAL_MAP", "1");
        result.put("MC_SPECULAR_MAP", "1");
        result.put("MC_RENDER_QUALITY", "1.0");
        result.put("MC_SHADOW_QUALITY", "1.0");
        result.put("MC_HAND_DEPTH", "0.125");
        return Collections.unmodifiableMap(result);
    }

    private static String osMacro() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) return "MC_OS_WINDOWS";
        if (os.contains("mac")) return "MC_OS_MAC";
        if (os.contains("nux") || os.contains("nix")) return "MC_OS_LINUX";
        return "MC_OS_UNKNOWN";
    }

    /** The engine's standard macros alone, for sources preprocessed outside a program. */
    public static Map<String, String> standard() {
        return STANDARD;
    }

    public static Map<String, String> forPack(Map<String, String> packDefines) {
        return with(packDefines, false, false);
    }

    public static Map<String, String> forCustomImages(Map<String, String> packDefines) {
        return with(packDefines, true, false);
    }

    public static Map<String, String> forCompute(
            Map<String, String> packDefines,
            boolean customImages
    ) {
        return with(packDefines, customImages, true);
    }

    public static Set<String> lockedNames(
            Collection<String> packNames,
            boolean customImages,
            boolean compute
    ) {
        TreeSet<String> result = new TreeSet<>();
        if (packNames != null) {
            packNames.stream()
                    .filter(value -> value != null && value.matches("[A-Za-z_]\\w*"))
                    .forEach(result::add);
        }
        result.add(IS_IRIS);
        result.addAll(STANDARD.keySet());
        if (customImages) {
            result.add(CUSTOM_IMAGES);
        }
        if (compute) {
            result.add(COMPUTE_SHADER);
            result.add(SHADOWCOMP);
        }
        return Collections.unmodifiableSet(result);
    }

    private static Map<String, String> with(
            Map<String, String> packDefines,
            boolean customImages,
            boolean compute
    ) {
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
        result.putAll(STANDARD);
        if (customImages) {
            result.put(CUSTOM_IMAGES, "1");
        } else {
            result.remove(CUSTOM_IMAGES);
        }
        if (compute) {
            result.put(COMPUTE_SHADER, "1");
            result.put(SHADOWCOMP, "1");
        } else {
            result.remove(COMPUTE_SHADER);
            result.remove(SHADOWCOMP);
        }
        return Collections.unmodifiableMap(result);
    }
}
