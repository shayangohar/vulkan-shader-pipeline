package net.chimera.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the shader-source declaration block of an OptiFine-format pack:
 * colortex buffer formats (const int colortexNFormat = TOKEN;), shadow
 * settings, and the geometry draw-buffer count (DRAWBUFFERS). A
 * shaders.properties file next to the pass list overrides same-key entries.
 *
 * <p>Honored: colortex formats drive the HDR buffer format (any VK-code
 * mapping in FMT_TO_VK; other tokens skip to the default). The narrow M5.4
 * shadow resolution and distance settings are validated here before the
 * shadow framebuffer is created. Draw-buffer counts beyond 1 remain visible
 * to the caller but are not applied.
 */
public final class PackConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    /** const int colortexNFormat = TOKEN; (value 0-9 for colortex0-9). */
    private static final Pattern COLORTEX_FMT_CONST =
            Pattern.compile("(?m)^\\s*const\\s+int\\s+colortex(\\d+)Format\\s*=\\s*(\\w+)\\s*;\\s*$");
    private static final Pattern SHADOW_CONST = Pattern.compile(
            "(?m)^\\s*const\\s+(?:int|float)\\s+"
                    + "(shadowMapResolution|shadowDistance|shadowMapSize|shadowMapFov|shadowDistanceRenderMul)"
                    + "\\s*=\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)[fF]?\\s*;\\s*$");
    private static final Pattern DRAWBUFFERS_DEFINE = Pattern.compile("#define\\s+DRAWBUFFERS(\\d+)");
    private static final Pattern DRAWBUFFERS_COMMENT = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9,]+)\\s*\\*/");
    private static final Pattern FRAG_DATA = Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern PROPERTY_LINE = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*([^#\\s]+)\\s*$");
    /** shaders.properties key shape: colortexNFormat. */
    private static final Pattern COLORTEX_PROPERTY_KEY = Pattern.compile("^colortex(\\d+)Format$");

    /** OptiFine format token -> VK format code (verified: 37/97/109 match the pins). */
    public static final Map<String, Integer> FMT_TO_VK = Map.of(
            "RGBA8", 37,
            "RGBA16F", 97,
            "RGBA32F", 109
    );
    private static final Map<Integer, String> VK_TO_NAME;
    static {
        Map<Integer, String> reverse = new HashMap<>();
        FMT_TO_VK.forEach((token, code) -> reverse.put(code, token));
        VK_TO_NAME = Map.copyOf(reverse);
    }

    private PackConfig() {}

    public static final int DEFAULT_SHADOW_MAP_RESOLUTION = 2048;
    public static final float DEFAULT_SHADOW_DISTANCE = 128.0F;
    private static final int MIN_SHADOW_MAP_RESOLUTION = 128;
    private static final int MAX_SHADOW_MAP_RESOLUTION = 4096;
    private static final float MIN_SHADOW_DISTANCE = 16.0F;
    private static final float MAX_SHADOW_DISTANCE = 2048.0F;

    /** Validated M5.4 settings. The raw values remain available for logs. */
    public record ShadowSettings(
            int resolution,
            float distance,
            Map<String, String> rawValues,
            List<String> deviations
    ) {
        public ShadowSettings {
            rawValues = Collections.unmodifiableMap(new TreeMap<>(rawValues));
            deviations = List.copyOf(new TreeSet<>(deviations));
        }
    }

    public record PackConfigData(Map<Integer, Integer> colortexFormats, int drawBufferCount,
                                 ShadowSettings shadowSettings) {}

    public static PackConfigData parse(List<PackProgram> programs, Path shadersDir) {
        Map<Integer, Integer> colortexFormats = new HashMap<>();
        Map<String, String> shadowValues = new TreeMap<>();

        for (PackProgram program : programs.stream()
                .sorted(java.util.Comparator.comparing(PackProgram::name)).toList()) {
            String src = program.fragmentSource();
            Matcher fmt = COLORTEX_FMT_CONST.matcher(src);
            while (fmt.find()) {
                int slot;
                try {
                    slot = Integer.parseInt(fmt.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                String token = fmt.group(2);
                Integer code = FMT_TO_VK.get(token);
                if (code == null) {
                    LOGGER.warn("[chimera] pack {}: colortex{}Format {}: unhandled format, keeping default",
                            program.name(), slot, token);
                    continue;
                }
                Integer previous = colortexFormats.put(slot, code);
                if (previous != null && previous != code) {
                    LOGGER.info("[chimera] pack {}: colortex{}Format override {} -> {}", program.name(), slot, previous, code);
                }
            }
            collectShadowConstants(src, shadowValues);
            collectShadowConstants(program.vertexSource(), shadowValues);
        }

        // shaders.properties overrides (OptiFine packs may declare formats here).
        Path properties = shadersDir != null ? shadersDir.resolve("shaders.properties") : null;
        if (properties != null && Files.isRegularFile(properties)) {
            try {
                for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
                    Matcher m = PROPERTY_LINE.matcher(line);
                    if (!m.find()) {
                        continue;
                    }
                    String key = m.group(1);
                    String value = m.group(2);
                    Matcher colortexKey = COLORTEX_PROPERTY_KEY.matcher(key);
                    if (colortexKey.find()) {
                        int slot;
                        try {
                            slot = Integer.parseInt(colortexKey.group(1));
                        } catch (NumberFormatException e) {
                            LOGGER.warn("[chimera] pack properties: {} is not a valid colortex slot, skipped", key);
                            continue;
                        }
                        Integer code = FMT_TO_VK.get(value);
                        if (code == null) {
                            LOGGER.warn("[chimera] pack properties {}: unhandled format, keeping default", value);
                            continue;
                        }
                        colortexFormats.put(slot, code);
                        LOGGER.info("[chimera] pack properties: {}={}", key, value);
                    } else if (SHADOW_PROPERTIES.contains(key)) {
                        shadowValues.put(key, value);
                        LOGGER.info("[chimera] pack properties: {}={}", key, value);
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("[chimera] pack: cannot read {}: {}", properties, e.getMessage());
            }
        }

        // Draw-buffer count: the geometry program's (targets it writes).
        int drawBufferCount = 1;
        for (PackProgram program : programs) {
            if (program.name().equals("gbuffers_terrain")) {
                drawBufferCount = drawBufferCountOf(program.fragmentSource());
                break;
            }
        }

        return new PackConfigData(colortexFormats, drawBufferCount, validateShadowSettings(shadowValues));
    }

    private static final List<String> SHADOW_PROPERTIES = List.of(
            "shadowMapResolution", "shadowDistance", "shadowMapSize", "shadowMapFov",
            "shadowDistanceRenderMul");

    private static void collectShadowConstants(String source, Map<String, String> values) {
        Matcher shadow = SHADOW_CONST.matcher(source == null ? "" : source);
        while (shadow.find()) {
            values.put(shadow.group(1), shadow.group(2));
        }
    }

    private static ShadowSettings validateShadowSettings(Map<String, String> rawValues) {
        List<String> deviations = new ArrayList<>();
        int resolution = DEFAULT_SHADOW_MAP_RESOLUTION;
        String rawResolution = rawValues.get("shadowMapResolution");
        if (rawResolution != null) {
            try {
                double parsed = Double.parseDouble(rawResolution);
                if (parsed >= MIN_SHADOW_MAP_RESOLUTION && parsed <= MAX_SHADOW_MAP_RESOLUTION
                        && parsed == Math.rint(parsed)) {
                    resolution = (int) parsed;
                } else {
                    deviations.add("SHADOW_SETTING_DEFAULTED:shadowMapResolution");
                }
            } catch (NumberFormatException e) {
                deviations.add("SHADOW_SETTING_DEFAULTED:shadowMapResolution");
            }
        }

        float distance = DEFAULT_SHADOW_DISTANCE;
        String rawDistance = rawValues.get("shadowDistance");
        if (rawDistance != null) {
            try {
                float parsed = Float.parseFloat(rawDistance);
                if (Float.isFinite(parsed)
                        && parsed >= MIN_SHADOW_DISTANCE && parsed <= MAX_SHADOW_DISTANCE) {
                    distance = parsed;
                } else {
                    deviations.add("SHADOW_SETTING_DEFAULTED:shadowDistance");
                }
            } catch (NumberFormatException e) {
                deviations.add("SHADOW_SETTING_DEFAULTED:shadowDistance");
            }
        }

        for (String key : rawValues.keySet()) {
            if (!key.equals("shadowMapResolution") && !key.equals("shadowDistance")) {
                deviations.add("SHADOW_SETTING_UNSUPPORTED:" + key);
            }
        }
        return new ShadowSettings(resolution, distance, rawValues, deviations);
    }

    /** The number of draw targets a program writes: DRAWBUFFERS, RENDERTARGETS comment, or gl_FragData usage. */
    public static int drawBufferCountOf(String source) {
        Matcher def = DRAWBUFFERS_DEFINE.matcher(source);
        if (def.find()) {
            try {
                int n = Integer.parseInt(def.group(1));
                if (n >= 1) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher comment = DRAWBUFFERS_COMMENT.matcher(source);
        if (comment.find()) {
            int count = comment.group(1).split(",").length;
            if (count >= 1) {
                return count;
            }
        }
        int max = 0;
        Matcher fragData = FRAG_DATA.matcher(source);
        while (fragData.find()) {
            int index;
            try {
                index = Integer.parseInt(fragData.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            max = Math.max(max, index + 1);
        }
        return max >= 1 ? max : 1;
    }

    /** Token name for a VK code (for logs); "code N" when unknown. */
    public static String formatName(int vkCode) {
        String name = VK_TO_NAME.get(vkCode);
        return name != null ? name : "code " + vkCode;
    }
}
