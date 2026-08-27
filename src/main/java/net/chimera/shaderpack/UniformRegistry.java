package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OptiFine uniform names -> VTextureSelector binding slots. Pack fragment
 * programs declare texture uniforms by OptiFine names (colortexN, shadowtexN,
 * depthtexN); chimera binds textures at fixed slots, so the converter
 * rewrites each declaration to an explicit layout(binding = N) where N is the
 * sampler's position in the generated pipeline config (see PackPipelines) —
 * binding numbers are config-array order, texture slots are these values.
 *
 * <p>Slots 4 and 7 are reserved and never bound in the wedge.
 */
public final class UniformRegistry {
    private static final Pattern SAMPLER_DECL = Pattern.compile("uniform\\s+sampler2D\\s+(\\w+)\\s*;");

    private UniformRegistry() {}

    /** OptiFine texture uniform name -> fixed VTextureSelector slot. */
    public static final Map<String, Integer> NAME_TO_SLOT = Map.ofEntries(
            Map.entry("colortex0", 0),
            Map.entry("colortex1", 1),
            Map.entry("colortex2", 2),
            Map.entry("colortex3", 3),
            Map.entry("shadowtex0", 5),
            Map.entry("depthtex0", 6)
    );

    /**
     * The OptiFine sampler names a fragment program declares, in ascending
     * slot order. Names outside NAME_TO_SLOT are skipped; duplicates collapse.
     */
    public static List<String> scanSamplerNames(String fragmentSource) {
        Map<String, Integer> found = new LinkedHashMap<>();
        Matcher matcher = SAMPLER_DECL.matcher(fragmentSource);
        while (matcher.find()) {
            String name = matcher.group(1);
            Integer slot = NAME_TO_SLOT.get(name);
            if (slot != null) {
                found.putIfAbsent(name, slot);
            }
        }
        List<String> names = new ArrayList<>(found.keySet());
        names.sort(Comparator.comparingInt(NAME_TO_SLOT::get));
        return names;
    }
}