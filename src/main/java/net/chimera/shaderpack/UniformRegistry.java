package net.chimera.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>Slot limits by pipeline stage:
 * <ul>
 * <li>POST (composite/final): colortex0-3 read the current seam's color
 *     attachment (slots 0-3), shadowtex0 -> 5, depthtex0 -> 6.
 * <li>GEOMETRY (gbuffers_*): texture -> 0 (block atlas, set by the host
 *     renderer), lightmap -> 2 (host), shadowtex0 -> 5 (chimera maintains).
 *     colortexN and depthtex0 are NOT supported in geometry: colortexN there
 *     is self-feedback (sampling the buffer being written) and depthtex0 is
 *     the depth being written — both undefined under OptiFine's own rules.
 * </ul>
 *
 * <p>Slots 4 and 7 are reserved.
 */
public final class UniformRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private static final Pattern SAMPLER_DECL = Pattern.compile("uniform\\s+sampler2D\\s+(\\w+)\\s*;");

    private UniformRegistry() {}

    /** Shader stage of the pipeline a pack program is built onto. */
    public enum Stage {
        /** composite/final post seams (fullscreen, slot 0 = seam color). */
        POST,
        /** gbuffers_* terrain path (fixed-vertex inputs, host-set registry slots). */
        GEOMETRY
    }

    /** OptiFine texture uniform name -> fixed VTextureSelector slot (post stage). */
    public static final Map<String, Integer> NAME_TO_SLOT = Map.ofEntries(
            Map.entry("colortex0", 0),
            Map.entry("colortex1", 1),
            Map.entry("colortex2", 2),
            Map.entry("colortex3", 3),
            Map.entry("shadowtex0", 5),
            Map.entry("depthtex0", 6)
    );

    /** Geometry stage: the host's registry slots the terrain draw path fills. */
    public static final Map<String, Integer> GEOMETRY_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("lightmap", 2),
            Map.entry("shadowtex0", 5)
    );

    /**
     * The OptiFine sampler names a fragment program declares, in ascending
     * slot order, resolved against the stage's map. Names outside the stage's
     * map are skipped; duplicates collapse.
     */
    public static List<String> scanSamplerNames(String fragmentSource, Stage stage) {
        Map<String, Integer> bySlot = stage == Stage.GEOMETRY
                ? GEOMETRY_NAME_TO_SLOT
                : NAME_TO_SLOT;
        Map<String, Integer> found = new LinkedHashMap<>();
        Matcher matcher = SAMPLER_DECL.matcher(fragmentSource);
        while (matcher.find()) {
            String name = matcher.group(1);
            Integer slot = bySlot.get(name);
            if (slot != null) {
                found.putIfAbsent(name, slot);
            } else if (stage == Stage.GEOMETRY && (name.startsWith("colortex") || name.startsWith("depthtex"))) {
                LOGGER.warn("[chimera] pack: sampler {} unsupported in geometry stage, skipped", name);
            }
        }
        List<String> names = new ArrayList<>(found.keySet());
        names.sort(Comparator.comparingInt(bySlot::get));
        return names;
    }
}