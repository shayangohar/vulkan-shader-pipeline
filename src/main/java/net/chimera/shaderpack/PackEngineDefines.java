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

    private PackEngineDefines() {}

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
